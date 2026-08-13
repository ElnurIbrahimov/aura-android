package com.aura.providers

import com.aura.usage.UsageTracker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Holds all providers, routes by `provider:model` prefix.
 * Mirrors aura/providers/registry.py + aura/core/router.py task-aware routing.
 */
@Singleton
class ProviderRegistry @Inject constructor(
    private val providers: Map<String, @JvmSuppressWildcards Provider>,
    private val providerKeys: ProviderKeys,
    private val usageTracker: UsageTracker = UsageTracker(),
    // Appended with a default so the existing positional constructions in the
    // provider test suites keep compiling. An in-memory budget with a real clock
    // is the right no-op for a test that is not about spending.
    private val backgroundBudget: com.aura.usage.BackgroundBudget =
        com.aura.usage.BackgroundBudget { System.currentTimeMillis() },
) {
    private val byPrefix: Map<String, Provider> = providers.mapKeys { (key, _) -> "$key:" }

    /** Resolve a fully-qualified `provider:model` id without network I/O. */
    suspend fun parse(modelId: String): Pair<Provider, String> {
        val parts = modelId.split(":", limit = 2)
        require(parts.size == 2 && parts[0].isNotBlank() && parts[1].isNotBlank()) {
            "Model id must be fully qualified as provider:model."
        }
        val provider = byPrefix[parts[0] + ":"]
            ?: throw IllegalArgumentException("Unknown provider prefix: ${parts[0]}")
        return provider to parts[1]
    }

    /**
     * Resolve a model id and dispatch a chat request to the matching
     * provider. Suspending so the dispatch participates in structured
     * concurrency — all 7 callers (Brain.stream, LlmWriteGate,
     * DeepResearchTool, KnowledgeGraphTool, TranslateTool,
     * QuickAskActivity) are already inside coroutine contexts and
     * the previous `runBlocking { parse }` was blocking the calling
     * thread (main thread when invoked from a Compose context).
     */
    suspend fun chat(
        modelId: String,
        messages: List<ProviderMessage>,
        options: ChatOptions = ChatOptions(),
        tools: List<ToolDefinition> = emptyList(),
    ): Flow<ProviderChunk> {
        // Ensure the async DataStore key load has completed before
        // dispatching. On cold start, the first chat can race the init
        // load — keyFor() returns null, the provider sends a blank
        // Bearer token, and the user gets a 401. awaitLoaded() suspends
        // (does NOT block) until the load finishes, which takes
        // 5-100ms on a warm start and ~200ms on a cold start.
        providerKeys.awaitLoaded()
        // The one place every LLM call in the app passes through, which is the
        // only place a spend ceiling cannot be forgotten by a new caller. The
        // check is on unattended work only — see ChatOptions.attended for why
        // refusing an attended turn would be the worse failure.
        if (!options.attended && !backgroundBudget.hasHeadroom()) {
            backgroundBudget.recordBlocked()
            val spend = backgroundBudget.snapshot()
            throw com.aura.usage.BackgroundBudgetExhausted(spend.tokens, spend.limit)
        }
        val (provider, model) = parse(modelId)
        val upstream = provider.chat(model, messages, options, tools)
        // MoA dispatches its reference and aggregator calls back through this
        // registry. Track those concrete calls and skip the synthetic outer
        // flow so a single MoA answer is not double-counted.
        if (provider.prefix == "moa") return upstream.flowOn(Dispatchers.IO)
        return flow {
            var outputChars = 0
            var exactUsage: Usage? = null
            var billableChunkSeen = false
            try {
                upstream.collect { chunk ->
                    outputChars += chunk.text?.length ?: 0
                    if (chunk.usage != null) exactUsage = chunk.usage
                    if (chunk.text != null || chunk.thinking != null || chunk.toolCall != null || chunk.usage != null || chunk.finishReason != null) {
                        billableChunkSeen = true
                    }
                    emit(chunk)
                }
            } finally {
                if (billableChunkSeen) {
                    usageTracker.recordLlmCall(
                        modelId = modelId,
                        inputChars = messages.sumOf { it.content.length },
                        outputChars = outputChars,
                        reportedUsage = exactUsage,
                    )
                    if (!options.attended) {
                        // Charged after the fact, against the same estimate
                        // UsageTracker uses, so the two numbers agree. The cap is
                        // therefore soft by one call: a single request cannot be
                        // known to be expensive until it has been made.
                        val spent: Long = exactUsage
                            ?.let { (it.promptTokens + it.completionTokens).toLong() }
                            ?: ((messages.sumOf { it.content.length } + outputChars).toLong() / CHARS_PER_TOKEN)
                        backgroundBudget.record(spent)
                    }
                    logUsage(modelId, exactUsage)
                }
            }
        }.flowOn(Dispatchers.IO)
    }

    /**
     * One line per call, carrying the prompt-cache figures.
     *
     * This is the evidence for whether prompt caching is worth keeping. Reading
     * it over a week of real turns answers the question that decides whether
     * dynamic tool selection is worth building at all — a high cache-hit rate
     * on steps 2..N means the tool schemas are already discounted to near-noise
     * and there is nothing left for tool selection to save.
     *
     * A low rate is a bug report, not a verdict: the most likely causes are a
     * per-step read making the "stable" prefix differ by one character, or a
     * prompt under the provider's minimum cacheable length. Both look identical
     * from the outside, and neither is fixed by sending fewer tools.
     *
     * `estimated` is called out explicitly because a zero from a provider that
     * reports no usage is indistinguishable from a genuine cache miss.
     */
    private fun logUsage(modelId: String, usage: Usage?) {
        if (usage == null) {
            android.util.Log.d(TAG, "usage $modelId: estimated (provider reported none)")
            return
        }
        val pct = if (usage.promptTokens > 0) {
            (usage.cachedPromptTokens * 100) / usage.promptTokens
        } else {
            0
        }
        android.util.Log.d(
            TAG,
            "usage $modelId: prompt=${usage.promptTokens} cached=${usage.cachedPromptTokens} " +
                "($pct%) cacheWrite=${usage.cacheWritePromptTokens} completion=${usage.completionTokens}",
        )
    }

    fun configured(): List<Provider> = providers.values.filter { it.isConfigured() }
    fun all(): List<Provider> = providers.values.toList()
    fun get(prefix: String): Provider? = byPrefix["$prefix:"]

    private companion object {
        const val TAG = "ProviderRegistry"

        /** Same 4-chars-per-token estimate `UsageTracker` uses, so the two agree. */
        const val CHARS_PER_TOKEN = 4
    }
}

