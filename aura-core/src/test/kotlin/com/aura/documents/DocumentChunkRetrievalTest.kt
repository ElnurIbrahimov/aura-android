package com.aura.documents

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
 * Document search, against a real database and a real index.
 *
 * Everything here depends on SQL that a mocked DAO would assert nothing about:
 * whether the triggers fired at all, whether `MATCH` reaches the right column,
 * whether the join drops orphans, and whether deleting a document takes its
 * index rows with it. `MemoryDaoContractTest` records an ESCAPE regression that
 * survived 1,669 green tests because every test touching it mocked the DAO.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class DocumentChunkRetrievalTest {

    private lateinit var db: MemoryDatabase
    private lateinit var chunks: DocumentChunkDao
    private lateinit var documents: DocumentDao
    private lateinit var retrieval: DocumentChunkRetrieval

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, MemoryDatabase::class.java)
            .allowMainThreadQueries()
            .addCallback(MemoryFtsSchema.triggerCallback)
            .build()
        chunks = db.documentChunkDao()
        documents = db.documentDao()
        retrieval = DocumentChunkRetrieval(chunks)
    }

    @After
    fun tearDown() = db.close()

    private suspend fun importDocument(id: String, name: String, texts: List<String>) {
        documents.insert(
            DocumentEntity(
                id = id,
                name = name,
                mimeType = "text/plain",
                sourceUri = "content://$id",
                characterCount = texts.sumOf { it.length },
                chunkCount = texts.size,
            )
        )
        chunks.insertAll(
            texts.mapIndexed { ordinal, text ->
                DocumentChunkEntity(
                    id = "$id:$ordinal",
                    documentId = id,
                    ordinal = ordinal,
                    charStart = ordinal * 100,
                    charEnd = ordinal * 100 + text.length,
                    text = text,
                    contentHash = "hash-$id-$ordinal",
                )
            }
        )
    }

    @Test
    fun `the insert trigger indexes chunks without anyone asking it to`() = runBlocking {
        // If this fails, every other assertion in this file becomes "returns
        // empty" and passes or fails for reasons unrelated to what it checks —
        // which is the exact hazard MemoryFtsSchema's KDoc names.
        importDocument("doc-a", "manual.txt", listOf("the coolant loop runs at ambient pressure"))

        val hits = chunks.searchFtsInDocument("\"coolant\"", "doc-a", 10)

        assertEquals(1, hits.size)
        assertEquals("doc-a:0", hits.single().chunk.id)
        assertEquals("manual.txt", hits.single().documentName)
    }

    @Test
    fun `search returns the passage that is about the query`() = runBlocking {
        importDocument(
            "doc-b",
            "field-guide.txt",
            listOf(
                "Chapter one introduces the estuary and its tides.",
                "Chapter two covers heron nesting behaviour in detail.",
                "Chapter three is a glossary of unrelated terminology.",
            ),
        )

        val results = retrieval.search("heron nesting")

        assertTrue(results.isNotEmpty())
        assertEquals(1, results.first().ordinal)
        assertTrue("heron" in results.first().text)
    }

    @Test
    fun `a passage carries an address that resolves`() = runBlocking {
        importDocument("doc-c", "world-bible.pdf", listOf("The northern reach is uninhabited."))

        val passage = retrieval.search("northern reach").single()

        assertEquals("doc-c", passage.documentId)
        assertEquals("world-bible.pdf", passage.documentName)
        assertEquals("doc-c:0", passage.chunkId)
        assertEquals(0, passage.ordinal)
        // "part 1", not "part 0" — the citation is for a person to follow.
        assertEquals("world-bible.pdf · part 1", passage.citation)
        assertTrue(passage.score > 0f)
    }

    @Test
    fun `nothing matching returns nothing rather than the least-bad passage`() = runBlocking {
        importDocument("doc-d", "recipes.txt", listOf("Fold the egg whites gently."))

        assertTrue(retrieval.search("quantum chromodynamics").isEmpty())
    }

    @Test
    fun `a query of only stopwords does not run an FTS statement`() = runBlocking {
        // `FtsQuery.build` returns null here and `MATCH ''` is a syntax error in
        // SQLite, not an empty result — so the guard has to be before the query,
        // and this is what proves it is.
        importDocument("doc-e", "notes.txt", listOf("Something worth finding later."))

        assertTrue(retrieval.search("the and of it").isEmpty())
        assertTrue(retrieval.search("").isEmpty())
    }

    @Test
    fun `search with no documents imported is empty, not an error`() = runBlocking {
        assertTrue(retrieval.search("anything at all").isEmpty())
    }

    @Test
    fun `deleting a chunk removes it from the index`() = runBlocking {
        importDocument("doc-f", "draft.txt", listOf("The prototype uses a beryllium mirror."))
        assertTrue(retrieval.search("beryllium").isNotEmpty())

        chunks.deleteForDocument("doc-f")

        assertTrue(retrieval.search("beryllium").isEmpty())
    }

    @Test
    fun `re-importing replaces the indexed text instead of indexing it twice`() = runBlocking {
        // REPLACE does not fire the delete trigger and the replacement row gets
        // a new rowid, so a plain insert trigger would leave the old index row
        // stranded AND add a new one — the same chunk indexed twice, inflating
        // df for exactly the passages that get revised most.
        importDocument("doc-g", "spec.txt", listOf("The valve is rated to twelve bar."))
        chunks.insertAll(
            listOf(
                DocumentChunkEntity(
                    id = "doc-g:0",
                    documentId = "doc-g",
                    ordinal = 0,
                    charStart = 0,
                    charEnd = 30,
                    text = "The valve is rated to twenty bar.",
                    contentHash = "hash-revised",
                )
            )
        )

        assertEquals(1, chunks.searchFtsInDocument("\"valve\"", "doc-g", 10).size)
        assertTrue(retrieval.search("twelve").isEmpty())
        assertTrue(retrieval.search("twenty").isNotEmpty())
    }

    @Test
    fun `an embedding update leaves the index intact and searchable`() = runBlocking {
        // The update trigger is scoped `AFTER UPDATE OF text` so an embedding
        // pass over a thousand-chunk document does not delete and reinsert a
        // thousand identical index rows — the defect MIGRATION_26_27 had to go
        // back and fix on `memories`.
        //
        // That saving is invisible to a query: UPDATE keeps the rowid, so a
        // fired trigger and a skipped one leave the same table. What IS
        // observable, and what would actually hurt, is the scoping breaking
        // search — so that is what is asserted, rather than dressing an
        // unobservable difference up as a test.
        importDocument("doc-h", "long.txt", listOf("Indexed once and only once."))

        chunks.updateEmbedding("doc-h:0", ByteArray(1536), "model", 384, 1L)

        assertEquals(1, chunks.searchFtsInDocument("\"indexed\"", "doc-h", 10).size)
        assertTrue(retrieval.search("indexed").isNotEmpty())
    }

    @Test
    fun `editing a chunk's text does reindex it`() = runBlocking {
        // The other side of the scoping, and the one that can silently break:
        // Room's `@Update` generates `SET` over every column, so `text` is
        // always named and the scoped trigger still fires. If that stopped
        // being true, search would keep answering with the old wording forever.
        importDocument("doc-k", "living.txt", listOf("The bridge is made of rope."))
        val existing = chunks.getById("doc-k:0")!!

        chunks.update(existing.copy(text = "The bridge is made of steel."))

        assertTrue(retrieval.search("rope").isEmpty())
        assertTrue(retrieval.search("steel").isNotEmpty())
    }

    @Test
    fun `deleting a document clears its chunks from the index too`() = runBlocking {
        // The load-bearing fact, asserted rather than assumed: `ON DELETE
        // CASCADE` fires the child table's DELETE trigger, so removing a
        // document takes its index rows with it. If that were not true, the
        // index would accumulate orphans that go on counting toward `docFreq`,
        // and IDF would drift for every term in every deleted document.
        //
        // This was written the other way round first — as a test for orphans
        // the wipe had to clean up — and it passed against code that could not
        // possibly have cleaned them up, which is how the assumption got
        // caught.
        importDocument("doc-i", "gone.txt", listOf("Ephemeral content."))
        assertTrue(retrieval.search("ephemeral").isNotEmpty())

        documents.deleteAll()

        assertEquals(0, chunks.countChunks())
        assertEquals(0, chunks.docFreq("\"ephemeral\""))
        assertTrue(retrieval.search("ephemeral").isEmpty())
    }

    @Test
    fun `the wipe works whichever order it is called in`() = runBlocking {
        // `BackupManager` deletes the documents first, which used to leave this
        // statement matching nothing at all. Harmless, because the cascade had
        // already done the work — but a wipe that depends on being called
        // before another one, and silently does nothing otherwise, is not a
        // wipe. Asserted in the order it is actually called.
        importDocument("doc-j2", "restored-over.txt", listOf("Superseded content."))

        documents.deleteAll()
        chunks.deleteAll()

        assertEquals(0, chunks.countChunks())
        assertTrue(retrieval.search("superseded").isEmpty())
    }

    @Test
    fun `the wipe also clears chunks whose document is still present`() = runBlocking {
        importDocument("doc-j3", "kept.txt", listOf("Still referenced content."))

        chunks.deleteAll()

        assertEquals(0, chunks.countChunks())
        assertEquals(0, chunks.docFreq("\"referenced\""))
        assertTrue(retrieval.search("referenced").isEmpty())
    }

    @Test
    fun `every matching document is searchable, not just the first by id`() = runBlocking {
        // The window used to be one query ending `ORDER BY documentId, ordinal
        // LIMIT n`, and SQLite applies LIMIT after ORDER BY — so the candidate
        // set was a prefix *by document*. Once one document contributed a full
        // window of matches, every other document was invisible for any query
        // it also matched: arbitrarily, since ids are content hashes, and
        // permanently, since the order never changes.
        //
        // The ids are chosen so the padded one sorts first, and the padding
        // must EXCEED the candidate window or the old single-query form fits
        // both documents in and this test passes against the defect. The
        // window is limit * ftsOverfetch * CANDIDATE_WIDTH = 5 * 4 * 5 = 100.
        // Written first at 60 chunks, which proved nothing.
        importDocument("aaa-first", "padded.txt", (0 until 140).map { "protocol filler passage $it" })
        importDocument("zzz-second", "wanted.txt", listOf("protocol handshake negotiation detail"))

        val results = retrieval.search("protocol handshake", limit = 5)

        assertTrue(
            "expected a passage from the second document, got ${results.map { it.documentName }}",
            results.any { it.documentId == "zzz-second" },
        )
    }

    @Test
    fun `a document with no match contributes nothing`() = runBlocking {
        importDocument("doc-m1", "relevant.txt", listOf("the anchor chain is galvanised"))
        importDocument("doc-m2", "irrelevant.txt", listOf("a recipe for lemon posset"))

        val results = retrieval.search("galvanised anchor")

        assertEquals(listOf("doc-m1"), results.map { it.documentId }.distinct())
    }

    @Test
    fun `document frequency is counted over the corpus, not the candidates`() = runBlocking {
        // Every chunk contains "protocol", one contains "handshake". If df came
        // from the candidate set both would look equally rare, since every
        // candidate matched by construction — the failure `MemoryFtsEntity`
        // documents. Against the corpus, "protocol" is in all four and
        // "handshake" in one.
        importDocument(
            "doc-j",
            "rfc.txt",
            listOf(
                "protocol overview and scope",
                "protocol message formats",
                "protocol error handling",
                "protocol handshake negotiation",
            ),
        )

        assertEquals(4, chunks.docFreq("\"protocol\""))
        assertEquals(1, chunks.docFreq("\"handshake\""))

        val results = retrieval.search("protocol handshake")
        assertEquals(3, results.first().ordinal)
    }
}
