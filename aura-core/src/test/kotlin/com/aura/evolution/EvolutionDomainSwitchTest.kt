package com.aura.evolution

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals

/**
 * The per-domain master switch in the Evolution inbox.
 *
 * `EvolutionSettingsEntity.enabled` is documented as the "Master enable switch for this
 * domain" and rendered as a `Switch` per domain in `EvolutionInboxScreen`. It was written
 * by `setDomainEnabled`, read back to draw the switch in the position the user left it,
 * and consulted by nothing else — the coordinator gated only on `reflectionEnabled` and
 * `autoApplyApproved`. Turning a domain off changed nothing at all: candidates were still
 * authored, LLM calls were still spent, and approved proposals were still applied.
 *
 * Same shape as the mute button that left the microphone running — a control whose only
 * effect is on its own appearance.
 */
class EvolutionDomainSwitchTest {

    private fun candidate() = EvolutionCandidateEntity(
        id = "c1",
        domain = "SKILL",
        action = EvolutionAction.PATCH_SKILL.name,
        targetId = "skill-1",
        score = 0.85f,
        rationale = "skill failed 5 times",
        status = CandidateStatus.PENDING.name,
    )

    private class Fixture {
        val author = mockk<EvolutionPatchAuthor>(relaxed = true)
        val proposalStore = mockk<EvolutionProposalStore>(relaxed = true)
        val candidateDao = mockk<EvolutionCandidateDao>(relaxed = true)
        val settingsDao = mockk<EvolutionSettingsDao>(relaxed = true)
        val metrics = mockk<EvolutionMetricsRecorder>(relaxed = true)
        val detectors = mockk<EvolutionCandidateDetectors>(relaxed = true)
        fun coordinator() = EvolutionCoordinator(
            detectors, metrics, author, proposalStore, candidateDao, settingsDao, EvolutionSafetyGuard(),
        )
    }

    @Test
    fun `a domain switched off authors nothing`() = runTest {
        val f = Fixture()
        coEvery { f.settingsDao.all() } returns listOf(
            EvolutionSettingsEntity(domain = "SKILL", enabled = false, reflectionEnabled = true),
        )
        coEvery { f.detectors.runAll() } returns listOf(candidate())

        val result = f.coordinator().runAll()

        assertEquals(0, result.promotedCount, "a domain the user switched off still promoted a proposal")
        coVerify(exactly = 0) { f.author.author(any()) }
    }

    @Test
    fun `a domain left on still authors`() = runTest {
        // The other half of the guard: switching a domain off must be the thing that
        // stopped it, not a mock that was never going to author anyway.
        val f = Fixture()
        coEvery { f.settingsDao.all() } returns listOf(
            EvolutionSettingsEntity(domain = "SKILL", enabled = true, reflectionEnabled = true),
        )
        coEvery { f.detectors.runAll() } returns listOf(candidate())
        coEvery { f.author.author(any()) } returns
            EvolutionPatchAuthor.Result.Approved("genuine failure pattern", """{"body":"x"}""")

        val result = f.coordinator().runAll()

        assertEquals(1, result.promotedCount)
        coVerify(exactly = 1) { f.author.author(any()) }
    }

    @Test
    fun `a domain with no settings row behaves as enabled`() = runTest {
        // `enabled` defaults to true, and a domain that has never been touched has no row
        // at all. Defaulting a missing row to "off" would silently disable evolution for
        // every domain on a fresh install.
        val f = Fixture()
        coEvery { f.settingsDao.all() } returns emptyList()
        coEvery { f.detectors.runAll() } returns listOf(candidate())
        coEvery { f.author.author(any()) } returns
            EvolutionPatchAuthor.Result.Approved("reason", """{"body":"x"}""")

        f.coordinator().runAll()

        // reflectionEnabled defaults to false, so nothing is authored — but the reason must
        // be that, not the master switch.
        coVerify(exactly = 0) { f.author.author(any()) }
    }
}
