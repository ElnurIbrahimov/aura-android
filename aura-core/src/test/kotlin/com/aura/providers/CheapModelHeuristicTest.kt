package com.aura.providers

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins the auxiliary-model selection contract.
 *
 * The regression this guards against: the previous heuristic ranked models
 * by name *length*, which systematically picked the flagship over the cheap
 * tier because the size suffix that marks a model as small also lengthens
 * its name. Every case below where a `-mini`/`-flash`/`haiku` variant sits
 * beside its flagship sibling would have failed under that rule.
 */
class CheapModelHeuristicTest {

    @Test
    fun `prefers mini over the flagship it shares a prefix with`() {
        // The exact inversion the old name-length rule produced:
        // "gpt-4o" (6 chars) sorted before "gpt-4o-mini" (11).
        assertEquals(
            "gpt-4o-mini",
            CheapModelHeuristic.pick(listOf("gpt-4o", "gpt-4o-mini")),
        )
    }

    @Test
    fun `prefers haiku over opus and sonnet`() {
        assertEquals(
            "claude-haiku-4-5",
            CheapModelHeuristic.pick(
                listOf("claude-opus-4-5", "claude-sonnet-4-5", "claude-haiku-4-5"),
            ),
        )
    }

    @Test
    fun `prefers flash over pro`() {
        assertEquals(
            "gemini-2.5-flash",
            CheapModelHeuristic.pick(listOf("gemini-2.5-pro", "gemini-2.5-flash")),
        )
    }

    @Test
    fun `prefers nano over mini over flagship`() {
        assertEquals(
            "gpt-5-nano",
            CheapModelHeuristic.pick(listOf("gpt-5", "gpt-5-mini", "gpt-5-nano")),
        )
    }

    @Test
    fun `prefers small parameter counts over large ones`() {
        assertEquals(
            "llama-3.1-8b-instant",
            CheapModelHeuristic.pick(
                listOf("llama-3.1-70b-versatile", "llama-3.1-405b", "llama-3.1-8b-instant"),
            ),
        )
    }

    @Test
    fun `parameter marker does not match inside a longer number`() {
        // "128b" must not be read as the small marker "8b" — otherwise a
        // 128-billion-parameter model would be scored as the cheap option.
        assertTrue(
            CheapModelHeuristic.score("model-128b") > CheapModelHeuristic.score("model-8b"),
            "128b should score as larger than 8b",
        )
    }

    @Test
    fun `strips provider prefix before scoring and returns the input form`() {
        assertEquals(
            "openai:gpt-4o-mini",
            CheapModelHeuristic.pick(listOf("openai:gpt-4o", "openai:gpt-4o-mini")),
        )
    }

    @Test
    fun `unrecognized models score neutral and fall back to length tie-break`() {
        // Neither name carries a marker; the heuristic degrades to the
        // tie-breaker rather than throwing or preferring arbitrarily.
        assertEquals(0, CheapModelHeuristic.score("some-unknown-model"))
        assertEquals(
            "aaa",
            CheapModelHeuristic.pick(listOf("aaaaaaaa", "aaa")),
        )
    }

    @Test
    fun `empty candidate list returns null`() {
        assertNull(CheapModelHeuristic.pick(emptyList()))
    }

    @Test
    fun `single candidate is returned unchanged`() {
        assertEquals("only-model", CheapModelHeuristic.pick(listOf("only-model")))
    }
}
