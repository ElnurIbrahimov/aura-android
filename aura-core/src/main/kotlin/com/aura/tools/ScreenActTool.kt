package com.aura.tools

import com.aura.agent.ToolCategories
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
import com.aura.agent.policy.PolicyEngine
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
    private val policyEngine: PolicyEngine? = null,
) {
    /**
     * Counts sessions this tool has opened, so each one needs its own approval.
     *
     * A confirmation key is remembered for the life of the conversation, which
     * is right for "may Aura use this tool" and wrong for "may Aura have five
     * minutes of screen control" — the second is a budget, and a budget that
     * renews itself without asking is not a budget. Incrementing after each
     * open means the next session asks under a key nobody has approved yet.
     */
    private val sessionEpoch = java.util.concurrent.atomic.AtomicLong(0)

    /** The confirmation key naming the session that is about to be opened. */
    internal fun sessionGrantKey(): String = "$SESSION_KEY_PREFIX${sessionEpoch.get()}"

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
                "selector" to ToolProperty(
                    type = "string",
                    description = "Alternative to element/snapshot: JSON naming the target, e.g. " +
                        "{\"text\":\"Send\"}. Resolved against a fresh read, so it survives a screen " +
                        "that has moved on. Used by recorded Hands, whose steps outlive any snapshot.",
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
            val named = (args["selector"] as? String)?.takeIf { it.isNotBlank() }
            element = if (named != null) {
                // A recorded step names its target, because the index path cannot serve it:
                // `element` is a position in one snapshot and stale snapshots are refused, so
                // a number captured yesterday means nothing today. Resolve against a screen
                // read now.
                val recorded = parseSelector(named)
                    ?: return ToolResult.Error("The selector could not be read.", "bad_args")
                val fresh = runCatching { bridge.snapshot() }.getOrNull()
                    ?: return ToolResult.Error("Could not read the screen.", "screen_read_failed")
                // Null here is a refusal, not a miss — see ElementSelector.bestMatchIn. The
                // step stops rather than acting on whatever is nearest, because a recorded
                // Hand runs unsupervised inside another app.
                recorded.bestMatchIn(fresh) ?: return ToolResult.Error(
                    "Could not find \"${describeSelector(recorded)}\" on this screen. " +
                        "The app may have changed since this was recorded.",
                    "element_not_found",
                )
            } else {
                bridge.resolve(request.snapshotId, request.elementIndex).getOrElse { e ->
                    return ToolResult.Error(
                        (e as? ScreenControlException)?.error?.detail ?: (e.message ?: "Element not found."),
                        "stale_snapshot",
                    )
                }
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

        // The user's app allowlist — layer 6, and the only one they configure by
        // naming apps rather than by flipping a switch. Checked HERE rather than
        // in `PolicyEngine.evaluate` because the target package is not known
        // until the foreground app has been read, which is long after the policy
        // gate ran. Fails closed: an allowlist that cannot be checked denies.
        policyEngine?.scopeDenial("screen_act", pkg)?.let {
            return ToolResult.Error(
                "Screen control is restricted to specific apps, and ${it.scope} is not one of them. " +
                    "Change the allowed apps in Settings if that is wrong.",
                "scope_denied",
            )
        }

        // The tripwire fires regardless of an open session: a live session means
        // "carry on with this task", not "you may now do the irreversible part
        // without asking".
        val label = element?.label.orEmpty()
        val destructive = label.isNotBlank() && ScreenControlGuard.looksDestructive(label)
        if (destructive && TRIPWIRE_KEY !in confirmedTools) {
            return ToolResult.NeedsConfirmation(
                level = ConfirmationLevel.EXPLICIT.name,
                // The key granted must be the key checked. This passed
                // `definition().name`, so approving a tripwire added
                // "screen_act" and never TRIPWIRE_KEY — which made the tripwire
                // ask forever (harmless) while silently satisfying the *session*
                // grant below (not harmless): confirming one irreversible button
                // handed out a five-minute, twenty-five-action session nobody
                // was shown the terms of.
                toolName = TRIPWIRE_KEY,
                rationale = "Aura wants to ${request.kind.name.lowercase()} \"$label\" in $pkg. " +
                    "That looks like it cannot be undone.",
            )
        }

        // A session is the general grant. Returning NeedsConfirmation from the
        // BODY works because the loop treats a body-returned gate identically
        // to a policy-returned one — so this needs no loop changes at all.
        if (session.check(pkg) != null) {
            // A confirmation grant is single-use, because a session is.
            //
            // This used to check `definition().name`, which is per-conversation
            // and never expires — so the first approval in a conversation
            // silently re-opened a fresh full-budget session every time the old
            // one ran out, in any app, for as long as the conversation lived.
            // The five-minute, twenty-five-action, one-app bounds the dialog
            // promises were real for the first session and unbounded after it.
            //
            // The epoch makes each grant name a specific session, in the same
            // way TRIPWIRE_KEY names a specific decision: once spent, the next
            // session asks under a key nobody has approved yet.
            val grantKey = sessionGrantKey()
            if (grantKey !in confirmedTools) {
                return ToolResult.NeedsConfirmation(
                    level = ConfirmationLevel.EXPLICIT.name,
                    toolName = grantKey,
                    rationale = "Aura wants to control the screen in $pkg. This allows up to " +
                        "${ScreenControlSession.MAX_ACTIONS} actions over the next " +
                        "${ScreenControlSession.DURATION_MS / 60_000} minutes, in that app only.",
                )
            }
            session.open(pkg)
            sessionEpoch.incrementAndGet()
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

    /** Read a recorded target. Bounds are deliberately not carried: they have moved. */
    private fun parseSelector(raw: String): com.aura.a11y.ElementSelector? {
        val obj = runCatching {
            kotlinx.serialization.json.Json.parseToJsonElement(raw)
                as? kotlinx.serialization.json.JsonObject
        }.getOrNull() ?: return null
        fun field(key: String): String? =
            (obj[key] as? kotlinx.serialization.json.JsonPrimitive)
                ?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }
        val viewId = field("viewId")
        val text = field("text")
        val description = field("contentDescription")
        if (viewId == null && text == null && description == null) return null
        return com.aura.a11y.ElementSelector(
            viewId = viewId,
            text = text,
            contentDescription = description,
            className = field("className"),
            bounds = com.aura.a11y.Rect4(0, 0, 0, 0),
        )
    }

    /** What to call the thing that could not be found, in an error a person reads. */
    private fun describeSelector(s: com.aura.a11y.ElementSelector): String =
        s.text ?: s.contentDescription ?: s.viewId.orEmpty()

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

    internal companion object {
        /**
         * Separate confirmation key for the tripwire, so approving a session
         * does not also pre-approve every destructive-looking button in it.
         */
        const val TRIPWIRE_KEY = "screen_act:destructive"

        /** Prefix of the single-use session grant key. See [sessionGrantKey]. */
        const val SESSION_KEY_PREFIX = "screen_act:session:"
    }
}
