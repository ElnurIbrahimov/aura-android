package com.aura.evolution

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * D4: the safety guard is enforced in the coordinator's auto-apply path.
 * SKILL proposals can NEVER auto-apply — even when the persisted
 * autoApplyApproved flag is (stale) true for the SKILL domain. MEMORY
 * proposals with the flag on do auto-apply.
 */
class EvolutionCoordinatorAutoApplyGuardTest {

    private fun coordinatorWith(
        candidate: EvolutionCandidateEntity,
        settings: EvolutionSettingsEntity,
        author: EvolutionPatchAuthor,
        applySaga: EvolutionApplySaga,
        candidateDao: EvolutionCandidateDao = mockk(relaxed = true),
        proposalStore: EvolutionProposalStore = mockk(relaxed = true),
    ): EvolutionCoordinator {
        val detectors = mockk<EvolutionCandidateDetectors>(relaxed = true)
        val settingsDao = mockk<EvolutionSettingsDao>(relaxed = true)
        coEvery { detectors.runAll() } returns listOf(candidate)
        coEvery { settingsDao.all() } returns listOf(settings)
        return EvolutionCoordinator(
            detectors,
            mockk<EvolutionMetricsRecorder>(relaxed = true),
            author,
            proposalStore,
            candidateDao,
            settingsDao,
            EvolutionSafetyGuard(),
            outcomeScorer = null,
            applySaga = applySaga,
        )
    }

    @Test
    fun `SKILL proposal never auto-applies even with stale autoApplyApproved flag`() = runTest {
        val candidate = EvolutionCandidateEntity(
            id = "c1",
            domain = EvolutionDomain.SKILL.name,
            action = EvolutionAction.PATCH_SKILL.name,
            targetId = "skill-1",
            score = 0.9f,
            status = CandidateStatus.PENDING.name,
        )
        // Stale DB row: SKILL domain flagged for auto-apply (e.g. old build
        // or imported backup wrote it). The guard must still win.
        val settings = EvolutionSettingsEntity(
            domain = EvolutionDomain.SKILL.name,
            reflectionEnabled = true,
            autoApplyApproved = true,
        )
        val author = mockk<EvolutionPatchAuthor>()
        coEvery { author.author(any()) } returns
            EvolutionPatchAuthor.Result.Approved("ok", """{"body":"new body"}""")
        val applySaga = mockk<EvolutionApplySaga>(relaxed = true)
        val proposalStore = mockk<EvolutionProposalStore>(relaxed = true)
        coEvery { proposalStore.fromCandidate(any()) } returns EvolutionProposalEntity(
            id = "p1",
            domain = EvolutionDomain.SKILL.name,
            action = EvolutionAction.PATCH_SKILL.name,
            targetId = "skill-1",
        )

        val coordinator = coordinatorWith(candidate, settings, author, applySaga, proposalStore = proposalStore)
        coordinator.runAll()

        // Proposal is created for the inbox…
        coVerify(exactly = 1) { proposalStore.fromCandidate(any()) }
        // …but the saga is NEVER invoked for a SKILL domain candidate.
        coVerify(exactly = 0) { applySaga.apply(any()) }
    }

    @Test
    fun `MEMORY proposal auto-applies when domain opted in`() = runTest {
        val candidate = EvolutionCandidateEntity(
            id = "c2",
            domain = EvolutionDomain.MEMORY.name,
            action = EvolutionAction.CONSOLIDATE_MEMORIES.name,
            targetId = "m1",
            score = 0.9f,
            status = CandidateStatus.PENDING.name,
        )
        val settings = EvolutionSettingsEntity(
            domain = EvolutionDomain.MEMORY.name,
            reflectionEnabled = true,
            autoApplyApproved = true,
        )
        val author = mockk<EvolutionPatchAuthor>()
        coEvery { author.author(any()) } returns EvolutionPatchAuthor.Result.Approved(
            "ok",
            """{"memoryIds":["m1","m2"],"consolidatedContent":"merged"}""",
        )
        val proposal = EvolutionProposalEntity(
            id = "p2",
            domain = EvolutionDomain.MEMORY.name,
            action = EvolutionAction.CONSOLIDATE_MEMORIES.name,
            targetId = "m1",
        )
        val proposalStore = mockk<EvolutionProposalStore>(relaxed = true)
        coEvery { proposalStore.fromCandidate(any()) } returns proposal
        val applySaga = mockk<EvolutionApplySaga>(relaxed = true)
        coEvery { applySaga.apply(proposal) } returns EvolutionApplySaga.ApplyResult.Ok("p2", "done")
        val candidateDao = mockk<EvolutionCandidateDao>(relaxed = true)

        val coordinator = coordinatorWith(
            candidate, settings, author, applySaga,
            candidateDao = candidateDao, proposalStore = proposalStore,
        )
        coordinator.runAll()

        coVerify(exactly = 1) { applySaga.apply(proposal) }
        coVerify { candidateDao.setStatus("c2", CandidateStatus.AUTO_APPLIED.name, any(), any()) }
    }

    @Test
    fun `MEMORY proposal does not auto-apply without the opt-in flag`() = runTest {
        val candidate = EvolutionCandidateEntity(
            id = "c3",
            domain = EvolutionDomain.MEMORY.name,
            action = EvolutionAction.CONSOLIDATE_MEMORIES.name,
            targetId = "m1",
            score = 0.9f,
            status = CandidateStatus.PENDING.name,
        )
        val settings = EvolutionSettingsEntity(
            domain = EvolutionDomain.MEMORY.name,
            reflectionEnabled = true,
            autoApplyApproved = false,
        )
        val author = mockk<EvolutionPatchAuthor>()
        coEvery { author.author(any()) } returns EvolutionPatchAuthor.Result.Approved(
            "ok",
            """{"memoryIds":["m1","m2"],"consolidatedContent":"merged"}""",
        )
        val applySaga = mockk<EvolutionApplySaga>(relaxed = true)

        val coordinator = coordinatorWith(candidate, settings, author, applySaga)
        coordinator.runAll()

        coVerify(exactly = 0) { applySaga.apply(any()) }
    }
}
