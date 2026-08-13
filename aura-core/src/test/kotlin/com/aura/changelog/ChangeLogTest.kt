package com.aura.changelog

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.aura.dream.ContradictionEntity
import com.aura.dream.DreamConsolidationDatabase
import com.aura.dream.DreamSummaryEntity
import com.aura.memory.MemoryDatabase
import com.aura.memory.CorrectionEntity
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
 * What changed, rather than what is true.
 *
 * `MindScreen` is entirely present-tense and nothing anywhere answered *what
 * changed this week*. Everything needed already existed and was already indexed
 * on time; the only reason there was no answer is that nobody had read across
 * the tables.
 *
 * The two properties that matter are that the window is a window — rows outside
 * it must not appear, however interesting — and that the merge across stores is
 * genuinely ordered rather than concatenated per source.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class ChangeLogTest {

    private lateinit var memoryDb: MemoryDatabase
    private lateinit var dreamDb: DreamConsolidationDatabase
    private lateinit var log: ChangeLog

    private val now = 1_800_000_000_000L
    private val day = 24L * 60 * 60 * 1000
    private val weekAgo = now - ChangeLog.WEEK_MS

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        memoryDb = Room.inMemoryDatabaseBuilder(context, MemoryDatabase::class.java)
            .allowMainThreadQueries().build()
        dreamDb = Room.inMemoryDatabaseBuilder(context, DreamConsolidationDatabase::class.java)
            .allowMainThreadQueries().build()
        log = ChangeLog(
            correctionDao = memoryDb.correctionDao(),
            dreamDao = dreamDb.dreamConsolidationDao(),
            beliefDao = null,
            contradictionDao = dreamDb.contradictionDao(),
            worldEventDao = null,
        )
    }

    @After
    fun tearDown() {
        memoryDb.close()
        dreamDb.close()
    }

    private suspend fun correction(at: Long, note: String) =
        memoryDb.correctionDao().insert(
            CorrectionEntity(
                id = "c$at",
                targetKind = CorrectionEntity.TARGET_MEMORY,
                targetId = "m1",
                kind = CorrectionEntity.NEVER_TRUE,
                note = note,
                createdAt = at,
            ),
        )

    private suspend fun dream(at: Long, text: String) =
        dreamDb.dreamConsolidationDao().insert(
            DreamSummaryEntity(
                id = "d$at",
                clusterId = "cl$at",
                compressedText = text,
                sourceMemoryIds = "m1,m2",
                dominantTags = "work",
                sourceCount = 2,
                modelUsed = "test",
                createdAt = at,
            ),
        )

    private suspend fun contradiction(at: Long, newer: String) =
        dreamDb.contradictionDao().insert(
            ContradictionEntity(
                id = "x$at",
                olderSummaryId = "d1",
                newerSummaryId = "d2",
                olderText = "older",
                newerText = newer,
                triggerPhrase = "but",
                createdAt = at,
            ),
        )

    @Test
    fun `changes from three stores merge into one ordered list`() = runBlocking {
        correction(now - 3 * day, "not that")
        dream(now - 1 * day, "worked it out")
        contradiction(now - 2 * day, "these disagree")

        val changes = log.since(weekAgo)

        assertEquals(3, changes.size)
        assertEquals(
            listOf(Change.Kind.CONSOLIDATION, Change.Kind.CONTRADICTION, Change.Kind.CORRECTION),
            changes.map { it.kind },
            "the merge concatenated per source instead of ordering across them",
        )
        assertTrue(changes.zipWithNext().all { (a, b) -> a.at >= b.at })
    }

    /**
     * The window has to be a window. A correction from a year ago is not news,
     * and a "since last week" list that quietly includes it is lying about its
     * own heading.
     */
    @Test
    fun `anything older than the window is excluded`() = runBlocking {
        correction(now - 400 * day, "ancient")
        dream(now - 8 * day, "just outside")
        correction(now - 2 * day, "inside")

        val changes = log.since(weekAgo)

        assertEquals(1, changes.size)
        assertEquals("inside", changes.single().detail)
    }

    @Test
    fun `the boundary itself is inside the window`() = runBlocking {
        correction(weekAgo, "exactly a week old")

        assertEquals(1, log.since(weekAgo).size)
    }

    @Test
    fun `the limit caps the merged list, keeping the newest`() = runBlocking {
        repeat(10) { i -> correction(now - (i + 1) * 1000L, "note $i") }

        val changes = log.since(weekAgo, limit = 3)

        assertEquals(3, changes.size)
        assertEquals(listOf("note 0", "note 1", "note 2"), changes.map { it.detail })
    }

    @Test
    fun `nothing to report is an empty list, not a failure`() = runBlocking {
        assertTrue(log.since(weekAgo).isEmpty())
    }

    /**
     * One dead store must not starve the rest — the `SituationReader` rule. Every
     * DAO here is nullable and absent ones simply contribute nothing, which is
     * also what a store that throws does.
     */
    @Test
    fun `a missing store contributes nothing rather than failing the read`() = runBlocking {
        correction(now - day, "still here")

        val partial = ChangeLog(
            correctionDao = memoryDb.correctionDao(),
            dreamDao = null,
            beliefDao = null,
            contradictionDao = null,
            worldEventDao = null,
        )

        assertEquals(1, partial.since(weekAgo).size)
    }

    @Test
    fun `a correction says which kind it was, in the words a person would use`() = runBlocking {
        correction(now - day, "wrong")

        assertEquals("You said something was never true", log.since(weekAgo).single().headline)
    }
}
