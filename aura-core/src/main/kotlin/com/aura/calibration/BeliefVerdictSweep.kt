package com.aura.calibration

import android.util.Log
import com.aura.curiosity.OpenQuestionDao
import com.aura.curiosity.OpenQuestionEntity
import com.aura.memory.MemoryDao
import com.aura.providers.ChatOptions
import com.aura.providers.CheapModelResolver
import com.aura.providers.ProviderMessage
import com.aura.providers.ProviderRegistry
import com.aura.providers.ResponseSchema
import com.aura.providers.StructuredJson
import com.aura.data.UserPreferences
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Turns answered verification questions into verdicts.
 *
 * The user answers in their own words — "no, never was", "it was, I moved in
 * March", "yeah still right" — and the difference between the first two decides
 * whether the claim counts as a miss or is excluded from scoring entirely. That
 * is a judgment about language, so it is the one place in this feature a model
 * is unavoidable.
 *
 * **Unattended.** Nobody waits on a verdict; it runs on the decay worker's
 * schedule, carries `attended = false`, and is capped by `BackgroundBudget` like
 * every other timed caller. A budget-exhausted pass records nothing and the
 * question stays answered-but-ungraded, so the next pass picks it up — which is
 * why the sweep keys off "no resolution exists" rather than a watermark.
 *
 * **`unclear` writes nothing.** An answer the model cannot place is left
 * ungraded forever rather than guessed at. The sample is small by design and a
 * guessed verdict is indistinguishable from a real one once it is a row.
 */
@Singleton
class BeliefVerdictSweep @Inject constructor(
    private val openQuestionDao: OpenQuestionDao,
    private val memoryDao: MemoryDao,
    private val resolutions: ClaimResolutionStore,
    private val registry: ProviderRegistry,
    private val cheapModelResolver: CheapModelResolver,
    private val userPreferences: UserPreferences,
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }

    data class Outcome(val graded: Int = 0, val reason: String = "")

    suspend fun sweep(now: Long = System.currentTimeMillis()): Outcome {
        val answered = runCatching {
            openQuestionDao.byStatus(OpenQuestionEntity.STATUS_ANSWERED, ANSWERED_SCAN)
        }
            .onFailure { Log.w(TAG, "could not read answered questions: ${it.message}", it) }
            .getOrDefault(emptyList())
            .filter { it.kind == OpenQuestionEntity.KIND_VERIFICATION }
        if (answered.isEmpty()) return Outcome(reason = "no answered verification questions")

        val alreadyResolved = resolutions.resolvedBeliefIds()
        val pending = answered.filter { it.subjectId !in alreadyResolved }
        if (pending.isEmpty()) return Outcome(reason = "nothing new to grade")

        val base = userPreferences.backgroundModel.first()?.takeIf { it.isNotBlank() }
            ?: return Outcome(reason = "no background model configured")
        val model = cheapModelResolver.resolve(base, base)
            ?: return Outcome(reason = "no model available")

        var graded = 0
        for (question in pending.take(MAX_PER_SWEEP)) {
            val answer = question.answerMemoryId
                ?.let { id -> runCatching { memoryDao.getById(id)?.content }.getOrNull() }
                ?.takeIf { it.isNotBlank() }
                ?: continue

            val verdict = classify(question.question, answer, model) ?: continue
            if (verdict == VERDICT_UNCLEAR) continue

            val written = resolutions.record(
                beliefId = question.subjectId,
                verdict = verdict,
                verdictSource = ClaimResolutionEntity.SOURCE_CHAT_ANSWER,
                note = answer.take(NOTE_CAP),
                now = now,
            )
            if (written != null) graded++
        }

        return Outcome(
            graded = graded,
            reason = if (graded == 0) "nothing gradable" else "graded $graded claim(s)",
        )
    }

    private suspend fun classify(question: String, answer: String, model: String): String? =
        StructuredJson.requestJson(
            registry = registry,
            modelId = model,
            messages = listOf(
                ProviderMessage(role = ProviderMessage.Role.system, content = SYSTEM_PROMPT),
                ProviderMessage(
                    role = ProviderMessage.Role.user,
                    content = "Question asked: $question\n\nWhat they said: ${answer.take(ANSWER_CAP)}",
                ),
            ),
            // Unattended: nobody is waiting, so the daily background budget
            // governs this call. See the class KDoc.
            options = ChatOptions(temperature = 0.0, maxTokens = 120, attended = false),
            schema = VERDICT_SCHEMA,
            timeoutMs = TIMEOUT_MS,
            tag = TAG,
        ) { cleaned ->
            runCatching { json.decodeFromString(VerdictAnswer.serializer(), cleaned) }
                .onFailure { Log.w(TAG, "unparseable verdict: ${it.message}", it) }
                .getOrNull()
                ?.verdict
                ?.trim()
                ?.lowercase()
                ?.takeIf { it in ALLOWED }
        }

    private companion object {
        const val TAG = "BeliefVerdictSweep"
        const val ANSWERED_SCAN = 50
        const val MAX_PER_SWEEP = 10
        const val ANSWER_CAP = 1_000
        const val NOTE_CAP = 300
        const val TIMEOUT_MS = 15_000L

        const val VERDICT_UNCLEAR = "unclear"

        val ALLOWED = setOf(
            ClaimResolutionEntity.VERDICT_NEVER_TRUE,
            ClaimResolutionEntity.VERDICT_NO_LONGER_TRUE,
            ClaimResolutionEntity.VERDICT_CONFIRMED,
            VERDICT_UNCLEAR,
        )

        /**
         * The prompt's whole job is the second and third options staying apart.
         *
         * "It was wrong" and "it stopped being true" read almost identically in
         * conversation — "no, I'm in Istanbul" could be either — and they are
         * scored oppositely: one is a miss, one is excluded. Collapsing them
         * toward `never_true` would make Aura look worse than it is as the world
         * changes; collapsing toward `no_longer_true` would erase the misses
         * entirely and report a calibration of nothing.
         */
        val SYSTEM_PROMPT = """
            Aura asked the user to confirm something it had on record. Classify what the
            user's reply means about that record. Return one verdict.

            confirmed        - the record is right.
            never_true       - the record was wrong. It was never right, Aura got it wrong.
            no_longer_true   - the record used to be right and has since changed. The user is
                               describing something that changed, not a mistake.
            unclear          - the reply does not settle it, changes the subject, asks a
                               question back, or you cannot tell which of the above applies.

            The distinction between never_true and no_longer_true is the entire point and
            they are easy to confuse. "No, I'm in Istanbul" alone is unclear. "No, I've never
            lived there" is never_true. "I moved in March" is no_longer_true. When the reply
            does not say which, answer unclear.

            Prefer unclear over guessing. An ungraded answer costs nothing; a wrong verdict
            is permanent and indistinguishable from a real one.
        """.trimIndent()

        val VERDICT_SCHEMA = ResponseSchema(
            name = "belief_verdict",
            schema = buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject {
                    put("verdict", buildJsonObject {
                        put("type", "string")
                        put("enum", buildJsonArray { ALLOWED.sorted().forEach { add(JsonPrimitive(it)) } })
                    })
                })
                put("required", buildJsonArray { add(JsonPrimitive("verdict")) })
            },
        )
    }
}

@Serializable
internal data class VerdictAnswer(val verdict: String = "")
