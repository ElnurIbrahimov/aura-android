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
 *
 * Phases 5-7 (routines, contradictions, densify) report their own
 * counters here. The Python implementation surfaces these in a
 * separate `ConsolidationReport.routines` / `graph_proposals` /
 * `contradiction_ids` collections; on Android we keep the structure
 * flat because all three are bounded to a few rows per cycle.
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
    // Phase 5: extract routines
    val routinesExtracted: Int = 0,
    val routineOccurrences: Int = 0,
    // Phase 6: detect contradictions
    val contradictionsFound: Int = 0,
    // Phase 7: densify graph
    val graphEdgesProposed: Int = 0,
    // Phase 8: prune stale (mirrors Python's _prune_stale_sqlite)
    val memoriesArchived: Int = 0,
    // Phase 9: user-profile update (mirrors Python's update_profile_from_memories)
    val profileUpdated: Boolean = false,
) {
    /** A one-line summary suitable for the Settings UI. */
    fun statsLine(): String = buildString {
        append("$summariesWritten summaries, ")
        append("$clustersFormed clusters")
        if (routinesExtracted > 0) append(", $routinesExtracted routines")
        if (contradictionsFound > 0) append(", $contradictionsFound contradictions")
        if (graphEdgesProposed > 0) append(", $graphEdgesProposed graph edges proposed")
        if (memoriesArchived > 0) append(", $memoriesArchived archived")
        if (totalCharsSaved > 0) append(", $totalCharsSaved chars saved")
    }

    companion object {
        val EMPTY = DreamCycleReport()
    }
}
