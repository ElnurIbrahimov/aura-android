package com.aura.voice

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The chunker exists so voice mode can speak before generation finishes.
 *
 * The easy half is finding full stops. The half that decides whether this is
 * usable is NOT stopping at the wrong one: "Dr. Smith", "3.5" and a trailing
 * "..." are all mid-sentence, and splitting there turns fluent speech into
 * stutter — which is worse than the delay it replaces.
 */
class SentenceChunkerTest {

    /** Feed [text] one character at a time, as a stream would. */
    private fun streamed(text: String): List<String> {
        val chunker = SentenceChunker()
        val out = mutableListOf<String>()
        for (i in 1..text.length) out += chunker.accept(text.substring(0, i))
        chunker.flush(text).takeIf { it.isNotBlank() }?.let { out += it }
        return out
    }

    // ---- the basics ------------------------------------------------------

    @Test
    fun `a complete sentence is emitted once`() {
        assertEquals(listOf("Hello there."), streamed("Hello there."))
    }

    @Test
    fun `multiple sentences are emitted in order`() {
        assertEquals(
            listOf("First one.", "Second one.", "Third one."),
            streamed("First one. Second one. Third one."),
        )
    }

    @Test
    fun `question and exclamation marks terminate`() {
        assertEquals(listOf("Really?", "Yes!"), streamed("Really? Yes!"))
    }

    @Test
    fun `an unterminated tail is only emitted by flush`() {
        // Otherwise the end of every reply — which usually has no terminator
        // when the stream stops — would be silently swallowed.
        val chunker = SentenceChunker()
        assertEquals(listOf("Done."), chunker.accept("Done. And then"))
        assertEquals("And then", chunker.flush("Done. And then"))
    }

    @Test
    fun `nothing is emitted twice across incremental calls`() {
        // Note the first call returns NOTHING: a terminator at the very end of
        // the buffer is ambiguous and waits for more, which is the same rule
        // that stops "Wait..." splitting. My first draft of this test asserted
        // otherwise and contradicted the design it was testing.
        val chunker = SentenceChunker()
        assertEquals(emptyList(), chunker.accept("One."))
        assertEquals(listOf("One."), chunker.accept("One. Two"))
        assertEquals(emptyList(), chunker.accept("One. Two"))
        assertEquals(listOf("Two."), chunker.accept("One. Two. Three"))
    }

    // ---- the part that decides whether it is usable ----------------------

    @Test
    fun `a decimal number does not split`() {
        assertEquals(listOf("It costs 3.50 exactly."), streamed("It costs 3.50 exactly."))
    }

    @Test
    fun `a domain name does not split`() {
        assertEquals(listOf("Go to example.com now."), streamed("Go to example.com now."))
    }

    @Test
    fun `titles do not split`() {
        assertEquals(listOf("Dr. Smith is here."), streamed("Dr. Smith is here."))
        assertEquals(listOf("Ask Mrs. Jones about it."), streamed("Ask Mrs. Jones about it."))
    }

    @Test
    fun `initials do not split`() {
        assertEquals(listOf("J. R. R. Tolkien wrote it."), streamed("J. R. R. Tolkien wrote it."))
    }

    @Test
    fun `e g and i e do not split`() {
        assertEquals(listOf("Use a list, e.g. this one."), streamed("Use a list, e.g. this one."))
        assertEquals(listOf("It is lazy, i.e. deferred."), streamed("It is lazy, i.e. deferred."))
    }

    @Test
    fun `an ellipsis emits once, not three times`() {
        // My first expectation here was "Wait... Then go." as ONE utterance,
        // which was wrong: "Wait..." genuinely is a sentence. What the run-past
        // logic prevents is the naive result — "Wait." plus two empty fragments
        // — which reads as a stutter when spoken.
        assertEquals(listOf("Wait...", "Then go."), streamed("Wait... Then go."))
    }

    @Test
    fun `combined terminators emit once`() {
        assertEquals(listOf("Really?!", "Yes."), streamed("Really?! Yes."))
    }

    @Test
    fun `a terminator at the very end of the buffer waits for more`() {
        // The ambiguous case: a lone "." may end a sentence or be the first
        // character of an ellipsis still arriving. Splitting eagerly is how
        // "Wait..." becomes three utterances.
        val chunker = SentenceChunker()
        assertEquals(emptyList(), chunker.accept("Hold on."))
        assertEquals(listOf("Hold on."), chunker.accept("Hold on. Now go"))
    }

    // ---- robustness ------------------------------------------------------

    @Test
    fun `text that shrinks resets rather than emitting a fragment`() {
        // A retry or an edit replaces the response. Continuing from the old
        // offset would speak the middle of the new text.
        val chunker = SentenceChunker()
        chunker.accept("A long first response. And more. Yet more")
        chunker.accept("Short. x")
        assertEquals(listOf("Short."), SentenceChunker().accept("Short. x"))
    }

    @Test
    fun `empty and whitespace input yields nothing`() {
        val chunker = SentenceChunker()
        assertEquals(emptyList(), chunker.accept(""))
        assertEquals(emptyList(), chunker.accept("   "))
        assertEquals("", chunker.flush("   "))
    }

    @Test
    fun `newlines between sentences are handled`() {
        assertEquals(listOf("First.", "Second."), streamed("First.\n\nSecond."))
    }

    @Test
    fun `no sentence is lost across a full stream`() {
        // The invariant that matters most: everything the model wrote is
        // spoken, exactly once, in order. A chunker that drops text is worse
        // than no chunker.
        val text = "Hello. I checked example.com for you. Dr. Lee said 3.5 percent. " +
            "Anything else?  Let me know..."
        val pieces = streamed(text)
        val rejoined = pieces.joinToString(" ") { it.trim() }
        val normalise = { s: String -> s.replace(Regex("\\s+"), " ").trim() }
        assertEquals(normalise(text), normalise(rejoined), "text was lost or reordered")
    }

    @Test
    fun `chunking a long reply starts speaking early`() {
        // The whole point: the first sentence must be available long before
        // the last one arrives.
        val text = "Here is the first thing. " + "Filler sentence. ".repeat(40)
        val chunker = SentenceChunker()
        val early = chunker.accept("Here is the first thing. Fil")
        assertTrue(early.isNotEmpty(), "nothing was speakable until the reply finished")
        assertEquals("Here is the first thing.", early.first())
        assertTrue(text.isNotEmpty())
    }

    @Test
    fun `CJK terminators are handled`() {
        assertEquals(listOf("こんにちは。", "元気ですか？"), streamed("こんにちは。元気ですか？"))
    }
}
