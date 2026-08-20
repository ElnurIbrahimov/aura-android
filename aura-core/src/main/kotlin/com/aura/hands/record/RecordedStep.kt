package com.aura.hands.record

import com.aura.a11y.ElementSelector

/**
 * One action inferred from a demonstration, before it becomes a [com.aura.hands.HandStep].
 *
 * Carries a [selector] rather than an element index. `screen_act`'s `element` parameter is
 * a position in one `screen_read` snapshot and stale snapshots are refused, so an index
 * recorded today means nothing tomorrow — a recorded step has to say *what* it acted on,
 * not *where it was in a list*.
 *
 * [candidates] is the honest part. Diffing two screens cannot always say which element was
 * tapped: tapping "Send" often leaves "Send" on screen, and several elements can change at
 * once. When attribution is uncertain the step carries every plausible target instead of
 * picking one, and the review screen asks. Guessing here would mean tapping an arbitrary
 * control in whatever app is open, which is the one failure this feature cannot have.
 */
data class RecordedStep(
    val kind: Kind,
    /** What to act on. Null only for [Kind.BACK], which targets no element. */
    val selector: ElementSelector?,
    /** Human-readable target, for the review screen. */
    val label: String = "",
    /** Text that was typed. [Kind.TYPE] only. */
    val text: String? = null,
    /** [Kind.SCROLL] only. */
    val direction: Direction? = null,
    /**
     * Other elements that could equally have been the target. Empty when attribution was
     * unambiguous. Non-empty means the review screen must ask before this can be saved.
     */
    val candidates: List<ElementSelector> = emptyList(),
) {
    enum class Kind { TAP, TYPE, SCROLL, BACK }

    enum class Direction { UP, DOWN, LEFT, RIGHT }

    val ambiguous: Boolean get() = candidates.isNotEmpty()
}
