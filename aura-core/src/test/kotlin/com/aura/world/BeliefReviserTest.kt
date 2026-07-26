package com.aura.world

import com.aura.dream.ContradictionDao
import com.aura.dream.ContradictionEntity
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals
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
        coVerify(exactly = 0) { contradictionDao.insert(any()) }
    }

    @Test
    fun `superseding stamps validTo with the timestamp passed in`() = runBlocking {
        val verdict = Verdict.Winner(belief("new"), belief("old"), margin = 0.4f)

        reviser().applyVerdict(verdict, now = 9_999L)

        // BeliefDao.supersede's fourth argument is both `updatedAt` and,
        // per the design spec, `validTo` — the DAO is mocked, so we can only
        // verify the argument value that the query will stamp into both
        // columns, not the row itself (that needs a Room/androidTest).
        coVerify { beliefDao.supersede("old", "superseded", "new", 9_999L) }
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

    @Test
    fun `synthetic summary ids are namespaced by belief id, not empty`() = runBlocking {
        val verdict = Verdict.Winner(belief("new"), belief("old"), margin = 0.4f)
        val captured = slot<ContradictionEntity>()
        coEvery { contradictionDao.insert(capture(captured)) } returns 1L

        reviser().applyVerdict(verdict, now = 5_000L)

        // Regression guard for the OnConflictStrategy.IGNORE collision: a
        // future refactor that reverts to "" for both columns must fail here
        // loudly rather than silently dropping every revision after the first.
        assertEquals("belief:old", captured.captured.olderSummaryId)
        assertEquals("belief:new", captured.captured.newerSummaryId)
    }

    @Test
    fun `two different revisions produce different synthetic summary id keys`() = runBlocking {
        val r = reviser()
        r.applyVerdict(Verdict.Winner(belief("new1"), belief("old1"), margin = 0.4f), now = 1_000L)
        r.applyVerdict(Verdict.Winner(belief("new2"), belief("old2"), margin = 0.4f), now = 2_000L)

        // If both rows shared the same (olderSummaryId, newerSummaryId) key,
        // the unique index would have made the second insert a silent no-op
        // in production (mockk has no such constraint, which is exactly why
        // this bug survived the original test suite).
        coVerify {
            contradictionDao.insert(match { it.olderSummaryId == "belief:old1" && it.newerSummaryId == "belief:new1" })
        }
        coVerify {
            contradictionDao.insert(match { it.olderSummaryId == "belief:old2" && it.newerSummaryId == "belief:new2" })
        }
    }
}
