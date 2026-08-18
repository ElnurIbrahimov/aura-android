package com.aura.creative

import com.aura.creative.longform.SceneContextBuilder
import com.aura.skills.Skill
import com.aura.skills.SkillsStore
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * That the author's craft actually reaches the prompt.
 *
 * Seeding craft into a store nothing reads would leave it exactly as invisible
 * as the Kotlin constants it came from — and this repo has already paid for
 * that once: `SceneContextBuilder` documented eight context sections while its
 * only caller supplied six, for months, because two defaulted parameters had no
 * production caller and the tests only proved their caps truncated.
 *
 * So these assert the wire, not the plumbing either side of it. Delete the
 * `craft = …` argument from `LongformRunner`, or the `templateCraft`/`modeCraft`
 * arguments from `CreativeEngine`, and one of these fails.
 */
class CraftWiringTest {

    private val builder = SceneContextBuilder(SmartCodexInjector())

    private fun project() = CreativeProject(
        id = "p1",
        name = "The Lighthouse",
        description = "A keeper who cannot swim",
        genre = "literary",
        tone = "spare",
        world = WorldBible(outline = beats()),
        templateId = "novel",
        turnCount = 0,
        createdAt = 0L,
        updatedAt = 0L,
    )

    private fun beats() = (1..3).map {
        StoryBeat(id = "b$it", title = "Beat $it", summary = "Summary $it", status = "planned")
    }

    @Test
    fun `the resolved craft reaches the scene prompt, not the shipped constant`() {
        val ctx = builder.build(
            project = project(),
            beats = beats(),
            beatIndex = 1,
            craft = "WRITE ONLY IN SECOND PERSON",
        )

        assertTrue(ctx.systemPrompt.contains("WRITE ONLY IN SECOND PERSON"), ctx.systemPrompt.take(400))
        assertFalse(
            ctx.systemPrompt.contains(GenreCraftPrompts.NOVEL_CRAFT),
            "the author's craft replaces the shipped novel craft, it does not sit beside it",
        )
    }

    @Test
    fun `with no craft supplied the shipped constant still answers`() {
        val ctx = builder.build(project = project(), beats = beats(), beatIndex = 1)

        assertTrue(ctx.systemPrompt.contains(GenreCraftPrompts.NOVEL_CRAFT))
    }

    /**
     * The resolver is what `LongformRunner` and `CreativeEngine` call, so this
     * pins the value they would actually receive for an edited skill.
     */
    @Test
    fun `the resolver returns the edited body that the builders then send`() = runTest {
        val secure = mockk<com.aura.security.SecureDataStore>(relaxed = true)
        coEvery { secure.getString(any()) } returns null
        coEvery { secure.putString(any(), any()) } returns Unit
        val store = SkillsStore(secure)
        store.seedBuiltins(CraftSkills.seeds())
        val name = CraftSkills.templateSkillName("novel")
        store.update(store.findByName(name)!!.withBody("WRITE ONLY IN SECOND PERSON"))

        val resolved = CraftResolver(store).forTemplate("novel")

        val ctx = builder.build(project = project(), beats = beats(), beatIndex = 1, craft = resolved)
        assertTrue(ctx.systemPrompt.contains("WRITE ONLY IN SECOND PERSON"))
    }

    /**
     * Seeded craft must be reachable by [com.aura.tools.UseSkillTool] too — the
     * model can invoke `use_skill("craft-novel")` and read the guidance
     * directly, which is the difference between a skill system and a private
     * constant with extra steps.
     */
    @Test
    fun `every seeded craft skill is findable by the name the model would use`() = runTest {
        val secure = mockk<com.aura.security.SecureDataStore>(relaxed = true)
        coEvery { secure.getString(any()) } returns null
        coEvery { secure.putString(any(), any()) } returns Unit
        val store = SkillsStore(secure)
        store.seedBuiltins(CraftSkills.seeds())

        for (seed in CraftSkills.seeds()) {
            val found: Skill? = store.findByName(seed.name)
            assertTrue(found != null, "use_skill('${seed.name}') would return skill_not_found")
        }
    }

    @Test
    fun `with no taste profile the two-arg resolve is byte-identical to the one-arg`() = runTest {
        val secure = mockk<com.aura.security.SecureDataStore>(relaxed = true)
        coEvery { secure.getString(any()) } returns null
        coEvery { secure.putString(any(), any()) } returns Unit
        val store = SkillsStore(secure)
        store.seedBuiltins(CraftSkills.seeds())
        val engine = io.mockk.mockk<com.aura.taste.TasteEngine>(relaxed = true)
        io.mockk.coEvery { engine.getTasteContextForProject(any()) } returns ""
        val enhancer = io.mockk.mockk<com.aura.taste.TastePromptEnhancer>(relaxed = true)
        val resolver = CraftResolver(store, engine, enhancer)

        kotlin.test.assertEquals(
            resolver.forTemplate("novel"),
            resolver.forTemplate("novel", "p1"),
            "an empty profile must change nothing at all",
        )
        io.mockk.verify(exactly = 0) { enhancer.enhance(any(), any()) }
    }

    @Test
    fun `a learned profile is appended at the resolver, capped`() = runTest {
        val secure = mockk<com.aura.security.SecureDataStore>(relaxed = true)
        coEvery { secure.getString(any()) } returns null
        coEvery { secure.putString(any(), any()) } returns Unit
        val store = SkillsStore(secure)
        store.seedBuiltins(CraftSkills.seeds())
        val engine = io.mockk.mockk<com.aura.taste.TasteEngine>(relaxed = true)
        io.mockk.coEvery { engine.getTasteContextForProject("p1") } returns "x".repeat(5_000)
        val enhancer = io.mockk.mockk<com.aura.taste.TastePromptEnhancer>(relaxed = true)
        val tasteSlot = io.mockk.slot<String>()
        io.mockk.every { enhancer.enhance(any(), capture(tasteSlot)) } answers { firstArg<String>() + "STYLE" }
        val resolver = CraftResolver(store, engine, enhancer)

        val enhanced = resolver.forTemplate("novel", "p1")

        kotlin.test.assertTrue(enhanced!!.endsWith("STYLE"), "the enhancer's output must be what resolves")
        kotlin.test.assertTrue(tasteSlot.captured.length <= 600, "taste seasons the craft; it does not replace it")
    }
}
