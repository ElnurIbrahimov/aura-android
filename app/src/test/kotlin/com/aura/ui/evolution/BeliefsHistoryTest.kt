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
import kotlin.test.assertFalse

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

        // "I used to think kotlin" is exactly this row being present. The
        // active belief itself is filtered out of the chain — only what
        // preceded it remains.
        val chain = vm.state.value.history["b2"]
        assertEquals(1, chain?.size)
        assertEquals("b1", chain?.get(0)?.id)
    }

    @Test
    fun `orders superseded predecessors newest-first`() = runTest(dispatcher) {
        val active = BeliefEntity(id = "b4", subject = "user", predicate = "USES", valueJson = "\"go\"")
        // BeliefDao.history is ORDER BY createdAt ASC — mock it that way.
        val oldest = BeliefEntity(
            id = "b1", subject = "user", predicate = "USES", valueJson = "\"c\"",
            status = "superseded", supersededBy = "b2", createdAt = 1_000L,
        )
        val middle = BeliefEntity(
            id = "b2", subject = "user", predicate = "USES", valueJson = "\"kotlin\"",
            status = "superseded", supersededBy = "b3", createdAt = 2_000L,
        )
        val newest = BeliefEntity(
            id = "b3", subject = "user", predicate = "USES", valueJson = "\"rust\"",
            status = "superseded", supersededBy = "b4", createdAt = 3_000L,
        )
        coEvery { beliefDao.allActive(any()) } returns listOf(active)
        coEvery { beliefDao.history("user", "USES") } returns listOf(oldest, middle, newest, active)

        val vm = BeliefsViewModel(beliefDao, evidenceDao)
        advanceUntilIdle()

        val ids = vm.state.value.history["b4"]?.map { it.id }
        assertEquals(listOf("b3", "b2", "b1"), ids)
    }

    @Test
    fun `truncating to 3 keeps the most recent predecessors, not the oldest`() = runTest(dispatcher) {
        val active = BeliefEntity(id = "b5", subject = "user", predicate = "USES", valueJson = "\"go\"")
        val gen1 = BeliefEntity(
            id = "b1", subject = "user", predicate = "USES", valueJson = "\"c\"",
            status = "superseded", supersededBy = "b2", createdAt = 1_000L,
        )
        val gen2 = BeliefEntity(
            id = "b2", subject = "user", predicate = "USES", valueJson = "\"kotlin\"",
            status = "superseded", supersededBy = "b3", createdAt = 2_000L,
        )
        val gen3 = BeliefEntity(
            id = "b3", subject = "user", predicate = "USES", valueJson = "\"rust\"",
            status = "superseded", supersededBy = "b4", createdAt = 3_000L,
        )
        val gen4 = BeliefEntity(
            id = "b4", subject = "user", predicate = "USES", valueJson = "\"python\"",
            status = "superseded", supersededBy = "b5", createdAt = 4_000L,
        )
        coEvery { beliefDao.allActive(any()) } returns listOf(active)
        coEvery { beliefDao.history("user", "USES") } returns listOf(gen1, gen2, gen3, gen4, active)

        val vm = BeliefsViewModel(beliefDao, evidenceDao)
        advanceUntilIdle()

        val firstThreeIds = vm.state.value.history["b5"].orEmpty().take(3).map { it.id }
        assertEquals(listOf("b4", "b3", "b2"), firstThreeIds)
        assertFalse(firstThreeIds.contains("b1"), "oldest superseded belief should be dropped by take(3)")
    }
}
