package com.aura.tools

import com.aura.a11y.ActionRequest
import com.aura.a11y.ScreenControlBridge
import com.aura.a11y.ScreenControlException
import com.aura.a11y.ScreenControlGuard
import com.aura.a11y.ScreenControlSession
import com.aura.a11y.UiElement
import com.aura.agent.Tool
import com.aura.agent.ToolResult
import com.aura.agent.ToolRisk
import com.aura.agent.policy.ConfirmationLevel
import com.aura.data.UserPreferences
import com.aura.providers.ToolDefinition
import com.aura.providers.ToolParameters
import com.aura.providers.ToolProperty
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Operate the screen: tap, type, scroll, swipe, and the system buttons.
 *
 * Risk [ToolRisk.DESTRUCTIVE], which is deliberate on two counts. It maps to
 * EXPLICIT confirmation in `ToolPolicyDefaults`, and it reads as
 * "assume irreversible" — which is the right prior for a tap in an app whose
 * state this code cannot see. A new enum value was NOT added: the `ToolRisk`
 * ordinal order is load-bearing (`risk.ordinal >= WRITE_LOCAL.ordinal` is the
 * incognito boundary in three places), so inserting one would silently shift
 * what incognito blocks.
 *
 * Verification is folded in rather than left to the model. `read_after` returns
 * the new screen with the result, which halves the steps, latency and tokens
 * per interaction versus a separate `screen_read`, and gives the model a real
 * changed/unchanged signal instead of asking it to infer one.
 */
@Singleton
class ScreenActTool @Inject constructor(
    private val bridge: ScreenControlBridge,
    private val session: ScreenControlSession,
    private val userPreferences: UserPreferences? = null,
) {
    fun definition(): ToolDefinition = ToolDefinition(
        name = "screen_act",
        description =
            "Operate the screen of the foreground app: tap or long-press an element, type into a " +
                "field, scroll, swipe, or press back/home/recents. Element numbers come from " +
                "screen_read and are only valid for that snapshot — read the screen first, and " +
                "read it again if an action reports the screen changed.",
        parameters = ToolParameters(
            properties = mapOf(
                "action" to ToolProperty(
                    type = "string",
                    description = "What to do.",
                    enum = listOf(
                        "tap", "long_press", "type", "clear", "swipe", "scroll",
                        "back", "home", "recents", "notifications", "wait",
                    ),
                ),
                "element" to ToolProperty(
                    type = "integer",
                    description = "Element number from screen_read. Required for tap, long_press, type, clear, scroll.",
                ),
                "snapshot" to ToolProperty(
                    type = "integer",
                    description = "The snapshot number the element came from. Stale snapshots are refused.",
                ),
                "text" to ToolProperty(type = "string", description = "Text to type. Required for type."),
                "direction" to ToolProperty(
                    type = "string",
                    description = "Direction for scroll and swipe (default down).",
                    enum = listOf("up", "down", "left", "right"),
                ),
                "read_after" to ToolProperty(
                    type = "boolean",
                    description = "Return the new screen with the result (default true).",
                ),
            ),
            required = listOf("action"),
        ),
        category = ToolCategories.DEVICE,
    )

    val tool = Tool(
        name = definition().name,
        description = definition().description,
        risk = ToolRisk.DESTRUCTIVE,
        // Empty, like ScreenReadTool: ToolExecutor.isGranted returns false for
        // anything that is not a real runtime permission.
        parameters = definition().parameters,
        execute = { call, ctx -> act(call.arguments, ctx.confirmedTools) },
        category = ToolCategories.DEVICE,
    )

    private suspend fun act(args: Map<String, Any?>, confirmedTools: Set<String>): ToolResult {
        if (!isEnabled()) {
            return ToolResult.Error(
                "Screen control is turned off. Enable it in Settings → Privacy first.",
                "screen_control_disabled",
            )
        }
        if (!bridge.connected.value) {
            return ToolResult.NeedsPermission(
                permission = ScreenControlBridge.A11Y_PERMISSION,
                rationale = "Aura needs Accessibility access to operate the screen. " +
                    "Enable it in Android Settings, then retry.",
            )
        }

        val request = parse(args) ?: return ToolResult.Error(
            "Unknown or missing action.",
            "bad_args",
        )

        // Resolve the element first: a stale snapshot is a hard refusal, and
        // refusing before consuming a session action means a stale reference
        // does not cost the user part of their budget.
        var element: UiElement? = null
        if (request.needsElement) {
            val resolved = bridge.resolve(request.snapshotId, request.elementIndex)
            element = resolved.getOrElse { e ->
                return ToolResult.Error(
                    (e as? ScreenControlException)?.error?.detail ?: (e.message ?: "Element not found."),
                    "stale_snapshot",
                )
            }
        }

        val pkg = bridge.foregroundPackage.value
        val snapshot = bridge.currentSnapshot()

        // Unconditional rules, before any session logic. Nothing turns these off.
        ScreenControlGuard.block(
            packageName = pkg,
            snapshot = snapshot,
            targetIsPassword = request.kind == ActionRequest.Kind.TYPE && element?.label == "••••",
            auraPackages = ScreenControlGuard.deniedPackages(),
        )?.let { return ToolResult.Error(it.reason, it.code) }

        // The tripwire fires regardless of an open session: a live session means
        // "carry on with this task", not "you may now do the irreversible part
        // without asking".
        val label = element?.label.orEmpty()
        val destructive = label.isNotBlank() && ScreenControlGuard.looksDestructive(label)
        if (destructive && TRIPWIRE_KEY !in confirmedTools) {
            return ToolResult.NeedsConfirmation(
                level = ConfirmationLevel.EXPLICIT.name,
                toolName = definition().name,
                rationale = "Aura wants to ${request.kind.name.lowercase()} \"$label\" in $pkg. " +
                    "That looks like it cannot be undone.",
            )
        }

        // A session is the general grant. Returning NeedsConfirmation from the
        // BODY works because the loop treats a body-returned gate identically
        // to a policy-returned one — so this needs no loop changes at all.
        if (session.check(pkg) != null) {
            if (definition().name !in confirmedTools) {
                return ToolResult.NeedsConfirmation(
                    level = ConfirmationLevel.EXPLICIT.name,
                    toolName = definition().name,
                    rationale = "Aura wants to control the screen in $pkg. This allows up to " +
                        "${ScreenControlSession.MAX_ACTIONS} actions over the next " +
                        "${ScreenControlSession.DURATION_MS / 60_000} minutes, in that app only.",
                )
            }
            session.open(pkg)
        }

        session.consume(pkg)?.let { return ToolResult.Error(it.reason, "session_denied") }

        return runCatching { bridge.act(request, element) }
            .fold(
                onSuccess = { outcome ->
                    val head = buildString {
                        append(if (outcome.performed) "ok " else "failed ")
                        append(outcome.summary)
                        append(if (outcome.screenChanged) " — screen changed" else " — screen unchanged")
                        append(" → ${outcome.newPackage}/${outcome.newActivity}")
                        val remaining = session.state.value.actionsRemaining
                        append(" [$remaining actions left]")
                    }
                    val readAfter = args["read_after"] as? Boolean ?: true
                    val body = if (readAfter) {
                        bridge.currentSnapshot()
                            ?.let { "\n" + com.aura.a11y.UiSnapshotSerializer.render(it) }
                            .orEmpty()
                    } else {
                        ""
                    }
                    ToolResult.Ok(head + body)
                },
                onFailure = { e ->
                    val err = (e as? ScreenControlException)?.error
                    ToolResult.Error(err?.detail ?: (e.message ?: "Action failed."), err?.code ?: "screen_act_failed")
                },
            )
    }

    private fun parse(args: Map<String, Any?>): ActionRequest? {
        val kind = when ((args["action"] as? String)?.lowercase()) {
            "tap" -> ActionRequest.Kind.TAP
            "long_press" -> ActionRequest.Kind.LONG_PRESS
            "type" -> ActionRequest.Kind.TYPE
            "clear" -> ActionRequest.Kind.CLEAR
            "swipe" -> ActionRequest.Kind.SWIPE
            "scroll" -> ActionRequest.Kind.SCROLL
            "back" -> ActionRequest.Kind.BACK
            "home" -> ActionRequest.Kind.HOME
            "recents" -> ActionRequest.Kind.RECENTS
            "notifications" -> ActionRequest.Kind.NOTIFICATIONS
            "wait" -> ActionRequest.Kind.WAIT
            else -> return null
        }
        return ActionRequest(
            kind = kind,
            snapshotId = (args["snapshot"] as? Int) ?: 0,
            elementIndex = (args["element"] as? Int) ?: 0,
            text = (args["text"] as? String).orEmpty(),
            direction = when ((args["direction"] as? String)?.lowercase()) {
                "up" -> ActionRequest.Direction.UP
                "left" -> ActionRequest.Direction.LEFT
                "right" -> ActionRequest.Direction.RIGHT
                else -> ActionRequest.Direction.DOWN
            },
        )
    }

    private suspend fun isEnabled(): Boolean =
        userPreferences?.let {
            runCatching { it.screenControlEnabled.first() }.getOrDefault(false)
        } ?: false

    private companion object {
        /**
         * Separate confirmation key for the tripwire, so approving a session
         * does not also pre-approve every destructive-looking button in it.
         */
        const val TRIPWIRE_KEY = "screen_act:destructive"
    }
}
