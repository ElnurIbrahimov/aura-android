package com.aura.calibration

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.aura.memory.MemoryDatabase
import com.aura.world.BeliefEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The report refuses to say more than it knows.
 *
 * Each of these guards a way the number could become a lie while still looking
 * like a measurement — the failure that would be worst here, because a
 * calibration figure exists precisely to be trusted.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class CalibrationTest {

    private lateinit var db: MemoryDatabase
    private lateinit var calibration: Calibration
    private lateinit var store: ClaimResolutionStore

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            MemoryDatabase::class.java,
        ).allowMainThreadQueries().build()
        calibration = Calibration(db.claimResolutionDao())
        store = ClaimResolutionStore(db.claimResolutionDao(), db.beliefDao(), db.evidenceDao())
    }

    @After
    fun tearDown() = db.close()

    /** Write a resolution directly, so a test can set confidence and source exactly. */
    private fun resolution(
        id: String,
        confidence: Float,
        verdict: String,
        source: String = "user_statement",
    ) = runBlocking {
        db.beliefDao().upsert(
            BeliefEntity(id = id, subject = "user", predicate = "p", valueJson = "\"v\"", confidence = confidence),
        )
        db.claimResolutionDao().insert(
            ClaimResolutionEntity(
                id = "r-$id",
                beliefId = id,
                verdict = verdict,
                verdictSource = ClaimResolutionEntity.SOURCE_CHAT_ANSWER,
                assertedConfidence = confidence,
                beliefSource = source,
            ),
        )
    }

    private fun report() = runBlocking { calibration.report() }

    // ── The floor ────────────────────────────────────────────────────────

    @Test
    fun `below the floor nothing is reportable and no rate is offered`() {
        repeat(5) { resolution("b$it", 0.9f, ClaimResolutionEntity.VERDICT_CONFIRMED) }

        val r = report()
        assertEquals(5, r.scored)
        assertTrue("five samples must not produce a verdict on Aura", !r.reportable)
        assertNull("a Brier score over five samples is noise with a decimal point", r.brier)
    }

    @Test
    fun `at the floor the report opens up`() {
        repeat(Calibration.MIN_SCORED) {
            resolution("b$it", 0.9f, ClaimResolutionEntity.VERDICT_CONFIRMED)
        }

        val r = report()
        assertTrue(r.reportable)
        assertNotNull(r.brier)
    }

    @Test
    fun `a band below the per-bucket floor reports counts but no rate`() {
        // 20 in one band clears the overall floor; 3 in another does not clear
        // the per-bucket floor, and must not be rendered as a percentage.
        repeat(20) { resolution("hi$it", 0.9f, ClaimResolutionEntity.VERDICT_CONFIRMED) }
        repeat(3) { resolution("lo$it", 0.5f, ClaimResolutionEntity.VERDICT_NEVER_TRUE) }

        val r = report()
        val unsure = r.bands.single { it.label == Calibration.Band.UNSURE.label }
        assertEquals(3, unsure.resolved)
        assertNull("three samples is not a rate", unsure.rate)
        assertNotNull(r.bands.single { it.label == Calibration.Band.CONFIDENT.label }.rate)
    }

    // ── The rule the whole design rests on ───────────────────────────────

    /**
     * A world change is neither a hit nor a miss. Counting it as a miss makes
     * measured accuracy fall as Aura learns more, which looks exactly like the
     * system degrading.
     */
    @Test
    fun `world changes are counted separately and never move the rate`() {
        repeat(10) { resolution("ok$it", 0.9f, ClaimResolutionEntity.VERDICT_CONFIRMED) }
        repeat(10) { resolution("bad$it", 0.9f, ClaimResolutionEntity.VERDICT_NEVER_TRUE) }
        val before = report()

        repeat(10) { resolution("moved$it", 0.9f, ClaimResolutionEntity.VERDICT_NO_LONGER_TRUE) }
        val after = report()

        assertEquals("scored count must not move", before.scored, after.scored)
        assertEquals(
            "the confident band's rate must not move",
            before.bands.single { it.label == "confident" }.rate,
            after.bands.single { it.label == "confident" }.rate,
        )
        assertEquals("but they are still visible in the total", 30, after.total)
    }

    // ── Per source ───────────────────────────────────────────────────────

    /**
     * The reason `beliefSource` is denormalised onto every row.
     *
     * `OpportunityEngine` hardcodes its confidences by hand and `BeliefArbiter`
     * computes a margin. If one is reliable and the other is not, a global
     * average describes neither and would hide exactly the finding worth having.
     */
    @Test
    fun `two sources with opposite reliability are not averaged away`() {
        repeat(10) { resolution("good$it", 0.9f, ClaimResolutionEntity.VERDICT_CONFIRMED, source = "user_statement") }
        repeat(10) { resolution("bad$it", 0.9f, ClaimResolutionEntity.VERDICT_NEVER_TRUE, source = "derived") }

        val r = report()
        assertEquals(1f, r.sources.single { it.label == "user_statement" }.rate)
        assertEquals(0f, r.sources.single { it.label == "derived" }.rate)
    }

    // ── Scoring ──────────────────────────────────────────────────────────

    @Test
    fun `a perfectly calibrated set scores zero on Brier and a reversed one scores one`() {
        repeat(Calibration.MIN_SCORED) {
            resolution("sure$it", 1.0f, ClaimResolutionEntity.VERDICT_CONFIRMED)
        }
        assertEquals(0f, report().brier!!, 0.0001f)

        setUp() // fresh db
        repeat(Calibration.MIN_SCORED) {
            resolution("wrong$it", 1.0f, ClaimResolutionEntity.VERDICT_NEVER_TRUE)
        }
        assertEquals(
            "certainty that was always wrong is the worst possible score",
            1f,
            report().brier!!,
            0.0001f,
        )
    }

    @Test
    fun `an empty corpus is not an error and is not a score`() {
        val r = report()
        assertEquals(0, r.scored)
        assertEquals(0, r.total)
        assertTrue(!r.reportable)
        assertNull(r.brier)
        assertTrue("every band is still listed, at zero", r.bands.all { it.resolved == 0 })
    }

    @Test
    fun `bands split on the documented boundaries`() {
        assertEquals(Calibration.Band.UNSURE, Calibration.Band.of(0.59f))
        assertEquals(Calibration.Band.LIKELY, Calibration.Band.of(0.6f))
        assertEquals(Calibration.Band.CONFIDENT, Calibration.Band.of(0.8f))
        assertEquals(Calibration.Band.CERTAIN, Calibration.Band.of(0.95f))
        assertEquals(Calibration.Band.CERTAIN, Calibration.Band.of(1.0f))
    }
}
