package com.aura.memory

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

/**
 * The decay pass must not rewrite rows it is only re-scoring.
 *
 * `runDecayPass` used Room's `@Update updateAll`, which emits `UPDATE … SET`
 * over **every** column of the entity. Moving one float therefore rewrote each
 * row's content, tags, metadata and 384-float embedding BLOB — and, because
 * `content` appears in the SET list even when its value is identical, fired the
 * FTS trigger and re-tokenised every touched row. On a six-hourly schedule over
 * a store of any size that is a large amount of write I/O to change a number.
 *
 * Scoping the trigger to `AFTER UPDATE OF content` (MIGRATION_26_27) does **not**
 * fix this case, for exactly that reason, and that is the trap this file exists
 * to hold: the trigger fix looks like it covers decay and does not. Only a
 * narrow statement does.
 *
 * The detector is the same sentinel used by `MemoryMigration26To27Test` — a term
 * written into the index that appears nowhere in `memories`, so its survival
 * means the trigger did not fire and its absence means it did.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class DecayDoesNotReindexTest {

    private lateinit var db: MemoryDatabase
    private lateinit var dao: MemoryDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, MemoryDatabase::class.java)
            .allowMainThreadQueries()
            .addCallback(MemoryFtsSchema.triggerCallback)
            .build()
        dao = db.memoryDao()
    }

    @After
    fun tearDown() = db.close()

    private fun memory(id: String, content: String) = MemoryEntity(
        id = id,
        content = content,
        source = "user",
        category = "fact",
        scope = "general",
        importance = 0.5f,
        decayScore = 1.0f,
    )

    private fun plantSentinel(id: String) {
        val raw = db.openHelper.writableDatabase
        raw.execSQL("DELETE FROM `memories_fts` WHERE docid = (SELECT rowid FROM memories WHERE id = ?)", arrayOf(id))
        raw.execSQL(
            "INSERT INTO `memories_fts`(docid, `memoryId`, `content`) " +
                "SELECT rowid, id, 'sentineltoken' FROM memories WHERE id = ?",
            arrayOf(id),
        )
    }

    private fun sentinelIds(): List<String> {
        val ids = mutableListOf<String>()
        db.openHelper.writableDatabase.query(
            "SELECT m.id FROM memories m JOIN memories_fts f ON f.rowid = m.rowid WHERE f.content MATCH ?",
            arrayOf("\"sentineltoken\""),
        ).use { c -> while (c.moveToNext()) ids.add(c.getString(0)) }
        return ids
    }

    @Test
    fun `updateDecayScores leaves the index alone`() = runBlocking {
        dao.insert(memory("m1", "kotlin coroutines"))
        plantSentinel("m1")

        dao.updateDecayScores(listOf(DecayCandidate("m1", createdAt = 0L, accessedAt = 0L, decayScore = 0.4f)))

        assertEquals(listOf("m1"), sentinelIds())
        assertEquals(0.4f, dao.getById("m1")!!.decayScore, 0.0001f)
    }

    @Test
    fun `the old whole-row update did reindex — this is what was fixed`() = runBlocking {
        // `updateAll` is still on the DAO for genuine whole-entity writes. This
        // pins why the decay pass must not use it, so a future change back is a
        // failing test rather than a silent regression in write volume.
        val row = memory("m1", "kotlin coroutines")
        dao.insert(row)
        plantSentinel("m1")

        dao.updateAll(listOf(row.copy(decayScore = 0.4f)))

        assertTrue(
            "Room's @Update names content in the SET list, so the trigger fires even unchanged",
            sentinelIds().isEmpty(),
        )
    }

    @Test
    fun `decayCandidates returns every non-retired row and no embeddings`() = runBlocking {
        // The projection is what removed the 10,000-row cap. A store past that
        // simply stopped fading beyond the ten thousandth row, which is the tail
        // — the part FadeMem exists to handle.
        repeat(20) { i -> dao.insert(memory("m$i", "memory number $i")) }
        val candidates = dao.decayCandidates()
        assertEquals(20, candidates.size)
        // A DecayCandidate carries four scalars; there is no embedding field to
        // load, which is the property that makes an unbounded pass affordable.
        assertTrue(candidates.all { it.id.isNotBlank() })
    }
}
