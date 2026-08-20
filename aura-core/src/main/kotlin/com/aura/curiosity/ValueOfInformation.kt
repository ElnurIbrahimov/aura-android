package com.aura.curiosity

/**
 * Scores a candidate subject by how much knowing about it would change what Aura does.
 *
 * `CuriosityStore.scanAndAuthor` refuses to author a question while one is open, so exactly
 * one exists at a time and this ranking is the entire decision — choose badly and the only
 * question Aura gets to ask is spent, with nothing better authorable until it closes.
 *
 * What it ranked by before was `kindBase × detectorConfidence`. Confidence answers "am I sure
 * I found a gap", which is a different question from "does this gap matter": a contradiction
 * the detector is certain about, concerning something said once in passing, outranked a
 * less-certain gap in the entity half the graph points at.
 *
 * The per-kind judgement is kept rather than replaced. It encodes something true — a wrong
 * belief corrupts every future recall while a stale memory only limits one — and the point
 * here is to weigh it against how much of the model actually touches the subject.
 *
 * Pure, and it takes looked-up values rather than DAOs, so it is testable with no mock, no
 * emulator and no key — the [com.aura.creative.livingworld.NotabilityScorer] arrangement.
 */
object ValueOfInformation {

    /** What the scanner looked up about a subject, so this stays a pure function. */
    data class Signals(
        /** How much of the model touches this subject: edges, accesses, shared ids. */
        val reach: Int = 0,
        /** Epoch ms the subject was last touched. 0 = unknown, treated as fresh. */
        val lastTouchedAt: Long = 0L,
    )

    /**
     * [priority] is the scanner's existing per-kind score, multiplied by two bounded factors.
     *
     * Multiplicative rather than additive so that neither term can rescue a subject the other
     * has ruled out: an unreferenced dead subject stays low whatever its kind, and no amount
     * of reach turns a trivial kind into the most important thing on the list.
     */
    fun score(priority: Float, signals: Signals, now: Long): Double =
        priority.toDouble() * reachFactor(signals.reach) * recencyFactor(signals.lastTouchedAt, now)

    /**
     * Saturating, in `1.0 .. 1.0 + REACH_WEIGHT`.
     *
     * A hub with ten thousand edges is not two thousand times more worth asking about than
     * one with five; past a point, more connections stop being evidence of more consequence.
     * Unbounded reach would also let a single mega-entity win every ranking forever.
     */
    private fun reachFactor(reach: Int): Double {
        if (reach <= 0) return 1.0
        return 1.0 + REACH_WEIGHT * (reach / (reach + REACH_HALF))
    }

    /**
     * Decays to [RECENCY_FLOOR] over [RECENCY_HORIZON_MS], never to zero.
     *
     * Something untouched for a year is probably not what to spend the question on, but "not
     * now" is not "never" — a floor rather than a cliff keeps it rankable if nothing fresher
     * exists. An unknown timestamp counts as fresh: absence of a record is not evidence of age.
     */
    private fun recencyFactor(lastTouchedAt: Long, now: Long): Double {
        if (lastTouchedAt <= 0L) return 1.0
        val age = (now - lastTouchedAt).coerceAtLeast(0L)
        val fraction = (age.toDouble() / RECENCY_HORIZON_MS).coerceAtMost(1.0)
        return 1.0 - (1.0 - RECENCY_FLOOR) * fraction
    }

    /**
     * A score as 0-100, for storing beside the question it chose.
     *
     * The maximum a score can reach is `MAX_PRIORITY x (1 + REACH_WEIGHT)`, so the scale is
     * fixed rather than relative to whatever else was on the list that night — two questions
     * scored weeks apart stay comparable.
     */
    fun percent(score: Double): Int =
        ((score / (MAX_PRIORITY * (1.0 + REACH_WEIGHT))) * 100).toInt().coerceIn(0, 100)

    /** The largest `Subject.priority` the scanner can produce: CONTRADICTION_BASE at full confidence. */
    const val MAX_PRIORITY = 1.0

    /** Reach at which half of [REACH_WEIGHT] has been earned. */
    const val REACH_HALF = 5.0

    /** Most that reach alone can multiply a score by, less one. */
    const val REACH_WEIGHT = 1.0

    /** Floor of the recency factor, so age never zeroes a subject out. */
    const val RECENCY_FLOOR = 0.5

    /** Age at which recency has decayed fully to its floor. */
    const val RECENCY_HORIZON_MS = 90L * 24 * 60 * 60 * 1000
}
