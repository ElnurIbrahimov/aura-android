package com.aura.tools

import com.aura.a11y.ScreenControlBridge
import com.aura.a11y.ScreenControlSession
import com.aura.a11y.UiSnapshot
import com.aura.agent.ToolResult
import com.aura.data.UserPreferences
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A screen-control session is a budget, and a budget that renews itself
 * without asking is not a budget.
 *
 * The dialog promises "up to 25 actions over the next 5 minutes, in that app
 * only". The grant behind it was keyed on the tool name, which lives in
 * `confirmedTools` for the whole conversation and never expires — so the first
 * approval silently re-opened a fresh full-budget session every time the
 * previous one ran out, in any app, for as long as the conversation lived. The
 * bounds were real for the first session and unbounded after it.
 *
 * The fix names each grant after the session it opens, the same way
 * `TRIPWIRE_KEY` names a specific decision, so a spent approval cannot open a
 * second session.
 */
class ScreenActSessionGrantTest {

    private val pkg = "com.whatsapp"

    private fun tool(session: ScreenControlSession): ScreenActTool {
        val bridge = mockk<ScreenControlBridge>(relaxed = true)
        every { bridge.connected } returns MutableStateFlow(true)
        every { bridge.foregroundPackage } returns MutableStateFlow(pkg)
        coEvery { bridge.currentSnapshot() } returns
            UiSnapshot(1, pkg, "Main", 1080, 2400, emptyList(), 0, false)

        val prefs = mockk<UserPreferences>(relaxed = true)
        every { prefs.screenControlEnabled } returns flowOf(true)

        return ScreenActTool(bridge = bridge, session = session, userPreferences = prefs)
    }

    /** `press back` needs no element, so it exercises the session path alone. */
    private val backPress = mapOf<String, Any?>("action" to "back")

    private suspend fun ScreenActTool.act(confirmed: Set<String>): ToolResult =
        tool.execute(
            com.aura.agent.ToolCall(id = "c1", name = "screen_act", arguments = backPress),
            com.aura.agent.ToolContext(conversationId = "conv-1", confirmedTools = confirmed),
        )

    @Test
    fun `the first action asks for a session under an unspent key`() = runTest {
        val t = tool(ScreenControlSession())

        val result = t.act(emptySet())

        assertTrue(result is ToolResult.NeedsConfirmation, "expected a confirmation, got $result")
        assertEquals(
            "${ScreenActTool.SESSION_KEY_PREFIX}0",
            (result as ToolResult.NeedsConfirmation).toolName,
            "the grant must name the session it opens, not the tool",
        )
    }

    @Test
    fun `a spent grant cannot open a second session`() = runTest {
        val session = ScreenControlSession()
        val t = tool(session)

        // Approve session 0 and use it.
        val granted = setOf("${ScreenActTool.SESSION_KEY_PREFIX}0")
        t.act(granted)
        assertTrue(session.state.value.active, "precondition: the approved session opened")

        // The session ends — expiry or a package change, both routine.
        session.close()

        // The same approval must not silently open another one. This is the
        // regression: with the old tool-name key, `confirmedTools` still
        // contained "screen_act" here and the session re-opened at full budget
        // with no prompt.
        val afterExpiry = t.act(granted)

        assertTrue(afterExpiry is ToolResult.NeedsConfirmation, "expected a fresh prompt, got $afterExpiry")
        assertEquals(
            "${ScreenActTool.SESSION_KEY_PREFIX}1",
            (afterExpiry as ToolResult.NeedsConfirmation).toolName,
        )
    }
}
