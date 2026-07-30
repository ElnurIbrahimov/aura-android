package com.aura.world

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorldEventProducerTest {

    private val worldEventDao = mockk<WorldEventDao>(relaxed = true)
    private val producer = WorldEventProducer(worldEventDao)

    @Test
    fun `record inserts event and returns id`() = runBlocking {
        val id = producer.record(
            eventType = "test_event",
            source = "unit_test",
            summary = "Test event",
        )
        assertTrue(id.startsWith("evt_"))
        coVerify { worldEventDao.insert(any()) }
    }

    @Test
    fun `recordToolExecution skips READ_ONLY tools`() = runBlocking {
        val id = producer.recordToolExecution(
            toolName = "web_search",
            toolRisk = com.aura.agent.ToolRisk.READ_ONLY,
            resultSummary = "search results",
        )
        assertNull(id)
        coVerify(exactly = 0) { worldEventDao.insert(any()) }
    }

    @Test
    fun `recordToolExecution skips REMOTE_COST tools`() = runBlocking {
        val id = producer.recordToolExecution(
            toolName = "tavily_search",
            toolRisk = com.aura.agent.ToolRisk.REMOTE_COST,
            resultSummary = "search results",
        )
        assertNull(id)
        coVerify(exactly = 0) { worldEventDao.insert(any()) }
    }

    @Test
    fun `recordToolExecution records WRITE_LOCAL tools`() = runBlocking {
        val id = producer.recordToolExecution(
            toolName = "set_reminder",
            toolRisk = com.aura.agent.ToolRisk.WRITE_LOCAL,
            resultSummary = "Reminder set for 3pm",
        )
        assertNotNull(id)
        coVerify { worldEventDao.insert(any()) }
    }

    @Test
    fun `recordToolExecution records WRITE_REMOTE tools`() = runBlocking {
        val id = producer.recordToolExecution(
            toolName = "send_email",
            toolRisk = com.aura.agent.ToolRisk.WRITE_REMOTE,
            resultSummary = "Email sent",
        )
        assertNotNull(id)
        coVerify { worldEventDao.insert(any()) }
    }

    @Test
    fun `recordToolExecution records DESTRUCTIVE tools`() = runBlocking {
        val id = producer.recordToolExecution(
            toolName = "delete_memory",
            toolRisk = com.aura.agent.ToolRisk.DESTRUCTIVE,
            resultSummary = "Deleted 5 memories",
        )
        assertNotNull(id)
        coVerify { worldEventDao.insert(any()) }
    }

    @Test
    fun `recordDreamCycle creates memory_consolidated event`() = runBlocking {
        val id = producer.recordDreamCycle(
            cycleId = "dream_123",
            summariesWritten = 5,
            memoriesArchived = 3,
        )
        assertTrue(id.startsWith("evt_"))
        val captor = slot<WorldEventEntity>()
        coVerify { worldEventDao.insert(capture(captor)) }
        assertEquals("memory_consolidated", captor.captured.eventType)
        assertEquals("dream", captor.captured.source)
        assertTrue(captor.captured.summary.contains("5 clusters"))
        assertTrue(captor.captured.summary.contains("3 memories"))
    }

    @Test
    fun `recordEvolutionApproval creates evolution_approved event`() = runBlocking {
        val id = producer.recordEvolutionApproval(
            action = "CREATE_SKILL",
            proposalId = "prop_123",
        )
        assertTrue(id.startsWith("evt_"))
        val captor = slot<WorldEventEntity>()
        coVerify { worldEventDao.insert(capture(captor)) }
        assertEquals("evolution_approved", captor.captured.eventType)
        assertEquals("evolution", captor.captured.source)
    }

    @Test
    fun `markAllConsumed marks unconsumed events`() = runBlocking {
        val events = listOf(
            WorldEventEntity(id = "e1", eventType = "test", source = "test", summary = "s1"),
            WorldEventEntity(id = "e2", eventType = "test", source = "test", summary = "s2"),
        )
        coEvery { worldEventDao.unconsumed(any()) } returns events

        producer.markAllConsumed()

        coVerify { worldEventDao.markConsumed("e1") }
        coVerify { worldEventDao.markConsumed("e2") }
    }
}