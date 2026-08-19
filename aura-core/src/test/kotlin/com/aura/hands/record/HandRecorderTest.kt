package com.aura.hands.record

import com.aura.a11y.ElementSelector
import com.aura.a11y.Rect4
import com.aura.a11y.ScreenControlBridge
import com.aura.a11y.UiElement
import com.aura.a11y.UiSnapshot
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What the recorder will and will not write down.
 *
 * It is driven by an accessibility callback that fires for every window change on the
 * device, so most of this file is about the things it must ignore. The tick carries a
 * package name and nothing else; everything the recorder learns comes from a snapshot it
 * asks for, under the same gate `screen_read` already runs under.
 */
class HandRecorderTest {

    private val pkg = "com.example.app"

    private fun el(label: String, top: Int, clickable: Boolean = true) = UiElement(
        index = 0, role = "button", label = label, bounds = Rect4(0, top, 100, top + 50),
        clickable = clickable, longClickable = false, scrollable = false, editable = false,
        checkable = false, checked = false, enabled = true,
        selector = ElementSelector(null, label, null, "android.widget.Button", Rect4(0, top, 100, top + 50)),
    )

    private fun snap(id: Int, vararg e: UiElement) =
        UiSnapshot(id, pkg, "Main", 1080, 2400, e.toList(), 0, false)

    private fun recorder(vararg reads: UiSnapshot): HandRecorder {
        val bridge = mockk<ScreenControlBridge>(relaxed = true)
        var i = 0
        coEvery { bridge.snapshot(any()) } answers { reads[minOf(i++, reads.size - 1)] }
        return HandRecorder(bridge)
    }

    @Test
    fun `a screen change before recording starts is not written down`() = runTest {
        val r = recorder(snap(1, el("Send", 100)), snap(2, el("Sent", 100)))

        r.onScreenChanged(pkg)
        r.onScreenChanged(pkg)

        assertTrue(r.state.value.steps.isEmpty(), "the recorder must be silent until started")
    }

    @Test
    fun `a change in a different app is ignored`() = runTest {
        // The callback fires for every window on the device. A recording bound to one app
        // must not quietly collect steps from whatever the user switched to.
        val r = recorder(snap(1, el("Send", 100)), snap(2, el("Sent", 100)))
        r.start(pkg)

        r.onScreenChanged("com.other.bank")
        r.onScreenChanged("com.other.bank")

        assertTrue(r.state.value.steps.isEmpty(), "steps must be bound to the recorded app")
    }

    @Test
    fun `two readings with a change between them become a step`() = runTest {
        val r = recorder(snap(1, el("Send", 100), el("Draft", 300)), snap(2, el("Draft", 300)))
        r.start(pkg)

        r.onScreenChanged(pkg)
        r.onScreenChanged(pkg)

        assertEquals(1, r.state.value.steps.size)
        assertEquals(RecordedStep.Kind.TAP, r.state.value.steps.single().kind)
        assertEquals("Send", r.state.value.steps.single().label)
    }

    @Test
    fun `a screen that did not change adds nothing`() = runTest {
        val same = snap(1, el("Send", 100))
        val r = recorder(same, same, same)
        r.start(pkg)

        repeat(3) { r.onScreenChanged(pkg) }

        assertTrue(r.state.value.steps.isEmpty(), "an unchanged screen is not an action")
    }

    @Test
    fun `stopping hands back the steps and leaves nothing recording`() = runTest {
        val r = recorder(snap(1, el("Send", 100), el("Draft", 300)), snap(2, el("Draft", 300)))
        r.start(pkg)
        r.onScreenChanged(pkg)
        r.onScreenChanged(pkg)

        val steps = r.stop()

        assertEquals(1, steps.size)
        assertFalse(r.state.value.recording, "stop must end the session")
        assertTrue(r.state.value.steps.isEmpty(), "stop must not leave the last recording behind")
    }

    @Test
    fun `a recording that runs away stops itself at the cap`() = runTest {
        // Driven by a device-wide callback, so an unbounded recorder is a memory leak that
        // grows for as long as the user forgets it is on.
        val a = snap(1, el("A", 100), el("B", 300))
        val b = snap(2, el("B", 300))
        val reads = (0 until HandRecorder.MAX_STEPS * 4).map { if (it % 2 == 0) a else b }
        val r = recorder(*reads.toTypedArray())
        r.start(pkg)

        repeat(HandRecorder.MAX_STEPS * 4) { r.onScreenChanged(pkg) }

        assertEquals(HandRecorder.MAX_STEPS, r.state.value.steps.size)
        assertFalse(r.state.value.recording, "hitting the cap should end the recording, not silently drop steps")
    }
}
