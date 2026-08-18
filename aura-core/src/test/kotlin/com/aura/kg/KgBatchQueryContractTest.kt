package com.aura.kg

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.aura.memory.MemoryDatabase
import com.aura.memory.MemoryFtsSchema
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
 * Real-Room coverage for the batched reads and the transactional write that
 * replaced `saveGraph`'s per-row round trips.
 *
 * The repository tests that exercise this path all mock `KnowledgeGraphDao`, so
 * they would pass over any SQL defect in these queries — which is precisely the
 * failure `MemoryDaoContractTest`'s KDoc records: *"the ESCAPE regression
 * survived 1,669 green tests because every touching test mocked the DAO."*
 * `IN (:ids)` is generated SQL with real edge cases (an empty list, ids that do
 * not exist), so it gets a real database.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class KgBatchQueryContractTest {

    private lateinit var db: MemoryDatabase
    private lateinit var dao: KnowledgeGraphDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, MemoryDatabase::class.java)
            .allowMainThreadQueries()
            .addCallback(MemoryFtsSchema.triggerCallback)
            .build()
        dao = db.knowledgeGraphDao()
    }

    @After
    fun tearDown() = db.close()

    private fun node(id: String, label: String) =
        NodeEntity(id = id, label = label, type = "skill")

    private fun edge(id: String, from: String, to: String) =
        EdgeEntity(id = id, type = "uses", sourceId = from, targetId = to)

    @Test
    fun `nodesByIds returns exactly the rows asked for`() = runBlocking {
        dao.insertNode(node("n1", "Kotlin"))
        dao.insertNode(node("n2", "Rust"))
        dao.insertNode(node("n3", "Go"))

        val found = dao.nodesByIds(listOf("n1", "n3")).associateBy { it.id }

        assertEquals(setOf("n1", "n3"), found.keys)
        assertEquals("Kotlin", found.getValue("n1").label)
    }

    @Test
    fun `nodesByIds tolerates an empty list and unknown ids`() = runBlocking {
        // saveGraph guards the empty case, but the query must not be the reason
        // that guard is load-bearing — an extraction with no nodes is ordinary.
        dao.insertNode(node("n1", "Kotlin"))

        assertTrue(dao.nodesByIds(emptyList()).isEmpty())
        assertTrue(dao.nodesByIds(listOf("nope")).isEmpty())
        assertEquals(1, dao.nodesByIds(listOf("n1", "nope")).size)
    }

    @Test
    fun `edgesByIds returns exactly the rows asked for`() = runBlocking {
        dao.insertNode(node("a", "A"))
        dao.insertNode(node("b", "B"))
        dao.insertEdge(edge("e1", "a", "b"))

        assertEquals(listOf("e1"), dao.edgesByIds(listOf("e1", "missing")).map { it.id })
        assertTrue(dao.edgesByIds(emptyList()).isEmpty())
    }

    @Test
    fun `writeGraph commits nodes and edges together`() = runBlocking {
        dao.writeGraph(
            nodes = listOf(node("a", "A"), node("b", "B")),
            edges = listOf(edge("e1", "a", "b")),
        )

        assertEquals(2, dao.nodesByIds(listOf("a", "b")).size)
        assertEquals(1, dao.edgesByIds(listOf("e1")).size)
    }

    @Test
    fun `writeGraph with nothing to write is a no-op rather than an error`() = runBlocking {
        dao.writeGraph(nodes = emptyList(), edges = emptyList())
        assertTrue(dao.nodesByIds(emptyList()).isEmpty())
    }

    @Test
    fun `writeGraph upserts nodes without orphaning their edges`() = runBlocking {
        // The defect this ordering exists to avoid, pinned: kg_edges has a
        // CASCADE relationship to kg_nodes, and `@Insert(REPLACE)` on a node
        // deletes-then-inserts, taking every edge touching it with it. `@Upsert`
        // does not. Re-saving a node the graph already has is the normal case —
        // it happens on every re-mention — so an edge lost here is an edge lost
        // on an ordinary turn.
        dao.writeGraph(listOf(node("a", "A"), node("b", "B")), listOf(edge("e1", "a", "b")))

        dao.writeGraph(listOf(node("a", "A renamed")), emptyList())

        assertEquals("A renamed", dao.nodesByIds(listOf("a")).single().label)
        assertEquals(
            "re-saving a node must not take its edges with it",
            1,
            dao.edgesByIds(listOf("e1")).size,
        )
    }
}
