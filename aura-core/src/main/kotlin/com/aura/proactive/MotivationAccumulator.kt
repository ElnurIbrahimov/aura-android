package com.aura.proactive

import android.util.Log
import com.aura.data.UserPreferences
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Motivation-Threshold Proactive Message System.
 *
 * Scores each potential proactive message on 5 factors. Only delivers
 * when the score exceeds a learned threshold that adapts from user
 * engagement/dismissal. Replaces the fixed-timer approach.
 *
 * Ported from Python Aura's `proactive/motivation_accumulator.py`.
 *
 * Factors:
 *   relevance_to_user  × 0.30  — How relevant is this to the user's current focus
 *   time_since_similar  × 0.20  — How long since a similar message was sent
 *   emotional_urgency   × 0.20  — EmotionEngine urgency signal
 *   curiosity_drive    × 0.15  — Curiosity-driven relevance
 *   user_receptivity   × 0.15  — AdaptiveTiming / TheoryOfMind belief
 */
@Singleton
class MotivationAccumulator @Inject constructor(
    private val proactiveInteractionDao: ProactiveInteractionDao,
    private val userPreferences: UserPreferences,
) {
    data class PotentialMessage(
        val content: kotlin.String,
        val source: kotlin.String, // "curiosity", "staleness", "deadline", "daemon", etc.
        val relevanceToUser: Float = 0.5f,    // 0-1
        val timeSinceSimilar: Float = 0.5f,   // 0-1 (0=just sent, 1=long ago)
        val emotionalUrgency: Float = 0.5f,   // 0-1
        val curiosityDrive: Float = 0.5f,     // 0-1
        val userReceptivity: Float = 0.5f,    // 0-1
    )

    data class MotivationScore(
        val score: Float,
        val threshold: Float,
        val shouldDeliver: Boolean,
        val breakdown: kotlin.String,
    )

    /**
     * Compute the motivation score for a potential message.
     * 5-factor weighted formula.
     */
    fun score(message: PotentialMessage): Float {
        return message.relevanceToUser * 0.30f +
               message.timeSinceSimilar * 0.20f +
               message.emotionalUrgency * 0.20f +
               message.curiosityDrive * 0.15f +
               message.userReceptivity * 0.15f
    }

    /**
     * Adaptive threshold: engagement lowers it (more messages),
     * dismissal raises it (fewer messages).
     * Base threshold is 0.5. Adjusts ±0.2 based on recent interaction
     * ratios. Clamped to [0.2, 0.8].
     */
    suspend fun currentThreshold(): Float {
        val baseThreshold = 0.5f
        val recentInteractions = runCatching {
            proactiveInteractionDao.recent(20)
        }.onFailure { Log.w("Motivation", "interactions read failed: ${it.message}", it) }
            .getOrDefault(emptyList())

        if (recentInteractions.isEmpty()) return baseThreshold

        val engaged = recentInteractions.count { it.action == "tapped" || it.action == "acted" }
        val dismissed = recentInteractions.count { it.action == "dismissed" || it.action == "snoozed" }
        val total = recentInteractions.size
        val engagementRatio = engaged.toFloat() / total
        val dismissalRatio = dismissed.toFloat() / total

        // High engagement → lower threshold (more messages)
        // High dismissal → raise threshold (fewer messages)
        return (baseThreshold - engagementRatio * 0.2f + dismissalRatio * 0.2f)
            .coerceIn(0.2f, 0.8f)
    }

    /**
     * Evaluate a potential message. Returns the score, threshold,
     * and whether it should be delivered.
     */
    suspend fun evaluate(message: PotentialMessage): MotivationScore {
        val s = score(message)
        val threshold = currentThreshold()
        return MotivationScore(
            score = s,
            threshold = threshold,
            shouldDeliver = s >= threshold,
            breakdown = "score=$s, threshold=$threshold, " +
                "rel=${message.relevanceToUser}, time=${message.timeSinceSimilar}, " +
                "urg=${message.emotionalUrgency}, curio=${message.curiosityDrive}, " +
                "recept=${message.userReceptivity}",
        )
    }
}