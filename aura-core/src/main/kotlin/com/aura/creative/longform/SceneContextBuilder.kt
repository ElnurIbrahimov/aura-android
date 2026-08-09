package com.aura.creative.longform

import com.aura.creative.CreativeProject
import com.aura.creative.GenreCraftPrompts
import com.aura.creative.SmartCodexInjector
import com.aura.creative.StoryBeat
import com.aura.creative.WorldBible
import com.aura.creative.WritingTemplates
import javax.inject.Inject
import javax.inject.Singleton

/** The two messages one scene call is made from. */
data class SceneContext(val systemPrompt: String, val userPrompt: String)

/**
 * Assembles the prompt for one scene.
 *
 * The property that matters is that this is **flat**: the assembled size does
 * not grow with the manuscript. Scene 12 of a novel costs the same input as
 * scene 2. The naive alternative — feed everything written so far — is quadratic
 * in a book's length and stops fitting long before a book is finished.
 *
 * That is bought with per-section caps rather than one overall truncation, so
 * running out of room degrades a single section instead of silently dropping
 * whichever happens to be last. The budget:
 *
 * | Section              | Cap    | Why it is there                                  |
 * |----------------------|--------|--------------------------------------------------|
 * | craft guidance       | ~5,000 | genre + mode technique, the existing prompts      |
 * | project header       |   600  | premise, genre, tone                              |
 * | world bible          | 6,000  | filtered to this beat, not the whole project      |
 * | outline spine        | 3,000  | every beat title, so the model knows where it is  |
 * | previous scene tail  | 2,000  | carries voice, place, an unfinished line          |
 * | story so far         | 1,500  | rolling summary, regenerated every few scenes     |
 * | retrieved prior text | 2,800  | what the manuscript itself says about this beat   |
 * | scene instruction    |   600  | the beat, its target, where to stop               |
 *
 * Pure by construction — every input is already-fetched data, so the caps can be
 * tested without a model, a database or a network.
 */
@Singleton
class SceneContextBuilder @Inject constructor(
    private val smartCodexInjector: SmartCodexInjector,
) {

    fun build(
        project: CreativeProject,
        beats: List<StoryBeat>,
        beatIndex: Int,
        previousSceneTail: String = "",
        storySoFar: String = "",
        retrieved: List<String> = emptyList(),
    ): SceneContext {
        val beat = beats.getOrNull(beatIndex)
            ?: return SceneContext(systemPrompt = "", userPrompt = "")
        val beatText = "${beat.title} ${beat.summary} ${beat.setting} ${beat.pov}".trim()

        val system = buildString {
            appendLine("You are Aura's Creative Studio engine, writing one scene of a longer work.")
            appendLine("You are not a chatbot. You are a craftsperson. Every word should serve the story.")
            appendLine()
            append(craftGuidance(project))
            append(section("PROJECT", projectHeader(project), PROJECT_HEADER_CAP))
            append(section("WORLD", worldFor(project.world, beatText), WORLD_CAP))
            append(section("OUTLINE", outlineSpine(beats, beatIndex), OUTLINE_CAP))
            append(section("STORY SO FAR", storySoFar, SUMMARY_CAP))
            append(section("FROM THE MANUSCRIPT", retrieved.joinToString("\n\n---\n\n") { it.take(RETRIEVED_ITEM_CAP) }, RETRIEVED_CAP))
            append(section("END OF THE PREVIOUS SCENE", previousSceneTail.takeLast(PREVIOUS_TAIL_CAP), PREVIOUS_TAIL_CAP))
            appendLine("== AUTHORSHIP ==")
            appendLine("The user is the author. You are the instrument. Their voice matters more than your instinct.")
        }.take(MAX_CONTEXT_CHARS)

        return SceneContext(systemPrompt = system, userPrompt = sceneInstruction(beat, beatIndex, beats.size))
    }

    private fun craftGuidance(project: CreativeProject): String = buildString {
        val template = WritingTemplates.byId(project.templateId)
        if (template != null) {
            GenreCraftPrompts.forTemplate(template.id)?.let {
                appendLine("== FORM: ${template.name} ==")
                appendLine(it)
                appendLine()
            }
        }
    }.take(CRAFT_CAP)

    private fun projectHeader(project: CreativeProject): String = buildString {
        appendLine(project.name)
        if (project.description.isNotBlank()) appendLine("Premise: ${project.description}")
        if (project.genre.isNotBlank()) appendLine("Genre: ${project.genre}")
        if (project.tone.isNotBlank()) appendLine("Tone: ${project.tone}")
    }

    /**
     * The world bible, filtered to this beat.
     *
     * Filtering against the **beat** rather than the user's original brief is
     * the whole point: by scene nine the brief describes a book, while the beat
     * describes what is happening on this page, and it is the second that should
     * decide which characters and places are worth the context.
     *
     * Rendered compactly and scene-scoped, which is why it is not
     * `CreativeEngine`'s full narrative render — that one is written for a
     * single-shot call with the whole project in view and no per-section budget.
     */
    private fun worldFor(world: WorldBible, beatText: String): String {
        val filtered = smartCodexInjector.filterRelevant(world, beatText)
            .takeIf { smartCodexInjector.hasContent(it) } ?: world
        return buildString {
            if (filtered.overview.isNotBlank()) {
                appendLine(filtered.overview)
                appendLine()
            }
            if (filtered.characters.isNotEmpty()) {
                appendLine("Characters:")
                for (c in filtered.characters) {
                    append("- ${c.name}")
                    if (c.role.isNotBlank()) append(" — ${c.role}")
                    if (c.motivation.isNotBlank()) append(", driven by ${c.motivation}")
                    appendLine()
                }
                appendLine()
            }
            if (filtered.locations.isNotEmpty()) {
                appendLine("Places:")
                for (l in filtered.locations) {
                    append("- ${l.name}")
                    if (l.description.isNotBlank()) append(": ${l.description}")
                    appendLine()
                }
                appendLine()
            }
            if (filtered.rules.isNotEmpty()) {
                appendLine("Rules of this world:")
                for (r in filtered.rules) appendLine("- ${r.name}: ${r.description}")
            }
        }
    }

    /**
     * Every beat title, with the current one marked.
     *
     * The model needs to know not just what this scene is but where it sits —
     * what has already happened, and what it must leave room for. Without the
     * spine, scene four resolves a thread scene nine was meant to.
     */
    private fun outlineSpine(beats: List<StoryBeat>, currentIndex: Int): String = buildString {
        beats.forEachIndexed { i, beat ->
            val marker = when {
                i == currentIndex -> ">> "
                beat.status == STATUS_DRAFTED -> "   [written] "
                else -> "   "
            }
            appendLine("$marker${i + 1}. ${beat.title}")
        }
    }

    private fun sceneInstruction(beat: StoryBeat, index: Int, total: Int): String = buildString {
        appendLine("Write scene ${index + 1} of $total.")
        appendLine()
        appendLine("BEAT: ${beat.title}")
        if (beat.summary.isNotBlank()) appendLine("WHAT HAPPENS: ${beat.summary}")
        if (beat.pov.isNotBlank()) appendLine("POV: ${beat.pov}")
        if (beat.setting.isNotBlank()) appendLine("SETTING: ${beat.setting}")
        val target = beat.targetWords.takeIf { it > 0 } ?: DEFAULT_TARGET_WORDS
        appendLine("LENGTH: about $target words.")
        appendLine()
        // The single most common long-form failure: a model handed an outline
        // races to the end of the story instead of writing the scene in front
        // of it, and the next scene then has nothing left to do.
        appendLine("Write only this beat. Stop at its turn — do not summarise or begin the next one.")
        appendLine("Continue directly from the previous scene. Do not recap it.")
        appendLine("Prose only: no headings, no beat numbers, no author's notes.")
    }.take(INSTRUCTION_CAP)

    /** A titled block, or nothing at all when the body is empty. */
    private fun section(title: String, body: String, cap: Int): String {
        val trimmed = body.trim()
        if (trimmed.isEmpty()) return ""
        return buildString {
            appendLine("== $title ==")
            appendLine(trimmed.take(cap))
            appendLine()
        }
    }

    companion object {
        /**
         * Hard ceiling on the assembled system prompt.
         *
         * The value was already in the codebase as `CreativeEngine.MAX_CONTEXT_CHARS`,
         * declared and never referenced by anything. It means something here.
         */
        const val MAX_CONTEXT_CHARS = 48_000

        const val CRAFT_CAP = 5_000
        const val PROJECT_HEADER_CAP = 600
        const val WORLD_CAP = 6_000
        const val OUTLINE_CAP = 3_000
        const val PREVIOUS_TAIL_CAP = 2_000
        const val SUMMARY_CAP = 1_500
        const val RETRIEVED_CAP = 2_800
        const val RETRIEVED_ITEM_CAP = 700
        const val INSTRUCTION_CAP = 600

        const val DEFAULT_TARGET_WORDS = 1_200
        const val STATUS_DRAFTED = "drafted"
    }
}
