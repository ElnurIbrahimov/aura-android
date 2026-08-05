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
 * Only findings above the salience threshold (0.4) reach the user.
 */
@Singleton
class SalienceFilter @Inject constructor(
    private val proactiveEventDao: ProactiveEventDao,
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
    private val SALIENCE_THRESHOLD = 0.4f

    suspend fun filter(findings: List<ProactiveAwarenessEngine.ProactiveFinding>): List<FilteredFinding> {
        val recentTypes = runCatching {
            proactiveEventDao.recent(30).map { it.eventType }.toSet()
        }.onFailure { Log.w("SalienceFilter", "runCatching failed: ${it.message}", it) }.getOrDefault(emptySet())

        return findings.map { finding ->
            val recency = if (finding.type in recentTypes) 0.2f else 1.0f
            val relevance = if (finding.actionRoute != null) 0.8f else 0.4f
            val importance = finding.urgency
            val novelty = if (finding.type !in recentTypes) 1.0f else 0.3f

            val salience = recency * weights.recency +
                           relevance * weights.relevance +
                           importance * weights.importance +
                           novelty * weights.novelty

            FilteredFinding(finding, salience, salience >= SALIENCE_THRESHOLD)
        }
    }
}