package com.aura.a11y

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Traversal and serialisation, tested with no device and no emulator.
 *
 * That is the whole point of [NodeLike]: `AccessibilityNodeInfo` is final,
 * pooled and effectively unmockable, so the platform type stays at the very
 * edge and everything with real logic sits above it as plain JVM code CI can
 * run. A screen-control feature whose only verification is "try it on a phone"
 * is a feature nobody dares change.
 */
class UiTraversalTest {

    /** A test tree. `recycled` counts calls so leak discipline is assertable. */
    private class FakeNode(
        override val viewId: String? = null,
        override val text: String? = null,
        override val contentDescription: String? = null,
        override val className: String? = "android.view.View",
        override val bounds: Rect4 = Rect4(0, 0, 100, 50),
        override val clickable: Boolean = false,
        override val longClickable: Boolean = false,
        override val scrollable: Boolean = false,
        override val editable: Boolean = false,
        override val checkable: Boolean = false,
        override val checked: Boolean = false,
        override val enabled: Boolean = true,
        override val password: Boolean = false,
        override val visibleToUser: Boolean = true,
        private val children: List<FakeNode> = emptyList(),
    ) : NodeLike {
        override val childCount: Int get() = children.size
        override fun child(i: Int): NodeLike? = children.getOrNull(i)
    }

    private fun flatten(root: FakeNode, options: SnapshotOptions = SnapshotOptions()) =
        UiTraversal.flatten(root, options)

    // ---- the reduction that makes the budget work ------------------------

    @Test
    fun `a wrapper around a single label collapses to one element`() {
        // The dominant bloat source, and the reason a raw dump blows the budget
        // instantly: Compose and React Native wrap every button in three or
        // four nodes, one clickable and one labelled. Emitting both produces
        // `button ""` then `text "Send"` and forces the model to guess.
        val tree = FakeNode(
            children = listOf(
                FakeNode(
                    className = "android.widget.Button",
                    clickable = true,
                    children = listOf(
                        FakeNode(
                            className = "android.view.View",
                            children = listOf(FakeNode(className = "android.widget.TextView", text = "Send")),
                        ),
                    ),
                ),
            ),
        )
        val result = flatten(tree)
        assertEquals(1, result.elements.size, "expected one collapsed element, got ${result.elements}")
        assertEquals("button", result.elements[0].role)
        assertEquals("Send", result.elements[0].label)
        assertTrue(result.elements[0].clickable)
    }

    @Test
    fun `a wrapper with two labels does NOT collapse`() {
        // Collapsing here would silently discard one of them. Ambiguity is a
        // reason to emit both, not to pick.
        val tree = FakeNode(
            children = listOf(
                FakeNode(
                    className = "android.widget.LinearLayout",
                    clickable = true,
                    children = listOf(
                        FakeNode(className = "android.widget.TextView", text = "Title"),
                        FakeNode(className = "android.widget.TextView", text = "Subtitle"),
                    ),
                ),
            ),
        )
        val labels = flatten(tree).elements.map { it.label }
        assertTrue("Title" in labels && "Subtitle" in labels, "a label was dropped: $labels")
    }

    @Test
    fun `invisible and zero-area nodes are dropped`() {
        val tree = FakeNode(
            children = listOf(
                FakeNode(text = "visible"),
                FakeNode(text = "hidden", visibleToUser = false),
                FakeNode(text = "collapsed", bounds = Rect4(0, 0, 0, 0)),
            ),
        )
        assertEquals(listOf("visible"), flatten(tree).elements.map { it.label })
    }

    @Test
    fun `a node with no label and no affordance is dropped`() {
        val tree = FakeNode(children = listOf(FakeNode(className = "android.view.View")))
        assertTrue(flatten(tree).elements.isEmpty())
    }

    @Test
    fun `an actionable node with no label survives`() {
        // An unlabelled icon button is still something the model can try.
        val tree = FakeNode(
            children = listOf(FakeNode(className = "android.widget.ImageButton", clickable = true)),
        )
        assertEquals(1, flatten(tree).elements.size)
    }

    // ---- the budget ------------------------------------------------------

    @Test
    fun `a huge tree stays inside the character budget`() {
        // The assertion the 4k tool-result limit actually depends on.
        val children = (1..200).map {
            FakeNode(
                className = "android.widget.Button",
                clickable = true,
                text = "Button number $it with a fairly long descriptive label attached",
                bounds = Rect4(0, it * 10, 300, it * 10 + 40),
            )
        }
        val result = flatten(FakeNode(children = children), SnapshotOptions(maxElements = 100))
        val rendered = UiSnapshotSerializer.render(
            UiSnapshot(1, "com.test", "Main", 1080, 2400, result.elements, result.truncatedCount, false),
        )
        assertTrue(
            rendered.length <= UiSnapshotSerializer.MAX_CHARS,
            "rendered ${rendered.length} chars, over the ${UiSnapshotSerializer.MAX_CHARS} budget",
        )
        assertTrue("more" in rendered, "the model must be told elements were dropped: $rendered")
    }

    @Test
    fun `the element cap is honoured and reported`() {
        val children = (1..50).map { FakeNode(text = "item $it") }
        val result = flatten(FakeNode(children = children), SnapshotOptions(maxElements = 10))
        assertEquals(10, result.elements.size)
        assertEquals(40, result.truncatedCount)
    }

    @Test
    fun `the hard cap cannot be exceeded by asking`() {
        val children = (1..500).map { FakeNode(text = "item $it") }
        val result = flatten(FakeNode(children = children), SnapshotOptions(maxElements = 9999))
        assertTrue(result.elements.size <= SnapshotOptions.HARD_MAX_ELEMENTS)
    }

    @Test
    fun `indices are contiguous from one`() {
        // The model refers to elements by index; a gap makes a reference
        // ambiguous and a zero-based one makes "#1" mean the second item.
        val children = (1..5).map { FakeNode(text = "item $it") }
        val result = flatten(FakeNode(children = children))
        assertEquals(listOf(1, 2, 3, 4, 5), result.elements.map { it.index })
    }

    // ---- redaction -------------------------------------------------------

    @Test
    fun `a password field is masked and flagged`() {
        val tree = FakeNode(
            children = listOf(
                FakeNode(className = "android.widget.EditText", editable = true, password = true, text = "hunter2"),
            ),
        )
        val result = flatten(tree)
        assertEquals("••••", result.elements[0].label)
        assertTrue(result.hasPasswordField, "the snapshot must flag it so actions can be refused")
    }

    @Test
    fun `fields that look like secrets are redacted even when not marked password`() {
        // Most one-time-code inputs are never marked isPassword, which is
        // exactly why the flag alone is not enough.
        val ids = listOf("com.bank:id/otp_input", "com.x:id/cvv", "com.y:id/security_code")
        ids.forEach { id ->
            val tree = FakeNode(
                children = listOf(
                    FakeNode(className = "android.widget.EditText", editable = true, viewId = id, text = "123456"),
                ),
            )
            assertEquals("[redacted]", flatten(tree).elements[0].label, "not redacted: $id")
        }
    }

    @Test
    fun `ordinary fields are not redacted`() {
        val tree = FakeNode(
            children = listOf(
                FakeNode(className = "android.widget.EditText", editable = true, viewId = "com.x:id/message", text = "hello"),
            ),
        )
        assertEquals("hello", flatten(tree).elements[0].label)
    }

    // ---- roles and filtering ---------------------------------------------

    @Test
    fun `platform class names map to short role tokens`() {
        assertEquals("button", UiTraversal.role("android.widget.Button"))
        assertEquals("input", UiTraversal.role("android.widget.EditText"))
        assertEquals("text", UiTraversal.role("android.widget.TextView"))
        assertEquals("list", UiTraversal.role("androidx.recyclerview.widget.RecyclerView"))
        assertEquals("switch", UiTraversal.role("android.widget.Switch"))
        assertEquals("web", UiTraversal.role("android.webkit.WebView"))
        assertEquals("view", UiTraversal.role(null))
        assertEquals("view", UiTraversal.role("com.example.SomeCustomThing"))
    }

    @Test
    fun `the filter narrows by label`() {
        val tree = FakeNode(
            children = listOf(
                FakeNode(text = "Send message"),
                FakeNode(text = "Delete conversation"),
                FakeNode(text = "Archive"),
            ),
        )
        val result = flatten(tree, SnapshotOptions(filter = "send"))
        assertEquals(listOf("Send message"), result.elements.map { it.label })
    }

    @Test
    fun `TEXT mode keeps only labelled elements`() {
        val tree = FakeNode(
            children = listOf(
                FakeNode(text = "readable"),
                FakeNode(className = "android.widget.ImageButton", clickable = true),
            ),
        )
        val result = flatten(tree, SnapshotOptions(mode = SnapshotOptions.Mode.TEXT))
        assertEquals(listOf("readable"), result.elements.map { it.label })
    }

    @Test
    fun `document order is preserved`() {
        // Reading order is how a person would describe the screen, and the
        // explicit stack has to push children reversed to get it.
        val tree = FakeNode(
            children = listOf(FakeNode(text = "first"), FakeNode(text = "second"), FakeNode(text = "third")),
        )
        assertEquals(listOf("first", "second", "third"), flatten(tree).elements.map { it.label })
    }

    @Test
    fun `a deeply nested tree does not overflow the stack`() {
        // WebViews and Compose trees are genuinely deep, and a
        // StackOverflowError inside an accessibility callback takes the whole
        // service down rather than the call.
        var node = FakeNode(text = "leaf")
        repeat(2_000) { node = FakeNode(children = listOf(node)) }
        val result = flatten(node)
        assertTrue(result.elements.isNotEmpty() || result.truncatedCount >= 0)
    }

    // ---- serialisation ---------------------------------------------------

    @Test
    fun `the rendered header carries the snapshot id`() {
        // Without it the model cannot pass a snapshot back, and every action
        // would have to trust that nothing moved.
        val snap = UiSnapshot(7, "com.whatsapp", "ConversationActivity", 1080, 2400, emptyList(), 0, false)
        val out = UiSnapshotSerializer.render(snap)
        assertTrue("snapshot=7" in out, out)
        assertTrue("app=com.whatsapp" in out, out)
        assertTrue("1080x2400" in out, out)
    }

    @Test
    fun `an empty screen says so rather than returning nothing`() {
        // "No elements" and "unreadable" call for completely different next
        // moves, and an empty string conveys neither.
        val snap = UiSnapshot(1, "com.game", "", 1080, 2400, emptyList(), 0, false)
        val out = UiSnapshotSerializer.render(snap)
        assertTrue("no elements matched" in out, out)
        assertTrue("FLAG_SECURE" in out, "the likely cause should be named: $out")
    }

    @Test
    fun `a visible password field is announced in the header`() {
        val snap = UiSnapshot(1, "com.bank", "Login", 1080, 2400, emptyList(), 0, hasPasswordField = true)
        assertTrue("password field visible" in UiSnapshotSerializer.render(snap))
    }

    @Test
    fun `coordinates appear only on actionable elements`() {
        val text = UiElement(
            1, "text", "just words", Rect4(0, 0, 100, 50),
            clickable = false, longClickable = false, scrollable = false, editable = false,
            checkable = false, checked = false, enabled = true,
            selector = ElementSelector(null, "just words", null, "android.widget.TextView", Rect4(0, 0, 100, 50)),
        )
        val button = text.copy(index = 2, role = "button", label = "Tap me", clickable = true)
        val out = UiSnapshotSerializer.render(
            UiSnapshot(1, "com.x", "A", 1080, 2400, listOf(text, button), 0, false),
        )
        val lines = out.lines()
        assertTrue(lines.none { it.startsWith("#1") && "(" in it }, "text line carried coordinates: $out")
        assertTrue(lines.any { it.startsWith("#2") && "(50,25)" in it }, "button line lacks coordinates: $out")
    }

    @Test
    fun `long labels are truncated inside the line`() {
        val long = "x".repeat(500)
        val e = UiElement(
            1, "text", long, Rect4(0, 0, 100, 50),
            clickable = false, longClickable = false, scrollable = false, editable = false,
            checkable = false, checked = false, enabled = true,
            selector = ElementSelector(null, long, null, null, Rect4(0, 0, 100, 50)),
        )
        val out = UiSnapshotSerializer.render(UiSnapshot(1, "com.x", "A", 1080, 2400, listOf(e), 0, false))
        assertTrue(out.length < 200, "a single long label blew the line: ${out.length}")
        assertTrue("…" in out)
    }

    @Test
    fun `newlines in a label do not break the line format`() {
        // One element per line is the entire parse contract.
        val e = UiElement(
            1, "text", "line one\nline two\nline three", Rect4(0, 0, 100, 50),
            clickable = false, longClickable = false, scrollable = false, editable = false,
            checkable = false, checked = false, enabled = true,
            selector = ElementSelector(null, null, null, null, Rect4(0, 0, 100, 50)),
        )
        val out = UiSnapshotSerializer.render(UiSnapshot(1, "com.x", "A", 1080, 2400, listOf(e), 0, false))
        assertEquals(2, out.lines().size, "expected header + one element line, got:\n$out")
    }

    // ---- selectors -------------------------------------------------------

    @Test
    fun `selector scoring prefers an id match over anything else`() {
        val base = ElementSelector("send", "Send", null, "android.widget.Button", Rect4(0, 0, 10, 10))
        val sameId = base.copy(text = "Different", bounds = Rect4(99, 99, 100, 100))
        val sameTextOnly = ElementSelector(null, "Send", null, null, Rect4(50, 50, 60, 60))
        assertTrue(base.score(sameId) > base.score(sameTextOnly))
    }

    @Test
    fun `a selector with nothing in common scores zero`() {
        val a = ElementSelector("send", "Send", null, "android.widget.Button", Rect4(0, 0, 10, 10))
        val b = ElementSelector("cancel", "Cancel", null, "android.widget.TextView", Rect4(50, 50, 60, 60))
        assertEquals(0, a.score(b))
    }

    @Test
    fun `a Compose-style element with no id still matches on text`() {
        // Compose emits no view ids, so this is the common case rather than
        // the fallback the design reads as.
        val a = ElementSelector(null, "Send", null, "android.view.View", Rect4(0, 0, 10, 10))
        assertTrue(a.score(a.copy(bounds = Rect4(0, 40, 10, 50))) > 0, "a scrolled element became unmatchable")
    }
}
