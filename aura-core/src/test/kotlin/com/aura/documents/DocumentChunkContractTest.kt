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
 * Real-Room coverage for the chunk table, which until now had never held a row.
 *
 * `DocumentRepositoryTest` mocks `DocumentChunkDao`, so it can prove the
 * repository asks for the right writes and nothing about whether SQLite accepts
 * them. Everything interesting here is SQL: a CASCADE foreign key that rejects
 * a chunk written before its parent, REPLACE semantics on re-import, and a
 * `deleteAll` whose `WHERE documentId IN (SELECT id FROM documents)` means it
 * deletes nothing once the documents are gone.
 *
 * The same reasoning as `KgBatchQueryContractTest`, and the same precedent
 * behind it: `MemoryDaoContractTest` records an ESCAPE regression that survived
 * 1,669 green tests because every test touching it mocked the DAO.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class DocumentChunkContractTest {

    private lateinit var db: MemoryDatabase
    private lateinit var chunks: DocumentChunkDao
    private lateinit var documents: DocumentDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, MemoryDatabase::class.java)
            .allowMainThreadQueries()
            .addCallback(MemoryFtsSchema.triggerCallback)
            .build()
        chunks = db.documentChunkDao()
        documents = db.documentDao()
    }

    @After
    fun tearDown() = db.close()

    private fun document(id: String) = DocumentEntity(
        id = id,
        name = "$id.txt",
        mimeType = "text/plain",
        sourceUri = "content://$id",
        characterCount = 100,
        chunkCount = 2,
    )

    private fun chunk(documentId: String, ordinal: Int, text: String = "chunk $ordinal") =
        DocumentChunkEntity(
            id = "$documentId:$ordinal",
            documentId = documentId,
            ordinal = ordinal,
            charStart = ordinal * 10,
            charEnd = ordinal * 10 + text.length,
            text = text,
            contentHash = "hash-$documentId-$ordinal",
        )

    @Test
    fun `a chunk written before its document is rejected`() = runBlocking {
        // The reason DocumentRepository writes chunks after `documentDao.insert`
        // and not before. Room restores and runs with foreign keys on, so this
        // is not a hypothetical ordering preference.
        val failure = runCatching { chunks.insertAll(listOf(chunk("ghost", 0))) }.exceptionOrNull()

        assertTrue(
            "expected a foreign key violation, got $failure",
            failure is android.database.sqlite.SQLiteConstraintException,
        )
    }

    @Test
    fun `chunks round trip in ordinal order`() = runBlocking {
        documents.insert(document("doc-a"))
        chunks.insertAll(listOf(chunk("doc-a", 1), chunk("doc-a", 0), chunk("doc-a", 2)))

        val read = chunks.forDocument("doc-a")

        assertEquals(listOf(0, 1, 2), read.map { it.ordinal })
        assertEquals(3, chunks.countForDocument("doc-a"))
    }

    @Test
    fun `re-importing replaces chunks instead of accumulating them`() = runBlocking {
        // Ids are `documentId:ordinal` precisely so this holds. Without the
        // deterministic id a second import would double the rows, and a
        // document that lost a section would keep answering from the old one.
        documents.insert(document("doc-b"))
        chunks.insertAll(listOf(chunk("doc-b", 0, "first draft"), chunk("doc-b", 1, "second")))

        chunks.deleteForDocument("doc-b")
        chunks.insertAll(listOf(chunk("doc-b", 0, "revised")))

        val read = chunks.forDocument("doc-b")
        assertEquals(1, read.size)
        assertEquals("revised", read.single().text)
    }

    @Test
    fun `deleting the document takes its chunks with it`() = runBlocking {
        documents.insert(document("doc-c"))
        chunks.insertAll(listOf(chunk("doc-c", 0), chunk("doc-c", 1)))

        documents.deleteById("doc-c")

        assertEquals(0, chunks.countForDocument("doc-c"))
    }

    @Test
    fun `chunks carry no embedding until something computes one`() = runBlocking {
        // Import deliberately does not embed — that would put a network call per
        // chunk on the import path. This pins the two DAO queries a later
        // embedding pass would be built on, which otherwise have no caller.
        documents.insert(document("doc-d"))
        chunks.insertAll(listOf(chunk("doc-d", 0), chunk("doc-d", 1)))

        assertEquals(2, chunks.allWithoutEmbeddings().count { it.documentId == "doc-d" })
        assertEquals(0, chunks.allWithEmbeddings().count { it.documentId == "doc-d" })

        chunks.updateEmbedding("doc-d:0", ByteArray(1536), "test-model", 384, 1_700_000_000L)

        assertEquals(1, chunks.allWithEmbeddings().count { it.documentId == "doc-d" })
        assertEquals(1, chunks.allWithoutEmbeddings().count { it.documentId == "doc-d" })
    }
}
