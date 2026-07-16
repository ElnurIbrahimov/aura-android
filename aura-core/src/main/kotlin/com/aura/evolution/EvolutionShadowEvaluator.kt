package com.aura.evolution

/**
 * Lightweight shadow evaluator for proposed evolutions. Given a baseline
 * artifact (e.g. a skill body or memory text) and a variant produced by the
 * evolution pipeline, it returns deterministic, interpretable metrics without
 * calling an LLM. This lets the system keep a "shadow" copy of what *would*
 * have happened and compare it later to real outcomes.
 */
object EvolutionShadowEvaluator {

    data class ShadowResult(
        val baselineScore: Double,
        val variantScore: Double,
        val winner: Winner,
        val overlap: Double,
        val explanation: kotlin.String,
    )

    enum class Winner { BASELINE, VARIANT, TIE }

    fun evaluate(baseline: kotlin.String, variant: kotlin.String): ShadowResult {
        val b = baseline.trim()
        val v = variant.trim()
        val baselineTokens = tokenCount(b)
        val variantTokens = tokenCount(v)
        val overlap = tokenOverlap(b, v)
        val baselineScore = score(baselineTokens)
        val variantScore = score(variantTokens)
        val winner = when {
            variantScore > baselineScore * 1.05 -> Winner.VARIANT
            baselineScore > variantScore * 1.05 -> Winner.BASELINE
            else -> Winner.TIE
        }
        val explanation = when (winner) {
            Winner.VARIANT -> "variant is ${"%.0f".format((variantScore / baselineScore - 1) * 100)}% larger and ${"%.0f".format(overlap * 100)}% overlapping"
            Winner.BASELINE -> "baseline is ${"%.0f".format((baselineScore / variantScore - 1) * 100)}% larger and ${"%.0f".format(overlap * 100)}% overlapping"
            Winner.TIE -> "similar size (${"%.0f".format(overlap * 100)}% overlap)"
        }
        return ShadowResult(baselineScore, variantScore, winner, overlap, explanation)
    }

    private fun tokenCount(text: kotlin.String): Int =
        text.split(Regex("""\s+""")).filter { it.isNotBlank() }.size.coerceAtLeast(1)

    private fun score(tokens: Int): Double =
        // Prefer concise but not empty content; peak around 200 tokens.
        1.0 - kotlin.math.min(1.0, kotlin.math.abs(tokens - 200.0) / 200.0)

    private fun tokenOverlap(a: kotlin.String, b: kotlin.String): Double {
        val setA = a.split(Regex("""\s+""")).filter { it.isNotBlank() }.toSet()
        val setB = b.split(Regex("""\s+""")).filter { it.isNotBlank() }.toSet()
        if (setA.isEmpty() || setB.isEmpty()) return 0.0
        val intersection = setA.intersect(setB).size
        val union = setA.union(setB).size
        return intersection.toDouble() / union.toDouble()
    }
}
