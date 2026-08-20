package com.aura.a11y

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Finding a recorded element again on a later run.
 *
 * This is the single most dangerous function in record mode. A recorded Hand replays against
 * a screen that has moved on — new rows, a redesign, a different account — and the wrong
 * answer here is not a failed macro but a tap on whatever happened to be nearest. Every case
 * below is about refusing rather than approximating.
 */
class ElementSelectorMatchTest {

    private fun el(index: Int, label: String, sel: ElementSelector) = UiElement(
        index = index, role = "button", label = label, bounds = sel.bounds,
        clickable = true, longClickable = false, scrollable = false, editable = false,
        checkable = false, checked = false, enabled = true, selector = sel,
    )

    private fun snap(vararg e: UiElement) = UiSnapshot(
        id = 1, packageName = "com.example.app", activityName = "Main",
        screenWidth = 1080, screenHeight = 2400, elements = e.toList(),
        truncatedCount = 0, hasPasswordField = false,
    )

    private fun sel(
        viewId: String? = null,
        text: String? = null,
        desc: String? = null,
        className: String? = "android.widget.Button",
        bounds: Rect4 = Rect4(0, 0, 100, 50),
    ) = ElementSelector(viewId, text, desc, className, bounds)

    @Test
    fun `an element that is still there is found`() {
        val send = sel(viewId = "send", text = "Send")
        val target = el(0, "Send", send)

        assertEquals(target, send.bestMatchIn(snap(el(1, "Cancel", sel(text = "Cancel")), target)))
    }

    @Test
    fun `an element that has moved is still found by what it says`() {
        // The whole point of a selector over coordinates: a list scrolled, so the bounds are
        // wrong, but the identity is not.
        val recorded = sel(viewId = "send", text = "Send", bounds = Rect4(0, 900, 300, 1000))
        val moved = el(0, "Send", sel(viewId = "send", text = "Send", bounds = Rect4(0, 200, 300, 300)))

        assertEquals(moved, recorded.bestMatchIn(snap(moved)))
    }

    @Test
    fun `an element that is gone is not replaced by the nearest thing`() {
        val recorded = sel(viewId = "send", text = "Send")
        val other = el(0, "Delete", sel(viewId = "delete", text = "Delete", bounds = Rect4(500, 500, 600, 550)))

        assertNull(
            recorded.bestMatchIn(snap(other)),
            "no match must mean no action, not the closest available button",
        )
    }

    @Test
    fun `position and type alone are not an identity`() {
        // Same class, same place, different thing entirely — which is exactly what a
        // redesigned screen looks like. Score is 1 + 2 = 3, below the floor.
        val recorded = sel(text = "Send", bounds = Rect4(0, 0, 100, 50))
        val impostor = el(0, "Delete account", sel(text = "Delete account", bounds = Rect4(0, 0, 100, 50)))

        assertNull(
            recorded.bestMatchIn(snap(impostor)),
            "matching on where it sits and what class it is would tap whatever moved into that spot",
        )
    }

    @Test
    fun `two equally good matches are refused rather than picked between`() {
        val recorded = sel(text = "Delete")
        val first = el(0, "Delete", sel(text = "Delete", bounds = Rect4(0, 100, 100, 150)))
        val second = el(1, "Delete", sel(text = "Delete", bounds = Rect4(0, 300, 100, 350)))

        assertNull(
            recorded.bestMatchIn(snap(first, second)),
            "two identical Delete buttons and no way to tell them apart is not a match",
        )
    }
}
