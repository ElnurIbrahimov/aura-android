package com.aura.hands.record

import com.aura.a11y.ElementSelector
import com.aura.a11y.Rect4
import com.aura.a11y.UiElement
import com.aura.a11y.UiSnapshot
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What a demonstration can and cannot be read off two screens.
 *
 * The rules here are deliberately conservative. An inference that guesses wrong does not
 * produce a slightly-off macro, it produces one that taps an arbitrary control in whatever
 * app the user pointed it at — so every rule either identifies a target or says it could
 * not, and "could not" is a first-class outcome rather than a fallback.
 */
class StepInferenceTest {

    private fun el(
        index: Int,
        label: String,
        bounds: Rect4,
        clickable: Boolean = false,
        editable: Boolean = false,
        text: String? = label,
        viewId: String? = null,
        className: String = "android.widget.TextView",
    ) = UiElement(
        index = index,
        role = if (editable) "field" else "button",
        label = label,
        bounds = bounds,
        clickable = clickable,
        longClickable = false,
        scrollable = false,
        editable = editable,
        checkable = false,
        checked = false,
        enabled = true,
        selector = ElementSelector(viewId, text, null, className, bounds),
    )

    private fun snap(
        id: Int,
        elements: List<UiElement>,
        activity: String = "MainActivity",
        password: Boolean = false,
    ) = UiSnapshot(
        id = id,
        packageName = "com.example.app",
        activityName = activity,
        screenWidth = 1080,
        screenHeight = 2400,
        elements = elements,
        truncatedCount = 0,
        hasPasswordField = password,
    )

    private fun rect(top: Int) = Rect4(0, top, 1080, top + 100)

    @Test
    fun `text appearing in an editable field is read as typing it`() {
        val before = snap(1, listOf(el(0, "", rect(100), editable = true, text = "", viewId = "msg")))
        val after = snap(2, listOf(el(0, "hello", rect(100), editable = true, text = "hello", viewId = "msg")))

        val step = StepInference.infer(before, after)

        assertEquals(RecordedStep.Kind.TYPE, step?.kind)
        assertEquals("hello", step?.text)
        assertTrue(step?.ambiguous == false, "typing into one field is not ambiguous")
    }

    @Test
    fun `the same elements at shifted positions are read as a scroll`() {
        val before = snap(1, listOf(el(0, "Row A", rect(500)), el(1, "Row B", rect(700))))
        val after = snap(2, listOf(el(0, "Row A", rect(200)), el(1, "Row B", rect(400))))

        val step = StepInference.infer(before, after)

        assertEquals(RecordedStep.Kind.SCROLL, step?.kind)
        // Content moved up the screen, so the user scrolled down through it.
        assertEquals(RecordedStep.Direction.DOWN, step?.direction)
    }

    @Test
    fun `a single clickable element disappearing identifies the tap`() {
        val send = el(0, "Send", rect(100), clickable = true, viewId = "send")
        val before = snap(1, listOf(send, el(1, "Draft", rect(300))))
        val after = snap(2, listOf(el(1, "Sent", rect(300))))

        val step = StepInference.infer(before, after)

        assertEquals(RecordedStep.Kind.TAP, step?.kind)
        assertEquals("Send", step?.label)
        assertEquals(send.selector, step?.selector)
        assertTrue(step?.ambiguous == false, "one candidate is not ambiguous")
    }

    @Test
    fun `several possible targets are reported as candidates rather than guessed`() {
        // Two clickable elements vanish at once. Nothing in the diff says which was tapped,
        // and picking one would mean tapping a control the user never chose.
        val a = el(0, "Archive", rect(100), clickable = true)
        val b = el(1, "Delete", rect(300), clickable = true)
        val before = snap(1, listOf(a, b))
        val after = snap(2, listOf(el(2, "Undo", rect(100), clickable = true)))

        val step = StepInference.infer(before, after)

        assertEquals(RecordedStep.Kind.TAP, step?.kind)
        assertTrue(step?.ambiguous == true, "two vanished clickables must not resolve to one")
        assertEquals(
            setOf(a.selector, b.selector),
            step?.candidates?.toSet(),
            "both plausible targets must be offered to the review screen",
        )
    }

    @Test
    fun `a screen that changed with nothing vanishing still records the tap as uncertain`() {
        // The common real case, and the one a naive diff drops on the floor: tapping "Send"
        // usually leaves "Send" on screen. Something was pressed — returning null here would
        // silently lose a step from the middle of the user's demonstration, which is worse
        // than admitting the target is unknown.
        val send = el(0, "Send", rect(100), clickable = true)
        val menu = el(1, "Menu", rect(300), clickable = true)
        val before = snap(1, listOf(send, menu, el(2, "Draft", rect(500))))
        val after = snap(2, listOf(send, menu, el(3, "Sent", rect(500))))

        val step = StepInference.infer(before, after)

        assertEquals(RecordedStep.Kind.TAP, step?.kind)
        assertTrue(step?.ambiguous == true, "an unattributable tap must not claim a target")
        assertEquals(
            setOf(send.selector, menu.selector),
            step?.candidates?.toSet(),
            "every clickable that was on screen is a possible target",
        )
    }

    @Test
    fun `nothing is recorded while a password field is on screen`() {
        val before = snap(1, listOf(el(0, "Password", rect(100), editable = true, text = "")), password = true)
        val after = snap(2, listOf(el(0, "Password", rect(100), editable = true, text = "hunter2")), password = true)

        assertNull(
            StepInference.infer(before, after),
            "recording near a password field would put the password in a saved macro",
        )
    }

    @Test
    fun `an unchanged screen produces no step`() {
        val elements = listOf(el(0, "Send", rect(100), clickable = true))
        assertNull(StepInference.infer(snap(1, elements), snap(2, elements)))
    }
}
