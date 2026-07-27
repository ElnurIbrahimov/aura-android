package com.aura.dream

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * DAO for [DreamSummaryEntity].
 *
 * Insert is REPLACE: re-running a cycle on the same cluster updates
 * the existing row instead of double-writing. This is the idempotency
 * contract that [DreamConsolidator.runCycle] depends on.
 */
@Dao
interface DreamConsolidationDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(summary: DreamSummaryEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(summaries: List<DreamSummaryEntity>)

    @Query("SELECT * FROM dream_summaries ORDER BY createdAt DESC")
    suspend fun all(): List<DreamSummaryEntity>

    @Query("SELECT * FROM dream_summaries ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<DreamSummaryEntity>>

    @Query("SELECT * FROM dream_summaries WHERE id = :id")
    suspend fun byId(id: String): DreamSummaryEntity?

    @Query("SELECT * FROM dream_summaries WHERE clusterId = :clusterId")
    suspend fun byClusterId(clusterId: String): DreamSummaryEntity?

    @Query("SELECT COUNT(*) FROM dream_summaries")
    suspend fun count(): Int

    @Query("SELECT COUNT(*) FROM dream_summaries")
    fun observeCount(): Flow<Int>

    /**
     * All clusterIds that have been processed. Used by
     * [DreamConsolidator] to skip memories already tagged with
     * `consolidated:dream_<clusterId>` (they're already represented
     * in a summary, no point re-clustering them).
     */
    @Query("SELECT clusterId FROM dream_summaries")
    suspend fun allClusterIds(): List<String>

    @Query("DELETE FROM dream_summaries WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM dream_summaries")
    suspend fun deleteAll()

    /**
     * For backup. No limit — the personal-use dream table is bounded
     * to a few hundred rows (one per consolidated cluster) so the
     * whole export is small.
     */
    @Query("SELECT * FROM dream_summaries ORDER BY createdAt ASC")
    suspend fun allForBackup(): List<DreamSummaryEntity>
}
