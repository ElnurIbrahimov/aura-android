package com.aura.ui.evolution

import com.aura.world.BeliefDao
import com.aura.world.BeliefEntity
import com.aura.world.EvidenceDao
import com.aura.world.EvidenceEntity
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
class BeliefsViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val beliefDao = mockk<BeliefDao>(relaxed = true)
    private val evidenceDao = mockk<EvidenceDao>(relaxed = true)

    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    @Test
    fun `loads evidence for each active belief`() = runTest(dispatcher) {
        val belief = BeliefEntity(
            id = "b1", subject = "user", predicate = "USES", valueJson = "\"kotlin\"",
        )
        coEvery { beliefDao.allActive(any()) } returns listOf(belief)
        coEvery { evidenceDao.forBelief("b1") } returns listOf(
            EvidenceEntity(id = "e1", beliefId = "b1", source = "kg_edge", summary = "USES → kotlin"),
        )

        val vm = BeliefsViewModel(beliefDao, evidenceDao)
        advanceUntilIdle()

        assertEquals(1, vm.state.value.beliefs.size)
        assertEquals(1, vm.state.value.evidence["b1"]?.size)
    }

    @Test
    fun `a failing evidence fetch leaves the belief listed with no evidence`() = runTest(dispatcher) {
        val belief = BeliefEntity(
            id = "b1", subject = "user", predicate = "USES", valueJson = "\"kotlin\"",
        )
        coEvery { beliefDao.allActive(any()) } returns listOf(belief)
        coEvery { evidenceDao.forBelief("b1") } throws RuntimeException("db unavailable")

        val vm = BeliefsViewModel(beliefDao, evidenceDao)
        advanceUntilIdle()

        // The belief must still render — losing evidence must not lose the belief.
        assertEquals(1, vm.state.value.beliefs.size)
        assertEquals(emptyList(), vm.state.value.evidence["b1"])
    }

    @Test
    fun `evidence is keyed per belief`() = runTest(dispatcher) {
        val a = BeliefEntity(id = "b1", subject = "user", predicate = "USES", valueJson = "\"kotlin\"")
        val b = BeliefEntity(id = "b2", subject = "user", predicate = "KNOWS", valueJson = "\"rust\"")
        coEvery { beliefDao.allActive(any()) } returns listOf(a, b)
        coEvery { evidenceDao.forBelief("b1") } returns listOf(
            EvidenceEntity(id = "e1", beliefId = "b1", source = "kg_edge", summary = "USES -> kotlin"),
        )
        coEvery { evidenceDao.forBelief("b2") } returns listOf(
            EvidenceEntity(id = "e2", beliefId = "b2", source = "kg_edge", summary = "KNOWS -> rust"),
            EvidenceEntity(id = "e3", beliefId = "b2", source = "kg_edge", summary = "KNOWS -> rust"),
        )

        val vm = BeliefsViewModel(beliefDao, evidenceDao)
        advanceUntilIdle()

        assertEquals(1, vm.state.value.evidence["b1"]?.size)
        assertEquals(2, vm.state.value.evidence["b2"]?.size)
        assertEquals("USES -> kotlin", vm.state.value.evidence["b1"]?.first()?.summary)
    }
}
