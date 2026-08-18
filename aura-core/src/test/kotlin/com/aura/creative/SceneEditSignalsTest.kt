package com.aura.creative

import com.aura.evolution.EvolutionHooks
import com.aura.skills.Skill
import com.aura.skills.SkillsStore
import com.aura.taste.TasteEngine
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The taste loop's arithmetic and its gates.
 *
 * keepRatio is pure and cheap; the weights are stated priors; and the craft
 * bridge fires only on rewrites, as evidence, without touching a single
 * evolution gate — a signals object built with no hooks still teaches taste.
 */
class SceneEditSignalsTest {

    private val tasteEngine = mockk<TasteEngine>(relaxed = true)

    @Test
    fun `keepRatio is one for identity, zero for disjoint, half for half`() {
        val a = (1..10).joinToString("\n") { "line $it" }
        assertEquals(1f, EditDistanceLite.keepRatio(a, a))
        val b = (11..20).joinToString("\n") { "line $it" }
        assertEquals(0f, EditDistanceLite.keepRatio(a, b))
        val half = (1..5).joinToString("\n") { "line $it" } + "\n" +
            (21..25).joinToString("\n") { "line $it" }
        val ratio = EditDistanceLite.keepRatio(a, half)
        assertTrue(ratio in 0.45f..0.55f, "expected about half, got $ratio")
    }

    @Test
    fun `prose that barely breaks lines falls back to sentences`() {
        val before = "She walked. She waited. She left."
        val after = "She walked. She waited. She stayed."
        val ratio = EditDistanceLite.keepRatio(before, after)
        assertTrue(ratio in 0.5f..0.9f, "sentence fallback did not engage: $ratio")
    }

    @Test
    fun `an untouched save records mild approval and recomputes both profiles`() = runBlocking {
        SceneEditSignals(tasteEngine).onSceneKept("p1", "art1")

        coVerify(exactly = 1) {
            tasteEngine.recordSignal(
                projectId = "p1", signalType = "accept", category = "scene_prose",
                artifactId = "art1", weight = 0.5f,
            )
        }
        coVerify(exactly = 1) { tasteEngine.recomputeProfile("p1") }
        coVerify(exactly = 1) { tasteEngine.recomputeProfile("") }
    }

    @Test
    fun `a touch-up, an edit and a rewrite land in their tiers`() = runBlocking {
        val base = (1..10).joinToString("\n") { "line $it" }
        val touchUp = (1..9).joinToString("\n") { "line $it" } + "\nchanged"
        val halfEdit = (1..6).joinToString("\n") { "line $it" } + "\n" +
            (21..24).joinToString("\n") { "line $it" }
        val rewrite = (31..40).joinToString("\n") { "line $it" }

        val signals = SceneEditSignals(tasteEngine)
        signals.onSceneEdited("p1", "novel", "art1", base, touchUp)
        coVerify(exactly = 1) { tasteEngine.recordSignal(any(), "edit", any(), any(), any(), -0.25f, any()) }

        signals.onSceneEdited("p1", "novel", "art1", base, halfEdit)
        coVerify(exactly = 1) { tasteEngine.recordEdit("p1", "art1", "scene_prose") }

        signals.onSceneEdited("p1", "novel", "art1", base, rewrite)
        coVerify(exactly = 1) { tasteEngine.recordSignal(any(), "rewrite", any(), any(), any(), -1.0f, any()) }
    }

    @Test
    fun `only a rewrite files craft evidence, against the skill id`() = runBlocking {
        val skillsStore = mockk<SkillsStore>(relaxed = true)
        val hooks = mockk<EvolutionHooks>(relaxed = true)
        val skill = mockk<Skill>(relaxed = true)
        every { skill.id } returns "sk-craft"
        every { skillsStore.findByName(CraftSkills.templateSkillName("novel")) } returns skill

        val base = (1..10).joinToString("\n") { "line $it" }
        val rewrite = (31..40).joinToString("\n") { "line $it" }
        val touchUp = (1..9).joinToString("\n") { "line $it" } + "\nchanged"

        val signals = SceneEditSignals(tasteEngine, skillsStore, hooks)
        signals.onSceneEdited("p1", "novel", "art1", base, touchUp)
        coVerify(exactly = 0) { hooks.onSkillFailed(any(), any(), any(), any(), any()) }

        signals.onSceneEdited("p1", "novel", "art1", base, rewrite)
        coVerify(exactly = 1) { hooks.onSkillFailed("sk-craft", "author_rewrote", any(), any(), any()) }
    }

    @Test
    fun `no hooks and no store still teach taste`() = runBlocking {
        val base = (1..10).joinToString("\n") { "line $it" }
        val rewrite = (31..40).joinToString("\n") { "line $it" }

        SceneEditSignals(tasteEngine, null, null).onSceneEdited("p1", "novel", "art1", base, rewrite)

        coVerify(exactly = 1) { tasteEngine.recordSignal(any(), "rewrite", any(), any(), any(), -1.0f, any()) }
    }
}
