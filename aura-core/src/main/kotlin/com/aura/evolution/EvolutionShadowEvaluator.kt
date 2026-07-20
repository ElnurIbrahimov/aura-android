package com.aura.evolution

/**
 * Lightweight shadow evaluator for proposed evolutions. Given a baseline
 * artifact (e.g. a skill body or memory text) and a variant produced by the
 * evolution pipeline, it returns deterministic, interpretable metrics without
 * calling an LLM. This lets the system keep a "shadow" copy of what *would*
 * have happened and compare it later to real outcomes.
 *
 * Metrics are purely heuristic — no LLM calls, no semantic analysis. The
 * scores are useful for relative comparison (variant vs baseline) but not
 * as absolute quality measures. The evaluator rewards:
 * - Information density (meaningful tokens per total tokens)
 * - Structural variety (sentences, paragraphs, code blocks)
 * - Specificity (numbers, proper nouns, quoted strings)
 * - Conciseness (penalizes both empty and bloated content)
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
        val overlap = tokenOverlap(b, v)
        val baselineScore = score(b)
        val variantScore = score(v)
        val winner = when {
            variantScore > baselineScore * 1.05 -> Winner.VARIANT
            baselineScore > variantScore * 1.05 -> Winner.BASELINE
            else -> Winner.TIE
        }
        val explanation = when (winner) {
            Winner.VARIANT -> "variant scores ${"%.2f".format(variantScore)} vs baseline ${"%.2f".format(baselineScore)} (${"%.0f".format(overlap * 100)}% token overlap)"
            Winner.BASELINE -> "baseline scores ${"%.2f".format(baselineScore)} vs variant ${" %.2f".format(variantScore)} (${"%.0f".format(overlap * 100)}% token overlap)"
            Winner.TIE -> "similar scores (${"%.2f".format(baselineScore)} vs ${"%.2f".format(variantScore)}, ${"%.0f".format(overlap * 100)}% overlap)"
        }
        return ShadowResult(baselineScore, variantScore, winner, overlap, explanation)
    }

    /**
     * Multi-signal heuristic score in [0, 1]. Combines:
     * - Length fitness: peak around 50-200 tokens, penalizes empty and bloated
     * - Information density: ratio of content tokens (non-stopwords) to total
     * - Structural variety: sentences + paragraphs + code blocks
     * - Specificity: numbers, proper nouns, quoted strings
     */
    private fun score(text: kotlin.String): Double {
        if (text.isBlank()) return 0.0
        val tokens = text.split(Regex("""\s+""")).filter { it.isNotBlank() }
        if (tokens.isEmpty()) return 0.0
        val tokenCount = tokens.size

        // Length fitness: peak at 50-200 tokens, decay outside that range.
        val lengthFitness = when {
            tokenCount in 50..200 -> 1.0
            tokenCount < 50 -> tokenCount / 50.0
            else -> 200.0 / tokenCount.coerceAtLeast(1)
        }

        // Information density: ratio of non-stopword tokens to total.
        val stopWords = setOf(
            "the", "a", "an", "is", "are", "was", "were", "be", "been", "being",
            "have", "has", "had", "do", "does", "did", "will", "would", "could",
            "should", "may", "might", "must", "can", "shall", "to", "of", "in",
            "for", "on", "with", "at", "by", "from", "as", "into", "about",
            "but", "or", "and", "not", "no", "if", "then", "else", "when",
            "this", "that", "these", "those", "it", "its", "i", "you", "he",
            "she", "we", "they", "me", "him", "her", "us", "them", "my", "your",
        )
        val contentTokens = tokens.count { it.lowercase() !in stopWords && it.length > 2 }
        val density = contentTokens.toDouble() / tokenCount

        // Structural variety: sentences, paragraphs, code blocks.
        val sentences = text.split(Regex("""[.!?]+""")).filter { it.isNotBlank() }.size.coerceAtLeast(1)
        val paragraphs = text.split(Regex("""\n\s*\n""")).filter { it.isNotBlank() }.size.coerceAtLeast(1)
        val codeBlocks = Regex("""```""").findAll(text).count() / 2
        val structure = ((sentences.toDouble() / 10).coerceAtMost(0.3) +
            (paragraphs.toDouble() / 5).coerceAtMost(0.2) +
            (codeBlocks.toDouble() / 3).coerceAtMost(0.2)).coerceIn(0.0, 0.7)

        // Specificity: numbers, proper nouns (capitalized mid-sentence), quoted strings.
        val numbers = Regex("""\b\d+\b""").findAll(text).count()
        val properNouns = Regex("""\s[A-Z][a-z]+""").findAll(text).count()
        val quotes = Regex(""""[^"]+"|'[^']+'""").findAll(text).count()
        val specificity = ((numbers + properNouns + quotes).toDouble() / tokenCount).coerceIn(0.0, 0.3)

        return (lengthFitness * 0.3 + density * 0.3 + structure * 0.25 + specificity * 0.15)
            .coerceIn(0.0, 1.0)
    }

    private fun tokenOverlap(a: kotlin.String, b: kotlin.String): Double {
        val setA = a.split(Regex("""\s+""")).filter { it.isNotBlank() }.toSet()
        val setB = b.split(Regex("""\s+""")).filter { it.isNotBlank() }.toSet()
        if (setA.isEmpty() || setB.isEmpty()) return 0.0
        val intersection = setA.intersect(setB).size
        val union = setA.union(setB).size
        return intersection.toDouble() / union.toDouble()
    }
}