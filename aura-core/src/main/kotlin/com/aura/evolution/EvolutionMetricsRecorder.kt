package com.aura.evolution

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Lightweight in-memory + persisted metrics for evolution runs. Keeps the
 * last 30 runs in memory and records aggregate counts to settings.
 */
@Singleton
class EvolutionMetricsRecorder @Inject constructor(
    private val settingsDao: EvolutionSettingsDao,
) {
    private val recentRuns = ArrayDeque<RunMetric>()

    suspend fun recordRun(candidateCount: Int, durationMs: kotlin.Long) {
        val metric = RunMetric(candidateCount, durationMs, System.currentTimeMillis())
        synchronized(recentRuns) {
            recentRuns.addFirst(metric)
            while (recentRuns.size > 30) recentRuns.removeLast()
        }
        // Persist aggregate to each domain settings row (lightweight counters).
        for (domain in EvolutionDomain.entries) {
            val current = settingsDao.get(domain.name) ?: EvolutionSettingsEntity(domain = domain.name)
            settingsDao.upsert(
                current.copy(
                    totalRuns = current.totalRuns + 1,
                    totalCandidates = current.totalCandidates + candidateCount,
                    updatedAt = System.currentTimeMillis(),
                )
            )
        }
    }

    fun recent(): List<RunMetric> = synchronized(recentRuns) { recentRuns.toList() }

    data class RunMetric(val candidateCount: Int, val durationMs: kotlin.Long, val timestamp: kotlin.Long)
}
