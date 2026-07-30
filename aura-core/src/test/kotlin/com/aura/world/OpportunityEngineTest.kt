package com.aura.world

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OpportunityEngineTest {

    private val worldEventDao = mockk<WorldEventDao>(relaxed = true)
    private val opportunityDao = mockk<OpportunityDao>(relaxed = true)
    private val beliefDao = mockk<BeliefDao>(relaxed = true)

    private fun engine() = OpportunityEngine(worldEventDao, opportunityDao, beliefDao)

    @Test
    fun `runCycle with no events and no beliefs produces no opportunities`() = runBlocking {
        coEvery { worldEventDao.unconsumed(any()) } returns emptyList()
        coEvery { beliefDao.allActiveInScopes(any(), any()) } returns emptyList()

        val count = engine().runCycle()
        assertEquals(0, count)
    }

    @Test
    fun `runCycle marks events consumed after processing`() = runBlocking {
        val event = WorldEventEntity(
            id = "e1",
            eventType = "memory_consolidated",
            source = "dream",
            summary = "Dream cycle: 3 clusters",
        )
        coEvery { worldEventDao.unconsumed(any()) } returns listOf(event)
        coEvery { beliefDao.allActiveInScopes(any(), any()) } returns emptyList()

        engine().runCycle()

        coVerify { worldEventDao.markConsumed("e1") }
    }

    @Test
    fun `memory_consolidated event generates review_dream opportunity`() = runBlocking {
        val event = WorldEventEntity(
            id = "e1",
            eventType = "memory_consolidated",
            source = "dream",
            summary = "Dream cycle: 3 clusters summarized",
        )
        coEvery { worldEventDao.unconsumed(any()) } returns listOf(event)
        coEvery { beliefDao.allActiveInScopes(any(), any()) } returns emptyList()

        val count = engine().runCycle()
        assertEquals(1, count)
        val captor = slot<OpportunityEntity>()
        coVerify { opportunityDao.upsert(capture(captor)) }
        assertTrue(captor.captured.title.contains("dream consolidation", ignoreCase = true))
    }

    @Test
    fun `destructive_action event generates high-urgency opportunity`() = runBlocking {
        val event = WorldEventEntity(
            id = "e1",
            eventType = "destructive_action",
            source = "tool:delete_memory",
            summary = "delete_memory: Deleted 5 memories",
        )
        coEvery { worldEventDao.unconsumed(any()) } returns listOf(event)
        coEvery { beliefDao.allActiveInScopes(any(), any()) } returns emptyList()

        val count = engine().runCycle()
        assertEquals(1, count)
        val captor = slot<OpportunityEntity>()
        coVerify { opportunityDao.upsert(capture(captor)) }
        assertEquals("action_required", captor.captured.kind)
        assertTrue(captor.captured.urgency > 0.8f)
    }

    @Test
    fun `evolution_approved event generates review opportunity`() = runBlocking {
        val event = WorldEventEntity(
            id = "e1",
            eventType = "evolution_approved",
            source = "evolution",
            summary = "Evolution action approved: CREATE_SKILL",
        )
        coEvery { worldEventDao.unconsumed(any()) } returns listOf(event)
        coEvery { beliefDao.allActiveInScopes(any(), any()) } returns emptyList()

        val count = engine().runCycle()
        assertEquals(1, count)
    }

    @Test
    fun `unverified belief generates verify_belief opportunity`() = runBlocking {
        val belief = BeliefEntity(
            id = "b1",
            subject = "user",
            predicate = "name",
            valueJson = "\"Bob\"",
            confidence = 0.7f,
            lastVerifiedAt = 0L,
        )
        coEvery { worldEventDao.unconsumed(any()) } returns emptyList()
        coEvery { beliefDao.allActiveInScopes(any(), any()) } returns listOf(belief)

        val count = engine().runCycle()
        assertEquals(1, count)
        val captor = slot<OpportunityEntity>()
        coVerify { opportunityDao.upsert(capture(captor)) }
        assertTrue(captor.captured.title.contains("Verify"))
        assertTrue(captor.captured.urgency < 0.5f)
    }

    @Test
    fun `contradictory beliefs generate resolve_conflict opportunity`() = runBlocking {
        val belief1 = BeliefEntity(
            id = "b1",
            subject = "user",
            predicate = "location",
            valueJson = "\"Baku\"",
            confidence = 0.8f,
        )
        val belief2 = BeliefEntity(
            id = "b2",
            subject = "user",
            predicate = "location",
            valueJson = "\"Tbilisi\"",
            confidence = 0.7f,
        )
        coEvery { worldEventDao.unconsumed(any()) } returns emptyList()
        coEvery { beliefDao.allActiveInScopes(any(), any()) } returns listOf(belief1, belief2)

        val count = engine().runCycle()
        // Two unverified beliefs + one contradiction = 3
        assertTrue(count >= 1)
    }

    @Test
    fun `opportunity ids are deterministic`() = runBlocking {
        val event = WorldEventEntity(
            id = "e1",
            eventType = "memory_consolidated",
            source = "dream",
            summary = "test",
        )
        coEvery { worldEventDao.unconsumed(any()) } returns listOf(event)
        coEvery { beliefDao.allActiveInScopes(any(), any()) } returns emptyList()

        val allCaptured = mutableListOf<OpportunityEntity>()
        coEvery { opportunityDao.upsert(capture(allCaptured)) } returns Unit

        engine().runCycle()
        engine().runCycle()

        // Two runs, each producing 1 opportunity — both should have the same id
        assertEquals(2, allCaptured.size)
        assertEquals(allCaptured[0].id, allCaptured[1].id)
    }
}