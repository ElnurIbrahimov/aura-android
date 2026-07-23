package com.aura.dream

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A recurring tool-call N-gram mined by [DreamConsolidator.extractRoutines].
 *
 * The Python implementation reads `logs/metacognition/` JSONL files
 * to find tool-call sequences that recur across 3+ successful
 * goals. On Android the equivalent signal lives in
 * [com.aura.agent.Conversation.turns], specifically
 * [com.aura.agent.Turn.toolTurns] - every conversation turn that
 * called a tool has its full call list there.
 *
 * Routines are *not* user facts. They describe how Aura works, not
 * who the user is. Surfaced in the "X patterns" stat on the dream
 * section in Settings and as a row in MemoryScreen -> Dream Summaries.
 *
 * Lifecycle: routines never decay and never get archived. They're
 * structural metadata about Aura's own behavior.
 */
@Entity(
    tableName = "routines",
    indices = [
        // Frequent query: "show me routines with at least N occurrences".
        Index("occurrenceCount"),
        // Stable id derived from the joined tool sequence; unique so
        // re-running a cycle on the same N-gram upserts (REPLACE)
        // instead of double-writing.
        Index(value = ["signature"], unique = true),
    ],
)
data class RoutineEntity(
    @PrimaryKey val id: String,                 // "routine_<md5-8>"
    /**
     * Canonical signature: pipe-separated ordered list of tool names.
     * Example: "delegate_to_agent|delegate_to_agent" or
     * "memory_query|tavily_search|deep_research". Used to detect
     * re-occurrences across runs and to deduplicate via the unique
     * index.
     */
    val signature: String,
    /** Human-readable version of [signature] for UI. */
    val displayLabel: String,
    /** Number of times this N-gram appeared in the scanned corpus. */
    val occurrenceCount: Int,
    /** Number of distinct conversations where this N-gram appeared. */
    val distinctConversations: Int,
    /** Comma-separated conversation IDs where it was seen. Capped at 20. */
    val sourceConversationIds: String,
    /** First time this routine was seen (epoch ms). */
    val firstSeenAt: Long,
    /** Most recent occurrence (epoch ms). */
    val lastSeenAt: Long,
    /** Optional LLM-generated description of what this routine does. */
    val description: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)
