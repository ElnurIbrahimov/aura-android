package com.aura.agent

import com.aura.providers.ChatOptions
import com.aura.providers.ProviderMessage
import com.aura.providers.ProviderRegistry
import com.aura.providers.ToolDefinition
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The brain. Wraps the provider registry, assembles system prompts,
 * owns the conversation context, hands the work to the agentic loop.
 *
 * ## Identity resolution
 *
 * The user override is stored in DataStore through [IdentityStore], which is
 * also what Settings and backup/restore use. When no custom text exists,
 * [IdentityStore] reads the bundled `assets/SOUL.md`; the hardcoded fallback
 * is only used if the asset is missing.
 */
@Singleton
class Brain @Inject constructor(
    private val providerRegistry: ProviderRegistry,
    private val identityStore: IdentityStore,
    private val contextBudgetResolver: ContextBudgetResolver,
    private val userPreferences: com.aura.data.UserPreferences,
) {
    /** Current identity as a cold flow; each subscription resolves DataStore anew. */
    val identity: Flow<String> = flow {
        emit(identityStore.readCurrent())
    }

    /**
     * Resolved system prompt. Reads the DataStore override, then bundled
     * asset, then hardcoded fallback through [IdentityStore]. This is the
     * "persona" layer only — the "about the user" layer (name, traits, facts)
     * is handled separately by [com.aura.profile.UserProfileStore]
     * and concatenated at the call site.
     */
    suspend fun resolvedIdentity(): String = identityStore.readCurrent()

    /**
     * Stream chat tokens from the configured provider. The function is
     * `suspend` (not `fun`) so the underlying `providerRegistry.chat`
     * call can dispatch without `runBlocking` — the call site is
     * already inside a coroutine context, and the previous
     * thread-blocking call was freezing the main thread when the
     * call came from a Compose context.
     */
    suspend fun stream(
        model: String,
        messages: List<ProviderMessage>,
        tools: List<ToolDefinition> = emptyList(),
        options: ChatOptions = ChatOptions(),
    ): Flow<BrainChunk> = flow {
        // One catalog lookup per stream, for both numbers.
        //
        // The ceiling is needed even when the caller set its own maxTokens —
        // that is the whole point: a creative draft asking for 28,672 output
        // plus thinking has to be clamped to what the model will actually
        // return, or Anthropic rejects the request outright. The lookup is a
        // live probe for several providers (OllamaCloud fans out an /api/show
        // per model), which is why ModelContextCache sits behind it.
        val budgets = contextBudgetResolver.budgetsFor(model)
        val resolverMaxTokens = if (options.maxTokens == null) budgets.maxTokens else null
        val reasoningEnabled = runCatching {
            userPreferences.reasoningEnabled.first()
        }.onFailure { android.util.Log.w("Brain", "reasoningEnabled read failed: ${it.message}", it) }
            .getOrDefault(true)
        val reasoningBudget = runCatching {
            userPreferences.reasoningBudget.first()
        }.onFailure { android.util.Log.w("Brain", "reasoningBudget read failed: ${it.message}", it) }
            .getOrDefault(32000)
        val budget = TokenBudgetPolicy.resolve(
            callerMaxTokens = options.maxTokens,
            callerThinkingBudget = options.thinkingBudget,
            resolverMaxTokens = resolverMaxTokens,
            reasoningEnabled = reasoningEnabled,
            reasoningBudget = reasoningBudget,
            outputCeiling = budgets.outputCeiling,
        )
        val resolvedOptions = options.copy(
            maxTokens = budget.maxTokens,
            thinkingBudget = budget.thinkingBudget,
        )
        // nameById accumulates tool-call ids to names across the stream so
        // providers that send argument deltas without re-sending the name
        // (e.g. Anthropic input_json_delta) can still be routed to the
        // correct tool. Reset per stream call.
        //
        // Bounded to prevent unbounded growth in pathological streams
        // (e.g. 100+ tool calls in a single response). When the map
        // exceeds MAX_NAME_BY_ID, the oldest entry is evicted. In
        // practice the map rarely exceeds 2-3 entries; the cap is
        // a defensive backstop, not a hot-path concern.
        val nameById = object : LinkedHashMap<String, String>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean {
                return size > MAX_NAME_BY_ID
            }
        }
        providerRegistry.chat(model, messages, resolvedOptions, tools).collect { providerChunk ->
            emit(BrainChunk.fromProvider(providerChunk, nameById))
        }
    }

    companion object {
        /** Legacy override filename retained for one-time migration. */
        const val IDENTITY_OVERRIDE_FILENAME = "identity.md"

        /** Path of the bundled identity asset (shipped with the APK). */
        const val IDENTITY_ASSET_FILENAME = "SOUL.md"

        /**
         * Maximum number of tool-call id→name entries retained in a
         * single stream. The map is an access-ordered LRU; entries
         * beyond this cap are evicted. In practice the map rarely
         * exceeds 2-3 entries (one per parallel tool call).
         */
        const val MAX_NAME_BY_ID = 32

        /**
         * Hardcoded fallback identity. The compiled artifact of the
         * original Michaela Osbourne persona, kept here as a safety
         * net in case both the asset and the user override are
         * missing (e.g. someone manually deleted SOUL.md from the
         * assets folder at build time).
         *
         * Not user-facing. Resetting identity clears the DataStore override
         * and resolves this only if the bundled asset is unavailable.
         */
        val IDENTITY_FALLBACK = """
            You are Aura, a personal AI assistant.
        """.trimIndent()
    }
}

sealed class BrainChunk {
    data class Text(val text: String) : BrainChunk()
    /**
     * Reasoning output, and — on Anthropic only — the signature that
     * authenticates it.
     *
     * Deliberately a second field on the existing case rather than a new
     * BrainChunk subclass: the agentic loop's `when (chunk)` over this sealed
     * class is exhaustive with no `else`, so a new case would be a compile error
     * at a call site that has nothing to say about signatures. [signature] is
     * null on every chunk that carries only text, and [text] is empty on the one
     * chunk that carries only a signature.
     */
    data class Thinking(val text: String, val signature: String? = null) : BrainChunk()
    data class ToolCallStart(val id: String, val name: String) : BrainChunk()
    data class ToolCallDelta(val id: String, val argumentsDelta: String) : BrainChunk()
    data class ToolCallEnd(val id: String, val name: String, val arguments: String) : BrainChunk()
    data class Finished(val reason: String) : BrainChunk()
    data class Error(
        val code: String,
        val message: String,
        val retryable: Boolean,
        val error: com.aura.providers.ProviderError? = null,
    ) : BrainChunk()

    companion object {
        /**
         * Map a ProviderChunk into the higher-level BrainChunk stream the
         * agentic loop consumes. For tool calls we emit:
         *   - ToolCallStart the first time we see a given id with a name
         *   - ToolCallDelta for every subsequent argument chunk
         *   - ToolCallEnd once (typically on the finish_reason=tool_calls event,
         *     but providers are inconsistent so we also accept ProviderChunks
         *     that carry a full arguments string).
         *
         * The previous version collapsed all three into a single ToolCallDelta
         * and never emitted ToolCallStart, which broke the loop's tool-name
         * lookup for OpenAI/Anthropic (the loop reads the name from
         * `toolCallStarts[id]`).
         */
        fun fromProvider(p: com.aura.providers.ProviderChunk, nameById: MutableMap<String, String> = mutableMapOf()): BrainChunk {
            p.error?.let { return Error(it.code, it.message, it.retryable, error = it) }
            p.finishReason?.let { return Finished(it.name) }
            val tc = p.toolCall
            if (tc != null) {
                if (tc.id.isNotEmpty() && tc.name.isNotEmpty()) {
                    if (nameById.put(tc.id, tc.name) == null) {
                        // First time seeing this tool call. Return Start
                        // with the name. The loop will register it and
                        // collect args from subsequent deltas.
                        // If args are present in this first chunk, return
                        // ToolCallEnd so the loop processes the complete
                        // call immediately (some providers send complete
                        // tool calls in a single chunk).
                        if (tc.arguments.isNotEmpty()) {
                            return ToolCallEnd(tc.id, tc.name, tc.arguments)
                        }
                        return ToolCallStart(tc.id, tc.name)
                    }
                    if (tc.arguments.isNotEmpty()) {
                        return ToolCallEnd(tc.id, tc.name, tc.arguments)
                    }
                    return ToolCallDelta(tc.id, "")
                }
                // Delta-only chunk from a provider that already resolved
                // the tool id (e.g. Anthropic's input_json_delta carries
                // the id through the `index`-keyed lookup in
                // AnthropicProvider). Honor the resolved id directly so
                // parallel tool_use blocks route their deltas to the
                // correct tool. Previously the code threw away the
                // resolved id and re-derived from `nameById.keys.lastOrNull()`,
                // which mis-routed interleaved deltas across parallel
                // tool calls (the second tool's delta would overwrite
                // the first tool's argument buffer).
                if (tc.id.isNotEmpty()) {
                    return ToolCallDelta(tc.id, tc.arguments)
                }
                // Last-resort fallback for providers that emit a delta
                // with no id and no name. Route to the most recent id
                // we saw in this stream. Only used by legacy
                // /v1/chat/completions providers that haven't been
                // migrated to id-tagged deltas.
                val id = nameById.keys.lastOrNull() ?: return Text("")
                return ToolCallDelta(id, tc.arguments)
            }
            // Anthropic sends the signature on its own event, after the last
            // thinking_delta, so a chunk can carry one without the other. Reading
            // `thinking` alone let the signature fall through to `Text("")` and
            // vanish, which is the same as never having parsed it.
            if (p.thinking != null || p.thinkingSignature != null) {
                return Thinking(p.thinking.orEmpty(), p.thinkingSignature)
            }
            p.text?.let { return Text(it) }
            return Text("")
        }
    }
}
