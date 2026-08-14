package com.aura.creative

import io.mockk.mockk
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The fallback half of "structured output with a prose fallback".
 *
 * `ChatOptions.responseSchema` is requested and most providers honour it — but
 * `custom` points at a user-supplied URL and `moa` fans out to whatever the
 * aggregator is, and the schema's own KDoc says callers must keep a lenient
 * parse either way. So this runs for exactly the models least likely to comply,
 * which is why it is forgiving about separators and labels rather than strict.
 *
 * Being strict here would mean returning *no scores at all* for those providers,
 * which is worse than a slightly wrong label.
 */
class TensionAnalyzerParseTest {

    private val analyzer = TensionAnalyzer(mockk(), mockk(), mockk(), null)

    @Test
    fun `the format the prompt asks for`() {
        val report = analyzer.parseProse(
            """
            SCENE 1: 4/10 — quiet opening, establishes the debt
            SCENE 2: 7/10 — the collector arrives
            SCENE 3: 3/10 — travel, nothing at stake

            PACING DIAGNOSIS:
            The middle sags badly between the arrival and the confrontation.

            RECOMMENDATIONS:
            1. Cut scene 3 or give it a reversal.
            2. Move the confrontation earlier.
            """.trimIndent(),
        )

        assertEquals(3, report.scenes.size)
        assertEquals("SCENE 2", report.scenes[1].label.uppercase())
        assertEquals(7, report.scenes[1].tension)
        assertEquals("the collector arrives", report.scenes[1].note)
        assertTrue(report.diagnosis.startsWith("The middle sags"))
        assertEquals(2, report.recommendations.size)
        assertEquals("Move the confrontation earlier.", report.recommendations[1])
    }

    /**
     * What models actually send: en-dashes, plain hyphens, "Chapter" instead of
     * "Scene", inconsistent spacing around the slash.
     */
    @Test
    fun `the variants models really produce`() {
        val report = analyzer.parseProse(
            """
            Scene 1 - 5 / 10 – hyphen separator, spaced slash
            Chapter 2: 8/10 — labelled as a chapter
            BEAT 3 : 2/10
            """.trimIndent(),
        )

        assertEquals(3, report.scenes.size)
        assertEquals(listOf(5, 8, 2), report.scenes.map { it.tension })
        assertEquals("", report.scenes[2].note, "a scored line with no note is not a parse failure")
    }

    @Test
    fun `prose with no scores yields an empty report rather than junk`() {
        val report = analyzer.parseProse("I read the manuscript and it was broadly fine, 8 out of 10 overall.")

        assertTrue(report.scenes.isEmpty(), "a stray '8 out of 10' was mistaken for a scene score")
        assertEquals(0f, report.meanTension)
    }

    @Test
    fun `an out-of-range score is clamped, not trusted`() {
        val report = analyzer.parseProse("SCENE 1: 47/10 — enthusiastic model")

        assertEquals(10, report.scenes.single().tension)
    }

    @Test
    fun `the mean and the flat list are what the diff will compare on`() {
        val report = analyzer.parseProse(
            """
            SCENE 1: 2/10 — flat
            SCENE 2: 8/10 — peak
            SCENE 3: 2/10 — flat again
            """.trimIndent(),
        )

        assertEquals(4f, report.meanTension)
        assertEquals(listOf("SCENE 1", "SCENE 3"), report.flat().map { it.label.uppercase() })
    }

    @Test
    fun `rendering a report reads as the report it came from`() {
        val report = TensionReport(
            scenes = listOf(SceneScore("Scene 1", 4, "opening")),
            diagnosis = "Sags in the middle.",
            recommendations = listOf("Cut scene 3."),
        )

        val text = analyzer.render(report)

        assertTrue("Scene 1: 4/10 — opening" in text)
        assertTrue("PACING DIAGNOSIS" in text)
        assertTrue("1. Cut scene 3." in text)
    }
}
