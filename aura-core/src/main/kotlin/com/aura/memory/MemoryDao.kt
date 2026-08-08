package com.aura.memory

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface MemoryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(memory: MemoryEntity)

    @Update
    suspend fun update(memory: MemoryEntity)

    /**
     * Batch update for decay pass and other bulk operations. Room
     * wraps the varargs in a single transaction, avoiding N+1
     * individual UPDATE statements when recomputing decay scores
     * for thousands of memories.
     */
    @Update
    suspend fun updateAll(memories: List<MemoryEntity>)

    @Query("SELECT * FROM memories WHERE id = :id")
    suspend fun getById(id: String): MemoryEntity?

    @Query("SELECT * FROM memories ORDER BY createdAt DESC LIMIT :limit")
    suspend fun recent(limit: Int = 50): List<MemoryEntity>

    /**
     * Memories created at or after [sinceMs], newest first. Bounded by
     * [limit] so the morning brief can show "what you learned in the
     * last 24h" without scanning the full table on large installs.
     */
    @Query("SELECT * FROM memories WHERE createdAt >= :sinceMs ORDER BY createdAt DESC LIMIT :limit")
    suspend fun recentSince(sinceMs: Long, limit: Int = 20): List<MemoryEntity>

    /**
     * Memories whose [MemoryEntity.decayScore] is at or below
     * [threshold]. Used by the morning brief to surface "X memories
     * are fading." Returns up to [limit] rows ordered by decayScore
     * ASC (most-faded first).
     */
    @Query("SELECT * FROM memories WHERE decayScore <= :threshold ORDER BY decayScore ASC LIMIT :limit")
    suspend fun decayedBelow(threshold: Float, limit: Int = 20): List<MemoryEntity>

    @Query("SELECT * FROM memories WHERE category = :category ORDER BY createdAt DESC LIMIT :limit")
    suspend fun byCategory(category: String, limit: Int = 50): List<MemoryEntity>

    @Query("SELECT * FROM memories WHERE scope = :scope ORDER BY accessedAt DESC LIMIT :limit")
    suspend fun byScope(scope: String, limit: Int = 50): List<MemoryEntity>

    @Query("SELECT * FROM memories WHERE scope = 'general' OR scope LIKE :scopePrefix ORDER BY accessedAt DESC LIMIT :limit")
    suspend fun withinScope(scopePrefix: String, limit: Int = 50): List<MemoryEntity>

    @Query("SELECT * FROM memories WHERE scope IN (:scopes) ORDER BY createdAt DESC LIMIT :limit")
    suspend fun byScopes(scopes: List<String>, limit: Int = 50): List<MemoryEntity>

    @Query("SELECT * FROM memories WHERE scope IN (:scopes) AND content LIKE :query ESCAPE '\\' ORDER BY decayScore DESC LIMIT :limit")
    suspend fun searchByTextInScopes(query: String, scopes: List<String>, limit: Int = 50): List<MemoryEntity>

    /**
     * Lexical candidate fetch for recall, over the FTS index.
     *
     * Replaced `searchByWordsInScopes(word1..word6, …)`, whose six `LIKE
     * '%w%'` clauses imposed a hard six-term ceiling in the DAO signature and
     * forced a full table scan (a leading-wildcard LIKE cannot use an index).
     * [ftsQuery] is an already-escaped FTS4 MATCH expression — build it with
     * [FtsQuery.build], never by string-concatenating user input.
     *
     * Matches `f.content`, not the bare table, so a query term cannot match a
     * `memoryId`.
     */
    @Query(
        "SELECT m.* FROM memories m JOIN memories_fts f ON f.rowid = m.rowid " +
            "WHERE f.content MATCH :ftsQuery AND m.scope IN (:scopes) " +
            "ORDER BY m.decayScore DESC LIMIT :limit",
    )
    suspend fun searchFts(ftsQuery: String, scopes: List<String>, limit: Int = 50): List<MemoryEntity>

    /**
     * Corpus size for the scoped set — the `N` in BM25's IDF.
     *
     * BM25 previously took `N` from the candidate list it was handed, which was
     * the set of rows that had already matched a query term. That made
     * `ln((N - df + 0.5) / (df + 0.5))` negative for every query term and
     * clamped them all to the same floor, so the lexical signal ranked
     * essentially at random.
     */
    @Query("SELECT COUNT(*) FROM memories WHERE scope IN (:scopes)")
    suspend fun countInScopes(scopes: List<String>): Int

    /**
     * How many scoped memories contain [ftsQuery] — the `df` in BM25's IDF.
     * One indexed FTS probe per query term; the caller bounds how many terms
     * it asks about.
     */
    @Query(
        "SELECT COUNT(*) FROM memories m JOIN memories_fts f ON f.rowid = m.rowid " +
            "WHERE f.content MATCH :ftsQuery AND m.scope IN (:scopes)",
    )
    suspend fun docFreqInScopes(ftsQuery: String, scopes: List<String>): Int

    @Insert
    suspend fun insertEdit(edit: MemoryEditEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEdits(edits: List<MemoryEditEntity>)

    /**
     * Apply a memory edit and record its audit-trail row in ONE
     * transaction. Without this, a crash between the two writes leaves
     * the audit trail claiming a different state than the row.
     */
    @Transaction
    suspend fun updateWithAudit(entity: MemoryEntity, edit: MemoryEditEntity) {
        insertEdit(edit)
        update(entity)
    }

    /**
     * Reinsert a soft-deleted memory and its CASCADE-deleted audit
     * trail atomically (undo path).
     */
    @Transaction
    suspend fun restoreWithAudit(memory: MemoryEntity, edits: List<MemoryEditEntity>) {
        insert(memory)
        insertEdits(edits)
    }

    /**
     * Batch-insert memories plus their audit rows atomically (backup
     * restore path, same database).
     */
    @Transaction
    suspend fun insertAllWithEdits(rows: List<MemoryEntity>, edits: List<MemoryEditEntity>) {
        insertAll(rows)
        insertEdits(edits)
    }

    @Query("SELECT * FROM memories WHERE scope IN (:scopes) ORDER BY createdAt DESC")
    suspend fun allByScopes(scopes: List<String>): List<MemoryEntity>

    /**
     * Bounded candidate set for the vector-fallback scan. Orders by
     * activity (accessCount then decayScore) so the recall scan is
     * capped at [limit] rows instead of loading the entire scoped
     * table and decoding every embedding into memory.
     */
    @Query(
        "SELECT * FROM memories WHERE scope IN (:scopes) AND embedding IS NOT NULL " +
            "ORDER BY accessCount DESC, decayScore DESC LIMIT :limit"
    )
    suspend fun vectorScanCandidates(scopes: List<String>, limit: Int): List<MemoryEntity>

    @Query("SELECT * FROM memories WHERE content LIKE :query ESCAPE '\\' ORDER BY decayScore DESC LIMIT :limit")
    suspend fun searchByText(query: String, limit: Int = 50): List<MemoryEntity>

    @Query("SELECT * FROM memories ORDER BY decayScore DESC LIMIT :limit")
    suspend fun top(limit: Int = 50): List<MemoryEntity>

    /**
     * All memories, ordered by createdAt ascending so the export is
     * deterministic. Used by the backup module. No limit — the
     * export wants everything, even if the personal-use table is
     * bounded to a few thousand rows.
     */
    @Query("SELECT * FROM memories ORDER BY createdAt ASC")
    suspend fun allForExport(): List<MemoryEntity>

    /**
     * Bulk insert for the import path. Skips individual inserts to
     * avoid N round-trips to Room.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(rows: List<MemoryEntity>)

    @Query("UPDATE memories SET accessedAt = :now, accessCount = accessCount + 1, decayScore = MIN(1.0, decayScore + 0.1) WHERE id = :id")
    suspend fun touch(id: String, now: Long = System.currentTimeMillis())

    @Query("UPDATE memories SET decayScore = :decayScore WHERE id = :id")
    suspend fun updateDecayScore(id: String, decayScore: Float)

    /**
     * Tags-only update. Unlike the user-edit path this preserves the
     * embedding, accessedAt, and audit trail — for background
     * bookkeeping (dream consolidation) that must not look like a user
     * edit or knock the row out of vector recall.
     */
    @Query("UPDATE memories SET tags = :tags WHERE id = :id")
    suspend fun updateTags(id: String, tags: String)

    @Query("DELETE FROM memories WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM memories WHERE source = :source")
    suspend fun deleteBySource(source: String)

    @Query("DELETE FROM memories")
    suspend fun deleteAll()

    /**
     * Delete all memories in a given category. Used by the bulk
     * "clear category" action in the Memory screen.
     */
    @Query("DELETE FROM memories WHERE category = :category")
    suspend fun deleteByCategory(category: String)

    @Query("UPDATE memories SET decayScore = decayScore * :factor WHERE createdAt < :cutoff")
    suspend fun applyDecay(cutoff: Long, factor: Float)

    @Query("SELECT COUNT(*) FROM memories")
    fun count(): Flow<Int>

    @Query("SELECT COUNT(*) FROM memories")
    suspend fun countOnce(): Int

    /**
     * Check whether a memory with the exact content already exists.
     * Used by [MemoryStore.maybeStore] to deduplicate — if the user
     * says "I prefer dark mode" across three conversations, only the
     * first one should be stored.
     */
    @Query("SELECT COUNT(*) FROM memories WHERE content = :content LIMIT 1")
    suspend fun existsByContent(content: String): Int

    /**
     * All memories that have a non-null embedding. Used by
     * [MemoryDaoContractTest]; production dedup uses the bounded
     * [recentWithEmbeddings] instead.
     */
    @Query("SELECT * FROM memories WHERE embedding IS NOT NULL ORDER BY createdAt DESC")
    suspend fun allWithEmbeddings(): List<MemoryEntity>

    /**
     * The most recent [limit] memories with a non-null embedding. Used by
     * the semantic dedup check in [MemoryStore.maybeStore] — after
     * computing the new content's embedding, we scan recent embeddings for
     * cosine similarity > threshold to catch "I like dark mode" vs
     * "I prefer dark mode" which exact-match would miss. Bounded because
     * the previous full-table scan decoded every embedding in the DB under
     * the insert mutex on every auto-store.
     */
    @Query("SELECT * FROM memories WHERE embedding IS NOT NULL ORDER BY createdAt DESC LIMIT :limit")
    suspend fun recentWithEmbeddings(limit: Int): List<MemoryEntity>

    /**
     * Update the category of all memories currently in [oldCategory].
     * Used by the rename and merge category actions in the Memory screen.
     */
    @Query("UPDATE memories SET category = :newCategory WHERE category = :oldCategory")
    suspend fun updateCategory(oldCategory: String, newCategory: String)
}

@Dao
interface MemoryFeedbackDao {
    @Insert
    suspend fun insert(row: MemoryFeedbackEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(rows: List<MemoryFeedbackEntity>)

    @Query("SELECT * FROM memory_feedback ORDER BY createdAt ASC")
    suspend fun all(): List<MemoryFeedbackEntity>

    @Query("SELECT * FROM memory_feedback WHERE memoryId = :memoryId ORDER BY createdAt DESC LIMIT :limit")
    suspend fun byMemoryId(memoryId: String, limit: Int = 20): List<MemoryFeedbackEntity>

    @Query("SELECT COUNT(*) FROM memory_feedback WHERE memoryId = :memoryId AND kind = :kind")
    suspend fun count(memoryId: String, kind: String): Int

    @Query("DELETE FROM memory_feedback")
    suspend fun deleteAll()
}
