package com.aura.agent.state

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface AgentStateDao {

    @Query("SELECT * FROM agent_state WHERE agentId = :agentId LIMIT 1")
    suspend fun getByAgent(agentId: kotlin.String): AgentStateEntity?

    @Query("SELECT * FROM agent_state")
    fun all(): Flow<List<AgentStateEntity>>

    @Query("SELECT * FROM agent_state")
    suspend fun allOnce(): List<AgentStateEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(state: AgentStateEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(states: List<AgentStateEntity>)

    @Query("UPDATE agent_state SET mood = :mood, energy = :energy, updatedAt = :now WHERE agentId = :agentId")
    suspend fun updateMoodEnergy(agentId: kotlin.String, mood: Float, energy: Float, now: Long)

    @Query("UPDATE agent_state SET currentGoal = :goal, updatedAt = :now WHERE agentId = :agentId")
    suspend fun updateGoal(agentId: kotlin.String, goal: kotlin.String, now: Long)

    @Query("UPDATE agent_state SET participationCount = participationCount + 1, lastActiveAt = :now, updatedAt = :now WHERE agentId = :agentId")
    suspend fun incrementParticipation(agentId: kotlin.String, now: Long)

    @Query("DELETE FROM agent_state WHERE agentId = :agentId")
    suspend fun deleteByAgent(agentId: kotlin.String)

    @Query("DELETE FROM agent_state")
    suspend fun deleteAll()
}

@Dao
interface AgentRelationshipDao {

    @Query("SELECT * FROM agent_relationships WHERE agentAId = :agentId OR agentBId = :agentId")
    suspend fun forAgent(agentId: kotlin.String): List<AgentRelationshipEntity>

    @Query("SELECT * FROM agent_relationships")
    suspend fun allOnce(): List<AgentRelationshipEntity>

    @Query("SELECT * FROM agent_relationships WHERE (agentAId = :a AND agentBId = :b) OR (agentAId = :b AND agentBId = :a) LIMIT 1")
    suspend fun between(a: kotlin.String, b: kotlin.String): AgentRelationshipEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(rel: AgentRelationshipEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(rels: List<AgentRelationshipEntity>)

    @Query("DELETE FROM agent_relationships")
    suspend fun deleteAll()
}

@Dao
interface AgentObservationDao {

    @Query("SELECT * FROM agent_observations WHERE agentId = :agentId ORDER BY createdAt DESC LIMIT :limit")
    suspend fun forAgent(agentId: kotlin.String, limit: Int = 20): List<AgentObservationEntity>

    @Query("SELECT * FROM agent_observations ORDER BY createdAt DESC")
    suspend fun allOnce(): List<AgentObservationEntity>

    @Query("SELECT * FROM agent_observations WHERE agentId = :agentId AND resolved = 0 ORDER BY createdAt DESC LIMIT :limit")
    suspend fun unresolvedForAgent(agentId: kotlin.String, limit: Int = 10): List<AgentObservationEntity>

    @Query("SELECT * FROM agent_observations WHERE targetType = :targetType AND resolved = 0 ORDER BY createdAt DESC LIMIT :limit")
    suspend fun unresolvedByTargetType(targetType: kotlin.String, limit: Int = 20): List<AgentObservationEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(obs: AgentObservationEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(obs: List<AgentObservationEntity>)

    @Query("UPDATE agent_observations SET resolved = 1 WHERE id = :id")
    suspend fun resolve(id: Long)

    @Query("UPDATE agent_observations SET resolved = 1 WHERE agentId = :agentId AND targetType = :targetType")
    suspend fun resolveAllForAgent(agentId: kotlin.String, targetType: kotlin.String)

    @Query("DELETE FROM agent_observations WHERE agentId = :agentId")
    suspend fun deleteByAgent(agentId: kotlin.String)

    @Query("DELETE FROM agent_observations")
    suspend fun deleteAll()
}