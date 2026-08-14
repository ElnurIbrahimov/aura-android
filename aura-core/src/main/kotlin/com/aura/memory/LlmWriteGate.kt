package com.aura.memory

import android.util.Log
import com.aura.providers.ChatOptions
import com.aura.providers.ProviderMessage
import com.aura.providers.ProviderRegistry
import com.aura.providers.ResponseSchema
import com.aura.providers.StructuredJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The "is this worth remembering?" decision, with a model in the loop.
 *
 * Flow:
 *  1. [WriteGate] runs first. If it hard-rejects — empty, too short, a system
 *     message, or a bare pleasantry — that stands and no model is called. Those
 *     are the cases where a round-trip would be spent to be told what is
 *     already obvious.
 *  2. Otherwise a model decides, and its answer wins in both directions.
 *  3. If the model cannot be reached or does not answer usefully, the
 *     heuristic's decision is the fallback.
 *
 * Step 1 used to short-circuit on *any* heuristic rejection. That was harmless
 * while the heuristic accepted everything, and became wrong the moment it
 * started rejecting properly: "the ARC deadline moved to April" carries no
 * first-person marker and no keyword the heuristic knows, so it would have been
 * dropped without ever being offered to a model. A weak-evidence rejection is
 * exactly what the model is here to overturn.
 *
 * The fallback in step 3 is only safe because the heuristic is now
 * conservative. When this class first shipped, an unreachable model meant
 * "store it" — so the failure mode of the whole memory system was to keep
 * everything, which is how an install ended up holding four greetings.
 */
class LlmWriteGate(
    private val heuristic: WriteGate,
    private val registry: ProviderRegistry,
    private val modelId: String,
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }

    suspend fun evaluate(content: String, source: String): WriteGate.Decision {
        val heuristicDecision = heuristic.evaluate(content, source)
        if (heuristicDecision.reason in WriteGate.HARD_REJECT) return heuristicDecision

        val llmDecision = runCatching { llmEvaluate(content) }
            .onFailure { Log.w(TAG, "write gate call failed: ${it.message}", it) }
            .getOrNull()

        return llmDecision ?: heuristicDecision
    }

    private suspend fun llmEvaluate(content: String): WriteGate.Decision? {
        val parsed = StructuredJson.requestJson(
            registry = registry,
            modelId = modelId,
            messages = listOf(
                ProviderMessage(role = ProviderMessage.Role.system, content = SYSTEM_PROMPT),
                ProviderMessage(role = ProviderMessage.Role.user, content = content.take(MAX_CONTENT_CHARS)),
            ),
            options = ChatOptions(temperature = 0.0, maxTokens = 120),
            schema = GATE_SCHEMA,
            timeoutMs = GATE_TIMEOUT_MS,
            tag = TAG,
        ) { cleaned ->
            runCatching { json.decodeFromString(GateVerdict.serializer(), cleaned) }
                .onFailure { Log.w(TAG, "unparseable gate verdict: ${it.message}", it) }
                .getOrNull()
        } ?: return null

        if (!parsed.store) return WriteGate.Decision(shouldStore = false, reason = "llm_rejected")

        return WriteGate.Decision(
            shouldStore = true,
            category = parsed.category.takeIf { it in CATEGORIES } ?: "fact",
            importance = parsed.importance.coerceIn(0f, 1f),
            reason = "llm_classified",
        )
    }

    companion object {
        private const val TAG = "LlmWriteGate"

        /** Sits after the user's turn, so it is not in front of a token. */
        private const val GATE_TIMEOUT_MS = 8_000L

        /** A gate decision does not need the whole of a long message. */
        private const val MAX_CONTENT_CHARS = 1_200

        private val CATEGORIES = setOf("fact", "preference", "person", "episode", "idea", "task")

        private val SYSTEM_PROMPT = """
            You are the memory gate for a personal AI assistant. Decide whether the user's message
            contains something worth recalling in a future conversation, weeks from now.

            Store: facts about the user, their preferences, people in their life, commitments and
            deadlines, decisions they have made, and anything they explicitly ask to be remembered.

            Do not store: greetings, thanks, acknowledgements, questions, requests for the assistant
            to do something, small talk, or anything whose value ends with this conversation.

            Be conservative. A message the user would have to be reminded of to recognise is not
            worth storing, and a store that fills with chatter buries the things that matter.
        """.trimIndent()

        private val GATE_SCHEMA = ResponseSchema(
            name = "memory_write_gate",
            schema = buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject {
                    put("store", buildJsonObject { put("type", "boolean") })
                    put("category", buildJsonObject {
                        put("type", "string")
                        put("enum", buildJsonArray { CATEGORIES.forEach { add(JsonPrimitive(it)) } })
                    })
                    put("importance", buildJsonObject { put("type", "number") })
                })
                put("required", buildJsonArray { add(JsonPrimitive("store")) })
            },
        )
    }
}

/**
 * Typed rather than hand-parsed.
 *
 * The previous reader pulled `store` out as a *string* and compared it to
 * "true", and recovered the JSON with a non-greedy `\{(.*?)}` — the exact
 * regex `StructuredJson`'s KDoc cites as the broken one it was written to
 * replace, since it truncates on any nested brace.
 */
@Serializable
private data class GateVerdict(
    val store: Boolean = false,
    val category: String = "fact",
    val importance: Float = 0.5f,
)
