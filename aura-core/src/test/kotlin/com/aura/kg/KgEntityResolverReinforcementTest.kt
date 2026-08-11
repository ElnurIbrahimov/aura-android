package com.aura.kg

import com.aura.provenance.ConversationProvenance
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * That turning [KgEntityResolver] on does not switch belief promotion off.
 *
 * The resolver's whole job is to *not* insert something it has seen before, and
 * before this wave it expressed that by dropping duplicate edges on the floor.
 * Wiring it into [KnowledgeGraphRepository] in that shape would have meant no
 * edge's `lastReinforced` ever moved past its `createdAt` — which is precisely
 * the bar `BeliefPromoter.qualifies()` applies — so no belief would ever be
 * promoted again, silently, with every test still green.
 *
 * [KnowledgeGraphRepositoryReinforcementTest] covers the same invariant with
 * the resolver absent. This one covers it with the resolver present, which is
 * the configuration production actually runs.
 */
class KgEntityResolverReinforcementTest {

    private val dao = mockk<KnowledgeGraphDao>(relaxed = true)
    private val repo = KnowledgeGraphRepository(dao, entityResolver = KgEntityResolver())
    private val provenance = ConversationProvenance("conv-1", 123L)

    /** In-memory tables standing in for Room's upsert behaviour. */
    private val edges = mutableMapOf<String, EdgeEntity>()
    private val nodes = mutableMapOf<String, NodeEntity>()

    init {
        coEvery { dao.insertEdge(any()) } answers {
            val edge = firstArg<EdgeEntity>()
            edges[edge.id] = edge
        }
        coEvery { dao.getEdge(any()) } answers { edges[firstArg<String>()] }
        coEvery { dao.allEdges() } answers { edges.values.toList() }
        coEvery { dao.insertNode(any()) } answers {
            val node = firstArg<NodeEntity>()
            nodes[node.id] = node
        }
        coEvery { dao.getNode(any()) } answers { nodes[firstArg<String>()] }
        coEvery { dao.allNodes() } answers { nodes.values.toList() }
    }

    @Test
    fun `an edge the resolver has seen before is still written, with its first-seen time kept`() = runTest {
        val id = KgId.edge(EdgeType.USES, "user", "kotlin")
        val firstSeen = 1_000L
        // The state Room would hold after a save that happened long ago. Seeding
        // rather than calling saveGraph twice keeps the assertion off the clock.
        edges[id] = EdgeEntity(
            id = id,
            type = "uses",
            sourceId = "user",
            targetId = "kotlin",
            createdAt = firstSeen,
            lastReinforced = firstSeen,
        )

        // Same triple again. The resolver classifies this as already-seen, which
        // is the case that used to vanish.
        val edge = KgEdge(id = "", type = EdgeType.USES, sourceId = "user", targetId = "kotlin")
        repo.saveGraph(emptyList(), listOf(edge), provenance)

        val saved = edges.getValue(id)
        assertEquals(firstSeen, saved.createdAt)
        assertTrue(
            saved.lastReinforced > saved.createdAt,
            "a re-asserted edge must clear BeliefPromoter.qualifies(); got " +
                "lastReinforced=${saved.lastReinforced} createdAt=${saved.createdAt}",
        )
    }

    @Test
    fun `a re-mentioned node advances updatedAt without losing its stored label`() = runTest {
        nodes["n1"] = NodeEntity(
            id = "n1",
            label = "Kotlin",
            type = "concept",
            confidence = 0.5f,
            createdAt = 1_000L,
            updatedAt = 1_000L,
        )

        // Same entity, different casing — the resolver merges it onto n1 and
        // reports it as a touch rather than an insert.
        val mention = KgNode(id = "", label = "kotlin", type = NodeType.CONCEPT)
        repo.saveGraph(listOf(mention), emptyList(), provenance)

        val stored = nodes.getValue("n1")
        assertTrue(
            stored.updatedAt > 1_000L,
            "recentNodesSince drives the morning brief's \"facts learned\" section and reads updatedAt",
        )
        assertEquals("Kotlin", stored.label)
        assertEquals(0.8f, stored.confidence)
    }
}
