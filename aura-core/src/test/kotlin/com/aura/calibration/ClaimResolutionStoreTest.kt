package com.aura.calibration

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.aura.memory.MemoryDatabase
import com.aura.world.BeliefEntity
import com.aura.world.EvidenceEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The invariants that keep the calibration number honest.
 *
 * Every one of these protects against the same class of failure: a number that
 * looks like a measurement and is not. A duplicate verdict lets one belief
 * dominate a small sample; a live-read confidence re-grades the past; a
 * `no_longer_true` counted as a miss makes accuracy fall as Aura learns.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class ClaimResolutionStoreTest {

    private lateinit var db: MemoryDatabase
    private lateinit var store: ClaimResolutionStore

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            MemoryDatabase::class.java,
        ).allowMainThreadQueries().build()
        store = ClaimResolutionStore(db.claimResolutionDao(), db.beliefDao(), db.evidenceDao())
    }

    @After
    fun tearDown() = db.close()

    private fun belief(id: String = "b1", confidence: Float = 0.8f) = runBlocking {
        val row = BeliefEntity(
            id = id,
            subject = "user",
            predicate = "location",
            valueJson = "\"Baku\"",
            confidence = confidence,
        )
        db.beliefDao().upsert(row)
        row
    }

    private fun evidence(beliefId: String, source: String, at: Long) = runBlocking {
        db.evidenceDao().upsert(
            EvidenceEntity(
                id = "$beliefId-$source-$at",
                beliefId = beliefId,
                source = source,
                summary = "said so",
                timestamp = at,
            ),
        )
    }

    private fun record(
        beliefId: String = "b1",
        verdict: String = ClaimResolutionEntity.VERDICT_NEVER_TRUE,
        source: String = ClaimResolutionEntity.SOURCE_CHAT_ANSWER,
    ) = runBlocking { store.record(beliefId, verdict, source) }

    // ── Refusals ─────────────────────────────────────────────────────────

    @Test
    fun `an unknown verdict is refused`() {
        belief()
        assertNull(record(verdict = "probably_fine"))
    }

    @Test
    fun `an unknown verdict source is refused`() {
        belief()
        assertNull(record(source = "vibes"))
    }

    @Test
    fun `a verdict on an unknown belief is refused before the foreign key rejects it`() {
        assertNull(record(beliefId = "no-such-belief"))
    }

    /**
     * One belief, one verdict. Without this, a claim the user kept revisiting
     * would contribute several rows to a sample of thirty and quietly become
     * most of the measurement.
     */
    @Test
    fun `a second verdict on the same belief is refused`() {
        belief()
        assertTrue(record() != null)
        assertNull(record(verdict = ClaimResolutionEntity.VERDICT_CONFIRMED))
        assertEquals(1, runBlocking { store.forBelief("b1") }.size)
    }

    // ── The snapshot ─────────────────────────────────────────────────────

    /**
     * The reason `assertedConfidence` is a column and not a join.
     *
     * If the calibration curve is ever applied back onto stored confidence —
     * `BeliefDao.verify` is the write path waiting for it — every belief's live
     * confidence starts moving, and a grade read live would re-grade history
     * every time the present changed.
     */
    @Test
    fun `the graded confidence is the one asserted at resolution, not the live one`() {
        belief(confidence = 0.9f)
        val row = record()!!
        assertEquals(0.9f, row.assertedConfidence, 0.0001f)

        runBlocking { db.beliefDao().verify("b1", 0.2f, System.currentTimeMillis()) }

        val stored = runBlocking { store.forBelief("b1") }.single()
        assertEquals(
            "the historical grade moved when the belief's confidence did",
            0.9f,
            stored.assertedConfidence,
            0.0001f,
        )
    }

    // ── Source attribution ───────────────────────────────────────────────

    /**
     * Earliest evidence, not newest.
     *
     * The newest row is whatever most recently agreed with the belief, and
     * treating agreement as provenance is exactly the circularity this feature
     * exists to replace — `BeliefPromoter` already bumps `lastVerifiedAt` when
     * the same edge reappears.
     */
    @Test
    fun `the belief source is the evidence that formed it, not the latest to agree`() {
        belief()
        evidence("b1", "user_statement", at = 100L)
        evidence("b1", "derived", at = 900L)

        assertEquals("user_statement", record()!!.beliefSource)
    }

    @Test
    fun `a belief with no evidence is bucketed as unknown rather than folded in`() {
        belief()
        assertEquals(ClaimResolutionStore.UNKNOWN_SOURCE, record()!!.beliefSource)
    }

    // ── Scoring ──────────────────────────────────────────────────────────

    @Test
    fun `outcome maps confirmed to one, never_true to zero, and world change to null`() {
        assertEquals(1f, ClaimResolutionEntity.outcome(ClaimResolutionEntity.VERDICT_CONFIRMED))
        assertEquals(0f, ClaimResolutionEntity.outcome(ClaimResolutionEntity.VERDICT_NEVER_TRUE))
        assertNull(ClaimResolutionEntity.outcome(ClaimResolutionEntity.VERDICT_NO_LONGER_TRUE))
    }

    @Test
    fun `resolved beliefs are reported so the author never re-asks one`() {
        belief("b1")
        belief("b2")
        record("b1")
        assertEquals(setOf("b1"), runBlocking { store.resolvedBeliefIds() })
    }

    // ── Cascade ──────────────────────────────────────────────────────────

    @Test
    fun `deleting a belief takes its verdicts with it`() {
        belief()
        record()
        runBlocking {
            db.beliefDao().deleteAll()
            assertTrue(db.claimResolutionDao().allForBackup().isEmpty())
        }
    }
}
