package com.aura.emotion

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.atomic.AtomicReference
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

    private val stateRef = AtomicReference(EmotionSnapshot())

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

        val s = stateRef.get()

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

        stateRef.set(
            EmotionSnapshot(
                tension = finalTension,
                connection = connection,
                energy = finalEnergy,
                focus = focus,
                updatedAt = System.currentTimeMillis(),
            )
        )
    }

    /**
     * Decay all dimensions toward baseline. Called after each turn.
     */
    fun decay() {
        val baseline = EmotionSnapshot()
        val decayRate = 0.05f
        val current = stateRef.get()
        stateRef.set(
            EmotionSnapshot(
                tension = lerp(current.tension, baseline.tension, decayRate),
                connection = lerp(current.connection, baseline.connection, decayRate),
                energy = lerp(current.energy, baseline.energy, decayRate),
                focus = lerp(current.focus, baseline.focus, decayRate),
                updatedAt = System.currentTimeMillis(),
            )
        )
    }

    /**
     * Get the current emotion snapshot.
     */
    fun snapshot(): EmotionSnapshot = stateRef.get()

    /**
     * Get a human-readable mood string for the system prompt.
     */
    fun moodString(): String {
        val s = stateRef.get()
        return "tension=${"%.1f".format(s.tension)}, connection=${"%.1f".format(s.connection)}, " +
            "energy=${"%.1f".format(s.energy)}, focus=${"%.1f".format(s.focus)}"
    }

    /**
     * Resolve the active [ResponseProfile] from the current state.
     */
    fun profile(): ResponseProfile = ResponseProfile.from(stateRef.get())

    /**
     * Neuromodulator-style sampling adjustments (ported from Python Aura's
     * `_build_neuro_llm_options`). Maps the 4D emotional state onto LLM
     * sampling parameters so mood affects generation, not just prompt tone:
     *
     *  - energy  (0..1) → temperature:  0.45 (focused) .. 0.95 (exploratory)
     *  - focus   (0..1) → topP:         0.95 (loose) .. 0.80 (tight sampling)
     *
     * Values are clamped to safe ranges and only *adjust* the caller's
     * options — they never override an explicit caller choice (maxTokens
     * is left untouched: an explicit cap is a user decision).
     */
    fun samplingAdjustments(): SamplingAdjustments {
        val s = stateRef.get()
        val temperature = (0.45 + s.energy.coerceIn(0f, 1f) * 0.5).coerceIn(0.2, 1.2)
        val topP = (0.95 - s.focus.coerceIn(0f, 1f) * 0.15).coerceIn(0.7, 1.0)
        return SamplingAdjustments(
            temperature = temperature,
            topP = topP,
        )
    }

    /**
     * Applies [samplingAdjustments] to a [com.aura.providers.ChatOptions].
     * Fills only unset (null) fields — an explicit caller temperature or
     * topP is never overridden (compactor 0.1, write gate 0.1, evolution
     * 0.0, ... all mean it).
     */
    fun applySampling(options: com.aura.providers.ChatOptions): com.aura.providers.ChatOptions {
        val adj = samplingAdjustments()
        return options.copy(
            temperature = options.temperature ?: adj.temperature,
            topP = options.topP ?: adj.topP,
        )
    }

    data class SamplingAdjustments(
        val temperature: Double,
        val topP: Double,
    )

    /**
     * Persist the current state to DataStore (called periodically).
     */
    suspend fun save() {
        val s = stateRef.get()
        context.emotionPrefs.edit { prefs ->
            prefs[KEY_TENSION] = s.tension
            prefs[KEY_CONNECTION] = s.connection
            prefs[KEY_ENERGY] = s.energy
            prefs[KEY_FOCUS] = s.focus
            prefs[KEY_UPDATED] = s.updatedAt
        }
    }

    /**
     * Load persisted state from DataStore. Only overwrites the in-memory
     * state if any persisted keys exist, so an in-flight update from the
     * agentic loop is not accidentally clobbered by a parallel load.
     */
    suspend fun load() {
        val prefs = context.emotionPrefs.data.first()
        if (prefs[KEY_TENSION] == null && prefs[KEY_CONNECTION] == null &&
            prefs[KEY_ENERGY] == null && prefs[KEY_FOCUS] == null
        ) {
            return
        }
        stateRef.set(
            EmotionSnapshot(
                tension = prefs[KEY_TENSION] ?: 0.3f,
                connection = prefs[KEY_CONNECTION] ?: 0.5f,
                energy = prefs[KEY_ENERGY] ?: 0.4f,
                focus = prefs[KEY_FOCUS] ?: 0.3f,
                updatedAt = prefs[KEY_UPDATED] ?: System.currentTimeMillis(),
            )
        )
    }

    /**
     * Replace the emotional state from a backup and persist it.
     *
     * [load] deliberately refuses to clobber in-memory state when DataStore is
     * empty, which is right at bootstrap and wrong here: a restore is an
     * explicit instruction to overwrite.
     */
    suspend fun restore(restored: EmotionSnapshot) {
        stateRef.set(restored)
        save()
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