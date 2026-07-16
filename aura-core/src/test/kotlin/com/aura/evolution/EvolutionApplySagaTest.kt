package com.aura.evolution

import com.aura.memory.MemoryEntity
import com.aura.memory.MemoryStore
import com.aura.memory.MemoryFeedbackDao
import com.aura.proactive.ProactiveEventDao
import com.aura.proactive.ProactiveEventEntity
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

class EvolutionApplySagaTest {

    @Test
    fun `apply retire skill removes skill`() = runTest {
        val skillsStore = mockk<SkillsStore>(relaxed = true)
        val proposalStore = mockk<EvolutionProposalStore>(relaxed = true)
        val saga = EvolutionApplySaga(proposalStore, skillsStore, null, null, null)
        val skill = Skill(id = "s1", name = "Test", description = "", body = "")
        every { skillsStore.findById("s1") } returns skill
        coEvery { skillsStore.remove("s1") } just Runs
        coEvery { proposalStore.markApplied(any(), any()) } just Runs

        val proposal = EvolutionProposalEntity(
            id = "p1",
            domain = EvolutionDomain.SKILL.name,
            action = EvolutionAction.RETIRE_SKILL.name,
            targetId = "s1",
            title = "Retire Test",
            summary = "",
            confidence = 0.8f,
            patchJson = "{}",
        )
        val result = saga.apply(proposal)

        assertTrue(result is EvolutionApplySaga.ApplyResult.Ok)
        coVerify { skillsStore.remove("s1") }
    }

    @Test
    fun `apply forget memory calls memoryStore forget`() = runTest {
        val memoryStore = mockk<MemoryStore>(relaxed = true)
        val proposalStore = mockk<EvolutionProposalStore>(relaxed = true)
        val saga = EvolutionApplySaga(proposalStore, null, null, memoryStore, null)
        coEvery { memoryStore.forget("m1") } just Runs
        coEvery { proposalStore.markApplied(any(), any()) } just Runs

        val proposal = EvolutionProposalEntity(
            id = "p2",
            domain = EvolutionDomain.MEMORY.name,
            action = EvolutionAction.FORGET_MEMORY.name,
            targetId = "m1",
            title = "Forget memory",
            summary = "",
            confidence = 0.8f,
            patchJson = "{}",
        )
        val result = saga.apply(proposal)

        assertTrue(result is EvolutionApplySaga.ApplyResult.Ok)
        coVerify { memoryStore.forget("m1") }
    }

    @Test
    fun `apply patch skill merges non blank fields`() = runTest {
        val skillsStore = mockk<SkillsStore>(relaxed = true)
        val proposalStore = mockk<EvolutionProposalStore>(relaxed = true)
        val saga = EvolutionApplySaga(proposalStore, skillsStore, null, null, null)
        val skill = Skill(id = "s1", name = "Old", description = "old", body = "old body")
        every { skillsStore.findById("s1") } returns skill
        coEvery { skillsStore.update(any()) } just Runs
        coEvery { proposalStore.markApplied(any(), any()) } just Runs
        val patch = Skill(id = "s1", name = "New", description = "new", body = "")

        val proposal = EvolutionProposalEntity(
            id = "p3",
            domain = EvolutionDomain.SKILL.name,
            action = EvolutionAction.PATCH_SKILL.name,
            targetId = "s1",
            title = "Patch",
            summary = "",
            confidence = 0.8f,
            patchJson = Json.encodeToString(Skill.serializer(), patch),
        )
        val result = saga.apply(proposal)

        assertTrue(result is EvolutionApplySaga.ApplyResult.Ok)
        val captured = slot<Skill>()
        coVerify { skillsStore.update(capture(captured)) }
        assertEquals("New", captured.captured.name)
        assertEquals("new", captured.captured.description)
        assertEquals("old body", captured.captured.body)
    }

    @Test
    fun `apply new proactive rule inserts event`() = runTest {
        val proactiveDao = mockk<ProactiveEventDao>(relaxed = true)
        val proposalStore = mockk<EvolutionProposalStore>(relaxed = true)
        val saga = EvolutionApplySaga(proposalStore, null, null, null, proactiveDao)
        coEvery { proactiveDao.insert(any()) } returns 1L
        coEvery { proposalStore.markApplied(any(), any()) } just Runs

        val proposal = EvolutionProposalEntity(
            id = "p4",
            domain = EvolutionDomain.PROACTIVE.name,
            action = EvolutionAction.NEW_PROACTIVE_RULE.name,
            targetId = "",
            title = "New rule",
            summary = "",
            confidence = 0.8f,
            patchJson = """{"title":"Hello","body":"world","eventType":"greeting"}""",
        )
        val result = saga.apply(proposal)

        assertTrue(result is EvolutionApplySaga.ApplyResult.Ok)
        val captured = slot<ProactiveEventEntity>()
        coVerify { proactiveDao.insert(capture(captured)) }
        assertEquals("Hello", captured.captured.title)
    }
}
