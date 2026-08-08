package com.aura.consciousness

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Theory of Mind — dynamic user mental model.
 *
 * Ported from Python Aura's `proactive/theory_of_mind.py`.
 *
 * Builds and maintains a model of the user:
 * 1. **Knowledge level per topic** — what they know/don't know
 * 2. **Emotional state** — predicted from message patterns
 * 3. **Communication preferences** — style adaptation
 * 4. **Anticipated needs** — proactive prediction
 *
 * All heuristic, no LLM calls. Updates from message analysis
 * (length, question marks, technical terms, sentiment markers).
 * Decays over time (1-week half-life on confidence).
 *
 * Injected into the system prompt so the agent adapts its communication
 * style to the user's predicted level and emotional state.
 *
 * Persistence: JSON file at `files/theory_of_mind.json`, same shape as
 * [NarrativeSelf]. Loaded on app start by `ProactiveBootstrap`, saved after
 * each update.
 *
 * The model held its state only in memory until 2026-08-08. Because
 * [toPrompt] stays silent until `commStyle.sampleCount >= 3`, and Android kills
 * the process between sessions, the counter almost never survived long enough
 * to reach three — so the class did its work and then discarded it before it
 * could be used. This is the same defect ENGINEERING_HISTORY §2.4 records
 * fixing for `EmotionEngine` ("save()/load() existed but were never called; 4D
 * emotional state reset every cold start"); it survived here and in
 * [IntrinsicMotivation].
 */
@Singleton
class TheoryOfMind @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val file: File get() = File(context.filesDir, "theory_of_mind.json")

    @Serializable
    data class TopicKnowledge(
        val topic: String = "",
        val level: Float = 0.5f,      // 0=novice, 0.5=intermediate, 1=expert
        val confidence: Float = 0.3f, // 0-1, decays over time
        val interactions: Int = 0,
        val lastSeen: Long = 0L,
        val signals: List<String> = emptyList(),
    )

    @Serializable
    data class EmotionalState(
        val valence: Float = 0f,    // -1 (negative) to +1 (positive)
        val arousal: Float = 0f,     // 0 (calm) to 1 (excited)
        val engagement: Float = 0.5f, // 0 (disengaged) to 1 (highly engaged)
        val frustration: Float = 0f, // 0 to 1
        val confidence: Float = 0.3f, // how sure we are
    )

    @Serializable
    data class CommStyle(
        val verbosity: Float = 0.5f,      // 0 (terse) to 1 (verbose)
        val formality: Float = 0.5f,      // 0 (casual) to 1 (formal)
        val technicalDepth: Float = 0.5f, // 0 (layperson) to 1 (expert)
        val avgMessageLength: Float = 0f, // chars
        val sampleCount: Int = 0,
    )

    @Serializable
    data class UserModel(
        val topics: Map<String, TopicKnowledge> = emptyMap(),
        val emotionalState: EmotionalState = EmotionalState(),
        val commStyle: CommStyle = CommStyle(),
        val lastInteractionAt: Long = 0L,
    )

    private val _model = MutableStateFlow(UserModel())
    val model: StateFlow<UserModel> = _model.asStateFlow()

    /**
     * Load the persisted user model. Called from `ProactiveBootstrap` on app
     * start. A missing or corrupt file leaves the in-memory model untouched
     * rather than clobbering it — bootstrap loads concurrently with the first
     * turn's update, and losing that update to a no-op load would reintroduce
     * the very reset this persistence exists to prevent.
     */
    suspend fun load() = withContext(Dispatchers.IO) {
        runCatching {
            if (!file.exists()) return@runCatching
            _model.value = json.decodeFromString(UserModel.serializer(), file.readText())
        }.onFailure { Log.w("TheoryOfMind", "load failed: ${it.message}", it) }
        Unit
    }

    /** Persist the current user model. Called after each mutation. */
    suspend fun save() = withContext(Dispatchers.IO) {
        runCatching {
            file.writeText(json.encodeToString(UserModel.serializer(), _model.value))
        }.onFailure { Log.w("TheoryOfMind", "save failed: ${it.message}", it) }
        Unit
    }

    /**
     * Update the user model from a message.
     * Heuristic: length, question marks, technical terms, sentiment markers.
     */
    fun updateFromMessage(message: String) {
        val m = _model.value
        val now = System.currentTimeMillis()

        // Communication style: update avg message length (running average)
        val newAvg = if (m.commStyle.sampleCount == 0) {
            message.length.toFloat()
        } else {
            (m.commStyle.avgMessageLength * m.commStyle.sampleCount + message.length) /
                (m.commStyle.sampleCount + 1)
        }
        // Formality: messages with proper capitalization + no slang → higher
        val formality = computeFormality(message)
        // Technical depth: presence of technical terms
        val techDepth = computeTechnicalDepth(message)

        // Emotional state: sentiment markers
        val valence = computeValence(message)
        val arousal = minOf(1f, message.count { it == '!' } / 3f)
        val engagement = if (message.length > 50) 0.8f else if (message.length > 10) 0.5f else 0.3f
        val frustration = computeFrustration(message)

        _model.value = m.copy(
            commStyle = m.commStyle.copy(
                avgMessageLength = newAvg,
                formality = formality,
                technicalDepth = techDepth,
                sampleCount = m.commStyle.sampleCount + 1,
            ),
            emotionalState = EmotionalState(
                valence = valence,
                arousal = arousal,
                engagement = engagement,
                frustration = frustration,
                confidence = minOf(1f, m.emotionalState.confidence + 0.05f),
            ),
            lastInteractionAt = now,
        )
    }

    /**
     * Record knowledge about a specific topic.
     */
    fun updateTopic(topic: String, levelDelta: Float, signal: String) {
        val m = _model.value
        val now = System.currentTimeMillis()
        val existing = m.topics[topic]
        val updated = if (existing != null) {
            existing.copy(
                level = minOf(1f, maxOf(0f, existing.level + levelDelta)),
                interactions = existing.interactions + 1,
                lastSeen = now,
                confidence = minOf(1f, existing.confidence + 0.1f),
                signals = (existing.signals + signal).takeLast(5),
            )
        } else {
            TopicKnowledge(
                topic = topic,
                level = 0.5f + levelDelta,
                confidence = 0.3f,
                interactions = 1,
                lastSeen = now,
                signals = listOf(signal),
            )
        }
        _model.value = m.copy(topics = m.topics + (topic to updated))
    }

    /**
     * Apply time-based confidence decay to all topics.
     * Call from a periodic worker (e.g. DecayWorker).
     */
    fun decayTopics(hoursElapsed: Float) {
        val decayFactor = Math.exp(-0.693 * hoursElapsed / 168.0) // 1-week half-life
        val m = _model.value
        _model.value = m.copy(
            topics = m.topics.mapValues { (_, tk) ->
                tk.copy(confidence = tk.confidence * decayFactor.toFloat())
            },
        )
    }

    /**
     * Format for system prompt injection.
     * Returns empty string if no meaningful model has been built.
     */
    fun toPrompt(): String {
        val m = _model.value
        if (m.commStyle.sampleCount < 3) return ""
        val parts = mutableListOf<String>()

        // Communication style
        val style = m.commStyle
        if (style.technicalDepth > 0.7f) parts.add("User communicates at expert level")
        else if (style.technicalDepth < 0.3f) parts.add("User prefers non-technical explanations")
        if (style.formality < 0.3f) parts.add("User communicates casually")
        else if (style.formality > 0.7f) parts.add("User communicates formally")

        // Emotional state
        val emo = m.emotionalState
        if (emo.frustration > 0.5f) parts.add("User may be frustrated — be extra clear and helpful")
        if (emo.valence < -0.3f) parts.add("User sentiment seems negative — be supportive")
        if (emo.engagement > 0.7f) parts.add("User is highly engaged — can go deeper")

        // Topic knowledge
        val expertTopics = m.topics.filter { it.value.level > 0.7f }.keys.take(3)
        if (expertTopics.isNotEmpty()) parts.add("User expertise: ${expertTopics.joinToString(", ")}")
        val noviceTopics = m.topics.filter { it.value.level < 0.3f }.keys.take(3)
        if (noviceTopics.isNotEmpty()) parts.add("User learning: ${noviceTopics.joinToString(", ")}")

        if (parts.isEmpty()) return ""
        return "[User Model]\n" + parts.joinToString("\n")
    }

    // ── Heuristic helpers ───────────────────────────────────────────

    private fun computeFormality(text: String): Float {
        val hasCapital = text.any { it.isUpperCase() }
        val hasPunct = text.any { it in ".,!?" }
        val slang = listOf("lol", "btw", "tbh", "ngl", "afk", "imo").any { it in text.lowercase() }
        var score = 0.5f
        if (hasCapital && hasPunct) score += 0.15f
        if (slang) score -= 0.2f
        return minOf(1f, maxOf(0f, score))
    }

    private fun computeTechnicalDepth(text: String): Float {
        val techTerms = listOf("api", "database", "kernel", "compiler", "algorithm", "protocol",
            "architecture", "refactor", "async", "concurrent", "serialize", "gradient",
            "matrix", "protocol", "schema", "migration", "deploy", "ci/cd", "docker")
        val lower = text.lowercase()
        val hits = techTerms.count { it in lower }
        return minOf(1f, hits / 5f + 0.3f)
    }

    private fun computeValence(text: String): Float {
        val positive = listOf("good", "great", "love", "thanks", "perfect", "awesome", "nice", "cool", "amazing")
        val negative = listOf("bad", "hate", "broken", "wrong", "stupid", "annoying", "frustrated", "angry")
        val lower = text.lowercase()
        val pos = positive.count { it in lower }.toFloat()
        val neg = negative.count { it in lower }.toFloat()
        return (pos - neg).coerceIn(-1f, 1f)
    }

    private fun computeFrustration(text: String): Float {
        val markers = listOf("wtf", "again", "still", "why is", "not working", "broken", "doesn't work")
        val lower = text.lowercase()
        val hits = markers.count { it in lower }
        val allCaps = text.count { it.isUpperCase() } > text.length * 0.3 && text.length > 5
        return minOf(1f, hits * 0.3f + if (allCaps) 0.3f else 0f)
    }
}