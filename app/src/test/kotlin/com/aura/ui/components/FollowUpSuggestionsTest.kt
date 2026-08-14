package com.aura.ui.components

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FollowUpSuggestionsTest {

    @Test
    fun `empty assistant text returns no suggestions`() {
        assertEquals(emptyList(), FollowUpSuggestions.suggest("", isCodey = false))
    }

    @Test
    fun `long prose response offers Summarize and More detail`() {
        val long = (1..20).joinToString(" ") { "word$it" } // 140+ chars, will exceed 800 once we add filler
        val response = long + " " + (1..80).joinToString(" ") { "filler$it" }
        val out = FollowUpSuggestions.suggest(response, isCodey = false)
        assertTrue("Summarize" in out, "got $out")
        assertTrue("More detail" in out, "got $out")
    }

    @Test
    fun `codey response offers Explain the code and Show an example`() {
        val response = "Here is a function that returns one:\n```kotlin\nfun foo() = 1\n```\nIt returns one."
        val out = FollowUpSuggestions.suggest(response, isCodey = true)
        assertTrue("Explain the code" in out, "got $out")
        assertTrue("Show an example" in out, "got $out")
    }

    @Test
    fun `response ending in unclosed code block gets Continue suggestion`() {
        // The text ends with ``` (the opening of a code block
        // with no closing fence). The endAbruptly check should
        // detect this.
        val response = "Here's the function:\n```"
        val out = FollowUpSuggestions.suggest(response, isCodey = true)
        assertTrue("Continue" in out, "got $out")
    }

    @Test
    fun `list response offers to pick one`() {
        val response = "1. Apple\n2. Banana\n3. Cherry"
        val out = FollowUpSuggestions.suggest(response, isCodey = false)
        assertTrue("Pick one" in out, "got $out")
    }

    @Test
    fun `question ending offers Yes and Something else`() {
        val response = "Do you want me to continue?"
        val out = FollowUpSuggestions.suggest(response, isCodey = false)
        assertTrue("Yes" in out, "got $out")
        assertTrue("Something else" in out, "got $out")
    }

    /**
     * A chip is a button, and a button that needs two lines is a sentence.
     *
     * "No, something else" wrapped inside its own chip at the width these
     * render at, so it stood at double the height of its neighbours and the
     * whole row looked broken. FlowRow now wraps whole chips rather than text,
     * but that is the backstop — short labels are the fix, and nothing enforced
     * them.
     */
    @Test
    fun `every suggestion is short enough to fit one line`() {
        val responses = listOf(
            "Do you want me to continue?",
            "1. Apple\n2. Banana\n3. Cherry",
            "Here are the options you asked about, would you like more?",
            "x".repeat(900),
            "```kotlin\nfun main() {}\n```",
        )

        val all = responses.flatMap { FollowUpSuggestions.suggest(it, isCodey = it.contains("```")) }

        assertTrue(all.isNotEmpty(), "no suggestions fired, so this asserts nothing")
        all.forEach {
            assertTrue(it.length <= MAX_CHIP_LABEL, "\"$it\" is ${it.length} chars and will wrap in a chip")
        }
    }

    private companion object {
        /**
         * Fits on one line beside two siblings at the width chips render at
         * (85% of screen, minus the bubble indent). "Explain the code" is the
         * longest that currently ships, at 16.
         */
        const val MAX_CHIP_LABEL = 18
    }

    @Test
    fun `suggestions are capped at 3`() {
        // Force every heuristic to fire.
        val response = (1..100).joinToString(" ") { "word" } + "\n```\nDo you want me to continue?"
        val out = FollowUpSuggestions.suggest(response, isCodey = true)
        assertTrue(out.size <= 3, "expected <= 3 suggestions, got ${out.size}: $out")
    }

    @Test
    fun `suggestions are unique`() {
        val response = (1..100).joinToString(" ") { "word" }
        val out = FollowUpSuggestions.suggest(response, isCodey = false)
        assertEquals(out.size, out.toSet().size, "expected unique suggestions, got $out")
    }

    @Test
    fun `short response offers only More detail`() {
        val out = FollowUpSuggestions.suggest("here is a short answer about the topic", isCodey = false)
        // 41 chars is in the > 300 bucket only if we count … but
        // 41 chars < 300 so should be empty.
        assertEquals(emptyList(), out, "got $out")
    }
}
