package com.aura.evolution

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Single entry point for running the evolution pipeline. The worker calls
 * this; tests and UI can also trigger it manually.
 *
 * The pipeline is:
 *   1. Run deterministic candidate detectors.
 *   2. (future) Reflect on high-confidence candidates with cloud model.
 *   3. (future) Promote approved candidates to proposals.
 *
 * For commit 5 the coordinator persists candidates and emits metrics.
 */
@Singleton
class EvolutionCoordinator @Inject constructor(
    private val detectors: EvolutionCandidateDetectors,
    private val metrics: EvolutionMetricsRecorder,
) {
    suspend fun runAll(): RunResult {
        val start = System.currentTimeMillis()
        val candidates = detectors.runAll()
        val duration = System.currentTimeMillis() - start
        metrics.recordRun(candidates.size, duration)
        return RunResult(candidates.size, duration)
    }

    data class RunResult(val candidateCount: Int, val durationMs: kotlin.Long)
}
