package com.aura.ui.viewmodel

import com.aura.agentrun.AgentRunEntity
import com.aura.agentrun.AgentRunStore
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AgentRunsViewModelTest {

    private val agentRunStore = mockk<AgentRunStore>(relaxed = true)
    private val context = mockk<android.content.Context>(relaxed = true)

    @Before
    fun setUp() { Dispatchers.setMain(UnconfinedTestDispatcher()) }
    @After
    fun tearDown() { Dispatchers.resetMain() }

    private fun makeVm(): AgentRunsViewModel {
        coEvery { agentRunStore.listRecent(any()) } returns emptyList()
        return AgentRunsViewModel(agentRunStore, context)
    }

    @Test
    fun `init loads runs from store`() = runTest {
        val runs = listOf(
            AgentRunEntity(id = "r1", goalId = "g1", triggerType = "USER_QUERY", status = "COMPLETED"),
            AgentRunEntity(id = "r2", goalId = "g2", triggerType = "PROACTIVE", status = "RUNNING"),
        )
        coEvery { agentRunStore.listRecent(any()) } returns runs
        val vm = AgentRunsViewModel(agentRunStore, context)

        // Wait for init coroutine
        kotlinx.coroutines.delay(100)

        assertEquals(2, vm.state.value.runs.size)
        assertEquals("r1", vm.state.value.runs[0].id)
    }

    @Test
    fun `loadRuns sets error on exception`() = runTest {
        coEvery { agentRunStore.listRecent(any()) } throws RuntimeException("DB error")
        val vm = AgentRunsViewModel(agentRunStore, context)

        kotlinx.coroutines.delay(100)

        assertEquals("DB error", vm.state.value.error)
    }

    @Test
    fun `selectRun loads run details`() = runTest {
        val run = AgentRunEntity(id = "r1", goalId = "g1", triggerType = "USER_QUERY", status = "COMPLETED")
        coEvery { agentRunStore.loadRun("r1") } returns run
        coEvery { agentRunStore.stepsForRun("r1") } returns emptyList()
        coEvery { agentRunStore.eventsForRun("r1") } returns emptyList()
        coEvery { agentRunStore.pendingApprovals("r1") } returns emptyList()
        val vm = makeVm()

        vm.selectRun("r1")

        kotlinx.coroutines.delay(100)

        assertEquals("r1", vm.state.value.selectedRun?.id)
    }

    @Test
    fun `selectRun with unknown id does not update state`() = runTest {
        coEvery { agentRunStore.loadRun("unknown") } returns null
        val vm = makeVm()

        vm.selectRun("unknown")

        kotlinx.coroutines.delay(100)

        assertNull(vm.state.value.selectedRun)
    }
}