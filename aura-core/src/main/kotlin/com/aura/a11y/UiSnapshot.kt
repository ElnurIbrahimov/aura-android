package com.aura.a11y

/** Plain rectangle. Deliberately not `android.graphics.Rect`, so this file is pure JVM. */
data class Rect4(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val centerX: Int get() = (left + right) / 2
    val centerY: Int get() = (top + bottom) / 2
    val width: Int get() = right - left
    val height: Int get() = bottom - top
    val isEmpty: Boolean get() = width <= 0 || height <= 0
}

/**
 * The subset of `AccessibilityNodeInfo` the traversal reads.
 *
 * The single most important testability decision in this package. Traversal and
 * serialisation are the parts with real logic and real failure modes, and
 * `AccessibilityNodeInfo` is final, pooled, and effectively unmockable — so the
 * platform type stays at the very edge, behind this, and everything above it is
 * a pure JVM unit that CI can run with no device and no emulator.
 */
interface NodeLike {
    val viewId: String?
    val text: String?
    val contentDescription: String?
    val className: String?
    val bounds: Rect4
    val clickable: Boolean
    val longClickable: Boolean
    val scrollable: Boolean
    val editable: Boolean
    val checkable: Boolean
    val checked: Boolean
    val enabled: Boolean
    val password: Boolean
    val visibleToUser: Boolean
    val childCount: Int
    fun child(i: Int): NodeLike?
}

/**
 * One element the model can see and, if actionable, refer to.
 *
 * [selector] rather than a node handle. A node cannot outlive its traversal —
 * see `UiTraversal` — and by the time an action runs the screen may have moved
 * anyway, so an element is re-resolved against a FRESH tree from its selector.
 * `docs/architecture/tool-policy.md` already specified this ("Accessibility
 * actions require a fresh UI snapshot — stale nodes are rejected") before there
 * was anything to specify it for.
 */
data class UiElement(
    val index: Int,
    val role: String,
    val label: String,
    val bounds: Rect4,
    val clickable: Boolean,
    val longClickable: Boolean,
    val scrollable: Boolean,
    val editable: Boolean,
    val checkable: Boolean,
    val checked: Boolean,
    val enabled: Boolean,
    val selector: ElementSelector,
) {
    val actionable: Boolean get() = clickable || longClickable || scrollable || editable || checkable
}

/**
 * How to find an element again in a later traversal.
 *
 * More robust than raw coordinates: scrolling moves pixels but not identity.
 * Weaker than it reads, though — Compose emits no view ids, so `viewId` is null
 * in most modern apps and matching leans on text, content description and
 * bounds.
 */
data class ElementSelector(
    val viewId: String?,
    val text: String?,
    val contentDescription: String?,
    val className: String?,
    val bounds: Rect4,
) {
    /**
     * How well [other] matches, 0 = not at all.
     *
     * Scored rather than boolean because no single field is reliable: ids are
     * absent in Compose, text changes as the screen updates, and bounds shift
     * on scroll. Requiring all of them would fail constantly; requiring any one
     * would match the wrong thing.
     */
    fun score(other: ElementSelector): Int {
        var s = 0
        if (viewId != null && viewId == other.viewId) s += 8
        if (text != null && text == other.text) s += 4
        if (contentDescription != null && contentDescription == other.contentDescription) s += 4
        if (className != null && className == other.className) s += 1
        if (bounds == other.bounds) s += 2
        return s
    }

    /**
     * The element in [snapshot] this selector refers to, or null if it cannot be found.
     *
     * Used to replay a recorded step against a screen that has moved on since. Refuses in
     * two cases, both deliberately:
     *
     * - **Nothing scores [MIN_MATCH_SCORE].** Position and class alone total 3, so a match
     *   has to come from what the element *says* — its id, text or description — never from
     *   where it sits. A redesigned screen puts a different control in the same place, and
     *   "the button that is where the button used to be" is how a macro deletes an account.
     * - **The best score is tied.** Two identical "Delete" buttons carry no information that
     *   separates them, and choosing the first is choosing at random.
     *
     * Null means the step stops with a legible reason. That is the correct outcome: a
     * recorded Hand runs unsupervised inside someone else's app, so the cost of doing
     * nothing is a re-run, and the cost of doing the wrong thing is unbounded.
     */
    fun bestMatchIn(snapshot: UiSnapshot): UiElement? {
        val scored = snapshot.elements
            .map { it to score(it.selector) }
            .filter { (_, s) -> s >= MIN_MATCH_SCORE }
        val best = scored.maxOfOrNull { (_, s) -> s } ?: return null
        val winners = scored.filter { (_, s) -> s == best }
        return winners.singleOrNull()?.first
    }

    companion object {
        /**
         * The floor for calling two elements the same thing.
         *
         * Set at 4 so it clears text (4), contentDescription (4) or viewId (8) but not
         * className + bounds (1 + 2 = 3). Identity comes from what an element says.
         */
        const val MIN_MATCH_SCORE = 4
    }
}

/**
 * One reading of the screen.
 *
 * [id] is monotonic per bridge instance and is what makes a stale reference
 * detectable: an action carries the snapshot it was chosen from, and acting on
 * anything but the current one is refused rather than guessed at.
 */
data class UiSnapshot(
    val id: Int,
    val packageName: String,
    val activityName: String,
    val screenWidth: Int,
    val screenHeight: Int,
    val elements: List<UiElement>,
    /** Elements dropped by the cap, so the model is told rather than misled. */
    val truncatedCount: Int,
    /** True when any password field was visible. Acting is refused while it is. */
    val hasPasswordField: Boolean,
)

/** What [UiSnapshot] to produce. */
data class SnapshotOptions(
    val mode: Mode = Mode.ACTIONABLE,
    /** Case-insensitive substring filter over label and role. */
    val filter: String = "",
    val maxElements: Int = DEFAULT_MAX_ELEMENTS,
) {
    enum class Mode {
        /** Only things that can be acted on, plus text that is not already on one. */
        ACTIONABLE,

        /** All readable text, no interaction detail. For "what does this say". */
        TEXT,

        /** Everything that survives the visibility filter. Expensive, rarely right. */
        FULL,
    }

    companion object {
        /**
         * 40 elements at ~80 chars is ~3.2k, inside the loop's 4k tool-result
         * budget with room for the header and the model's own reasoning.
         */
        const val DEFAULT_MAX_ELEMENTS = 40
        const val HARD_MAX_ELEMENTS = 100
    }
}
