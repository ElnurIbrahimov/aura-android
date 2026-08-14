package com.aura.agent

import android.util.Log
import com.aura.providers.ChatOptions
import com.aura.providers.ProviderMessage
import com.aura.providers.ProviderMessage.Role
import com.aura.providers.ProviderRegistry
import com.aura.providers.ResponseSchema
import com.aura.providers.StructuredJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One cheap pass over what recall already returned, before the answer starts.
 *
 * The failure this exists for is not retrieval. It is that a model given the
 * right constraint in its context does not reliably look at it. PrefEval
 * (ICLR 2025) measures preference-following at ten turns of conversation at
 * **7%**, and **91%** when the preference is simply re-stated near the
 * question — with 70–80% of the failures classified as "preference-unaware
 * violations", meaning the constraint was present and unread. The same shape
 * shows up across memory systems generally: when they fail, the evidence was
 * retrievable roughly ten times more often than it was missing.
 *
 * So this is not a smarter retriever and not a critique of the answer. It is a
 * reminder, placed where reminders work, and the literature is unusually clear
 * that the dumb version beats the clever ones — self-critique and
 * chain-of-thought both measured *worse* than a plain restatement.
 *
 * ## The model selects; Aura writes
 *
 * [consult] asks only for the *indices* of the constraints that bear on the
 * question. It never authors the text that goes back into the prompt — [render]
 * builds that from the constraint strings Aura already held.
 *
 * That split is deliberate and it is a security boundary. Retrieved memories
 * are attacker-reachable in one hop: the model reads a page with `read_url`,
 * judges a line memorable, calls `remember`, and the line returns inside a
 * system message on a later turn. `MemoryAugmentedAgenticLoop` already answers
 * that by framing all retrieved content under an untrusted-data preamble. If
 * the consult pass could emit free text into the prompt, it would be a second
 * path around that framing — one that launders attacker-controlled text through
 * a model call and back into a region the preamble does not cover. Letting it
 * choose from a list but never write closes that, and costs nothing: the whole
 * intervention is restatement, and Aura can restate its own strings.
 */
@Singleton
class ConsultGate @Inject constructor(
    private val providerRegistry: ProviderRegistry,
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }

    /**
     * One thing Aura has been told, and the row it came from.
     *
     * [sourceId] is carried through so the caller can record which constraints
     * were present against which were judged to bear — the had-it/used-it
     * ratio. Nothing in the prompt sees it.
     */
    data class Constraint(val sourceId: String, val text: String)

    /**
     * @param considered how many constraints were offered — the denominator.
     * @param applicable the subset the pass judged relevant, in the order given.
     */
    data class Consultation(
        val considered: Int,
        val applicable: List<Constraint>,
    )

    /**
     * Ask which of [constraints] bear on [userMessage].
     *
     * Returns null when the call fails, times out, or returns nothing usable.
     * Null means "proceed as before" — never an error the caller has to handle.
     * A turn that would have been answered without this pass must still be
     * answered if the pass falls over, which is why every exit here is a plain
     * null rather than a throw.
     */
    suspend fun consult(
        userMessage: String,
        constraints: List<Constraint>,
        model: String,
    ): Consultation? {
        if (userMessage.isBlank() || constraints.isEmpty()) return null

        // Bounded because this is a per-turn cost on the user's own latency.
        // The recall limit is already small; this is a backstop against a
        // future caller passing the whole store.
        val offered = constraints.take(MAX_CONSTRAINTS)

        val numbered = offered.mapIndexed { i, c -> "${i + 1}. ${c.text.flatten(CONSTRAINT_CHARS)}" }
            .joinToString("\n")

        val messages = listOf(
            ProviderMessage(role = Role.system, content = SYSTEM_PROMPT),
            ProviderMessage(
                role = Role.user,
                content = "Things the user has told you:\n$numbered\n\n" +
                    "Their new message:\n${userMessage.flatten(MESSAGE_CHARS)}",
            ),
        )

        val parsed = StructuredJson.requestJson(
            registry = providerRegistry,
            modelId = model,
            messages = messages,
            options = ChatOptions(temperature = 0.0, maxTokens = 120),
            schema = CONSULT_SCHEMA,
            timeoutMs = CONSULT_TIMEOUT_MS,
            tag = TAG,
        ) { cleaned ->
            runCatching { json.decodeFromString(ConsultResponse.serializer(), cleaned) }
                .onFailure { Log.w(TAG, "unparseable consultation: ${it.message}", it) }
                .getOrNull()
        } ?: return null

        // Indices are 1-based in the prompt because a model asked for "entry 0"
        // reliably answers about entry 1. Out-of-range and duplicate values are
        // dropped rather than clamped: a clamp would silently attribute the
        // reminder to a constraint the model did not choose.
        val applicable = parsed.applicable
            .distinct()
            .filter { it in 1..offered.size }
            .map { offered[it - 1] }

        return Consultation(considered = offered.size, applicable = applicable)
    }

    /**
     * The reminder, built from Aura's own strings.
     *
     * Returns "" when nothing applies, which is the common case and must cost
     * the prompt nothing — an empty "no constraints apply" section is still a
     * section, and the point of firing conditionally is undone by always
     * emitting something.
     *
     * Each line is flattened to a single row of plain text. The content is the
     * same content already present under the untrusted-context preamble, but it
     * is being restated *outside* that block, where it reads with more
     * authority. Flattening is what keeps that from being exploitable: a
     * "memory" containing newlines and a `# System` heading cannot open a new
     * section here, only occupy one bullet.
     */
    fun render(consultation: Consultation): String {
        if (consultation.applicable.isEmpty()) return ""
        val lines = consultation.applicable.joinToString("\n") { "- ${it.text.flatten(CONSTRAINT_CHARS)}" }
        return "\n\n# Before you answer\nThe user has told you the following, and it bears on what they just " +
            "asked. Honour it unless this message changes it.\n$lines"
    }

    /** Collapse to one line and bound the length. */
    private fun String.flatten(limit: Int): String =
        replace(Regex("\\s+"), " ").trim().take(limit)

    companion object {
        private const val TAG = "ConsultGate"

        /**
         * Five seconds, matching [com.aura.profile.LlmProfileExtractor].
         *
         * This sits in front of the user's first token, so the timeout is a
         * latency budget rather than a generosity: past this the reminder is
         * worth less than the wait, and [consult] returning null costs only the
         * improvement, not the answer.
         */
        const val CONSULT_TIMEOUT_MS = 5_000L

        /** Backstop on prompt size; recall already returns fewer than this. */
        const val MAX_CONSTRAINTS = 12

        private const val CONSTRAINT_CHARS = 240
        private const val MESSAGE_CHARS = 600

        private val SYSTEM_PROMPT = """
            You are checking whether any standing instruction from the user applies to their new message.

            You will be given a numbered list of things the user has previously told you, and their new message.
            Reply with the numbers of the entries that bear on how the new message should be answered.

            Include an entry only if ignoring it would produce a worse or contradictory answer.
            Reply with an empty list if none apply — that is the normal case and is not a failure.
            Do not answer the user's message. Do not explain. Numbers only.
        """.trimIndent()

        /**
         * Integers only. The pass has no field in which to write prose, which is
         * the property the class KDoc is about — there is nothing here for
         * attacker-controlled text to travel through.
         */
        private val CONSULT_SCHEMA = ResponseSchema(
            name = "select_applicable_constraints",
            schema = buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject {
                    put("applicable", buildJsonObject {
                        put("type", "array")
                        put("items", buildJsonObject { put("type", "integer") })
                    })
                })
                put("required", buildJsonArray { add(JsonPrimitive("applicable")) })
            },
        )
    }
}

@Serializable
private data class ConsultResponse(val applicable: List<Int> = emptyList())
