package com.aura.evolution

import com.aura.data.UserPreferences
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Evidence must not be written for a feature that is switched off.
 *
 * `EvolutionHooks` consulted no preference at all, and `MemoryStore` calls
 * `onMemoryRecalled` **once per result, from both recall branches**. So every
 * recall wrote one five-index row per hit, and every store wrote another, into
 * a table whose only readers are detectors that `EvolutionWorker` never runs
 * because `evolutionEnabled` defaults to false. The writes were pure cost: on
 * the user's critical path, unbounded, and for nothing.
 *
 * Two things made it invisible. The rows were written correctly, so nothing
 * looked broken; and `EvolutionEvidenceDao.deleteOlderThan` had no caller, so
 * there was no retention pass whose absence might have raised the question.
 */
class EvolutionEvidenceGateTest {

    private fun prefs(enabled: Boolean) = mockk<UserPreferences>().also {
        every { it.evolutionEnabled } returns flowOf(enabled)
    }

    @Test
    fun `nothing is recorded while evolution is switched off`() = runTest {
        val recorder = mockk<EvolutionEvidenceRecorder>(relaxed = true)
        val hooks = EvolutionHooks(recorder, prefs(enabled = false))

        hooks.onMemoryRecalled("m1", "a question", rank = 1)
        hooks.onMemoryStored("m2", "fact")
        hooks.onSkillInvoked("s1")

        coVerify(exactly = 0) {
            recorder.record(any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        }
    }

    @Test
    fun `evidence is recorded once evolution is on`() = runTest {
        val recorder = mockk<EvolutionEvidenceRecorder>(relaxed = true)
        val hooks = EvolutionHooks(recorder, prefs(enabled = true))

        hooks.onMemoryRecalled("m1", "a question", rank = 1)

        coVerify(exactly = 1) {
            recorder.record(any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        }
    }

    @Test
    fun `a hooks instance with no preferences still records`() = runTest {
        // Manual construction is how every existing test builds this, and those
        // tests assert on rows the hooks write. Failing open here — and only
        // here — keeps them meaningful; the production graph always supplies
        // preferences, and a failed *read* fails closed.
        val recorder = mockk<EvolutionEvidenceRecorder>(relaxed = true)
        val hooks = EvolutionHooks(recorder)

        hooks.onMemoryStored("m1", "fact")

        coVerify(exactly = 1) {
            recorder.record(any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        }
    }

    @Test
    fun `an unreadable preference fails closed`() = runTest {
        // A DataStore that throws must not be read as "on". Evidence is
        // telemetry for an opt-in feature, so the safe default when the answer
        // is unknown is to write nothing.
        val recorder = mockk<EvolutionEvidenceRecorder>(relaxed = true)
        val broken = mockk<UserPreferences>().also {
            every { it.evolutionEnabled } returns kotlinx.coroutines.flow.flow { error("datastore unavailable") }
        }
        val hooks = EvolutionHooks(recorder, broken)

        hooks.onMemoryStored("m1", "fact")

        coVerify(exactly = 0) {
            recorder.record(any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        }
    }
}
