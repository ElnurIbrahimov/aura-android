package com.aura.dream

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * DAO for [RoutineEntity]. Insert is REPLACE: re-running a cycle on
 * the same N-gram updates the existing row's occurrence count and
 * last-seen timestamp instead of double-writing. This is the
 * idempotency contract that [DreamConsolidator.extractRoutines]
 * depends on.
 */
@Dao
interface RoutineDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(routine: RoutineEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(routines: List<RoutineEntity>)

    @Query("SELECT * FROM routines ORDER BY occurrenceCount DESC, lastSeenAt DESC")
    suspend fun all(): List<RoutineEntity>

    @Query("SELECT * FROM routines ORDER BY occurrenceCount DESC, lastSeenAt DESC")
    fun observeAll(): Flow<List<RoutineEntity>>

    @Query("SELECT * FROM routines WHERE id = :id")
    suspend fun byId(id: String): RoutineEntity?

    @Query("SELECT * FROM routines WHERE signature = :signature")
    suspend fun bySignature(signature: String): RoutineEntity?

    @Query("SELECT COUNT(*) FROM routines")
    suspend fun count(): Int

    @Query("SELECT COUNT(*) FROM routines")
    fun observeCount(): Flow<Int>

    /**
     * All signatures that have been recorded. Used by
     * [DreamConsolidator.extractRoutines] to decide whether a
     * candidate N-gram is new or already in the table.
     */
    @Query("SELECT signature FROM routines")
    suspend fun allSignatures(): List<String>

    @Query("DELETE FROM routines")
    suspend fun deleteAll()

    /**
     * For backup. Bounded — even after a year of use there should be
     * no more than a few hundred routines.
     */
    @Query("SELECT * FROM routines ORDER BY occurrenceCount DESC")
    suspend fun allForBackup(): List<RoutineEntity>
}
