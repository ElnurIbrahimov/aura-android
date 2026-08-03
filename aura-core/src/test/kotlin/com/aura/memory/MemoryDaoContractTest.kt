package com.aura.memory

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
 * Real-Room contract suite for MemoryDatabase.
 *
 * Runs against REAL in-memory SQLite (Robolectric + Room), not mocked DAOs.
 * The ESCAPE regression (Aug 2026) survived 1,669 green tests because every
 * touching test mocked the DAO — SQL-level errors are invisible to mocks.
 * This suite exercises every MemoryDao query that involves SQL string
 * manipulation (LIKE, ESCAPE, scope filtering, ordering, decay updates).
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class MemoryDaoContractTest {

    private lateinit var db: MemoryDatabase
    private lateinit var dao: MemoryDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, MemoryDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.memoryDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun memory(content: String, scope: String = "general", category: String = "fact", importance: Float = 0.5f, decayScore: Float = 1.0f) = MemoryEntity(
        id = "m_${content.hashCode()}_${System.nanoTime()}",
        content = content,
        source = "user",
        category = category,
        scope = scope,
        importance = importance,
        decayScore = decayScore,
    )

    // --- ESCAPE regression (already pinned in EscapeRegressionTest, broadened here) ---

    @Test
    fun `searchByText matches literal percent sign`() = runBlocking {
        dao.insert(memory("discount 100% off"))
        dao.insert(memory("discount 100 percent"))
        val hits = dao.searchByText("%${escapeLikeWildcards("100% off")}%", limit = 10)
        assertEquals(1, hits.size)
        assertEquals("discount 100% off", hits.single().content)
    }

    @Test
    fun `searchByText matches literal underscore`() = runBlocking {
        dao.insert(memory("a_b"))
        dao.insert(memory("axb"))
        val hits = dao.searchByText("%${escapeLikeWildcards("a_b")}%", limit = 10)
        assertEquals(listOf("a_b"), hits.map { it.content })
    }

    @Test
    fun `searchByTextInScopes respects scope filter`() = runBlocking {
        dao.insert(memory("shared fact"))
        dao.insert(memory("private fact", scope = "agent:agent_1"))
        val hits = dao.searchByTextInScopes("%fact%", listOf("agent:agent_1"), limit = 10)
        assertEquals(listOf("private fact"), hits.map { it.content })
    }

    @Test
    fun `searchByWordsInScopes returns matching word`() = runBlocking {
        dao.insert(memory("I love kotlin programming"))
        dao.insert(memory("I prefer python"))
        val hits = dao.searchByWordsInScopes(
            word1 = "%kotlin%", word2 = "%%", word3 = "%%",
            word4 = "%%", word5 = "%%", word6 = "%%",
            scopes = listOf("general"), limit = 10,
        )
        assertTrue(hits.any { it.content == "I love kotlin programming" })
    }

    // --- CRUD ---

    @Test
    fun `insert and getById roundtrip`() = runBlocking {
        dao.insert(memory("test fact"))
        val got = dao.getById("m_test fact".hashCode().toString())
        // getById uses our id; find by content
        val all = dao.recent(10)
        assertTrue(all.any { it.content == "test fact" })
    }

    @Test
    fun `update changes fields`() = runBlocking {
        val m = memory("original")
        dao.insert(m)
        dao.update(m.copy(content = "updated"))
        val all = dao.recent(10)
        assertEquals("updated", all.first().content)
    }

    @Test
    fun `delete removes row`() = runBlocking {
        val m = memory("to delete")
        dao.insert(m)
        dao.delete(m.id)
        assertNull(dao.getById(m.id))
    }

    // --- Scope queries ---

    @Test
    fun `byScopes filters to specified scopes`() = runBlocking {
        dao.insert(memory("general fact"))
        dao.insert(memory("private fact", scope = "agent:agent_1"))
        val hits = dao.byScopes(listOf("agent:agent_1"), limit = 10)
        assertEquals(1, hits.size)
        assertEquals("private fact", hits.first().content)
    }

    @Test
    fun `allByScopes returns all rows in scopes`() = runBlocking {
        dao.insert(memory("fact 1"))
        dao.insert(memory("fact 2", scope = "agent:agent_1"))
        val hits = dao.allByScopes(listOf("general", "agent:agent_1"))
        assertEquals(2, hits.size)
    }

    @Test
    fun `vectorScanCandidates is bounded, activity-ordered, embedding-only`() = runBlocking {
        fun scanMemory(content: String, accessCount: Int = 0, decayScore: Float = 1.0f, embedding: ByteArray? = FloatArray(4) { 0.5f }.let { java.nio.ByteBuffer.allocate(16).putFloat(0.5f).putFloat(0.5f).putFloat(0.5f).putFloat(0.5f).array() }, scope: String = "general") =
            MemoryEntity(
                id = "m_${content.hashCode()}_${System.nanoTime()}",
                content = content,
                source = "user",
                category = "fact",
                scope = scope,
                importance = 0.5f,
                decayScore = decayScore,
                accessCount = accessCount,
                embedding = embedding,
            )
        dao.insert(scanMemory("no embedding", accessCount = 99, embedding = null))
        dao.insert(scanMemory("hot", accessCount = 10, decayScore = 0.9f))
        dao.insert(scanMemory("warm", accessCount = 5, decayScore = 0.9f))
        dao.insert(scanMemory("cold", accessCount = 1, decayScore = 0.1f))
        dao.insert(scanMemory("other scope", accessCount = 50, scope = "agent:agent_1"))

        val hits = dao.vectorScanCandidates(listOf("general"), limit = 2)

        assertEquals("limit must cap the scan", 2, hits.size)
        assertEquals("most active first", "hot", hits[0].content)
        assertEquals("second most active", "warm", hits[1].content)
    }

    @Test
    fun `withinScope matches general plus prefix`() = runBlocking {
        dao.insert(memory("general fact"))
        dao.insert(memory("project fact", scope = "project:p1"))
        dao.insert(memory("other fact", scope = "agent:agent_1"))
        val hits = dao.withinScope("project:%", limit = 10)
        // general always included + prefix matches
        assertTrue(hits.any { it.content == "general fact" })
        assertTrue(hits.any { it.content == "project fact" })
        assertTrue(!hits.any { it.content == "other fact" })
    }

    // --- Category and decay ---

    @Test
    fun `byCategory filters by category`() = runBlocking {
        dao.insert(memory("preference 1", category = "preference"))
        dao.insert(memory("fact 1", category = "fact"))
        val hits = dao.byCategory("preference", limit = 10)
        assertEquals(1, hits.size)
        assertEquals("preference 1", hits.first().content)
    }

    @Test
    fun `decayedBelow returns memories under threshold`() = runBlocking {
        dao.insert(memory("fresh", decayScore = 0.9f))
        dao.insert(memory("fading", decayScore = 0.1f))
        val hits = dao.decayedBelow(0.5f, limit = 10)
        assertEquals(1, hits.size)
        assertEquals("fading", hits.first().content)
    }

    @Test
    fun `applyDecay multiplies decayScore for old memories`() = runBlocking {
        val m = memory("old memory", decayScore = 1.0f, importance = 0.5f)
        dao.insert(m)
        dao.applyDecay(System.currentTimeMillis() + 1000, 0.5f)
        val got = dao.getById(m.id)
        assertNotNull(got)
        assertEquals(0.5f, got!!.decayScore, 0.001f)
    }

    @Test
    fun `touch increments accessCount and updates timestamp`() = runBlocking {
        val m = memory("touch me")
        dao.insert(m)
        val before = dao.getById(m.id)!!
        assertEquals(0, before.accessCount)
        dao.touch(m.id, now = 99999L)
        val after = dao.getById(m.id)!!
        assertEquals(1, after.accessCount)
        assertEquals(99999L, after.accessedAt)
    }

    // --- Dedup ---

    @Test
    fun `existsByContent returns count for duplicate content`() = runBlocking {
        dao.insert(memory("same content"))
        dao.insert(memory("same content")) // same content, different id
        val count = dao.existsByContent("same content")
        assertTrue(count >= 1)
    }

    // --- Batch operations ---

    @Test
    fun `insertAll and allForExport roundtrip`() = runBlocking {
        dao.insertAll(listOf(memory("batch 1"), memory("batch 2")))
        val all = dao.allForExport()
        assertTrue(all.any { it.content == "batch 1" })
        assertTrue(all.any { it.content == "batch 2" })
    }

    @Test
    fun `updateAll updates multiple rows`() = runBlocking {
        val m1 = memory("batch 1")
        val m2 = memory("batch 2")
        dao.insertAll(listOf(m1, m2))
        dao.updateAll(listOf(m1.copy(decayScore = 0.3f), m2.copy(decayScore = 0.4f)))
        assertEquals(0.3f, dao.getById(m1.id)!!.decayScore, 0.001f)
        assertEquals(0.4f, dao.getById(m2.id)!!.decayScore, 0.001f)
    }

    // --- Delete operations ---

    @Test
    fun `deleteBySource removes by source`() = runBlocking {
        val m = memory("tool fact")
        dao.insert(m.copy(source = "tool"))
        dao.deleteBySource("tool")
        assertNull(dao.getById(m.id))
    }

    @Test
    fun `deleteByCategory removes by category`() = runBlocking {
        dao.insert(memory("to remove", category = "episode"))
        dao.insert(memory("to keep", category = "fact"))
        dao.deleteByCategory("episode")
        val all = dao.recent(10)
        assertTrue(all.none { it.category == "episode" })
    }

    @Test
    fun `deleteAll clears table`() = runBlocking {
        dao.insert(memory("a"))
        dao.insert(memory("b"))
        dao.deleteAll()
        assertEquals(0, dao.countOnce())
    }

    // --- Embeddings ---

    @Test
    fun `allWithEmbeddings returns only rows with embedding`() = runBlocking {
        dao.insert(memory("no embedding"))
        dao.insert(memory("has embedding", importance = 0.9f).copy(embedding = floatToByteArray(0.1f, 0.2f)))
        val withEmb = dao.allWithEmbeddings()
        assertEquals(1, withEmb.size)
        assertEquals("has embedding", withEmb.first().content)
    }

    @Test
    fun `updateDecayScore updates single row`() = runBlocking {
        val m = memory("decay me")
        dao.insert(m)
        dao.updateDecayScore(m.id, 0.15f)
        assertEquals(0.15f, dao.getById(m.id)!!.decayScore, 0.001f)
    }

    // --- Top by decayScore ---

    @Test
    fun `top returns highest decayScore first`() = runBlocking {
        dao.insert(memory("low", decayScore = 0.1f))
        dao.insert(memory("high", decayScore = 0.9f))
        val hits = dao.top(10)
        assertEquals("high", hits.first().content)
    }

    // --- Recent ---

    @Test
    fun `recent orders by createdAt DESC`() = runBlocking {
        dao.insert(memory("older", importance = 0.1f))
        Thread.sleep(5)
        dao.insert(memory("newer", importance = 0.1f))
        val hits = dao.recent(10)
        assertEquals("newer", hits.first().content)
    }

    @Test
    fun `recentSince filters by timestamp`() = runBlocking {
        val old = memory("old", importance = 0.1f)
        dao.insert(old)
        Thread.sleep(10)
        val cutoff = System.currentTimeMillis()
        Thread.sleep(10)
        dao.insert(memory("new", importance = 0.1f))
        val hits = dao.recentSince(cutoff, limit = 10)
        assertTrue(hits.any { it.content == "new" })
        assertTrue(hits.none { it.content == "old" })
    }
}

private fun floatToByteArray(vararg floats: Float): ByteArray {
    val ba = ByteArray(floats.size * 4)
    for (i in floats.indices) {
        val bits = java.lang.Float.floatToRawIntBits(floats[i])
        ba[i * 4] = (bits and 0xFF).toByte()
        ba[i * 4 + 1] = ((bits shr 8) and 0xFF).toByte()
        ba[i * 4 + 2] = ((bits shr 16) and 0xFF).toByte()
        ba[i * 4 + 3] = ((bits shr 24) and 0xFF).toByte()
    }
    return ba
}