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
    /**
     * Ask for JSON without constraining its shape. Weaker than [responseSchema]
     * and the two are mutually exclusive in practice — see the resolution rule
     * on [responseSchema].
     *
     * This field existed for a long time without any provider serialising it:
     * one caller set it, nothing read it, and the JSON came back — or didn't —
     * purely on the strength of the prompt. It reaches the wire now.
     */
    val responseFormat: ResponseFormat = ResponseFormat.TEXT,
    /**
     * Constrain the reply to a JSON Schema.
     *
     * Resolution, implemented identically by every provider that supports it:
     * `responseSchema != null` wins; else [responseFormat] `== JSON` asks for a
     * bare JSON object; else plain text.
     *
     * The wire shape differs per provider — `response_format.json_schema` on
     * OpenAI-compatible endpoints, `text.format` on the Responses API,
     * `generationConfig.responseSchema` on Gemini, and a forced `tool_choice`
     * on Anthropic, which has no JSON mode at all. What every provider
     * guarantees the *caller* is the same thing: the text you collect off the
     * flow is JSON matching this schema. Anthropic buys that uniformity by
     * translating its `tool_use` deltas back into text deltas.
     *
     * Not every endpoint honours it — `custom` is a user-supplied URL and MoA
     * fans out to whatever the aggregator is. [Provider.supportsResponseSchema]
     * says only whether *we* serialise it, which is the strongest claim this
     * code can honestly make, so callers must keep a lenient parse either way.
     */
    val responseSchema: ResponseSchema? = null,
    /**
     * How many LEADING system messages are byte-identical across the requests
     * of one run. `0` means caching is off and nothing changes on the wire.
     *
     * Cache intent is request-level policy, not message content — a message
     * cannot know whether it is being resent — so it lives here rather than on
     * [ProviderMessage]. That also keeps it out of the shared wire DTO, where
     * every one of six serialisers would have to explicitly *not* emit it and
     * `toOpenAiJson` leaking an unknown key to a strict endpoint is a 400.
     *
     * The *position* a per-message flag would carry is carried by structure
     * instead: the loop emits a stable system message followed by a volatile
     * one, and `stableSystemPrefix = 1` says the first is the fixed part.
     *
     * Providers with explicit markers (Anthropic) place a breakpoint after this
     * many system messages. Providers with automatic prefix caching (OpenAI,
     * Gemini) need no marker and benefit from the stable ordering alone.
     */
    val stableSystemPrefix: Int = 0,
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
    /**
     * False when nobody asked for this call and nobody is waiting for it.
     *
     * The daemon, dream consolidation, the morning brief, curiosity authoring
     * and self-serve research all spend money on a timer. Nothing bounded that,
     * and seeding `backgroundModel` on 2026-08-13 switched several of them on at
     * once — see [com.aura.usage.BackgroundBudget], which is what reads this.
     *
     * Never serialised to any provider. It is a routing fact about who wanted
     * the call, not a parameter of the call.
     *
     * **Defaults to true on purpose.** A call site that forgets to set it is
     * treated as the user's own turn and is never blocked. Getting this wrong in
     * the other direction would mean a chat message refused because a dream
     * cycle spent the budget overnight, which is a far worse failure than an
     * unbounded background job.
     */
    val attended: Boolean = true,
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
