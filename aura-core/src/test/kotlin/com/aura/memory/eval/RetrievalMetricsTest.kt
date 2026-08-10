package com.aura.memory.eval

import org.junit.Test
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The metrics have to be right or every number downstream is noise dressed as
 * evidence. Hand-computed expectations throughout — a metric test that derives
 * its expectation from the implementation proves only that the code runs.
 */
class RetrievalMetricsTest {

    private fun close(expected: Double, actual: Double, eps: Double = 1e-9) =
        assertTrue(abs(expected - actual) < eps, "expected $expected, got $actual")

    // ---- nDCG ------------------------------------------------------------

    @Test
    fun `perfect ranking scores one`() {
        val j = mapOf("a" to 3, "b" to 2, "c" to 1)
        close(1.0, RetrievalMetrics.nDCG(listOf("a", "b", "c"), j, 10))
    }

    @Test
    fun `reversed ranking scores below one`() {
        val j = mapOf("a" to 3, "b" to 2, "c" to 1)
        val reversed = RetrievalMetrics.nDCG(listOf("c", "b", "a"), j, 10)
        assertTrue(reversed < 1.0, "reversed ranking must score below perfect, got $reversed")
        assertTrue(reversed > 0.0)
    }

    @Test
    fun `nDCG matches a hand computation`() {
        // One ideal doc at rank 2, one related doc at rank 1.
        // log2(3) = 1.5849625007211562
        // DCG  = (2^1-1)/log2(2) + (2^3-1)/log2(3)
        //      = 1 + 4.416508274900101 = 5.416508274900101
        // IDCG = (2^3-1)/log2(2) + (2^1-1)/log2(3)
        //      = 7 + 0.6309297535714574 = 7.630929753571457
        // nDCG = 0.7098097413968654
        //
        // The first draft of this said 0.70969, from rounding log2(3) to five
        // places. The implementation was right and the hand computation was
        // wrong — which is the correct outcome for a test whose expectation is
        // derived independently rather than read off the code.
        val j = mapOf("ideal" to 3, "related" to 1)
        close(0.7098097413968654, RetrievalMetrics.nDCG(listOf("related", "ideal"), j, 10), 1e-9)
    }

    @Test
    fun `irrelevant results contribute nothing`() {
        val j = mapOf("a" to 3)
        val withNoise = RetrievalMetrics.nDCG(listOf("a", "junk1", "junk2"), j, 10)
        close(1.0, withNoise)
    }

    @Test
    fun `a relevant doc beyond k does not count`() {
        val j = mapOf("a" to 3)
        close(0.0, RetrievalMetrics.nDCG(listOf("junk", "junk2", "a"), j, 2))
    }

    @Test
    fun `a query with nothing relevant scores zero, not one`() {
        // The trap: an expect-empty query has an IDCG of 0, and "0/0 = perfect"
        // would make adding should-return-nothing cases look like a quality
        // improvement. Scorecard also excludes them from the ranking means.
        close(0.0, RetrievalMetrics.nDCG(listOf("a", "b"), emptyMap(), 10))
        close(0.0, RetrievalMetrics.nDCG(emptyList(), mapOf("a" to 3), 10))
    }

    @Test
    fun `grade three is worth more than three grade ones`() {
        // 2^grade - 1 is superlinear on purpose: one ideal answer beats a pile
        // of tangentially related ones, which is how recall is actually judged.
        val ideal = RetrievalMetrics.nDCG(listOf("x"), mapOf("x" to 3, "a" to 1, "b" to 1, "c" to 1), 1)
        val related = RetrievalMetrics.nDCG(listOf("a"), mapOf("x" to 3, "a" to 1, "b" to 1, "c" to 1), 1)
        assertTrue(ideal > related * 3, "gain must be superlinear in grade: $ideal vs $related")
    }

    // ---- recall ----------------------------------------------------------

    @Test
    fun `recall counts relevant documents in the top k`() {
        val j = mapOf("a" to 3, "b" to 2, "c" to 1, "d" to 0)
        close(1.0 / 3.0, RetrievalMetrics.recallAt(listOf("a", "d"), j, 5))
        close(2.0 / 3.0, RetrievalMetrics.recallAt(listOf("a", "b", "d"), j, 5))
        close(1.0, RetrievalMetrics.recallAt(listOf("a", "b", "c"), j, 5))
    }

    @Test
    fun `grade zero is not relevant`() {
        close(0.0, RetrievalMetrics.recallAt(listOf("d"), mapOf("a" to 3, "d" to 0), 5))
    }

    @Test
    fun `recall respects k`() {
        val j = mapOf("a" to 3, "b" to 3)
        close(0.5, RetrievalMetrics.recallAt(listOf("a", "b"), j, 1))
    }

    @Test
    fun `recall with nothing relevant is zero, not one`() {
        close(0.0, RetrievalMetrics.recallAt(listOf("a"), emptyMap(), 5))
    }

    // ---- MRR -------------------------------------------------------------

    @Test
    fun `reciprocal rank finds the first strongly relevant hit`() {
        val j = mapOf("weak" to 1, "strong" to 2)
        close(1.0, RetrievalMetrics.reciprocalRank(listOf("strong", "weak"), j))
        close(0.5, RetrievalMetrics.reciprocalRank(listOf("weak", "strong"), j))
    }

    @Test
    fun `a merely related hit does not satisfy MRR`() {
        // Grade 2, not 1: MRR answers "how fast does the user reach the thing
        // they wanted", and a tangential hit at rank 1 is not that.
        close(0.0, RetrievalMetrics.reciprocalRank(listOf("weak"), mapOf("weak" to 1)))
    }

    @Test
    fun `no strong hit scores zero`() {
        close(0.0, RetrievalMetrics.reciprocalRank(listOf("x", "y"), mapOf("a" to 3)))
    }

    // ---- Scorecard aggregation ------------------------------------------

    @Test
    fun `expect-empty queries are excluded from the ranking means`() {
        // Otherwise adding should-return-nothing cases — which score 0 on every
        // ranking metric by construction — would read as a quality regression,
        // and the set would quietly stop covering the behaviour.
        val ranked = QueryResult("q1", "lexical", listOf("a"), mapOf("a" to 3), 1)
        val empty = QueryResult("q2", "expect-empty", emptyList(), emptyMap(), 1)

        val alone = Scorecard.from("a", listOf(ranked), 0)
        val together = Scorecard.from("b", listOf(ranked, empty), 0)

        close(alone.ndcg10, together.ndcg10)
        assertEquals(2, together.queryCount, "the empty query is still counted in queryCount")
    }

    @Test
    fun `correctlyEmptyRate measures only the queries that should be empty`() {
        val good = QueryResult("q1", "expect-empty", emptyList(), emptyMap(), 1)
        val bad = QueryResult("q2", "expect-empty", listOf("junk"), emptyMap(), 1)
        val ranked = QueryResult("q3", "lexical", listOf("a"), mapOf("a" to 3), 1)

        close(1.0, Scorecard.from("x", listOf(good, ranked), 0).correctlyEmptyRate)
        close(0.0, Scorecard.from("y", listOf(bad, ranked), 0).correctlyEmptyRate)
        close(0.5, Scorecard.from("z", listOf(good, bad, ranked), 0).correctlyEmptyRate)
    }

    @Test
    fun `a set with no expect-empty queries reports a rate of one`() {
        // Not zero: "no such queries" must not read as "all of them failed".
        val ranked = QueryResult("q1", "lexical", listOf("a"), mapOf("a" to 3), 1)
        close(1.0, Scorecard.from("x", listOf(ranked), 0).correctlyEmptyRate)
    }

    @Test
    fun `an empty result set does not throw`() {
        val card = Scorecard.from("empty", emptyList(), 0)
        assertEquals(0, card.queryCount)
        close(0.0, card.ndcg10)
    }

    @Test
    fun `the per-class breakdown groups by class`() {
        val a = QueryResult("q1", "lexical", listOf("a"), mapOf("a" to 3), 1)
        val b = QueryResult("q2", "synonym-only", listOf("junk"), mapOf("b" to 3), 1)
        val card = Scorecard.from("x", listOf(a, b), 0)

        assertEquals(setOf("lexical", "synonym-only"), card.byClass.keys)
        close(1.0, card.byClass.getValue("lexical").ndcg10)
        close(0.0, card.byClass.getValue("synonym-only").ndcg10)
    }
}
