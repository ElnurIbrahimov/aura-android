package com.aura.a11y

import android.accessibilityservice.AccessibilityService
import android.util.DisplayMetrics
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import dagger.hilt.android.AndroidEntryPoint
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
    }
}
