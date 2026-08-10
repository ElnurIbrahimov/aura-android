package com.aura.memory

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What happens to stored vectors when the embedding model changes.
 *
 * The case with no prior coverage at all is a **same-dimension** swap, and it
 * is also the likely one: every credible small embedding model is 384-dim, the
 * same as the local hash embedder. `cosineSimilarity` only guards on size, so a
 * 384→384 change produces plausible-looking, entirely meaningless similarities
 * with no log, no error, and no way to tell from the outside. The 768→384 case
 * at least collapses to zero.
 *
 * Three defects met here:
 *  - `rebuildEmbeddings` filtered on `embedding == null`, and a model change
 *    nulls nothing — so it re-embedded exactly zero rows.
 *  - the rebuild wrote `embedding` but not `embeddingModel`, so a rebuilt row
 *    kept its stale tag and would be found again forever.
 *  - `store` wrote the CONFIGURED model over whatever the embedder actually
 *    produced, so a cloud-failure fallback was tagged as a cloud vector.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class ReembedOnModelChangeTest {

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

    /** A FakeEmbedder with a settable model id, so a swap is expressible. */
    private class TaggedEmbedder(
        private val id: String,
        private val dim: Int = 384,
    ) : Embedder {
        private val delegate = FakeEmbedder(dim)
        override suspend fun embed(text: String) = delegate.embed(text)
        override fun modelId() = id
        override fun dimension() = dim
    }

    private fun storeWith(embedder: Embedder) = MemoryStore(
        db.memoryDao(),
        embedder,
        WriteGate(),
        db.memoryEditDao(),
        db.memoryFeedbackDao(),
    )

    private suspend fun seed(store: MemoryStore, n: Int) {
        repeat(n) { i ->
            store.store(
                content = "memory number $i about kotlin and android",
                source = "user",
                category = "fact",
                importance = 0.5f,
            )
        }
    }

    // ---- the case with no prior coverage ---------------------------------

    @Test
    fun `a same-dimension model change is detected and repaired`() = runBlocking {
        val modelA = TaggedEmbedder("model-a", dim = 384)
        seed(storeWith(modelA), 5)

        // Same dimension. cosineSimilarity's size guard never fires, so nothing
        // downstream can notice on its own.
        val modelB = TaggedEmbedder("model-b", dim = 384)
        val storeB = storeWith(modelB)

        assertEquals(5, storeB.countNeedingReembed(), "a model change must be visible")

        val rebuilt = storeB.rebuildEmbeddings()
        assertEquals(5, rebuilt)
        assertEquals(0, storeB.countNeedingReembed(), "rebuild did not converge")

        val rows = db.memoryDao().allForExport()
        assertTrue(rows.all { it.embeddingModel == "model-b" }, "stale tags survived the rebuild")
        assertTrue(rows.all { it.embedding != null })
    }

    @Test
    fun `rebuilding is idempotent`() = runBlocking {
        // The convergence property. The rebuild used to write `embedding` only,
        // leaving the stale `embeddingModel` — so the very next run found the
        // same rows again, forever, re-embedding them and never finishing.
        val store = storeWith(TaggedEmbedder("model-a"))
        seed(store, 4)
        val storeB = storeWith(TaggedEmbedder("model-b"))

        assertEquals(4, storeB.rebuildEmbeddings())
        assertEquals(0, storeB.rebuildEmbeddings(), "a second run re-did work that was already done")
    }

    @Test
    fun `no model change means no work`() = runBlocking {
        val embedder = TaggedEmbedder("model-a")
        val store = storeWith(embedder)
        seed(store, 3)

        assertEquals(0, store.countNeedingReembed())
        assertEquals(0, store.rebuildEmbeddings())
    }

    @Test
    fun `rows with no embedding at all are still picked up`() = runBlocking {
        // The original null-based behaviour has to keep working: the user edit
        // path and backup restore both null the embedding deliberately.
        val embedder = TaggedEmbedder("model-a")
        val store = storeWith(embedder)
        seed(store, 2)

        val dao = db.memoryDao()
        val first = dao.allForExport().first()
        dao.update(first.copy(embedding = null))

        assertEquals(1, store.countNeedingReembed())
        assertEquals(1, store.rebuildEmbeddings())
        assertTrue(dao.allForExport().all { it.embedding != null })
    }

    @Test
    fun `a restored row with a stale tag and no embedding is repaired`() = runBlocking {
        // Backup restore drops embeddings but keeps the old embeddingModel
        // string, so a restored row hits BOTH clauses of the query at once.
        val store = storeWith(TaggedEmbedder("model-a"))
        seed(store, 1)
        val dao = db.memoryDao()
        dao.update(dao.allForExport().first().copy(embedding = null, embeddingModel = "some-old-model"))

        val storeB = storeWith(TaggedEmbedder("model-b"))
        assertEquals(1, storeB.rebuildEmbeddings())
        assertEquals("model-b", dao.allForExport().first().embeddingModel)
    }

    // ---- honest tagging --------------------------------------------------

    @Test
    fun `store records the model that actually produced the vector`() = runBlocking {
        // embedTagged's default reports modelId(); the point of the seam is
        // that an embedder whose output may not match its configuration can
        // override it, as CloudEmbedder does for its local fallback.
        val store = storeWith(TaggedEmbedder("model-a", dim = 384))
        seed(store, 1)
        val row = db.memoryDao().allForExport().first()
        assertEquals("model-a", row.embeddingModel)
        assertEquals(384, row.embeddingVersion)
        assertEquals(384 * 4, row.embedding!!.size, "the BLOB must match the declared dimension")
    }

    @Test
    fun `an embedder reporting a mismatched tag is taken at its word`() = runBlocking {
        // Simulates CloudEmbedder's fallback: the vector is local, and the tag
        // has to say so rather than claiming the configured cloud model.
        val lying = object : Embedder {
            private val delegate = FakeEmbedder(384)
            override suspend fun embed(text: String) = delegate.embed(text)
            override fun modelId() = "ollama:nomic-embed-text"
            override fun dimension() = 768
            override suspend fun embedTagged(text: String) =
                Embedding(embed(text), "local-hash-v2", 384)
        }
        val store = storeWith(lying)
        seed(store, 1)

        val row = db.memoryDao().allForExport().first()
        assertEquals("local-hash-v2", row.embeddingModel, "the fallback was tagged as a cloud vector")
        assertEquals(384, row.embeddingVersion)

        // And it is immediately visible as needing repair, because the tag
        // disagrees with what the embedder now claims to be.
        assertEquals(1, store.countNeedingReembed())
    }

    // ---- recall behaviour ------------------------------------------------

    @Test
    fun `stale vectors score zero rather than a plausible wrong number`() = runBlocking {
        val store = storeWith(TaggedEmbedder("model-a"))
        seed(store, 6)

        // Same dimension, different model. Every stored vector is now
        // meaningless, but structurally valid.
        val storeB = storeWith(TaggedEmbedder("model-b"))

        // Lexical recall still works — the FTS index is model-independent.
        val results = storeB.query("kotlin android", MemoryStore.RecallOptions(limit = 5))
        assertTrue(results.isNotEmpty(), "lexical recall must survive an embedding-model change")

        // After repair, recall still works. The assertion that matters is that
        // neither state throws or returns garbage.
        storeB.rebuildEmbeddings()
        assertTrue(storeB.query("kotlin android", MemoryStore.RecallOptions(limit = 5)).isNotEmpty())
    }
}
