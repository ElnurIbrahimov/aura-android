package com.aura.agent

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One row per (category, strategy) pair in the strategy bandit.
 * α and β are the Beta distribution parameters. α=1, β=1 is the
 * uniform prior (no preference). Updated by [StrategyBandit.recordOutcome].
 */
@Entity(tableName = "strategy_bandit")
data class StrategyBanditEntity(
    @PrimaryKey val category: kotlin.String,
    val strategy: kotlin.String,
    val alpha: Double = 1.0,
    val beta: Double = 1.0,
    val lastUpdated: Long = 0L,
)
