package com.aura.agent

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Wraps [StrategyBanditDao] with a clean API for the [StrategyBandit].
 * Seeds default rows (α=1, β=1) on first access for all 7×3 = 21 pairs.
 */
@Singleton
class StrategyBanditStore @Inject constructor(
    private val dao: StrategyBanditDao,
) {
    suspend fun getArms(category: ProblemCategory): List<Triple<ReasoningStrategy, Double, Double>> {
        val rows = dao.forCategory(category.name)
        if (rows.isEmpty()) {
            // Seed defaults for this category
            seedCategory(category)
            return ReasoningStrategy.values().map { it to Triple(it, 1.0, 1.0) }.map { it.second }
        }
        return rows.mapNotNull { row ->
            ReasoningStrategy.values().find { it.name == row.strategy }
                ?.let { Triple(it, row.alpha, row.beta) }
        }
    }

    suspend fun recordOutcome(category: ProblemCategory, strategy: ReasoningStrategy, success: Boolean) {
        val now = System.currentTimeMillis()
        // Ensure the row exists
        val rows = dao.forCategory(category.name)
        if (rows.none { it.strategy == strategy.name }) {
            dao.upsert(StrategyBanditEntity(category = category.name, strategy = strategy.name, alpha = 1.0, beta = 1.0, lastUpdated = now))
        }
        if (success) {
            dao.incrementAlpha(category.name, strategy.name, now)
        } else {
            dao.incrementBeta(category.name, strategy.name, now)
        }
    }

    private suspend fun seedCategory(category: ProblemCategory) {
        val now = System.currentTimeMillis()
        for (strategy in ReasoningStrategy.values()) {
            dao.upsert(StrategyBanditEntity(
                category = category.name,
                strategy = strategy.name,
                alpha = 1.0,
                beta = 1.0,
                lastUpdated = now,
            ))
        }
    }
}
