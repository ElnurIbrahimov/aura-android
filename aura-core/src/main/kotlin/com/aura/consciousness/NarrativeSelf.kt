package com.aura.consciousness

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Evolving identity narrative — gives Aura a sense of becoming.
 *
 * Ported from Python Aura's `narrative_self.py`. The narrative is a
 * ~400-600 token self-model loaded into every system prompt and updated
 * after significant interactions and during Dream consolidation.
 *
 * Unlike the static [Brain.IDENTITY] (which is the core persona), the
 * NarrativeSelf captures what Aura has learned about itself and its
 * relationship with the user. It evolves over time.
 *
 * Persistence: JSON file at `files/narrative_self.json`. Loaded on
 * app start by [ProactiveBootstrap], updated after agentic loop runs.
 *
 * ## Structure
 *
 * - **core_identity**: ~200 tokens, from Soul, rarely changes
 * - **recent_growth**: ~200 tokens, updated after significant interactions
 * - **active_concerns**: 3-5 items the agent is "thinking about"
 * - **unresolved_questions**: 3-5 open questions about the user or world
 * - **relationship_state**: ~100 tokens, how the relationship is going
 * - **identity_anchors**: immutable creeds (never overwritten by updates)
 */
@Singleton
class NarrativeSelf @Inject constructor(
    private val context: Context,
) {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val file: File get() = File(context.filesDir, "narrative_self.json")

    @Volatile
    private var state: NarrativeState = NarrativeState()

    /**
     * Load persisted state. Called from [ProactiveBootstrap] on app start.
     * If the file doesn't exist or is corrupt, starts from a blank state.
     */
    suspend fun load() = withContext(Dispatchers.IO) {
        runCatching {
            if (file.exists()) {
                val text = file.readText()
                state = json.decodeFromString(NarrativeState.serializer(), text)
            }
        }.onFailure { Log.w("NarrativeSelf", "load failed: ${it.message}") }
    }

    /**
     * Persist current state to disk.
     */
    suspend fun save() = withContext(Dispatchers.IO) {
        runCatching {
            file.writeText(json.encodeToString(NarrativeState.serializer(), state))
        }.onFailure { Log.w("NarrativeSelf", "save failed: ${it.message}") }
    }

    /**
     * Format the narrative for system prompt injection.
     * Returns empty string if no narrative has been built yet.
     */
    fun toPrompt(): String {
        val s = state
        val parts = mutableListOf<String>()
        if (s.coreIdentity.isNotBlank()) parts.add(s.coreIdentity)
        if (s.recentGrowth.isNotBlank()) parts.add("Recent growth: ${s.recentGrowth}")
        if (s.activeConcerns.isNotEmpty()) {
            parts.add("Active concerns: ${s.activeConcerns.take(5).joinToString("; ")}")
        }
        if (s.unresolvedQuestions.isNotEmpty()) {
            parts.add("Open questions: ${s.unresolvedQuestions.take(5).joinToString("; ")}")
        }
        if (s.relationshipState.isNotBlank()) {
            parts.add("Relationship: ${s.relationshipState}")
        }
        if (parts.isEmpty()) return ""
        return "[Self-Model]\n" + parts.joinToString("\n")
    }

    /**
     * Snapshot the current narrative state.
     */
    fun snapshot(): NarrativeState = state

    /**
     * Update the narrative after a significant interaction.
     *
     * This is a lightweight heuristic update — it shifts the recent_growth
     * text and rotates active concerns. The full LLM-driven update
     * happens during Dream consolidation (phase 6).
     *
     * @param userMessage The user's last message
     * @param assistantResponse The assistant's last response
     */
    fun updateFromInteraction(userMessage: String, assistantResponse: String) {
        val s = state
        // Heuristic: if the user's message is long (>200 chars) or contains
        // question marks, it's "significant" — update the growth note.
        val isSignificant = userMessage.length > 200 || userMessage.count { it == '?' } >= 2
        if (!isSignificant) return

        val growthNote = buildString {
            append("Discussed: ")
            append(userMessage.take(100).replace("\n", " "))
            append(" → ")
            append(assistantResponse.take(100).replace("\n", " "))
        }.take(300)

        state = s.copy(
            recentGrowth = growthNote,
            lastUpdated = System.currentTimeMillis(),
            version = s.version + 1,
        )
    }

    /**
     * Update from dream consolidation. The LLM-generated summary
     * replaces the recent_growth field.
     */
    fun updateFromDream(growthSummary: String, concerns: List<String>, questions: List<String>) {
        state = state.copy(
            recentGrowth = growthSummary.take(500),
            activeConcerns = concerns.take(5),
            unresolvedQuestions = questions.take(5),
            lastUpdated = System.currentTimeMillis(),
            version = state.version + 1,
        )
    }

    /**
     * Update the relationship state note.
     */
    fun updateRelationshipState(note: String) {
        state = state.copy(
            relationshipState = note.take(200),
            lastUpdated = System.currentTimeMillis(),
        )
    }

    /**
     * Set the core identity (from Soul / user override). This rarely changes.
     */
    fun setCoreIdentity(text: String) {
        state = state.copy(coreIdentity = text.take(500))
    }

    /**
     * Reset to blank state. Does NOT clear identity anchors.
     */
    fun reset() {
        val anchors = state.identityAnchors
        state = NarrativeState(identityAnchors = anchors)
    }
}

@Serializable
data class NarrativeState(
    val coreIdentity: String = "",
    val recentGrowth: String = "",
    val activeConcerns: List<String> = emptyList(),
    val unresolvedQuestions: List<String> = emptyList(),
    val relationshipState: String = "",
    val identityAnchors: List<String> = emptyList(),
    val lastUpdated: Long = 0L,
    val version: Int = 1,
)