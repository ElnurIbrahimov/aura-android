package com.aura.agent

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ConversationDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(conv: ConversationEntity)

    @Update
    suspend fun update(conv: ConversationEntity)

    @Query("UPDATE conversations SET turnsJson = :turnsJson, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateTurns(id: kotlin.String, turnsJson: kotlin.String, updatedAt: Long)

    @Query("SELECT * FROM conversations WHERE id = :id")
    suspend fun getById(id: String): ConversationEntity?

    @Query("SELECT * FROM conversations ORDER BY updatedAt DESC LIMIT :limit")
    suspend fun recent(limit: Int = 50): List<ConversationEntity>

    /**
     * Like [recent] but excludes soft-deleted rows. This is what the
     * History screen should call — the user already deleted tombstones
     * shouldn't show up in the live list.
     */
    @Query("SELECT * FROM conversations WHERE deletedAt IS NULL ORDER BY updatedAt DESC LIMIT :limit")
    suspend fun recentVisible(limit: Int = 50): List<ConversationEntity>

    @Query("SELECT * FROM conversations ORDER BY createdAt ASC")
    suspend fun allForExport(): List<ConversationEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(rows: List<ConversationEntity>)

    /**
     * Full-text-ish search across conversation title + serialized turn
     * content. Uses SQL LIKE because the turns are stored as a single
     * JSON column — adding an FTS virtual table would require a
     * migration and a per-turn indexer, which is overkill for personal
     * use. LIKE is O(n) on a personal-size table (hundreds of rows),
     * which is fine.
     *
     * [escapedQuery] must have `%`/`_`/`\` escaped (see
     * [com.aura.memory.MemoryStore.escapeLikeWildcards]) so a user
     * query containing those characters matches literally rather than
     * acting as a pattern.
     */
    @Query(
        """
        SELECT * FROM conversations
        WHERE title LIKE '%' || :escapedQuery || '%' ESCAPE '\\'
           OR turnsJson LIKE '%' || :escapedQuery || '%' ESCAPE '\\'
        ORDER BY updatedAt DESC
        LIMIT :limit
        """
    )
    suspend fun search(escapedQuery: kotlin.String, limit: Int = 50): List<ConversationEntity>

    /** Like [search] but excludes soft-deleted rows. */
    @Query(
        """
        SELECT * FROM conversations
        WHERE deletedAt IS NULL
          AND (title LIKE '%' || :escapedQuery || '%' ESCAPE '\\'
            OR turnsJson LIKE '%' || :escapedQuery || '%' ESCAPE '\\')
        ORDER BY updatedAt DESC
        LIMIT :limit
        """
    )
    suspend fun searchVisible(escapedQuery: kotlin.String, limit: Int = 50): List<ConversationEntity>

    @Query("SELECT * FROM conversations WHERE deletedAt IS NULL ORDER BY updatedAt DESC LIMIT 1")
    suspend fun mostRecent(): ConversationEntity?

    @Query("SELECT COUNT(*) FROM conversations")
    fun count(): Flow<Int>

    @Query("DELETE FROM conversations WHERE id = :id")
    suspend fun delete(id: String)

    /**
     * Soft-delete: stamp the tombstone instead of removing the row. The
     * History list stops showing it but [getById] still returns it so
     * "Undo" can restore it. Use [restore] to clear the tombstone or
     * [purgeDeletedBefore] to hard-delete tombstones older than the
     * retention window.
     */
    @Query("UPDATE conversations SET deletedAt = :deletedAt WHERE id = :id")
    suspend fun softDelete(id: String, deletedAt: Long)

    /** Clear the soft-delete tombstone. Called by the History "Undo" snackbar. */
    @Query("UPDATE conversations SET deletedAt = NULL WHERE id = :id")
    suspend fun restore(id: String)

    /**
     * Hard-delete tombstones older than the cutoff. The background
     * sweep runs periodically (e.g. on app start) so the table doesn't
     * grow forever with dead rows.
     */
    @Query("DELETE FROM conversations WHERE deletedAt IS NOT NULL AND deletedAt < :cutoff")
    suspend fun purgeDeletedBefore(cutoff: Long): Int

    @Query("DELETE FROM conversations")
    suspend fun deleteAll()

    /**
     * All VISIBLE conversations that have a non-null embedding. Used by
     * [ConversationStore.semanticSearch] to scan for cosine similarity
     * matches. Soft-deleted rows (deletedAt != null) are excluded — they
     * shouldn't be discoverable via search even if their embedding is
     * still in the cache, because the user has chosen to hide them.
     * The embedding byte array stays on the row so undo() can find the
     * conversation quickly; it just isn't surfaced.
     */
    @Query("SELECT * FROM conversations WHERE embedding IS NOT NULL AND deletedAt IS NULL ORDER BY updatedAt DESC")
    suspend fun allWithEmbeddings(): List<ConversationEntity>

    /**
     * Bounded legacy-row source for semantic-search backfill. Same
     * deletedAt filter as [allWithEmbeddings] — backfilling embeddings
     * for soft-deleted rows would just waste an API call.
     */
    @Query("SELECT * FROM conversations WHERE embedding IS NULL AND deletedAt IS NULL ORDER BY updatedAt DESC LIMIT :limit")
    suspend fun missingEmbeddings(limit: Int): List<ConversationEntity>

    /**
     * Update just the embedding column for a conversation. Used by
     * save-time refresh and bounded legacy backfill.
     */
    @Query("UPDATE conversations SET embedding = :embedding WHERE id = :id")
    suspend fun updateEmbedding(id: String, embedding: ByteArray)
}
