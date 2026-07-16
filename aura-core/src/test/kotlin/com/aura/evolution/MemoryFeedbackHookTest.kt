package com.aura.evolution

import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MemoryFeedbackHookTest {

    @Test
    fun `onMemoryFeedback records helpful feedback with note`() = runTest {
        val dao = mockk<EvolutionEvidenceDao>(relaxed = true)
        val captured = mutableListOf<EvolutionEvidenceEntity>()
        coEvery { dao.upsert(capture(captured)) } just Runs
        val recorder = EvolutionEvidenceRecorder(dao)
        val hooks = EvolutionHooks(recorder)

        hooks.onMemoryFeedback("mem-1", helpful = true, note = "accurate")

        assertEquals(1, captured.size)
        assertEquals("memory_helpful", captured[0].kind)
        assertEquals("mem-1", captured[0].sourceEntityId)
        assertTrue(captured[0].payloadJson.contains("\"note\""))
        assertTrue(captured[0].payloadJson.contains("accurate"))
    }

    @Test
    fun `onMemoryFeedback records unhelpful feedback`() = runTest {
        val dao = mockk<EvolutionEvidenceDao>(relaxed = true)
        val captured = mutableListOf<EvolutionEvidenceEntity>()
        coEvery { dao.upsert(capture(captured)) } just Runs
        val recorder = EvolutionEvidenceRecorder(dao)
        val hooks = EvolutionHooks(recorder)

        hooks.onMemoryFeedback("mem-2", helpful = false)

        assertEquals(1, captured.size)
        assertEquals("memory_not_helpful", captured[0].kind)
        assertEquals("mem-2", captured[0].sourceEntityId)
    }
}
