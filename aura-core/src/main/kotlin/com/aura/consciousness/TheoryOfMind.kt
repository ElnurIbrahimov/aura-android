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
     * Replace the user model from a backup and persist it.
     *
     * The value that actually matters here is `commStyle.sampleCount`:
     * [toPrompt] stays silent below three samples, so a restore that dropped
     * the counter gave the user a fresh install that had their memories and
     * had forgotten how they talk.
     */
    suspend fun restore(restored: UserModel) {
        _model.value = restored
        save()
    }

    /**
     * Update the user model from a message.
     * Heuristic: length, question marks, technical terms, sentiment markers.
     *
     * Topics are updated here rather than through a public `updateTopic` /
     * `decayTopics` pair. Those existed, had no production caller, and were
     * deleted on 2026-08-22 — so `UserModel.topics` was a persisted, backed-up
     * map that nothing ever wrote, and [toPrompt]'s two topic lines could not
     * render. One entry point that the loop already calls cannot be forgotten
     * the way a second one was.
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
            topics = updateTopics(m.topics, message, m.lastInteractionAt, now),
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
        val hits = topicsIn(text).size
        return minOf(1f, hits / 5f + 0.3f)
    }

    /**
     * Which known subjects this message is about.
     *
     * The same list [computeTechnicalDepth] scores on. It counted these hits
     * and threw away which ones they were, while `UserModel.topics` — designed
     * to hold exactly that, persisted, and carried through backup — stayed
     * empty forever. One vocabulary, read twice, so a term added for depth
     * scoring is a term the topic model learns too.
     *
     * A closed list, not free extraction, and that is the point: the map is
     * bounded by construction, the model is deterministic, and a stray proper
     * noun cannot become a claim about what the user knows.
     */
    private fun topicsIn(text: String): Set<String> {
        val lower = text.lowercase()
        return TERM_PATTERNS.entries
            .filterTo(mutableSetOf()) { (_, pattern) -> pattern.containsMatchIn(lower) }
            .mapTo(mutableSetOf()) { it.key }
    }

    /**
     * Fold this message into the topic map, after ageing what was already there.
     *
     * Two directions, and the asymmetry is the whole model:
     *
     * - **Using** a term is weak evidence of knowing it. Level rises by
     *   [LEVEL_STEP_USED].
     * - **Asking about** a term is strong evidence of not knowing it. Level
     *   falls by [LEVEL_STEP_ASKED], which is larger, because "how does the
     *   scheduler work" is a much clearer signal than mentioning the scheduler
     *   in passing.
     *
     * That is what [toPrompt]'s two topic branches were always shaped for —
     * `level > 0.7` reads as expertise, `level < 0.3` as learning — and both
     * were unreachable while nothing wrote the map.
     *
     * Confidence decays on elapsed wall time with a one-week half-life, which
     * is the decay this class's KDoc has always documented. A topic that falls
     * under [MIN_CONFIDENCE] is dropped rather than kept at nearly zero: the
     * map is a claim about the user, and a claim nobody is confident about is
     * one this should stop making.
     */
    private fun updateTopics(
        existing: Map<String, TopicKnowledge>,
        message: String,
        lastInteractionAt: Long,
        now: Long,
    ): Map<String, TopicKnowledge> {
        // First interaction ever: nothing has aged, so no decay to apply.
        val elapsed = if (lastInteractionAt <= 0L) 0L else (now - lastInteractionAt).coerceAtLeast(0L)
        val decay = if (elapsed == 0L) 1f else {
            Math.pow(0.5, elapsed.toDouble() / TOPIC_HALF_LIFE_MS).toFloat()
        }

        val aged = existing.mapValues { (_, t) -> t.copy(confidence = t.confidence * decay) }
            .filterValues { it.confidence >= MIN_CONFIDENCE }

        val mentioned = topicsIn(message)
        if (mentioned.isEmpty()) return aged

        val asking = isQuestion(message)
        val signal = if (asking) "asked" else "used"
        val out = aged.toMutableMap()
        for (topic in mentioned) {
            val prior = out[topic] ?: TopicKnowledge(topic = topic)
            val level = if (asking) {
                prior.level - LEVEL_STEP_ASKED
            } else {
                prior.level + LEVEL_STEP_USED
            }
            out[topic] = prior.copy(
                topic = topic,
                level = level.coerceIn(0f, 1f),
                // Evidence raises confidence regardless of direction: knowing
                // the user is a novice is as much a fact as knowing they are not.
                confidence = minOf(1f, maxOf(prior.confidence, MIN_CONFIDENCE) + CONFIDENCE_STEP),
                interactions = prior.interactions + 1,
                lastSeen = now,
                // Bounded, newest last. Unbounded it would grow one entry per
                // message per topic and be written to disk on every turn.
                signals = (prior.signals + signal).takeLast(MAX_SIGNALS),
            )
        }
        return out
    }

    /**
     * Whether the message is asking about its subject rather than using it.
     *
     * A question mark is the strong form. The interrogative openers catch the
     * ones typed without it, which is most of them in a chat box.
     */
    private fun isQuestion(text: String): Boolean {
        if ('?' in text) return true
        val lower = text.trimStart().lowercase()
        return QUESTION_OPENERS.any { lower.startsWith(it) }
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

    private companion object {
        /** The subjects this model can hold an opinion about. See [topicsIn]. */
        val TECH_TERMS = listOf(
            "api", "database", "kernel", "compiler", "algorithm", "protocol",
            "architecture", "refactor", "async", "concurrent", "serialize", "gradient",
            "matrix", "schema", "migration", "deploy", "ci/cd", "docker",
        )

        /**
         * Whole words only, plus an optional plural.
         *
         * `computeTechnicalDepth` matched these as bare substrings, which made
         * "therapist", "capital" and "rapidly" all count as `api`. That was
         * invisible while the only consequence was a slightly inflated float
         * nobody could trace back. It stops being invisible the moment the
         * topic map exists, because the prompt then tells the model
         * "User expertise: api" on the strength of the user mentioning their
         * therapist — a confident, specific, wrong claim about a person.
         *
         * Lookarounds on alphanumerics rather than ``, because `ci/cd`
         * contains a non-word character and `ci/cd` does not mean what it
         * looks like it means.
         */
        val TERM_PATTERNS: Map<String, Regex> = TECH_TERMS.associateWith { term ->
            Regex("(?<![a-z0-9])" + Regex.escape(term) + "s?(?![a-z0-9])")
        }

        val QUESTION_OPENERS = listOf(
            "how ", "what ", "why ", "when ", "where ", "which ", "can i", "can you",
            "should i", "is there", "do i", "does ",
        )

        /** One week, the half-life this class's KDoc has always documented. */
        const val TOPIC_HALF_LIFE_MS = 7L * 24 * 60 * 60 * 1000

        /** Below this a topic is dropped rather than kept as a claim nobody believes. */
        const val MIN_CONFIDENCE = 0.05f

        const val CONFIDENCE_STEP = 0.15f

        /** Using a term is weak evidence of knowing it. */
        const val LEVEL_STEP_USED = 0.08f

        /** Asking about one is stronger evidence of not knowing it. */
        const val LEVEL_STEP_ASKED = 0.12f

        const val MAX_SIGNALS = 5
    }
}