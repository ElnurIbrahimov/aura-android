package com.aura.world

import com.aura.dream.ContradictionDao
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BeliefReviserTest {

    private val beliefDao = mockk<BeliefDao>(relaxed = true)
    private val contradictionDao = mockk<ContradictionDao>(relaxed = true)
    private fun reviser() = BeliefReviser(beliefDao, contradictionDao)

    private fun belief(id: String) =
        BeliefEntity(id = id, subject = "user", predicate = "USES", valueJson = "\"$id\"")

    @Test
    fun `winner supersedes loser without deleting it`() = runBlocking {
        val verdict = Verdict.Winner(belief("new"), belief("old"), margin = 0.4f)

        assertTrue(reviser().applyVerdict(verdict, now = 5_000L))

        // The loser is marked, never removed — the chain is the feature.
        coVerify { beliefDao.supersede("old", "superseded", "new", 5_000L) }
        coVerify(exactly = 0) { beliefDao.deleteAll() }
    }

    @Test
    fun `too close writes nothing`() = runBlocking {
        assertFalse(reviser().applyVerdict(Verdict.TooClose, now = 5_000L))
        coVerify(exactly = 0) { beliefDao.supersede(any(), any(), any(), any()) }
    }

    @Test
    fun `revision records a resolved contradiction linking both beliefs`() = runBlocking {
        val verdict = Verdict.Winner(belief("new"), belief("old"), margin = 0.4f)

        reviser().applyVerdict(verdict, now = 5_000L)

        coVerify {
            contradictionDao.insert(
                match { it.olderBeliefId == "old" && it.newerBeliefId == "new" && it.status == "RESOLVED" },
            )
        }
    }
}
