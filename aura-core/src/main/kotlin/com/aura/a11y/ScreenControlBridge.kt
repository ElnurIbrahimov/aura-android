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
}

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

    companion object {
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
