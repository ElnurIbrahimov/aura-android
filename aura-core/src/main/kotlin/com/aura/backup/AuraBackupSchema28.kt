package com.aura.backup

import com.aura.calibration.ClaimResolutionEntity
import kotlinx.serialization.Serializable

/**
 * Backup type added in schema v28 — verdicts on Aura's own claims.
 *
 * Small, slow-growing, and the most expensive rows in the export to lose. Every
 * other table refills itself: memories accumulate from conversation, beliefs
 * from the graph, project notes from the sweep. A resolution is a **judgment a
 * person made once**, and there is no process anywhere that would produce it
 * again. Losing this table means losing the only measurement Aura has of whether
 * its own confidence means anything, and it would take months to rebuild by
 * asking the user the same questions a second time.
 *
 * [ClaimResolutionBackup.assertedConfidence] travels as recorded and is never
 * recomputed on restore. It is a snapshot of what the belief claimed at the
 * moment it was graded; re-deriving it from the restored belief would silently
 * re-grade history against whatever the confidence happens to be now.
 */
@Serializable
data class ClaimResolutionBackup(
    val id: String,
    val beliefId: String,
    val verdict: String,
    val verdictSource: String,
    val assertedConfidence: Float,
    val beliefSource: String,
    val note: String = "",
    val resolvedAt: Long = 0L,
)

internal fun ClaimResolutionEntity.toBackup() = ClaimResolutionBackup(
    id = id,
    beliefId = beliefId,
    verdict = verdict,
    verdictSource = verdictSource,
    assertedConfidence = assertedConfidence,
    beliefSource = beliefSource,
    note = note,
    resolvedAt = resolvedAt,
)

internal fun ClaimResolutionBackup.toEntity() = ClaimResolutionEntity(
    id = id,
    beliefId = beliefId,
    verdict = verdict,
    verdictSource = verdictSource,
    assertedConfidence = assertedConfidence,
    beliefSource = beliefSource,
    note = note,
    resolvedAt = resolvedAt,
)
