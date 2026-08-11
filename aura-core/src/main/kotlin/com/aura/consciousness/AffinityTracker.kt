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

    /**
     * Min-threshold semantics: a level applies from its [min] up to (but
     * not including) the next level's min. Continuous by construction —
     * the old (min, max) ranges had gaps (10-11, 25-26, ...) where a
     * fractional score like 10.5 matched nothing and silently fell back
     * to ACQUAINTANCE.
     */
    enum class AffinityLevel(val min: Float, val label: String, val directive: String) {
        ACQUAINTANCE(0f, "Acquaintance", "Keep responses helpful and informative. You are still getting to know the user."),
        FAMILIAR(11f, "Familiar", "Remember user preferences. Use their name occasionally. Be warm but professional."),
        CONNECTED(26f, "Connected", "Be proactive in suggestions. Reference past conversations. Show genuine interest."),
        TRUSTED(51f, "Trusted", "Offer deeper insights. Challenge assumptions respectfully. Use shared context freely."),
        CLOSE(76f, "Close", "Be emotionally present. Check in on wellbeing. Use warm, personal language. Share observations about the relationship.");

        companion object {
            fun fromScore(score: Float): AffinityLevel =
                entries.last { score.coerceIn(0f, 100f) >= it.min }
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
        // Invalidate cache — the level may have changed.
        cachedDirective = null
    }

    private var cachedDirective: String? = null

    /**
     * Generate the system prompt directive for the current affinity level.
     * Cached — the DataStore read only happens once per process lifetime
     * (the score changes slowly, 0.5 per turn).
     */
    suspend fun getDirective(): String {
        cachedDirective?.let { return it }
        val state = load()
        cachedDirective = state.level.directive
        return state.level.directive
    }

    /**
     * Invalidate the directive cache. Call after recordTurn() if the
     * level may have changed.
     */
    fun invalidateCache() {
        cachedDirective = null
    }

    /**
     * The stored score and last-interaction timestamp, before decay.
     *
     * [load] applies decay before returning, which is correct for reading the
     * level and wrong for a backup: exporting an already-decayed score and
     * restoring it lets [applyDecay] run over the same elapsed days a second
     * time, so every export/restore roundtrip would quietly cost the user
     * affinity.
     */
    suspend fun exportRaw(): Pair<Float, Long> {
        val prefs = context.affinityStore.data.first()
        return (prefs[KEY_SCORE] ?: 0f) to (prefs[KEY_LAST_INTERACTION] ?: 0L)
    }

    /** Write a raw score/timestamp pair back. Restore path only. */
    suspend fun restoreRaw(score: Float, lastInteractionAt: Long) {
        context.affinityStore.edit {
            it[KEY_SCORE] = score.coerceIn(MIN_SCORE, MAX_SCORE)
            it[KEY_LAST_INTERACTION] = lastInteractionAt
        }
        cachedDirective = null
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