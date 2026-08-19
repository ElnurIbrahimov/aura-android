package com.aura.hands.record

import com.aura.a11y.ElementSelector
import com.aura.hands.HandStep
import com.aura.hands.toJsonString

/**
 * Turns a reviewed demonstration into the steps a Hand runs.
 *
 * Emits `screen_act` calls, so nothing downstream needs to know a Hand was recorded: the
 * repository parses it, the worker runs it, and backup carries it, all unchanged. The one
 * thing that matters is that the target travels as a `selector` rather than an element
 * index — see `ScreenActTool`, where a recorded index would refer to a snapshot that
 * stopped existing the moment the screen changed.
 */
object RecordedHandCompiler {

    sealed class Result {
        data class Compiled(val steps: List<HandStep>) : Result()

        /** Steps whose target was never resolved, by position in the recording. */
        data class Unresolved(val positions: List<Int>) : Result()
    }

    fun compile(recorded: List<RecordedStep>): Result {
        // An ambiguous step still carries the first candidate as its selector, so compiling
        // one would quietly bake a guess into a saved macro — the exact outcome the review
        // screen exists to prevent. Refuse, and name the positions still to answer.
        val unresolved = recorded.withIndex().filter { it.value.ambiguous }.map { it.index }
        if (unresolved.isNotEmpty()) return Result.Unresolved(unresolved)

        return Result.Compiled(recorded.map(::step))
    }

    private fun step(recorded: RecordedStep): HandStep {
        val args = mutableMapOf<String, String>()
        args["action"] = when (recorded.kind) {
            RecordedStep.Kind.TAP -> "tap"
            RecordedStep.Kind.TYPE -> "type"
            RecordedStep.Kind.SCROLL -> "scroll"
            RecordedStep.Kind.BACK -> "back"
        }
        recorded.selector?.let { args["selector"] = selectorJson(it) }
        recorded.text?.let { args["text"] = it }
        recorded.direction?.let { args["direction"] = it.name.lowercase() }
        return HandStep(tool = "screen_act", args = args)
    }

    /**
     * The target, in the shape `ScreenActTool` reads back.
     *
     * Bounds are deliberately absent. They will have moved by the time this runs, which is
     * the whole reason a selector exists, and carrying them would score a match for whatever
     * element happens to occupy the old position.
     */
    private fun selectorJson(selector: ElementSelector): String = buildMap {
        selector.viewId?.let { put("viewId", it) }
        selector.text?.let { put("text", it) }
        selector.contentDescription?.let { put("contentDescription", it) }
        selector.className?.let { put("className", it) }
    }.toJsonString()
}
