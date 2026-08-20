package com.aura.curiosity

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Which gap is worth the one question Aura gets to ask.
 *
 * `CuriosityStore.scanAndAuthor` refuses to author while any question is open, so exactly one
 * exists at a time and the scan-time ranking is the entire decision. What it ranked by until
 * now was `kindBase × detectorConfidence` — how sure the detector was that it had found
 * something, which is a different question from whether the something matters.
 *
 * Pure, and it takes looked-up values rather than DAOs, so none of this needs a mock, an
 * emulator or a key.
 */
class ValueOfInformationTest {

    private val now = 1_700_000_000_000L
    private fun daysAgo(d: Long) = now - d * 24 * 60 * 60 * 1000

    @Test
    fun `a subject more of the model touches scores higher`() {
        val quiet = ValueOfInformation.score(0.7f, ValueOfInformation.Signals(reach = 0, lastTouchedAt = now), now)
        val busy = ValueOfInformation.score(0.7f, ValueOfInformation.Signals(reach = 20, lastTouchedAt = now), now)

        assertTrue(busy > quiet, "reach must raise the score: $busy vs $quiet")
    }

    @Test
    fun `a subject nobody has touched in months scores lower than a fresh one`() {
        val fresh = ValueOfInformation.score(0.7f, ValueOfInformation.Signals(reach = 5, lastTouchedAt = now), now)
        val stale = ValueOfInformation.score(0.7f, ValueOfInformation.Signals(reach = 5, lastTouchedAt = daysAgo(120)), now)

        assertTrue(stale < fresh, "age must lower the score: $stale vs $fresh")
    }

    @Test
    fun `the per-kind judgement still decides between otherwise equal subjects`() {
        // CONTRADICTION_BASE is 1.0 and STALE_BASE is 0.5 because a wrong belief corrupts
        // every future recall while a stale memory only limits one. That judgement is kept.
        val signals = ValueOfInformation.Signals(reach = 3, lastTouchedAt = now)

        assertTrue(
            ValueOfInformation.score(1.0f, signals, now) > ValueOfInformation.score(0.5f, signals, now),
            "kind priority must still separate equal subjects",
        )
    }

    @Test
    fun `a well-connected lesser kind can outrank a confident but isolated one`() {
        // The case the whole feature exists for. A contradiction the detector is sure about,
        // concerning something mentioned once, against a gap in the entity half the graph
        // points at.
        val isolatedContradiction = ValueOfInformation.score(
            1.0f, ValueOfInformation.Signals(reach = 0, lastTouchedAt = daysAgo(200)), now,
        )
        val connectedGap = ValueOfInformation.score(
            0.7f, ValueOfInformation.Signals(reach = 40, lastTouchedAt = now), now,
        )

        assertTrue(
            connectedGap > isolatedContradiction,
            "a live, well-connected gap must be able to beat a confident dead one: " +
                "$connectedGap vs $isolatedContradiction",
        )
    }

    @Test
    fun `with nothing to tell subjects apart the order is exactly what it was before`() {
        // The degradation pin. Empty graph, no access history, everything touched at once:
        // the score must reduce to priority, so ranking collapses to today's behaviour
        // rather than to noise.
        val blank = ValueOfInformation.Signals(reach = 0, lastTouchedAt = now)
        val priorities = listOf(0.5f, 1.0f, 0.7f, 0.6f)

        val ranked = priorities.sortedByDescending { ValueOfInformation.score(it, blank, now) }

        assertEquals(priorities.sortedByDescending { it }, ranked, "ordering must match raw priority")
    }

    @Test
    fun `reach saturates, so one enormous hub cannot swamp the ranking`() {
        val big = ValueOfInformation.score(0.5f, ValueOfInformation.Signals(reach = 100, lastTouchedAt = now), now)
        val absurd = ValueOfInformation.score(0.5f, ValueOfInformation.Signals(reach = 100_000, lastTouchedAt = now), now)

        assertTrue(absurd - big < 0.05, "reach must saturate, got $big then $absurd")
        assertTrue(
            ValueOfInformation.score(1.0f, ValueOfInformation.Signals(reach = 0, lastTouchedAt = now), now) > big,
            "a top-priority isolated subject must still beat a hub two kinds below it",
        )
    }
}
