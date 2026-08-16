package com.aura.memory

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.aura.provenance.ConversationProvenance
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
 * That recall actually writes labels — from every branch, and only for real turns.
 *
 * This is the seam where the whole feature ships doing nothing. `RetrievalLabelDaoTest`
 * proves the table behaves; a judge, an export script and a metric can all be
 * built and unit-tested green against hand-constructed rows while production
 * writes not one. So this drives a **real** `MemoryStore.query` against real
 * in-memory SQLite and asserts rows appear, which is the only claim that matters.
 *
 * Both branches are covered on purpose. `MemoryStore.query` has two returns, and
 * the comment above its vector-fallback branch records that this exact function
 * already shipped a half-wired version of exactly this problem —
 * `evolutionHooks.onMemoryRecalled` fired on the lexical path and never on the
 * fallback, so recall telemetry saw BM25 hits and nothing else. Labels harvested
 * from one branch only would be worse than that was: they would bias the corpus
 * toward the recalls a hash embedder already handles, which is the exact
 * comparison Gate B exists to make.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class RetrievalLabelHarvestIsWiredTest {

    private lateinit var db: MemoryDatabase
    private lateinit var store: MemoryStore
    private lateinit var labels: RetrievalLabelDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, MemoryDatabase::class.java)
            .allowMainThreadQueries()
            .addCallback(MemoryFtsSchema.triggerCallback)
            .build()
        labels = db.retrievalLabelDao()
        store = MemoryStore(
            db.memoryDao(),
            FakeEmbedder(384),
            WriteGate(),
            db.memoryEditDao(),
            db.memoryFeedbackDao(),
            null,
            null,
            null,
            RetrievalConfig.DEFAULT,
            null,
            // The real store over the same database, not a mock. A mock would
            // prove the call happened; this proves rows land.
            RetrievalLabelStore(db.retrievalLabelDao()),
        )
    }

    @After
    fun tearDown() = db.close()

    /**
     * Seeded **with** an embedding, which is not decoration. A row with a null
     * embedding gives the vector-fallback branch nothing to score, so it returns
     * empty and the fallback case below cannot exercise the branch it names.
     * Production rows carry embeddings; the fixture has to as well.
     */
    private suspend fun seed(id: String, content: String) {
        val embedder = FakeEmbedder(384)
        db.memoryDao().insert(
            MemoryEntity(
                id = id,
                content = content,
                source = "user",
                category = "fact",
                importance = 0.6f,
                createdAt = 1_000L,
                accessedAt = 1_000L,
                accessCount = 1,
                decayScore = 1.0f,
                embedding = Embedder.toBytes(embedder.embed(content)),
                embeddingModel = embedder.modelId(),
                embeddingVersion = embedder.dimension(),
            ),
        )
    }

    private val turn = ConversationProvenance(conversationId = "c1", turnTimestamp = 1_700_000_000_000L)

    @Test
    fun `a lexical recall writes one label per returned memory, in rank order`() = runBlocking {
        seed("m1", "Elnur's favourite programming language is Kotlin")
        seed("m2", "The Kotlin compiler runs K2 by default")

        val hits = store.query("Kotlin", MemoryStore.RecallOptions(limit = 5, provenance = turn))
        assertTrue("the query returned nothing, so there is nothing to assert on", hits.isNotEmpty())

        val rows = labels.forConversation("c1")
        assertEquals("one row per returned memory", hits.size, rows.size)
        assertEquals(
            "labels must record what recall returned, in the order it returned it",
            hits.map { it.id },
            rows.sortedBy { it.rank }.map { it.memoryId },
        )
        assertEquals("rank is 1-based and gapless", (1..hits.size).toList(), rows.map { it.rank }.sorted())
        assertTrue("the question must be recorded with the row", rows.all { it.queryText.isNotBlank() })
        assertTrue("a fresh label is unjudged", rows.all { it.grade == null })
    }

    /**
     * The branch that would otherwise be missed. A query sharing no term with
     * the corpus falls through the lexical path to the vector fallback, which
     * has its own `return results` — and its own history of being forgotten.
     */
    @Test
    fun `a vector-fallback recall writes labels too`() = runBlocking {
        seed("m1", "Elnur's favourite programming language is Kotlin")

        // `minRelevance` is dropped to zero for this case only. Under
        // `FakeEmbedder` — a hash embedder — the cosine between a lexically
        // unrelated query and the corpus never clears the 0.15 production floor,
        // so the fallback returns empty and the branch is unreachable from a
        // test. Zeroing the floor is what `RETRIEVAL_EVAL.md` describes the
        // branch doing anyway once rows carry embeddings: admitting whatever
        // clears it. The floor is not what is under test here; the write is.
        val fallbackStore = MemoryStore(
            db.memoryDao(),
            FakeEmbedder(384),
            WriteGate(),
            db.memoryEditDao(),
            db.memoryFeedbackDao(),
            null,
            null,
            null,
            RetrievalConfig.DEFAULT.copy(minRelevance = 0f),
            null,
            RetrievalLabelStore(db.retrievalLabelDao()),
        )

        val hits = fallbackStore.query(
            "zzqxv unrelated terminology",
            MemoryStore.RecallOptions(limit = 5, provenance = turn),
        )

        // Asserted before the comparison, and not as a convenience. The obvious
        // form of this test — "returned count equals recorded count" — passes as
        // 0 == 0 when the fallback returns nothing, which is exactly what it did
        // on the run that proved this gate: deleting the wire failed the two
        // lexical cases and left this one green. A branch test that cannot fail
        // when its branch is unwired is not a test of that branch.
        assertTrue(
            "the vector fallback returned nothing, so this case proves nothing about it",
            hits.isNotEmpty(),
        )
        assertEquals(
            "the fallback branch returned results but recorded no labels",
            hits.size,
            labels.forConversation("c1").size,
        )
    }

    /**
     * The other half of the invariant, and the half that protects the corpus.
     *
     * `RecallOptions.provenance` is empty for reads that are not serving a turn
     * — tool calls, and `RetrievalEvalRunner`'s own queries. Labelling those
     * would fill the eval corpus with questions the user never asked, and the
     * runner would end up scoring the ranker against its own fixtures.
     */
    @Test
    fun `a recall with no provenance records nothing`() = runBlocking {
        seed("m1", "Elnur's favourite programming language is Kotlin")

        val hits = store.query("Kotlin", MemoryStore.RecallOptions(limit = 5))
        assertTrue("the query returned nothing, so the assertion below is vacuous", hits.isNotEmpty())

        assertTrue(
            "a read that is not serving a turn must not be labelled",
            labels.all().isEmpty(),
        )
    }

    /**
     * A retry re-runs recall for the same turn. The derived primary key is what
     * stops that becoming a second set of rows, and this is the path it actually
     * travels — not the DAO in isolation.
     */
    @Test
    fun `re-recalling the same turn does not duplicate rows`() = runBlocking {
        seed("m1", "Elnur's favourite programming language is Kotlin")

        val first = store.query("Kotlin", MemoryStore.RecallOptions(limit = 5, provenance = turn))
        store.query("Kotlin", MemoryStore.RecallOptions(limit = 5, provenance = turn))

        assertEquals(first.size, labels.forConversation("c1").size)
    }
}
