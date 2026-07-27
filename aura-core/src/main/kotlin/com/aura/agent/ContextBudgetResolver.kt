package com.aura.agent

import com.aura.providers.ModelInfo
import com.aura.providers.ProviderContextWindows
import com.aura.providers.ProviderRegistry
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Resolves a safe per-call token budget for a fully-qualified model id.
 *
 * The budget is derived from the provider's advertised context window
 * (live API when available, hardcoded table otherwise). We reserve 2K
 * tokens for the system prompt + tools so a long system persona does not
 * silently steal generation headroom, and cap the generation budget at
 * 80% of the total context window.
 *
 * Mirrors the per-provider context-window work landed in ConversationCompactor
 * and propagates the same budget to every model call in the app.
 */
@Singleton
class ContextBudgetResolver @Inject constructor(
    private val providerRegistry: ProviderRegistry,
) {
    companion object {
        /** Tokens reserved for system prompt, tool definitions and working memory. */
        private const val RESERVED_TOKENS = 2_000

        /** Default context window when a provider cannot advertise one. */
        private const val DEFAULT_CONTEXT_WINDOW = 32_768

        /** Max generation budget as a fraction of total context window. */
        private const val GENERATION_FRACTION = 0.8
    }

    /**
     * Returns a safe `maxTokens` value for the given model id, or `null`
     * if the provider cannot be resolved (callers then fall back to the
     * provider's own default).
     */
    suspend fun maxTokensFor(modelId: String): Int? {
        val (provider, modelName) = try {
            providerRegistry.parse(modelId)
        } catch (_: Exception) {
            return null
        }
        val models = runCatching { provider.listModelsWithContext() }.getOrNull()
            ?: provider.listModels().map { ModelInfo(name = it, contextWindow = null) }
        val info = models.firstOrNull { it.name == modelName || modelId.endsWith(":${it.name}") }
        val contextWindow = info?.contextWindow
            ?: ProviderContextWindows.lookup(provider.prefix, modelName)
            ?: DEFAULT_CONTEXT_WINDOW
        return ((contextWindow - RESERVED_TOKENS) * GENERATION_FRACTION).toInt()
            .coerceAtLeast(1_024)
            .coerceAtMost(32_768)
    }
}
