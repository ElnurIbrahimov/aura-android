package com.aura.documents

import com.aura.memory.MemoryStore
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DocumentRepositoryTest {
    private val dao = mockk<DocumentDao>(relaxed = true)
    private val memoryStore = mockk<MemoryStore>(relaxed = true)
    private val chunkDao = mockk<DocumentChunkDao>(relaxed = true)
    private val repository = DocumentRepository(
        dao,
        memoryStore,
        documentChunkDao = chunkDao,
    )

    @Test
    fun `import stores searchable chunks and document metadata`() = runTest {
        val inserted = slot<DocumentEntity>()
        coEvery { dao.insert(capture(inserted)) } returns Unit
        val text = (1..500).joinToString(" ") { "worldbuilding-$it" }

        val result = repository.import(
            id = "sha256-id",
            name = "world-bible.pdf",
            mimeType = "application/pdf",
            sourceUri = "content://docs/world-bible",
            text = text,
        )

        assertTrue(result.chunkCount > 1)
        assertEquals("sha256-id", inserted.captured.id)
        assertEquals(result.chunkCount, inserted.captured.chunkCount)
        assertEquals(text.length, inserted.captured.characterCount)
        coVerify(exactly = result.chunkCount) {
            memoryStore.storeIfAbsent(
                content = any(),
                source = "document:sha256-id",
                category = "document",
                importance = 0.65f,
                tags = match { "document" in it && "world-bible" in it },
            )
        }
    }

    @Test
    fun `import also writes addressable chunk rows`() = runTest {
        // The memory rows above are still written and still what recall reads.
        // These are the same chunks in the table that was built for them, and
        // had never received a single row: DocumentChunkEntity's KDoc describes
        // itself as replacing "the current approach of storing document chunks
        // as undifferentiated memories", and nothing had ever written to it.
        val written = slot<List<DocumentChunkEntity>>()
        coEvery { chunkDao.insertAll(capture(written)) } returns Unit
        val text = (1..500).joinToString(" ") { "worldbuilding-$it" }

        val result = repository.import(
            id = "doc-9",
            name = "world-bible.pdf",
            mimeType = "application/pdf",
            sourceUri = "content://docs/world-bible",
            text = text,
        )

        val rows = written.captured
        assertEquals(result.chunkCount, rows.size)
        assertEquals(List(rows.size) { it }, rows.map { it.ordinal })
        assertEquals(List(rows.size) { "doc-9:$it" }, rows.map { it.id })
        assertTrue(rows.all { it.documentId == "doc-9" })
        // Offsets address the normalised text, and the chunk is what sits there.
        val normalized = DocumentChunker.chunkWithOffsets(text).normalized
        assertTrue(rows.all { normalized.substring(it.charStart, it.charEnd) == it.text })
        // Hashes are real and distinguish chunks, or dedup by hash is decorative.
        assertTrue(rows.all { it.contentHash.length == 64 })
        assertEquals(rows.size, rows.map { it.contentHash }.distinct().size)
        // Nothing is embedded on the import path; that is what
        // `allWithoutEmbeddings` is for.
        assertTrue(rows.all { it.embedding == null && it.embeddedAt == 0L })
    }

    @Test
    fun `delete removes document chunks before metadata`() = runTest {
        repository.delete("doc-1")
        coVerify { memoryStore.deleteBySource("document:doc-1") }
        coVerify { chunkDao.deleteForDocument("doc-1") }
        coVerify { dao.deleteById("doc-1") }
    }

    @Test
    fun `a failed import leaves neither chunks nor a document row`() = runTest {
        // The rollback used to delete only the memories, which was complete
        // while the document row was written last. Writing chunks moved that
        // insert earlier, so a throw after it now has a parent row to strand —
        // and stranding it strands the chunks under it too.
        coEvery { chunkDao.insertAll(any()) } throws IllegalStateException("disk full")

        val thrown = runCatching {
            repository.import("doc-2", "a.txt", "text/plain", "content://a", "hello world")
        }.exceptionOrNull()

        assertTrue(thrown is IllegalStateException)
        coVerify { memoryStore.deleteBySource("document:doc-2") }
        coVerify { dao.deleteById("doc-2") }
    }

    @Test
    fun `observeAll delegates to document dao`() {
        coEvery { dao.observeAll() } returns flowOf(emptyList())
        repository.observeAll()
        io.mockk.verify { dao.observeAll() }
    }
}