package com.aura.dream

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface KgEdgeProposalDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(proposal: KgEdgeProposalEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(proposals: List<KgEdgeProposalEntity>): List<Long>

    @Update
    suspend fun update(proposal: KgEdgeProposalEntity)

    @Query("SELECT * FROM kg_edge_proposals WHERE id = :id")
    suspend fun byId(id: String): KgEdgeProposalEntity?

    @Query("SELECT * FROM kg_edge_proposals WHERE status = :status ORDER BY similarity DESC, createdAt DESC")
    suspend fun byStatus(status: String): List<KgEdgeProposalEntity>

    @Query("SELECT * FROM kg_edge_proposals WHERE status = :status ORDER BY similarity DESC, createdAt DESC")
    fun observeByStatus(status: String): Flow<List<KgEdgeProposalEntity>>

    @Query("SELECT * FROM kg_edge_proposals ORDER BY similarity DESC, createdAt DESC")
    fun observeAll(): Flow<List<KgEdgeProposalEntity>>

    @Query("SELECT COUNT(*) FROM kg_edge_proposals WHERE status = 'PENDING'")
    suspend fun pendingCount(): Int

    @Query("SELECT COUNT(*) FROM kg_edge_proposals WHERE status = 'PENDING'")
    fun observePendingCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM kg_edge_proposals")
    suspend fun count(): Int

    @Query("DELETE FROM kg_edge_proposals WHERE status = 'PENDING' AND createdAt < :beforeMs")
    suspend fun expireOlderThan(beforeMs: Long): Int

    @Query("DELETE FROM kg_edge_proposals")
    suspend fun deleteAll()
}
