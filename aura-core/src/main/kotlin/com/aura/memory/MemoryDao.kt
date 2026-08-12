package com.aura.memory

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface MemoryDao {
    @Upsert
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

    @Query("SELECT * FROM memories WHERE retiredAt IS NULL ORDER BY createdAt DESC LIMIT :limit")
    suspend fun recent(limit: Int = 50): List<MemoryEntity>

    /**
     * Memories created at or after [sinceMs], newest first. Bounded by
     * [limit] so the morning brief can show "what you learned in the
     * last 24h" without scanning the full table on large installs.
     */
    @Query("SELECT * FROM memories WHERE createdAt >= :sinceMs AND retiredAt IS NULL ORDER BY createdAt DESC LIMIT :limit")
    suspend fun recentSince(sinceMs: Long, limit: Int = 20): List<MemoryEntity>

    /**
     * Memories whose [MemoryEntity.decayScore] is at or below
     * [threshold]. Used by the morning brief to surface "X memories
     * are fading." Returns up to [limit] rows ordered by decayScore
     * ASC (most-faded first).
     */
    @Query("SELECT * FROM memories WHERE decayScore <= :threshold AND retiredAt IS NULL ORDER BY decayScore ASC LIMIT :limit")
    suspend fun decayedBelow(threshold: Float, limit: Int = 20): List<MemoryEntity>

    @Query("SELECT * FROM memories WHERE category = :category AND retiredAt IS NULL ORDER BY createdAt DESC LIMIT :limit")
    suspend fun byCategory(category: String, limit: Int = 50): List<MemoryEntity>

    @Query("SELECT * FROM memories WHERE scope = :scope AND retiredAt IS NULL ORDER BY accessedAt DESC LIMIT :limit")
    suspend fun byScope(scope: String, limit: Int = 50): List<MemoryEntity>

    @Query("SELECT * FROM memories WHERE (scope = 'general' OR scope LIKE :scopePrefix) AND retiredAt IS NULL ORDER BY accessedAt DESC LIMIT :limit")
    suspend fun withinScope(scopePrefix: String, limit: Int = 50): List<MemoryEntity>

    @Query("SELECT * FROM memories WHERE scope IN (:scopes) AND retiredAt IS NULL ORDER BY createdAt DESC LIMIT :limit")
    suspend fun byScopes(scopes: List<String>, limit: Int = 50): List<MemoryEntity>

    @Query(
        "SELECT * FROM memories WHERE scope IN (:scopes) AND content LIKE :query ESCAPE '\\' " +
            "AND retiredAt IS NULL ORDER BY decayScore DESC LIMIT :limit",
    )
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
            "WHERE f.content MATCH :ftsQuery AND m.scope IN (:scopes) AND m.retiredAt IS NULL " +
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
    @Query("SELECT COUNT(*) FROM memories WHERE scope IN (:scopes) AND retiredAt IS NULL")
    suspend fun countInScopes(scopes: List<String>): Int

    /**
     * How many scoped memories contain [ftsQuery] — the `df` in BM25's IDF.
     * One indexed FTS probe per query term; the caller bounds how many terms
     * it asks about.
     */
    @Query(
        "SELECT COUNT(*) FROM memories m JOIN memories_fts f ON f.rowid = m.rowid " +
            "WHERE f.content MATCH :ftsQuery AND m.scope IN (:scopes) AND m.retiredAt IS NULL",
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

    @Query("SELECT * FROM memories WHERE scope IN (:scopes) AND retiredAt IS NULL ORDER BY createdAt DESC")
    suspend fun allByScopes(scopes: List<String>): List<MemoryEntity>

    /**
     * Bounded candidate set for the vector-fallback scan. Orders by
     * activity (accessCount then decayScore) so the recall scan is
     * capped at [limit] rows instead of loading the entire scoped
     * table and decoding every embedding into memory.
     */
    @Query(
        "SELECT * FROM memories WHERE scope IN (:scopes) AND embedding IS NOT NULL AND retiredAt IS NULL " +
            "ORDER BY accessCount DESC, decayScore DESC LIMIT :limit"
    )
    suspend fun vectorScanCandidates(scopes: List<String>, limit: Int): List<MemoryEntity>

    @Query(
        "SELECT * FROM memories WHERE content LIKE :query ESCAPE '\\' AND retiredAt IS NULL " +
            "ORDER BY decayScore DESC LIMIT :limit",
    )
    suspend fun searchByText(query: String, limit: Int = 50): List<MemoryEntity>

    @Query("SELECT * FROM memories WHERE retiredAt IS NULL ORDER BY decayScore DESC LIMIT :limit")
    suspend fun top(limit: Int = 50): List<MemoryEntity>

    /**
     * Facts that mattered once and have never been confirmed since.
     *
     * Old, important, still live, and never corrected. These are the memories
     * most likely to be quietly wrong — a job, a city, a preference stated a
     * year ago and true at the time — and the ones decay handles worst, because
     * importance keeps them fresh precisely when they are least verifiable.
     *
     * Excludes anything already corrected: the user has spoken about it, and
     * asking again would be asking them to repeat themselves.
     */
    @Query(
        "SELECT * FROM memories m WHERE m.retiredAt IS NULL " +
            "AND m.importance >= :minImportance AND m.createdAt <= :olderThan " +
            "AND m.category IN ('fact', 'preference', 'person', 'project') " +
            "AND NOT EXISTS (SELECT 1 FROM corrections c WHERE c.targetId = m.id AND c.undoneAt IS NULL) " +
            "ORDER BY m.importance DESC, m.createdAt ASC LIMIT :limit",
    )
    suspend fun staleAssumptions(
        minImportance: Float,
        olderThan: Long,
        limit: Int = 20,
    ): List<MemoryEntity>

    /**
     * All memories, ordered by createdAt ascending so the export is
     * deterministic. Used by the backup module. No limit — the
     * export wants everything, even if the personal-use table is
     * bounded to a few thousand rows.
     */
    @Query("SELECT * FROM memories ORDER BY createdAt ASC")
    suspend fun allForExport(): List<MemoryEntity>

    /**
     * How many rows carry a vector from something other than [model].
     *
     * Keyed on the MODEL, not on `embedding IS NULL`. `rebuildEmbeddings` used
     * the null test, and a model change nulls nothing — so switching the
     * embedding model in Settings re-embedded exactly zero rows while every
     * existing vector silently stopped meaning anything.
     */
    @Query(
        "SELECT COUNT(*) FROM memories " +
            "WHERE retiredAt IS NULL " +
            "AND (embedding IS NULL OR embeddingModel IS NULL OR embeddingModel != :model)",
    )
    suspend fun countNeedingReembed(model: String): Int

    /**
     * A page of rows needing re-embedding, most-used first.
     *
     * Ordered by activity so an interrupted rebuild fixes the memories that
     * actually get recalled before the ones that do not — the same rationale as
     * [vectorScanCandidates]. Paged rather than loaded whole: [allForExport]
     * pulls every row WITH its embedding BLOB into memory, which is the O(N)
     * heap churn already fixed twice elsewhere in this file.
     */
    @Query(
        "SELECT * FROM memories " +
            "WHERE retiredAt IS NULL " +
            "AND (embedding IS NULL OR embeddingModel IS NULL OR embeddingModel != :model) " +
            "ORDER BY accessCount DESC, decayScore DESC LIMIT :limit",
    )
    suspend fun needingReembed(model: String, limit: Int): List<MemoryEntity>

    /**
     * Bulk insert for the import path. Skips individual inserts to
     * avoid N round-trips to Room.
     */
    @Upsert
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

    /**
     * Retire a memory instead of deleting it: it stops being retrievable and
     * stays readable by id, so a rollback restores the row rather than a copy
     * of it reconstructed from a snapshot.
     *
     * The `retiredAt IS NULL` guard makes this idempotent — re-running an apply
     * after a crash must not overwrite the first retirement's reason or move
     * its timestamp forward.
     */
    @Query(
        "UPDATE memories SET retiredAt = :now, supersededBy = :supersededBy, retiredReason = :reason " +
            "WHERE id = :id AND retiredAt IS NULL",
    )
    suspend fun retire(id: String, supersededBy: String?, reason: String, now: Long): Int

    /** Undo [retire]. */
    @Query("UPDATE memories SET retiredAt = NULL, supersededBy = NULL, retiredReason = NULL WHERE id = :id")
    suspend fun unretire(id: String)

    /** Retired rows, newest first — the history view and rollback both read this. */
    @Query("SELECT * FROM memories WHERE retiredAt IS NOT NULL ORDER BY retiredAt DESC LIMIT :limit")
    suspend fun retired(limit: Int = 100): List<MemoryEntity>

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

    @Query("SELECT COUNT(*) FROM memories WHERE retiredAt IS NULL")
    fun count(): Flow<Int>

    @Query("SELECT COUNT(*) FROM memories WHERE retiredAt IS NULL")
    suspend fun countOnce(): Int

    /**
     * Check whether a memory with the exact content already exists.
     * Used by [MemoryStore.maybeStore] to deduplicate — if the user
     * says "I prefer dark mode" across three conversations, only the
     * first one should be stored.
     */
    @Query("SELECT COUNT(*) FROM memories WHERE content = :content AND retiredAt IS NULL LIMIT 1")
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
    @Query("SELECT * FROM memories WHERE embedding IS NOT NULL AND retiredAt IS NULL ORDER BY createdAt DESC LIMIT :limit")
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

    /**
     * Memories the user has marked unhelpful more often than helpful.
     *
     * The first reader this table has ever had. Rows have been accumulating
     * since the Helpful / Not helpful control shipped, read by nothing —
     * not retrieval, not the detectors, not the outcome scorer — so pressing
     * "Not helpful" a dozen times changed exactly nothing about what Aura
     * recalled or proposed.
     */
    @Query(
        "SELECT memoryId FROM memory_feedback GROUP BY memoryId HAVING " +
            "SUM(CASE WHEN kind = 'downvote' THEN 1 ELSE 0 END) > " +
            "SUM(CASE WHEN kind = 'upvote' THEN 1 ELSE 0 END)",
    )
    suspend fun netDownvotedMemoryIds(): List<String>

    @Query("DELETE FROM memory_feedback")
    suspend fun deleteAll()
}
