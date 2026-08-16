package com.aura.memory

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.aura.provenance.ConversationProvenance
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
 * Which signals are allowed to set a grade, and which are not.
 *
 * The distinction this pins is the one the whole design rests on. Two signals
 * are about *this memory for this question* — the consult pass and the user's
 * "not for this question" — and those grade. Thumbs and regenerate are verdicts
 * on the *answer*; spreading one across every memory recalled for that turn
 * produces rows that all grade alike, which `RetrievalMetrics` cannot separate
 * and which would dilute nDCG rather than inform it. Those write
 * `heuristicGrade` and leave `grade` alone.
 *
 * Getting this backwards would not fail anything loudly. It would just produce a
 * metric that moved with how often the user tapped thumbs-down.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class RetrievalLabelHeuristicsTest {

    private lateinit var db: MemoryDatabase
    private lateinit var store: RetrievalLabelStore
    private lateinit var dao: RetrievalLabelDao

    private val turn = ConversationProvenance("c1", 1_000L)

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, MemoryDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.retrievalLabelDao()
        store = RetrievalLabelStore(dao)
    }

    @After
    fun tearDown() = db.close()

    private suspend fun recallOf(vararg memoryIds: String) =
        store.record("what do I like?", memoryIds.toList(), turn)

    private suspend fun row(memoryId: String) =
        dao.forConversation("c1").first { it.memoryId == memoryId }

    /**
     * Unselected is not the same as irrelevant. Grading the unconsulted rows 0
     * would manufacture negatives the pass never asserted, and those negatives
     * would then count against any retrieval change that surfaced them.
     */
    @Test
    fun `the consult pass grades what it selected and leaves the rest alone`() = runBlocking {
        recallOf("m1", "m2", "m3")

        store.recordConsulted(turn, listOf("m2"))

        assertEquals(RetrievalLabelStore.GRADE_RELEVANT, row("m2").grade)
        assertEquals(RetrievalLabelStore.SOURCE_HEURISTIC, row("m2").gradeSource)
        assertNull("an unconsulted memory must stay ungraded", row("m1").grade)
        assertNull("an unconsulted memory must stay ungraded", row("m3").grade)
    }

    /**
     * `RecallSummary.consultedIds` carries beliefs as `belief:<id>` alongside
     * memory ids. Those match no label row, and the UPDATE must simply do
     * nothing rather than fail the turn.
     */
    @Test
    fun `a consulted id that is not a memory grades nothing and does not throw`() = runBlocking {
        recallOf("m1")

        store.recordConsulted(turn, listOf("belief:b1", "m1"))

        assertEquals(RetrievalLabelStore.GRADE_RELEVANT, row("m1").grade)
        assertEquals(1, dao.forConversation("c1").size)
    }

    @Test
    fun `the user's not-for-this-question is a graded zero from the user`() = runBlocking {
        recallOf("m1", "m2")

        store.recordIrrelevantHere(turn, "m1")

        assertEquals(RetrievalLabelStore.GRADE_IRRELEVANT, row("m1").grade)
        assertEquals(RetrievalLabelStore.SOURCE_USER, row("m1").gradeSource)
        assertNull(row("m2").grade)
    }

    /**
     * The load-bearing negative case. A thumbs-down must not become a grade on
     * five memories that happened to be recalled together.
     */
    @Test
    fun `a turn-level verdict never sets grade`() = runBlocking {
        recallOf("m1", "m2", "m3")

        store.recordTurnSignal(turn, RetrievalLabelStore.TurnSignal.THUMBS_DOWN)

        val rows = dao.forConversation("c1")
        assertTrue("a turn verdict must not grade individual memories", rows.all { it.grade == null })
        assertTrue(
            "the signal must still be recorded, for calibrating against the judge",
            rows.all { it.heuristicGrade == RetrievalLabelStore.GRADE_IRRELEVANT },
        )
        assertTrue("which signal fired must be recoverable", rows.all { "thumbs_down" in it.signalsJson })
    }

    /** A precise grade already set must survive a later turn-level signal. */
    @Test
    fun `a turn-level verdict does not overwrite a precise grade`() = runBlocking {
        recallOf("m1")
        store.recordConsulted(turn, listOf("m1"))

        store.recordTurnSignal(turn, RetrievalLabelStore.TurnSignal.THUMBS_DOWN)

        assertEquals(
            "the consult pass's grade was clobbered by a verdict on the whole answer",
            RetrievalLabelStore.GRADE_RELEVANT,
            row("m1").grade,
        )
    }

    @Test
    fun `an edit marks the turn rather than grading it down`() = runBlocking {
        recallOf("m1", "m2")

        store.markSupersededByEdit(turn)

        val rows = dao.forConversation("c1")
        assertTrue("the turn must be marked", rows.all { it.supersededByEdit })
        assertTrue(
            "an edit says the question was wrong, not the memories — it must not grade them",
            rows.all { it.grade == null },
        )
    }

    /** Everything here is a no-op for a read that was not serving a turn. */
    @Test
    fun `signals with no provenance do nothing`() = runBlocking {
        recallOf("m1")
        val absent = ConversationProvenance()

        store.recordConsulted(absent, listOf("m1"))
        store.recordIrrelevantHere(absent, "m1")
        store.recordTurnSignal(absent, RetrievalLabelStore.TurnSignal.THUMBS_UP)
        store.markSupersededByEdit(absent)

        assertNull(row("m1").grade)
        assertNull(row("m1").heuristicGrade)
    }
}
