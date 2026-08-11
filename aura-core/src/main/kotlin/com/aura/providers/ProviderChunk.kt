package com.aura.providers

import kotlinx.serialization.Serializable

@Serializable
data class ProviderChunk(
    val text: String? = null,
    val thinking: String? = null,
    /**
     * Anthropic's HMAC over the reasoning it just streamed, delivered on its own
     * `signature_delta` event after the last `thinking_delta`.
     *
     * It exists because a thinking block has to be handed back verbatim on the
     * next request — Anthropic rejects an assistant turn that issued a
     * `tool_use` while extended thinking is on unless the block that preceded it
     * comes back too, and it rejects the block itself unless this value comes
     * with it. Without somewhere to put the signature there was nowhere to put
     * the thinking either, so step 2 of every tool call took a 400 and the turn
     * died with the call recorded and never executed.
     *
     * Null on every other provider. That is what makes replaying one model's
     * reasoning to another impossible rather than merely unwise: nothing but
     * [com.aura.providers.AnthropicProvider] ever fills it, and the serialiser
     * drops an unsigned trace.
     */
    val thinkingSignature: String? = null,
    val toolCall: ToolCall? = null,
    val finishReason: FinishReason? = null,
    val usage: Usage? = null,
    val error: ProviderError? = null,
) {
    val isDone: Boolean get() = finishReason != null || error != null
}

@Serializable
enum class FinishReason { stop, length, tool_calls, error, cancelled }

@Serializable
data class Usage(
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    val totalTokens: Int = 0,
    /**
     * Prompt tokens served from the provider's cache. A subset of
     * [promptTokens], not an addition to it — every provider reports it that
     * way, and treating it as extra would double-count the prefix.
     *
     * Priced far below a fresh prompt token (Anthropic 0.1x, OpenAI 0.5x), so
     * this is the number that says whether prompt caching is working at all.
     * Zero from a provider that reports no usage is indistinguishable from a
     * genuine miss — [UsageSnapshot] carries call counts alongside so the two
     * can be told apart in aggregate.
     */
    val cachedPromptTokens: Int = 0,
    /**
     * Prompt tokens written INTO the cache on this call. Anthropic prices these
     * at 1.25x a normal prompt token, so a workload that writes a cache it
     * never reads is more expensive than not caching at all — which is exactly
     * what one-shot calls with a cache breakpoint would do.
     *
     * Only Anthropic reports this separately; the others fold it into
     * [promptTokens] and leave this zero.
     */
    val cacheWritePromptTokens: Int = 0,
)

@Serializable
data class ProviderError(
    val code: String,
    val message: String,
    val retryable: Boolean = false,
    val cause: String? = null,
    /**
     * Server-requested backoff in milliseconds, parsed from a 429
     * response's `Retry-After` header. Null when the server sent none.
     * The agentic loop waits (capped) and retries the SAME model once
     * before failing over to another provider.
     */
    val retryAfterMs: Long? = null,
) {
    fun toAuraError(providerId: String? = null): com.aura.core.error.AuraError =
        code.toAuraError(message, retryable, providerId)

    /** Map a raw error code to a typed domain error. */
    private fun String.toAuraError(message: String, retryable: Boolean, providerId: String?): com.aura.core.error.AuraError =
        com.aura.core.error.AuraError.fromCode(this, message, retryable)
            .let { if (it is com.aura.core.error.AuraError.Provider && providerId != null) it.copy(providerCode = providerId) else it }
}
