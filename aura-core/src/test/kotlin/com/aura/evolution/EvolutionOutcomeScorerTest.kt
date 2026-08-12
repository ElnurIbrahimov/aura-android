package com.aura.evolution

import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class EvolutionOutcomeScorerTest {

    private val now = 1_700_000_000_000L
    private val day = 24L * 60 * 60 * 1000

    private fun evidence(kind: String, sourceId: String, at: Long) = EvolutionEvidenceEntity(
        id = java.util.UUID.randomUUID().toString(),
        domain = EvolutionDomain.SKILL.name,
        kind = kind,
        sourceEntityId = sourceId,
        createdAt = at,
    )

    private fun proposal(
        action: String = EvolutionAction.PATCH_SKILL.name,
        status: String = ProposalStatus.APPLIED.name,
        resolvedAt: Long? = now - 8 * day,
        patchJson: String = """{"body":"x"}""",
        rollbackSnapshotJson: String = "{}",
    ) = EvolutionProposalEntity(
        id = "p1",
        domain = EvolutionDomain.SKILL.name,
        action = action,
        targetId = "s1",
        status = status,
        resolvedAt = resolvedAt,
        patchJson = patchJson,
        rollbackSnapshotJson = rollbackSnapshotJson,
    )

    private fun consolidationSnapshot(vararg sourceIds: String) = EvolutionPatchJson.json
        .encodeToString(
            ConsolidateMemoriesSnapshot.serializer(),
            ConsolidateMemoriesSnapshot(
                consolidatedMemoryId = "merged",
                sources = sourceIds.map {
                    com.aura.memory.MemoryEntity(id = it, content = it, source = "user", category = "fact")
                },
            ),
        )

    private fun memoryRecall(sourceId: String, at: Long) = EvolutionEvidenceEntity(
        id = java.util.UUID.randomUUID().toString(),
        domain = EvolutionDomain.MEMORY.name,
        kind = "memory_recalled",
        sourceEntityId = sourceId,
        createdAt = at,
    )

    @Test
    fun `too-early applied proposal is not scored`() = runTest {
        val scorer = EvolutionOutcomeScorer(mockk(relaxed = true))
        assertNull(scorer.score(proposal(resolvedAt = now - 3 * day), now))
    }

    @Test
    fun `rolled back proposal scores 0_1 immediately`() = runTest {
        val scorer = EvolutionOutcomeScorer(mockk(relaxed = true))
        val outcome = scorer.score(
            proposal(status = ProposalStatus.ROLLED_BACK.name, resolvedAt = now - 1 * day),
            now,
        )
        assertEquals(0.1f, outcome?.score)
        assertEquals("rolled_back", outcome?.signal)
    }

    @Test
    fun `patch skill with fewer failures after apply scores 0_9`() = runTest {
        val appliedAt = now - 8 * day
        val dao = mockk<EvolutionEvidenceDao>(relaxed = true)
        coEvery { dao.byKind(EvolutionDomain.SKILL.name, "skill_failed", any()) } returns listOf(
            // 4 failures in the 14 days before apply, none after.
            evidence("skill_failed", "s1", appliedAt - 2 * day),
            evidence("skill_failed", "s1", appliedAt - 3 * day),
            evidence("skill_failed", "s1", appliedAt - 5 * day),
            evidence("skill_failed", "s1", appliedAt - 10 * day),
        )
        val outcome = EvolutionOutcomeScorer(dao).score(proposal(), now)
        assertEquals(0.9f, outcome?.score)
        assertEquals("failure_rate_improved", outcome?.signal)
    }

    @Test
    fun `patch skill with more failures after apply scores 0_3`() = runTest {
        val appliedAt = now - 8 * day
        val dao = mockk<EvolutionEvidenceDao>(relaxed = true)
        coEvery { dao.byKind(EvolutionDomain.SKILL.name, "skill_failed", any()) } returns listOf(
            // 1 failure before, 6 after → rate got worse.
            evidence("skill_failed", "s1", appliedAt - 2 * day),
            evidence("skill_failed", "s1", appliedAt + 1 * day),
            evidence("skill_failed", "s1", appliedAt + 2 * day),
            evidence("skill_failed", "s1", appliedAt + 3 * day),
            evidence("skill_failed", "s1", appliedAt + 4 * day),
            evidence("skill_failed", "s1", appliedAt + 5 * day),
            evidence("skill_failed", "s1", appliedAt + 6 * day),
        )
        val outcome = EvolutionOutcomeScorer(dao).score(proposal(), now)
        assertEquals(0.3f, outcome?.score)
        assertEquals("failure_rate_worse", outcome?.signal)
    }

    @Test
    fun `patch skill with no evidence at all scores neutral 0_7`() = runTest {
        val dao = mockk<EvolutionEvidenceDao>(relaxed = true)
        coEvery { dao.byKind(any(), any(), any()) } returns emptyList()
        val outcome = EvolutionOutcomeScorer(dao).score(proposal(), now)
        assertEquals(0.7f, outcome?.score)
        assertEquals("failure_rate_unchanged", outcome?.signal)
    }

    @Test
    fun `legacy empty-patch proposal is scored by evidence only`() = runTest {
        // The old system created proposals with patchJson "{}" — the scorer
        // never reads the patch, so these must still score fine.
        val dao = mockk<EvolutionEvidenceDao>(relaxed = true)
        coEvery { dao.byKind(any(), any(), any()) } returns emptyList()
        val outcome = EvolutionOutcomeScorer(dao).score(proposal(patchJson = "{}"), now)
        assertEquals(0.7f, outcome?.score)
    }

    @Test
    fun `legacy removed action scores neutral 0_5`() = runTest {
        val dao = mockk<EvolutionEvidenceDao>(relaxed = true)
        val outcome = EvolutionOutcomeScorer(dao).score(proposal(action = "FORGET_MEMORY"), now)
        assertEquals(0.5f, outcome?.score)
    }

    @Test
    fun `retired skill still invoked after apply scores 0_3`() = runTest {
        val appliedAt = now - 8 * day
        val dao = mockk<EvolutionEvidenceDao>(relaxed = true)
        coEvery { dao.byKind(EvolutionDomain.SKILL.name, "skill_invoked", any()) } returns listOf(
            evidence("skill_invoked", "s1", appliedAt + 2 * day),
        )
        val outcome = EvolutionOutcomeScorer(dao)
            .score(proposal(action = EvolutionAction.RETIRE_SKILL.name), now)
        assertEquals(0.3f, outcome?.score)
        assertEquals("retired_skill_still_invoked", outcome?.signal)
    }

    @Test
    fun `promote to hand with fewer manual invocations after scores 0_9`() = runTest {
        val appliedAt = now - 8 * day
        val dao = mockk<EvolutionEvidenceDao>(relaxed = true)
        coEvery { dao.byKind(EvolutionDomain.SKILL.name, "skill_invoked", any()) } returns listOf(
            evidence("skill_invoked", "s1", appliedAt - 1 * day),
            evidence("skill_invoked", "s1", appliedAt - 2 * day),
            evidence("skill_invoked", "s1", appliedAt - 3 * day),
        )
        val outcome = EvolutionOutcomeScorer(dao)
            .score(proposal(action = EvolutionAction.PROMOTE_TO_HAND.name), now)
        assertEquals(0.9f, outcome?.score)
        assertEquals("invocations_shifted_to_hand", outcome?.signal)
    }

    @Test
    fun `consolidation is judged on whether the merged memory still gets recalled`() = runTest {
        // The old scorer returned a flat 0.7 named "consolidation_survived_Nd"
        // and read no evidence at all, so a merge that destroyed a fact the
        // user relied on daily scored the same as one that worked.
        val applied = now - 8 * day
        val dao = mockk<EvolutionEvidenceDao>(relaxed = true)
        coEvery { dao.forSource("m1", any()) } returns listOf(
            memoryRecall("m1", applied - day),
            memoryRecall("m1", applied - 2 * day),
        )
        coEvery { dao.forSource("m2", any()) } returns emptyList()
        coEvery { dao.forSource("merged", any()) } returns listOf(
            memoryRecall("merged", applied + day),
            memoryRecall("merged", applied + 2 * day),
        )
        val scorer = EvolutionOutcomeScorer(dao)
        val carried = scorer.score(
            proposal(
                action = EvolutionAction.CONSOLIDATE_MEMORIES.name,
                rollbackSnapshotJson = consolidationSnapshot("m1", "m2"),
            ),
            now,
        )
        assertEquals(0.9f, carried?.score)
        assertEquals("consolidated_memory_carries_recall", carried?.signal)

        // Same merge, but the fact stopped being found afterwards.
        coEvery { dao.forSource("merged", any()) } returns emptyList()
        val lost = scorer.score(
            proposal(
                action = EvolutionAction.CONSOLIDATE_MEMORIES.name,
                rollbackSnapshotJson = consolidationSnapshot("m1", "m2"),
            ),
            now,
        )
        assertEquals(0.2f, lost?.score)
        assertEquals("consolidated_memory_never_recalled", lost?.signal)
    }

    @Test
    fun `consolidation with nothing to lose is neutral, not good`() = runTest {
        val dao = mockk<EvolutionEvidenceDao>(relaxed = true)
        coEvery { dao.forSource(any(), any()) } returns emptyList()
        val outcome = EvolutionOutcomeScorer(dao).score(
            proposal(
                action = EvolutionAction.CONSOLIDATE_MEMORIES.name,
                rollbackSnapshotJson = consolidationSnapshot("m1", "m2"),
            ),
            now,
        )
        assertEquals(0.5f, outcome?.score)
        assertEquals("consolidation_no_recall_baseline", outcome?.signal)
    }

    @Test
    fun `equal event counts either side of apply do not read as a regression`() = runTest {
        // The before-rate divided by 14 while the after-rate divided by the
        // elapsed days, so at day 7 — when proposals are first scored — an
        // unchanged failure count read twice as high after as before, and
        // every patch that changed nothing scored "worse".
        val applied = now - 7 * day
        val dao = mockk<EvolutionEvidenceDao>(relaxed = true)
        coEvery { dao.byKind(EvolutionDomain.SKILL.name, "skill_failed", any()) } returns listOf(
            evidence("skill_failed", "s1", applied - 2 * day),
            evidence("skill_failed", "s1", applied - day),
            evidence("skill_failed", "s1", applied + day),
            evidence("skill_failed", "s1", applied + 2 * day),
        )
        val outcome = EvolutionOutcomeScorer(dao).score(proposal(resolvedAt = applied), now)
        assertEquals("failure_rate_unchanged", outcome?.signal)
    }

    @Test
    fun `proposal without resolvedAt is not scored`() = runTest {
        val scorer = EvolutionOutcomeScorer(mockk(relaxed = true))
        assertNull(scorer.score(proposal(resolvedAt = null), now))
        assertNull(scorer.score(proposal(resolvedAt = 0), now))
    }
}
