package com.aura.dream

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A contradiction detected between two dream summaries -- the same
 * cluster got a new summary that negates the older one (e.g. "user
 * prefers dark mode" -> "user switched to light mode").
 *
 * Python's [DreamConsolidator._contradiction_report] reads these
 * directly from the KG's CONTRADICTS edges. On Android we don't
 * have a contradiction-extraction pass yet, so this table is
 * populated by [DreamConsolidator.detectContradictions] using a
 * lightweight heuristic: if a new summary's text contains explicit
 * negation patterns ("no longer", "switched from", "instead of",
 * "used to") referencing the prior summary's content, record a
 * contradiction.
 *
 * Surfaced in the dream section of Settings + the MemoryScreen
 * "Dream Summaries" view so the user can resolve them.
 */
@Entity(
    tableName = "contradictions",
    indices = [
        Index("status"),
        Index(value = ["olderSummaryId", "newerSummaryId"], unique = true),
    ],
)
data class ContradictionEntity(
    @PrimaryKey val id: String,                 // "contra_<md5-8>"
    val olderSummaryId: String,
    val newerSummaryId: String,
    val olderText: String,
    val newerText: String,
    /** The phrase that triggered the contradiction detection. */
    val triggerPhrase: String,
    /** Confidence in 0.0..1.0. Heuristic-detected values are <= 0.7. */
    val confidence: Float = 0.6f,
    val status: String = "UNRESOLVED",          // UNRESOLVED | RESOLVED | DISMISSED
    /**
     * Beliefs this contradiction is between, when it came from belief
     * revision rather than summary-text comparison. Null for the existing
     * summary-level rows, which is why both are nullable — the two
     * detectors share this table but link different things.
     */
    val olderBeliefId: String? = null,
    val newerBeliefId: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val resolvedAt: Long? = null,
)
