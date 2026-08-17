package com.aura.calibration

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.aura.memory.CorrectionEntity
import com.aura.world.BeliefEntity

/**
 * A verdict on one of Aura's own claims.
 *
 * `BeliefEntity.confidence` is asserted thousands of times and has never been
 * checked against anything. The only thing in the codebase that calls itself
 * verification — `BeliefDao.verify`, reached from `BeliefPromoter` — fires when
 * the same knowledge-graph edge is observed a second time, which is repetition
 * counted as evidence rather than a test of the claim. A row here is the first
 * time anything in Aura records whether a stated confidence was *earned*.
 *
 * **Silence is deliberately absent from this table.** A belief nothing ever
 * contradicted produces no row. Scoring the un-contradicted as correct would
 * report near-perfect accuracy over a system that has learned nothing, because
 * most beliefs are never revisited — the calibration would be a flattering lie
 * and the most useless possible outcome. Only claims that reached an actual
 * verdict are here, so the sample is small and honest rather than large and
 * meaningless.
 */
@Entity(
    tableName = "claim_resolutions",
    foreignKeys = [
        ForeignKey(
            entity = BeliefEntity::class,
            parentColumns = ["id"],
            childColumns = ["beliefId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("beliefId"),
        Index("verdict"),
        // The grouping calibration actually reads: reliability per source, per
        // verdict. Global reliability is a number that describes nothing.
        Index("beliefSource", "verdict"),
        Index("resolvedAt"),
    ],
)
data class ClaimResolutionEntity(
    @PrimaryKey val id: String,
    val beliefId: String,
    /** [VERDICT_NEVER_TRUE], [VERDICT_NO_LONGER_TRUE] or [VERDICT_CONFIRMED]. */
    val verdict: String,
    /** How the verdict arrived: [SOURCE_CHAT_ANSWER] and friends. */
    val verdictSource: String,
    /**
     * The confidence the belief carried **at the moment it was resolved**.
     *
     * A snapshot, not a join. If applying the calibration curve back onto stored
     * confidence is ever wired — `BeliefDao.verify` is the write path waiting for
     * it — then every belief's live confidence starts moving, and a historical
     * grade read live would silently re-grade the past every time the present
     * changed. The same denormalisation reasoning `ProactiveOutcomeEntity`
     * records for `findingType`.
     */
    val assertedConfidence: Float,
    /**
     * `source` of the belief's earliest [com.aura.world.EvidenceEntity], carried
     * here so calibration can group without a join.
     *
     * Load-bearing, because Aura's confidences come from unrelated places:
     * `OpportunityEngine` hardcodes 0.6/0.7/0.8/0.9/0.95 by hand, `BeliefArbiter`
     * computes a margin, KG edges carry their own. Averaging those produces a
     * figure that is true of no source and useful for none.
     */
    val beliefSource: String,
    /** What the user actually said, when they said anything. */
    val note: String = "",
    val resolvedAt: Long = System.currentTimeMillis(),
) {
    companion object {
        /**
         * It was wrong when it was formed. **A miss.**
         *
         * Deliberately the same string as [CorrectionEntity.NEVER_TRUE] rather
         * than a parallel vocabulary: the user is already asked to draw exactly
         * this distinction when correcting a memory, and two taxonomies for one
         * judgment is how they drift apart.
         */
        const val VERDICT_NEVER_TRUE = CorrectionEntity.NEVER_TRUE

        /**
         * It was right and the world moved. **Not a miss, and not a hit.**
         *
         * Excluded from scoring entirely. Counting a world change as an error
         * makes measured confidence fall as Aura learns more, which is both
         * wrong and the kind of wrong that looks like the system working.
         */
        const val VERDICT_NO_LONGER_TRUE = CorrectionEntity.NO_LONGER_TRUE

        /** Independent evidence said yes. **A hit.** */
        const val VERDICT_CONFIRMED = "confirmed"

        /** The user answered a verification question in conversation. */
        const val SOURCE_CHAT_ANSWER = "chat_answer"

        /** The user graded a belief directly on the Mind screen. */
        const val SOURCE_MIND_SCREEN = "mind_screen"

        /** A memory correction propagated to a belief promoted from it. */
        const val SOURCE_INHERITED_CORRECTION = "inherited_correction"

        val VERDICTS = setOf(VERDICT_NEVER_TRUE, VERDICT_NO_LONGER_TRUE, VERDICT_CONFIRMED)

        val VERDICT_SOURCES = setOf(SOURCE_CHAT_ANSWER, SOURCE_MIND_SCREEN, SOURCE_INHERITED_CORRECTION)

        /**
         * The verdicts that carry a right/wrong signal.
         *
         * [VERDICT_NO_LONGER_TRUE] is absent on purpose and every query that
         * computes a rate must filter on this set. It is the single rule most
         * likely to be lost in a later edit, which is why it is a named constant
         * rather than an inline condition repeated in three places.
         */
        val SCORED = setOf(VERDICT_NEVER_TRUE, VERDICT_CONFIRMED)

        /** 1.0 when the claim held, 0.0 when it did not. Null when unscorable. */
        fun outcome(verdict: String): Float? = when (verdict) {
            VERDICT_CONFIRMED -> 1f
            VERDICT_NEVER_TRUE -> 0f
            else -> null
        }
    }
}
