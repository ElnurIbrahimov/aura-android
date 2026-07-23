package com.aura.dream

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A consolidated summary written by [DreamConsolidator].
 *
 * A dream summary is a *new* memory that represents 3+ source memories
 * which the consolidator decided were paraphrases of the same fact.
 * Future retrieval hits the summary instead of 12 paraphrases of the
 * same idea.
 *
 * Lifecycle: a dream summary is never pruned by [com.aura.memory.MemoryStore.runDecayPass]
 * — it's a structural record, not a user fact. The source memories are
 * tagged with `consolidated:dream_<clusterId>` so future cycles skip them,
 * but they are NOT deleted in v1 (consolidation is non-destructive; v2
 * may add an opt-in "forget sources" mode).
 */
@Entity(
    tableName = "dream_summaries",
    indices = [
        Index("createdAt"),
        // clusterId is unique so re-running a cycle on the same cluster
        // upserts (REPLACE) instead of double-writing. This is the
        // idempotency contract that makes the cycle safe to re-run.
        Index(value = ["clusterId"], unique = true),
    ],
)
data class DreamSummaryEntity(
    @PrimaryKey val id: String,                 // "dream_<clusterId>"
    val clusterId: String,                      // MD5 of joined source content
    val compressedText: String,                 // 2-3 sentence summary
    val sourceMemoryIds: String,                // comma-separated
    val dominantTags: String,                   // comma-separated
    val sourceCount: Int,                       // # of source memories
    @ColumnInfo(name = "modelUsed") val modelUsed: String,    // which LLM summarized
    @ColumnInfo(name = "createdAt") val createdAt: Long = System.currentTimeMillis(),
)
