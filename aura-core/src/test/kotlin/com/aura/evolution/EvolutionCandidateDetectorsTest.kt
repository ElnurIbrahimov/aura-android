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

    @Test
    fun `detects skill patch candidate after 3 failures`() = runBlocking {
        val now = System.currentTimeMillis()
        repeat(3) {
            recorder.record(
                domain = EvolutionDomain.SKILL,
                kind = "skill_failed",
                sourceEntityId = "skill_bad",
                summary = "fail",
                payload = mapOf("errorCode" to "timeout"),
            )
        }
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
    fun `detects proactive dismissal candidate after 3 dismissals`() = runBlocking {
        repeat(3) {
            recorder.record(
                domain = EvolutionDomain.PROACTIVE,
                kind = "proactive_dismissed",
                sourceEntityId = "event_noisy",
                payload = mapOf("kind" to "swipe"),
            )
        }
        val candidates = detectors.runAll()
        assertTrue(candidates.any { it.targetId == "event_noisy" && it.action == EvolutionAction.REWRITE_RULE_MESSAGE.name })
    }
}
