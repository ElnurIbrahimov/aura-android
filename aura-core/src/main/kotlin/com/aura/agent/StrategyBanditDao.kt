package com.aura.agent

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface StrategyBanditDao {
    @Query("SELECT * FROM strategy_bandit WHERE category = :category")
    suspend fun forCategory(category: kotlin.String): List<StrategyBanditEntity>

    @Query("SELECT * FROM strategy_bandit")
    suspend fun all(): List<StrategyBanditEntity>

    @Upsert
    suspend fun upsert(entity: StrategyBanditEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entities: List<StrategyBanditEntity>)

    @Query("UPDATE strategy_bandit SET alpha = alpha + 1, lastUpdated = :now WHERE category = :category AND strategy = :strategy")
    suspend fun incrementAlpha(category: kotlin.String, strategy: kotlin.String, now: Long)

    @Query("UPDATE strategy_bandit SET beta = beta + 1, lastUpdated = :now WHERE category = :category AND strategy = :strategy")
    suspend fun incrementBeta(category: kotlin.String, strategy: kotlin.String, now: Long)

    @Query("DELETE FROM strategy_bandit")
    suspend fun clear()
}
