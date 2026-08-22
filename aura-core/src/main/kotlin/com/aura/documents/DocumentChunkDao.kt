package com.aura.documents

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface DocumentChunkDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(chunk: DocumentChunkEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(chunks: List<DocumentChunkEntity>)

    @Update
    suspend fun update(chunk: DocumentChunkEntity)

    @Query("SELECT * FROM document_chunks WHERE id = :id LIMIT 1")
    suspend fun getById(id: kotlin.String): DocumentChunkEntity?

    @Query("SELECT * FROM document_chunks WHERE embedding IS NOT NULL")
    suspend fun allWithEmbeddings(): List<DocumentChunkEntity>

    @Query("SELECT * FROM document_chunks WHERE embedding IS NULL")
    suspend fun allWithoutEmbeddings(): List<DocumentChunkEntity>

    @Query("DELETE FROM document_chunks WHERE documentId = :documentId")
    suspend fun deleteForDocument(documentId: kotlin.String)

    /**
     * Which documents contain a match at all.
     *
     * The first half of a two-step fetch, and the reason there are two steps:
     * a single `... ORDER BY c.documentId, c.ordinal LIMIT :limit` is a prefix
     * **by document**, because SQLite applies LIMIT after ORDER BY. With two
     * imported books and a window of 100, a query matching 100+ chunks of the
     * first one returned nothing from the second — permanently, since document
     * ids are content hashes and the order never changes.
     *
     * Deliberately not `ROW_NUMBER() OVER (PARTITION BY documentId ...)`, which
     * is the obvious one-query form: `minSdk = 26` and the project uses plain
     * `room-runtime` with no bundled SQLite, so window functions need Android
     * 11+. That version compiles, passes every test on a modern emulator, and
     * throws a syntax error at runtime on Android 8-10.
     */
    @Query(
        "SELECT DISTINCT c.documentId FROM document_chunks c " +
            "JOIN document_chunks_fts f ON f.rowid = c.rowid " +
            "WHERE f.content MATCH :ftsQuery",
    )
    suspend fun matchingDocumentIds(ftsQuery: kotlin.String): List<kotlin.String>

    /**
     * The candidate window for one document.
     *
     * Joined back to `document_chunks` on `rowid`, and to `documents` for the
     * name a citation needs, so an orphaned index row matches nothing. Matches
     * `f.content`, not the bare table, so a query term cannot match a `chunkId`.
     *
     * [ftsQuery] is an already-escaped FTS4 MATCH expression — build it with
     * `FtsQuery.build`, never by string-concatenating user input.
     *
     * Ordered by `ordinal` rather than by any relevance signal: FTS4 has no
     * `bm25()` (that is FTS5), so this is a candidate *window* and the ranking
     * happens in Kotlin. `MemoryDao.searchFts` records what happens when the
     * window is mistaken for a ranking.
     */
    @Query(
        "SELECT c.*, d.name AS documentName FROM document_chunks c " +
            "JOIN document_chunks_fts f ON f.rowid = c.rowid " +
            "JOIN documents d ON d.id = c.documentId " +
            "WHERE f.content MATCH :ftsQuery AND c.documentId = :documentId " +
            "ORDER BY c.ordinal LIMIT :limit",
    )
    suspend fun searchFtsInDocument(
        ftsQuery: kotlin.String,
        documentId: kotlin.String,
        limit: Int = 200,
    ): List<DocumentChunkHit>

    /** Corpus size for the chunk index — the `N` in BM25's IDF, over documents only. */
    /**
     * Chunks of one document, in order.
     *
     * Kept although only tests call it: it is the only way to observe a write
     * production really performs, and a write nothing can read back is a write
     * nothing can prove.
     */
    @Query("SELECT * FROM document_chunks WHERE documentId = :documentId ORDER BY ordinal ASC")
    suspend fun forDocument(documentId: String): List<DocumentChunkEntity>

    @Query("SELECT COUNT(*) FROM document_chunks WHERE documentId = :documentId")
    suspend fun countForDocument(documentId: String): Int

    @Query("SELECT COUNT(*) FROM document_chunks")
    suspend fun countChunks(): Int

    /**
     * How many chunks contain [term], for one already-escaped single-term MATCH
     * expression. The `df` in IDF, counted against the whole chunk corpus
     * rather than against the candidates — which is the distinction
     * `MemoryFtsEntity`'s KDoc records as the reason the lexical signal used to
     * rank at random.
     */
    @Query("SELECT COUNT(*) FROM document_chunks_fts WHERE content MATCH :term")
    suspend fun docFreq(term: kotlin.String): Int

    /**
     * Wipe the table. Used by the backup restore.
     *
     * This was `DELETE FROM document_chunks WHERE documentId IN (SELECT id FROM
     * documents)`, and `BackupManager` calls it *after* `documentDao.deleteAll()`
     * — so the subquery was always empty and the statement always deleted
     * nothing.
     *
     * That was harmless rather than a live bug, and the distinction is worth
     * writing down because the first version of this comment got it wrong:
     * `ON DELETE CASCADE` **does** fire the child table's DELETE triggers, so
     * removing the documents had already taken both the chunks and their index
     * rows. (The rule that a delete can skip triggers belongs to REPLACE
     * conflict resolution, not to foreign key actions — which is why the insert
     * trigger has to delete by `chunkId` first, and why this does not.)
     *
     * Rewritten anyway: a statement whose correctness depends on being called
     * before another one, and which silently does nothing when it is not, is a
     * wipe that only looks like a wipe. This one works in either order.
     */
    @Query("DELETE FROM document_chunks")
    suspend fun deleteAll()

    @Query("UPDATE document_chunks SET embedding = :embedding, embeddingModel = :model, embeddingVersion = :version, embeddedAt = :timestamp WHERE id = :id")
    suspend fun updateEmbedding(id: kotlin.String, embedding: ByteArray, model: kotlin.String, version: Int, timestamp: kotlin.Long)

    @Query("SELECT * FROM document_chunks ORDER BY documentId, ordinal")
    suspend fun allForBackup(): List<DocumentChunkEntity>
}