package com.aura.consciousness

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

private val Context.affinityStore by preferencesDataStore("agent_affinity")

/**
 * Tracks a persistent relationship affinity score (0-100) that increases
 * with each conversation turn and decays slowly without interaction.
 *
 * Levels:
 *   0-10   → Acquaintance
 *  11-25   → Familiar
 *  26-50   → Connected
 *  51-75   → Trusted
 *  76-100  → Close
 *
 * Each level unlocks different agent behaviors (injected into system prompt):
 * - Acquaintance: basic helpful responses
 * - Familiar: remembers preferences, uses names
 * - Connected: proactive outreach messages
 * - Trusted: proactive suggestions, deeper context use
 * - Close: emotional check-ins, warmer tone
 *
 * Score increases by 0.5 per conversation turn (capped at 100).
 * Score decays by 0.1 per day without interaction (min 0).
 */
@Singleton
class AffinityTracker @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    companion object {
        private val KEY_SCORE = floatPreferencesKey("affinity_score")
        private val KEY_LAST_INTERACTION = longPreferencesKey("last_interaction_ts")
        private const val INCREMENT_PER_TURN = 0.5f
        private const val DECAY_PER_DAY = 0.1f
        private const val MAX_SCORE = 100f
        private const val MIN_SCORE = 0f
    }

    data class AffinityState(
        val score: Float = 0f,
        val level: AffinityLevel = AffinityLevel.ACQUAINTANCE,
        val progressToNext: Float = 0f,
    )

    enum class AffinityLevel(val min: Float, val max: Float, val label: String, val directive: String) {
        ACQUAINTANCE(0f, 10f, "Acquaintance", "Keep responses helpful and informative. You are still getting to know the user."),
        FAMILIAR(11f, 25f, "Familiar", "Remember user preferences. Use their name occasionally. Be warm but professional."),
        CONNECTED(26f, 50f, "Connected", "Be proactive in suggestions. Reference past conversations. Show genuine interest."),
        TRUSTED(51f, 75f, "Trusted", "Offer deeper insights. Challenge assumptions respectfully. Use shared context freely."),
        CLOSE(76f, 100f, "Close", "Be emotionally present. Check in on wellbeing. Use warm, personal language. Share observations about the relationship.");

        companion object {
            fun fromScore(score: Float): AffinityLevel =
                entries.firstOrNull { score >= it.min && score <= it.max } ?: ACQUAINTANCE
        }
    }

    suspend fun load(): AffinityState {
        val prefs = context.affinityStore.data.first()
        val rawScore = prefs[KEY_SCORE] ?: 0f
        val lastInteraction = prefs[KEY_LAST_INTERACTION] ?: 0L
        val score = applyDecay(rawScore, lastInteraction)
        return toState(score)
    }

    /**
     * Call after each conversation turn to increase the affinity score.
     */
    suspend fun recordTurn() {
        val prefs = context.affinityStore.data.first()
        val rawScore = prefs[KEY_SCORE] ?: 0f
        val lastInteraction = prefs[KEY_LAST_INTERACTION] ?: System.currentTimeMillis()
        val decayed = applyDecay(rawScore, lastInteraction)
        val newScore = (decayed + INCREMENT_PER_TURN).coerceIn(MIN_SCORE, MAX_SCORE)
        val now = System.currentTimeMillis()
        context.affinityStore.edit {
            it[KEY_SCORE] = newScore
            it[KEY_LAST_INTERACTION] = now
        }
    }

    /**
     * Generate the system prompt directive for the current affinity level.
     */
    suspend fun getDirective(): String {
        val state = load()
        return state.level.directive
    }

    private fun applyDecay(score: Float, lastInteraction: Long): Float {
        if (lastInteraction == 0L) return score
        val daysSince = ((System.currentTimeMillis() - lastInteraction) / (1000L * 60 * 60 * 24)).toFloat()
        if (daysSince <= 0f) return score
        return (score - (daysSince * DECAY_PER_DAY)).coerceIn(MIN_SCORE, MAX_SCORE)
    }

    private fun toState(score: Float): AffinityState {
        val level = AffinityLevel.fromScore(score)
        val nextLevel = AffinityLevel.entries.firstOrNull { it.min > score }
        val progressToNext = if (nextLevel != null) {
            ((score - level.min) / (nextLevel.min - level.min)).coerceIn(0f, 1f)
        } else {
            1f // At max level
        }
        return AffinityState(score = score, level = level, progressToNext = progressToNext)
    }
}