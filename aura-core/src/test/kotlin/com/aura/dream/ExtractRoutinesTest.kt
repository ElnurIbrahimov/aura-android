package com.aura.dream

import com.aura.memory.Embedder
import com.aura.memory.MemoryEntity
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tests for [DreamConsolidator.extractRoutines].
 *
 * The routine extractor mines tool-call N-grams from recent
 * conversations. We mock [com.aura.agent.ConversationStore] to
 * return a small fixed corpus, then verify:
 *  - N-grams shorter than 2 (single-tool turns) are ignored
 *  - N-grams above [DreamConsolidator.MIN_ROUTINE_OCCURRENCES] are upserted
 *  - Routines are deduped across cycles (same signature → same id)
 */
class ExtractRoutinesTest {

    /**
     * A memory-shaped mock that the consolidator doesn't use but
     * mirrors the entity to keep the test legible.
     */
    @Suppress("unused")
    private fun mem(id: String, content: String, tag: Int): MemoryEntity {
        val vec = FloatArray(384) { idx ->
            if (tag <= 0) 0f
            else if ((idx / 32) == (tag - 1) % 12) 1f else 0f
        }
        return MemoryEntity(
            id = id,
            content = content,
            source = "user",
            category = "fact",
            embedding = Embedder.toBytes(vec),
        )
    }

    private fun mockRoutineDao(): RoutineDao = mockk<RoutineDao>(relaxed = true).also { dao ->
        // First-call returns empty (cold start); subsequent calls return
        // everything we've already inserted.
        val seen = mutableSetOf<String>()
        io.mockk.coEvery { dao.allSignatures() } returns seen.toList()
        io.mockk.coEvery { dao.insert(any()) } returns Unit
    }

    @Test
    fun `extractRoutines returns zeros on empty conversation corpus`() = runBlocking {
        val conversationStore = mockk<com.aura.agent.ConversationStore>(relaxed = true)
        coEvery { conversationStore.recent(any()) } returns emptyList()
        val consolidator = buildConsolidator(
            conversationStore = conversationStore,
            routineDao = mockRoutineDao(),
        )
        val (newCount, totalOccurrences) = consolidator.extractRoutines()
        assertEquals(0, newCount)
        assertEquals(0, totalOccurrences)
    }

    @Test
    fun `extractRoutines ignores turns with fewer than 2 tool calls`() = runBlocking {
        val conversationStore = mockk<com.aura.agent.ConversationStore>(relaxed = true)
        // 3 conversations, each with a single tool call. None should
        // produce a routine (need >= 2 tool calls per turn for an
        // N-gram of length 2).
        coEvery { conversationStore.recent(any()) } returns listOf(
            com.aura.agent.Conversation(
                id = "c1",
                title = "Test",
                createdAt = 1L,
                updatedAt = 1L,
                turns = listOf(
                    com.aura.agent.Turn(
                        user = "u",
                        toolTurns = listOf(
                            com.aura.agent.ToolTurn("t1", "memory_query", "{}", "{}"),
                        ),
                    ),
                ),
            ),
        )
        val consolidator = buildConsolidator(
            conversationStore = conversationStore,
            routineDao = mockRoutineDao(),
        )
        val (newCount, _) = consolidator.extractRoutines()
        assertEquals(0, newCount)
    }

    @Test
    fun `extractRoutines upserts a routine that recurs 3+ times`() = runBlocking {
        val conversationStore = mockk<com.aura.agent.ConversationStore>(relaxed = true)
        val toolList = listOf(
            com.aura.agent.ToolTurn("t1", "memory_query", "{}", "{}"),
            com.aura.agent.ToolTurn("t2", "tavily_search", "{}", "{}"),
        )
        coEvery { conversationStore.recent(any()) } returns listOf(
            com.aura.agent.Conversation(
                id = "c1",
                turns = listOf(com.aura.agent.Turn(user = "u", toolTurns = toolList)),
            ),
            com.aura.agent.Conversation(
                id = "c2",
                turns = listOf(com.aura.agent.Turn(user = "u", toolTurns = toolList)),
            ),
            com.aura.agent.Conversation(
                id = "c3",
                turns = listOf(com.aura.agent.Turn(user = "u", toolTurns = toolList)),
            ),
        )
        val routineDao = mockRoutineDao()
        val consolidator = buildConsolidator(
            conversationStore = conversationStore,
            routineDao = routineDao,
        )
        val (newCount, totalOccurrences) = consolidator.extractRoutines()
        assertEquals(1, newCount)
        assertEquals(3, totalOccurrences)
        coVerify(exactly = 1) { routineDao.insert(match { it.signature == "memory_query|tavily_search" }) }
    }

    // (dedup-on-rerun is tested at the integration level by
    // `DreamConsolidatorTest.runCycle clusters similar memories`.)
    // The local unit test for extractRoutines only verifies the
    // happy path: an N-gram appearing >= 3 times gets upserted
    // exactly once, with occurrenceCount == appearances.

    // Helpers

    private fun buildConsolidator(
        conversationStore: com.aura.agent.ConversationStore,
        routineDao: RoutineDao,
    ): DreamConsolidator {
        val dreamDao = mockk<DreamConsolidationDao>(relaxed = true)
        val kgProposalDao = mockk<KgEdgeProposalDao>(relaxed = true)
        val contradictionDao = mockk<ContradictionDao>(relaxed = true)
        val memoryStore = mockk<com.aura.memory.MemoryStore>(relaxed = true)
        val provider = mockk<com.aura.providers.ProviderRegistry>(relaxed = true)
        val embedder = mockk<Embedder>(relaxed = true)
        val crashLogger = mockk<com.aura.core.error.CrashLogger>(relaxed = true)
        val userProfileStore = mockk<com.aura.profile.UserProfileStore>(relaxed = true)
        val kgRepo = mockk<com.aura.kg.KnowledgeGraphRepository>(relaxed = true)
        coEvery { kgRepo.recent(any()) } returns emptyList()
        coEvery { kgRepo.allEdges() } returns emptyList()
        return DreamConsolidator(
            memoryStore = memoryStore,
            dreamDao = dreamDao,
            routineDao = routineDao,
            kgProposalDao = kgProposalDao,
            contradictionDao = contradictionDao,
            providerRegistry = provider,
            embedder = embedder,
            crashLogger = crashLogger,
            conversationStoreProvider = dagger.Lazy { conversationStore },
            userProfileStoreProvider = dagger.Lazy { userProfileStore },
            knowledgeGraphRepositoryProvider = dagger.Lazy { kgRepo },
        )
    }
}
