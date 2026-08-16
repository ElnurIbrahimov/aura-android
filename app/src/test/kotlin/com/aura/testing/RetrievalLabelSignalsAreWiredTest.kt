package com.aura.testing

import org.junit.Test
import kotlin.test.assertTrue

/**
 * The three turn-level signals only exist if `ChatViewModel` emits them.
 *
 * Thumbs, regenerate and edit all stopped in `:app` and recorded nothing. Each
 * is now a call in a coroutine launched from a UI callback, which is the shape
 * a behavioural test cannot reach and the shape this repo has repeatedly shipped
 * missing — `WorkerRunRecorder.prune()` with a unit test and no caller, the
 * background-model seed this file's sibling covers.
 *
 * Each assertion is scoped to its own function rather than to the file. A call
 * that is merely *present* proves nothing: the whole failure mode is a signal
 * wired on one path and not its neighbour, which is exactly what
 * `evolutionHooks.onMemoryRecalled` did on `MemoryStore`'s two branches.
 */
class RetrievalLabelSignalsAreWiredTest {

    private val source: String by lazy {
        sourceDir("src/main/kotlin/com/aura")
            .resolve("ui/viewmodel/ChatViewModel.kt")
            .also { check(it.isFile) { "ChatViewModel.kt not found at ${it.absolutePath}" } }
            .readText()
    }

    private fun functionBody(signature: String): String =
        source.substringAfter(signature, "")
            .also { check(it.isNotBlank()) { "$signature not found — this test reads the wrong shape" } }
            .substringBefore("\n    fun ")

    @Test
    fun `a thumbs reaction reaches the label store`() {
        assertTrue(
            "recordTurnSignal(" in functionBody("fun reactToTurn("),
            "thumbs no longer record a relevance signal. The reaction still reaches TasteEngine, so " +
                "nothing looks broken — the labels simply never learn the answer was rated.",
        )
    }

    /**
     * The ordering matters, not just the presence.
     * `ChatRetryPolicy.prepareConversationForRetry` nulls `recall` as its first
     * act, so the turn has to be captured before that call or the signal is
     * structurally unavailable.
     */
    @Test
    fun `regenerate is captured before the retry policy discards the recall`() {
        val body = functionBody("fun retryLast(")

        assertTrue("recordTurnSignal(" in body, "regenerating no longer records anything")

        val capture = body.indexOf("turns.lastOrNull()?.timestamp")
        val prepare = body.indexOf("prepareConversationForRetry(")
        assertTrue(capture > 0 && prepare > 0, "this test is reading the wrong shape")
        assertTrue(
            capture < prepare,
            "the turn is captured after prepareConversationForRetry, which nulls `recall` first — " +
                "so the regenerate signal now names whatever turn happens to be last afterwards",
        )
    }

    @Test
    fun `an edit marks the turn instead of grading it`() {
        val body = functionBody("fun editAndResend(")

        assertTrue(
            "markSupersededByEdit(" in body,
            "editing a question no longer marks its labels, so they will be exported as though the " +
                "question stood",
        )
        assertTrue(
            "recordTurnSignal(" !in body,
            "an edit is being recorded as a relevance verdict. It says the question was wrong, not " +
                "the memories — grading it teaches the eval the opposite of what happened.",
        )
    }
}
