package com.aura.providers

import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Short-lived cache of [Provider.listModelsWithContext] results, keyed by
 * provider prefix.
 *
 * That call is a live network round-trip for most providers, and for
 * `OllamaCloudProvider` it is worse than one: it probes `/api/show` per model,
 * eight at a time. `ContextBudgetResolver` calls it on **every step of every
 * agentic turn** — a ten-step turn meant ten fan-outs — because it had no cache
 * of its own. `ConversationCompactor` had already hit this and solved it
 * privately with a five-minute map; this is that solution, extracted so both
 * callers share one set of entries instead of racing to build two.
 *
 * A model catalog changes when the user adds a provider key or a vendor ships a
 * model, so minutes-stale is fine and a process-lifetime cache would not be.
 */
@Singleton
class ModelContextCache @Inject constructor() {

    private data class Entry(val models: List<ModelInfo>, val at: Long, val failed: Boolean)

    private val entries = ConcurrentHashMap<String, Entry>()

    /**
     * Model metadata for [provider], from cache when fresh.
     *
     * A failed lookup is cached too, for a much shorter window. Without that, a
     * provider that is down, rate-limited or misconfigured gets re-probed on
     * every single step — the exact case where the cache is needed most, and the
     * one a naive "only cache successes" policy misses entirely. Failures return
     * an empty list rather than throwing, matching what the callers already do
     * with a failed probe.
     */
    suspend fun modelsFor(provider: Provider): List<ModelInfo> {
        val now = System.currentTimeMillis()
        val cached = entries[provider.prefix]
        if (cached != null) {
            val ttl = if (cached.failed) FAILURE_TTL_MS else SUCCESS_TTL_MS
            if (now - cached.at < ttl) return cached.models
        }
        return runCatching { provider.listModelsWithContext() }
            .onSuccess { entries[provider.prefix] = Entry(it, now, failed = false) }
            .onFailure {
                Log.w("ModelContextCache", "catalog probe failed for ${provider.prefix}: ${it.message}", it)
                entries[provider.prefix] = Entry(emptyList(), now, failed = true)
            }
            .getOrDefault(emptyList())
    }

    /** Drop everything. For tests, and for a provider-key change that should re-probe now. */
    fun invalidate() {
        entries.clear()
    }

    private companion object {
        const val SUCCESS_TTL_MS = 5 * 60 * 1000L

        /**
         * Short enough that a provider coming back up is picked up within a
         * turn or two, long enough that a hard-down provider is not re-probed
         * on every step of the turn that is failing because of it.
         */
        const val FAILURE_TTL_MS = 30 * 1000L
    }
}
