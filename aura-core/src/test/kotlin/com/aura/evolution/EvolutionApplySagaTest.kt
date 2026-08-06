package com.aura.evolution

import com.aura.hands.Hand
import com.aura.hands.HandRepository
import com.aura.memory.MemoryEntity
import com.aura.memory.MemoryStore
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
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EvolutionApplySagaTest {

    private val json = EvolutionPatchJson.json

    @Test
    fun `apply retire skill removes skill and snapshots it first`() = runTest {
        val skillsStore = mockk<SkillsStore>(relaxed = true)
        val proposalStore = mockk<EvolutionProposalStore>(relaxed = true)
        val saga = EvolutionApplySaga(proposalStore, skillsStore)
        val skill = Skill(id = "s1", name = "Test", description = "d", body = "b")
        coEvery { skillsStore.awaitLoaded() } just Runs
        every { skillsStore.findById("s1") } returns skill
        coEvery { skillsStore.remove("s1") } just Runs

        val proposal = proposal(
            action = EvolutionAction.RETIRE_SKILL,
            targetId = "s1",
            patchJson = json.encodeToString(RetireSkillPatch.serializer(), RetireSkillPatch(reason = "unused")),
        )
        val result = saga.apply(proposal)

        assertTrue(result is EvolutionApplySaga.ApplyResult.Ok)
        coVerify { skillsStore.remove("s1") }
        val snapshot = slot<String>()
        coVerify { proposalStore.recordRollbackSnapshot("p1", capture(snapshot)) }
        val decoded = json.decodeFromString<Skill>(snapshot.captured)
        assertEquals("Test", decoded.name)
    }

    @Test
    fun `apply patch skill replaces body and keeps name`() = runTest {
        val skillsStore = mockk<SkillsStore>(relaxed = true)
        val proposalStore = mockk<EvolutionProposalStore>(relaxed = true)
        val saga = EvolutionApplySaga(proposalStore, skillsStore)
        val skill = Skill(id = "s1", name = "Old", description = "old", body = "old body")
        coEvery { skillsStore.awaitLoaded() } just Runs
        every { skillsStore.findById("s1") } returns skill
        coEvery { skillsStore.update(any()) } just Runs

        val patch = SkillPatch(description = "new", body = "new body")
        val proposal = proposal(
            action = EvolutionAction.PATCH_SKILL,
            targetId = "s1",
            patchJson = json.encodeToString(SkillPatch.serializer(), patch),
        )
        val result = saga.apply(proposal)

        assertTrue(result is EvolutionApplySaga.ApplyResult.Ok)
        val captured = slot<Skill>()
        coVerify { skillsStore.update(capture(captured)) }
        assertEquals("Old", captured.captured.name)
        assertEquals("new", captured.captured.description)
        assertEquals("new body", captured.captured.body)
    }

    @Test
    fun `apply patch skill with empty patch fails`() = runTest {
        val skillsStore = mockk<SkillsStore>(relaxed = true)
        val proposalStore = mockk<EvolutionProposalStore>(relaxed = true)
        val saga = EvolutionApplySaga(proposalStore, skillsStore)
        coEvery { skillsStore.awaitLoaded() } just Runs
        every { skillsStore.findById("s1") } returns Skill(id = "s1", name = "T", description = "", body = "b")

        // The legacy defect: detector-born proposals carried "{}". The saga
        // must reject them instead of silently no-oping.
        val result = saga.apply(proposal(EvolutionAction.PATCH_SKILL, "s1", patchJson = "{}"))

        assertTrue(result is EvolutionApplySaga.ApplyResult.Error)
        coVerify(exactly = 0) { skillsStore.update(any()) }
    }

    @Test
    fun `apply promote to hand creates hand with real steps and typed snapshot`() = runTest {
        val skillsStore = mockk<SkillsStore>(relaxed = true)
        val proposalStore = mockk<EvolutionProposalStore>(relaxed = true)
        val handRepository = mockk<HandRepository>(relaxed = true)
        val saga = EvolutionApplySaga(proposalStore, skillsStore, null, null, handRepository)
        coEvery { skillsStore.awaitLoaded() } just Runs
        every { skillsStore.findById("s1") } returns Skill(id = "s1", name = "Daily", description = "", body = "b")

        val patch = PromoteToHandPatch(
            handName = "daily_digest",
            triggerPhrase = "daily digest",
            steps = listOf(HandStepPatch(tool = "web_search", args = mapOf("query" to "news"))),
        )
        val proposal = proposal(
            action = EvolutionAction.PROMOTE_TO_HAND,
            targetId = "s1",
            patchJson = json.encodeToString(PromoteToHandPatch.serializer(), patch),
        )
        val result = saga.apply(proposal)

        assertTrue(result is EvolutionApplySaga.ApplyResult.Ok)
        val hand = slot<Hand>()
        coVerify { handRepository.insert(capture(hand)) }
        assertEquals("daily_digest", hand.captured.name)
        assertTrue(hand.captured.steps.contains("web_search"), "steps must be REAL, not []: ${hand.captured.steps}")
        // Typed snapshot records the created hand id (D7).
        val snapshot = slot<String>()
        coVerify { proposalStore.recordRollbackSnapshot("p1", capture(snapshot)) }
        val snap = json.decodeFromString<PromoteToHandSnapshot>(snapshot.captured)
        assertEquals(hand.captured.id, snap.handId)
    }

    @Test
    fun `apply consolidate memories snapshots full sources before deleting`() = runTest {
        val memoryStore = mockk<MemoryStore>(relaxed = true)
        val proposalStore = mockk<EvolutionProposalStore>(relaxed = true)
        val saga = EvolutionApplySaga(proposalStore, null, null, memoryStore)
        val m1 = MemoryEntity(id = "m1", content = "likes tea", source = "user", category = "preference", scope = "general")
        val m2 = MemoryEntity(id = "m2", content = "prefers tea over coffee", source = "user", category = "preference", scope = "general")
        coEvery { memoryStore.get("m1") } returns m1
        coEvery { memoryStore.get("m2") } returns m2
        coEvery { memoryStore.store(any(), any(), any(), any(), any(), any(), any()) } returns "consolidated-1"
        coEvery { memoryStore.forget(any()) } just Runs

        val patch = ConsolidateMemoriesPatch(
            memoryIds = listOf("m1", "m2"),
            consolidatedContent = "Elnur prefers tea over coffee",
        )
        val proposal = proposal(
            action = EvolutionAction.CONSOLIDATE_MEMORIES,
            targetId = "m1",
            domain = EvolutionDomain.MEMORY,
            patchJson = json.encodeToString(ConsolidateMemoriesPatch.serializer(), patch),
        )
        val result = saga.apply(proposal)

        assertTrue(result is EvolutionApplySaga.ApplyResult.Ok)
        val snapshot = slot<String>()
        coVerify { proposalStore.recordRollbackSnapshot("p1", capture(snapshot)) }
        val snap = json.decodeFromString<ConsolidateMemoriesSnapshot>(snapshot.captured)
        assertEquals("consolidated-1", snap.consolidatedMemoryId)
        assertEquals(listOf("m1", "m2"), snap.sources.map { it.id })
        assertEquals("likes tea", snap.sources[0].content)
        coVerify { memoryStore.forget("m1") }
        coVerify { memoryStore.forget("m2") }
    }

    @Test
    fun `apply consolidate memories keeps shared source scope`() = runTest {
        val memoryStore = mockk<MemoryStore>(relaxed = true)
        val proposalStore = mockk<EvolutionProposalStore>(relaxed = true)
        val saga = EvolutionApplySaga(proposalStore, null, null, memoryStore)
        val m1 = MemoryEntity(id = "m1", content = "a", source = "user", category = "fact", scope = "agent:researcher")
        val m2 = MemoryEntity(id = "m2", content = "b", source = "user", category = "fact", scope = "agent:researcher")
        coEvery { memoryStore.get("m1") } returns m1
        coEvery { memoryStore.get("m2") } returns m2
        coEvery { memoryStore.store(any(), any(), any(), any(), any(), any(), any()) } returns "c1"
        coEvery { memoryStore.forget(any()) } just Runs

        val patch = ConsolidateMemoriesPatch(memoryIds = listOf("m1", "m2"), consolidatedContent = "ab")
        val result = saga.apply(
            proposal(EvolutionAction.CONSOLIDATE_MEMORIES, "m1", EvolutionDomain.MEMORY,
                json.encodeToString(ConsolidateMemoriesPatch.serializer(), patch))
        )

        assertTrue(result is EvolutionApplySaga.ApplyResult.Ok)
        coVerify { memoryStore.store("ab", any(), any(), any(), any(), "agent:researcher", any()) }
    }

    @Test
    fun `apply unknown legacy action returns error`() = runTest {
        val saga = EvolutionApplySaga(mockk(relaxed = true))
        val legacy = EvolutionProposalEntity(
            id = "p9",
            domain = EvolutionDomain.MEMORY.name,
            action = "FORGET_MEMORY",
            targetId = "m1",
        )
        val result = saga.apply(legacy)
        assertTrue(result is EvolutionApplySaga.ApplyResult.Error)
        assertTrue((result as EvolutionApplySaga.ApplyResult.Error).message.contains("unknown action"))
    }

    private fun proposal(
        action: EvolutionAction,
        targetId: String,
        domain: EvolutionDomain = EvolutionDomain.SKILL,
        patchJson: String = "{}",
    ) = EvolutionProposalEntity(
        id = "p1",
        domain = domain.name,
        action = action.name,
        targetId = targetId,
        title = "Test",
        summary = "",
        confidence = 0.8f,
        patchJson = patchJson,
    )
}
