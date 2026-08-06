package com.aura.consciousness

import com.aura.agent.StrategyBanditDao
import com.aura.dream.ContradictionDao
import com.aura.kg.KnowledgeGraphDao
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class DriveSignalsTest {

    private fun daos(
        gaps: Int = 7,
        contradictions: Int = 2,
        lowConfidence: Int = 3,
    ): Triple<KnowledgeGraphDao, ContradictionDao, StrategyBanditDao> {
        val kg = mockk<KnowledgeGraphDao>(relaxed = true)
        coEvery { kg.gapNodeCount() } returns gaps
        val contra = mockk<ContradictionDao>(relaxed = true)
        coEvery { contra.unresolvedCount() } returns contradictions
        val bandit = mockk<StrategyBanditDao>(relaxed = true)
        coEvery { bandit.lowConfidenceCount() } returns lowConfidence
        return Triple(kg, contra, bandit)
    }

    @Test
    fun `get returns the DAO counts`() = runTest {
        val (kg, contra, bandit) = daos(gaps = 7, contradictions = 2, lowConfidence = 3)
        val signals = DriveSignals(kg, contra, bandit)
        val snapshot = signals.get()
        assertEquals(7, snapshot.kgGapCount)
        assertEquals(2, snapshot.contradictionCount)
        assertEquals(3, snapshot.lowConfidenceSkillCount)
    }

    @Test
    fun `second call within TTL does not re-query`() = runTest {
        val (kg, contra, bandit) = daos()
        val signals = DriveSignals(kg, contra, bandit)
        signals.get()
        signals.get()
        signals.get()
        coVerify(exactly = 1) { kg.gapNodeCount() }
        coVerify(exactly = 1) { contra.unresolvedCount() }
        coVerify(exactly = 1) { bandit.lowConfidenceCount() }
    }

    @Test
    fun `expired TTL re-queries`() = runTest {
        val (kg, contra, bandit) = daos()
        val signals = DriveSignals(kg, contra, bandit)
        signals.get(ttlMs = 0L) // never valid — forces refresh
        signals.get(ttlMs = 0L)
        coVerify(exactly = 2) { kg.gapNodeCount() }
    }

    @Test
    fun `DAO failure falls back to zero without throwing`() = runTest {
        val kg = mockk<KnowledgeGraphDao>(relaxed = true)
        coEvery { kg.gapNodeCount() } throws RuntimeException("db locked")
        val contra = mockk<ContradictionDao>(relaxed = true)
        coEvery { contra.unresolvedCount() } returns 4
        val bandit = mockk<StrategyBanditDao>(relaxed = true)
        coEvery { bandit.lowConfidenceCount() } throws RuntimeException("no table")

        val signals = DriveSignals(kg, contra, bandit)
        val snapshot = signals.get()
        assertEquals(0, snapshot.kgGapCount)
        assertEquals(4, snapshot.contradictionCount)
        assertEquals(0, snapshot.lowConfidenceSkillCount)
    }

    @Test
    fun `DAO failure after a good snapshot falls back to the previous value`() = runTest {
        val kg = mockk<KnowledgeGraphDao>(relaxed = true)
        coEvery { kg.gapNodeCount() } returns 9 andThenThrows RuntimeException("db locked")
        val (_, contra, bandit) = daos()

        val signals = DriveSignals(kg, contra, bandit)
        assertEquals(9, signals.get(ttlMs = 0L).kgGapCount)
        // Second refresh: kg query throws — keep the last known count.
        assertEquals(9, signals.get(ttlMs = 0L).kgGapCount)
    }
}
