package com.aura.ui.evolution

import com.aura.world.BeliefDao
import com.aura.world.BeliefEntity
import com.aura.world.EvidenceDao
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
class BeliefsHistoryTest {

    private val dispatcher = StandardTestDispatcher()
    private val beliefDao = mockk<BeliefDao>(relaxed = true)
    private val evidenceDao = mockk<EvidenceDao>(relaxed = true)

    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    @Test
    fun `loads the supersession chain for an active belief`() = runTest(dispatcher) {
        val active = BeliefEntity(id = "b2", subject = "user", predicate = "USES", valueJson = "\"rust\"")
        val superseded = BeliefEntity(
            id = "b1", subject = "user", predicate = "USES", valueJson = "\"kotlin\"",
            status = "superseded", supersededBy = "b2",
        )
        coEvery { beliefDao.allActive(any()) } returns listOf(active)
        coEvery { beliefDao.history("user", "USES") } returns listOf(active, superseded)

        val vm = BeliefsViewModel(beliefDao, evidenceDao)
        advanceUntilIdle()

        // "I used to think kotlin" is exactly this row being present.
        assertEquals(2, vm.state.value.history["b2"]?.size)
    }
}
