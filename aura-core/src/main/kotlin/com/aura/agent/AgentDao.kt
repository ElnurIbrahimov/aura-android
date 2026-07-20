package com.aura.agent

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface AgentDao {
    @Query("SELECT * FROM agents ORDER BY isDefault DESC, name ASC")
    fun all(): Flow<List<AgentEntity>>

    @Query("SELECT * FROM agents ORDER BY isDefault DESC, name ASC")
    suspend fun allOnce(): List<AgentEntity>

    @Query("SELECT * FROM agents WHERE id = :id")
    suspend fun getById(id: String): AgentEntity?

    @Query("SELECT * FROM agents WHERE name = :name LIMIT 1")
    suspend fun byName(name: String): AgentEntity?

    @Query("SELECT * FROM agents WHERE isBuiltin = 1")
    suspend fun builtins(): List<AgentEntity>

    @Query("SELECT * FROM agents WHERE isBuiltin = 0")
    suspend fun customs(): List<AgentEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(agent: AgentEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(agents: List<AgentEntity>)

    @Delete
    suspend fun delete(agent: AgentEntity)

    @Query("DELETE FROM agents WHERE id = :id AND isBuiltin = 0")
    suspend fun deleteCustom(id: String)

    @Query("SELECT COUNT(*) FROM agents")
    suspend fun count(): Int

    @Query("DELETE FROM agents WHERE isBuiltin = 0")
    suspend fun deleteAllCustom()
}