package com.aura.evolution

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
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

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, EvolutionDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        detectors = EvolutionCandidateDetectors(db.evidenceDao(), db.candidateDao())
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
    fun `detects memory consolidation candidate after 10 recalls`() = runBlocking {
        repeat(10) {
            recorder.record(
                domain = EvolutionDomain.MEMORY,
                kind = "memory_recalled",
                sourceEntityId = "mem_popular",
            )
        }
        val candidates = detectors.runAll()
        assertTrue(candidates.any { it.targetId == "mem_popular" && it.action == EvolutionAction.CONSOLIDATE_MEMORIES.name })
    }

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
