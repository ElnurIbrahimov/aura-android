package com.aura.world

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class BeliefArbiterTest {

    private val now = 1_000_000L
    private val day = 86_400_000L

    private fun belief(id: String, confidence: Float = 0.8f) =
        BeliefEntity(id = id, subject = "user", predicate = "USES", valueJson = "\"$id\"", confidence = confidence)

    // detailJson MUST differ per row: corroboration counts distinct turns via
    // distinct() on detailJson, so identical values collapse to a count of 1
    // and the corroboration test cannot discriminate. In production
    // BeliefPromoter writes a distinct sourceTurnId per turn.
    private fun evidence(beliefId: String, ageDays: Long, source: String = "user_statement", n: Int = 1) =
        (1..n).map {
            EvidenceEntity(
                id = "$beliefId-$it",
                beliefId = beliefId,
                source = source,
                summary = "s",
                detailJson = """{"turn":"$beliefId-$it"}""",
                timestamp = now - ageDays * day - it,
            )
        }

    @Test
    fun `recent evidence beats stale evidence`() {
        val fresh = BeliefSide(belief("fresh"), evidence("fresh", ageDays = 1))
        val stale = BeliefSide(belief("stale"), evidence("stale", ageDays = 200))

        val verdict = BeliefArbiter.arbitrate(fresh, stale, now)

        assertIs<Verdict.Winner>(verdict)
        assertEquals("fresh", verdict.winning.id)
        assertEquals("stale", verdict.losing.id)
    }

    @Test
    fun `corroboration beats a single remark of the same age`() {
        val many = BeliefSide(belief("many"), evidence("many", ageDays = 5, n = 4))
        val once = BeliefSide(belief("once"), evidence("once", ageDays = 5, n = 1))

        val verdict = BeliefArbiter.arbitrate(many, once, now)

        assertIs<Verdict.Winner>(verdict)
        assertEquals("many", verdict.winning.id)
    }

    @Test
    fun `a direct user statement outranks a derived inference`() {
        val stated = BeliefSide(belief("stated"), evidence("stated", ageDays = 5, source = "user_statement"))
        val derived = BeliefSide(belief("derived"), evidence("derived", ageDays = 5, source = "derived"))

        val verdict = BeliefArbiter.arbitrate(stated, derived, now)

        assertIs<Verdict.Winner>(verdict)
        assertEquals("stated", verdict.winning.id)
    }

    @Test
    fun `identical sides are too close to call`() {
        // The safety property: refusing to decide is a valid outcome. A tie
        // must not silently overwrite an established belief.
        val a = BeliefSide(belief("a"), evidence("a", ageDays = 5))
        val b = BeliefSide(belief("b"), evidence("b", ageDays = 5))

        assertIs<Verdict.TooClose>(BeliefArbiter.arbitrate(a, b, now))
    }

    @Test
    fun `a side with no evidence never wins`() {
        val withEvidence = BeliefSide(belief("has"), evidence("has", ageDays = 90))
        val without = BeliefSide(belief("none"), emptyList())

        val verdict = BeliefArbiter.arbitrate(without, withEvidence, now)

        assertIs<Verdict.Winner>(verdict)
        assertEquals("has", verdict.winning.id)
    }
}
