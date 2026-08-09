package com.aura.creative.longform

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The parser is the only thing standing between a model's prose and a run that
 * spends twelve expensive calls, so its failure mode matters more than its happy
 * path: it must return nothing rather than something wrong, and the caller must
 * treat empty as "ask again".
 */
class OutlineParserTest {

    @Test
    fun `parses a full beat line`() {
        val beats = OutlineParser.parse(
            "BEAT 1 | The lighthouse goes dark | Mara wakes to silence | POV: Mara | SETTING: The lighthouse | TARGET: 1200",
        )
        assertEquals(1, beats.size)
        val beat = beats.single()
        assertEquals("The lighthouse goes dark", beat.title)
        assertEquals("Mara wakes to silence", beat.summary)
        assertEquals("Mara", beat.pov)
        assertEquals("The lighthouse", beat.setting)
        assertEquals(1_200, beat.targetWords)
        assertEquals("planned", beat.status)
    }

    @Test
    fun `everything after the summary is optional`() {
        val beats = OutlineParser.parse(
            """
            BEAT 1 | Arrival | She reaches the island
            BEAT 2 | The light fails
            """.trimIndent(),
        )
        assertEquals(2, beats.size)
        assertEquals("She reaches the island", beats[0].summary)
        assertEquals("The light fails", beats[1].title)
        assertEquals("", beats[1].summary)
        assertEquals(0, beats[1].targetWords)
    }

    /** Models wrap structured output in fences unprompted, hiding the first beat. */
    @Test
    fun `code fences are stripped`() {
        val beats = OutlineParser.parse(
            """
            ```
            BEAT 1 | Arrival | She reaches the island
            BEAT 2 | Departure | She leaves
            ```
            """.trimIndent(),
        )
        assertEquals(2, beats.size)
    }

    /** Preamble and trailing commentary are common and must not become beats. */
    @Test
    fun `prose around the beats is ignored`() {
        val beats = OutlineParser.parse(
            """
            Here is the outline you asked for:

            BEAT 1 | Arrival | She reaches the island
            BEAT 2 | Departure | She leaves

            Let me know if you'd like me to expand any of these.
            """.trimIndent(),
        )
        assertEquals(2, beats.size)
        assertEquals("Arrival", beats[0].title)
    }

    @Test
    fun `separator and numbering variations are tolerated`() {
        val beats = OutlineParser.parse(
            """
            BEAT 1: Arrival | She reaches the island
            beat 2 - Departure | She leaves
            BEAT | Nightfall | The beam dies
            """.trimIndent(),
        )
        assertEquals(3, beats.size)
        assertEquals("Arrival", beats[0].title)
        assertEquals("Departure", beats[1].title)
        assertEquals("Nightfall", beats[2].title)
    }

    @Test
    fun `labels are case-insensitive and order-independent`() {
        val beats = OutlineParser.parse("BEAT 1 | Arrival | Summary | target: 900 | pov: Mara")
        assertEquals("Mara", beats.single().pov)
        assertEquals(900, beats.single().targetWords)
    }

    @Test
    fun `a target with units still parses`() {
        assertEquals(1_500, OutlineParser.parse("BEAT 1 | A | B | TARGET: 1500 words").single().targetWords)
        assertEquals(0, OutlineParser.parse("BEAT 1 | A | B | TARGET: about a page").single().targetWords)
    }

    /** One beat must not be able to turn a run into something that never ends. */
    @Test
    fun `an absurd target is capped`() {
        assertTrue(OutlineParser.parse("BEAT 1 | A | B | TARGET: 100000").single().targetWords <= 5_000)
    }

    /**
     * Extra unlabelled fields are a model continuing its sentence past a pipe.
     * Joining them keeps the meaning; dropping them would silently lose half a
     * summary.
     */
    @Test
    fun `extra unlabelled fields join the summary`() {
        val beat = OutlineParser.parse("BEAT 1 | Arrival | She reaches the island | and finds it empty").single()
        assertEquals("She reaches the island and finds it empty", beat.summary)
    }

    @Test
    fun `a beat with no title produces nothing`() {
        assertTrue(OutlineParser.parse("BEAT 1 |  | just a summary").isEmpty())
        assertTrue(OutlineParser.parse("BEAT 1").isEmpty())
    }

    /**
     * The case the caller must handle as "ask again". Returning an empty list is
     * the whole contract — an outline of zero beats would otherwise start a run
     * that writes nothing and reports success.
     */
    @Test
    fun `pure prose yields no beats at all`() {
        val beats = OutlineParser.parse(
            """
            The story opens on a windswept island where Mara, a lighthouse keeper,
            wakes to find the beam extinguished. Over the following chapters she
            discovers the cause and must decide what to do about it.
            """.trimIndent(),
        )
        assertTrue(beats.isEmpty(), "prose must not be mistaken for an outline")
    }

    @Test
    fun `empty input yields no beats`() {
        assertTrue(OutlineParser.parse("").isEmpty())
        assertTrue(OutlineParser.parse("   \n  \n").isEmpty())
    }

    @Test
    fun `beats get distinct ids`() {
        val beats = OutlineParser.parse(
            """
            BEAT 1 | Arrival | x
            BEAT 2 | Departure | y
            """.trimIndent(),
        )
        assertEquals(2, beats.map { it.id }.distinct().size)
    }

    @Test
    fun `the retry threshold is what the caller checks against`() {
        val beats = OutlineParser.parse("BEAT 1 | Only one | x")
        assertTrue(beats.size < OutlineParser.MIN_BEATS, "one beat is not an outline worth drafting")
    }
}
