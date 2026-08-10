package com.aura.a11y

import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The act path, driven through a fake [A11yController].
 *
 * That the controller is an interface is what makes this possible: every
 * decision — node action versus gesture, swipe direction, verify-after-act,
 * stale-snapshot refusal — is testable in CI, and only the platform adapter
 * needs a device.
 */
class ScreenActBridgeTest {

    private class FakeNode(
        override val text: String? = null,
        override val className: String? = "android.view.View",
        override val bounds: Rect4 = Rect4(0, 0, 100, 50),
        override val clickable: Boolean = false,
        override val editable: Boolean = false,
        override val scrollable: Boolean = false,
        override val password: Boolean = false,
        private val children: List<FakeNode> = emptyList(),
    ) : NodeLike {
        override val viewId: String? = null
        override val contentDescription: String? = null
        override val longClickable = false
        override val checkable = false
        override val checked = false
        override val enabled = true
        override val visibleToUser = true
        override val childCount: Int get() = children.size
        override fun child(i: Int): NodeLike? = children.getOrNull(i)
    }

    private open class FakeController(
        var tree: FakeNode = FakeNode(children = listOf(FakeNode(text = "Send", clickable = true))),
        var pkg: String = "com.whatsapp",
        var nodeActionSucceeds: Boolean = true,
    ) : A11yController {
        val nodeActions = mutableListOf<Pair<NodeAction, String?>>()
        val gestures = mutableListOf<List<Pair<Int, Int>>>()
        val globals = mutableListOf<GlobalAction>()

        override fun rootNode(): NodeLike = tree
        override fun foregroundPackage() = pkg
        override fun foregroundActivity() = "Main"
        override fun screenWidth() = 1080
        override fun screenHeight() = 2400

        override suspend fun performNodeAction(
            selector: ElementSelector,
            action: NodeAction,
            text: String?,
        ): Boolean {
            nodeActions += action to text
            return nodeActionSucceeds
        }


        override suspend fun dispatchGesture(path: List<Pair<Int, Int>>, durationMs: Long): Boolean {
            gestures += path
            return true
        }

        override suspend fun performGlobalAction(action: GlobalAction): Boolean {
            globals += action
            return true
        }
    }

    private fun bridgeWith(controller: FakeController): ScreenControlBridge =
        ScreenControlBridge().also { it.attach(controller) }

    // ---- node action preferred over gesture ------------------------------

    @Test
    fun `a tap uses the node action, not a synthesized gesture`() = runBlocking {
        // A node action works through partial overlays and cannot miss by a few
        // pixels. The gesture is the fallback, not the default.
        val c = FakeController()
        val bridge = bridgeWith(c)
        val snap = bridge.snapshot()
        val element = snap.elements.first()

        bridge.act(ActionRequest(ActionRequest.Kind.TAP, snap.id, element.index), element)

        assertEquals(listOf(NodeAction.CLICK), c.nodeActions.map { it.first })
        assertTrue(c.gestures.isEmpty(), "a gesture was dispatched when a node action succeeded")
    }

    @Test
    fun `a tap falls back to a gesture when the node action fails`() = runBlocking {
        val c = FakeController(nodeActionSucceeds = false)
        val bridge = bridgeWith(c)
        val snap = bridge.snapshot()
        val element = snap.elements.first()

        bridge.act(ActionRequest(ActionRequest.Kind.TAP, snap.id, element.index), element)

        assertEquals(1, c.gestures.size, "no gesture fallback")
        assertEquals(listOf(50 to 25), c.gestures.first(), "the gesture did not target the element centre")
    }

    @Test
    fun `typing never falls back to a gesture`() = runBlocking {
        // Typing via synthesized taps on a keyboard the agent cannot see is
        // guesswork with a real cost: characters land in the wrong field.
        val c = FakeController(
            tree = FakeNode(children = listOf(FakeNode(text = "Message", editable = true))),
            nodeActionSucceeds = false,
        )
        val bridge = bridgeWith(c)
        val snap = bridge.snapshot()
        val element = snap.elements.first()

        bridge.act(
            ActionRequest(ActionRequest.Kind.TYPE, snap.id, element.index, text = "hello"),
            element,
        )

        assertTrue(c.gestures.isEmpty(), "typing fell back to a gesture")
        assertTrue(c.nodeActions.any { it.first == NodeAction.SET_TEXT && it.second == "hello" })
    }

    @Test
    fun `typing focuses the field before setting text`() = runBlocking {
        val c = FakeController(tree = FakeNode(children = listOf(FakeNode(text = "Message", editable = true))))
        val bridge = bridgeWith(c)
        val snap = bridge.snapshot()

        bridge.act(
            ActionRequest(ActionRequest.Kind.TYPE, snap.id, 1, text = "hi"),
            snap.elements.first(),
        )

        assertEquals(NodeAction.FOCUS, c.nodeActions.first().first, "text was set without focusing first")
    }

    // ---- gesture geometry ------------------------------------------------

    @Test
    fun `a swipe travels against the direction of intent`() = runBlocking {
        // Scrolling DOWN means dragging the content UP. Getting this backwards
        // is the easiest mistake here and produces a feature that appears to
        // work in reverse.
        val c = FakeController(tree = FakeNode(bounds = Rect4(0, 0, 300, 600)))
        val bridge = bridgeWith(c)
        bridge.snapshot()

        bridge.act(ActionRequest(ActionRequest.Kind.SWIPE, direction = ActionRequest.Direction.DOWN), null)
        val down = c.gestures.last()
        assertTrue(down.first().second > down.last().second, "swipe down must drag upward: $down")

        bridge.act(ActionRequest(ActionRequest.Kind.SWIPE, direction = ActionRequest.Direction.UP), null)
        val up = c.gestures.last()
        assertTrue(up.first().second < up.last().second, "swipe up must drag downward: $up")
    }

    @Test
    fun `global actions are dispatched without an element`() = runBlocking {
        val c = FakeController()
        val bridge = bridgeWith(c)
        bridge.snapshot()

        bridge.act(ActionRequest(ActionRequest.Kind.BACK), null)
        bridge.act(ActionRequest(ActionRequest.Kind.HOME), null)

        assertEquals(listOf(GlobalAction.BACK, GlobalAction.HOME), c.globals)
    }

    // ---- verify-after-act ------------------------------------------------

    @Test
    fun `an unchanged screen is reported as unchanged`() = runBlocking {
        val c = FakeController()
        val bridge = bridgeWith(c)
        val snap = bridge.snapshot()

        val outcome = bridge.act(ActionRequest(ActionRequest.Kind.TAP, snap.id, 1), snap.elements.first())

        assertTrue(outcome.performed)
        assertFalse(outcome.screenChanged, "an identical screen was reported as changed")
    }

    @Test
    fun `a changed screen is detected`() = runBlocking {
        val c = FakeController()
        val bridge = bridgeWith(c)
        val snap = bridge.snapshot()
        val element = snap.elements.first()
        // The tap "navigates": the tree the next read sees is different.
        c.tree = FakeNode(children = listOf(FakeNode(text = "Delivered", clickable = true)))

        val outcome = bridge.act(ActionRequest(ActionRequest.Kind.TAP, snap.id, element.index), element)

        assertTrue(outcome.screenChanged, "a different screen was reported as unchanged")
    }

    @Test
    fun `a package change counts as a change even if the elements match`() = runBlocking {
        // The package has to change AS A RESULT of the action, not before it —
        // `act` captures the package on entry, so setting it up front makes
        // before and after agree and the test proves nothing. That is exactly
        // the shape of a real navigation: the tap causes the switch.
        val c = object : FakeController() {
            override suspend fun performNodeAction(
                selector: ElementSelector,
                action: NodeAction,
                text: String?,
            ): Boolean {
                pkg = "com.android.chrome"
                return super.performNodeAction(selector, action, text)
            }
        }
        val bridge = bridgeWith(c)
        val snap = bridge.snapshot()

        val outcome = bridge.act(ActionRequest(ActionRequest.Kind.TAP, snap.id, 1), snap.elements.first())

        assertTrue(outcome.screenChanged, "switching apps was reported as unchanged")
        assertEquals("com.android.chrome", outcome.newPackage)
    }

    @Test
    fun `acting refreshes the snapshot so the next reference is valid`() = runBlocking {
        // Otherwise the model would have to issue a separate screen_read after
        // every action, which is the round-trip read_after exists to remove.
        val c = FakeController()
        val bridge = bridgeWith(c)
        val first = bridge.snapshot()

        bridge.act(ActionRequest(ActionRequest.Kind.TAP, first.id, 1), first.elements.first())

        val after = bridge.currentSnapshot()
        assertTrue(after != null && after.id > first.id, "the snapshot was not refreshed after acting")
    }

    // ---- staleness -------------------------------------------------------

    @Test
    fun `a stale snapshot id is refused`() = runBlocking {
        val c = FakeController()
        val bridge = bridgeWith(c)
        val first = bridge.snapshot()
        bridge.snapshot() // supersedes it

        val result = bridge.resolve(first.id, 1)

        assertTrue(result.isFailure, "an action against a superseded snapshot was allowed")
        assertTrue("stale" in result.exceptionOrNull()!!.message!!.lowercase())
    }

    @Test
    fun `an unknown element index is refused`() = runBlocking {
        val c = FakeController()
        val bridge = bridgeWith(c)
        val snap = bridge.snapshot()

        assertTrue(bridge.resolve(snap.id, 99).isFailure)
    }

    @Test
    fun `resolving before any snapshot is refused`() {
        val bridge = bridgeWith(FakeController())
        assertTrue(bridge.resolve(1, 1).isFailure)
    }

    @Test
    fun `snapshot ids are not reused after a reconnect`() = runBlocking {
        // A reused id is the one staleness case the check cannot catch: an
        // action carrying an old id would match a new snapshot and land on
        // whatever now occupies that index.
        val c = FakeController()
        val bridge = bridgeWith(c)
        val first = bridge.snapshot()
        bridge.detach()
        bridge.attach(c)
        val afterReconnect = bridge.snapshot()

        assertTrue(afterReconnect.id > first.id, "snapshot ids restarted after reconnect")
    }

    // ---- disconnected ----------------------------------------------------

    @Test
    fun `reading without a connected service fails cleanly`() = runBlocking {
        val bridge = ScreenControlBridge()
        val result = runCatching { bridge.snapshot() }
        assertTrue(result.isFailure)
        assertTrue((result.exceptionOrNull() as ScreenControlException).error is ScreenControlError.NotConnected)
    }

    @Test
    fun `detach clears the connected flag and the current snapshot`() = runBlocking {
        val bridge = bridgeWith(FakeController())
        bridge.snapshot()
        assertTrue(bridge.connected.value)

        bridge.detach()

        assertFalse(bridge.connected.value)
        assertTrue(bridge.currentSnapshot() == null, "a stale snapshot survived a disconnect")
    }
}
