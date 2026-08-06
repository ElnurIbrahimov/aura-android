package com.aura.evolution

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals

/**
 * Coordinator promotion flow with the patch author (D1): one LLM call per
 * candidate returns {decision, reason, patch}; approvals become proposals
 * that carry the REAL authored patch, rejections resolve the candidate.
 */
class EvolutionCoordinatorReflectionTest {

    private fun candidate(id: String = "c1", targetId: String = "skill-1") = EvolutionCandidateEntity(
        id = id,
        domain = "SKILL",
        action = EvolutionAction.PATCH_SKILL.name,
        targetId = targetId,
        score = 0.85f,
        rationale = "skill failed 5 times",
        status = CandidateStatus.PENDING.name,
    )

    @Test
    fun `authored approval creates proposal carrying the authored patch`() = runTest {
        val author = mockk<EvolutionPatchAuthor>()
        val proposalStore = mockk<EvolutionProposalStore>(relaxed = true)
        val candidateDao = mockk<EvolutionCandidateDao>(relaxed = true)
        val settingsDao = mockk<EvolutionSettingsDao>(relaxed = true)
        val metrics = mockk<EvolutionMetricsRecorder>(relaxed = true)
        val detectors = mockk<EvolutionCandidateDetectors>(relaxed = true)

        coEvery { settingsDao.all() } returns listOf(EvolutionSettingsEntity(domain = "SKILL", reflectionEnabled = true))
        coEvery { detectors.runAll() } returns listOf(candidate())
        val patchJson = """{"description":null,"body":"improved body"}"""
        coEvery { author.author(any()) } returns
            EvolutionPatchAuthor.Result.Approved("genuine failure pattern", patchJson)

        val coordinator = EvolutionCoordinator(
            detectors, metrics, author, proposalStore, candidateDao, settingsDao, EvolutionSafetyGuard(),
        )
        val result = coordinator.runAll()

        assertEquals(1, result.candidateCount)
        assertEquals(1, result.promotedCount)
        // The candidate handed to fromCandidate carries the AUTHORED patch,
        // not the detector's empty argsJson.
        coVerify {
            proposalStore.fromCandidate(match { it.id == "c1" && it.argsJson == patchJson })
        }
    }

    @Test
    fun `authored rejection marks candidate rejected with model reason`() = runTest {
        val author = mockk<EvolutionPatchAuthor>()
        val proposalStore = mockk<EvolutionProposalStore>(relaxed = true)
        val candidateDao = mockk<EvolutionCandidateDao>(relaxed = true)
        val settingsDao = mockk<EvolutionSettingsDao>(relaxed = true)
        val metrics = mockk<EvolutionMetricsRecorder>(relaxed = true)
        val detectors = mockk<EvolutionCandidateDetectors>(relaxed = true)

        coEvery { settingsDao.all() } returns listOf(EvolutionSettingsEntity(domain = "SKILL", reflectionEnabled = true))
        coEvery { detectors.runAll() } returns listOf(candidate(id = "c2", targetId = "skill-2"))
        coEvery { author.author(any()) } returns EvolutionPatchAuthor.Result.Rejected("not enough evidence")

        val coordinator = EvolutionCoordinator(
            detectors, metrics, author, proposalStore, candidateDao, settingsDao, EvolutionSafetyGuard(),
        )
        val result = coordinator.runAll()

        assertEquals(0, result.promotedCount)
        coVerify { candidateDao.setStatus("c2", CandidateStatus.REJECTED.name, "model: not enough evidence", any()) }
        coVerify(exactly = 0) { proposalStore.fromCandidate(any()) }
    }

    @Test
    fun `transport error keeps candidate pending for retry`() = runTest {
        val author = mockk<EvolutionPatchAuthor>()
        val proposalStore = mockk<EvolutionProposalStore>(relaxed = true)
        val candidateDao = mockk<EvolutionCandidateDao>(relaxed = true)
        val settingsDao = mockk<EvolutionSettingsDao>(relaxed = true)
        val metrics = mockk<EvolutionMetricsRecorder>(relaxed = true)
        val detectors = mockk<EvolutionCandidateDetectors>(relaxed = true)

        coEvery { settingsDao.all() } returns listOf(EvolutionSettingsEntity(domain = "SKILL", reflectionEnabled = true))
        coEvery { detectors.runAll() } returns listOf(candidate(id = "c3"))
        coEvery { author.author(any()) } returns EvolutionPatchAuthor.Result.Error("timeout", "Reflection timed out")

        val coordinator = EvolutionCoordinator(
            detectors, metrics, author, proposalStore, candidateDao, settingsDao, EvolutionSafetyGuard(),
        )
        val result = coordinator.runAll()

        assertEquals(0, result.promotedCount)
        coVerify { candidateDao.setStatus("c3", CandidateStatus.PENDING.name, "author_error: timeout", any()) }
        coVerify(exactly = 0) { proposalStore.fromCandidate(any()) }
    }

    @Test
    fun `reflection disabled domain is never authored`() = runTest {
        val author = mockk<EvolutionPatchAuthor>(relaxed = true)
        val proposalStore = mockk<EvolutionProposalStore>(relaxed = true)
        val candidateDao = mockk<EvolutionCandidateDao>(relaxed = true)
        val settingsDao = mockk<EvolutionSettingsDao>(relaxed = true)
        val metrics = mockk<EvolutionMetricsRecorder>(relaxed = true)
        val detectors = mockk<EvolutionCandidateDetectors>(relaxed = true)

        coEvery { settingsDao.all() } returns listOf(EvolutionSettingsEntity(domain = "SKILL", reflectionEnabled = false))
        coEvery { detectors.runAll() } returns listOf(candidate())

        val coordinator = EvolutionCoordinator(
            detectors, metrics, author, proposalStore, candidateDao, settingsDao, EvolutionSafetyGuard(),
        )
        coordinator.runAll()

        coVerify(exactly = 0) { author.author(any()) }
    }
}
