package com.aura.providers

import kotlinx.serialization.Serializable

@Serializable
data class ChatOptions(
    /**
     * Sampling temperature. `null` means UNSET — the caller expressed no
     * preference. Providers substitute [DEFAULT_TEMPERATURE] at
     * serialization time, so unset behaves exactly as the old non-null
     * default did on the wire. A non-null value is an explicit caller
     * choice and is never overridden (EmotionEngine.applySampling fills
     * only nulls).
     */
    val temperature: Double? = null,
    /** Nucleus sampling. Same null-means-unset contract as [temperature]; providers substitute [DEFAULT_TOP_P]. */
    val topP: Double? = null,
    val maxTokens: Int? = null,
    val stop: List<String> = emptyList(),
    val seed: Int? = null,
    val responseFormat: ResponseFormat = ResponseFormat.TEXT,
    /**
     * Extended thinking / reasoning budget in tokens. When non-null,
     * the provider enables extended thinking mode:
     * - Anthropic: `thinking: { type: "enabled", budget_tokens: N }`
     * - OpenAI o-series: `reasoning_effort: "high"` (budget maps to effort level)
     * - Gemini: `generationConfig.thinkingConfig.thinkingBudget = N`
     * - DeepSeek: `reasoning_effort: "high"` + `thinking: { type: "enabled" }`
     * - Ollama: `think: true` or `think: "high"` (budget maps to level)
     * - xAI Grok: `reasoning_effort: "high"` (OpenAI-compatible)
     * - ChatGPT subscription: `reasoning_effort: "high"` (Responses API)
     * - All other OpenAI-compatible: `reasoning_effort` (low/medium/high)
     *
     * When null, the provider uses its default (no extended thinking).
     * Set to a large value (e.g. 32000) to maximize reasoning depth.
     */
    val thinkingBudget: Int? = null,
) {
    companion object {
        /** Wire default when [temperature] is unset (null). */
        const val DEFAULT_TEMPERATURE = 0.7

        /** Wire default when [topP] is unset (null). */
        const val DEFAULT_TOP_P = 1.0
    }
}

@Serializable
enum class ResponseFormat { TEXT, JSON }
