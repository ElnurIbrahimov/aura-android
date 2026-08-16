package com.aura.memory

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
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
 * Real-Room contract suite for `retrieval_labels`.
 *
 * Against real in-memory SQLite rather than a mocked DAO, for the reason
 * [MemoryDaoContractTest] already records: the ESCAPE regression survived 1,669
 * green tests because every touching test mocked the DAO, and SQL-level errors
 * are invisible to mocks. The load-bearing behaviour here — that re-recalling
 * the same memory for the same turn updates rather than duplicates — is a
 * primary-key behaviour, which is exactly the kind a mock cannot show.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class RetrievalLabelDaoTest {

    private lateinit var db: MemoryDatabase
    private lateinit var dao: RetrievalLabelDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, MemoryDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.retrievalLabelDao()
    }

    @After
    fun tearDown() = db.close()

    private fun label(
        conversationId: String = "c1",
        turnTimestamp: Long = 1_000L,
        memoryId: String = "m1",
        rank: Int = 1,
        grade: Int? = null,
        sampled: Boolean = false,
        createdAt: Long = 1_000L,
    ) = RetrievalLabelEntity(
        id = RetrievalLabelEntity.idFor(conversationId, turnTimestamp, memoryId),
        conversationId = conversationId,
        turnTimestamp = turnTimestamp,
        queryText = "what do I like?",
        memoryId = memoryId,
        rank = rank,
        grade = grade,
        sampled = sampled,
        createdAt = createdAt,
    )

    /**
     * The reason the primary key is derived rather than random.
     *
     * A retry re-runs recall for the same turn and returns the same memories.
     * With a random id that writes a second row per memory per attempt, and the
     * export would then count one query's judgments several times — weighting
     * whichever queries the user happened to retry. Deriving the key from
     * (conversation, turn, memory) makes the second write an update.
     */
    @Test
    fun `re-recalling the same memory for the same turn updates rather than duplicates`() = runBlocking {
        dao.upsert(label(rank = 3))
        dao.upsert(label(rank = 1))

        val rows = dao.forConversation("c1")
        assertEquals("a retry must not create a second row", 1, rows.size)
        assertEquals("the newer rank must win", 1, rows.first().rank)
    }

    /** Different memories in one turn are different rows; different turns too. */
    @Test
    fun `the key separates memories and turns`() = runBlocking {
        dao.upsert(label(memoryId = "m1"))
        dao.upsert(label(memoryId = "m2"))
        dao.upsert(label(turnTimestamp = 2_000L, memoryId = "m1"))

        assertEquals(3, dao.forConversation("c1").size)
    }

    /**
     * Deletion is explicit because it cannot be anything else: `conversations`
     * lives in a different Room database, and SQLite has no cross-database
     * foreign keys, so no cascade is expressible. Orphaned labels are the
     * default state and every deletion path has to be wired by hand.
     */
    @Test
    fun `deleting one conversation leaves the others intact`() = runBlocking {
        dao.upsert(label(conversationId = "c1"))
        dao.upsert(label(conversationId = "c2"))

        dao.deleteForConversation("c1")

        assertTrue(dao.forConversation("c1").isEmpty())
        assertEquals(1, dao.forConversation("c2").size)
    }

    @Test
    fun `retention deletes only rows older than the cutoff`() = runBlocking {
        dao.upsert(label(memoryId = "old", createdAt = 1_000L))
        dao.upsert(label(memoryId = "new", createdAt = 9_000L))

        dao.deleteOlderThan(5_000L)

        val remaining = dao.forConversation("c1")
        assertEquals(1, remaining.size)
        assertEquals("new", remaining.first().memoryId)
    }

    /**
     * An observed-but-ungraded row is the normal state — most recalls are never
     * judged. Null grade must survive the round trip rather than defaulting to
     * 0, which `RetrievalMetrics` would read as "irrelevant" and score against
     * the ranker.
     */
    @Test
    fun `an ungraded row round-trips with a null grade`() = runBlocking {
        dao.upsert(label(grade = null))

        assertNull(dao.forConversation("c1").first().grade)
    }

    /** The judge's work queue: sampled turns that have not been graded yet. */
    @Test
    fun `ungraded sampled rows are what the judge picks up`() = runBlocking {
        dao.upsert(label(memoryId = "sampled-ungraded", sampled = true, grade = null))
        dao.upsert(label(memoryId = "sampled-graded", sampled = true, grade = 2))
        dao.upsert(label(memoryId = "unsampled", sampled = false, grade = null))

        val pending = dao.ungradedSampled(limit = 10)

        assertEquals(1, pending.size)
        assertEquals("sampled-ungraded", pending.first().memoryId)
    }

    /**
     * Sampling draws to a target count rather than a fixed rate, so the store
     * has to be able to ask how many turns are already marked in the window.
     * A 5% rate cannot reach the ~50-query floor `RETRIEVAL_EVAL.md` sets:
     * 30 days at a plausible 20 recall-turns a day is ~600 turns, which is
     * ~30 queries — permanently below the noise floor.
     */
    @Test
    fun `sampled turns are counted by turn, not by row`() = runBlocking {
        // One turn, three recalled memories — that is one sampled *query*.
        dao.upsert(label(turnTimestamp = 1_000L, memoryId = "m1", sampled = true))
        dao.upsert(label(turnTimestamp = 1_000L, memoryId = "m2", sampled = true))
        dao.upsert(label(turnTimestamp = 1_000L, memoryId = "m3", sampled = true))
        dao.upsert(label(turnTimestamp = 2_000L, memoryId = "m1", sampled = true))
        dao.upsert(label(turnTimestamp = 3_000L, memoryId = "m1", sampled = false))

        assertEquals(2, dao.countSampledTurnsSince(0L))
    }
}
