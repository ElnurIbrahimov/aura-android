package com.aura.tools.evolution

import com.aura.agent.ToolContext
import com.aura.agent.ToolRisk
import com.aura.evolution.EvolutionApplySaga
import com.aura.evolution.EvolutionCoordinator
import com.aura.evolution.EvolutionProposalEntity
import com.aura.evolution.EvolutionProposalStore
import com.aura.evolution.EvolutionRollbackManager
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EvolutionToolsTest {

    @Test
    fun `approve tool applies proposal`() = runTest {
        val store = mockk<EvolutionProposalStore>(relaxed = true)
        val saga = mockk<EvolutionApplySaga>(relaxed = true)
        val proposal = mockk<EvolutionProposalEntity>(relaxed = true)
        coEvery { store.getById("p1") } returns proposal
        coEvery { saga.apply(proposal) } returns EvolutionApplySaga.ApplyResult.Ok("p1", "patched skill")
        val tool = ApproveEvolutionProposalTool(store, saga).tool
        val result = tool.execute(
            com.aura.agent.ToolCall("c1", "approve_evolution_proposal", mapOf("proposalId" to "p1")),
            ToolContext(conversationId = "conv1"),
        )
        assertTrue(result is com.aura.agent.ToolResult.Ok)
        coVerify { store.approve("p1") }
    }

    @Test
    fun `trigger tool returns run metrics`() = runTest {
        val coordinator = mockk<EvolutionCoordinator>(relaxed = true)
        coEvery { coordinator.runAll() } returns EvolutionCoordinator.RunResult(3, 1, 120L)
        val tool = TriggerEvolutionRunTool(coordinator).tool
        val result = tool.execute(
            com.aura.agent.ToolCall("c1", "trigger_evolution_run", emptyMap()),
            ToolContext(conversationId = "conv1"),
        )
        assertTrue(result is com.aura.agent.ToolResult.Ok)
    }

    @Test
    fun `rollback tool returns ok`() = runTest {
        val manager = mockk<EvolutionRollbackManager>(relaxed = true)
        coEvery { manager.rollback("p1") } returns EvolutionRollbackManager.RollbackResult.Ok("restored skill")
        val tool = RollbackEvolutionTool(manager).tool
        val result = tool.execute(
            com.aura.agent.ToolCall("c1", "rollback_evolution_change", mapOf("proposalId" to "p1")),
            ToolContext(conversationId = "conv1"),
        )
        assertTrue(result is com.aura.agent.ToolResult.Ok)
    }
}
