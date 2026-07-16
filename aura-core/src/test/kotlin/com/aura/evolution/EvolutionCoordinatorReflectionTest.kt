package com.aura.evolution

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals

class EvolutionCoordinatorReflectionTest {

    @Test
    fun `reflectAndPromote creates proposal when model approves high confidence candidate`() = runTest {
        val reflection = mockk<EvolutionReflectionExecutor>()
        val proposalStore = mockk<EvolutionProposalStore>(relaxed = true)
        val candidateDao = mockk<EvolutionCandidateDao>(relaxed = true)
        val settingsDao = mockk<EvolutionSettingsDao>(relaxed = true)
        val metrics = mockk<EvolutionMetricsRecorder>(relaxed = true)
        val detectors = mockk<EvolutionCandidateDetectors>(relaxed = true)

        coEvery { settingsDao.all() } returns listOf(EvolutionSettingsEntity(domain = "SKILL", reflectionEnabled = true))
        coEvery { detectors.runAll() } returns listOf(
            EvolutionCandidateEntity(
                id = "c1",
                domain = "SKILL",
                action = EvolutionAction.PATCH_SKILL.name,
                targetId = "skill-1",
                score = 0.85f,
                rationale = "skill failed 5 times",
                status = CandidateStatus.PENDING.name,
            )
        )
        coEvery { reflection.reflect(any(), any()) } returns EvolutionReflectionExecutor.Result.Ok("approve: true\nreason: genuine failure pattern")

        val coordinator = EvolutionCoordinator(detectors, metrics, reflection, proposalStore, candidateDao, settingsDao)
        val result = coordinator.runAll()

        assertEquals(1, result.candidateCount)
        assertEquals(1, result.promotedCount)
        coVerify { candidateDao.setStatus("c1", CandidateStatus.PROMOTED.name, any(), any()) }
        coVerify { proposalStore.fromCandidate(any()) }
    }

    @Test
    fun `reflectAndPromote rejects candidate when model disapproves`() = runTest {
        val reflection = mockk<EvolutionReflectionExecutor>()
        val proposalStore = mockk<EvolutionProposalStore>(relaxed = true)
        val candidateDao = mockk<EvolutionCandidateDao>(relaxed = true)
        val settingsDao = mockk<EvolutionSettingsDao>(relaxed = true)
        val metrics = mockk<EvolutionMetricsRecorder>(relaxed = true)
        val detectors = mockk<EvolutionCandidateDetectors>(relaxed = true)

        coEvery { settingsDao.all() } returns listOf(EvolutionSettingsEntity(domain = "SKILL", reflectionEnabled = true))
        coEvery { detectors.runAll() } returns listOf(
            EvolutionCandidateEntity(
                id = "c2",
                domain = "SKILL",
                action = EvolutionAction.PATCH_SKILL.name,
                targetId = "skill-2",
                score = 0.85f,
                rationale = "skill failed 5 times",
                status = CandidateStatus.PENDING.name,
            )
        )
        coEvery { reflection.reflect(any(), any()) } returns EvolutionReflectionExecutor.Result.Ok("approve: false\nreason: not enough evidence")

        val coordinator = EvolutionCoordinator(detectors, metrics, reflection, proposalStore, candidateDao, settingsDao)
        val result = coordinator.runAll()

        assertEquals(0, result.promotedCount)
        coVerify { candidateDao.setStatus("c2", CandidateStatus.REJECTED.name, any(), any()) }
        coVerify(exactly = 0) { proposalStore.fromCandidate(any()) }
    }
}
