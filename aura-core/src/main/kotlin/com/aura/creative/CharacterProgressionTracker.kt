package com.aura.creative

import com.aura.data.UserPreferences
import com.aura.providers.ChatOptions
import com.aura.providers.ProviderMessage
import com.aura.providers.ProviderRegistry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Character progression tracker. Instead of a static "arc" field,
 * this system tracks how characters CHANGE over time — scene by scene,
 * chapter by chapter.
 *
 * The tracker:
 * 1. AUTO-EXTRACTS progression entries from generated text (what changed
 *    for this character in this scene?)
 * 2. STORES progression entries with scene/chapter references
 * 3. FEEDS progression into the world bible context so the model knows
 *    "Character X was naive in scene 1, became suspicious in scene 3,
 *    and is now ruthless in scene 7" — and generates accordingly.
 *
 * This is the difference between "Character.arc = 'goes from naive to
 * ruthless'" (static, flat) and a progression timeline that shows
 * WHERE and HOW the change happened, scene by scene.
 */
@Singleton
class CharacterProgressionTracker @Inject constructor(
    private val providerRegistry: ProviderRegistry,
    private val userPreferences: UserPreferences,
    private val brain: com.aura.agent.Brain,
) {
    /**
     * A single progression entry — a snapshot of a character's state
     * at a specific point in the story.
     */
    data class ProgressionEntry(
        val characterName: String,
        val sceneLabel: String, // "Scene 3" or "Chapter 7" or "Draft 2, Scene 1"
        val emotionalState: String, // "suspicious", "grieving", "determined"
        val changeDescription: String, // "Discovered the letter — trust broken"
        val newBelief: String = "", // "People lie to protect themselves"
        val newSkill: String = "", // "Learned to pick locks"
        val newRelationship: String = "", // "Now distrusts Marcus"
        val timestamp: Long = System.currentTimeMillis(),
    )

    /**
     * Auto-extract progression entries from a generated scene.
     * The LLM reads the scene and identifies how each character changed.
     */
    fun extractFromScene(
        sceneText: String,
        knownCharacters: List<WorldCharacter>,
        sceneLabel: String = "",
    ): Flow<String> = flow {
        require(sceneText.isNotBlank()) { "Scene text is required." }
        val model = resolveModel()
        val charList = knownCharacters.joinToString(", ") { it.name }

        val systemPrompt = """
            You are a character progression analyst. You read a scene and identify how each character CHANGED.

            For each character that appears in the scene, identify:
            - EMOTIONAL STATE: What is their dominant emotion at the END of the scene?
            - CHANGE: What specifically changed for them? (belief, relationship, knowledge, skill, goal)
            - NEW BELIEF: If their worldview shifted, what do they now believe that they didn't before?
            - NEW RELATIONSHIP: Did any relationship shift? (trust → distrust, stranger → ally, love → suspicion)

            Only report characters who actually appear in the scene and who experienced a change.
            If a character appears but doesn't change, skip them.

            Characters to watch for: $charList

            Format (one entry per character, skip unchanged characters):
            CHARACTER: [name]
            EMOTIONAL STATE: [1-3 words]
            CHANGE: [one sentence]
            NEW BELIEF: [if any, one sentence]
            NEW RELATIONSHIP: [if any, one sentence]
            ---
        """.trimIndent()

        val options = ChatOptions(temperature = 0.3, maxTokens = 2_000, thinkingBudget = 4_096)

        brain.stream(model, listOf(
            ProviderMessage(ProviderMessage.Role.system, systemPrompt),
            ProviderMessage(ProviderMessage.Role.user, "SCENE ${if (sceneLabel.isNotBlank()) "($sceneLabel)" else ""}:\n\n${sceneText.take(8000)}"),
        ), emptyList(), options).collect { chunk ->
            when (chunk) {
                is com.aura.agent.BrainChunk.Text -> {
                    if (chunk.text.isNotEmpty()) emit(chunk.text)
                }
                is com.aura.agent.BrainChunk.Error -> throw IllegalStateException(chunk.message)
                else -> {}
            }
        }
    }

    /**
     * Build a progression summary for injection into the world bible context.
     * This gives the model awareness of where each character is in their arc.
     */
    fun buildProgressionSummary(
        entries: List<ProgressionEntry>,
    ): String {
        if (entries.isEmpty()) return ""
        val byCharacter = entries.groupBy { it.characterName }
        return buildString {
            appendLine("CHARACTER PROGRESSIONS:")
            for ((name, characterEntries) in byCharacter) {
                append("- $name: ")
                // Show the progression as a chain
                val chain = characterEntries.sortedBy { it.timestamp }
                val states = chain.map { e ->
                    val parts = mutableListOf<String>()
                    parts.add(e.emotionalState)
                    if (e.changeDescription.isNotBlank()) parts.add(e.changeDescription)
                    if (e.newBelief.isNotBlank()) parts.add("now believes: ${e.newBelief}")
                    if (e.newRelationship.isNotBlank()) parts.add(e.newRelationship)
                    parts.joinToString(" → ")
                }
                appendLine(states.joinToString(" | ") { "(${it})" })
            }
        }
    }

    private suspend fun resolveModel(): String {
        userPreferences.defaultModel.first()?.takeIf(String::isNotBlank)?.let { return it }
        for (provider in providerRegistry.configured()) {
            val model = runCatching { provider.listModels().firstOrNull() }.getOrNull()
            if (!model.isNullOrBlank()) return "${provider.prefix}:$model"
        }
        throw IllegalStateException("Configure an LLM provider before using progression tracking.")
    }
}