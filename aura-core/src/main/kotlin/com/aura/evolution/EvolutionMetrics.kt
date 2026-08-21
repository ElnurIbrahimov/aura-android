package com.aura.evolution

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Shadow metrics for the evolution system. Tracks proposal lifecycle
 * outcomes so the user can evaluate whether auto-improvement is helping.
 *
 * These counts are kept in memory and persisted on demand; they are not
 * tied to a single run, so cumulative totals survive as long as the process.
 */
@Singleton
class EvolutionMetrics @Inject constructor() {
    /**
     * Concurrent, and updated through [java.util.concurrent.ConcurrentHashMap.merge].
     *
     * This is a `@Singleton` with three injectors, all of which record from
     * coroutines on `Dispatchers.IO`. The counters were a plain `mutableMapOf`
     * updated with a read-then-write, so concurrent records could interleave
     * and lose one, and a resize during iteration could throw where nothing
     * expects it. The counts exist to tell the user whether auto-improvement is
     * helping — silently undercounting is the one failure that makes them
     * worse than absent.
     */
    private val counters = java.util.concurrent.ConcurrentHashMap<String, Long>()

    fun record(event: String, delta: Long = 1) {
        counters.merge(event, delta) { old, add -> old + add }
    }

    fun count(event: String): Long = counters.getOrDefault(event, 0L)

    fun snapshot(): Map<String, Long> = counters.toSortedMap()

    fun reset() {
        counters.clear()
    }

    fun score(): Float {
        val approved = count("proposal.approved")
        val applied = count("proposal.applied")
        val rolledBack = count("proposal.rolled_back")
        val rejected = count("proposal.rejected")
        val total = approved + applied + rolledBack + rejected
        return if (total == 0L) 0f else (applied - rolledBack).toFloat() / total.toFloat()
    }
}
