package com.aura.dream

/**
 * A single cluster-summary produced by [DreamConsolidator.runCycle].
 *
 * This is the in-memory representation; the persisted form is
 * [DreamSummaryEntity]. The two are kept separate because:
 * 1. The consolidator produces a stream of [DreamSummary]s during a
 *    cycle; only the survivors (clusters that pass min size + dedup
 *    check) become rows in Room.
 * 2. The entity uses comma-separated strings for portability with
 *    the existing schema; the in-memory form uses typed lists.
 */
data class DreamSummary(
    val clusterId: String,
    val sourceMemoryIds: List<String>,
    val compressedText: String,
    val dominantTags: List<String>,
    val sourceCount: Int,
    val modelUsed: String,
) {
    /** Persistable form. [id] follows the convention `dream_<clusterId>`. */
    fun toEntity(): DreamSummaryEntity = DreamSummaryEntity(
        id = "dream_$clusterId",
        clusterId = clusterId,
        compressedText = compressedText,
        sourceMemoryIds = sourceMemoryIds.joinToString(","),
        dominantTags = dominantTags.joinToString(","),
        sourceCount = sourceCount,
        modelUsed = modelUsed,
    )
}
