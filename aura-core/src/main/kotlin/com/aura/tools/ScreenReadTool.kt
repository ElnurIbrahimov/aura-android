package com.aura.tools

import com.aura.agent.ToolCategories
import com.aura.a11y.ScreenControlBridge
import com.aura.a11y.ScreenControlException
import com.aura.a11y.SnapshotOptions
import com.aura.a11y.UiSnapshotSerializer
import com.aura.agent.Tool
import com.aura.agent.ToolResult
import com.aura.agent.ToolRisk
import com.aura.data.UserPreferences
import com.aura.providers.ToolDefinition
import com.aura.providers.ToolParameters
import com.aura.providers.ToolProperty
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Read what is on screen, in any app.
 *
 * Reads the accessibility tree rather than a screenshot. Not a cost decision —
 * a screenshot is impossible here. `ScreenCaptureHolder.captureOnce` needs an
 * attached `FragmentActivity` and launches a consent dialog, and
 * `startForeground(TYPE_MEDIA_PROJECTION)` throws from the background on API
 * 34+. Reading another app's screen means Aura is backgrounded by definition,
 * so MediaProjection cannot run in the one scenario it would serve.
 *
 * The tree is also better for the job: it is text, so it is deterministic,
 * cheap, testable off-device, and it carries what is *actionable* rather than
 * only what is visible.
 *
 * Risk PRIVACY, which `ToolPolicyDefaults` maps to IMPLICIT confirmation — so
 * the first read in a conversation raises a dialog and `confirmedTools` covers
 * the rest. That is a session-scoped read approval for free, with no new gate
 * machinery.
 */
@Singleton
class ScreenReadTool @Inject constructor(
    private val bridge: ScreenControlBridge,
    private val userPreferences: UserPreferences? = null,
) {
    fun definition(): ToolDefinition = ToolDefinition(
        name = "screen_read",
        description =
            "Read the current screen of whatever app is in the foreground. Returns a numbered list " +
                "of on-screen elements with their roles, labels and coordinates. Use before acting " +
                "on anything, and again after — element numbers are only valid for the snapshot " +
                "they came from.",
        parameters = ToolParameters(
            properties = mapOf(
                "mode" to ToolProperty(
                    type = "string",
                    description = "actionable (default, things you can tap or type in, plus visible text), " +
                        "text (all readable text), full (everything; expensive)",
                    enum = listOf("actionable", "text", "full"),
                ),
                "filter" to ToolProperty(
                    type = "string",
                    description = "Only return elements whose label or role contains this text.",
                ),
                "max_elements" to ToolProperty(
                    type = "integer",
                    description = "Cap on returned elements (default 40, max 100).",
                ),
            ),
        ),
        category = ToolCategories.DEVICE,
    )

    val tool = Tool(
        name = definition().name,
        description = definition().description,
        risk = ToolRisk.PRIVACY,
        // requiredPermissions stays EMPTY. ToolExecutor.isGranted returns false
        // for anything the package manager does not recognise as a runtime
        // permission, so listing BIND_ACCESSIBILITY_SERVICE here would gate the
        // tool permanently. The pseudo-permission is reported from the body
        // instead — the pattern NotificationListTool established.
        parameters = definition().parameters,
        execute = { call, _ ->
            when {
                !isEnabled() -> ToolResult.Error(
                    "Screen control is turned off. Enable it in Settings → Privacy first.",
                    "screen_control_disabled",
                )

                !bridge.connected.value -> ToolResult.NeedsPermission(
                    permission = ScreenControlBridge.A11Y_PERMISSION,
                    rationale = "Aura needs Accessibility access to read the screen. " +
                        "Enable it in Android Settings, then retry.",
                )

                else -> read(call.arguments)
            }
        },
        category = ToolCategories.DEVICE,
    )

    private suspend fun read(args: Map<String, Any?>): ToolResult {
        val options = SnapshotOptions(
            mode = when ((args["mode"] as? String)?.lowercase()) {
                "text" -> SnapshotOptions.Mode.TEXT
                "full" -> SnapshotOptions.Mode.FULL
                else -> SnapshotOptions.Mode.ACTIONABLE
            },
            filter = (args["filter"] as? String).orEmpty(),
            maxElements = (args["max_elements"] as? Int ?: SnapshotOptions.DEFAULT_MAX_ELEMENTS)
                .coerceIn(1, SnapshotOptions.HARD_MAX_ELEMENTS),
        )
        return runCatching { bridge.snapshot(options) }
            .fold(
                onSuccess = { ToolResult.Ok(UiSnapshotSerializer.render(it)) },
                onFailure = { e ->
                    val err = (e as? ScreenControlException)?.error
                    ToolResult.Error(err?.detail ?: (e.message ?: "Screen read failed."), err?.code ?: "screen_read_failed")
                },
            )
    }

    /**
     * The master switch, checked before the service state.
     *
     * Deliberately redundant with hiding the tool from the model: a tool that
     * is merely absent from the schema list can still be invoked by a model
     * that remembers it from earlier in the conversation, and the bridge is
     * reachable by anything holding the singleton.
     */
    private suspend fun isEnabled(): Boolean =
        userPreferences?.let {
            runCatching { it.screenControlEnabled.first() }.getOrDefault(false)
        } ?: false
}
