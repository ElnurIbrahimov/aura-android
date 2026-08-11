package com.aura.proactive

import javax.inject.Inject
import javax.inject.Singleton
import android.util.Log

/**
 * Salience Filter — determines which proactive findings are worth
 * surfacing to the user.
 *
 * Ported from Python Aura's `proactive/salience_filter.py`.
 *
 * Filters findings based on:
 *   recency (25%): How recent is this event type?
 *   relevance (35%): How related to current context?
 *   importance (25%): How urgent is this?
 *   novelty (15%): Have we seen this type recently?
 *
 * ## Why the threshold moved from 0.4 to 0.49
 *
 * Recency and novelty are the two terms that suppress repeats, and both keyed
 * off `finding.type in recentTypes`. `recentTypes` was built from the recorded
 * rows' `eventType` — "DaemonInsight", "MorningBriefReady" — while findings
 * carry "stale_memories", "stuck_tasks". No value in one set could ever appear
 * in the other, so every finding scored as never-before-seen and the pair was
 * inert. Now that [ProactiveFindingType] spans both sides, the score has a
 * reachable low end and the old threshold has to be re-derived against it.
 *
 * The scores that are actually reachable, given the eight checks in
 * [ProactiveAwarenessEngine] (urgency 0.2 … 0.7, and `pattern_alert` the only
 * check with no `actionRoute`, at urgency 0.2 or 0.3):
 *
 *   - never seen recently, no route:  0.590 … 0.605
 *   - never seen recently, route:     0.730 (u=0.2) … 0.855 (u=0.7)
 *   - seen recently, no route:        0.285 … 0.310
 *   - seen recently, route:           0.425 (u=0.2) … 0.550 (u=0.7)
 *
 * So the filter was never "mathematically incapable" of rejecting — the
 * formula's floor is 0.375, not 0.4 — but at 0.4 the only thing it rejected
 * was a repeated routeless `pattern_alert`. 0.49 is the threshold that states
 * the intended rule: **anything new always surfaces; a repeat has to carry
 * urgency ≥ 0.5.** Not 0.5 itself, which is the exact score of a repeated
 * route-bearing finding at urgency 0.5 — four of the eight checks
 * (`relationship_gap` past a week, `contradiction_alert`, `stress_correlation`,
 * `priority_shift`) sit there — and `Float` rounding makes a `>=` comparison at
 * that point a coin flip. 0.49 leaves the nearest reachable scores (0.475 at
 * u=0.4 and 0.500 at u=0.5) well clear on either side.
 *
 * The appetite multiplier from [ProactivePolicyEngine] rides on top. It is
 * exactly 1.0 when there is no interaction history, so the derivation above
 * holds for a fresh install; a user who dismisses most cards drives it to 0.7
 * or 0.4, which raises the bar for everything and — at 0.4 — mutes even novel
 * findings outright. That is the intended direction, but it is a mute, not a
 * damper, and it is the reason the multiplier is applied last where it can be
 * seen.
 */
@Singleton
class SalienceFilter @Inject constructor(
    private val proactiveEventDao: ProactiveEventDao,
    private val interactionDao: ProactiveInteractionDao? = null,
    private val policyEngine: ProactivePolicyEngine? = null,
) {
    data class SalienceWeights(
        val recency: Float = 0.25f,
        val relevance: Float = 0.35f,
        val importance: Float = 0.25f,
        val novelty: Float = 0.15f,
    )

    data class FilteredFinding(
        val finding: ProactiveAwarenessEngine.ProactiveFinding,
        val salience: Float,
        val passed: Boolean,
    )

    private val weights = SalienceWeights()

    suspend fun filter(findings: List<ProactiveAwarenessEngine.ProactiveFinding>): List<FilteredFinding> {
        // The finding type lives in `payload`, not `eventType`. Rows written
        // before that column carried anything decode to null and are skipped,
        // so an upgrade degrades to "nothing seen recently" rather than to a
        // wrong match. The window is shared with every other proactive event
        // (curiosity, council, LLM insight, morning brief), so a busy cycle can
        // push a finding row out of it — the filter suppresses recent repeats,
        // it does not remember forever.
        val recentTypes = runCatching {
            proactiveEventDao.recent(RECENT_EVENT_WINDOW)
                .mapNotNull { ProactiveFindingType.from(it.payload)?.wire }
                .toSet()
        }.onFailure { Log.w("SalienceFilter", "recent events unavailable: ${it.message}", it) }.getOrDefault(emptySet())

        val appetite = runCatching { proactiveAppetite() }
            .onFailure { Log.w("SalienceFilter", "appetite unavailable: ${it.message}", it) }
            .getOrDefault(1f)

        return findings.map { finding ->
            val seenRecently = finding.type in recentTypes
            val recency = if (seenRecently) 0.2f else 1.0f
            val relevance = if (finding.actionRoute != null) 0.8f else 0.4f
            val importance = finding.urgency
            val novelty = if (seenRecently) 0.3f else 1.0f

            val salience = (recency * weights.recency +
                            relevance * weights.relevance +
                            importance * weights.importance +
                            novelty * weights.novelty) * appetite

            FilteredFinding(finding, salience, salience >= SALIENCE_THRESHOLD)
        }
    }

    /**
     * How receptive the user has been to proactive cards, as a multiplier on
     * salience. This is [ProactivePolicyEngine]'s only production caller — the
     * class computed weights that nothing read.
     *
     * Returns exactly 1.0 with no recorded interactions, which is what keeps
     * the threshold derivation in this file's KDoc true on a fresh install.
     */
    private suspend fun proactiveAppetite(): Float {
        val engine = policyEngine ?: return 1f
        val summary = interactionDao?.summary().orEmpty()
        if (summary.isEmpty()) return 1f
        return engine.adaptFromSummary(summary, listOf(BASELINE_POLICY)).first().weight
    }

    private companion object {
        /** See this class's KDoc for why 0.49 and not 0.4 or 0.5. */
        const val SALIENCE_THRESHOLD = 0.49f

        /** How far back "have I surfaced this lately" looks, in recorded events. */
        const val RECENT_EVENT_WINDOW = 30

        /**
         * Baseline the policy engine adjusts. Only its `weight` is read back —
         * the engine's quiet-period field has no scheduler behind it, and this
         * file deliberately does not pretend otherwise.
         */
        val BASELINE_POLICY = ProactivePolicyEngine.Policy(
            eventType = "DaemonInsight",
            weight = 1f,
            quietPeriodMs = 0L,
        )
    }
}