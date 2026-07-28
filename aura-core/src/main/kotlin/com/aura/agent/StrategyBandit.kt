package com.aura.agent

import android.util.Log
import kotlin.math.ln
import kotlin.random.Random
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reasoning strategies the bandit can select.
 *
 * - SINGLE_PASS: maxSteps=5, no planning. For simple Q&A and quick tool calls.
 * - MULTI_STEP_REFLECT: maxSteps=15, planning enabled, reflection on failure.
 *   For complex multi-tool tasks (research, debugging, multi-source synthesis).
 * - CREATIVE_PASS: maxSteps=3, no planning, higher temperature. For creative
 *   writing where over-thinking kills the output.
 */
enum class ReasoningStrategy {
    SINGLE_PASS,
    MULTI_STEP_REFLECT,
    CREATIVE_PASS,
    ;

    /** Max agentic loop steps for this strategy. */
    val maxSteps: Int get() = when (this) {
        SINGLE_PASS -> 5
        MULTI_STEP_REFLECT -> 15
        CREATIVE_PASS -> 3
    }

    /** Whether to enable the planning step. */
    val enablePlanning: Boolean get() = this == MULTI_STEP_REFLECT
}

/**
 * Problem categories for strategy routing. Classified by keyword matching
 * on the user's message — same style as [SpecialistRouter]. Must be instant
 * (no LLM call) because it runs before every message.
 */
enum class ProblemCategory {
    MATH,
    CODE,
    ANALYSIS,
    CREATIVE,
    PLANNING,
    DEBUG,
    CONVERSATION,
    ;

    companion object {
        /**
         * Classify the user's message into a problem category.
         *
         * Uses keyword matching — same approach as [SpecialistRouter].
         * Returns [CONVERSATION] as the default fallback for messages
         * that don't match any specific category.
         */
        fun classify(userMessage: kotlin.String, specialist: Specialist?): ProblemCategory {
            val lower = userMessage.lowercase()

            // If a specialist is already selected, use it as a strong hint.
            when (specialist?.name) {
                "coder" -> if (lower.contains("debug") || lower.contains("error") || lower.contains("fix")) return DEBUG else return CODE
                "researcher" -> return ANALYSIS
                "writer", "creative" -> return CREATIVE
                "executive" -> return PLANNING
            }

            // Keyword-based classification (order matters — most specific first)
            if (matchesAny(lower, setOf("debug", "stack trace", "exception", "fix this", "not working", "broken", "crash", "why is it", "error message"))) return DEBUG
            if (matchesAny(lower, setOf("analyze", "compare", "research", "investigate", "evaluate", "pros and cons", "trade-off", "breakdown"))) return ANALYSIS
            if (matchesAny(lower, setOf("code", "function", "class", "method", "kotlin", "python", "java", "gradle", "compile", "build", "refactor", "program", "algorithm", "typescript", "rust"))) return CODE
            if (matchesAny(lower, setOf("calculate", "solve", "equation", "integral", "derivative", "matrix", "prove", "theorem", "sum of", "probability"))) return MATH
            if (matchesAny(lower, setOf("write", "story", "novel", "poem", "scene", "dialogue", "character", "screenplay", "fiction", "prose", "lyrics"))) return CREATIVE
            if (matchesAny(lower, setOf("plan", "schedule", "organize", "roadmap", "strategy", "steps for", "how do i", "what should i do"))) return PLANNING

            return CONVERSATION
        }

        private fun matchesAny(lower: kotlin.String, keywords: Set<kotlin.String>): Boolean =
            keywords.any { kw -> lower.contains(kw) }
    }
}

/**
 * Thompson Sampling over reasoning strategies per problem category.
 *
 * Each (category, strategy) pair has a Beta(α, β) distribution. On each
 * request, we sample from each strategy's Beta and pick the highest
 * sample. After the run completes, we update α (success) or β (failure).
 *
 * "Success" = the agentic loop finished without hitting max_steps_exceeded.
 * This is a proxy for "the strategy was appropriate for the task" — if
 * the loop ran out of steps, the strategy was too conservative (or the
 * task was too hard for that strategy).
 *
 * The bandit is persisted in Room via [StrategyBanditStore] so it
 * survives restarts. 7 categories × 3 strategies = 21 rows max.
 *
 * Invisible to the user — no Settings UI, no visible routing. The user
 * just feels the assistant getting better at choosing its approach over
 * time.
 */
@Singleton
class StrategyBandit @Inject constructor(
    private val store: StrategyBanditStore,
) {
    /**
     * Select the best reasoning strategy for [category] using Thompson Sampling.
     *
     * Samples from Beta(α, β) for each strategy in the category, picks the
     * highest sample. With uniform priors (α=1, β=1), all strategies are
     * equally likely initially. As the bandit learns, the best strategy
     * for each category converges.
     */
    suspend fun selectStrategy(category: ProblemCategory): ReasoningStrategy {
        val arms = store.getArms(category)
        if (arms.isEmpty()) return ReasoningStrategy.MULTI_STEP_REFLECT // safe default

        var bestSample = Double.NEGATIVE_INFINITY
        var bestStrategy = ReasoningStrategy.MULTI_STEP_REFLECT

        for ((strategy, alpha, beta) in arms) {
            val sample = sampleBeta(alpha, beta)
            if (sample > bestSample) {
                bestSample = sample
                bestStrategy = strategy
            }
        }
        return bestStrategy
    }

    /**
     * Record the outcome of a run. Call after the agentic loop completes.
     * Success increments α; failure increments β.
     */
    suspend fun recordOutcome(category: ProblemCategory, strategy: ReasoningStrategy, success: Boolean) {
        runCatching {
            store.recordOutcome(category, strategy, success)
        }.onFailure { Log.w("StrategyBandit", "recordOutcome failed: ${it.message}") }
    }

    /**
     * Sample from a Beta(α, β) distribution using the log-ratio method.
     * Returns a value in [0, 1].
     */
    private fun sampleBeta(alpha: Double, beta: Double): Double {
        if (alpha <= 0.0 || beta <= 0.0) return Random.nextDouble()
        val x = sampleGamma(alpha)
        val y = sampleGamma(beta)
        return x / (x + y)
    }

    /**
     * Sample from Gamma(shape) using Marsaglia-Tsang method.
     * For shape >= 1, uses the standard method. For shape < 1, uses
     * the boosting factor from Marsaglia-Tsang.
     */
    private fun sampleGamma(shape: Double): Double {
        if (shape < 1.0) {
            // Boost: Gamma(shape) = Gamma(shape + 1) * U^(1/shape)
            val u = Random.nextDouble()
            return sampleGamma(shape + 1.0) * Math.pow(u, 1.0 / shape)
        }
        val d = shape - 1.0 / 3.0
        val c = 1.0 / Math.sqrt(9.0 * d)
        while (true) {
            var x: Double
            var v: Double
            do {
                x = java.util.Random().nextGaussian()
                v = 1.0 + c * x
            } while (v <= 0.0)
            v = v * v * v
            val u = Random.nextDouble()
            if (u < 1.0 - 0.0331 * x * x * x * x) return d * v
            if (ln(u) < 0.5 * x * x + d * (1.0 - v + ln(v))) return d * v
        }
    }
}
