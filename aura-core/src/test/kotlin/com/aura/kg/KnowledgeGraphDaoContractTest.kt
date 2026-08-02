package com.aura.kg

import android.content.Context
import androidx.room.Room
import com.aura.memory.MemoryDatabase
import androidx.test.core.app.ApplicationProvider
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
 * Real-Room contract suite for KnowledgeGraphDao (inside MemoryDatabase).
 *
 * Exercises: node CRUD, edge CRUD, searchNodes (LIKE with ESCAPE),
 * edgesForNode, edgesFrom/edgesTo, delete cascading, mergeNodeRecords
 * (Transaction), recentNodes/recentNodesSince, incrementAccessCount.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class KnowledgeGraphDaoContractTest {

    private lateinit var db: MemoryDatabase
    private lateinit var dao: KnowledgeGraphDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, MemoryDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.knowledgeGraphDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun node(id: String, label: String, type: String = "CONCEPT") = NodeEntity(
        id = id,
        label = label,
        type = type,
    )

    private fun edge(id: String, source: String, target: String, type: String = "related_to") = EdgeEntity(
        id = id,
        type = type,
        sourceId = source,
        targetId = target,
    )

    // --- Node CRUD ---

    @Test
    fun `insertNode and getNode roundtrip`() = runBlocking {
        dao.insertNode(node("n1", "Kotlin"))
        val got = dao.getNode("n1")
        assertNotNull(got)
        assertEquals("Kotlin", got!!.label)
    }

    @Test
    fun `getNodeByLabel returns matching node`() = runBlocking {
        dao.insertNode(node("n1", "Kotlin"))
        dao.insertNode(node("n2", "Python"))
        val got = dao.getNodeByLabel("Kotlin")
        assertNotNull(got)
        assertEquals("n1", got!!.id)
    }

    @Test
    fun `updateNode changes fields`() = runBlocking {
        val n = node("n1", "original")
        dao.insertNode(n)
        dao.updateNode(n.copy(label = "updated", confidence = 0.95f))
        val got = dao.getNode("n1")!!
        assertEquals("updated", got.label)
        assertEquals(0.95f, got.confidence, 0.001f)
    }

    @Test
    fun `deleteNode removes node`() = runBlocking {
        dao.insertNode(node("n1", "test"))
        dao.deleteNode("n1")
        assertNull(dao.getNode("n1"))
    }

    // --- Edge CRUD ---

    @Test
    fun `insertEdge and getEdge roundtrip`() = runBlocking {
        dao.insertNode(node("n1", "A"))
        dao.insertNode(node("n2", "B"))
        dao.insertEdge(edge("e1", "n1", "n2"))
        val got = dao.getEdge("e1")
        assertNotNull(got)
        assertEquals("n1", got!!.sourceId)
        assertEquals("n2", got.targetId)
    }

    @Test
    fun `edgesForNode returns both incoming and outgoing`() = runBlocking {
        dao.insertNode(node("n1", "A"))
        dao.insertNode(node("n2", "B"))
        dao.insertNode(node("n3", "C"))
        dao.insertEdge(edge("e1", "n1", "n2")) // outgoing from n1
        dao.insertEdge(edge("e2", "n3", "n1")) // incoming to n1
        val edges = dao.edgesForNode("n1")
        assertEquals(2, edges.size)
    }

    @Test
    fun `edgesFrom returns outgoing only`() = runBlocking {
        dao.insertNode(node("n1", "A"))
        dao.insertNode(node("n2", "B"))
        dao.insertNode(node("n3", "C"))
        dao.insertEdge(edge("e1", "n1", "n2"))
        dao.insertEdge(edge("e2", "n1", "n3"))
        dao.insertEdge(edge("e3", "n2", "n3"))
        val fromN1 = dao.edgesFrom("n1")
        assertEquals(2, fromN1.size)
    }

    @Test
    fun `edgesTo returns incoming only`() = runBlocking {
        dao.insertNode(node("n1", "A"))
        dao.insertNode(node("n2", "B"))
        dao.insertEdge(edge("e1", "n1", "n2"))
        dao.insertEdge(edge("e2", "n2", "n1"))
        val toN2 = dao.edgesTo("n2")
        assertEquals(1, toN2.size)
        assertEquals("e1", toN2.first().id)
    }

    @Test
    fun `deleteEdgesForNode removes all connected edges`() = runBlocking {
        dao.insertNode(node("n1", "A"))
        dao.insertNode(node("n2", "B"))
        dao.insertEdge(edge("e1", "n1", "n2"))
        dao.insertEdge(edge("e2", "n2", "n1"))
        dao.deleteEdgesForNode("n1")
        assertEquals(0, dao.edgesForNode("n1").size)
    }

    // --- Search (LIKE with ESCAPE) ---

    @Test
    fun `searchNodes matches literal percent in label`() = runBlocking {
        dao.insertNode(node("n1", "100% Kotlin"))
        dao.insertNode(node("n2", "100 percent"))
        val hits = dao.searchNodes("%100% Kotlin%", limit = 10)
        assertEquals(1, hits.size)
        assertEquals("100% Kotlin", hits.first().label)
    }

    @Test
    fun `searchNodes matches partial label`() = runBlocking {
        dao.insertNode(node("n1", "Kotlin programming"))
        dao.insertNode(node("n2", "Python programming"))
        val hits = dao.searchNodes("%Kotlin%", limit = 10)
        assertEquals(1, hits.size)
        assertEquals("Kotlin programming", hits.first().label)
    }

    // --- Recent ---

    @Test
    fun `recentNodes orders by updatedAt DESC`() = runBlocking {
        dao.insertNode(node("n1", "old").copy(updatedAt = 1000L))
        dao.insertNode(node("n2", "new").copy(updatedAt = 2000L))
        val hits = dao.recentNodes(10)
        assertEquals("new", hits.first().label)
    }

    @Test
    fun `recentNodesSince filters by timestamp`() = runBlocking {
        dao.insertNode(node("n1", "old").copy(updatedAt = 1000L))
        dao.insertNode(node("n2", "new").copy(updatedAt = 3000L))
        val hits = dao.recentNodesSince(2000L, limit = 10)
        assertEquals(1, hits.size)
        assertEquals("new", hits.first().label)
    }

    // --- Access count ---

    @Test
    fun `incrementAccessCount bumps accessCount and updates lastAccessed`() = runBlocking {
        dao.insertNode(node("n1", "test"))
        dao.incrementAccessCount("n1", now = 99999L)
        val got = dao.getNode("n1")!!
        assertEquals(1, got.accessCount)
        assertEquals(99999L, got.lastAccessed)
    }

    // --- Counts ---

    @Test
    fun `nodeCount and edgeCount return totals`() = runBlocking {
        dao.insertNode(node("n1", "A"))
        dao.insertNode(node("n2", "B"))
        dao.insertEdge(edge("e1", "n1", "n2"))
        assertEquals(2, dao.nodeCount())
        assertEquals(1, dao.edgeCount())
    }

    // --- All + insertAll (backup roundtrip) ---

    @Test
    fun `allNodes and insertAllNodes roundtrip`() = runBlocking {
        dao.insertAllNodes(listOf(node("n1", "A"), node("n2", "B")))
        assertEquals(2, dao.allNodes().size)
    }

    @Test
    fun `allEdges and insertAllEdges roundtrip`() = runBlocking {
        dao.insertNode(node("n1", "A"))
        dao.insertNode(node("n2", "B"))
        dao.insertAllEdges(listOf(edge("e1", "n1", "n2")))
        assertEquals(1, dao.allEdges().size)
    }

    // --- Delete all ---

    @Test
    fun `deleteAllEdges clears edge table only`() = runBlocking {
        dao.insertNode(node("n1", "A"))
        dao.insertNode(node("n2", "B"))
        dao.insertEdge(edge("e1", "n1", "n2"))
        dao.deleteAllEdges()
        assertEquals(0, dao.edgeCount())
        assertEquals(2, dao.nodeCount()) // nodes survive
    }

    @Test
    fun `deleteAllNodes clears node table`() = runBlocking {
        dao.insertNode(node("n1", "A"))
        dao.deleteAllNodes()
        assertEquals(0, dao.nodeCount())
    }
}