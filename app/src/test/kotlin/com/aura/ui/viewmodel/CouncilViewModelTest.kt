package com.aura.ui.viewmodel

import com.aura.agent.AgentCouncil
import com.aura.agent.AgentEntity
import com.aura.agent.AgentStore
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CouncilViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `runCouncil emits progress and result`() = runTest(dispatcher) {
        val agents = listOf(
            AgentEntity(id = "agent_a", name = "Agent A", icon = "", description = "", identity = "", toolsAllowed = ""),
            AgentEntity(id = "agent_b", name = "Agent B", icon = "", description = "", identity = "", toolsAllowed = ""),
        )
        val store = mockk<AgentStore>(relaxed = true).apply {
            every { all() } returns flowOf(agents)
        }
        val council = mockk<AgentCouncil>(relaxed = true).apply {
            coEvery {
                run(
                    agentIds = any(),
                    task = any(),
                    context = any(),
                    budgetMs = any(),
                    directorAgentId = any(),
                    onProgress = any(),
                )
            } coAnswers {
                val progress = invocation.args[5] as? (suspend (AgentCouncil.Progress) -> Unit)
                progress?.invoke(AgentCouncil.Progress.ProposalsStarted(listOf("Agent A", "Agent B")))
                progress?.invoke(AgentCouncil.Progress.DirectorDone("synthesis"))
                AgentCouncil.CouncilResult(directorOutput = "final answer", proposals = emptyList())
            }
        }
        val viewModel = CouncilViewModel(store, council)
        advanceUntilIdle()

        viewModel.setTask("test task")
        viewModel.runCouncil()
        advanceUntilIdle()

        val state = viewModel.state.value
        assertTrue(!state.running)
        assertEquals("final answer", state.result?.directorOutput)
        assertTrue(state.progress.any { it is AgentCouncil.Progress.ProposalsStarted })
        assertTrue(state.progress.any { it is AgentCouncil.Progress.DirectorDone })
    }
}
