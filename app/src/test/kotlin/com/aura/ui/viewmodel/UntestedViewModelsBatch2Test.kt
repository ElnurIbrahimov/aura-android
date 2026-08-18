package com.aura.ui.viewmodel

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
 * ViewModels that had no coverage — batch 2.
 *
 * Rewritten rather than deleted. The original assertions were mostly
 * `assertNotNull(vm.state)` against non-nullable Kotlin properties, which cannot
 * fail short of a compile error, and one `clearStatus` test that asserted the
 * status was null after clearing it — having never set it, so it passed whether
 * or not `clearStatus` did anything at all.
 *
 * The real property under all of them is worth keeping: these ViewModels
 * construct without throwing and start in a sane state, which is exactly what a
 * Hilt graph change breaks. So each now asserts something a defect could
 * actually violate, and the two that already did were left alone.
 */
class UntestedViewModelsBatch2Test {

    @Before fun setUp() { Dispatchers.setMain(Dispatchers.Unconfined) }
    @After fun tearDown() { Dispatchers.resetMain() }

    private fun historyViewModel(
        runner: ProactiveRunner = mockk(relaxed = true),
    ): ProactiveHistoryViewModel {
        val events = mockk<ProactiveEvents>(relaxed = true)
        every { events.history } returns MutableStateFlow(emptyList())
        every { events.markSeen() } returns Unit
        return ProactiveHistoryViewModel(events, runner)
    }

    // ── ProactiveHistoryViewModel ──────────────────────────────────

    @Test
    fun `ProactiveHistoryViewModel starts with no status`() = runTest {
        // `assertNotNull(vm.state)` went here and could not fail. The status
        // being null on construction is the part a regression could break — it
        // is what decides whether the screen opens showing a stale banner.
        assertNull(historyViewModel().status.value)
    }

    @Test
    fun `ProactiveHistoryViewModel clearStatus clears a status that exists`() = runTest {
        // Previously this cleared a status that had never been set, so it passed
        // against an empty `clearStatus`. Setting one first is what makes the
        // call the thing under test.
        val runner = mockk<ProactiveRunner>(relaxed = true)
        coEvery { runner.fireMorningBrief() } returns ProactiveRunner.RunResult.Ok("done")
        val vm = historyViewModel(runner)

        vm.fireMorningBrief()
        assertNotNull("precondition: firing a brief sets a status", vm.status.value)

        vm.clearStatus()
        assertNull(vm.status.value)
    }

    @Test
    fun `ProactiveHistoryViewModel fireMorningBrief sets status`() = runTest {
        val runner = mockk<ProactiveRunner>(relaxed = true)
        coEvery { runner.fireMorningBrief() } returns ProactiveRunner.RunResult.Ok("done")
        val vm = historyViewModel(runner)
        vm.fireMorningBrief()
        assertNotNull(vm.status.value)
    }

    // ── EvolutionInboxViewModel ─────────────────────────────────────

    @Test
    fun `EvolutionInboxViewModel starts with an empty inbox`() = runTest {
        // `assertNotNull(vm.proposals)` was the whole test, against a
        // non-nullable property. What it was reaching for — that this
        // six-dependency constructor resolves and does not throw — is kept by
        // constructing it; the emptiness is the part that can actually regress.
        val vm = com.aura.ui.evolution.EvolutionInboxViewModel(
            mockk<com.aura.evolution.EvolutionProposalDao>(relaxed = true),
            mockk<com.aura.evolution.EvolutionProposalStore>(relaxed = true),
            mockk<com.aura.evolution.EvolutionSettingsDao>(relaxed = true),
            mockk<com.aura.evolution.EvolutionRollbackManager>(relaxed = true),
            mockk<com.aura.data.UserPreferences>(relaxed = true),
            mockk<com.aura.evolution.EvolutionApplySaga>(relaxed = true),
        )
        assertEquals(emptyList<Any?>(), vm.proposals.value)
    }

    // ── ProductionPipelineViewModel ────────────────────────────────

    @Test
    fun `ProductionPipelineViewModel starts idle`() = runTest {
        val projectStore = mockk<com.aura.creative.CreativeProjectStore>(relaxed = true)
        every { projectStore.observeAll() } returns flowOf(emptyList())
        val engine = mockk<com.aura.creative.ProductionPipelineEngine>(relaxed = true)
        val vm = ProductionPipelineViewModel(projectStore, engine)
        assertEquals(false, vm.state.value.busy)
    }
}
