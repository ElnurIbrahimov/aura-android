package com.aura.memory.eval

import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The loader for the Gate B experiment.
 *
 * Tested against inline fixtures rather than a committed vector file, so these
 * assertions hold whether or not anyone has run the Python generator. What is
 * under test is the loader's refusal to paper over a bad file — every failure
 * mode here produces a plausible-looking scorecard if it is handled quietly.
 */
class PrecomputedEmbedderTest {

    private fun row(text: String, vararg v: Float) =
        """{"text":"$text","vector":[${v.joinToString(",")}]}"""

    @Test
    fun `vectors are normalized on load, not trusted`() {
        // MemoryStore's similarity is a bare dot product that assumes unit
        // vectors on both sides. An unnormalized file does not error there — it
        // ranks by magnitude and returns a confident wrong answer.
        val e = PrecomputedEmbedder.parse(row("hello", 3f, 4f), "test")
        val v = runBlocking { e.embed("hello") }
        assertEquals(0.6f, v[0], 1e-6f)
        assertEquals(0.8f, v[1], 1e-6f)
    }

    @Test
    fun `a missing text fails loudly rather than falling back`() {
        // The single most important behaviour in the file. A hash fallback or a
        // zero vector on a miss would score the FIXTURE and report it as the
        // model's number, which is worse than not running the experiment.
        val e = PrecomputedEmbedder.parse(row("hello", 1f, 0f), "test")
        val err = assertFailsWith<IllegalStateException> { runBlocking { e.embed("not in the file") } }
        assertTrue("regenerate" in err.message.orEmpty(), err.message.orEmpty())
    }

    @Test
    fun `mixed dimensions are rejected`() {
        // Two models' output appended into one file. Room would store both
        // happily, and the cosine between a 384 and a 768 vector is meaningless
        // in a way that produces numbers rather than errors.
        val text = row("a", 1f, 0f) + "\n" + row("b", 1f, 0f, 0f)
        val err = assertFailsWith<IllegalArgumentException> { PrecomputedEmbedder.parse(text, "test") }
        assertTrue("mixes dimensions" in err.message.orEmpty(), err.message.orEmpty())
    }

    @Test
    fun `duplicate keys are rejected rather than silently last-wins`() {
        val text = row("a", 1f, 0f) + "\n" + row("a", 0f, 1f)
        assertFailsWith<IllegalArgumentException> { PrecomputedEmbedder.parse(text, "test") }
    }

    @Test
    fun `an empty file is rejected`() {
        assertFailsWith<IllegalArgumentException> { PrecomputedEmbedder.parse("\n// comment\n", "test") }
    }

    @Test
    fun `lookup tolerates surrounding whitespace on both sides`() {
        val e = PrecomputedEmbedder.parse("""{"text":"  padded  ","vector":[1,0]}""", "test")
        assertEquals(1f, runBlocking { e.embed("padded\n") }[0], 1e-6f)
    }

    @Test
    fun `dimension and model id come from the file, not the caller's hope`() {
        val e = PrecomputedEmbedder.parse(row("a", 1f, 0f, 0f, 0f), "gte-small")
        assertEquals(4, e.dimension())
        assertEquals("gte-small", e.modelId())
        assertTrue(e.isCurrent("gte-small"))
        assertTrue(!e.isCurrent("local-hash-v2"))
    }

    @Test
    fun `embed calls are counted so the harness can report them`() {
        val e = PrecomputedEmbedder.parse(row("a", 1f, 0f), "test")
        runBlocking { e.embed("a"); e.embed("a") }
        assertEquals(2, e.callCount.get())
    }

    @Test
    fun `absent vector files report as absent rather than throwing`() {
        // CI has neither Python nor the real corpus, so this is the normal case
        // there. It must be distinguishable from "ran and found nothing", which
        // is why the caller reports it into the scorecard.
        assertEquals(null, PrecomputedEmbedder.loadOrNull("definitely-not-generated"))
        assertEquals(emptyList(), PrecomputedEmbedder.available(listOf("definitely-not-generated")))
    }
}
