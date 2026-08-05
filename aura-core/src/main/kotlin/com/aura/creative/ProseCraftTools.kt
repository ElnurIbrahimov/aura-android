package com.aura.creative

import com.aura.data.UserPreferences
import com.aura.providers.ChatOptions
import com.aura.providers.ProviderMessage
import com.aura.providers.ProviderRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import android.util.Log

/**
 * Prose-level craft tools that operate on SELECTED TEXT.
 *
 * Unlike CreativeMode (which generates new content from a prompt),
 * these tools take existing text the user has selected and transform
 * it using specific craft principles. This is the difference between
 * a generator and a writing partner.
 *
 * Tools:
 * - SHOW_DONT_TELL: Convert told emotions into shown sensory details
 * - DESCRIBE: Expand a sparse sentence with all 5 senses
 * - EXPAND: Add detail, subtext, or internal monologue to a paragraph
 * - SHRINK_RAY: Tighten bloated prose without losing meaning
 * - TWIST: Generate unexpected plot directions for where you're stuck
 * - REWRITE: Rewrite a passage in a different tone or voice
 *
 * Each tool has a carefully crafted system prompt that teaches the
 * model the specific craft technique — not generic "improve this
 * text" but the actual principle behind the tool.
 */
@Singleton
class ProseCraftTools @Inject constructor(
    private val providerRegistry: ProviderRegistry,
    private val userPreferences: UserPreferences,
    private val brain: com.aura.agent.Brain,
) {
    enum class CraftTool(val label: String, val iconName: String) {
        SHOW_DONT_TELL("Show, Don't Tell", "👁"),
        DESCRIBE("Describe", "🌿"),
        EXPAND("Expand", "📝"),
        SHRINK_RAY("Shrink Ray", "✂"),
        TWIST("Twist", "🌀"),
        REWRITE("Rewrite", "🔄"),
    }

    fun apply(
        tool: CraftTool,
        selectedText: String,
        context: String = "",
        projectId: String? = null,
        voiceProfile: String = "",
    ): Flow<String> = flow {
        require(selectedText.isNotBlank()) { "Select some text first." }
        val model = resolveModel()
        val systemPrompt = buildSystemPrompt(tool, voiceProfile)
        val userPrompt = buildUserPrompt(tool, selectedText, context)

        val outputBudget = when (tool) {
            CraftTool.EXPAND -> 8_192
            CraftTool.TWIST -> 4_096
            CraftTool.DESCRIBE -> 4_096
            else -> 4_096
        }

        val options = ChatOptions(
            temperature = when (tool) {
                CraftTool.TWIST -> 0.95
                CraftTool.DESCRIBE -> 0.85
                CraftTool.SHRINK_RAY -> 0.3
                CraftTool.SHOW_DONT_TELL -> 0.8
                CraftTool.REWRITE -> 0.7
                CraftTool.EXPAND -> 0.8
            },
            maxTokens = outputBudget,
            // Craft tools use thinking for quality — but a smaller
            // budget than full creative generation. These are
            // transformations, not new content generation.
            thinkingBudget = 8_192,
        )

        brain.stream(model, listOf(
            ProviderMessage(ProviderMessage.Role.system, systemPrompt),
            ProviderMessage(ProviderMessage.Role.user, userPrompt),
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

    private fun buildSystemPrompt(tool: CraftTool, voiceProfile: String): String = buildString {
        appendLine("You are Aura's prose craft engine. You operate on SELECTED TEXT the user has chosen.")
        appendLine("You are not a chatbot. You are a master editor with decades of craft experience.")
        appendLine()
        when (tool) {
            CraftTool.SHOW_DONT_TELL -> {
                appendLine("== SHOW, DON'T TELL ==")
                appendLine("The user has selected a passage where emotion or state is TOLD instead of SHOWN.")
                appendLine("Your job: rewrite the passage so the emotion is demonstrated through action, sensory detail, and behavior.")
                appendLine()
                appendLine("Rules:")
                appendLine("- Do not name the emotion. If the original says 'she was angry,' show her gripping the table edge, her jaw tight, her voice flat.")
                appendLine("- Use at least two senses beyond sight.")
                appendLine("- The body tells the truth the mouth won't say. Show what the character's hands, posture, and breathing are doing.")
                appendLine("- Keep the same narrative beat. Don't add new plot — just transform the telling into showing.")
                appendLine("- Produce 3 alternatives, labeled A, B, C. Each should use a different sensory channel as the primary vehicle.")
            }
            CraftTool.DESCRIBE -> {
                appendLine("== DESCRIBE ==")
                appendLine("The user has selected a sparse sentence or passage that needs sensory richness.")
                appendLine("Your job: expand it with all five senses (sight, sound, smell, taste, touch).")
                appendLine()
                appendLine("Rules:")
                appendLine("- Every sense must serve the scene's mood, not just fill a checklist.")
                appendLine("- Sight is the default. Sound creates atmosphere. Smell triggers memory. Touch grounds the body. Taste is rare — use it only when it earns its place.")
                appendLine("- Don't describe everything. Choose the 2-3 most evocative details that imply the rest.")
                appendLine("- The description should make the reader feel present, not like they're reading an inventory.")
                appendLine("- Keep the original sentence's intent. Expand, don't replace.")
                appendLine("- Produce one richly expanded version.")
            }
            CraftTool.EXPAND -> {
                appendLine("== EXPAND ==")
                appendLine("The user has selected a paragraph that needs more depth.")
                appendLine("Your job: add detail, subtext, internal monologue, or atmospheric texture.")
                appendLine()
                appendLine("Rules:")
                appendLine("- Expand what's there. Don't add new plot points.")
                appendLine("- If the passage is dialogue, add the pauses, the gestures, the things NOT said.")
                appendLine("- If it's action, slow down the moment — let the reader feel the time.")
                appendLine("- If it's description, go deeper into one detail rather than adding more surface.")
                appendLine("- Subtext: what is the character thinking but NOT saying? Add 1-2 lines of internal monologue that contradicts the surface.")
                appendLine("- Produce one expanded version, 2-3x the original length.")
            }
            CraftTool.SHRINK_RAY -> {
                appendLine("== SHRINK RAY ==")
                appendLine("The user has selected a passage that's bloated, repetitive, or over-explained.")
                appendLine("Your job: tighten the prose. Cut the fat. Keep the muscle.")
                appendLine()
                appendLine("Rules:")
                appendLine("- Cut every word that doesn't earn its place. 'Very', 'really', 'quite', 'just' — gone.")
                appendLine("- Convert passive voice to active. 'The door was opened by him' → 'He opened the door.'")
                appendLine("- Merge sentences that say the same thing in different words.")
                appendLine("- Cut the last sentence of each paragraph — it's usually a summary of what was already shown.")
                appendLine("- Preserve every beat, every image, every piece of information. Cut words, not meaning.")
                appendLine("- Target: 60-70% of the original word count, with zero information loss.")
                appendLine("- Produce one tightened version.")
            }
            CraftTool.TWIST -> {
                appendLine("== TWIST ==")
                appendLine("The user is stuck. They've selected a passage or described a scene and need an unexpected direction.")
                appendLine("Your job: generate 5 DISTINCT plot twists that change everything — each with a different dramatic engine.")
                appendLine()
                appendLine("Rules:")
                appendLine("- Each twist must be surprising but earned — it follows from what's already established, just from an unexpected angle.")
                appendLine("- Twist 1: REVELATION — a secret is exposed that reframes everything.")
                appendLine("- Twist 2: REVERSAL — an ally becomes an enemy, or a goal becomes a trap.")
                appendLine("- Twist 3: CONVERGENCE — two unrelated plotlines collide unexpectedly.")
                appendLine("- Twist 4: ESCALATION — the stakes jump an order of magnitude.")
                appendLine("- Twist 5: PERSPECTIVE — the same events look completely different from another character's POV.")
                appendLine("- For each twist: 2-3 sentences explaining what happens, then 1 sentence on why it works dramatically.")
                appendLine("- Do not pick a winner. The user chooses.")
            }
            CraftTool.REWRITE -> {
                appendLine("== REWRITE ==")
                appendLine("The user has selected a passage and wants it rewritten — different tone, voice, or approach.")
                appendLine("Your job: rewrite while preserving meaning, beats, and information.")
                appendLine()
                appendLine("Rules:")
                appendLine("- Keep every piece of information from the original. Cut nothing that matters.")
                appendLine("- Change the PROSE, not the PLOT.")
                appendLine("- Vary sentence structure. If the original uses 3 similar sentences, use 3 different lengths.")
                appendLine("- If the original is flat, add rhythm. If it's florid, add restraint.")
                appendLine("- Dialogue should sound more natural — more interruptions, more trailing off, more talking past each other.")
                appendLine("- Produce one rewritten version.")
            }
        }
        if (voiceProfile.isNotBlank()) {
            appendLine()
            appendLine("== VOICE PROFILE ==")
            appendLine("The user has a voice profile. Match it:")
            appendLine(voiceProfile)
        }
    }

    private fun buildUserPrompt(tool: CraftTool, selectedText: String, context: String): String = buildString {
        if (context.isNotBlank()) {
            appendLine("CONTEXT (surrounding passage for reference):")
            appendLine(context.take(2000))
            appendLine()
        }
        appendLine("SELECTED TEXT:")
        appendLine(selectedText)
    }

    private suspend fun resolveModel(): String {
        userPreferences.defaultModel.first()?.takeIf(String::isNotBlank)?.let { return it }
        for (provider in providerRegistry.configured()) {
            val model = runCatching { provider.listModels().firstOrNull() }.onFailure { Log.w("ProseCraftTools", "runCatching failed: ${it.message}", it) }.getOrNull()
            if (!model.isNullOrBlank()) return "${provider.prefix}:$model"
        }
        throw IllegalStateException("Configure an LLM provider before using craft tools.")
    }
}