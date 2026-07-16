package com.aura.evolution

import com.aura.memory.MemoryEntity
import com.aura.memory.MemoryStore
import com.aura.proactive.ProactiveEventDao
import com.aura.skills.Skill
import com.aura.skills.SkillsStore
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EvolutionRollbackManagerTest {

    @Test
    fun `rollback create skill removes the skill`() = runTest {
        val proposal = appliedProposal(action = EvolutionAction.CREATE_SKILL, targetId = "s1")
        val dao = mockk<EvolutionProposalDao>(relaxed = true)
        val skillsStore = mockk<SkillsStore>(relaxed = true)
        val metrics = mockk<EvolutionMetrics>(relaxed = true)
        coEvery { dao.getById("p1") } returns proposal
        coEvery { dao.open() } returns emptyList()
        coEvery { skillsStore.remove("s1") } just Runs
        val manager = EvolutionRollbackManager(dao, mockk(relaxed = true), metrics, skillsStore, null, null)
        val result = manager.rollback("p1")
        assertTrue(result is EvolutionRollbackManager.RollbackResult.Ok)
        coVerify { skillsStore.remove("s1") }
    }

    @Test
    fun `rollback patch skill restores previous skill`() = runTest {
        val original = Skill(id = "s1", name = "Original", description = "", body = "")
        val proposal = appliedProposal(
            action = EvolutionAction.PATCH_SKILL,
            targetId = "s1",
            rollbackSnapshot = Json.encodeToString(Skill.serializer(), original),
        )
        val dao = mockk<EvolutionProposalDao>(relaxed = true)
        val skillsStore = mockk<SkillsStore>(relaxed = true)
        val metrics = mockk<EvolutionMetrics>(relaxed = true)
        coEvery { dao.getById("p1") } returns proposal
        coEvery { dao.open() } returns emptyList()
        coEvery { skillsStore.update(any()) } just Runs
        val manager = EvolutionRollbackManager(dao, mockk(relaxed = true), metrics, skillsStore, null, null)
        val result = manager.rollback("p1")
        assertTrue(result is EvolutionRollbackManager.RollbackResult.Ok)
        val captured = slot<Skill>()
        coVerify { skillsStore.update(capture(captured)) }
        assertEquals("Original", captured.captured.name)
    }

    @Test
    fun `rollback retire skill re-adds skill`() = runTest {
        val original = Skill(id = "s1", name = "Original", description = "", body = "")
        val proposal = appliedProposal(
            action = EvolutionAction.RETIRE_SKILL,
            targetId = "s1",
            rollbackSnapshot = Json.encodeToString(Skill.serializer(), original),
        )
        val dao = mockk<EvolutionProposalDao>(relaxed = true)
        val skillsStore = mockk<SkillsStore>(relaxed = true)
        val metrics = mockk<EvolutionMetrics>(relaxed = true)
        coEvery { dao.getById("p1") } returns proposal
        coEvery { dao.open() } returns emptyList()
        coEvery { skillsStore.add(any()) } just Runs
        val manager = EvolutionRollbackManager(dao, mockk(relaxed = true), metrics, skillsStore, null, null)
        val result = manager.rollback("p1")
        assertTrue(result is EvolutionRollbackManager.RollbackResult.Ok)
        coVerify { skillsStore.add(any()) }
    }

    @Test
    fun `rollback new proactive rule deletes events by correlation tag`() = runTest {
        val proposal = appliedProposal(action = EvolutionAction.NEW_PROACTIVE_RULE, targetId = "")
        val dao = mockk<EvolutionProposalDao>(relaxed = true)
        val proactiveDao = mockk<ProactiveEventDao>(relaxed = true)
        val metrics = mockk<EvolutionMetrics>(relaxed = true)
        coEvery { dao.getById("p1") } returns proposal
        coEvery { dao.open() } returns emptyList()
        coEvery { proactiveDao.deleteByCorrelationTag("evolution:p1") } returns 1
        val manager = EvolutionRollbackManager(dao, mockk(relaxed = true), metrics, null, null, proactiveDao)
        val result = manager.rollback("p1")
        assertTrue(result is EvolutionRollbackManager.RollbackResult.Ok)
        coVerify { proactiveDao.deleteByCorrelationTag("evolution:p1") }
    }

    private fun appliedProposal(
        action: EvolutionAction,
        targetId: kotlin.String,
        rollbackSnapshot: kotlin.String = "{}",
    ): EvolutionProposalEntity = EvolutionProposalEntity(
        id = "p1",
        domain = EvolutionDomain.SKILL.name,
        action = action.name,
        targetId = targetId,
        title = "Test",
        summary = "",
        confidence = 0.8f,
        status = ProposalStatus.APPLIED.name,
        patchJson = "{}",
        rollbackSnapshotJson = rollbackSnapshot,
    )
}
