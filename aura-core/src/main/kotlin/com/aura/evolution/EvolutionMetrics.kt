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
    private val counters = mutableMapOf<String, Long>()

    fun record(event: String, delta: Long = 1) {
        counters[event] = counters.getOrDefault(event, 0L) + delta
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
