package com.aura.providers

import android.util.Log
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Picks the model for short auxiliary work — reranking a recall, rewriting a
 * query, summarising for compaction, scoring a reasoning branch, extracting a
 * profile fact. Calls measured in tens or hundreds of tokens, run several times
 * per turn, where the user's flagship model is pure waste.
 *
 * This exists because five different implementations of the same idea had grown
 * across the codebase and only one of them was right:
 *
 *  - the agentic loop had two (an inline one and a private `resolveCheapModel`),
 *    both using [CheapModelHeuristic];
 *  - `ConversationCompactor` had a third, also using the heuristic;
 *  - `DreamConsolidator` and `ParallelResearchTool` each took "the first model
 *    the first configured provider happens to list", which is not a cheapness
 *    judgement at all;
 *  - `DebateRoundUseCase` used `minByOrNull { it.length }` — literally the
 *    by-name-length ranking that ENGINEERING_HISTORY §2.5 records replacing,
 *    because `gpt-4o` (6 chars) sorts before `gpt-4o-mini` (11) and the suffix
 *    marking a model as small also lengthens its name. §2.5 claims the fix
 *    landed "at all three sites"; there were six.
 *
 * The other thing it adds is the [ModelRole.FAST] preference, which the user
 * could set in Settings and which nothing had ever read.
 */
@Singleton
class CheapModelResolver @Inject constructor(
    private val modelRoleRouter: ModelRoleRouter,
    private val providerRegistry: ProviderRegistry,
    private val modelContextCache: ModelContextCache? = null,
) {
    /**
     * Resolution order: the user's explicit **Fast** model, then the cheapest
     * model [CheapModelHeuristic] can find across configured providers, then
     * [fallback].
     *
     * Deliberately reads [ModelRoleRouter.explicit] rather than
     * [ModelRoleRouter.resolve]: `resolve` falls back to the conversation
     * default, and defaulting a 50-token rerank to the user's flagship is the
     * precise failure this class exists to prevent. If the user has not chosen a
     * Fast model, the heuristic should decide — not the chat setting.
     *
     * @param fallback returned when nothing else resolves; usually the caller's
     *   own model, so the work still happens rather than being skipped.
     * @param exclude a model to leave out — normally the one the caller is
     *   already using, so an "auxiliary" call does not land back on it.
     */
    suspend fun resolve(fallback: String? = null, exclude: String? = null): String? {
        modelRoleRouter.explicit(ModelRole.FAST)?.let { return it }

        val candidates = runCatching {
            providerRegistry.configured().flatMap { provider ->
                namesFor(provider).map { "${provider.prefix}:$it" }
            }
        }.onFailure {
            Log.w("CheapModelResolver", "catalog listing failed: ${it.message}", it)
        }.getOrDefault(emptyList())
            .filter { it != exclude && !it.startsWith("$MOA_PREFIX:") }

        return CheapModelHeuristic.pick(candidates) ?: fallback
    }

    /**
     * Model names for one provider, through [ModelContextCache] when available.
     *
     * Two reasons for the cache rather than a bare `listModels()`: it is the
     * same catalog the budget resolver already probes, so they share entries
     * instead of each paying for their own round-trip; and it lists chat-capable
     * models specifically, which is what an auxiliary text call wants.
     */
    private suspend fun namesFor(provider: Provider): List<String> {
        val cached = modelContextCache?.modelsFor(provider)?.map { it.name }.orEmpty()
        if (cached.isNotEmpty()) return cached
        return runCatching { provider.listModels() }
            .onFailure { Log.w("CheapModelResolver", "listModels failed for ${provider.prefix}: ${it.message}", it) }
            .getOrDefault(emptyList())
    }

    private companion object {
        /** The virtual multi-model provider — never a cheap auxiliary choice, it fans out. */
        const val MOA_PREFIX = "moa"
    }
}
