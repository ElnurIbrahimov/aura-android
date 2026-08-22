package com.aura.dream

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ContradictionDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(contradiction: ContradictionEntity): Long

    @Update
    suspend fun update(contradiction: ContradictionEntity)

    @Query("SELECT * FROM contradictions WHERE id = :id")
    suspend fun byId(id: String): ContradictionEntity?

    @Query("SELECT * FROM contradictions WHERE status = :status ORDER BY createdAt DESC")
    suspend fun byStatus(status: String): List<ContradictionEntity>

    @Query("SELECT * FROM contradictions WHERE status = :status ORDER BY createdAt DESC")
    fun observeByStatus(status: String): Flow<List<ContradictionEntity>>

    @Query("SELECT * FROM contradictions ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<ContradictionEntity>>

    @Query("SELECT * FROM contradictions ORDER BY createdAt DESC")
    suspend fun allForBackup(): List<ContradictionEntity>

    /** Bounded window, for `ChangeLog`. The unbounded queries above serve backup and observation. */
    @Query("SELECT * FROM contradictions WHERE createdAt >= :since ORDER BY createdAt DESC LIMIT :limit")
    suspend fun since(since: Long, limit: Int): List<ContradictionEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(contradictions: List<ContradictionEntity>): List<Long>

    /**
     * Contradictions still unresolved that were noticed since [since].
     *
     * Windowed, and the window is the whole point. This was an all-time count,
     * and **nothing anywhere sets a contradiction to RESOLVED** — so the number
     * could only ever rise, and the COHERENCE drive it feeds could only ever
     * rise with it. ENGINEERING_HISTORY §2.4 records exactly this defect for
     * COMPETENCE, which "had no satisfy() caller at all, so it could only ever
     * climb"; it survived here.
     *
     * A drive should measure how incoherent things are now, not how many
     * contradictions have ever been noticed. Bounding it by time answers that
     * without inventing a resolution nobody performed — the honest fix for the
     * missing RESOLVED writer is a RESOLVED writer, and that is recorded as
     * open rather than faked here.
     */
    @Query("SELECT COUNT(*) FROM contradictions WHERE status = 'UNRESOLVED' AND createdAt >= :since")
    suspend fun unresolvedSince(since: Long): Int

    @Query("SELECT COUNT(*) FROM contradictions WHERE status = 'UNRESOLVED'")
    fun observeUnresolvedCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM contradictions")
    suspend fun count(): Int

    @Query("DELETE FROM contradictions")
    suspend fun deleteAll()
}
