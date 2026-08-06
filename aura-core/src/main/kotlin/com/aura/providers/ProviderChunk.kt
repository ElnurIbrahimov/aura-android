package com.aura.providers

import kotlinx.serialization.Serializable

@Serializable
data class ProviderChunk(
    val text: String? = null,
    val thinking: String? = null,
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
