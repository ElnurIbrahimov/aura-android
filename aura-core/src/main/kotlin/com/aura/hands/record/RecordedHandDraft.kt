package com.aura.hands.record

import com.aura.a11y.ElementSelector

/**
 * A recording being reviewed: what was inferred, plus the answers only a person can give.
 *
 * Diffing two screens cannot always say which element was tapped, so most recordings arrive
 * with questions attached. This is where they are answered, and the answering is deliberately
 * constrained — a target can only be one of the candidates that were on screen when the step
 * was recorded, never a selector supplied afterwards. Otherwise a saved macro could tap
 * something nobody ever demonstrated.
 *
 * Pure, and in `aura-core` rather than the screen that drives it, for the reason `NodeLike`
 * gives one package over: the parts with real logic belong where CI can run them without a
 * device.
 */
data class RecordedHandDraft(
    val name: String = "",
    val steps: List<RecordedStep> = emptyList(),
    /** Variable name to the text it was recorded with. */
    val variables: Map<String, String> = emptyMap(),
) {
    /** Positions still waiting for the user to say which element was meant. */
    val unresolved: List<Int>
        get() = steps.withIndex().filter { it.value.ambiguous }.map { it.index }

    /**
     * Hands are found and run by name, and a hand with no steps does nothing — so both are
     * required alongside every question being answered.
     */
    val canSave: Boolean
        get() = name.isNotBlank() && steps.isNotEmpty() && unresolved.isEmpty()

    fun resolve(index: Int, choice: ElementSelector): RecordedHandDraft {
        val step = steps.getOrNull(index) ?: return this
        // Only among what the recording saw.
        if (choice !in step.candidates) return this
        return copy(
            steps = steps.toMutableList().also {
                it[index] = step.copy(selector = choice, candidates = emptyList())
            },
        )
    }

    fun remove(index: Int): RecordedHandDraft {
        if (index !in steps.indices) return this
        return copy(steps = steps.filterIndexed { i, _ -> i != index })
    }

    /**
     * Replace a step's typed text with `{{variable}}`, keeping the recorded text as the
     * default. Without this a recording replays one fixed message forever, which is a replay
     * rather than a macro.
     */
    fun makeVariable(index: Int, variable: String): RecordedHandDraft {
        val step = steps.getOrNull(index) ?: return this
        val recorded = step.text ?: return this
        val key = variable.trim()
        if (key.isEmpty()) return this
        return copy(
            steps = steps.toMutableList().also { it[index] = step.copy(text = "{{$key}}") },
            variables = variables + (key to recorded),
        )
    }
}
