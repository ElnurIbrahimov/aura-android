package com.aura.agent

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
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
 * Real-Room contract suite for ConversationDatabase.
 *
 * Exercises: insert, update, search (LIKE with ESCAPE), soft-delete/restore,
 * purgeDeletedBefore, recentVisible, embedding queries, allForExport/insertAll.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class ConversationDaoContractTest {

    private lateinit var db: ConversationDatabase
    private lateinit var dao: ConversationDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, ConversationDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.conversationDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun conv(id: String, title: String, agentId: String? = null) = ConversationEntity(
        id = id,
        title = title,
        createdAt = System.currentTimeMillis(),
        updatedAt = System.currentTimeMillis(),
        systemPrompt = null,
        model = "test:model",
        agentId = agentId,
    )

    // --- CRUD ---

    @Test
    fun `insert and getById roundtrip`() = runBlocking {
        dao.insert(conv("c1", "first conversation"))
        val got = dao.getById("c1")
        assertNotNull(got)
        assertEquals("first conversation", got!!.title)
    }

    @Test
    fun `update changes fields`() = runBlocking {
        val c = conv("c1", "original title")
        dao.insert(c)
        dao.update(c.copy(title = "new title"))
        assertEquals("new title", dao.getById("c1")!!.title)
    }

    @Test
    fun `updateTurns updates turnsJson and updatedAt`() = runBlocking {
        val c = conv("c1", "test")
        dao.insert(c)
        dao.updateTurns("c1", """[{"user":"hi"}]""", 99999L)
        val got = dao.getById("c1")!!
        assertEquals("""[{"user":"hi"}]""", got.turnsJson)
        assertEquals(99999L, got.updatedAt)
    }

    // --- Recent and visible ---

    @Test
    fun `recent orders by updatedAt DESC`() = runBlocking {
        dao.insert(conv("c1", "older", agentId = null).copy(createdAt = 1000L, updatedAt = 1000L))
        dao.insert(conv("c2", "newer", agentId = null).copy(createdAt = 2000L, updatedAt = 2000L))
        val hits = dao.recent(10)
        assertEquals("newer", hits.first().title)
    }

    @Test
    fun `recentVisible excludes soft-deleted`() = runBlocking {
        dao.insert(conv("c1", "visible"))
        dao.insert(conv("c2", "deleted"))
        dao.softDelete("c2", System.currentTimeMillis())
        val hits = dao.recentVisible(10)
        assertEquals(1, hits.size)
        assertEquals("visible", hits.first().title)
    }

    @Test
    fun `mostRecent returns latest`() = runBlocking {
        dao.insert(conv("c1", "old").copy(updatedAt = 1000L))
        dao.insert(conv("c2", "new").copy(updatedAt = 2000L))
        assertEquals("new", dao.mostRecent()!!.title)
    }

    // --- Search (LIKE with ESCAPE) ---

    @Test
    fun `search finds conversations by title`() = runBlocking {
        dao.insert(conv("c1", "kotlin discussion"))
        dao.insert(conv("c2", "python notes"))
        val hits = dao.search("%kotlin%", limit = 10)
        assertEquals(1, hits.size)
        assertEquals("kotlin discussion", hits.first().title)
    }

    @Test
    fun `searchVisible excludes soft-deleted`() = runBlocking {
        dao.insert(conv("c1", "kotlin discussion"))
        dao.insert(conv("c2", "kotlin deleted"))
        dao.softDelete("c2", System.currentTimeMillis())
        val hits = dao.searchVisible("%kotlin%", limit = 10)
        assertEquals(1, hits.size)
        assertEquals("kotlin discussion", hits.first().title)
    }

    // --- Soft delete / restore / purge ---

    @Test
    fun `softDelete sets deletedAt`() = runBlocking {
        dao.insert(conv("c1", "to delete"))
        dao.softDelete("c1", 12345L)
        val got = dao.getById("c1")
        assertNotNull(got)
        assertEquals(12345L, got!!.deletedAt)
    }

    @Test
    fun `restore clears deletedAt`() = runBlocking {
        dao.insert(conv("c1", "deleted"))
        dao.softDelete("c1", 12345L)
        dao.restore("c1")
        assertNull(dao.getById("c1")!!.deletedAt)
    }

    @Test
    fun `purgeDeletedBefore removes only old tombstones`() = runBlocking {
        dao.insert(conv("c1", "old deleted"))
        dao.insert(conv("c2", "new deleted"))
        dao.softDelete("c1", 1000L)
        dao.softDelete("c2", 5000L)
        val purged = dao.purgeDeletedBefore(3000L)
        assertEquals(1, purged)
        assertNull(dao.getById("c1"))
        assertNotNull(dao.getById("c2"))
    }

    @Test
    fun `hard delete removes row`() = runBlocking {
        dao.insert(conv("c1", "hard delete"))
        dao.delete("c1")
        assertNull(dao.getById("c1"))
    }

    @Test
    fun `deleteAll clears table`() = runBlocking {
        dao.insert(conv("c1", "a"))
        dao.insert(conv("c2", "b"))
        dao.deleteAll()
        assertEquals(0, dao.allForExport().size)
    }

    // --- Export / import ---

    @Test
    fun `allForExport and insertAll roundtrip`() = runBlocking {
        dao.insertAll(listOf(conv("c1", "first"), conv("c2", "second")))
        val all = dao.allForExport()
        assertEquals(2, all.size)
    }

    // --- Embeddings ---

    @Test
    fun `allWithEmbeddings returns only rows with embedding`() = runBlocking {
        dao.insert(conv("c1", "no emb"))
        dao.insert(conv("c2", "has emb").copy(embedding = byteArrayOf(1.toByte(), 2.toByte(), 3.toByte())))
        val withEmb = dao.allWithEmbeddings()
        assertEquals(1, withEmb.size)
        assertEquals("has emb", withEmb.first().title)
    }

    @Test
    fun `missingEmbeddings returns rows without embedding`() = runBlocking {
        dao.insert(conv("c1", "no emb"))
        dao.insert(conv("c2", "has emb").copy(embedding = byteArrayOf(1.toByte())))
        val missing = dao.missingEmbeddings(10)
        assertEquals(1, missing.size)
        assertEquals("no emb", missing.first().title)
    }

    @Test
    fun `updateEmbedding sets embedding bytes`() = runBlocking {
        dao.insert(conv("c1", "test"))
        dao.updateEmbedding("c1", byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0xFD.toByte()))
        val got = dao.getById("c1")!!
        assertNotNull(got.embedding)
        assertEquals(0xFF.toByte(), got.embedding!![0])
    }

    @Test
    fun `count returns total`() = runBlocking {
        dao.insert(conv("c1", "a"))
        dao.insert(conv("c2", "b"))
        assertEquals(2, dao.count().first())
    }
}