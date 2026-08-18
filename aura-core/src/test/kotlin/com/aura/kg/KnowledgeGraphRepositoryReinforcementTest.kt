package com.aura.kg

import com.aura.provenance.ConversationProvenance
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Regression coverage for a REPLACE-resets-createdAt bug in
 * [KnowledgeGraphRepository.saveGraph]: `KnowledgeGraphDao.insertEdge` is
 * `OnConflictStrategy.REPLACE`, so without explicitly carrying the existing
 * row's `createdAt` forward, every re-save of the same edge silently wiped
 * out its first-seen time. That made `lastReinforced > createdAt` — the bar
 * [BeliefPromoter] uses as a proxy for "seen in more than one turn" — true
 * even on the very first sighting, because `KgEdge.createdAt` defaults at
 * parse time (in [com.aura.tools.KnowledgeGraphTool.parseEdge]) and `now` is
 * captured later, inside `saveGraph`, after an LLM round-trip.
 */
class KnowledgeGraphRepositoryReinforcementTest {

    private val dao = mockk<KnowledgeGraphDao>(relaxed = true)
    private val repo = KnowledgeGraphRepository(dao)
    private val provenance = ConversationProvenance("conv-1", 123L)

    /** In-memory table simulating Room's REPLACE conflict strategy. */
    private val store = mutableMapOf<String, EdgeEntity>()

    init {
        coEvery { dao.insertEdge(any()) } answers {
            val edge = firstArg<EdgeEntity>()
            store[edge.id] = edge
        }
        coEvery { dao.getEdge(any()) } answers {
            store[firstArg<String>()]
        }
        // saveGraph now reads its whole extraction in one query and commits it
        // in one transaction, instead of a getEdge/insertEdge pair per row. The
        // fake stands in for both so every assertion below still measures what
        // it did: what ends up stored, not how many statements got it there.
        coEvery { dao.edgesByIds(any()) } answers {
            firstArg<List<String>>().mapNotNull { store[it] }
        }
        coEvery { dao.writeGraph(any(), any()) } answers {
            secondArg<List<EdgeEntity>>().forEach { store[it.id] = it }
        }
    }

    @Test
    fun `first save leaves lastReinforced equal to createdAt`() = runTest {
        val edge = KgEdge(id = "", type = EdgeType.USES, sourceId = "user", targetId = "kotlin")

        repo.saveGraph(emptyList(), listOf(edge), provenance)

        val id = KgId.edge(EdgeType.USES, "user", "kotlin")
        val saved = store.getValue(id)
        // Nothing existed before this save, so createdAt and lastReinforced
        // are both set from the same `now` -- the bar BeliefPromoter applies
        // (lastReinforced > createdAt) must reject this row.
        assertEquals(saved.createdAt, saved.lastReinforced)
    }

    @Test
    fun `second save preserves first-seen time and advances lastReinforced`() = runTest {
        val id = KgId.edge(EdgeType.USES, "user", "kotlin")
        // Seed the fake table with the state Room would hold after a first
        // save that happened long ago. This avoids relying on two live
        // saveGraph calls landing in different milliseconds (which would be
        // flaky) while still exercising the real "carry createdAt forward"
        // logic in saveGraph for this, the second save.
        val firstSeen = 1_000L
        store[id] = EdgeEntity(
            id = id,
            type = "uses",
            sourceId = "user",
            targetId = "kotlin",
            createdAt = firstSeen,
            lastReinforced = firstSeen,
        )

        val edge = KgEdge(id = "", type = EdgeType.USES, sourceId = "user", targetId = "kotlin")
        repo.saveGraph(emptyList(), listOf(edge), provenance)

        val saved = store.getValue(id)
        assertEquals(firstSeen, saved.createdAt)
        assertTrue(saved.lastReinforced > saved.createdAt)
    }
}
