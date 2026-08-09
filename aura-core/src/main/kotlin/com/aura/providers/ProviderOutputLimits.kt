package com.aura.providers

/**
 * Per-provider **output** token ceiling, for providers whose catalog does not
 * report one. The sibling of [ProviderContextWindows], and deliberately built
 * to the same policy — but answering a different question.
 *
 * A context window is how much a model can read. An output cap is how much it
 * will write. Nothing in this codebase modelled the second until now, so
 * [com.aura.agent.ContextBudgetResolver] derived `max_tokens` from the first:
 * `(contextWindow - 2_000) * 0.8`. For any `anthropic:` model that is 158,400,
 * far above what any current Claude model will accept, and Anthropic rejects an
 * oversized `max_tokens` outright. Providers that silently clamp instead — which
 * most OpenAI-compatible endpoints do — are why this went unnoticed.
 *
 * **Null means "do not clamp", not "use a default."** An unknown provider keeps
 * exactly the behaviour it had before this table existed, so adding an entry can
 * only ever fix a rejection; a wrong guess here could truncate an answer, and a
 * missing entry cannot.
 *
 * Like [ProviderContextWindows] this intentionally avoids model-name substring
 * heuristics. They rot as providers rename models, and they under-serve
 * newly-shipped models absent from any static list. The only safe values to
 * hardcode are provider-wide platform minimums the provider itself documents;
 * anything per-model belongs in [Provider.listModelsWithContext], where it can
 * be read live and will override this table.
 *
 * SNAPSHOT — last verified 2026-08-09.
 */
object ProviderOutputLimits {

    /**
     * Largest output the provider will produce, in tokens. Null when unknown or
     * when the provider's models vary too much for a single safe number.
     *
     * `anthropic` is the only entry because it is the only provider here that
     * both (a) hard-rejects an oversized `max_tokens` rather than clamping, and
     * (b) documents a platform-wide floor. 32,000 is the smallest output cap
     * across the current Claude family, so it is safe on every model at the cost
     * of leaving headroom unused on the larger ones — the same "slightly early
     * is better than late" trade [ProviderContextWindows] makes. Models that
     * allow more should report it live; `AnthropicProvider` cannot today,
     * because Anthropic's /v1/models returns no size fields at all.
     *
     * Everything else returns null on purpose:
     * - OpenAI-compatible endpoints span 4K to 100K+ across models and generally
     *   clamp rather than reject, so a single number would cost more than it saves.
     * - Gemini, OpenRouter and the ChatGPT subscription backend report a real
     *   per-model value through [Provider.listModelsWithContext]; a table entry
     *   would only ever be the worse answer.
     * - `custom` is the user's own endpoint and is genuinely unknowable.
     */
    @Suppress("UNUSED_PARAMETER")
    fun lookup(prefix: String, model: String): Int? = when (prefix) {
        "anthropic" -> 32_000
        else -> null
    }
}
