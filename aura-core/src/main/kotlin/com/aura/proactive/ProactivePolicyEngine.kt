package com.aura.proactive

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Adaptive proactive policy engine. Reads interaction history and
 * tunes event-type weights. High dismiss rates suppress an event
 * type; high tap/acted rates amplify it. No LLM required.
 */
@Singleton
class ProactivePolicyEngine @Inject constructor() {

    data class Policy(
        val eventType: String,
        /** 0.0-1.0 weight multiplier applied to scheduling score. */
        val weight: Float,
        /** Minimum quiet period in ms before proposing again. */
        val quietPeriodMs: Long,
    )

    /**
     * Compute per-event-type weights from aggregated action counts.
     * Dismissed and snoozed actions lower the weight; tapped and acted
     * raise it. Baseline weight is 1.0.
     */
    fun adaptFromSummary(summaries: List<ActionCount>, defaults: List<Policy>): List<Policy> {
        val byAction = summaries.associate { it.action to it.count }
        val total = byAction.values.sum().coerceAtLeast(1)
        val dismissed = byAction["dismissed"] ?: 0
        val snoozed = byAction["snoozed"] ?: 0
        val negative = dismissed + snoozed
        val positive = (byAction["tapped"] ?: 0) + (byAction["acted"] ?: 0)
        val negativeRatio = negative.toFloat() / total
        val positiveRatio = positive.toFloat() / total

        val multiplier = when {
            negativeRatio > 0.6f -> 0.4f
            negativeRatio > 0.3f -> 0.7f
            positiveRatio > 0.4f -> 1.3f
            else -> 1.0f
        }

        return defaults.map { it.copy(weight = (it.weight * multiplier).coerceIn(0.1f, 2.0f)) }
    }
}
