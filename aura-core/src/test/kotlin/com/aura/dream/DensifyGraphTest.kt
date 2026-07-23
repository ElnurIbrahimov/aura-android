package com.aura.dream

import com.aura.kg.EdgeType
import com.aura.kg.KgEdge
import com.aura.kg.KgNode
import com.aura.kg.KnowledgeGraphRepository
import com.aura.kg.NodeType
import com.aura.memory.Embedder
import com.aura.memory.MemoryStore
import com.aura.profile.UserProfileStore
import com.aura.providers.ProviderRegistry
import com.aura.core.error.CrashLogger
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tests for [DreamConsolidator.densifyGraph].
 *
 * The densify-graph phase proposes new edges between existing
 * knowledge-graph nodes whose labels are similar (Jaccard on the
 * label's token set, with a minimum threshold). We mock
 * [KnowledgeGraphRepository] to return a fixed node set and verify:
 *  - Two nodes whose labels share a token produce a proposal
 *  - Already-connected pairs are skipped (no duplicate proposals)
 *  - The function returns 0 when the corpus is too small or has
 *    no token overlap
 */
class DensifyGraphTest {

    @Test
    fun `densifyGraph returns 0 when fewer than 2 nodes exist`() = runBlocking {
        val kg = mockk<KnowledgeGraphRepository>(relaxed = true)
        coEvery { kg.recent(any()) } returns emptyList()
        coEvery { kg.allEdges() } returns emptyList()
        val consolidator = buildConsolidator(kg = kg)
        val added = consolidator.densifyGraph()
        assertEquals(0, added)
    }

    @Test
    fun `densifyGraph proposes a new edge for two similar nodes`() = runBlocking {
        val kg = mockk<KnowledgeGraphRepository>(relaxed = true)
        // "Kotlin language" vs "Kotlin language history" - 2 shared
        // tokens of 3 total = Jaccard 0.67, above the 0.5 threshold.
        coEvery { kg.recent(50) } returns listOf(
            KgNode(id = "n1", label = "Kotlin language", type = NodeType.CONCEPT),
            KgNode(id = "n2", label = "Kotlin language history", type = NodeType.CONCEPT),
        )
        coEvery { kg.allEdges() } returns emptyList()
        val kgProposalDao = mockk<KgEdgeProposalDao>(relaxed = true)
        val consolidator = buildConsolidator(kg = kg, kgProposalDao = kgProposalDao)
        val added = consolidator.densifyGraph()
        assertEquals(1, added)
        coVerify(exactly = 1) {
            kgProposalDao.insertAll(match { it.isNotEmpty() && it.first().fromNodeId in setOf("n1", "n2") })
        }
    }

    @Test
    fun `densifyGraph skips pairs that already have an edge`() = runBlocking {
        val kg = mockk<KnowledgeGraphRepository>(relaxed = true)
        coEvery { kg.recent(50) } returns listOf(
            KgNode(id = "n1", label = "Kotlin language", type = NodeType.CONCEPT),
            KgNode(id = "n2", label = "Kotlin language history", type = NodeType.CONCEPT),
        )
        // Edge already exists between n1 and n2.
        coEvery { kg.allEdges() } returns listOf(
            KgEdge(
                id = "e1",
                type = EdgeType.RELATES_TO,
                sourceId = "n1",
                targetId = "n2",
            ),
        )
        val kgProposalDao = mockk<KgEdgeProposalDao>(relaxed = true)
        val consolidator = buildConsolidator(kg = kg, kgProposalDao = kgProposalDao)
        val added = consolidator.densifyGraph()
        assertEquals(0, added)
        coVerify(exactly = 0) { kgProposalDao.insertAll(any()) }
    }

    @Test
    fun `densifyGraph returns 0 when no two nodes share a token`() = runBlocking {
        val kg = mockk<KnowledgeGraphRepository>(relaxed = true)
        coEvery { kg.recent(50) } returns listOf(
            KgNode(id = "n1", label = "Baku city", type = NodeType.LOCATION),
            KgNode(id = "n2", label = "Bicycle mechanics", type = NodeType.CONCEPT),
        )
        coEvery { kg.allEdges() } returns emptyList()
        val kgProposalDao = mockk<KgEdgeProposalDao>(relaxed = true)
        val consolidator = buildConsolidator(kg = kg, kgProposalDao = kgProposalDao)
        val added = consolidator.densifyGraph()
        // {"baku","city"} vs {"bicycle","mechanics"} -> intersection = 0
        assertEquals(0, added)
    }

    // Helpers

    private fun buildConsolidator(
        kg: KnowledgeGraphRepository,
        kgProposalDao: KgEdgeProposalDao = mockk(relaxed = true),
    ): DreamConsolidator = DreamConsolidator(
        memoryStore = mockk<MemoryStore>(relaxed = true),
        dreamDao = mockk<DreamConsolidationDao>(relaxed = true),
        routineDao = mockk<RoutineDao>(relaxed = true),
        kgProposalDao = kgProposalDao,
        contradictionDao = mockk<ContradictionDao>(relaxed = true),
        providerRegistry = mockk<ProviderRegistry>(relaxed = true),
        embedder = mockk<Embedder>(relaxed = true),
        crashLogger = mockk<CrashLogger>(relaxed = true),
        conversationStoreProvider = dagger.Lazy {
            mockk<com.aura.agent.ConversationStore>(relaxed = true)
        },
        userProfileStoreProvider = dagger.Lazy {
            mockk<UserProfileStore>(relaxed = true)
        },
        knowledgeGraphRepositoryProvider = dagger.Lazy { kg },
    )
}
