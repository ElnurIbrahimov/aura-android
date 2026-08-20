package com.aura.hands.record

import com.aura.a11y.ElementSelector
import com.aura.a11y.Rect4
import com.aura.hands.HandDao
import com.aura.hands.HandRepository
import io.mockk.mockk
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A reviewed demonstration becoming steps a Hand can run.
 *
 * The output has to satisfy two readers that know nothing about each other: HandRepository,
 * which parses and executes it, and ScreenActTool, which reads the selector back out. Both
 * are exercised here rather than described, because a compiler whose output only its own
 * test can read is the defect this repo keeps finding.
 */
class RecordedHandCompilerTest {

    private val repository = HandRepository(mockk<HandDao>(relaxed = true))

    private fun sel(text: String, viewId: String? = null) =
        ElementSelector(viewId, text, null, "android.widget.Button", Rect4(0, 0, 100, 50))

    private fun compiled(vararg steps: RecordedStep) =
        RecordedHandCompiler.compile(steps.toList()) as RecordedHandCompiler.Result.Compiled

    @Test
    fun `a tap becomes a screen_act step naming its target`() {
        val result = compiled(RecordedStep(RecordedStep.Kind.TAP, sel("Send", "send"), "Send"))

        val step = result.steps.single()
        assertEquals("screen_act", step.tool)
        assertEquals("tap", step.args["action"])
        val selector = step.args.getValue("selector")
        assertTrue("\"text\":\"Send\"" in selector, "the target must survive into the step: $selector")
        assertTrue("\"viewId\":\"send\"" in selector, "the id is the strongest match and must survive: $selector")
    }

    @Test
    fun `typing carries its text, which is what makes the hand reusable`() {
        val result = compiled(
            RecordedStep(RecordedStep.Kind.TYPE, sel("Message"), "Message", text = "{{message}}"),
        )

        val step = result.steps.single()
        assertEquals("type", step.args["action"])
        assertEquals("{{message}}", step.args["text"])
    }

    @Test
    fun `scrolling and going back need no target`() {
        val result = compiled(
            RecordedStep(RecordedStep.Kind.SCROLL, null, direction = RecordedStep.Direction.DOWN),
            RecordedStep(RecordedStep.Kind.BACK, null),
        )

        assertEquals("scroll", result.steps[0].args["action"])
        assertEquals("down", result.steps[0].args["direction"])
        assertTrue("selector" !in result.steps[0].args, "a scroll targets no element")
        assertEquals("back", result.steps[1].args["action"])
    }

    @Test
    fun `a step whose target was never chosen refuses to compile`() {
        // Compiling an ambiguous step would bake the first guess into a saved macro, which
        // is the one thing the review screen exists to prevent.
        val result = RecordedHandCompiler.compile(
            listOf(
                RecordedStep(RecordedStep.Kind.TAP, sel("Send"), "Send"),
                RecordedStep(
                    RecordedStep.Kind.TAP, sel("Archive"), "Archive",
                    candidates = listOf(sel("Archive"), sel("Delete")),
                ),
            ),
        )

        assertTrue(result is RecordedHandCompiler.Result.Unresolved, "expected a refusal, got $result")
        assertEquals(listOf(1), (result as RecordedHandCompiler.Result.Unresolved).positions)
    }

    @Test
    fun `the steps survive the repository's own round trip`() {
        val result = compiled(
            RecordedStep(RecordedStep.Kind.TAP, sel("Send", "send"), "Send"),
            RecordedStep(RecordedStep.Kind.TYPE, sel("Message"), "Message", text = "hello"),
        )

        val parsed = repository.parseSteps(repository.stepsToJson(result.steps))

        assertEquals(result.steps, parsed, "what the compiler writes must be what the runner reads")
    }
}
