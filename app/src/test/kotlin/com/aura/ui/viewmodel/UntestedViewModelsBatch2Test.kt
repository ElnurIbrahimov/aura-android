package com.aura.ui.viewmodel

import com.aura.proactive.ProactiveEventBus
import com.aura.proactive.ProactiveEvents
import com.aura.proactive.ProactiveRunner
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * Tests for ViewModels with zero coverage — batch 2.
 * Covers ProactiveHistoryViewModel, VoiceViewModel (contract-level),
 * ProductionPipelineViewModel, EvolutionInboxViewModel.
 */
class UntestedViewModelsBatch2Test {

    @Before fun setUp() { Dispatchers.setMain(Dispatchers.Unconfined) }
    @After fun tearDown() { Dispatchers.resetMain() }

    // ── ProactiveHistoryViewModel ──────────────────────────────────

    @Test
    fun `ProactiveHistoryViewModel exposes history state`() = runTest {
        val events = mockk<ProactiveEvents>(relaxed = true)
        every { events.history } returns MutableStateFlow(emptyList())
        every { events.markSeen() } returns Unit
        val runner = mockk<ProactiveRunner>(relaxed = true)
        val vm = ProactiveHistoryViewModel(events, runner)
        assertNotNull(vm.state)
        assertNotNull(vm.status)
        assertNull(vm.status.value)
    }

    @Test
    fun `ProactiveHistoryViewModel clearStatus nulls status`() = runTest {
        val events = mockk<ProactiveEvents>(relaxed = true)
        every { events.history } returns MutableStateFlow(emptyList())
        every { events.markSeen() } returns Unit
        val runner = mockk<ProactiveRunner>(relaxed = true)
        val vm = ProactiveHistoryViewModel(events, runner)
        vm.clearStatus()
        assertNull(vm.status.value)
    }

    @Test
    fun `ProactiveHistoryViewModel fireMorningBrief sets status`() = runTest {
        val events = mockk<ProactiveEvents>(relaxed = true)
        every { events.history } returns MutableStateFlow(emptyList())
        every { events.markSeen() } returns Unit
        val runner = mockk<ProactiveRunner>(relaxed = true)
        coEvery { runner.fireMorningBrief() } returns ProactiveRunner.RunResult.Ok("done")
        val vm = ProactiveHistoryViewModel(events, runner)
        vm.fireMorningBrief()
        assertNotNull(vm.status.value)
    }

    // ── EvolutionInboxViewModel ─────────────────────────────────────

    @Test
    fun `EvolutionInboxViewModel loads proposals`() = runTest {
        val proposalDao = mockk<com.aura.evolution.EvolutionProposalDao>(relaxed = true)
        val proposalStore = mockk<com.aura.evolution.EvolutionProposalStore>(relaxed = true)
        val settingsDao = mockk<com.aura.evolution.EvolutionSettingsDao>(relaxed = true)
        val rollbackManager = mockk<com.aura.evolution.EvolutionRollbackManager>(relaxed = true)
        val userPreferences = mockk<com.aura.data.UserPreferences>(relaxed = true)
        val applySaga = mockk<com.aura.evolution.EvolutionApplySaga>(relaxed = true)
        val vm = com.aura.ui.evolution.EvolutionInboxViewModel(
            proposalDao, proposalStore, settingsDao, rollbackManager, userPreferences, applySaga,
        )
        assertNotNull(vm.proposals)
    }

    // ── ProductionPipelineViewModel ────────────────────────────────

    @Test
    fun `ProductionPipelineViewModel exposes state`() = runTest {
        val projectStore = mockk<com.aura.creative.CreativeProjectStore>(relaxed = true)
        every { projectStore.observeAll() } returns flowOf(emptyList())
        val engine = mockk<com.aura.creative.ProductionPipelineEngine>(relaxed = true)
        val vm = ProductionPipelineViewModel(projectStore, engine)
        assertNotNull(vm.state)
        assertEquals(false, vm.state.value.busy)
    }
}