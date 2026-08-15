package com.aura.creative.longform

import com.aura.creative.CreativeProject
import com.aura.creative.SmartCodexInjector
import com.aura.creative.StoryBeat
import com.aura.creative.WorldBible
import com.aura.creative.WorldCharacter
import com.aura.creative.WorldLocation
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The property that makes long-form possible: assembled size does not grow with
 * the manuscript. Scene 40 must cost the same input as scene 2, or a book stops
 * fitting long before it is finished.
 */
class SceneContextBuilderTest {

    private val builder = SceneContextBuilder(SmartCodexInjector())

    private fun beats(count: Int) = (1..count).map {
        StoryBeat(id = "b$it", title = "Beat $it", summary = "Summary $it", status = "planned")
    }

    private fun project(
        beats: List<StoryBeat>,
        characters: List<WorldCharacter> = emptyList(),
        locations: List<WorldLocation> = emptyList(),
        overview: String = "",
    ) = CreativeProject(
        id = "p1",
        name = "The Lighthouse",
        description = "A keeper who cannot swim",
        genre = "literary",
        tone = "spare",
        world = WorldBible(
            overview = overview,
            characters = characters,
            locations = locations,
            outline = beats,
        ),
        templateId = "novel",
        turnCount = 0,
        createdAt = 0L,
        updatedAt = 0L,
    )

    @Test
    fun `the scene instruction names the beat and its position`() {
        val ctx = builder.build(project(beats(12)), beats(12), beatIndex = 3)
        assertTrue(ctx.userPrompt.contains("scene 4 of 12"), ctx.userPrompt)
        assertTrue(ctx.userPrompt.contains("Beat 4"))
    }

    /**
     * The most common long-form failure: handed an outline, a model races to the
     * end of the story instead of writing the scene in front of it, leaving the
     * remaining beats with nothing to do.
     */
    @Test
    fun `the instruction tells the model to write only this beat`() {
        val ctx = builder.build(project(beats(5)), beats(5), beatIndex = 0)
        assertTrue(ctx.userPrompt.contains("Write only this beat"), ctx.userPrompt)
    }

    @Test
    fun `the outline spine marks where the model is`() {
        val ctx = builder.build(project(beats(5)), beats(5), beatIndex = 2)
        val marked = ctx.systemPrompt.lines().first { it.contains("3. Beat 3") }
        assertTrue(marked.trimStart().startsWith(">>"), "current beat should be marked: '$marked'")
    }

    /** The whole point. A giant manuscript tail must not inflate the prompt. */
    @Test
    fun `the assembled prompt does not grow with the manuscript`() {
        val small = builder.build(project(beats(12)), beats(12), 5, previousSceneTail = "short tail")
        val huge = builder.build(
            project(beats(12)),
            beats(12),
            5,
            previousSceneTail = "x".repeat(500_000),
            storySoFar = "y".repeat(50_000),
            retrieved = List(50) { "z".repeat(20_000) },
        )

        assertTrue(
            huge.systemPrompt.length <= SceneContextBuilder.MAX_CONTEXT_CHARS,
            "assembled ${huge.systemPrompt.length} chars, over the ceiling",
        )
        val growth = huge.systemPrompt.length - small.systemPrompt.length
        assertTrue(growth < 20_000, "prompt grew by $growth chars with a 500k-char manuscript behind it")
    }

    @Test
    fun `each section is capped independently so one cannot starve the others`() {
        val ctx = builder.build(
            project(beats(200)),
            beats(200),
            0,
            previousSceneTail = "x".repeat(100_000),
            retrieved = List(20) { "z".repeat(10_000) },
        )
        // Every section still present despite each input being oversized.
        assertTrue(ctx.systemPrompt.contains("== OUTLINE =="), "outline dropped")
        assertTrue(ctx.systemPrompt.contains("== PROJECT =="), "project header dropped")
        assertTrue(ctx.systemPrompt.contains("== END OF THE PREVIOUS SCENE =="), "previous scene dropped")
    }

    /**
     * The tail is taken from the END of the previous scene. Taking the start
     * would hand the model the paragraph it has already moved past.
     */
    @Test
    fun `the previous scene contributes its ending, not its beginning`() {
        val tail = "OPENING" + "x".repeat(10_000) + "CLOSING"
        val ctx = builder.build(project(beats(3)), beats(3), 1, previousSceneTail = tail)
        assertTrue(ctx.systemPrompt.contains("CLOSING"), "the end of the previous scene should survive")
        assertTrue(!ctx.systemPrompt.contains("OPENING"), "its beginning should not")
    }

    @Test
    fun `an empty section is omitted rather than left as a bare heading`() {
        val ctx = builder.build(project(beats(3)), beats(3), 0, previousSceneTail = "", storySoFar = "")
        assertTrue(!ctx.systemPrompt.contains("== STORY SO FAR =="))
        assertTrue(!ctx.systemPrompt.contains("== END OF THE PREVIOUS SCENE =="))
    }

    @Test
    fun `the world bible reaches the prompt`() {
        val ctx = builder.build(
            project(
                beats(3),
                characters = listOf(WorldCharacter(name = "Mara", role = "keeper")),
                locations = listOf(WorldLocation(name = "The lighthouse")),
                overview = "An island at the end of a long winter.",
            ),
            beats(3),
            0,
        )
        assertTrue(ctx.systemPrompt.contains("Mara"), "characters should be present")
    }

    @Test
    fun `a beat target overrides the default length`() {
        val custom = listOf(StoryBeat(title = "Beat 1", targetWords = 2_500))
        val ctx = builder.build(project(custom), custom, 0)
        assertTrue(ctx.userPrompt.contains("2500"), ctx.userPrompt)
    }

    @Test
    fun `an out-of-range beat yields an empty context rather than throwing`() {
        val ctx = builder.build(project(beats(2)), beats(2), beatIndex = 9)
        assertEquals("", ctx.systemPrompt)
        assertEquals("", ctx.userPrompt)
    }

    /**
     * The two sections the builder documents and has never been given. Both were
     * defaulted parameters that no production caller passed, so `section()` saw an
     * empty body and emitted nothing at all — the headings did not appear, which is
     * why nothing ever looked wrong.
     */
    @Test
    fun `it renders the story-so-far and manuscript sections when supplied`() {
        val ctx = builder.build(
            project = project(beats(12)),
            beats = beats(12),
            beatIndex = 5,
            previousSceneTail = "the door closed behind her",
            storySoFar = "Mira reached the lighthouse. The keeper refused her.",
            retrieved = listOf("the lamp had not been lit in forty years"),
        )

        assertTrue(ctx.systemPrompt.contains("== STORY SO FAR =="), ctx.systemPrompt)
        assertTrue(ctx.systemPrompt.contains("The keeper refused her."))
        assertTrue(ctx.systemPrompt.contains("== FROM THE MANUSCRIPT =="))
        assertTrue(ctx.systemPrompt.contains("the lamp had not been lit"))
    }

    /**
     * Thirty two-sentence synopses do not fit 1,500 characters, which is what the
     * cap was when nothing ever filled the section.
     */
    @Test
    fun `the story-so-far budget holds a book's worth of synopses`() {
        assertTrue(
            SceneContextBuilder.SUMMARY_CAP >= 8_000,
            "SUMMARY_CAP is ${SceneContextBuilder.SUMMARY_CAP}; 30 synopses at 400 chars need 8,000",
        )
    }
}
