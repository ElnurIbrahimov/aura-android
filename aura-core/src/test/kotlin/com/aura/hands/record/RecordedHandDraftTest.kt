package com.aura.hands.record

import com.aura.a11y.ElementSelector
import com.aura.a11y.Rect4
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The half of record mode a person supplies.
 *
 * Diffing two screens cannot always say which element was tapped, so most recordings arrive
 * with questions attached. This is where they get answered, and the answers are constrained:
 * a target can only be one of the candidates that were actually on screen when the step was
 * recorded, never an arbitrary selector typed in later.
 */
class RecordedHandDraftTest {

    private fun sel(text: String) =
        ElementSelector(null, text, null, "android.widget.Button", Rect4(0, 0, 100, 50))

    private val archive = sel("Archive")
    private val delete = sel("Delete")

    private fun ambiguousTap() = RecordedStep(
        kind = RecordedStep.Kind.TAP,
        selector = archive,
        label = "Archive",
        candidates = listOf(archive, delete),
    )

    private fun typed(text: String) = RecordedStep(
        kind = RecordedStep.Kind.TYPE, selector = sel("Message"), label = "Message", text = text,
    )

    @Test
    fun `choosing a target answers the question and leaves nothing outstanding`() {
        val draft = RecordedHandDraft(name = "Archive it", steps = listOf(ambiguousTap()))
        assertEquals(listOf(0), draft.unresolved)

        val answered = draft.resolve(0, delete)

        assertEquals(delete, answered.steps.single().selector)
        assertFalse(answered.steps.single().ambiguous, "an answered step must stop asking")
        assertTrue(answered.unresolved.isEmpty())
    }

    @Test
    fun `a target that was never on screen cannot be chosen`() {
        // The candidates are what the recording actually saw. Accepting anything else would
        // let a saved macro tap an element nobody demonstrated.
        val draft = RecordedHandDraft(steps = listOf(ambiguousTap()))

        val unchanged = draft.resolve(0, sel("Transfer funds"))

        assertEquals(draft, unchanged, "a selector outside the recorded candidates must be ignored")
    }

    @Test
    fun `a step can be dropped`() {
        val draft = RecordedHandDraft(steps = listOf(typed("hello"), ambiguousTap()))

        assertEquals(1, draft.remove(1).steps.size)
        assertEquals(RecordedStep.Kind.TYPE, draft.remove(1).steps.single().kind)
    }

    @Test
    fun `typed text becomes a variable, keeping what was recorded as its default`() {
        // Without this a recording replays one fixed message forever, which is a replay
        // rather than a macro.
        val draft = RecordedHandDraft(steps = listOf(typed("running late")))

        val parameterised = draft.makeVariable(0, "message")

        assertEquals("{{message}}", parameterised.steps.single().text)
        assertEquals(mapOf("message" to "running late"), parameterised.variables)
    }

    @Test
    fun `nothing can be saved while a question is outstanding`() {
        val draft = RecordedHandDraft(name = "Archive it", steps = listOf(ambiguousTap()))

        assertFalse(draft.canSave, "an unresolved step would compile with a guess baked in")
        assertTrue(draft.resolve(0, delete).canSave)
    }

    @Test
    fun `an empty or unnamed recording cannot be saved`() {
        assertFalse(RecordedHandDraft(name = "Nothing").canSave, "a hand with no steps does nothing")
        assertFalse(
            RecordedHandDraft(name = "  ", steps = listOf(typed("hi"))).canSave,
            "hands are run by name, so a blank one cannot be found again",
        )
    }
}
