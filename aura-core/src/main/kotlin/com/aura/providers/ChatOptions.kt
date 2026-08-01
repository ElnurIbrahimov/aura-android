package com.aura.providers

import kotlinx.serialization.Serializable

@Serializable
data class ChatOptions(
    val temperature: Double = 0.7,
    val topP: Double = 1.0,
    val maxTokens: Int? = null,
    val stop: List<String> = emptyList(),
    val seed: Int? = null,
    val responseFormat: ResponseFormat = ResponseFormat.TEXT,
    /**
     * Extended thinking / reasoning budget in tokens. When non-null,
     * the provider enables extended thinking mode:
     *
     * - Anthropic: `thinking: { type: "enabled", budget_tokens: N }`
     * - OpenAI o-series: `reasoning_effort: "high"` (budget maps to effort level)
     * - Gemini: `generationConfig.thinkingConfig.thinkingBudget = N`
     *
     * When null, the provider uses its default (no extended thinking).
     * Set to a large value (e.g. 32000) to maximize reasoning depth.
     */
    val thinkingBudget: Int? = null,
)

@Serializable
enum class ResponseFormat { TEXT, JSON }
