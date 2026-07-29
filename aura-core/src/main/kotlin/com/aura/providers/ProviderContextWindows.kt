package com.aura.providers

/**
 * Per-provider context window fallback for providers whose
 * /models endpoint does NOT return a context window field
 * (Anthropic, OpenAI, Groq, ChatGPT). The compactor calls
 * [Provider.listModelsWithContext]; providers that can
 * return real data (OllamaCloud, Gemini, OpenRouter)
 * override it and ignore this table.
 *
 * This object intentionally does NOT use model-name substring
 * heuristics. Those rot as providers rename models and they
 * under-utilize newly-shipped models that aren't in any static
 * list. The only safe values to hardcode are provider-wide
 * platform defaults documented by the provider itself.
 *
 * Policy:
 * - If a provider documents a single platform-wide context window
 *   for all current models, return that number.
 * - Otherwise return null and let the compactor fall back to its
 *   safe 32K default. A slightly early compaction is better than a
 *   late one on a 4K-context model.
 */
object ProviderContextWindows {

    /** Look up context window in tokens. Returns null when unknown — caller uses 32K default. */
    @Suppress("UNUSED_PARAMETER")
    fun lookup(prefix: String, model: String): Int? = when (prefix) {
        "anthropic" -> 200_000
        else -> null
    }
}
