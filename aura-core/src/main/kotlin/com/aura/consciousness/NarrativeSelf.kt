package com.aura.consciousness

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
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
 * during Dream consolidation (see [updateFromDream]).
 *
 * Unlike the static [Brain.IDENTITY] (which is the core persona), the
 * NarrativeSelf captures what Aura has learned about itself and its
 * relationship with the user. It evolves over time.
 *
 * Persistence: JSON file at `files/narrative_self.json`. Loaded on
 * app start by [ProactiveBootstrap], saved after dream-cycle updates.
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
    @ApplicationContext private val context: Context,
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
        }.onFailure { Log.w("NarrativeSelf", "load failed: ${it.message}", it) }
    }

    /**
     * Persist current state to disk.
     */
    suspend fun save() = withContext(Dispatchers.IO) {
        runCatching {
            file.writeText(json.encodeToString(NarrativeState.serializer(), state))
        }.onFailure { Log.w("NarrativeSelf", "save failed: ${it.message}", it) }
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
     * Update from dream consolidation — the only writer of recentGrowth,
     * activeConcerns, and unresolvedQuestions. Called by
     * [com.aura.dream.DreamConsolidator]'s narrative phase with the
     * cycle's LLM-written cluster summaries as growth and unresolved
     * contradictions as concerns; zero extra LLM calls.
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
     * Replace the open questions, leaving the rest of the narrative alone.
     *
     * [updateFromDream] writes all three of growth, concerns and questions at
     * once, and passes the questions straight back in from the previous
     * snapshot — so with no other writer the field could only ever stay empty,
     * which it has for every user since it shipped. The curiosity scan runs
     * late in the dream cycle, after the graph has been densified and the gaps
     * it can see are the real ones, so it needs to set this field on its own.
     */
    fun updateOpenQuestions(questions: List<String>) {
        state = state.copy(
            unresolvedQuestions = questions.take(5),
            lastUpdated = System.currentTimeMillis(),
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

    /**
     * Replace the whole narrative and persist it. Used by the backup restore
     * path, which is the only caller that legitimately overwrites every field
     * at once — [updateFromDream] and [setCoreIdentity] each own a slice, and
     * neither can express "this is the state from another install".
     */
    suspend fun restore(restored: NarrativeState) {
        state = restored
        save()
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