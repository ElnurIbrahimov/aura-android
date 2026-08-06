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

    /**
     * Bandit arms with at least 3 real observations whose mean success is
     * below 0.5 — the COMPETENCE signal for
     * [com.aura.consciousness.DriveSignals]. Arms start at the Beta(1,1)
     * prior (alpha + beta = 2), so >= 3 observations means
     * alpha + beta >= 5. "Mean success < 0.5" is alpha / (alpha+beta) < 0.5,
     * written division-free as alpha * 2 < alpha + beta.
     */
    @Query(
        """
        SELECT COUNT(*) FROM strategy_bandit
        WHERE (alpha + beta) >= 5.0 AND alpha * 2 < (alpha + beta)
        """
    )
    suspend fun lowConfidenceCount(): Int
}
