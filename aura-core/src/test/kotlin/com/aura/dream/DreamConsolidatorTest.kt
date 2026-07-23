package com.aura.dream

import com.aura.memory.Embedder
import com.aura.memory.MemoryEntity
import com.aura.memory.MemoryStore
import com.aura.providers.FinishReason
import com.aura.providers.ProviderChunk
import com.aura.providers.ProviderRegistry
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the [DreamConsolidator] orchestration class.
 *
 * Scope: the parts worth pinning — the cluster threshold, the
 * empty-input fast-path, and the LLM-failure safe-fallback. The
 * cosine math is verified by [MemoryStoreCosineTest] in the
 * memory package; we only assert here that [DreamConsolidator]
 * uses it correctly.
 *
 * Test doubles:
 *  - [MemoryStore] is mockk with explicit overrides for `recent()`
 *    and `cosineSimilarity()`
 *  - [ProviderRegistry.chat] returns a [Flow]&lt;[ProviderChunk]&gt;
 *    with the response text and a `stop` finish reason
 *  - [DreamConsolidationDao.insert] is a no-op
 */
class DreamConsolidatorTest {

    /**
     * A memory with a pre-baked 384-dim embedding. The vector
     * encodes a single 32-bit "tag" repeated 12 times (12 * 32 =
     * 384 dims) so similar tags produce identical vectors and
     * dissimilar tags produce orthogonal vectors. Real embeddings
     * are normalized floats; this is enough to drive cosine.
     */
    private fun mem(
        id: String,
        content: String,
        tag: Int,
    ): MemoryEntity {
        val vec = FloatArray(384) { idx ->
            if (tag <= 0) 0f
            else if ((idx / 32) == (tag - 1) % 12) 1f
            else 0f
        }
        val bytes = Embedder.toBytes(vec)
        return MemoryEntity(
            id = id,
            content = content,
            source = "user",
            category = "fact",
            embedding = bytes,
        )
    }

    private fun mockStore(mems: List<MemoryEntity>): MemoryStore = mockk<MemoryStore>(relaxed = true).also { store ->
        coEvery { store.recent(any()) } returns mems
    }

    private fun mockProvider(summaries: List<String>): ProviderRegistry = mockk<ProviderRegistry>(relaxed = true).also { reg ->
        val flows = summaries.map { text ->
            flowOf(
                ProviderChunk(text = text),
                ProviderChunk(finishReason = FinishReason.stop),
            )
        }
        coEvery { reg.chat(any(), any(), any(), any()) } returnsMany flows
    }

    private fun mockProviderFailing(): ProviderRegistry = mockk<ProviderRegistry>(relaxed = true).also { reg ->
        coEvery { reg.chat(any(), any(), any(), any()) } throws RuntimeException("network down")
    }

    private fun mockDao(): DreamConsolidationDao = mockk<DreamConsolidationDao>(relaxed = true)

    @Test
    fun `runCycle on empty memory list returns zero-everything report`() = runBlocking {
        val store = mockStore(emptyList())
        val provider = mockProvider(emptyList())
        val consolidator = DreamConsolidator(
            memoryStore = store,
            dreamDao = mockDao(),
            providerRegistry = provider,
            embedder = mockk(relaxed = true),
            crashLogger = mockk(relaxed = true),
        )
        val report = consolidator.runCycle()
        assertEquals(0, report.summariesWritten)
        assertEquals(0, report.clustersFormed)
        assertEquals(0, report.totalCharsSaved)
        assertEquals(0, report.memoriesProcessed)
    }

    @Test
    fun `runCycle with too-few memories returns no-clusters report`() = runBlocking {
        // Below MIN_MEMORIES_TO_CONSOLIDATE (3). Should return a
        // "too few" report with no work done.
        val store = mockStore(
            listOf(
                mem("a", "User likes Kotlin", 1),
                mem("b", "User is a backend engineer", 2),
            )
        )
        val provider = mockProvider(emptyList())
        val consolidator = DreamConsolidator(
            memoryStore = store,
            dreamDao = mockDao(),
            providerRegistry = provider,
            embedder = mockk(relaxed = true),
            crashLogger = mockk(relaxed = true),
        )
        val report = consolidator.runCycle()
        assertEquals(0, report.summariesWritten)
        assertEquals(0, report.clustersFormed)
        assertEquals(2, report.memoriesProcessed)
    }

    @Test
    fun `runCycle clusters similar memories and writes summaries`() = runBlocking {
        // 6 memories: 3 with tag=1 (identical vectors) → cluster A,
        // 3 with tag=2 (orthogonal) → cluster B. Both clusters
        // clear MIN_CLUSTER_SIZE=3 so both should be written.
        val store = mockStore(
            listOf(
                mem("a", "User likes Kotlin", 1),
                mem("b", "User prefers Kotlin over Java", 1),
                mem("c", "User codes in Kotlin daily", 1),
                mem("d", "Lives in Baku", 2),
                mem("e", "Based in Baku Azerbaijan", 2),
                mem("f", "Travels often to Baku", 2),
            )
        )
        val provider = mockProvider(
            listOf(
                "User prefers Kotlin and uses it daily",
                "User is based in Baku",
            )
        )
        val consolidator = DreamConsolidator(
            memoryStore = store,
            dreamDao = mockDao(),
            providerRegistry = provider,
            embedder = mockk(relaxed = true),
            crashLogger = mockk(relaxed = true),
        )
        val report = consolidator.runCycle()
        assertEquals(2, report.clustersFormed)
        assertEquals(2, report.summariesWritten)
        assertTrue("Total chars saved should be > 0", report.totalCharsSaved > 0)
    }

    @Test
    fun `runCycle writes a raw-text fallback when LLM fails`() = runBlocking {
        val store = mockStore(
            listOf(
                mem("a", "Likes Kotlin", 1),
                mem("b", "Prefers Kotlin", 1),
                mem("c", "Codes in Kotlin", 1),
            )
        )
        val consolidator = DreamConsolidator(
            memoryStore = store,
            dreamDao = mockDao(),
            providerRegistry = mockProviderFailing(),
            embedder = mockk(relaxed = true),
            crashLogger = mockk(relaxed = true),
        )
        val report = consolidator.runCycle()
        // When LLM fails, the consolidator falls back to the
        // first memory's content (truncated to 300 chars). The
        // summary IS still written — just with raw text — and
        // summariesFailedLlm=1 records the failure for the UI.
        assertEquals(1, report.clustersFormed)
        assertEquals(1, report.summariesWritten)
        assertEquals(1, report.summariesFailedLlm)
    }
}
