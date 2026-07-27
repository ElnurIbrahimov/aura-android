package com.aura.providers

/**
 * Per-provider context window fallback for providers whose
 * /models endpoint does NOT return a context window field
 * (Anthropic, OpenAI, Groq, ChatGPT). The compactor calls
 * [Provider.listModelsWithContext]; providers that can
 * return real data (OllamaCloud, Gemini, OpenRouter)
 * override it and ignore this table.
 *
 * For the remaining providers, the only way to get the
 * context window is to either (a) hardcode it here or
 * (b) always return null and fall back to the compactor's
 * 32K default. Hardcoding is better for the well-known
 * models because the default would under-utilize them.
 *
 * The table is intentionally a SNAPSHOT: when a provider
 * ships a new model that isn't listed, the lookup returns
 * null and the compactor uses the safe 32K default. That's
 * a degraded experience (the compactor fires earlier than
 * necessary) but it's never wrong. A wrong entry in this
 * table would cause the compactor to NOT fire on a real
 * 4K-context model — a worse failure mode.
 *
 * Adding a new entry: only do it when the model is GA
 * (not preview/alpha/beta) and the context window is
 * documented on the provider's own model page. The number
 * MUST be the largest published context for that model
 * (e.g. a model with 8K/32K/128K variants → 128K).
 */
object ProviderContextWindows {

    /** Look up context window in tokens. Returns null when unknown — caller uses 32K default. */
    fun lookup(prefix: String, model: String): Int? = when (prefix) {
        "anthropic" -> anthropic(model)
        "openai" -> openai(model)
        "chatgpt" -> openai(model) // ChatGPT subscription is gated to OpenAI model IDs
        "groq" -> groq(model)
        else -> null
    }

    private fun anthropic(model: String): Int? = when {
        // Latest Claude family uses 200K context.
        // Source: docs.anthropic.com/en/docs/about-claude/models
        // (table intentionally only covers names that are
        // publicly known to have a stable context — when
        // Anthropic ships a new model name, we add it here
        // after verifying the documented context size).
        model.contains("opus-4") -> 200_000
        model.contains("sonnet-4") -> 200_000
        model.contains("haiku-4") -> 200_000
        model.contains("opus-3") -> 200_000
        model.contains("sonnet-3-7") -> 200_000
        model.contains("sonnet-3-5") -> 200_000
        model.contains("haiku-3-5") -> 200_000
        // Older Claude 3 / 2 / Instant families
        model.contains("claude-3-opus") -> 200_000
        model.contains("claude-3-sonnet") -> 200_000
        model.contains("claude-3-haiku") -> 200_000
        model.contains("claude-2") -> 100_000
        model.contains("claude-instant") -> 100_000
        else -> null
    }

    private fun openai(model: String): Int? = when {
        // Source: platform.openai.com/docs/models
        // Reasoning models have 200K, GPT-4o has 128K,
        // GPT-4 (original) has 8K, GPT-3.5 has 16K.
        model.contains("gpt-4o-mini") -> 128_000
        model.contains("gpt-4o") -> 128_000
        model.contains("gpt-4-turbo") -> 128_000
        model.contains("gpt-4-32k") -> 32_768
        model.contains("gpt-4") -> 8_192
        model.contains("gpt-3.5-turbo-16k") -> 16_385
        model.contains("gpt-3.5") -> 16_385
        // Reasoning models
        model.contains("o1-mini") -> 128_000
        model.contains("o1-preview") -> 128_000
        model.contains("o1") -> 200_000
        model.contains("o3-mini") -> 200_000
        model.contains("o3") -> 200_000
        model.contains("o4-mini") -> 200_000
        model.contains("o4") -> 200_000
        else -> null
    }

    private fun groq(model: String): Int? = when {
        // Source: console.groq.com/docs/models
        // Llama 3.1 / 3.3 family uses 131K context.
        // Llama 3 (original) was 8K. Mixtral 32K.
        model.contains("llama-3.3-70b") -> 131_072
        model.contains("llama-3.1-405b") -> 131_072
        model.contains("llama-3.1-70b") -> 131_072
        model.contains("llama-3.1-8b") -> 131_072
        model.contains("llama-3-70b") -> 8_192
        model.contains("llama-3-8b") -> 8_192
        model.contains("llama-guard-3-8b") -> 131_072
        model.contains("mixtral-8x7b") -> 32_768
        model.contains("gemma2-9b") -> 8_192
        else -> null
    }
}
