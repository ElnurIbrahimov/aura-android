package com.aura.a11y

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What the accessibility service can do, expressed so the rest of the app can
 * use it without knowing the service exists.
 *
 * Implemented by the service itself. The bridge holds one of these and nothing
 * else, which is what keeps [ScreenControlBridge] free of any compile-time
 * dependency on `AccessibilityService` — and therefore unit-testable with a
 * fake, on CI, with no device.
 */
interface A11yController {
    /** Root of the active window, or null when there is none. */
    fun rootNode(): NodeLike?
    fun foregroundPackage(): String
    fun foregroundActivity(): String
    fun screenWidth(): Int
    fun screenHeight(): Int

    /**
     * Perform an accessibility action on the node matching [selector].
     *
     * Preferred over a synthesized tap wherever the node supports it: a node
     * action works through partial overlays, cannot miss by a few pixels, and
     * does not depend on the element being visually unobstructed. Returns false
     * when no node matches or the action is unsupported, so the caller can fall
     * back to a gesture.
     */
    suspend fun performNodeAction(selector: ElementSelector, action: NodeAction, text: String? = null): Boolean

    /** Drag a path. Suspends until the gesture completes, is cancelled, or times out. */
    suspend fun dispatchGesture(path: List<Pair<Int, Int>>, durationMs: Long): Boolean

    /** BACK, HOME, RECENTS, NOTIFICATIONS. */
    suspend fun performGlobalAction(action: GlobalAction): Boolean
}

/** Accessibility actions this package uses. */
enum class NodeAction { CLICK, LONG_CLICK, SET_TEXT, CLEAR_TEXT, SCROLL_FORWARD, SCROLL_BACKWARD, FOCUS }

/** Global actions, named rather than passed as platform ints. */
enum class GlobalAction { BACK, HOME, RECENTS, NOTIFICATIONS }

/** What an act request asks for. */
data class ActionRequest(
    val kind: Kind,
    val snapshotId: Int = 0,
    val elementIndex: Int = 0,
    val text: String = "",
    val direction: Direction = Direction.DOWN,
    val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
) {
    enum class Kind { TAP, LONG_PRESS, TYPE, CLEAR, SWIPE, SCROLL, BACK, HOME, RECENTS, NOTIFICATIONS, WAIT }
    enum class Direction { UP, DOWN, LEFT, RIGHT }

    /** Whether this kind needs an element, and therefore a live snapshot. */
    val needsElement: Boolean
        get() = kind in setOf(Kind.TAP, Kind.LONG_PRESS, Kind.TYPE, Kind.CLEAR, Kind.SCROLL)

    companion object {
        const val DEFAULT_TIMEOUT_MS = 5_000L
        const val MAX_TIMEOUT_MS = 15_000L
    }
}

/** What happened. */
data class ActionOutcome(
    val performed: Boolean,
    val summary: String,
    val screenChanged: Boolean,
    val newPackage: String,
    val newActivity: String,
)

/** Why a screen operation could not be performed. */
sealed class ScreenControlError(val code: String, val detail: String) {
    object NotConnected : ScreenControlError(
        "a11y_not_connected",
        "Aura's accessibility service is not enabled.",
    )

    object NoWindow : ScreenControlError(
        "no_window",
        "No readable window. The screen may be a game, a canvas, or FLAG_SECURE.",
    )

    class Failed(detail: String) : ScreenControlError("a11y_failed", detail)
}

class ScreenControlException(val error: ScreenControlError) : Exception(error.detail)

/**
 * The app-facing surface of screen control.
 *
 * `@Singleton`, and deliberately holds **no `Context`** — modelled on
 * `NotificationCaptureStore`, the existing example of a system-bound service
 * feeding a Context-free holder. The service attaches on connect and detaches
 * on unbind, so [connected] is the single source of truth for whether any of
 * this is available.
 *
 * Not modelled on `ScreenCaptureHolder`, whose `CompletableDeferred` handshakes
 * exist only because its work crosses an Activity-result boundary and a
 * foreground-service start. Here the service object is directly reachable
 * in-process, so a plain call under a mutex has strictly fewer failure modes.
 */
@Singleton
class ScreenControlBridge @Inject constructor() {

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    private val _foregroundPackage = MutableStateFlow("")
    val foregroundPackage: StateFlow<String> = _foregroundPackage.asStateFlow()

    private val _foregroundActivity = MutableStateFlow("")
    val foregroundActivity: StateFlow<String> = _foregroundActivity.asStateFlow()

    @Volatile
    private var controller: A11yController? = null

    /**
     * Serialises every operation. The agentic loop executes tools in parallel
     * (`coroutineScope { async { … } }`), and two concurrent reads of a moving
     * screen produce two snapshots that disagree — with the model holding
     * indices from whichever arrived second.
     */
    private val mutex = Mutex()

    private val snapshotCounter = AtomicInteger(0)

    @Volatile
    private var current: UiSnapshot? = null

    // ---- service lifecycle ----------------------------------------------

    internal fun attach(controller: A11yController) {
        this.controller = controller
        _connected.value = true
    }

    internal fun detach() {
        controller = null
        _connected.value = false
        current = null
        // Deliberately NOT resetting the counter. Snapshot ids must never be
        // reused: a stale id colliding with a fresh one is the one case the
        // staleness check cannot catch, and it would let an action land on the
        // wrong element after a service restart.
        _foregroundPackage.value = ""
        _foregroundActivity.value = ""
    }

    internal fun onWindowChanged(packageName: String, activityName: String) {
        _foregroundPackage.value = packageName
        _foregroundActivity.value = activityName
    }

    // ---- reading ---------------------------------------------------------

    /**
     * Read the screen.
     *
     * The returned snapshot becomes the current one, and its id is what a
     * later action must present. Anything else is refused rather than guessed
     * at — `docs/architecture/tool-policy.md` already required exactly this.
     */
    suspend fun snapshot(options: SnapshotOptions = SnapshotOptions()): UiSnapshot = mutex.withLock {
        val c = controller ?: throw ScreenControlException(ScreenControlError.NotConnected)
        val root = c.rootNode() ?: throw ScreenControlException(ScreenControlError.NoWindow)

        val result = UiTraversal.flatten(root, options)
        val snapshot = UiSnapshot(
            id = snapshotCounter.incrementAndGet(),
            packageName = c.foregroundPackage(),
            activityName = c.foregroundActivity(),
            screenWidth = c.screenWidth(),
            screenHeight = c.screenHeight(),
            elements = result.elements,
            truncatedCount = result.truncatedCount,
            hasPasswordField = result.hasPasswordField,
        )
        current = snapshot
        snapshot
    }

    /** The most recent snapshot, or null if none has been taken since connect. */
    fun currentSnapshot(): UiSnapshot? = current

    /**
     * Resolve an element reference against the CURRENT snapshot.
     *
     * Refuses a stale snapshot id outright. The alternative — acting on a
     * best-effort match from an old reading — is how an agent taps the wrong
     * thing on a screen that moved under it, and that failure is both silent
     * and unrecoverable.
     */
    fun resolve(snapshotId: Int, elementIndex: Int): Result<UiElement> {
        val snap = current
            ?: return Result.failure(ScreenControlException(ScreenControlError.Failed("No snapshot taken yet.")))
        if (snap.id != snapshotId) {
            return Result.failure(
                ScreenControlException(
                    ScreenControlError.Failed(
                        "Snapshot $snapshotId is stale (current is ${snap.id}). Read the screen again.",
                    ),
                ),
            )
        }
        val element = snap.elements.firstOrNull { it.index == elementIndex }
            ?: return Result.failure(
                ScreenControlException(
                    ScreenControlError.Failed("No element #$elementIndex in snapshot $snapshotId."),
                ),
            )
        return Result.success(element)
    }

    // ---- acting ----------------------------------------------------------

    /**
     * Perform [request] and report what changed.
     *
     * Verification is part of the operation, not a separate call. Dispatch,
     * settle, re-read, compare — folding that in halves the steps, the latency
     * and the tokens per interaction versus making the model issue a second
     * `screen_read`, and it gives the model a real changed/unchanged signal
     * rather than asking it to infer one from two snapshots.
     *
     * Callers are responsible for the guard and session checks BEFORE calling
     * this. The bridge performs; it does not decide whether it should.
     */
    suspend fun act(request: ActionRequest, element: UiElement?): ActionOutcome = mutex.withLock {
        val c = controller ?: throw ScreenControlException(ScreenControlError.NotConnected)

        val beforeFingerprint = current?.elements?.map { it.selector }?.toSet().orEmpty()
        val beforePackage = c.foregroundPackage()

        val performed = perform(c, request, element)

        // Let the UI settle. A window/content-changed event is the real signal,
        // but it does not always arrive — a tap that only toggles a checkbox
        // may fire nothing — so a bounded wait is the honest floor rather than
        // an optimisation.
        kotlinx.coroutines.delay(SETTLE_MS)

        val after = runCatching { snapshotLocked(c, SnapshotOptions()) }.getOrNull()
        val afterFingerprint = after?.elements?.map { it.selector }?.toSet().orEmpty()
        val changed = after != null &&
            (afterFingerprint != beforeFingerprint || c.foregroundPackage() != beforePackage)

        ActionOutcome(
            performed = performed,
            summary = if (performed) describe(request, element) else "${describe(request, element)} — not performed",
            screenChanged = changed,
            newPackage = c.foregroundPackage(),
            newActivity = c.foregroundActivity(),
        )
    }

    private suspend fun perform(c: A11yController, request: ActionRequest, element: UiElement?): Boolean =
        when (request.kind) {
            ActionRequest.Kind.BACK -> c.performGlobalAction(GlobalAction.BACK)
            ActionRequest.Kind.HOME -> c.performGlobalAction(GlobalAction.HOME)
            ActionRequest.Kind.RECENTS -> c.performGlobalAction(GlobalAction.RECENTS)
            ActionRequest.Kind.NOTIFICATIONS -> c.performGlobalAction(GlobalAction.NOTIFICATIONS)
            ActionRequest.Kind.WAIT -> true

            ActionRequest.Kind.TAP -> {
                val e = element ?: return@perform false
                // Node action first, gesture second. A node action works
                // through partial overlays and cannot miss by a few pixels;
                // the synthesized tap is the fallback for elements that report
                // no click affordance but respond to one anyway.
                c.performNodeAction(e.selector, NodeAction.CLICK) ||
                    c.dispatchGesture(listOf(e.bounds.centerX to e.bounds.centerY), TAP_MS)
            }

            ActionRequest.Kind.LONG_PRESS -> {
                val e = element ?: return@perform false
                c.performNodeAction(e.selector, NodeAction.LONG_CLICK) ||
                    c.dispatchGesture(listOf(e.bounds.centerX to e.bounds.centerY), LONG_PRESS_MS)
            }

            ActionRequest.Kind.TYPE -> {
                val e = element ?: return@perform false
                // No gesture fallback. Typing via synthesized taps on a
                // keyboard the agent cannot see is guesswork with a real cost:
                // characters land in the wrong field.
                c.performNodeAction(e.selector, NodeAction.FOCUS)
                c.performNodeAction(e.selector, NodeAction.SET_TEXT, request.text)
            }

            ActionRequest.Kind.CLEAR -> {
                val e = element ?: return@perform false
                c.performNodeAction(e.selector, NodeAction.CLEAR_TEXT)
            }

            ActionRequest.Kind.SCROLL -> {
                val e = element ?: return@perform false
                val action = if (request.direction == ActionRequest.Direction.UP) {
                    NodeAction.SCROLL_BACKWARD
                } else {
                    NodeAction.SCROLL_FORWARD
                }
                c.performNodeAction(e.selector, action) ||
                    c.dispatchGesture(swipePath(e.bounds, request.direction), SWIPE_MS)
            }

            ActionRequest.Kind.SWIPE -> {
                val bounds = element?.bounds
                    ?: Rect4(0, 0, c.screenWidth(), c.screenHeight())
                c.dispatchGesture(swipePath(bounds, request.direction), SWIPE_MS)
            }
        }

    /** Snapshot without re-taking the mutex; only called from inside [act]. */
    private fun snapshotLocked(c: A11yController, options: SnapshotOptions): UiSnapshot {
        val root = c.rootNode() ?: throw ScreenControlException(ScreenControlError.NoWindow)
        val result = UiTraversal.flatten(root, options)
        val snapshot = UiSnapshot(
            id = snapshotCounter.incrementAndGet(),
            packageName = c.foregroundPackage(),
            activityName = c.foregroundActivity(),
            screenWidth = c.screenWidth(),
            screenHeight = c.screenHeight(),
            elements = result.elements,
            truncatedCount = result.truncatedCount,
            hasPasswordField = result.hasPasswordField,
        )
        current = snapshot
        return snapshot
    }

    /**
     * A swipe travels against the direction of intent: scrolling DOWN means
     * dragging the content UP. Getting this backwards is the single easiest
     * mistake here and produces a feature that appears to work in reverse.
     */
    private fun swipePath(bounds: Rect4, direction: ActionRequest.Direction): List<Pair<Int, Int>> {
        val cx = bounds.centerX
        val cy = bounds.centerY
        val dx = (bounds.width / 3).coerceAtLeast(50)
        val dy = (bounds.height / 3).coerceAtLeast(50)
        return when (direction) {
            ActionRequest.Direction.DOWN -> listOf(cx to cy + dy, cx to cy - dy)
            ActionRequest.Direction.UP -> listOf(cx to cy - dy, cx to cy + dy)
            ActionRequest.Direction.RIGHT -> listOf(cx + dx to cy, cx - dx to cy)
            ActionRequest.Direction.LEFT -> listOf(cx - dx to cy, cx + dx to cy)
        }
    }

    private fun describe(request: ActionRequest, element: UiElement?): String {
        val target = element?.let { " #${it.index} \"${it.label}\"" }.orEmpty()
        return when (request.kind) {
            ActionRequest.Kind.TYPE -> "type into$target"
            ActionRequest.Kind.SWIPE, ActionRequest.Kind.SCROLL ->
                "${request.kind.name.lowercase()} ${request.direction.name.lowercase()}$target"
            else -> "${request.kind.name.lowercase()}$target"
        }
    }

    companion object {
        /**
         * How long to let the UI settle before re-reading.
         *
         * A window/content-changed event is the real signal, but a tap that
         * only toggles a checkbox may fire nothing at all — so this is the
         * floor, not an optimisation. Short enough not to be felt, long enough
         * for a transition to commit.
         */
        internal const val SETTLE_MS = 400L
        internal const val TAP_MS = 60L
        internal const val LONG_PRESS_MS = 700L
        internal const val SWIPE_MS = 250L

        /**
         * The pseudo-permission `screen_read` reports when the service is off.
         *
         * Not a real runtime permission, and it must NOT go in a tool's
         * `requiredPermissions`: `ToolExecutor.isGranted` returns false for
         * anything the package manager does not recognise, which would gate the
         * tool permanently. `NotificationListTool` established this pattern for
         * the same reason.
         */
        const val A11Y_PERMISSION = "android.permission.BIND_ACCESSIBILITY_SERVICE"
    }
}
