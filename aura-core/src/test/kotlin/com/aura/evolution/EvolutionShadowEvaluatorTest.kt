package com.aura.evolution

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EvolutionShadowEvaluatorTest {

    @Test
    fun `variant with more structure and specificity wins`() {
        val result = EvolutionShadowEvaluator.evaluate(
            baseline = "short skill body",
            variant = "This is a much longer and more detailed skill body with many words. It has 3 paragraphs.\n\nSecond paragraph with specific numbers like 42.\n\nThird paragraph with quoted \"strings\".",
        )
        // The variant has more tokens, structure, numbers, and quotes —
        // the multi-signal score should reward it.
        assertEquals(EvolutionShadowEvaluator.Winner.VARIANT, result.winner)
        assertTrue(result.overlap in 0.0..1.0)
        assertTrue(result.explanation.contains("variant"))
    }

    @Test
    fun `identical texts result in tie`() {
        val result = EvolutionShadowEvaluator.evaluate(
            baseline = "alpha beta gamma",
            variant = "alpha beta gamma",
        )
        assertEquals(EvolutionShadowEvaluator.Winner.TIE, result.winner)
        assertEquals(1.0, result.overlap, 0.01)
    }

    @Test
    fun `empty baseline loses to non-empty variant`() {
        val result = EvolutionShadowEvaluator.evaluate(
            baseline = "",
            variant = "some content here with a few words to work with",
        )
        assertEquals(EvolutionShadowEvaluator.Winner.VARIANT, result.winner)
    }

    @Test
    fun `empty variant loses to non-empty baseline`() {
        val result = EvolutionShadowEvaluator.evaluate(
            baseline = "some content here with a few words to work with",
            variant = "",
        )
        assertEquals(EvolutionShadowEvaluator.Winner.BASELINE, result.winner)
    }

    @Test
    fun `both empty results in tie`() {
        val result = EvolutionShadowEvaluator.evaluate(
            baseline = "",
            variant = "",
        )
        assertEquals(EvolutionShadowEvaluator.Winner.TIE, result.winner)
        assertEquals(0.0, result.baselineScore, 0.01)
        assertEquals(0.0, result.variantScore, 0.01)
    }

    @Test
    fun `scores are in 0 to 1 range`() {
        val result = EvolutionShadowEvaluator.evaluate(
            baseline = "The quick brown fox jumps over the lazy dog.",
            variant = "A skill body with some content and structure. It has 2 sentences.",
        )
        assertTrue(result.baselineScore in 0.0..1.0)
        assertTrue(result.variantScore in 0.0..1.0)
    }
}