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
enum class ProactiveFindingType(val wire: String, val action: ProactiveAction) {
    STALE_MEMORIES("stale_memories", ProactiveAction.Navigate("memory")),
    STUCK_TASKS("stuck_tasks", ProactiveAction.Navigate("tasks")),
    RELATIONSHIP_GAP("relationship_gap", ProactiveAction.OpenChat()),

    /** No Aura screen shows a calendar; this hands off to the system app. */
    DEADLINE_APPROACHING("deadline_approaching", ProactiveAction.OpenCalendarApp),

    /** `"graph"` until now, which matched no route and would have thrown. */
    CONTRADICTION_ALERT("contradiction_alert", ProactiveAction.Navigate("knowledge_graph")),
    STRESS_CORRELATION("stress_correlation", ProactiveAction.OpenChat()),

    /** The one check that describes a state rather than proposing a move. */
    PATTERN_ALERT("pattern_alert", ProactiveAction.None),
    PRIORITY_SHIFT("priority_shift", ProactiveAction.Navigate("tasks")),

    /**
     * Aura has something it wants to ask and the user has not opened chat.
     *
     * The only category where the suggestion is for Aura's benefit rather than
     * the user's, which is exactly why it goes through the same earned-
     * interruption ledger as everything else and starts silent.
     */
    OPEN_QUESTION("open_question", ProactiveAction.OpenChat()),

    /**
     * The living world has news. Quotes the world's own narration; tapping
     * lands on Creative. Appended, not inserted — the per-category
     * notification ids are 1100 + ordinal, and reordering would swap which
     * notification replaces which.
     */
    LIVING_WORLD("living_world", ProactiveAction.Navigate("creative")),
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
