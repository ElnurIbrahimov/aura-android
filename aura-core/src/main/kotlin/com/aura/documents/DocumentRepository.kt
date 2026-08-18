package com.aura.documents

import com.aura.memory.MemoryStore
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DocumentRepository @Inject constructor(
    private val documentDao: DocumentDao,
    private val memoryStore: MemoryStore,
    // Appended and defaulted so the existing two-argument construction in tests
    // keeps working, and so an install with no model configured still imports
    // documents exactly as it did before.
    private val studier: DocumentStudier? = null,
    private val cheapModelResolver: com.aura.providers.CheapModelResolver? = null,
    /**
     * Where chunks belong, and where they now also go.
     *
     * Import has always written its chunks as undifferentiated `memories` rows,
     * which is what [DocumentChunkEntity]'s own KDoc says it exists to replace.
     * The reader side is a larger change than the writer — every retrieval
     * consumer is typed on `MemoryEntity`, and chunks carry no `scope`,
     * `decayScore`, `accessCount`, `importance` or `retiredAt` — so this writes
     * both and keeps recall reading `memories`. Switching the reader and then
     * dropping the double write are separate steps, in that order, because the
     * reverse takes document recall dark in between.
     *
     * Appended and defaulted for the reason the two above it are.
     */
    private val documentChunkDao: DocumentChunkDao? = null,
) {
    fun observeAll(): Flow<List<DocumentEntity>> = documentDao.observeAll()

    suspend fun import(
        id: String,
        name: String,
        mimeType: String,
        sourceUri: String,
        text: String,
    ): DocumentImportResult {
        require(text.isNotBlank()) { "The document contains no extractable text." }
        require(text.length <= MAX_TEXT_CHARS) {
            "The document is too large after extraction (maximum ${MAX_TEXT_CHARS / 1_000_000}M characters)."
        }
        val safeName = name.trim().take(240).ifBlank { "Untitled document" }
        val chunked = DocumentChunker.chunkWithOffsets(text)
        val chunks = chunked.chunks.map { it.text }
        require(chunks.isNotEmpty()) { "The document contains no extractable text." }
        val source = sourceFor(id)
        val stemTag = safeName.substringBeforeLast('.')
            .lowercase()
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
            .take(48)
            .ifBlank { "imported" }

        try {
            chunks.forEachIndexed { index, chunk ->
                memoryStore.storeIfAbsent(
                    content = buildString {
                        append("Document: ").append(safeName)
                        append(" · Part ").append(index + 1).append('/').append(chunks.size)
                        append("\n\n").append(chunk)
                    },
                    source = source,
                    category = "document",
                    importance = 0.65f,
                    tags = listOf("document", stemTag),
                )
            }
            val document = DocumentEntity(
                id = id,
                name = safeName,
                mimeType = mimeType.ifBlank { "application/octet-stream" },
                sourceUri = sourceUri,
                characterCount = text.length,
                chunkCount = chunks.size,
            )
            documentDao.insert(document)
            // After the document row, never before: `document_chunks` carries a
            // CASCADE foreign key into `documents` and Room runs with
            // `PRAGMA foreign_keys = ON`, so the reverse order rejects every
            // chunk. BackupManager already had to learn this twice, for
            // project notes and for claim resolutions.
            writeChunks(id, chunked)
            val outlined = writeOutline(safeName, chunks, source, stemTag)
            return DocumentImportResult(document, chunks.size, outlined)
        } catch (failure: Throwable) {
            memoryStore.deleteBySource(source)
            // Was a no-op before chunks were written here, because the document
            // row was only inserted once the memory loop had finished. It is
            // load-bearing now: a chunk insert that throws leaves both the
            // document row and a partial chunk set behind, and deleting the
            // parent cascades the children.
            runCatching { documentDao.deleteById(id) }
                .onFailure { android.util.Log.w("DocumentRepository", "rollback of '$id' failed", it) }
            throw failure
        }
    }

    /**
     * Write the chunks as chunks: addressable by document, ordinal and character
     * range, and de-duplicable by content hash.
     *
     * No embedding is computed here. `embedding` is nullable and
     * `DocumentChunkDao.allWithoutEmbeddings` exists precisely so a later pass
     * can fill them, and doing it inline would put a per-chunk network call on
     * the import path — which is half of what makes importing a large document
     * slow today.
     *
     * Ids are deterministic (`documentId:ordinal`) so re-importing the same
     * document replaces its chunks rather than accumulating a second copy;
     * `insertAll` is REPLACE. Stale rows past the new end are cleared first, or
     * a re-import of a shortened document would leave its tail behind.
     */
    private suspend fun writeChunks(documentId: String, chunked: DocumentChunker.ChunkedText) {
        val dao = documentChunkDao ?: return
        dao.deleteForDocument(documentId)
        dao.insertAll(
            chunked.chunks.mapIndexed { ordinal, chunk ->
                DocumentChunkEntity(
                    id = "$documentId:$ordinal",
                    documentId = documentId,
                    ordinal = ordinal,
                    charStart = chunk.charStart,
                    charEnd = chunk.charEnd,
                    text = chunk.text,
                    contentHash = sha256(chunk.text),
                )
            }
        )
    }

    /**
     * Read the document once and store what it is, as one memory.
     *
     * The chunks are already stored and already retrievable, which answers
     * questions *about a passage*. It cannot answer questions about the whole —
     * "what are all the constraints", "does part four contradict part nine" —
     * because retrieval returns the passages nearest the question and neither
     * of those two parts is about the other. The outline is the map, small
     * enough to be carried whole where the source is not.
     *
     * Stored under the same `source` as the chunks so [delete] removes it with
     * them, and at a higher importance so the map outranks any single passage
     * when a question is about the document rather than about a detail in it.
     *
     * Never fails the import. A document that imported without an outline is
     * exactly the document that imported before this existed, which is a
     * working outcome; failing the import to protect an index would not be.
     *
     * @return true when an outline was written.
     */
    private suspend fun writeOutline(
        name: String,
        chunks: List<String>,
        source: String,
        stemTag: String,
    ): Boolean = runCatching {
        val study = studier ?: return false
        // explicit(), via CheapModelResolver — an indexing pass must not fall
        // through to the flagship chat model.
        val model = cheapModelResolver?.resolve() ?: return false
        val outline = study.study(name, chunks, model) ?: return false
        memoryStore.storeIfAbsent(
            content = study.render(name, outline),
            source = source,
            category = "document",
            importance = OUTLINE_IMPORTANCE,
            tags = listOf("document", "outline", stemTag),
        )
        true
    }.onFailure {
        android.util.Log.w("DocumentRepository", "outlining '$name' failed: ${it.message}", it)
    }.getOrDefault(false)

    suspend fun delete(id: String) {
        memoryStore.deleteBySource(sourceFor(id))
        // The CASCADE below would take these anyway, triggers included. Kept
        // explicit so the chunk lifetime is visible at the call site rather
        // than resting on a foreign key declared in another file — and so this
        // still holds if the chunk table is ever detached from `documents`.
        documentChunkDao?.deleteForDocument(id)
        documentDao.deleteById(id)
    }

    private fun sha256(text: String): String =
        java.security.MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    suspend fun get(id: String): DocumentEntity? = documentDao.getById(id)

    companion object {
        const val MAX_TEXT_CHARS = 2_000_000
        fun sourceFor(id: String): String = "document:$id"

        /**
         * Above the 0.65 a chunk gets.
         *
         * A question about the document as a whole should surface the map, not
         * whichever passage happens to share the most words with the question.
         */
        const val OUTLINE_IMPORTANCE = 0.85f
    }
}