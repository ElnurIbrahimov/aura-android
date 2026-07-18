package com.aura.emotion

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import javax.inject.Inject
import javax.inject.Singleton

private val Context.emotionPrefs by preferencesDataStore(name = "aura_emotion")

/**
 * Tracks 4 emotional dimensions across the conversation:
 *
 * - tension: how stressed/pressured the user feels (0=calm, 1=stressed)
 * - connection: how engaged/warm the interaction feels (0=distant, 1=warm)
 * - energy: how active/fast-paced the conversation is (0=slow, 1=energetic)
 * - focus: how concentrated/technical the conversation is (0=casual, 1=focused)
 *
 * Each dimension has inertia (how fast it changes) and decay (how fast it
 * returns to baseline). The model sees the current state in the system
 * prompt so it can adapt its tone naturally.
 */
@Singleton
class EmotionEngine @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    @Serializable
    data class EmotionSnapshot(
        val tension: Float = 0.3f,
        val connection: Float = 0.5f,
        val energy: Float = 0.4f,
        val focus: Float = 0.3f,
        val updatedAt: Long = System.currentTimeMillis(),
    )

    private var state = EmotionSnapshot()

    // Heuristic signal patterns
    private val frustrationPatterns = listOf(
        "\\b(angry|frustrated|annoyed|irritated|pissed|mad)\\b",
        "\\b(doesn'?t work|broken|wrong|stupid|ridiculous)\\b",
        "\\b(wtf|omg|ugh|seriously)\\b",
        "\\b(no|nope|stop|enough)\\b",
    ).map { Regex(it, RegexOption.IGNORE_CASE) }

    private val satisfactionPatterns = listOf(
        "\\b(great|awesome|perfect|excellent|love it|amazing|fantastic)\\b",
        "\\b(thanks|thank you|appreciate|helpful|good job)\\b",
        "\\b(yes|exactly|that'?s right|correct)\\b",
    ).map { Regex(it, RegexOption.IGNORE_CASE) }

    private val questionPattern = Regex("\\?", RegexOption.IGNORE_CASE)
    private val codeRequestPattern = Regex("\\b(code|function|class|method|api|debug|error|stack ?trace)\\b", RegexOption.IGNORE_CASE)

    /**
     * Update emotion state based on the latest user message.
     */
    fun update(userMessage: String) {
        val msg = userMessage.trim()
        if (msg.isBlank()) return

        val s = state.copy()

        // Length-based signals
        val len = msg.length
        val tension = when {
            len < 20 -> s.tension + 0.05f  // short messages slightly raise tension
            len > 200 -> s.tension - 0.02f  // long messages lower tension (user is engaging)
            else -> s.tension
        }

        val energy = when {
            len > 150 -> s.energy + 0.05f
            len < 15 -> s.energy - 0.03f
            else -> s.energy
        }

        // Pattern-based signals
        val hasFrustration = frustrationPatterns.any { it.containsMatchIn(msg) }
        val hasSatisfaction = satisfactionPatterns.any { it.containsMatchIn(msg) }
        val hasQuestion = questionPattern.containsMatchIn(msg)
        val hasCodeRequest = codeRequestPattern.containsMatchIn(msg)

        val finalTension = when {
            hasFrustration -> (tension + 0.15f).coerceAtMost(1f)
            hasSatisfaction -> (tension - 0.15f).coerceAtLeast(0f)
            else -> tension
        }

        val connection = when {
            hasSatisfaction -> (s.connection + 0.1f).coerceAtMost(1f)
            hasFrustration -> (s.connection - 0.05f).coerceAtLeast(0f)
            len > 100 -> (s.connection + 0.03f).coerceAtMost(1f)
            else -> s.connection
        }

        val focus = when {
            hasCodeRequest -> (s.focus + 0.15f).coerceAtMost(1f)
            hasQuestion -> (s.focus + 0.05f).coerceAtMost(1f)
            else -> (s.focus - 0.02f).coerceAtLeast(0f)
        }

        val finalEnergy = when {
            hasFrustration -> (energy + 0.1f).coerceAtMost(1f)
            hasSatisfaction -> (energy + 0.05f).coerceAtMost(1f)
            else -> energy
        }

        state = EmotionSnapshot(
            tension = finalTension,
            connection = connection,
            energy = finalEnergy,
            focus = focus,
            updatedAt = System.currentTimeMillis(),
        )
    }

    /**
     * Decay all dimensions toward baseline. Called after each turn.
     */
    fun decay() {
        val baseline = EmotionSnapshot()
        val decayRate = 0.05f
        state = EmotionSnapshot(
            tension = lerp(state.tension, baseline.tension, decayRate),
            connection = lerp(state.connection, baseline.connection, decayRate),
            energy = lerp(state.energy, baseline.energy, decayRate),
            focus = lerp(state.focus, baseline.focus, decayRate),
            updatedAt = System.currentTimeMillis(),
        )
    }

    /**
     * Get the current emotion snapshot.
     */
    fun snapshot(): EmotionSnapshot = state

    /**
     * Get a human-readable mood string for the system prompt.
     */
    fun moodString(): String {
        val s = state
        return "tension=${"%.1f".format(s.tension)}, connection=${"%.1f".format(s.connection)}, " +
            "energy=${"%.1f".format(s.energy)}, focus=${"%.1f".format(s.focus)}"
    }

    /**
     * Resolve the active [ResponseProfile] from the current state.
     */
    fun profile(): ResponseProfile = ResponseProfile.from(state)

    /**
     * Persist the current state to DataStore (called periodically).
     */
    suspend fun save() {
        context.emotionPrefs.edit { prefs ->
            prefs[KEY_TENSION] = state.tension
            prefs[KEY_CONNECTION] = state.connection
            prefs[KEY_ENERGY] = state.energy
            prefs[KEY_FOCUS] = state.focus
            prefs[KEY_UPDATED] = state.updatedAt
        }
    }

    /**
     * Load persisted state from DataStore.
     */
    suspend fun load() {
        val prefs = context.emotionPrefs.data.first()
        state = EmotionSnapshot(
            tension = prefs[KEY_TENSION] ?: 0.3f,
            connection = prefs[KEY_CONNECTION] ?: 0.5f,
            energy = prefs[KEY_ENERGY] ?: 0.4f,
            focus = prefs[KEY_FOCUS] ?: 0.3f,
            updatedAt = prefs[KEY_UPDATED] ?: System.currentTimeMillis(),
        )
    }

    private fun lerp(current: Float, target: Float, t: Float): Float =
        current + (target - current) * t

    companion object {
        private val KEY_TENSION = floatPreferencesKey("tension")
        private val KEY_CONNECTION = floatPreferencesKey("connection")
        private val KEY_ENERGY = floatPreferencesKey("energy")
        private val KEY_FOCUS = floatPreferencesKey("focus")
        private val KEY_UPDATED = longPreferencesKey("updated_at")
    }
}