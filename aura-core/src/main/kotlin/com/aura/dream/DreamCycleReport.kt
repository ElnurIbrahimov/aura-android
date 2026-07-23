package com.aura.dream

/**
 * Stats from one [DreamConsolidator.runCycle] pass.
 *
 * Surfaced in the Settings UI ("X summaries, Y clusters, Z chars saved")
 * and the WorkManager output bundle (so the on-success notification
 * can show "Dream: 3 summaries written").
 *
 * `totalCharsSaved` is computed as `sumOf source content length - new
 * summary length`. A typical 5-paraphrase cluster of "user is a
 * developer" produces ~600 chars of source + ~150 chars of summary =
 * 450 chars saved. The number is illustrative, not load-bearing.
 */
data class DreamCycleReport(
    val memoriesProcessed: Int = 0,
    val clustersFormed: Int = 0,
    val summariesWritten: Int = 0,
    val summariesSkippedDuplicate: Int = 0,
    val summariesSkippedSmall: Int = 0,
    val summariesFailedLlm: Int = 0,
    val totalCharsSaved: Int = 0,
    val durationMs: Long = 0L,
    val modelUsed: String = "",
    val cycleId: String = "",
) {
    /** A one-line summary suitable for the Settings UI. */
    fun statsLine(): String = buildString {
        append("$summariesWritten summaries, ")
        append("$clustersFormed clusters")
        if (totalCharsSaved > 0) append(", $totalCharsSaved chars saved")
    }

    /** Companion: empty report for no-op cycles (gated off, no work, etc). */
    companion object {
        val EMPTY = DreamCycleReport()
    }
}
