package com.aura.memory

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * That `RetrievalConfig.rerankMode` and `.trace` actually do something.
 *
 * Both were declared when the config was introduced and neither was read: the
 * rerank decision tested `rerankModel != null` inline at two call sites, and
 * `RetrievalTrace` was a data class nobody constructed. That is the same defect
 * this sweep found in `ToolPolicy.allowedScopes` — a setting that exists,
 * persists, and decides nothing — committed by the change that was documenting
 * the pattern. These tests exist so a config field cannot go back to being
 * decorative without something failing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class RetrievalTraceAndModeTest {

    private lateinit var db: MemoryDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, MemoryDatabase::class.java)
            .allowMainThreadQueries()
            .addCallback(MemoryFtsSchema.triggerCallback)
            .build()
    }

    @After
    fun tearDown() = db.close()

    private fun store(
        config: RetrievalConfig,
        reranker: MemoryReranker? = null,
    ) = MemoryStore(
        db.memoryDao(),
        FakeEmbedder(384),
        WriteGate(),
        db.memoryEditDao(),
        db.memoryFeedbackDao(),
        reranker,
        null,
        null,
        config,
    )

    private suspend fun seed(n: Int = 8) {
        val now = System.currentTimeMillis()
        repeat(n) { i ->
            db.memoryDao().insert(
                MemoryEntity(
                    id = "m$i",
                    content = "Kotlin coroutines note number $i about structured concurrency",
                    source = "user",
                    category = "fact",
                    scope = "general",
                    importance = 0.5f,
                    createdAt = now - i * 1000L,
                    accessedAt = now - i * 1000L,
                    decayScore = 1f,
                ),
            )
        }
    }

    /** A reranker that reverses, so "did it run?" is visible in the output. */
    private fun reversingReranker(): MemoryReranker {
        val r = mockk<MemoryReranker>()
        coEvery { r.rerank(any(), any(), any(), any()) } answers {
            // arg(3), not lastArg: on a suspend function mockk's argument list
            // ends with the hidden Continuation, so lastArg<Int>() casts that
            // and throws ClassCastException.
            secondArg<List<MemoryEntity>>().reversed().take(arg<Int>(3))
        }
        return r
    }

    // ---- rerankMode -------------------------------------------------------

    @Test
    fun `OFF suppresses reranking even when a model was passed`() = runBlocking {
        seed()
        val result = store(RetrievalConfig.DEFAULT.copy(rerankMode = RerankMode.OFF), reversingReranker())
            .query("Kotlin coroutines", MemoryStore.RecallOptions(limit = 5, rerankModel = "cheap:model"))

        assertTrue(result.isNotEmpty())
        // The reversing reranker would have put the last RRF result first.
        val direct = store(RetrievalConfig.DEFAULT.copy(rerankMode = RerankMode.OFF))
            .query("Kotlin coroutines", MemoryStore.RecallOptions(limit = 5))
        assertEquals(direct.map { it.id }, result.map { it.id }, "the reranker ran despite mode OFF")
    }

    @Test
    fun `LLM reranks when a model was passed`() = runBlocking {
        seed()
        val cfg = RetrievalConfig.DEFAULT.copy(rerankMode = RerankMode.LLM)
        val plain = store(cfg).query("Kotlin coroutines", MemoryStore.RecallOptions(limit = 5))
        val reranked = store(cfg, reversingReranker())
            .query("Kotlin coroutines", MemoryStore.RecallOptions(limit = 5, rerankModel = "cheap:model"))

        assertTrue(plain.isNotEmpty() && reranked.isNotEmpty())
        assertTrue(
            plain.map { it.id } != reranked.map { it.id },
            "the reranker did not run: $plain",
        )
    }

    @Test
    fun `no model means no rerank, which is what the four tool callers rely on`() = runBlocking {
        // MemoryTools, DelegateToAgentTool, CanonQueryTool and QueryTasteTool
        // pass no rerank model, deliberately: each would add 200-500ms to
        // several recalls per turn.
        seed()
        val cfg = RetrievalConfig.DEFAULT.copy(rerankMode = RerankMode.LLM)
        val plain = store(cfg).query("Kotlin coroutines", MemoryStore.RecallOptions(limit = 5))
        val noModel = store(cfg, reversingReranker())
            .query("Kotlin coroutines", MemoryStore.RecallOptions(limit = 5))

        assertEquals(plain.map { it.id }, noModel.map { it.id }, "reranking ran without a model")
    }

    // ---- trace ------------------------------------------------------------

    @Test
    fun `trace is null in production config`() = runBlocking {
        seed()
        val s = store(RetrievalConfig.DEFAULT)
        s.query("Kotlin coroutines", MemoryStore.RecallOptions(limit = 5))
        assertNull(s.lastTrace, "tracing is on by default; it costs allocations on every recall")
    }

    @Test
    fun `trace records the lexical branch with its terms and candidates`() = runBlocking {
        seed()
        val s = store(RetrievalConfig.DEFAULT.copy(trace = true))
        s.query("Kotlin coroutines", MemoryStore.RecallOptions(limit = 5))

        val t = assertNotNull(s.lastTrace)
        assertEquals(RetrievalTrace.Branch.LEXICAL, t.branch)
        assertTrue("kotlin" in t.queryTerms, "query terms were not captured: ${t.queryTerms}")
        assertTrue(t.candidateCount > 0, "candidate count was not captured")
        assertTrue(!t.rerankRan)
    }

    @Test
    fun `trace records the vector fallback branch when lexical finds nothing`() = runBlocking {
        seed()
        val s = store(RetrievalConfig.DEFAULT.copy(trace = true))
        s.query("zzzz nonexistent terminology", MemoryStore.RecallOptions(limit = 5))

        val t = assertNotNull(s.lastTrace)
        assertEquals(
            RetrievalTrace.Branch.VECTOR_FALLBACK, t.branch,
            "the branch a recall took is the first thing you want when recall goes wrong",
        )
    }

    @Test
    fun `trace counts stale vectors, which is the invisible failure it exists for`() = runBlocking {
        // A model change leaves rows whose vectors score 0. Nothing else
        // surfaces that per-query: recall just quietly gets worse.
        seed(6)
        val other = FakeEmbedder(384)
        db.memoryDao().insert(
            MemoryEntity(
                id = "stale",
                content = "Kotlin coroutines note from an older embedding model",
                source = "user",
                category = "fact",
                scope = "general",
                importance = 0.5f,
                embedding = Embedder.toBytes(other.embed("x")),
                embeddingModel = "some-older-model",
                embeddingVersion = 384,
                createdAt = System.currentTimeMillis(),
                accessedAt = System.currentTimeMillis(),
                decayScore = 1f,
            ),
        )

        val s = store(RetrievalConfig.DEFAULT.copy(trace = true))
        s.query("Kotlin coroutines", MemoryStore.RecallOptions(limit = 10))

        val t = assertNotNull(s.lastTrace)
        assertTrue(t.staleVectorCount >= 1, "a stale-model row was not counted: $t")
    }
}
