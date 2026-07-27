package com.aura.ui.viewmodel

import com.aura.dream.ContradictionDao
import com.aura.taste.PreferenceSignalDao
import com.aura.taste.StyleProfileDao
import com.aura.taste.TasteEngine
import com.aura.world.BeliefDao
import com.aura.world.EvidenceDao
import com.aura.world.OpportunityDao
import com.aura.world.WorldEventDao
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WorldModelViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val beliefDao = mockk<BeliefDao>(relaxed = true)
    private val evidenceDao = mockk<EvidenceDao>(relaxed = true)
    private val worldEventDao = mockk<WorldEventDao>(relaxed = true)
    private val opportunityDao = mockk<OpportunityDao>(relaxed = true)
    private val contradictionDao = mockk<ContradictionDao>(relaxed = true)

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
        coEvery { beliefDao.allActiveInScopes(any(), any()) } returns emptyList()
        every { worldEventDao.observeRecent(any()) } returns flowOf(emptyList())
        every { opportunityDao.observeProposed(any()) } returns flowOf(emptyList())
        every { contradictionDao.observeByStatus(any()) } returns flowOf(emptyList())
    }

    @After fun tearDown() { Dispatchers.resetMain() }

    private fun vm(): WorldModelViewModel = WorldModelViewModel(
        beliefDao, evidenceDao, worldEventDao, opportunityDao, contradictionDao
    )

    @Test
    fun `approve opportunity resolves status`() = runTest(dispatcher) {
        vm().resolveOpportunity("op1", approve = true)
        advanceUntilIdle()
        coVerify { opportunityDao.resolve("op1", "approved", any()) }
    }
}
