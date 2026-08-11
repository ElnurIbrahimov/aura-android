package com.aura.proactive

/**
 * The closed vocabulary of [ProactiveAwarenessEngine.ProactiveFinding] kinds.
 *
 * The finding type was a bare `String` on one side of the boundary and an
 * event-table column on the other, with nothing asserting the two ever used the
 * same words. They did not: findings said "stale_memories", recorded rows said
 * "DaemonInsight", and [SalienceFilter]'s recency and novelty terms compared
 * one set against the other and never matched.
 *
 * The values stay `String` at both ends — they travel through
 * `MotivationAccumulator.source` and a Room `TEXT` column, and turning either
 * into an enum column would need a migration for no gain — so this type is the
 * registry rather than the transport. [wire] is the persisted form;
 * `ProactiveFindingTypeCoverageTest` fails the build if a check in
 * [ProactiveAwarenessEngine] emits a type with no entry here.
 */
enum class ProactiveFindingType(val wire: String) {
    STALE_MEMORIES("stale_memories"),
    STUCK_TASKS("stuck_tasks"),
    RELATIONSHIP_GAP("relationship_gap"),
    DEADLINE_APPROACHING("deadline_approaching"),
    CONTRADICTION_ALERT("contradiction_alert"),
    STRESS_CORRELATION("stress_correlation"),
    PATTERN_ALERT("pattern_alert"),
    PRIORITY_SHIFT("priority_shift"),
    ;

    companion object {
        private val byWire: Map<String, ProactiveFindingType> = entries.associateBy { it.wire }

        /**
         * The type for a persisted [wire] value, or null when there is none.
         *
         * Null rather than a throw or a fallback constant: rows written before
         * the `payload` column carried anything decode to "", and every event
         * that did not come from a finding writes "" forever. Those are not
         * errors, they are "no finding behind this row", and [SalienceFilter]
         * has to skip them rather than treat them as a matching kind.
         */
        fun from(wire: String): ProactiveFindingType? = byWire[wire]
    }
}
