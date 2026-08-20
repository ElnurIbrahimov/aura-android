package com.aura.hands.record

import com.aura.a11y.UiElement
import com.aura.a11y.UiSnapshot
import kotlin.math.abs

/**
 * Infers what the user did, from what the screen looked like before and after.
 *
 * Pure JVM on purpose, like the rest of `com.aura.a11y`: [UiSnapshot] is already a
 * device-free description of a screen, so every rule here is testable with no emulator.
 *
 * The rules are ordered most-certain first, and each one either identifies a target or
 * declines. Nothing here falls back to a best guess: a wrong inference does not produce a
 * slightly-off macro, it produces one that taps an arbitrary control in whatever app the
 * user pointed it at.
 */
object StepInference {

    /** The action that best explains the change from [before] to [after], or null. */
    fun infer(before: UiSnapshot, after: UiSnapshot): RecordedStep? {
        // A macro that replays typing would replay the password with it, and a recorded
        // step is stored in plain text in the hands table. Refuse before reading anything.
        if (before.hasPasswordField || after.hasPasswordField) return null

        typing(before, after)?.let { return it }

        if (before.elements.map { it.selector } == after.elements.map { it.selector }) return null

        scrolling(before, after)?.let { return it }

        return tapping(before, after)
    }

    /** Text arriving in a field that was already there. */
    private fun typing(before: UiSnapshot, after: UiSnapshot): RecordedStep? {
        for (was in before.elements.filter { it.editable }) {
            val now = after.elements.firstOrNull { it.editable && sameField(was, it) } ?: continue
            val typed = now.selector.text.orEmpty()
            if (typed.isNotEmpty() && typed != was.selector.text.orEmpty()) {
                return RecordedStep(
                    kind = RecordedStep.Kind.TYPE,
                    selector = now.selector,
                    label = now.label,
                    text = typed,
                )
            }
        }
        return null
    }

    /**
     * The same elements, moved together.
     *
     * Requires at least two survivors moving by an identical offset. One element moving is
     * a layout change; two moving in lockstep is the list underneath them scrolling.
     */
    private fun scrolling(before: UiSnapshot, after: UiSnapshot): RecordedStep? {
        val was = before.elements.associateBy(::identity)
        val now = after.elements.associateBy(::identity)
        val survivors = was.keys intersect now.keys
        if (survivors.size < 2) return null

        val offsets = survivors.map { id ->
            val b = was.getValue(id).bounds
            val a = now.getValue(id).bounds
            (a.left - b.left) to (a.top - b.top)
        }
        val first = offsets.first()
        if (offsets.any { it != first }) return null
        val (dx, dy) = first
        if (dx == 0 && dy == 0) return null

        // Content moving up the screen means the user scrolled down through it.
        val direction = if (abs(dy) >= abs(dx)) {
            if (dy < 0) RecordedStep.Direction.DOWN else RecordedStep.Direction.UP
        } else {
            if (dx < 0) RecordedStep.Direction.RIGHT else RecordedStep.Direction.LEFT
        }
        return RecordedStep(kind = RecordedStep.Kind.SCROLL, selector = null, direction = direction)
    }

    /**
     * Something was pressed, and the question is what.
     *
     * A tap is attributed only when exactly one clickable element left the screen. Anything
     * else lists its candidates and lets the review screen ask — see [RecordedStep.candidates].
     */
    private fun tapping(before: UiSnapshot, after: UiSnapshot): RecordedStep? {
        val present = after.elements.map { it.selector }.toSet()
        val gone = before.elements.filter { it.clickable && it.selector !in present }

        // Exactly one clickable left the screen: that is as close to proof as a diff gets.
        if (gone.size == 1) {
            val it = gone.single()
            return RecordedStep(RecordedStep.Kind.TAP, it.selector, it.label)
        }

        // Otherwise the screen changed and the diff cannot say what caused it — tapping
        // "Send" usually leaves "Send" right where it was. Narrow to the clickables that
        // vanished if any did, else offer everything that could have been pressed. Dropping
        // the step instead would lose an action from the middle of a demonstration, and the
        // replay would then do something the user never showed it.
        val candidates = gone.ifEmpty { before.elements.filter { it.clickable } }
        if (candidates.isEmpty()) return null

        val best = candidates.first()
        return RecordedStep(
            kind = RecordedStep.Kind.TAP,
            selector = best.selector,
            label = best.label,
            candidates = candidates.map { it.selector },
        )
    }

    /** Same field across two readings: its id if it has one, else where it sits. */
    private fun sameField(a: UiElement, b: UiElement): Boolean =
        if (a.selector.viewId != null || b.selector.viewId != null) {
            a.selector.viewId == b.selector.viewId
        } else {
            a.bounds == b.bounds
        }

    /** Identity that survives scrolling: bounds move, text and id do not. */
    private fun identity(e: UiElement): String =
        e.selector.viewId ?: "${e.selector.className}|${e.selector.text}|${e.selector.contentDescription}"
}
