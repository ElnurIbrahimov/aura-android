package com.aura.ui.screens

import com.aura.dream.ContradictionDao
import com.aura.dream.ContradictionEntity
import com.aura.dream.DreamConsolidationDao
import com.aura.dream.DreamSummaryEntity
import com.aura.dream.KgEdgeProposalDao
import com.aura.dream.KgEdgeProposalEntity
import com.aura.dream.RoutineDao
import com.aura.kg.KnowledgeGraphRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
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
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class DreamsViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val routineDao = mockk<RoutineDao>(relaxed = true)
    private val contradictionDao = mockk<ContradictionDao>(relaxed = true)
    private val dreamDao = mockk<DreamConsolidationDao>(relaxed = true)
    private val kgProposalDao = mockk<KgEdgeProposalDao>(relaxed = true)
    private val knowledgeGraphRepository = mockk<KnowledgeGraphRepository>(relaxed = true)

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
        every { routineDao.observeAll() } returns flowOf(emptyList())
        every { contradictionDao.observeByStatus("UNRESOLVED") } returns flowOf(emptyList())
        every { dreamDao.observeAll() } returns flowOf(emptyList())
        every { kgProposalDao.observeByStatus("PENDING") } returns flowOf(emptyList())
    }

    @After fun tearDown() { Dispatchers.resetMain() }

    private fun viewModel(): DreamsViewModel = DreamsViewModel(
        routineDao = routineDao,
        contradictionDao = contradictionDao,
        dreamDao = dreamDao,
        kgProposalDao = kgProposalDao,
        knowledgeGraphRepository = knowledgeGraphRepository,
    )

    @Test
    fun `resolving contradiction updates status to RESOLVED when acceptNewer true`() = runTest(dispatcher) {
        val entity = ContradictionEntity(
            id = "c1",
            olderSummaryId = "old",
            newerSummaryId = "new",
            olderText = "old text",
            newerText = "new text",
            triggerPhrase = "no longer",
        )
        coEvery { contradictionDao.byId("c1") } returns entity

        viewModel().resolveContradiction("c1", acceptNewer = true)
        advanceUntilIdle()

        val slot = slot<ContradictionEntity>()
        coVerify { contradictionDao.update(capture(slot)) }
        assertEquals("RESOLVED", slot.captured.status)
    }

    @Test
    fun `accepting kg proposal inserts edge and marks ACCEPTED`() = runTest(dispatcher) {
        val proposal = KgEdgeProposalEntity(
            id = "p1",
            fromNodeId = "n1",
            toNodeId = "n2",
            fromLabel = "A",
            toLabel = "B",
            similarity = 0.8f,
        )
        coEvery { kgProposalDao.byId("p1") } returns proposal

        viewModel().acceptKgProposal("p1")
        advanceUntilIdle()

        coVerify { knowledgeGraphRepository.addRelatesToEdge("n1", "n2", 0.8f) }
        val slot = slot<KgEdgeProposalEntity>()
        coVerify { kgProposalDao.update(capture(slot)) }
        assertEquals("ACCEPTED", slot.captured.status)
    }
}
