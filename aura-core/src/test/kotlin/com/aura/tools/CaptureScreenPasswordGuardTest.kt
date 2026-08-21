package com.aura.tools

import com.aura.a11y.ScreenControlBridge
import com.aura.a11y.UiSnapshot
import com.aura.agent.ToolContext
import com.aura.agent.ToolResult
import com.aura.agent.ToolCall
import com.aura.security.ScreenCaptureHolder
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Test

/**
 * A screenshot of a login screen is the one capture Aura must refuse.
 *
 * `ScreenControlGuard` refuses to act while a password field is visible and
 * `StepInference` refuses to record one, but `capture_screen` sent the pixels
 * of that same screen to a vision provider. Everything protecting the text path
 * is a regex over the view tree, and a regex cannot edit a JPEG — so the only
 * surface where the password is actually legible was the only one unguarded.
 *
 * The check needs the accessibility service, because the view tree is the only
 * thing that knows a field is a password field. With the service off there is
 * nothing to ask and MediaProjection's per-capture consent dialog is the
 * control instead; `capture proceeds when there is no view tree to ask` pins
 * that this is a deliberate fail-open and not an oversight.
 */
class CaptureScreenPasswordGuardTest {

    private fun snapshot(hasPassword: Boolean) = UiSnapshot(
        id = 1,
        packageName = "com.bank.app",
        activityName = "Login",
        screenWidth = 1080,
        screenHeight = 1920,
        elements = emptyList(),
        truncatedCount = 0,
        hasPasswordField = hasPassword,
    )

    private fun tool(bridge: ScreenControlBridge?): com.aura.agent.Tool =
        CaptureScreenTool(
            screenCaptureHolder = mockk<ScreenCaptureHolder>(relaxed = true),
            screenControlBridge = bridge,
            userPreferences = null,
        ).tool

    @Test
    fun `a visible password field refuses the capture`() = runTest {
        val bridge = mockk<ScreenControlBridge>(relaxed = true)
        every { bridge.connected } returns MutableStateFlow(true)
        coEvery { bridge.snapshot(any()) } returns snapshot(hasPassword = true)

        val result = tool(bridge).execute(
            ToolCall("id-1", "capture_screen", emptyMap<String, Any?>()),
            ToolContext(conversationId = "test"),
        )

        assertTrue(result is ToolResult.Error, "expected a refusal, got $result")
        assertEquals("password_field_visible", (result as ToolResult.Error).code)
    }

    @Test
    fun `a screen with no password field is not refused for that reason`() = runTest {
        val bridge = mockk<ScreenControlBridge>(relaxed = true)
        every { bridge.connected } returns MutableStateFlow(true)
        coEvery { bridge.snapshot(any()) } returns snapshot(hasPassword = false)

        val result = tool(bridge).execute(
            ToolCall("id-2", "capture_screen", emptyMap<String, Any?>()),
            ToolContext(conversationId = "test"),
        )

        // It still fails — there is no real capture behind a relaxed mock — but
        // it must not fail for this reason. Asserting the code rather than
        // success is what keeps this test about the guard.
        val code = (result as? ToolResult.Error)?.code
        assertTrue(code != "password_field_visible", "guard fired on a screen with no password field")
    }

    @Test
    fun `capture proceeds when there is no view tree to ask`() = runTest {
        // Service off: nothing can answer the question. Documented fail-open,
        // covered by MediaProjection's per-capture consent dialog.
        val result = tool(null).execute(
            ToolCall("id-3", "capture_screen", emptyMap<String, Any?>()),
            ToolContext(conversationId = "test"),
        )

        val code = (result as? ToolResult.Error)?.code
        assertTrue(code != "password_field_visible", "guard fired with no accessibility service connected")
    }
}
