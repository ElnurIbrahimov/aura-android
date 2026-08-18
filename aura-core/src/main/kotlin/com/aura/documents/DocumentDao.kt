package com.aura.documents

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface DocumentDao {
    @Upsert
    suspend fun insert(document: DocumentEntity)

    @Upsert
    suspend fun insertAll(documents: List<DocumentEntity>)

    @Query("SELECT * FROM documents ORDER BY importedAt DESC")
    fun observeAll(): Flow<List<DocumentEntity>>

    @Query("SELECT * FROM documents ORDER BY importedAt DESC")
    suspend fun allForBackup(): List<DocumentEntity>

    @Query("SELECT * FROM documents WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): DocumentEntity?

    /**
     * Documents with no rows in `document_chunks`, and therefore invisible to
     * `search_documents`.
     *
     * Every document imported before the chunk table had a writer is in this
     * state: the migration that added the chunk index backfilled from a table
     * that had never held a row, and there is no path from the old storage into
     * the new one. Their text is still in `memories`, so ordinary recall finds
     * them — but the library shows a chunk count for content document search
     * reports as absent, which is the app contradicting itself.
     *
     * Used to say so in the UI rather than to repair anything. A reconstructive
     * migration is the wrong answer: the character ranges are unrecoverable and
     * inventing them would be the same class of error as printing a range that
     * addresses nothing. Re-importing repairs it cleanly, because document ids
     * are content hashes.
     */
    @Query("SELECT id FROM documents WHERE id NOT IN (SELECT DISTINCT documentId FROM document_chunks)")
    suspend fun idsWithoutChunks(): List<String>

    @Query("DELETE FROM documents WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM documents")
    suspend fun deleteAll()
}