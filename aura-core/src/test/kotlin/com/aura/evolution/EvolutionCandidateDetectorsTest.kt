package com.aura.evolution

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.aura.memory.DuplicateCluster
import com.aura.memory.MemoryEntity
import com.aura.memory.MemoryStore
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class EvolutionCandidateDetectorsTest {
    private lateinit var db: EvolutionDatabase
    private lateinit var detectors: EvolutionCandidateDetectors
    private lateinit var recorder: EvolutionEvidenceRecorder
    private val memoryStore = mockk<MemoryStore>(relaxed = true)

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, EvolutionDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        coEvery { memoryStore.findNearDuplicateClusters(any(), any(), any()) } returns emptyList()
        detectors = EvolutionCandidateDetectors(db.evidenceDao(), db.candidateDao(), memoryStore)
        recorder = EvolutionEvidenceRecorder(db.evidenceDao())
    }

    @After
    fun teardown() { db.close() }

    private suspend fun seedSkillFailures(skillId: String = "skill_bad", count: Int = 3) {
        repeat(count) {
            recorder.record(
                domain = EvolutionDomain.SKILL,
                kind = "skill_failed",
                sourceEntityId = skillId,
                summary = "fail",
                payload = mapOf("errorCode" to "timeout"),
            )
        }
    }

    @Test
    fun `detects skill patch candidate after 3 failures`() = runBlocking {
        seedSkillFailures()
        val candidates = detectors.runAll()
        assertTrue(candidates.any { it.targetId == "skill_bad" && it.action == EvolutionAction.PATCH_SKILL.name })
    }

    @Test
    fun `consolidation is proposed for near-duplicates, not for heavily recalled memories`() = runBlocking {
        // Twenty-one recalls of one memory used to be the entire signal, so the
        // memory Aura reached for most often was the one it most wanted to
        // merge away. Recall count now proposes nothing on its own.
        repeat(21) {
            recorder.record(
                domain = EvolutionDomain.MEMORY,
                kind = "memory_recalled",
                sourceEntityId = "mem_popular",
            )
        }
        assertTrue(
            "being useful is not evidence a memory is redundant",
            detectors.runAll().none { it.action == EvolutionAction.CONSOLIDATE_MEMORIES.name },
        )

        coEvery { memoryStore.findNearDuplicateClusters(any(), any(), any()) } returns listOf(
            DuplicateCluster(
                memories = listOf(memory("mem_b", "I drink tea"), memory("mem_a", "I drink tea daily")),
                meanSimilarity = 0.94f,
            ),
        )
        val candidate = detectors.runAll()
            .single { it.action == EvolutionAction.CONSOLIDATE_MEMORIES.name }
        // Lowest id anchors the cluster so re-detection refreshes one row.
        assertEquals("mem_a", candidate.targetId)
        assertTrue("a 0.94 cluster must clear the authoring bar: ${candidate.score}", candidate.score >= 0.7f)
        // The cluster travels with the candidate; the authoring step used to
        // rebuild its own unrelated list of "recent" memories instead.
        assertEquals(
            listOf("mem_a", "mem_b"),
            EvolutionPatchJson.json
                .decodeFromString(MemoryClusterArgs.serializer(), candidate.argsJson).memoryIds,
        )
    }

    @Test
    fun `a weak cluster is recorded but stays below the authoring bar`() = runBlocking {
        coEvery { memoryStore.findNearDuplicateClusters(any(), any(), any()) } returns listOf(
            DuplicateCluster(
                memories = listOf(memory("m1", "a"), memory("m2", "b")),
                meanSimilarity = 0.86f,
            ),
        )
        val candidate = detectors.runAll()
            .single { it.action == EvolutionAction.CONSOLIDATE_MEMORIES.name }
        assertTrue(
            "a barely-similar pair must not spend an LLM call: ${candidate.score}",
            candidate.score < EvolutionCoordinator.AUTHORING_SCORE_THRESHOLD,
        )
    }

    private fun memory(id: String, content: String) = MemoryEntity(
        id = id,
        content = content,
        source = "user",
        category = "preference",
    )

    @Test
    fun `detects promotion candidate after 5 invocations`() = runBlocking {
        repeat(5) {
            recorder.record(
                domain = EvolutionDomain.SKILL,
                kind = "skill_invoked",
                sourceEntityId = "skill_hot",
            )
        }
        val candidates = detectors.runAll()
        assertTrue(candidates.any { it.targetId == "skill_hot" && it.action == EvolutionAction.PROMOTE_TO_HAND.name })
    }

    // ── D5 dedup ─────────────────────────────────────────────────

    @Test
    fun `re-detection refreshes the existing pending row instead of duplicating`() = runBlocking {
        seedSkillFailures(count = 3)
        val first = detectors.runAll().first { it.action == EvolutionAction.PATCH_SKILL.name }

        // More failures accumulate; the detector fires again.
        seedSkillFailures(count = 2)
        val second = detectors.runAll().first { it.action == EvolutionAction.PATCH_SKILL.name }

        // Same row (same id), refreshed score/rationale, and only ONE row in the DB.
        assertEquals(first.id, second.id)
        assertTrue(second.rationale.contains("5 times"))
        val pending = db.candidateDao().byStatus(EvolutionDomain.SKILL.name, CandidateStatus.PENDING.name)
            .filter { it.action == EvolutionAction.PATCH_SKILL.name && it.targetId == "skill_bad" }
        assertEquals(1, pending.size)
    }

    @Test
    fun `recently resolved candidate is skipped during the cooldown`() = runBlocking {
        seedSkillFailures()
        val candidate = detectors.runAll().first { it.action == EvolutionAction.PATCH_SKILL.name }
        // Resolve it (e.g. the model rejected it) — updatedAt = now.
        db.candidateDao().setStatus(candidate.id, CandidateStatus.REJECTED.name, "model: no")

        val secondRun = detectors.runAll()

        // Inside the 14-day cooldown: not returned, row stays REJECTED.
        assertTrue(secondRun.none { it.action == EvolutionAction.PATCH_SKILL.name && it.targetId == "skill_bad" })
        assertEquals(CandidateStatus.REJECTED.name, db.candidateDao().getById(candidate.id)?.status)
    }

    @Test
    fun `resolved candidate older than cooldown is reset to pending on the same row`() = runBlocking {
        seedSkillFailures()
        val candidate = detectors.runAll().first { it.action == EvolutionAction.PATCH_SKILL.name }
        // Resolve it 15 days ago (outside the 14-day cooldown).
        val fifteenDaysAgo = System.currentTimeMillis() - 15L * 24 * 60 * 60 * 1000
        db.candidateDao().setStatus(candidate.id, CandidateStatus.REJECTED.name, "model: no", fifteenDaysAgo)

        val secondRun = detectors.runAll()

        val reset = secondRun.first { it.action == EvolutionAction.PATCH_SKILL.name && it.targetId == "skill_bad" }
        assertEquals(candidate.id, reset.id)
        assertEquals(CandidateStatus.PENDING.name, reset.status)
        assertEquals(CandidateStatus.PENDING.name, db.candidateDao().getById(candidate.id)?.status)
        // Still exactly one row for the key.
        val rows = db.candidateDao().allForBackup()
            .filter { it.domain == EvolutionDomain.SKILL.name && it.action == EvolutionAction.PATCH_SKILL.name && it.targetId == "skill_bad" }
        assertEquals(1, rows.size)
    }
}
