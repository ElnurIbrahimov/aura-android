package com.aura.consciousness

import android.content.Context
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

/**
 * Save → new instance → load round-trips for the consciousness components.
 *
 * These are the tests that would have caught the defect they exist for.
 * `ConsciousnessLayerTest` hands every component a `mockk(relaxed = true)`
 * Context, so `load()`/`save()` are silent no-ops there — the suite could not
 * distinguish "persistence works" from "persistence does not exist", which is
 * how [IntrinsicMotivation] and [TheoryOfMind] shipped for months holding all
 * their state in memory. Each test below writes with one instance and reads
 * with a *different* one, which is the only shape that proves anything: a
 * single instance passes whether or not a file was ever written.
 */
class ConsciousnessPersistenceTest {

    private fun ctx(dir: File): Context =
        mockk<Context>(relaxed = true).also { every { it.filesDir } returns dir }

    private fun tempDir(): File =
        createTempDirectory("aura-persistence-test").toFile().also { it.deleteOnExit() }

    // ── IntrinsicMotivation ────────────────────────────────────────

    @Test
    fun `drives survive a process restart`() = runTest {
        val dir = tempDir()

        val first = IntrinsicMotivation(ctx(dir))
        first.assess(
            kgGapCount = 20,          // → CURIOSITY intensity 1.0
            lowConfidenceSkillCount = 1,
            hoursSinceLastInteraction = 0f,
            contradictionCount = 3,   // → COHERENCE intensity 1.0
        )
        first.save()

        // A different instance, as after a cold start.
        val second = IntrinsicMotivation(ctx(dir))
        second.load()

        val drives = second.drives.value
        assertEquals(1.0f, drives[IntrinsicMotivation.DriveType.CURIOSITY]!!.intensity, 0.001f)
        assertEquals(1.0f, drives[IntrinsicMotivation.DriveType.COHERENCE]!!.intensity, 0.001f)
        assertEquals(0.2f, drives[IntrinsicMotivation.DriveType.COMPETENCE]!!.intensity, 0.001f)
        assertTrue(
            "triggers should survive the round-trip",
            drives[IntrinsicMotivation.DriveType.CURIOSITY]!!.triggers.any { "20" in it },
        )
    }

    @Test
    fun `lastSatisfiedAt survives, so the urgency ramp can actually run`() = runTest {
        val dir = tempDir()

        val first = IntrinsicMotivation(ctx(dir))
        first.satisfy(IntrinsicMotivation.DriveType.SOCIAL)
        val satisfiedAt = first.drives.value[IntrinsicMotivation.DriveType.SOCIAL]!!.lastSatisfiedAt
        first.save()

        val second = IntrinsicMotivation(ctx(dir))
        second.load()

        // This is the whole point of persisting. DriveState.urgency blends
        // intensity with (now - lastSatisfiedAt) / 24h. When the timestamp was
        // re-stamped to `now` on every construction, that term was always ~0
        // and the "builds over 24h" ramp was unreachable code on a platform
        // that kills the process between sessions.
        assertEquals(satisfiedAt, second.drives.value[IntrinsicMotivation.DriveType.SOCIAL]!!.lastSatisfiedAt)
    }

    @Test
    fun `a fresh install starts from defaults without failing`() = runTest {
        val im = IntrinsicMotivation(ctx(tempDir()))
        im.load() // no file yet

        assertEquals(4, im.drives.value.size)
        assertEquals(0.3f, im.drives.value[IntrinsicMotivation.DriveType.CURIOSITY]!!.intensity, 0.001f)
    }

    @Test
    fun `a corrupt file does not clobber in-memory state`() = runTest {
        val dir = tempDir()
        File(dir, "intrinsic_motivation.json").writeText("{ this is not json")

        val im = IntrinsicMotivation(ctx(dir))
        im.assess(kgGapCount = 20, lowConfidenceSkillCount = 0, hoursSinceLastInteraction = 0f, contradictionCount = 0)
        im.load() // must not reset what assess() just computed

        assertEquals(1.0f, im.drives.value[IntrinsicMotivation.DriveType.CURIOSITY]!!.intensity, 0.001f)
    }

    @Test
    fun `a drive absent from an older file keeps its default`() = runTest {
        val dir = tempDir()
        // Simulates a file written before a DriveType existed.
        File(dir, "intrinsic_motivation.json").writeText(
            """[{"drive":"SOCIAL","intensity":0.9,"satisfaction":0.1,"lastSatisfiedAt":123,"triggers":[]}]""",
        )

        val im = IntrinsicMotivation(ctx(dir))
        im.load()

        assertEquals(4, im.drives.value.size)
        assertEquals(0.9f, im.drives.value[IntrinsicMotivation.DriveType.SOCIAL]!!.intensity, 0.001f)
        assertEquals(0.3f, im.drives.value[IntrinsicMotivation.DriveType.COHERENCE]!!.intensity, 0.001f)
    }

    // ── TheoryOfMind ───────────────────────────────────────────────

    @Test
    fun `the user model survives a process restart`() = runTest {
        val dir = tempDir()

        val first = TheoryOfMind(ctx(dir))
        first.updateFromMessage("Can you refactor the async database migration schema?")
        first.updateFromMessage("The deploy protocol is broken again, why is it still failing")
        first.updateTopic("kotlin", levelDelta = 0.4f, signal = "discussed coroutines")
        first.save()

        val second = TheoryOfMind(ctx(dir))
        second.load()

        val model = second.model.value
        assertEquals(2, model.commStyle.sampleCount)
        assertTrue("technical depth should have been learned", model.commStyle.technicalDepth > 0.3f)
        assertEquals(0.9f, model.topics["kotlin"]!!.level, 0.001f)
        assertNotEquals(0L, model.lastInteractionAt)
    }

    @Test
    fun `sampleCount accumulates across restarts so toPrompt can fire`() = runTest {
        val dir = tempDir()

        // toPrompt() stays silent below 3 samples. With no persistence the
        // counter reset every cold start, so on a phone it could effectively
        // never reach the threshold — the class computed a model it then threw
        // away before anything could read it.
        repeat(3) { i ->
            val instance = TheoryOfMind(ctx(dir))
            instance.load()
            instance.updateFromMessage("message number $i about the api and the database schema")
            instance.save()
        }

        val finalInstance = TheoryOfMind(ctx(dir))
        finalInstance.load()

        assertEquals(3, finalInstance.model.value.commStyle.sampleCount)
        assertTrue(
            "with 3 accumulated samples toPrompt must produce something",
            finalInstance.toPrompt().isNotBlank(),
        )
    }

    @Test
    fun `a corrupt user model does not clobber in-memory state`() = runTest {
        val dir = tempDir()
        File(dir, "theory_of_mind.json").writeText("not json at all")

        val tom = TheoryOfMind(ctx(dir))
        tom.updateFromMessage("hello there")
        tom.load()

        assertEquals(1, tom.model.value.commStyle.sampleCount)
    }
}
