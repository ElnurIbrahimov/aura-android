package com.aura.tools

import com.aura.agent.ToolCall
import com.aura.agent.ToolContext
import com.aura.agent.ToolResult
import com.aura.world.BeliefDao
import com.aura.world.BeliefEntity
import com.aura.world.OpportunityDao
import com.aura.world.OpportunityEntity
import com.aura.world.WorldEventDao
import com.aura.world.WorldEventEntity
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue

class QueryWorldModelToolTest {
    private val beliefDao = mockk<BeliefDao>(relaxed = true)
    private val worldEventDao = mockk<WorldEventDao>(relaxed = true)
    private val opportunityDao = mockk<OpportunityDao>(relaxed = true)
    private val tool = QueryWorldModelTool(beliefDao, worldEventDao, opportunityDao)

    @Test
    fun `query returns beliefs, events, and opportunities`() = runTest {
        coEvery { beliefDao.allActive(10) } returns listOf(
            BeliefEntity(id = "b1", subject = "user", predicate = "name", valueJson = "\"Elnur\"", confidence = 0.95f),
        )
        coEvery { worldEventDao.unconsumed(10) } returns listOf(
            WorldEventEntity(id = "e1", eventType = "deadline", source = "calendar", summary = "Project X ships Friday"),
        )
        coEvery { opportunityDao.pending(any(), 10) } returns listOf(
            OpportunityEntity(id = "o1", title = "Review PR", description = "PR #23 needs review"),
        )
        val result = tool.tool.execute(
            ToolCall(id = "1", name = "query_world_model", arguments = mapOf("question" to "what's happening")),
            ToolContext(conversationId = "c1"),
        )
        assertIs<ToolResult.Ok>(result)
        assertTrue(result.output.contains("Elnur"))
        assertTrue(result.output.contains("Project X ships Friday"))
        assertTrue(result.output.contains("Review PR"))
    }

    @Test
    fun `query with no data returns helpful message`() = runTest {
        coEvery { beliefDao.allActive(10) } returns emptyList()
        coEvery { worldEventDao.unconsumed(10) } returns emptyList()
        coEvery { opportunityDao.pending(any(), 10) } returns emptyList()
        val result = tool.tool.execute(
            ToolCall(id = "1", name = "query_world_model", arguments = mapOf("question" to "anything?")),
            ToolContext(conversationId = "c1"),
        )
        assertIs<ToolResult.Ok>(result)
        assertTrue(result.output.contains("No world-model entries found"))
    }
}