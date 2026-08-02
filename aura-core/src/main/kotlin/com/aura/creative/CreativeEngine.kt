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

enum class CreativeMode(val label: String, val instruction: String, val temperature: Double) {
    BRAINSTORM(
        "Brainstorm",
        "Generate several distinct, specific possibilities. Explain the dramatic trade-off of each without declaring one canon.",
        0.95,
    ),
    OUTLINE(
        "Outline",
        "Create a structured sequence of story beats with escalation, reversals, setup/payoff, and an earned ending.",
        0.75,
    ),
    DRAFT(
        "Draft",
        "Write polished prose or screenplay material in the project's established voice. Do not contradict canon.",
        0.85,
    ),
    REWRITE(
        "Rewrite",
        "Rewrite the supplied text while preserving its intent and the user's voice. Improve specificity, rhythm, subtext, and clarity.",
        0.75,
    ),
    SIMULATE(
        "Simulate",
        "Run this as a non-canon what-if simulation. Trace plausible decisions, reactions, second-order consequences, and the resulting world state.",
        0.9,
    ),
    CONTINUITY(
        "Continuity",
        "Audit the supplied text against canon. List concrete contradictions, timeline problems, unexplained changes, and possible repairs. Do not invent problems.",
        0.2,
    ),
}

/**
 * The creative engine. Generates content for a creative project by
 * assembling a rich system prompt from:
 * - The mode instruction (what to do)
 * - Genre-specific craft guidance (how to do it well)
 * - Mode-specific craft guidance (adjustments for this mode)
 * - The world bible rendered as narrative, not data
 * - Prior simulation summaries (so the model can build on what-if explorations)
 * - The most recent artifacts (so iterative drafting has context)
 * - Word count targets for long-form modes
 *
 * Conversation history: the last N turns of creative generation are
 * included as prior messages so the model has continuity between
 * calls. Without this, every call is amnesiac — you can't say "make
 * the dialogue in the last scene sharper" because the model doesn't
 * remember the last scene.
 */
@Singleton
class CreativeEngine @Inject constructor(
    private val providerRegistry: ProviderRegistry,
    private val userPreferences: UserPreferences,
    private val projectStore: CreativeProjectStore,
    private val artifactStore: CreativeArtifactStore,
    private val brain: com.aura.agent.Brain,
    private val smartCodexInjector: SmartCodexInjector,
) {
    fun generate(
        projectId: String,
        mode: CreativeMode,
        input: String,
        perspective: String = "",
        thinkingBudget: Int? = null,
    ): Flow<String> = flow {
        require(input.isNotBlank()) { "A writing prompt is required." }
        val project = projectStore.get(projectId)
            ?: throw IllegalArgumentException("Creative project not found.")
        val model = resolveModel()
        val template = WritingTemplates.byId(project.templateId)
        val systemPrompt = buildSystemPrompt(project, mode, template, input)
        val messages = buildMessages(projectId, project, mode, input, perspective, systemPrompt)
        val outputBudget = when (mode) {
            CreativeMode.SIMULATE, CreativeMode.DRAFT -> 28_672
            CreativeMode.REWRITE -> 16_384
            else -> 8_192
        }
        val options = ChatOptions(
            temperature = mode.temperature,
            maxTokens = outputBudget,
            thinkingBudget = thinkingBudget,
        )
        val output = StringBuilder()
        brain.stream(model, messages, emptyList(), options).collect { chunk ->
            when (chunk) {
                is com.aura.agent.BrainChunk.Text -> {
                    if (chunk.text.isNotEmpty()) {
                        output.append(chunk.text)
                        emit(chunk.text)
                    }
                }
                is com.aura.agent.BrainChunk.Error -> throw IllegalStateException(chunk.message)
                else -> {}
            }
        }
        if (output.isNotBlank()) {
            projectStore.incrementTurn(projectId)
            if (mode == CreativeMode.SIMULATE) {
                projectStore.recordSimulation(
                    projectId,
                    SimulationRecord(
                        premise = input.trim(),
                        outcome = output.toString(),
                        perspective = perspective.trim(),
                    ),
                )
            }
        }
    }

    /**
     * Build the system prompt. This is the core SOTA upgrade —
     * instead of 3 generic lines, the prompt now includes:
     *
     * 1. Role + mode instruction
     * 2. Genre-specific craft guidance (40-80 lines of technique)
     * 3. Mode-specific craft guidance (5-15 lines of adjustments)
     * 4. Word count target for long-form modes
     * 5. World bible rendered as narrative prose, not key-value data
     * 6. Prior simulation summaries (last 3)
     * 7. Recent artifacts (last 3, first 500 chars each)
     */
    private fun buildSystemPrompt(
        project: CreativeProject,
        mode: CreativeMode,
        template: WritingTemplate?,
        prompt: String = "",
    ): String = buildString {
        appendLine("You are Aura's Creative Studio engine working inside one durable creative project.")
        appendLine("You are not a chatbot. You are a craftsperson. Every word you write should serve the story.")
        appendLine()
        appendLine("== TASK ==")
        appendLine(mode.instruction)
        appendLine()
        // Genre-specific craft
        if (template != null) {
            appendLine("== FORM: ${template.name} ==")
            GenreCraftPrompts.forTemplate(template.id)?.let { craft ->
                appendLine(craft)
                appendLine()
            }
        }
        // Mode-specific craft
        appendLine("== MODE GUIDANCE ==")
        appendLine(GenreCraftPrompts.forMode(mode))
        appendLine()
        // Word count target for long-form modes
        if (mode == CreativeMode.DRAFT || mode == CreativeMode.SIMULATE) {
            appendLine("== LENGTH TARGET ==")
            appendLine("Aim for 12,000-16,000 words. Do not stop early. Do not summarize the ending — write it in full.")
            appendLine("If you feel the story winding down, that's the final act. Push through to the resolution.")
            appendLine("Write in scenes. Each scene is 1,000-1,500 words. That's 8-12 scenes for a full draft.")
            appendLine()
        }
        // Authorship preservation
        appendLine("== AUTHORSHIP ==")
        appendLine("Preserve the user's authorship: assist and extend; never claim generated exploration is canon.")
        appendLine("The user is the author. You are the instrument. Their voice matters more than your instinct.")
        appendLine()
        // World bible as narrative
        appendLine("== WORLD BIBLE ==")
        append(buildNarrativeWorldContext(project, prompt))
    }

    /**
     * Render the world bible as narrative prose, not key-value data.
     *
     * Uses [SmartCodexInjector] to filter the world bible to only
     * entries relevant to the current prompt. This reduces context
     * waste and improves output quality by letting the model focus
     * on what matters for this scene.
     */
    private fun buildNarrativeWorldContext(project: CreativeProject, prompt: String = ""): String = buildString {
        val world = if (prompt.isNotBlank()) {
            val filtered = smartCodexInjector.filterRelevant(project.world, prompt)
            if (smartCodexInjector.hasContent(filtered)) filtered else project.world
        } else {
            project.world
        }
        appendLine("PROJECT: ${project.name}")
        if (project.description.isNotBlank()) appendLine("PREMISE: ${project.description}")
        if (project.genre.isNotBlank()) appendLine("GENRE: ${project.genre}")
        if (project.tone.isNotBlank()) appendLine("TONE: ${project.tone}")
        appendLine()

        if (world.overview.isNotBlank()) {
            appendLine("WORLD:")
            appendLine(world.overview)
            appendLine()
        }

        if (world.characters.isNotEmpty()) {
            appendLine("CHARACTERS:")
            for (c in world.characters) {
                append("- ${c.name}")
                if (c.role.isNotBlank()) append(" — ${c.role}")
                appendLine()
                if (c.backstory.isNotBlank()) appendLine("  Background: ${c.backstory}")
                if (c.motivation.isNotBlank()) appendLine("  Driven by: ${c.motivation}")
                if (c.traits.isNotEmpty()) appendLine("  Traits: ${c.traits.joinToString(", ")}")
                if (c.arc.isNotBlank()) appendLine("  Arc: ${c.arc}")
                appendLine()
            }
        }

        if (world.locations.isNotEmpty()) {
            appendLine("LOCATIONS:")
            for (l in world.locations) {
                append("- ${l.name}")
                if (l.type.isNotBlank()) append(" (${l.type})")
                appendLine()
                if (l.description.isNotBlank()) appendLine("  ${l.description}")
                if (l.significance.isNotBlank()) appendLine("  Story significance: ${l.significance}")
                appendLine()
            }
        }

        if (world.factions.isNotEmpty()) {
            appendLine("FACTIONS:")
            for (f in world.factions) {
                append("- ${f.name}")
                appendLine()
                if (f.ideology.isNotBlank()) appendLine("  Ideology: ${f.ideology}")
                if (f.members.isNotEmpty()) appendLine("  Key members: ${f.members.joinToString(", ")}")
                if (f.rivals.isNotEmpty()) appendLine("  Rivals: ${f.rivals.joinToString(", ")}")
                appendLine()
            }
        }

        if (world.rules.isNotEmpty()) {
            appendLine("WORLD RULES:")
            for (r in world.rules) {
                appendLine("- ${r.name}: ${r.description}")
                if (r.impact.isNotBlank()) appendLine("  Impact on story: ${r.impact}")
            }
            appendLine()
        }

        if (world.timeline.isNotEmpty()) {
            appendLine("TIMELINE:")
            for (e in world.timeline) {
                append("- ${e.date}: ${e.title}")
                if (e.description.isNotBlank()) append(" — ${e.description}")
                appendLine()
            }
            appendLine()
        }

        if (world.outline.isNotEmpty()) {
            appendLine("STORY OUTLINE:")
            world.outline.forEachIndexed { i, beat ->
                appendLine("${i + 1}. [${beat.status}] ${beat.title}: ${beat.summary}")
            }
            appendLine()
        }

        // Prior simulations — feed the last 3 back so the model
        // can build on previous what-if explorations.
        if (world.simulations.isNotEmpty()) {
            appendLine("PRIOR SIMULATIONS (non-canon, for reference):")
            world.simulations.take(3).forEach { sim ->
                appendLine("- ${sim.premise}")
                appendLine("  Outcome: ${sim.outcome.take(500)}...")
                appendLine()
            }
        }

        if (world.continuityNotes.isNotEmpty()) {
            appendLine("KNOWN CONTINUITY ISSUES:")
            for (issue in world.continuityNotes) {
                appendLine("- [${issue.severity}] ${issue.description}")
            }
            appendLine()
        }

        if (world.notes.isNotBlank()) {
            appendLine("AUTHOR NOTES:")
            appendLine(world.notes)
        }
    }

    /**
     * Build the message list. Includes the system prompt, prior
     * conversation context (last 3 creative artifacts), and the
     * current user prompt.
     *
     * The prior artifacts give the model continuity: it can see
     * what was written before and build on it instead of starting
     * fresh every time.
     */
    private suspend fun buildMessages(
        projectId: String,
        project: CreativeProject,
        mode: CreativeMode,
        input: String,
        perspective: String,
        systemPrompt: String,
    ): List<ProviderMessage> {
        val messages = mutableListOf<ProviderMessage>()
        messages.add(ProviderMessage(ProviderMessage.Role.system, systemPrompt))

        // Feed recent artifacts as conversation context so the model
        // has continuity. Last 3 artifacts, first 2000 chars each.
        val recentArtifacts = runCatching {
            artifactStore.forProject(projectId)
                .sortedByDescending { it.updatedAt }
                .take(3)
        }.getOrDefault(emptyList())

        for (artifact in recentArtifacts) {
            val content = runCatching {
                val revisions = artifactStore.revisionsForArtifact(artifact.id)
                revisions.lastOrNull()?.contentText ?: artifact.previewText
            }.getOrDefault(artifact.previewText)

            if (content.isNotBlank()) {
                messages.add(ProviderMessage(
                    ProviderMessage.Role.assistant,
                    "[Previous ${artifact.kind}: ${artifact.title}]\n${content.take(2000)}"
                ))
            }
        }

        // Current user prompt
        val userPrompt = buildString {
            if (perspective.isNotBlank()) appendLine("Perspective: $perspective")
            append(input.trim())
        }
        messages.add(ProviderMessage(ProviderMessage.Role.user, userPrompt))
        return messages
    }

    suspend fun resolveModel(): String {
        userPreferences.defaultModel.first()?.takeIf(String::isNotBlank)?.let { return it }
        for (provider in providerRegistry.configured()) {
            val model = runCatching { provider.listModels().firstOrNull() }
                .onFailure { android.util.Log.w("CreativeEngine", "listModels failed for ${provider.prefix}: ${it.message}", it) }
                .getOrNull()
            if (!model.isNullOrBlank()) return "${provider.prefix}:$model"
        }
        throw IllegalStateException("Configure an LLM provider and choose a default model before using Creative Studio.")
    }

    companion object {
        const val MAX_CONTEXT_CHARS = 48_000
    }
}