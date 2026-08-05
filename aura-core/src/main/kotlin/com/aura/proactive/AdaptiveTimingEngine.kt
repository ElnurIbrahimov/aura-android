package com.aura.proactive

import javax.inject.Inject
import javax.inject.Singleton
import android.util.Log

/**
 * Adaptive Timing Engine — learns when the user engages vs dismisses
 * proactive messages and only sends during high-engagement windows.
 *
 * Inspired by ProActor (ACL 2026) timing-aware scheduling and
 * ProMemAssist (UIST 2025) context-aware deferral.
 */
@Singleton
class AdaptiveTimingEngine @Inject constructor(
    private val proactiveInteractionDao: ProactiveInteractionDao,
) {
    suspend fun hourlyEngagement(): FloatArray {
        val interactions = runCatching {
            proactiveInteractionDao.recent(200)
        }.onFailure { Log.w("AdaptiveTimingEngine", "runCatching failed: ${it.message}", it) }.getOrDefault(emptyList())

        val scores = FloatArray(24) { 0f }
        for (interaction in interactions) {
            val hour = ((interaction.timestamp / (1000L * 60 * 60)) % 24).toInt()
            when (interaction.action) {
                "tapped", "acted" -> scores[hour] += 1f
                "dismissed", "snoozed" -> scores[hour] -= 0.5f
            }
        }
        val max = scores.maxOrNull()?.coerceAtLeast(1f) ?: 1f
        return scores.map { (it / max).coerceIn(0f, 1f) }.toFloatArray()
    }

    suspend fun isGoodTime(): Boolean {
        val scores = hourlyEngagement()
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        return scores[hour] >= 0.4f
    }

    suspend fun bestTime(): Int {
        val scores = hourlyEngagement()
        var bestHour = 9
        var bestScore = 0f
        for (hour in 0..23) {
            if (scores[hour] > bestScore) {
                bestScore = scores[hour]
                bestHour = hour
            }
        }
        return bestHour
    }
}