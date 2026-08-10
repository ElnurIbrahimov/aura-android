package com.aura.evolution

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.aura.agent.Tool
import com.aura.agent.ToolRegistry
import com.aura.agent.ToolResult
import com.aura.agent.ToolRisk
import com.aura.hands.Hand
import com.aura.hands.HandRepository
import com.aura.memory.MemoryEntity
import com.aura.memory.MemoryStore
import com.aura.providers.ToolParameters
import com.aura.skills.Skill
import com.aura.skills.SkillsStore
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import javax.inject.Provider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Keystone test for the evolution rebuild: proves the WHOLE authoring
 * pipeline produces applyable patches for every producible action.
 *
 * For each of the 4 actions:
 *   detector-shaped candidate (argsJson = "{}", exactly what detectors emit)
 *   → EvolutionPatchAuthor (LLM fixture JSON) → schema validation
 *   → EvolutionProposalStore.fromCandidate (real Room store)
 *   → EvolutionApplySaga.apply returns Ok (mockk artifact stores)
 *   → EvolutionRollbackManager.rollback returns Ok with the EXACT inverse calls.
 *
 * This is the regression suite for the original defect: detector-born
 * proposals reached the saga as "{}" and failed or silently no-oped.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class EvolutionEndToEndApplyTest {

    private lateinit var db: EvolutionDatabase
    private lateinit var proposalStore: EvolutionProposalStore
    private lateinit var registry: ToolRegistry
    private lateinit var validator: EvolutionPatchValidator

    private val json = EvolutionPatchJson.json

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, EvolutionDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        proposalStore = EvolutionProposalStore(
            db.proposalDao(), db.revisionDao(), db.candidateDao(), EvolutionMetrics(), EvolutionSafetyGuard(),
        )
        registry = ToolRegistry().apply {
            register(
                Tool(
                    name = "web_search",
                    description = "search the web",
                    risk = ToolRisk.READ_ONLY,
                    parameters = ToolParameters(),
                    execute = { _, _ -> ToolResult.Ok("ok") },
                )
            )
        }
        validator = EvolutionPatchValidator(Provider { registry }, EvolutionSafetyGuard())
    }

    @After
    fun teardown() { db.close() }

    /** Exactly what the detectors emit: real action/target, EMPTY argsJson. */
    private fun detectorCandidate(action: EvolutionAction, domain: EvolutionDomain, targetId: String) =
        EvolutionCandidateEntity(
            id = java.util.UUID.randomUUID().toString(),
            domain = domain.name,
            action = action.name,
            targetId = targetId,
            argsJson = "{}",
            score = 0.85f,
            rationale = "detector fired",
            status = CandidateStatus.PENDING.name,
        )

    private fun reflectionReturning(fixtureJson: String): EvolutionReflectionExecutor {
        val reflection = mockk<EvolutionReflectionExecutor>()
        coEvery { reflection.reflect(any(), any(), any()) } returns EvolutionReflectionExecutor.Result.Ok(fixtureJson)
        return reflection
    }

    private suspend fun authorAndPropose(
        candidate: EvolutionCandidateEntity,
        author: EvolutionPatchAuthor,
    ): EvolutionProposalEntity {
        db.candidateDao().upsert(candidate)
        val result = author.author(candidate)
        assertTrue(result is EvolutionPatchAuthor.Result.Approved, "author must approve, got $result")
        val approved = result as EvolutionPatchAuthor.Result.Approved
        // Same step the coordinator performs: proposal carries the authored patch.
        return proposalStore.fromCandidate(candidate.copy(argsJson = approved.patchJson, reflectionResult = approved.reason))
    }

    // ── PATCH_SKILL ─────────────────────────────────────────────

    @Test
    fun `patch skill end to end apply then exact rollback`() = runBlocking {
        val original = Skill(id = "s1", name = "Summarizer", description = "sums", body = "old broken body")
        val skillsStore = mockk<SkillsStore>(relaxed = true)
        coEvery { skillsStore.awaitLoaded() } just Runs
        every { skillsStore.findById("s1") } returns original

        val author = EvolutionPatchAuthor(
            reflectionReturning("""{"decision":"approve","reason":"fixes the failure","patch":{"description":"sums better","body":"new fixed body"}}"""),
            validator, Provider { registry }, skillsStore,
        )
        val proposal = authorAndPropose(
            detectorCandidate(EvolutionAction.PATCH_SKILL, EvolutionDomain.SKILL, "s1"), author,
        )

        val saga = EvolutionApplySaga(proposalStore, skillsStore)
        val applied = saga.apply(proposal)
        assertTrue(applied is EvolutionApplySaga.ApplyResult.Ok, "apply must succeed, got $applied")
        val updatedSkill = slot<Skill>()
        coVerify { skillsStore.update(capture(updatedSkill)) }
        assertEquals("new fixed body", updatedSkill.captured.body)
        assertEquals("sums better", updatedSkill.captured.description)

        // Rollback: the exact inverse — restore the snapshotted original.
        val rollback = EvolutionRollbackManager(
            db.proposalDao(), db.revisionDao(), EvolutionMetrics(), skillsStore,
        )
        val rolled = rollback.rollback(proposal.id)
        assertTrue(rolled is EvolutionRollbackManager.RollbackResult.Ok, "rollback must succeed, got $rolled")
        val restored = mutableListOf<Skill>()
        coVerify(exactly = 2) { skillsStore.update(capture(restored)) }
        assertEquals("old broken body", restored.last().body)
        assertEquals("Summarizer", restored.last().name)
        assertEquals(ProposalStatus.ROLLED_BACK.name, db.proposalDao().getById(proposal.id)?.status)
    }

    // ── RETIRE_SKILL ────────────────────────────────────────────

    @Test
    fun `retire skill end to end apply then exact rollback`() = runBlocking {
        val original = Skill(id = "s2", name = "DeadSkill", description = "", body = "never works")
        val skillsStore = mockk<SkillsStore>(relaxed = true)
        coEvery { skillsStore.awaitLoaded() } just Runs
        every { skillsStore.findById("s2") } returns original

        val author = EvolutionPatchAuthor(
            reflectionReturning("""{"decision":"approve","reason":"unfixable","patch":{"reason":"failed 12 of 12 invocations"}}"""),
            validator, Provider { registry }, skillsStore,
        )
        val proposal = authorAndPropose(
            detectorCandidate(EvolutionAction.RETIRE_SKILL, EvolutionDomain.SKILL, "s2"), author,
        )

        val saga = EvolutionApplySaga(proposalStore, skillsStore)
        val applied = saga.apply(proposal)
        assertTrue(applied is EvolutionApplySaga.ApplyResult.Ok, "apply must succeed, got $applied")
        coVerify { skillsStore.remove("s2") }

        val rollback = EvolutionRollbackManager(
            db.proposalDao(), db.revisionDao(), EvolutionMetrics(), skillsStore,
        )
        val rolled = rollback.rollback(proposal.id)
        assertTrue(rolled is EvolutionRollbackManager.RollbackResult.Ok, "rollback must succeed, got $rolled")
        // Exact inverse: the removed skill is re-added byte-identical.
        val readded = slot<Skill>()
        coVerify { skillsStore.add(capture(readded)) }
        assertEquals("s2", readded.captured.id)
        assertEquals("never works", readded.captured.body)
    }

    // ── PROMOTE_TO_HAND ─────────────────────────────────────────

    @Test
    fun `promote to hand end to end apply then exact rollback`() = runBlocking {
        val skill = Skill(id = "s3", name = "DailyDigest", description = "digest", body = "search news, email me")
        val skillsStore = mockk<SkillsStore>(relaxed = true)
        coEvery { skillsStore.awaitLoaded() } just Runs
        every { skillsStore.findById("s3") } returns skill
        val handRepository = mockk<HandRepository>(relaxed = true)

        val author = EvolutionPatchAuthor(
            reflectionReturning(
                """{"decision":"approve","reason":"used daily","patch":{"handName":"daily_digest","triggerPhrase":"daily digest","steps":[{"tool":"web_search","args":{"query":"today's news"}}]}}"""
            ),
            validator, Provider { registry }, skillsStore,
        )
        val proposal = authorAndPropose(
            detectorCandidate(EvolutionAction.PROMOTE_TO_HAND, EvolutionDomain.SKILL, "s3"), author,
        )

        val saga = EvolutionApplySaga(proposalStore, skillsStore, null, null, handRepository)
        val applied = saga.apply(proposal)
        assertTrue(applied is EvolutionApplySaga.ApplyResult.Ok, "apply must succeed, got $applied")
        val hand = slot<Hand>()
        coVerify { handRepository.insert(capture(hand)) }
        assertEquals("daily_digest", hand.captured.name)
        assertTrue(hand.captured.steps.contains("web_search"), "hand must carry REAL steps: ${hand.captured.steps}")
        assertTrue(hand.captured.steps.contains("today's news"))

        // The recorded snapshot carries the exact created hand id.
        val storedSnapshot = db.proposalDao().getById(proposal.id)!!.rollbackSnapshotJson
        val snap = json.decodeFromString<PromoteToHandSnapshot>(storedSnapshot)
        assertEquals(hand.captured.id, snap.handId)

        val rollback = EvolutionRollbackManager(
            db.proposalDao(), db.revisionDao(), EvolutionMetrics(), skillsStore, null, handRepository,
        )
        val rolled = rollback.rollback(proposal.id)
        assertTrue(rolled is EvolutionRollbackManager.RollbackResult.Ok, "rollback must succeed, got $rolled")
        // Exact inverse: delete precisely the created hand id.
        coVerify { handRepository.deleteById(hand.captured.id) }
    }

    // ── CONSOLIDATE_MEMORIES ────────────────────────────────────

    @Test
    fun `consolidate memories end to end apply then exact rollback`() = runBlocking {
        val m1 = MemoryEntity(id = "m1", content = "Elnur likes tea", source = "user", category = "preference", scope = "general")
        val m2 = MemoryEntity(id = "m2", content = "Elnur prefers tea over coffee", source = "user", category = "preference", scope = "general")
        val memoryStore = mockk<MemoryStore>(relaxed = true)
        coEvery { memoryStore.get("m1") } returns m1
        coEvery { memoryStore.get("m2") } returns m2
        coEvery { memoryStore.recent(any()) } returns listOf(m2)
        coEvery { memoryStore.store(any(), any(), any(), any(), any(), any(), any()) } returns "consolidated-1"
        coEvery { memoryStore.forget(any()) } just Runs
        coEvery { memoryStore.restore(any(), any()) } just Runs

        val author = EvolutionPatchAuthor(
            reflectionReturning(
                """{"decision":"approve","reason":"same fact twice","patch":{"memoryIds":["m1","m2"],"consolidatedContent":"Elnur prefers tea over coffee","category":"preference"}}"""
            ),
            validator, Provider { registry }, null, memoryStore,
        )
        val proposal = authorAndPropose(
            detectorCandidate(EvolutionAction.CONSOLIDATE_MEMORIES, EvolutionDomain.MEMORY, "m1"), author,
        )

        val saga = EvolutionApplySaga(proposalStore, null, null, memoryStore)
        val applied = saga.apply(proposal)
        assertTrue(applied is EvolutionApplySaga.ApplyResult.Ok, "apply must succeed, got $applied")
        coVerify { memoryStore.store("Elnur prefers tea over coffee", any(), "preference", any(), any(), "general", any()) }
        coVerify { memoryStore.forget("m1") }
        coVerify { memoryStore.forget("m2") }

        // The snapshot holds the FULL source entities.
        val storedSnapshot = db.proposalDao().getById(proposal.id)!!.rollbackSnapshotJson
        val snap = json.decodeFromString<ConsolidateMemoriesSnapshot>(storedSnapshot)
        assertEquals("consolidated-1", snap.consolidatedMemoryId)
        assertEquals(setOf("m1", "m2"), snap.sources.map { it.id }.toSet())
        assertEquals("Elnur likes tea", snap.sources.first { it.id == "m1" }.content)

        val rollback = EvolutionRollbackManager(
            db.proposalDao(), db.revisionDao(), EvolutionMetrics(), null, memoryStore,
        )
        val rolled = rollback.rollback(proposal.id)
        assertTrue(rolled is EvolutionRollbackManager.RollbackResult.Ok, "rollback must succeed, got $rolled")
        // Exact inverse: forget the consolidated memory, restore every source.
        coVerify { memoryStore.forget("consolidated-1") }
        coVerify { memoryStore.restore(match { it.id == "m1" && it.content == "Elnur likes tea" }, any()) }
        coVerify { memoryStore.restore(match { it.id == "m2" }, any()) }
        assertEquals(ProposalStatus.ROLLED_BACK.name, db.proposalDao().getById(proposal.id)?.status)
    }

    // ── Legacy guard ────────────────────────────────────────────

    @Test
    fun `legacy empty-patch proposal fails apply with a typed error instead of no-oping`() = runBlocking {
        val skillsStore = mockk<SkillsStore>(relaxed = true)
        coEvery { skillsStore.awaitLoaded() } just Runs
        every { skillsStore.findById("s1") } returns Skill(id = "s1", name = "T", description = "", body = "b")
        val legacy = EvolutionProposalEntity(
            id = "legacy-1",
            domain = EvolutionDomain.SKILL.name,
            action = EvolutionAction.PATCH_SKILL.name,
            targetId = "s1",
            patchJson = "{}",
        )
        db.proposalDao().upsert(legacy)
        val saga = EvolutionApplySaga(proposalStore, skillsStore)
        val result = saga.apply(legacy)
        assertTrue(result is EvolutionApplySaga.ApplyResult.Error)
        coVerify(exactly = 0) { skillsStore.update(any()) }
    }
}
