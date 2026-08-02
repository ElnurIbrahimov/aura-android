package com.aura.ui.evolution

import com.aura.evolution.EvolutionProposalDao
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import app.cash.turbine.test
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals

/**
 * Tests for [EvolutionBadgeViewModel] — verifies it exposes the
 * pending proposal count from the DAO.
 */
class EvolutionBadgeViewModelTest {

    @Before
    fun setUp() { Dispatchers.setMain(UnconfinedTestDispatcher()) }
    @After
    fun tearDown() { Dispatchers.resetMain() }

    @Test
    fun `pendingCount starts at 0`() {
        val dao = mockk<EvolutionProposalDao>(relaxed = true)
        every { dao.observePendingCount() } returns flowOf(0)
        val vm = EvolutionBadgeViewModel(dao)

        assertEquals(0, vm.pendingCount.value)
    }

    @Test
    fun `pendingCount reflects DAO count`() = runTest {
        val dao = mockk<EvolutionProposalDao>(relaxed = true)
        every { dao.observePendingCount() } returns flowOf(5)
        val vm = EvolutionBadgeViewModel(dao)

        // stateIn(WhileSubscribed) doesn't emit until collected
        vm.pendingCount.test {
            assertEquals(5, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `pendingCount with zero proposals`() {
        val dao = mockk<EvolutionProposalDao>(relaxed = true)
        every { dao.observePendingCount() } returns flowOf(0)
        val vm = EvolutionBadgeViewModel(dao)

        assertEquals(0, vm.pendingCount.value)
    }
}