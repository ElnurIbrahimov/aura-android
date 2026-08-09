package com.aura.agent

import com.aura.providers.ModelInfo
import com.aura.providers.ProviderContextWindows
import com.aura.providers.ProviderOutputLimits
import com.aura.providers.ProviderRegistry
import javax.inject.Inject
import javax.inject.Singleton
import android.util.Log

/**
 * Resolves a safe per-call token budget for a fully-qualified model id.
 *
 * The budget is derived from the provider's advertised context window
 * (live API when available, hardcoded table otherwise). We reserve 2K
 * tokens for the system prompt + tools so a long system persona does not
 * silently steal generation headroom, and set the generation budget at
 * 80% of the total context window.
 *
 * There is no hard cap on the generation budget: a model with 200K
 * context gets up to ~159K tokens of generation headroom. The previous
 * 32K cap was a safety net from when the table only covered Anthropic;
 * now that all major providers have entries, the real context window
 * IS the cap.
 *
 * Mirrors the per-provider context-window work landed in ConversationCompactor
 * and propagates the same budget to every model call in the app.
 */
@Singleton
class ContextBudgetResolver @Inject constructor(
    private val providerRegistry: ProviderRegistry,
    // Nullable so the ~7 hand-constructed instances in tests keep compiling;
    // Hilt always supplies the real cache in production.
    private val modelContextCache: com.aura.providers.ModelContextCache? = null,
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
     * Both numbers a caller needs to size a request.
     *
     * @param maxTokens the context-derived budget, already clamped to
     *   [outputCeiling] when one is known. Null when the model is unresolvable.
     * @param outputCeiling the largest response this model will produce, or null
     *   when unknown. **Null means "do not clamp"** — callers must not
     *   substitute a default, because a wrong guess truncates answers whereas a
     *   missing value merely preserves the previous behaviour.
     */
    data class Budgets(val maxTokens: Int?, val outputCeiling: Int?)

    /**
     * Resolve both budgets in a single catalog lookup.
     *
     * One call rather than two because the lookup is a live probe for several
     * providers — OllamaCloud fans out an `/api/show` request per model — and
     * this runs on every step of every agentic turn.
     */
    suspend fun budgetsFor(modelId: String): Budgets {
        val (provider, modelName) = try {
            providerRegistry.parse(modelId)
        } catch (_: Exception) {
            return Budgets(null, null)
        }
        val models = if (modelContextCache != null) {
            modelContextCache.modelsFor(provider)
        } else {
            runCatching { provider.listModelsWithContext() }
                .onFailure { Log.w("ContextBudgetResolver", "catalog probe failed: ${it.message}", it) }
                .getOrDefault(emptyList())
        }.ifEmpty {
            runCatching { provider.listModels().map { ModelInfo(name = it, contextWindow = null) } }
                .onFailure { Log.w("ContextBudgetResolver", "listModels fallback failed: ${it.message}", it) }
                .getOrDefault(emptyList())
        }
        val info = models.firstOrNull { it.name == modelName || modelId.endsWith(":${it.name}") }
        val contextWindow = info?.contextWindow
            ?: ProviderContextWindows.lookup(provider.prefix, modelName)
            ?: DEFAULT_CONTEXT_WINDOW
        val contextDerived = ((contextWindow - RESERVED_TOKENS) * GENERATION_FRACTION).toInt()
            .coerceAtLeast(1_024)
        // A live per-model value always beats the static table: the table holds
        // platform-wide minimums, so it under-serves whichever models allow more.
        val ceiling = info?.maxOutputTokens
            ?: ProviderOutputLimits.lookup(provider.prefix, modelName)
        return Budgets(
            maxTokens = ceiling?.let { minOf(contextDerived, it) } ?: contextDerived,
            outputCeiling = ceiling,
        )
    }

    /**
     * Returns a safe `maxTokens` value for the given model id, or `null`
     * if the provider cannot be resolved (callers then fall back to the
     * provider's own default).
     */
    suspend fun maxTokensFor(modelId: String): Int? = budgetsFor(modelId).maxTokens
}
