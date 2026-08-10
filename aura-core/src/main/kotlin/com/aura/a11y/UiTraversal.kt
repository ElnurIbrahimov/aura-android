package com.aura.a11y

/**
 * Walks a [NodeLike] tree into a flat list of [UiElement].
 *
 * **The tree never escapes this object.** Nodes are read and converted to plain
 * data immediately; nothing here returns or stores a [NodeLike]. That is a
 * requirement, not a style: `minSdk` is 26, so `AccessibilityNodeInfo.recycle()`
 * is not yet the no-op it became around API 33, and a leaked node exhausts the
 * obtain pool — which manifests as the service quietly returning nothing,
 * long after and far from the leak.
 *
 * Pure JVM. The platform type is adapted at the edge by the service.
 */
object UiTraversal {

    /** Elements visited before the walk gives up, whatever the cap. */
    private const val MAX_VISITED = 3_000

    fun flatten(root: NodeLike, options: SnapshotOptions): TraversalResult {
        val out = mutableListOf<UiElement>()
        var visited = 0
        var hasPassword = false

        // Explicit stack rather than recursion: WebViews and deeply nested
        // Compose trees are genuinely deep, and a StackOverflowError inside an
        // accessibility callback takes the service down rather than the call.
        // Carries the label an actionable ancestor already borrowed, so a
        // descendant does not repeat it. The immediate parent is not enough:
        // Compose nests the text two or three levels below the clickable node,
        // so a parent-only check collapses nothing in exactly the trees that
        // need it most.
        val stack = ArrayDeque<Triple<NodeLike, NodeLike?, String>>()
        stack.addLast(Triple(root, null, ""))

        while (stack.isNotEmpty() && visited < MAX_VISITED) {
            val (node, parent, claimedByAncestor) = stack.removeLast()
            visited++

            if (node.password) hasPassword = true

            var claimed = claimedByAncestor
            if (isVisible(node)) {
                val element = convert(node, claimedByAncestor, out.size)
                if (element != null && keep(element, node, options)) {
                    out += element
                    // An actionable node that borrowed its label from below
                    // owns that text now; the descendant it came from must not
                    // emit it again.
                    if (isActionable(node) && label(node).isBlank()) claimed = element.label
                }
            }

            // Children pushed in reverse so they pop in document order, which
            // is reading order and therefore the order a person would describe
            // the screen in.
            for (i in node.childCount - 1 downTo 0) {
                node.child(i)?.let { stack.addLast(Triple(it, node, claimed)) }
            }
        }

        val filtered = applyFilter(out, options)
        val cap = options.maxElements.coerceIn(1, SnapshotOptions.HARD_MAX_ELEMENTS)
        val kept = filtered.take(cap).mapIndexed { i, e -> e.copy(index = i + 1) }
        return TraversalResult(
            elements = kept,
            truncatedCount = (filtered.size - kept.size).coerceAtLeast(0),
            hasPasswordField = hasPassword,
        )
    }

    /**
     * Collapse a wrapper around a single text-bearing descendant.
     *
     * The dominant source of bloat, and the reason a raw dump blows the
     * character budget instantly: Compose and React Native wrap every button in
     * three or four nodes, only one of which carries the label and only one of
     * which is clickable. Emitting both produces `button ""` followed by
     * `text "Send"` and forces the model to guess they are the same thing.
     */
    private fun convert(node: NodeLike, claimedByAncestor: String, ordinal: Int): UiElement? {
        val ownLabel = label(node)
        val borrowed = if (ownLabel.isBlank() && isActionable(node)) borrowLabel(node) else ""
        val label = ownLabel.ifBlank { borrowed }

        // A node with no label and no affordance says nothing.
        if (label.isBlank() && !isActionable(node)) return null

        // A plain text node whose label an actionable ancestor already took
        // would repeat it. Drop the descendant, keep the thing that can be
        // acted on.
        if (!isActionable(node) && label.isNotBlank() && label == claimedByAncestor) return null

        return UiElement(
            index = ordinal + 1,
            role = role(node.className),
            label = redact(label, node),
            bounds = node.bounds,
            clickable = node.clickable,
            longClickable = node.longClickable,
            scrollable = node.scrollable,
            editable = node.editable,
            checkable = node.checkable,
            checked = node.checked,
            enabled = node.enabled,
            selector = ElementSelector(
                viewId = node.viewId?.substringAfterLast('/')?.ifBlank { null },
                text = node.text?.ifBlank { null },
                contentDescription = node.contentDescription?.ifBlank { null },
                className = node.className,
                bounds = node.bounds,
            ),
        )
    }

    /** The single text-bearing descendant's label, or blank if not exactly one. */
    private fun borrowLabel(node: NodeLike): String {
        val found = mutableListOf<String>()
        val stack = ArrayDeque<NodeLike>()
        for (i in 0 until node.childCount) node.child(i)?.let { stack.addLast(it) }
        var depth = 0
        while (stack.isNotEmpty() && depth < 64 && found.size < 2) {
            val n = stack.removeLast()
            depth++
            val l = label(n)
            if (l.isNotBlank()) found += l
            for (i in 0 until n.childCount) n.child(i)?.let { stack.addLast(it) }
        }
        return if (found.size == 1) found.first() else ""
    }

    private fun label(node: NodeLike): String =
        node.text?.takeIf { it.isNotBlank() }
            ?: node.contentDescription?.takeIf { it.isNotBlank() }
            ?: ""

    private fun isActionable(node: NodeLike): Boolean =
        node.clickable || node.longClickable || node.scrollable || node.editable || node.checkable

    private fun isVisible(node: NodeLike): Boolean =
        node.visibleToUser && !node.bounds.isEmpty

    private fun keep(element: UiElement, node: NodeLike, options: SnapshotOptions): Boolean =
        when (options.mode) {
            SnapshotOptions.Mode.ACTIONABLE -> element.actionable || element.label.isNotBlank()
            SnapshotOptions.Mode.TEXT -> element.label.isNotBlank()
            SnapshotOptions.Mode.FULL -> true
        }

    private fun applyFilter(elements: List<UiElement>, options: SnapshotOptions): List<UiElement> {
        if (options.filter.isBlank()) return elements
        val needle = options.filter.lowercase()
        return elements.filter {
            it.label.lowercase().contains(needle) || it.role.contains(needle)
        }
    }

    /**
     * Short role token from the platform class name.
     *
     * `android.widget.Button` is nine tokens of nothing; `button` is the whole
     * signal. At 40 elements the saving is real, and the model reads the short
     * form more reliably than the qualified one.
     */
    internal fun role(className: String?): String {
        val simple = className?.substringAfterLast('.') ?: return "view"
        return when {
            simple.contains("EditText", true) -> "input"
            simple.contains("Button", true) -> "button"
            simple.contains("CheckBox", true) -> "checkbox"
            simple.contains("Switch", true) || simple.contains("ToggleButton", true) -> "switch"
            simple.contains("RadioButton", true) -> "radio"
            simple.contains("ImageView", true) || simple.contains("ImageButton", true) -> "image"
            simple.contains("RecyclerView", true) || simple.contains("ListView", true) ||
                simple.contains("ScrollView", true) || simple.contains("ViewPager", true) -> "list"
            simple.contains("WebView", true) -> "web"
            simple.contains("TextView", true) -> "text"
            simple.contains("SeekBar", true) || simple.contains("ProgressBar", true) -> "slider"
            else -> "view"
        }
    }

    /**
     * Mask anything that looks like a secret before it can reach a model.
     *
     * `docs/architecture/privacy-boundaries.md` already required this
     * ("Accessibility UI snapshots are redacted before reaching the model").
     * The password flag is authoritative; the id pattern catches the fields
     * apps forget to mark, which in practice is most one-time-code inputs.
     */
    internal fun redact(label: String, node: NodeLike): String {
        if (node.password) return "••••"
        // Resource ids use underscores where a phrase would use spaces, so
        // normalise before matching or `security_code` slips past a pattern
        // written for `security code`.
        val haystack = ((node.viewId ?: "") + " " + (node.contentDescription ?: ""))
            .lowercase()
            .replace('_', ' ')
            .replace('-', ' ')
        return if (SECRET_HINT.containsMatchIn(haystack)) "[redacted]" else label
    }

    private val SECRET_HINT = Regex("otp|pin|cvv|cvc|card ?number|ssn|passcode|secret|token|security ?code")
}

data class TraversalResult(
    val elements: List<UiElement>,
    val truncatedCount: Int,
    val hasPasswordField: Boolean,
)
