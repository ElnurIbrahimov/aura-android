package com.aura.evolution

import com.aura.hands.HandRepository
import com.aura.memory.MemoryEntity
import com.aura.memory.MemoryStore
import com.aura.skills.Skill
import com.aura.skills.SkillsStore
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EvolutionRollbackManagerTest {

    private val json = EvolutionPatchJson.json

    @Test
    fun `rollback patch skill restores previous skill`() = runTest {
        val original = Skill(id = "s1", name = "Original", description = "", body = "orig body")
        val proposal = appliedProposal(
            action = EvolutionAction.PATCH_SKILL,
            targetId = "s1",
            rollbackSnapshot = json.encodeToString(Skill.serializer(), original),
        )
        val dao = mockk<EvolutionProposalDao>(relaxed = true)
        val skillsStore = mockk<SkillsStore>(relaxed = true)
        coEvery { dao.getById("p1") } returns proposal
        coEvery { dao.open() } returns emptyList()
        coEvery { skillsStore.update(any()) } just Runs
        val manager = EvolutionRollbackManager(dao, mockk(relaxed = true), EvolutionMetrics(), skillsStore)

        val result = manager.rollback("p1")

        assertTrue(result is EvolutionRollbackManager.RollbackResult.Ok)
        val captured = slot<Skill>()
        coVerify { skillsStore.update(capture(captured)) }
        assertEquals("Original", captured.captured.name)
        assertEquals("orig body", captured.captured.body)
    }

    @Test
    fun `rollback retire skill re-adds skill`() = runTest {
        val original = Skill(id = "s1", name = "Original", description = "", body = "b")
        val proposal = appliedProposal(
            action = EvolutionAction.RETIRE_SKILL,
            targetId = "s1",
            rollbackSnapshot = json.encodeToString(Skill.serializer(), original),
        )
        val dao = mockk<EvolutionProposalDao>(relaxed = true)
        val skillsStore = mockk<SkillsStore>(relaxed = true)
        coEvery { dao.getById("p1") } returns proposal
        coEvery { dao.open() } returns emptyList()
        coEvery { skillsStore.add(any()) } just Runs
        val manager = EvolutionRollbackManager(dao, mockk(relaxed = true), EvolutionMetrics(), skillsStore)

        val result = manager.rollback("p1")

        assertTrue(result is EvolutionRollbackManager.RollbackResult.Ok)
        coVerify { skillsStore.add(any()) }
    }

    @Test
    fun `rollback promote to hand deletes exactly the recorded hand id`() = runTest {
        val proposal = appliedProposal(
            action = EvolutionAction.PROMOTE_TO_HAND,
            targetId = "s1",
            rollbackSnapshot = json.encodeToString(
                PromoteToHandSnapshot.serializer(),
                PromoteToHandSnapshot(handId = "hand-42", handName = "daily_digest"),
            ),
        )
        val dao = mockk<EvolutionProposalDao>(relaxed = true)
        val handRepository = mockk<HandRepository>(relaxed = true)
        coEvery { dao.getById("p1") } returns proposal
        coEvery { dao.open() } returns emptyList()
        val manager = EvolutionRollbackManager(dao, mockk(relaxed = true), EvolutionMetrics(), null, null, handRepository)

        val result = manager.rollback("p1")

        assertTrue(result is EvolutionRollbackManager.RollbackResult.Ok)
        coVerify { handRepository.deleteById("hand-42") }
    }

    @Test
    fun `rollback consolidate memories forgets consolidated and un-retires all sources`() = runTest {
        val m1 = MemoryEntity(id = "m1", content = "a", source = "user", category = "fact")
        val m2 = MemoryEntity(id = "m2", content = "b", source = "user", category = "fact")
        val proposal = appliedProposal(
            action = EvolutionAction.CONSOLIDATE_MEMORIES,
            targetId = "m1",
            domain = EvolutionDomain.MEMORY,
            rollbackSnapshot = json.encodeToString(
                ConsolidateMemoriesSnapshot.serializer(),
                ConsolidateMemoriesSnapshot(consolidatedMemoryId = "c1", sources = listOf(m1, m2)),
            ),
        )
        val dao = mockk<EvolutionProposalDao>(relaxed = true)
        val memoryStore = mockk<MemoryStore>(relaxed = true)
        coEvery { dao.getById("p1") } returns proposal
        coEvery { dao.open() } returns emptyList()
        coEvery { memoryStore.forget(any()) } just Runs
        val manager = EvolutionRollbackManager(dao, mockk(relaxed = true), EvolutionMetrics(), null, memoryStore)

        val result = manager.rollback("p1")

        assertTrue(result is EvolutionRollbackManager.RollbackResult.Ok)
        coVerify { memoryStore.forget("c1") }
        // Un-retired, not re-inserted from the snapshot. The rows were never
        // deleted, so writing the snapshot back over them would discard
        // anything that happened to them between apply and rollback.
        coVerify { memoryStore.unretire("m1") }
        coVerify { memoryStore.unretire("m2") }
        coVerify(exactly = 0) { memoryStore.restore(any(), any()) }
    }

    @Test
    fun `rollback without snapshot returns error`() = runTest {
        val proposal = appliedProposal(action = EvolutionAction.PATCH_SKILL, targetId = "s1", rollbackSnapshot = "{}")
        val dao = mockk<EvolutionProposalDao>(relaxed = true)
        coEvery { dao.getById("p1") } returns proposal
        coEvery { dao.open() } returns emptyList()
        val manager = EvolutionRollbackManager(dao, mockk(relaxed = true), EvolutionMetrics(), mockk(relaxed = true))

        val result = manager.rollback("p1")

        assertTrue(result is EvolutionRollbackManager.RollbackResult.Error)
        assertTrue((result as EvolutionRollbackManager.RollbackResult.Error).message.contains("no rollback snapshot"))
    }

    private fun appliedProposal(
        action: EvolutionAction,
        targetId: kotlin.String,
        domain: EvolutionDomain = EvolutionDomain.SKILL,
        rollbackSnapshot: kotlin.String = "{}",
    ): EvolutionProposalEntity = EvolutionProposalEntity(
        id = "p1",
        domain = domain.name,
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
