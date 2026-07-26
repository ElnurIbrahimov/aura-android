package com.aura.world

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.min

/** A belief and the evidence supporting it. */
data class BeliefSide(
    val belief: BeliefEntity,
    val evidence: List<EvidenceEntity>,
)

/** Outcome of arbitrating two conflicting beliefs. */
sealed interface Verdict {
    data class Winner(
        val winning: BeliefEntity,
        val losing: BeliefEntity,
        val margin: Float,
    ) : Verdict

    /** Neither side is sufficiently better supported. Do not revise. */
    data object TooClose : Verdict
}

/**
 * Decides which of two conflicting beliefs is better supported.
 *
 * Deliberately dependency-free — no Room, no coroutines — so the scoring rule
 * can be unit-tested exhaustively and driven directly by the convergence eval.
 *
 * Last-write-wins is the obvious rule and it is wrong: it lets one offhand
 * remark overturn a belief supported by a year of consistent behaviour. The
 * scoring below weights recency most heavily (people genuinely change) but
 * requires a real margin before acting.
 */
object BeliefArbiter {

    /** Minimum score gap before a revision is allowed. See design spec §6. */
    const val ARBITER_MIN_MARGIN = 0.15f

    // Sum to 1.0. SOURCE_WEIGHT is 0.25 rather than a smaller "moderate"
    // value because a pure source-rank difference must be able to clear
    // ARBITER_MIN_MARGIN on its own: a direct user statement should beat a
    // derived inference when nothing else separates them. At 0.15 the gap was
    // 0.105 and every such pair returned TooClose.
    private const val RECENCY_WEIGHT = 0.40f
    private const val CORROBORATION_WEIGHT = 0.25f
    private const val SOURCE_WEIGHT = 0.25f
    private const val CONFIDENCE_WEIGHT = 0.10f

    /** Evidence half-life for recency scoring. */
    private const val HALF_LIFE_DAYS = 30.0
    private const val DAY_MS = 86_400_000.0

    fun arbitrate(a: BeliefSide, b: BeliefSide, now: Long = System.currentTimeMillis()): Verdict {
        val scoreA = score(a, now)
        val scoreB = score(b, now)
        val margin = abs(scoreA - scoreB)
        if (margin < ARBITER_MIN_MARGIN) return Verdict.TooClose
        return if (scoreA > scoreB) {
            Verdict.Winner(a.belief, b.belief, margin)
        } else {
            Verdict.Winner(b.belief, a.belief, margin)
        }
    }

    /** Normalised 0..1 support score. */
    internal fun score(side: BeliefSide, now: Long): Float {
        if (side.evidence.isEmpty()) return 0f
        return RECENCY_WEIGHT * recency(side, now) +
            CORROBORATION_WEIGHT * corroboration(side) +
            SOURCE_WEIGHT * sourceRank(side) +
            CONFIDENCE_WEIGHT * side.belief.confidence
    }

    /** Exponential decay on the newest supporting evidence. */
    private fun recency(side: BeliefSide, now: Long): Float {
        val newest = side.evidence.maxOf { it.timestamp }
        val ageDays = (now - newest).coerceAtLeast(0L) / DAY_MS
        return exp(-ageDays / HALF_LIFE_DAYS).toFloat()
    }

    /**
     * Distinct supporting turns, saturating at 4. Repetition separates a real
     * change of mind from a one-off; beyond a handful it stops being
     * informative.
     */
    private fun corroboration(side: BeliefSide): Float {
        val distinct = side.evidence.map { it.detailJson }.distinct().size
        return min(distinct, 4) / 4f
    }

    /** A direct statement outranks a tool result, which outranks an inference. */
    private fun sourceRank(side: BeliefSide): Float =
        side.evidence.maxOf {
            when (it.source) {
                "user_statement" -> 1.0f
                "tool_result", "calendar", "notification" -> 0.6f
                "kg_edge" -> 0.6f
                else -> 0.3f
            }
        }
}
