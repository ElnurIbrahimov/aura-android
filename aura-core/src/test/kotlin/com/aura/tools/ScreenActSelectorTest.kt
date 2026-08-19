package com.aura.tools

import com.aura.a11y.ActionOutcome
import com.aura.a11y.ActionRequest
import com.aura.a11y.ElementSelector
import com.aura.a11y.Rect4
import com.aura.a11y.ScreenControlBridge
import com.aura.a11y.ScreenControlSession
import com.aura.a11y.UiElement
import com.aura.a11y.UiSnapshot
import com.aura.agent.ToolCall
import com.aura.agent.ToolContext
import com.aura.agent.ToolResult
import com.aura.data.UserPreferences
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Acting on an element named by what it is, rather than by where it sat in a list.
 *
 * `element` is an index into one `screen_read` snapshot and stale snapshots are refused —
 * correct for the agentic loop, which reads the screen and acts on it in the same breath,
 * and useless for a recorded Hand, whose steps were captured yesterday. "Tap element 7 of
 * snapshot 3" means nothing on a later run.
 *
 * So a step can instead carry the selector it recorded, and the tool resolves it against a
 * screen read fresh at replay time. The old path is untouched: both must keep working, since
 * the loop uses one and Hands use the other.
 */
class ScreenActSelectorTest {

    private val pkg = "com.example.app"

    private fun sel(text: String, viewId: String? = null, bounds: Rect4 = Rect4(0, 0, 100, 50)) =
        ElementSelector(viewId, text, null, "android.widget.Button", bounds)

    private fun el(index: Int, label: String, selector: ElementSelector) = UiElement(
        index = index, role = "button", label = label, bounds = selector.bounds,
        clickable = true, longClickable = false, scrollable = false, editable = false,
        checkable = false, checked = false, enabled = true, selector = selector,
    )

    private fun snap(vararg e: UiElement) =
        UiSnapshot(9, pkg, "Main", 1080, 2400, e.toList(), 0, false)

    private fun tool(
        fresh: UiSnapshot,
        bridge: ScreenControlBridge = mockk(relaxed = true),
    ): Pair<ScreenActTool, ScreenControlBridge> {
        every { bridge.connected } returns MutableStateFlow(true)
        every { bridge.foregroundPackage } returns MutableStateFlow(pkg)
        every { bridge.currentSnapshot() } returns fresh
        coEvery { bridge.snapshot(any()) } returns fresh
        coEvery { bridge.act(any(), any()) } returns
            ActionOutcome(performed = true, summary = "tapped", screenChanged = true, newPackage = pkg, newActivity = "Main")

        val prefs = mockk<UserPreferences>(relaxed = true)
        every { prefs.screenControlEnabled } returns flowOf(true)
        val session = ScreenControlSession().also { it.open(pkg) }
        return ScreenActTool(bridge = bridge, session = session, userPreferences = prefs) to bridge
    }

    private suspend fun ScreenActTool.run(args: Map<String, Any?>): ToolResult =
        tool.execute(
            ToolCall(id = "c1", name = "screen_act", arguments = args),
            ToolContext(conversationId = "conv-1", confirmedTools = emptySet()),
        )

    @Test
    fun `a step that names its target acts on the element found in a fresh read`() = runTest {
        val send = el(0, "Send", sel("Send", viewId = "send"))
        val (t, bridge) = tool(snap(el(1, "Cancel", sel("Cancel")), send))

        val result = t.run(
            mapOf("action" to "tap", "selector" to """{"viewId":"send","text":"Send"}"""),
        )

        assertTrue(result is ToolResult.Ok, "expected the tap to run, got $result")
        val acted = slot<UiElement>()
        coVerify { bridge.act(any(), capture(acted)) }
        assertEquals("Send", acted.captured.label, "acted on the wrong element")
    }

    @Test
    fun `a target that is no longer on screen stops the step instead of tapping something else`() = runTest {
        // The failure that matters. A recorded Hand runs unsupervised inside another app, so
        // "Send is gone, here is Delete instead" is not a degraded result, it is a different
        // and unbounded action.
        val (t, bridge) = tool(snap(el(0, "Delete account", sel("Delete account"))))

        val result = t.run(
            mapOf("action" to "tap", "selector" to """{"viewId":"send","text":"Send"}"""),
        )

        assertTrue(result is ToolResult.Error, "expected a refusal, got $result")
        assertTrue(
            "Send" in (result as ToolResult.Error).message,
            "the error should name what it could not find: ${result.message}",
        )
        coVerify(exactly = 0) { bridge.act(any(), any()) }
    }

    @Test
    fun `the snapshot and element path the agentic loop uses is unchanged`() = runTest {
        val send = el(0, "Send", sel("Send", viewId = "send"))
        val bridge = mockk<ScreenControlBridge>(relaxed = true)
        every { bridge.resolve(9, 0) } returns Result.success(send)
        val (t, _) = tool(snap(send), bridge)

        val result = t.run(mapOf("action" to "tap", "element" to 0, "snapshot" to 9))

        assertTrue(result is ToolResult.Ok, "the index path must keep working, got $result")
        coVerify { bridge.resolve(9, 0) }
    }
}
