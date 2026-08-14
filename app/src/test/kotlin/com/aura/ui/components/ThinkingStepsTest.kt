package com.aura.ui.components

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ThinkingStepsTest {

    @Test
    fun `each paragraph the model wrote becomes a step`() {
        val steps = ThinkingSteps.segment(
            """
            The user is asking about the deployment schedule for next quarter.

            I should check what I know about their calendar before answering.

            There is nothing in memory about Q3, so I will say so rather than guess.
            """.trimIndent(),
        )

        assertEquals(3, steps.size)
        assertTrue(steps[0].label.startsWith("The user is asking"))
        assertTrue(steps[2].label.startsWith("There is nothing in memory"))
    }

    @Test
    fun `a stray beat is folded into the thought it belongs with`() {
        // "Wait." is not a step. A timeline that gives it its own dot fills up
        // with fragments and stops being scannable.
        val steps = ThinkingSteps.segment(
            """
            I will start by listing the constraints the user gave me earlier.

            Wait.

            They also said not to use the staging environment for this, which
            changes the order of the last two operations entirely.
            """.trimIndent(),
        )

        assertEquals(2, steps.size)
        assertTrue("Wait." in steps[0].body, "the beat was dropped instead of folded: ${steps[0]}")
    }

    @Test
    fun `the label is the step's own first sentence, verbatim`() {
        // Not a summary. A generated title is a second author's account of the
        // reasoning and can be wrong about it; a quote cannot.
        val steps = ThinkingSteps.segment(
            "Extracting and numbering all the writing rules. There are eleven of them and two conflict.",
        )

        assertEquals("Extracting and numbering all the writing rules.", steps.single().label)
        assertEquals("There are eleven of them and two conflict.", steps.single().body)
    }

    @Test
    fun `a paragraph that is only its opening sentence carries no body`() {
        // Otherwise the UI prints the same sentence twice, once as the label
        // and once beneath it.
        val steps = ThinkingSteps.segment("I should check the calendar before answering this.")

        assertEquals("I should check the calendar before answering this.", steps.single().label)
        assertEquals("", steps.single().body)
    }

    @Test
    fun `a long opening clause is cut at a word boundary`() {
        val long = "I am going to work through the entire specification document " +
            "section by section without stopping because the user asked for completeness above all"
        val label = ThinkingSteps.segment(long).single().label

        assertTrue(label.length <= ThinkingSteps.MAX_LABEL_CHARS + 1, "label was ${label.length}: $label")
        assertTrue(label.endsWith("…"), "expected an ellipsis: $label")
        assertFalse(label.dropLast(1).endsWith(" "), "cut left a trailing space: $label")
        // The cut lands between words, never inside one.
        assertTrue(long.startsWith(label.dropLast(1)), "label is not a prefix of the source: $label")
    }

    @Test
    fun `a very long deliberation is folded rather than listed`() {
        // Forty dots is as unreadable as the single blob this replaced.
        val many = (1..40).joinToString("\n\n") { "Considering angle number $it of the problem in detail." }

        val steps = ThinkingSteps.segment(many)

        assertEquals(ThinkingSteps.MAX_STEPS, steps.size)
        // Folded from the front, so the end — where the model reaches its
        // conclusion — stays granular.
        assertTrue(steps.last().label.contains("40"), "the final step was merged away: ${steps.last().label}")
    }

    @Test
    fun `thinking with no paragraph breaks is a single step`() {
        val steps = ThinkingSteps.segment("One continuous thought with no structure to find at all here.")

        assertEquals(1, steps.size)
    }

    @Test
    fun `blank thinking yields nothing to render`() {
        assertTrue(ThinkingSteps.segment("").isEmpty())
        assertTrue(ThinkingSteps.segment("   \n\n  ").isEmpty())
    }

    @Test
    fun `leading markdown markers do not become part of the label`() {
        val steps = ThinkingSteps.segment("- Checking whether the user already told me this.\n\nThey did, in March.")

        assertEquals("Checking whether the user already told me this.", steps[0].label)
    }

    @Test
    fun `every step carries a non-blank label`() {
        // The label is the only thing shown when a step is collapsed, so a
        // blank one is an invisible row.
        val messy = "###\n\nHmm\n\n   \n\nThe actual reasoning starts here and runs on for a while.\n\n*"

        ThinkingSteps.segment(messy).forEach {
            assertTrue(it.label.isNotBlank(), "blank label in $it")
        }
    }
}
