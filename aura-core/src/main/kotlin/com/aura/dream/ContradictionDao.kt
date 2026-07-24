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

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(contradictions: List<ContradictionEntity>): List<Long>

    @Query("SELECT COUNT(*) FROM contradictions WHERE status = 'UNRESOLVED'")
    suspend fun unresolvedCount(): Int

    @Query("SELECT COUNT(*) FROM contradictions WHERE status = 'UNRESOLVED'")
    fun observeUnresolvedCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM contradictions")
    suspend fun count(): Int

    @Query("DELETE FROM contradictions WHERE status IN ('RESOLVED', 'DISMISSED') AND createdAt < :beforeMs")
    suspend fun purgeOldResolved(beforeMs: Long): Int

    @Query("DELETE FROM contradictions")
    suspend fun deleteAll()
}
