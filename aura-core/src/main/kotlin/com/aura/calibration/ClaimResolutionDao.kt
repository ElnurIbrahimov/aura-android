package com.aura.calibration

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Upsert

/**
 * Reads and writes for [ClaimResolutionEntity].
 *
 * `insert` is a plain `@Insert` — ABORT on conflict — and never
 * `@Insert(onConflict = REPLACE)`. `beliefs` is the CASCADE parent of this
 * table, and `INSERT OR REPLACE` is a DELETE followed by an INSERT, so a
 * REPLACE-shaped write here would be a live footgun the moment anyone reused it
 * upward. `CascadeParentReplaceAuditTest` holds the rule permanently;
 * `CreativeProjectDao` carries the post-mortem of it costing thirteen scenes.
 */
@Dao
interface ClaimResolutionDao {

    @Insert
    suspend fun insert(row: ClaimResolutionEntity)

    /** Restore only. Ids come from the backup, so a collision is a re-import. */
    @Upsert
    suspend fun upsertAll(rows: List<ClaimResolutionEntity>)

    @Query("SELECT * FROM claim_resolutions WHERE beliefId = :beliefId ORDER BY resolvedAt DESC")
    suspend fun forBelief(beliefId: String): List<ClaimResolutionEntity>

    /**
     * Belief ids already resolved, so the question author skips them.
     *
     * A belief is asked about once. Re-asking a claim the user has already ruled
     * on is the fastest way to make the verification questions ignorable, and an
     * ignored question is an empty sample, which is the whole feature.
     */
    @Query("SELECT DISTINCT beliefId FROM claim_resolutions")
    suspend fun resolvedBeliefIds(): List<String>

    /**
     * Everything with a right/wrong signal, newest first.
     *
     * Filters to [ClaimResolutionEntity.SCORED] in SQL rather than leaving it to
     * the caller: `no_longer_true` reaching a rate calculation is the defect this
     * whole design is shaped to avoid, and a filter every caller must remember
     * is one a caller will eventually forget.
     */
    @Query(
        "SELECT * FROM claim_resolutions WHERE verdict IN ('never_true', 'confirmed') " +
            "AND resolvedAt >= :since ORDER BY resolvedAt DESC LIMIT :limit",
    )
    suspend fun scoredSince(since: Long, limit: Int = 500): List<ClaimResolutionEntity>

    /** Everything, including the unscorable — the Mind screen shows the split. */
    @Query("SELECT COUNT(*) FROM claim_resolutions")
    suspend fun totalCount(): Int

    @Query("SELECT * FROM claim_resolutions ORDER BY resolvedAt ASC")
    suspend fun allForBackup(): List<ClaimResolutionEntity>

    @Query("DELETE FROM claim_resolutions")
    suspend fun deleteAll()
}
