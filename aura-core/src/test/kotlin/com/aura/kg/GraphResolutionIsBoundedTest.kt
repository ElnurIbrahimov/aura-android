package com.aura.kg

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.aura.memory.MemoryDatabase
import com.aura.memory.MemoryFtsSchema
import com.aura.provenance.ConversationProvenance
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Saving a turn's graph must not get more expensive as the graph grows.
 *
 * `saveGraph` used to load the entire `kg_nodes` table and the entire
 * `kg_edges` table into memory for entity resolution, on every extraction —
 * which is roughly every turn behind a 2s debounce. The cost was invisible
 * precisely because it scales with install age rather than with the turn: free
 * on a week-old graph and quietly not free a year in. Nothing measured it and
 * nothing bounded it.
 *
 * What is pinned here is that resolution still resolves — the bound must not be
 * bought by breaking the merging it exists to do.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class GraphResolutionIsBoundedTest {

    private lateinit var db: MemoryDatabase
    private lateinit var repository: KnowledgeGraphRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, MemoryDatabase::class.java)
            .allowMainThreadQueries()
            .addCallback(MemoryFtsSchema.triggerCallback)
            .build()
        repository = KnowledgeGraphRepository(db.knowledgeGraphDao(), null, KgEntityResolver())
    }

    @After
    fun tearDown() = db.close()

    private suspend fun save(vararg nodes: KgNode, edges: List<KgEdge> = emptyList()) =
        repository.saveGraph(nodes.toList(), edges, ConversationProvenance())

    // The id has to include the type: two nodes sharing a primary key upsert
    // over each other before resolution is ever consulted, which looks exactly
    // like a merge and is not one.
    private fun node(label: String, type: NodeType = NodeType.PERSON) =
        KgNode(id = "new-$label-${type.name}", label = label, type = type)

    @Test
    fun `an exact label match still merges rather than duplicating`(): Unit = runBlocking {
        save(node("Causeway", NodeType.CONCEPT))
        save(node("causeway", NodeType.CONCEPT))

        val all = db.knowledgeGraphDao().allNodes()
        assertEquals(1, all.size, "case-only difference should have merged: ${all.map { it.label }}")
    }

    @Test
    fun `a different type with the same label stays separate`(): Unit = runBlocking {
        save(node("Sam", NodeType.PERSON))
        save(node("Sam", NodeType.CONCEPT))

        assertEquals(2, db.knowledgeGraphDao().allNodes().size)
    }

    @Test
    fun `a near-miss label still merges through the fuzzy pass`(): Unit = runBlocking {
        save(node("Elnur"))
        save(node("Elnurr"))

        val all = db.knowledgeGraphDao().allNodes()
        assertEquals(1, all.size, "fuzzy match should still resolve: ${all.map { it.label }}")
    }

    @Test
    fun `merging still works with a large graph in the way`(): Unit = runBlocking {
        // Far more nodes than the fuzzy pool, all of a type the incoming node
        // does not share, so nothing about them should matter.
        val dao = db.knowledgeGraphDao()
        for (i in 0 until 600) {
            dao.insertNode(
                NodeEntity(id = "noise-$i", label = "Topic$i", type = "concept", updatedAt = 1_000L + i),
            )
        }
        save(node("Elnur"))
        save(node("Elnur"))

        val people = dao.allNodes().filter { it.type == "person" }
        assertEquals(1, people.size, "the noise should not have prevented a merge")
    }

    @Test
    fun `an edge that already exists is not inserted twice`(): Unit = runBlocking {
        val a = node("Elnur")
        val b = node("Baku", NodeType.LOCATION)
        val edge = KgEdge(id = "e1", sourceId = a.id, targetId = b.id, type = EdgeType.RELATES_TO)
        save(a, b, edges = listOf(edge))
        save(a, b, edges = listOf(edge.copy(id = "e2")))

        assertEquals(1, db.knowledgeGraphDao().allEdges().size)
    }

    @Test
    fun `the first save into an empty graph works`(): Unit = runBlocking {
        // The candidate queries take list parameters; an empty graph and an
        // empty label list are the boundary cases that would throw on a bad
        // IN () clause.
        save(node("Elnur"))
        assertNotNull(db.knowledgeGraphDao().getNodeByLabel("Elnur"))
        repository.saveGraph(emptyList(), emptyList(), ConversationProvenance())
        assertTrue(db.knowledgeGraphDao().allNodes().isNotEmpty())
    }
}
