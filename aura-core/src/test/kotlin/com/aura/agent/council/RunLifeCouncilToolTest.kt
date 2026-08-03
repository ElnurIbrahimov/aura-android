package com.aura.agent.council

import com.aura.agent.ToolCall
import com.aura.agent.ToolContext
import com.aura.agent.ToolResult
import com.aura.agent.ToolRisk
import com.aura.agent.forum.DebateRoundUseCase
import com.aura.agent.forum.ForumEngine
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class RunLifeCouncilToolTest {

    private val councilOrchestrator: CouncilOrchestrator = mockk(relaxed = true)
    private lateinit var tool: RunLifeCouncilTool
    private val mockContext: ToolContext = mockk(relaxed = true)

    @Before
    fun setUp() {
        tool = RunLifeCouncilTool(councilOrchestrator)
    }

    @Test
    fun `tool has correct name and risk`() {
        assertEquals("run_life_council", tool.tool.name)
        assertEquals(ToolRisk.REMOTE_COST, tool.tool.risk)
    }

    @Test
    fun `execute with valid topic returns debate transcript`() = runBlocking {
        coEvery {
            councilOrchestrator.runSession(any(), any())
        } returns CouncilResult(
            threadId = "test_thread",
            topic = "Should I take a break?",
            debateEntries = listOf(
                DebateRoundUseCase.DebateEntry("agent_general", "general", "Yes, you seem stressed", 0.6f),
                DebateRoundUseCase.DebateEntry("agent_researcher", "researcher", "Agreed, breaks improve focus", 0.7f),
            ),
            quorumReached = true,
            proposal = Intervention.SelfCare("Take a 15-minute walk", "Council agreed: stress detected"),
            voteTally = ForumEngine.VoteTally(3, 0, 0),
        )

        val result = tool.tool.execute(
            ToolCall(id = "1", name = "run_life_council", arguments = mapOf("topic" to "Should I take a break?")),
            mockContext,
        )

        assertTrue(result is ToolResult.Ok)
        val output = (result as ToolResult.Ok).output
        assertTrue(output.contains("Council: Should I take a break?"))
        assertTrue(output.contains("General:"))
        assertTrue(output.contains("PROPOSAL APPROVED"))
        assertTrue(output.contains("Take a 15-minute walk"))
    }

    @Test
    fun `execute without topic returns error`() = runBlocking {
        val result = tool.tool.execute(
            ToolCall(id = "1", name = "run_life_council", arguments = emptyMap()),
            mockContext,
        )
        assertTrue(result is ToolResult.Error)
        assertEquals("bad_args", (result as ToolResult.Error).code)
    }

    @Test
    fun `execute with failed quorum shows vote tally`() = runBlocking {
        coEvery {
            councilOrchestrator.runSession(any(), any())
        } returns CouncilResult(
            threadId = "test_thread",
            topic = "Should I quit my job?",
            debateEntries = listOf(
                DebateRoundUseCase.DebateEntry("agent_general", "general", "No, too risky right now", -0.5f),
                DebateRoundUseCase.DebateEntry("agent_executive", "executive", "Agreed, wait for savings", -0.6f),
            ),
            quorumReached = false,
            proposal = null,
            voteTally = ForumEngine.VoteTally(1, 2, 0),
            dissent = "Researcher: consider upskilling first",
        )

        val result = tool.tool.execute(
            ToolCall(id = "1", name = "run_life_council", arguments = mapOf("topic" to "Should I quit my job?")),
            mockContext,
        )

        assertTrue(result is ToolResult.Ok)
        val output = (result as ToolResult.Ok).output
        assertTrue(output.contains("VOTE FAILED"))
        assertTrue(output.contains("For: 1, Against: 2"))
        assertTrue(output.contains("Dissent"))
    }
}