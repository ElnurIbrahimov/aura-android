package com.aura.calibration

import android.util.Log
import com.aura.curiosity.OpenQuestionEntity
import com.aura.world.BeliefDao
import com.aura.world.BeliefEntity
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonPrimitive
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Picks one belief worth asking about, and phrases the question.
 *
 * The scarce resource here is the user's willingness to answer, not compute. A
 * verification question that arrives too often stops being read, and an unread
 * question produces no verdict, and no verdicts means no calibration — so the
 * cooldown is not politeness, it is the thing that keeps the sample coming at
 * all.
 *
 * **No model call.** The phrasing is a template over `subject`, `predicate` and
 * `valueJson`, which are already short and human-readable by construction. A
 * model would phrase it better, and would also mean this can be skipped when the
 * daily background budget is spent — making the one input calibration has
 * dependent on spend. The model that surfaces the question in conversation will
 * rephrase it naturally anyway; that is what it is for.
 */
@Singleton
class BeliefVerificationAuthor @Inject constructor(
    private val beliefDao: BeliefDao,
    private val resolutions: ClaimResolutionStore,
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * The next belief to ask about, as an unsaved question row.
     *
     * @param claimedSubjects `subjectKind/subjectId` pairs already spoken for,
     *   from `OpenQuestionDao.claimedSubjects()`. Carries the dismissals too, so
     *   "never ask me about this again" survives here for free.
     * @param lastAskedAt when a verification question was last written. Zero when
     *   there has never been one.
     */
    suspend fun nextQuestion(
        claimedSubjects: Set<String>,
        lastAskedAt: Long,
        now: Long = System.currentTimeMillis(),
    ): OpenQuestionEntity? {
        if (lastAskedAt > 0L && now - lastAskedAt < COOLDOWN_MS) return null

        val resolved = resolutions.resolvedBeliefIds()
        val candidates = runCatching { beliefDao.allActive(CANDIDATE_LIMIT) }
            .onFailure { Log.w(TAG, "could not read beliefs: ${it.message}", it) }
            .getOrDefault(emptyList())
            .filter { it.id !in resolved }
            .filter { "${OpenQuestionEntity.SUBJECT_BELIEF}/${it.id}" !in claimedSubjects }

        // Highest confidence first, oldest verification as the tiebreak.
        //
        // Confidence rather than age is the primary key on purpose: a claim Aura
        // is *sure* about is the one where being wrong costs most, and it is also
        // the one whose verdict tells calibration the most — the high-confidence
        // bands are exactly where an overconfident model is overconfident.
        val belief = candidates
            .sortedWith(compareByDescending<BeliefEntity> { it.confidence }.thenBy { it.lastVerifiedAt })
            .firstOrNull() ?: return null

        return OpenQuestionEntity(
            id = UUID.randomUUID().toString(),
            kind = OpenQuestionEntity.KIND_VERIFICATION,
            subjectKind = OpenQuestionEntity.SUBJECT_BELIEF,
            subjectId = belief.id,
            question = phrase(belief),
            // Only the user can settle whether a claim about their own life was
            // ever true. A web search cannot.
            answerable = OpenQuestionEntity.ANSWERABLE_USER,
            createdAt = now,
        )
    }

    /**
     * Turn a belief into something a person can answer.
     *
     * Deliberately asks whether it is *right*, not whether it is *still* right.
     * "Still" presupposes it once was, which is the very distinction the answer
     * has to draw — a question that leads the witness toward `no_longer_true`
     * would bias the sample toward the verdict that is excluded from scoring, and
     * quietly starve the calibration of the misses it exists to count.
     */
    internal fun phrase(belief: BeliefEntity): String {
        val value = runCatching { json.parseToJsonElement(belief.valueJson).jsonPrimitive.content }
            .getOrElse { belief.valueJson }
            .trim()
            .take(VALUE_CAP)
        val predicate = belief.predicate.replace('_', ' ')
        return "I have your $predicate recorded as \"$value\". Is that right?"
    }

    private companion object {
        const val TAG = "BeliefVerificationAuthor"

        /**
         * Three days between verification questions.
         *
         * Long enough that the questions stay rare and get read; short enough to
         * accumulate a scoreable sample within a quarter, which is the window the
         * report is framed in. It also stops verification monopolising the single
         * open-question slot and starving the curiosity questions that share it.
         */
        const val COOLDOWN_MS = 3L * 24 * 60 * 60 * 1000

        const val CANDIDATE_LIMIT = 200
        const val VALUE_CAP = 120
    }
}
