package com.aura.a11y

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityService.GestureResultCallback
import android.accessibilityservice.AccessibilityService.ScreenshotResult
import android.accessibilityservice.AccessibilityService.TakeScreenshotCallback
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.util.DisplayMetrics
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.util.Log
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

/**
 * The accessibility service. Adapts the platform to [A11yController] and does
 * nothing else.
 *
 * Lives in `:aura-core` because [ScreenControlBridge] and the tools that use it
 * do, and the tight main-thread contract belongs beside the code it constrains.
 * Declared in the app manifest with a relative name — the precedent is
 * `.security.ScreenCaptureService`, which is likewise implemented here.
 *
 * **Nothing here retains an [AccessibilityNodeInfo].** `minSdk` is 26, so
 * `recycle()` is not yet the no-op it became around API 33, and a leaked node
 * exhausts the obtain pool — which surfaces as the service silently returning
 * nothing, long after and far from the leak. [PlatformNode] wraps a node only
 * for the duration of one traversal, and [rootNode] hands the tree straight to
 * a walk that converts it to plain data before returning.
 */
@AndroidEntryPoint
class AuraAccessibilityService : AccessibilityService() {

    @Inject lateinit var bridge: ScreenControlBridge

    private var lastPackage: String = ""
    private var lastActivity: String = ""

    override fun onServiceConnected() {
        super.onServiceConnected()
        bridge.attach(controller)
        android.util.Log.i(TAG, "accessibility service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val e = event ?: return
        if (e.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        // Package and activity only. Deliberately NOT event text: this callback
        // fires for every window change in every app, and keeping their content
        // would turn the bridge into a device-wide keystroke and content log.
        // Never log these either — see the logging scan test.
        val pkg = e.packageName?.toString().orEmpty()
        val cls = e.className?.toString().orEmpty()
        if (pkg != lastPackage || cls != lastActivity) {
            lastPackage = pkg
            lastActivity = cls
            bridge.onWindowChanged(pkg, cls.substringAfterLast('.'))
        }
    }

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        bridge.detach()
        android.util.Log.i(TAG, "accessibility service unbound")
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        bridge.detach()
        super.onDestroy()
    }

    private val controller = object : A11yController {
        override fun rootNode(): NodeLike? = rootInActiveWindow?.let(::PlatformNode)
        override fun foregroundPackage(): String = lastPackage
        override fun foregroundActivity(): String = lastActivity.substringAfterLast('.')
        override fun screenWidth(): Int = metrics().widthPixels
        override fun screenHeight(): Int = metrics().heightPixels

        override suspend fun performNodeAction(
            selector: ElementSelector,
            action: NodeAction,
            text: String?,
        ): Boolean = withContext(Dispatchers.Main) {
            // Resolved against a FRESH tree every time. A node from an earlier
            // traversal may be recycled, detached, or describing a screen that
            // has since moved — docs/architecture/tool-policy.md requires the
            // re-read for exactly that reason.
            val root = rootInActiveWindow ?: return@withContext false
            val target = findBySelector(root, selector) ?: return@withContext false
            try {
                when (action) {
                    NodeAction.CLICK -> target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    NodeAction.LONG_CLICK -> target.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)
                    NodeAction.FOCUS -> target.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                    NodeAction.SCROLL_FORWARD -> target.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                    NodeAction.SCROLL_BACKWARD -> target.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
                    NodeAction.SET_TEXT -> target.performAction(
                        AccessibilityNodeInfo.ACTION_SET_TEXT,
                        android.os.Bundle().apply {
                            putCharSequence(
                                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                                text.orEmpty(),
                            )
                        },
                    )
                    NodeAction.CLEAR_TEXT -> target.performAction(
                        AccessibilityNodeInfo.ACTION_SET_TEXT,
                        android.os.Bundle().apply {
                            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "")
                        },
                    )
                }
            } finally {
                @Suppress("DEPRECATION")
                target.recycle()
            }
        }

        override suspend fun dispatchGesture(path: List<Pair<Int, Int>>, durationMs: Long): Boolean {
            if (path.isEmpty()) return false
            val stroke = Path().apply {
                moveTo(path.first().first.toFloat(), path.first().second.toFloat())
                path.drop(1).forEach { lineTo(it.first.toFloat(), it.second.toFloat()) }
            }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(stroke, 0, durationMs.coerceAtLeast(1)))
                .build()

            // withTimeout around the callback, not just around the gesture.
            // dispatchGesture's callback is not guaranteed to fire — a gesture
            // cancelled by a window transition can simply never call back — and
            // without this the bridge mutex would be held forever, taking every
            // later screen operation with it.
            return withContext(Dispatchers.Main) {
                withTimeoutOrNull(durationMs + GESTURE_CALLBACK_GRACE_MS) {
                    suspendCancellableCoroutine { cont ->
                        val callback = object : GestureResultCallback() {
                            override fun onCompleted(d: GestureDescription?) {
                                if (cont.isActive) cont.resume(true) {}
                            }

                            override fun onCancelled(d: GestureDescription?) {
                                if (cont.isActive) cont.resume(false) {}
                            }
                        }
                        if (!dispatchGesture(gesture, callback, null)) {
                            if (cont.isActive) cont.resume(false) {}
                        }
                    }
                } ?: false
            }
        }

        override suspend fun takeScreenshot(quality: Int): ByteArray? {
            // API 30+. Below that there is no accessibility screenshot at all
            // and the caller must fall back to MediaProjection.
            if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R) return null
            return withTimeoutOrNull(SCREENSHOT_TIMEOUT_MS) {
                suspendCancellableCoroutine { cont ->
                    takeScreenshot(
                        android.view.Display.DEFAULT_DISPLAY,
                        java.util.concurrent.Executors.newSingleThreadExecutor(),
                        object : TakeScreenshotCallback {
                            override fun onSuccess(result: ScreenshotResult) {
                                val bytes = runCatching {
                                    val bitmap = android.graphics.Bitmap.wrapHardwareBuffer(
                                        result.hardwareBuffer,
                                        result.colorSpace,
                                    ) ?: return@runCatching null
                                    val copy = bitmap.copy(android.graphics.Bitmap.Config.ARGB_8888, false)
                                    java.io.ByteArrayOutputStream().use { out ->
                                        copy.compress(
                                            android.graphics.Bitmap.CompressFormat.JPEG,
                                            quality.coerceIn(1, 100),
                                            out,
                                        )
                                        out.toByteArray()
                                    }
                                }.getOrNull()
                                // The HardwareBuffer is ours to close; leaking
                                // one per capture exhausts graphics memory and
                                // the symptom appears far from the cause.
                                runCatching { result.hardwareBuffer.close() }
                                    .onFailure { Log.w(TAG, "hardware buffer close failed: ${it.message}", it) }
                                if (cont.isActive) cont.resume(bytes) {}
                            }

                            override fun onFailure(errorCode: Int) {
                                // A FLAG_SECURE window failing here is expected.
                                // Everything else is not, and treating the whole
                                // callback as "expected" is what let a total
                                // outage hide: the service was missing
                                // canTakeScreenshot, so every call landed here
                                // with NO_ACCESSIBILITY_ACCESS, resumed null, and
                                // looked exactly like a banking app being
                                // screenshotted. Naming the code is the
                                // difference between a fallback and a failure.
                                if (errorCode != AccessibilityService.ERROR_TAKE_SCREENSHOT_SECURE_WINDOW) {
                                    Log.w(TAG, "accessibility screenshot failed: ${screenshotErrorName(errorCode)}")
                                }
                                if (cont.isActive) cont.resume(null) {}
                            }
                        },
                    )
                }
            }
        }

        override suspend fun performGlobalAction(action: GlobalAction): Boolean =
            withContext(Dispatchers.Main) {
                performGlobalAction(
                    when (action) {
                        GlobalAction.BACK -> GLOBAL_ACTION_BACK
                        GlobalAction.HOME -> GLOBAL_ACTION_HOME
                        GlobalAction.RECENTS -> GLOBAL_ACTION_RECENTS
                        GlobalAction.NOTIFICATIONS -> GLOBAL_ACTION_NOTIFICATIONS
                    },
                )
            }
    }

    /**
     * Best match for [selector] in a fresh tree, or null.
     *
     * Scored rather than exact, because no single field is reliable: Compose
     * emits no view ids, text changes as a screen updates, and bounds shift on
     * scroll. Requiring all of them would fail constantly; accepting any one
     * would act on the wrong element. The threshold exists so a weak partial
     * match is treated as "not found" rather than as good enough.
     *
     * The caller owns the returned node and must recycle it.
     */
    private fun findBySelector(root: AccessibilityNodeInfo, selector: ElementSelector): AccessibilityNodeInfo? {
        var best: AccessibilityNodeInfo? = null
        var bestScore = 0
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(AccessibilityNodeInfo.obtain(root))
        var visited = 0

        while (stack.isNotEmpty() && visited < MAX_SELECTOR_VISITS) {
            val node = stack.removeLast()
            visited++
            val candidate = ElementSelector(
                viewId = node.viewIdResourceName?.substringAfterLast('/')?.ifBlank { null },
                text = node.text?.toString()?.ifBlank { null },
                contentDescription = node.contentDescription?.toString()?.ifBlank { null },
                className = node.className?.toString(),
                bounds = android.graphics.Rect().also { node.getBoundsInScreen(it) }
                    .let { Rect4(it.left, it.top, it.right, it.bottom) },
            )
            val score = selector.score(candidate)
            if (score > bestScore) {
                @Suppress("DEPRECATION")
                best?.recycle()
                best = AccessibilityNodeInfo.obtain(node)
                bestScore = score
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { stack.addLast(it) }
            }
            @Suppress("DEPRECATION")
            node.recycle()
        }

        if (bestScore < MIN_SELECTOR_SCORE) {
            @Suppress("DEPRECATION")
            best?.recycle()
            return null
        }
        return best
    }

    @Suppress("DEPRECATION")
    private fun metrics(): DisplayMetrics = resources.displayMetrics

    /**
     * Adapts one [AccessibilityNodeInfo] to [NodeLike].
     *
     * Reads through to the live node rather than copying, because a traversal
     * touches a small fraction of the fields on most nodes and copying all of
     * them for a 2,000-node tree is the expensive option. Safe because the
     * wrapper never outlives the traversal that created it.
     */
    private class PlatformNode(private val node: AccessibilityNodeInfo) : NodeLike {
        override val viewId: String? get() = node.viewIdResourceName
        override val text: String? get() = node.text?.toString()
        override val contentDescription: String? get() = node.contentDescription?.toString()
        override val className: String? get() = node.className?.toString()
        override val bounds: Rect4
            get() = android.graphics.Rect().also { node.getBoundsInScreen(it) }
                .let { Rect4(it.left, it.top, it.right, it.bottom) }
        override val clickable: Boolean get() = node.isClickable
        override val longClickable: Boolean get() = node.isLongClickable
        override val scrollable: Boolean get() = node.isScrollable
        override val editable: Boolean get() = node.isEditable
        override val checkable: Boolean get() = node.isCheckable
        override val checked: Boolean get() = node.isChecked
        override val enabled: Boolean get() = node.isEnabled
        override val password: Boolean get() = node.isPassword
        override val visibleToUser: Boolean get() = node.isVisibleToUser
        override val childCount: Int get() = node.childCount
        override fun child(i: Int): NodeLike? = node.getChild(i)?.let(::PlatformNode)
    }

    private companion object {
        const val TAG = "AuraA11y"

        /**
         * Extra time allowed for dispatchGesture's callback beyond the gesture
         * itself. The callback is not guaranteed to fire — a gesture cancelled
         * by a window transition can simply never call back — so this bounds
         * the wait rather than trusting it.
         */
        const val GESTURE_CALLBACK_GRACE_MS = 2_000L

        /** Nodes examined while resolving a selector. */
        const val MAX_SELECTOR_VISITS = 3_000

        /**
         * Minimum selector score to act on.
         *
         * 4 is one strong field — a matching text or content description — or
         * a class plus bounds. Below that the match is a coincidence, and
         * acting on a coincidence is how an agent taps the wrong thing.
         */
        const val MIN_SELECTOR_SCORE = 4

        /** Screenshots are a single platform call; this only bounds a hang. */
        const val SCREENSHOT_TIMEOUT_MS = 5_000L

        /**
         * Names a [TakeScreenshotCallback.onFailure] code.
         *
         * The int alone is not worth logging — nobody reads a stack trace and
         * recalls that 2 means the service never asked for the capability, which
         * is precisely the failure that went unnoticed here for the life of the
         * feature.
         */
        fun screenshotErrorName(code: Int): String = when (code) {
            AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERNAL_ERROR -> "INTERNAL_ERROR"
            AccessibilityService.ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS ->
                "NO_ACCESSIBILITY_ACCESS (canTakeScreenshot missing from the service config)"
            AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT -> "INTERVAL_TIME_SHORT"
            AccessibilityService.ERROR_TAKE_SCREENSHOT_INVALID_DISPLAY -> "INVALID_DISPLAY"
            else -> "code $code"
        }
    }
}
