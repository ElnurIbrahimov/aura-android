package com.aura.evolution

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EvolutionShadowEvaluatorTest {

    @Test
    fun `variant larger than baseline picks variant`() {
        val result = EvolutionShadowEvaluator.evaluate(
            baseline = "short skill body",
            variant = "This is a much longer and more detailed skill body with many words",
        )
        assertEquals(EvolutionShadowEvaluator.Winner.VARIANT, result.winner)
        assertTrue(result.overlap in 0.0..1.0)
        assertTrue(result.explanation.contains("variant"))
    }

    @Test
    fun `similar texts result in tie`() {
        val result = EvolutionShadowEvaluator.evaluate(
            baseline = "alpha beta gamma",
            variant = "alpha beta gamma",
        )
        assertEquals(EvolutionShadowEvaluator.Winner.TIE, result.winner)
        assertEquals(1.0, result.overlap, 0.01)
    }

    @Test
    fun `baseline larger picks baseline`() {
        val result = EvolutionShadowEvaluator.evaluate(
            baseline = "one two three four five six seven eight nine ten",
            variant = "one two",
        )
        assertEquals(EvolutionShadowEvaluator.Winner.BASELINE, result.winner)
    }
}
