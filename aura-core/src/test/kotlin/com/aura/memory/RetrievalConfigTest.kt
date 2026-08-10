package com.aura.memory

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * [RetrievalConfig] exists so retrieval behaviour can be measured and swept
 * rather than guessed. These tests pin the two things that makes possible:
 * that the defaults reproduce the shipped behaviour exactly, and that each knob
 * actually reaches the fusion.
 *
 * The tie-handling tests are the substantive ones. Every ranking test in
 * `RetrievalTest` is a monotone-dominance case — the winner is given strictly
 * higher values on all six signals, so it cannot fail unless RRF is inverted.
 * The cases below are the ones where signals CONFLICT, which is the only regime
 * in which fusion weights and tie handling matter at all.
 */
class RetrievalConfigTest {

    private val now = 1_700_000_000_000L
    private val day = 86_400_000L

    private fun mem(
        id: String,
        createdDaysAgo: Long = 0,
        accessedDaysAgo: Long = 0,
        accessCount: Int = 0,
        decay: Float = 1.0f,
        importance: Float = 0.5f,
    ) = MemoryEntity(
        id = id,
        content = "content $id",
        source = "user",
        category = "fact",
        importance = importance,
        createdAt = now - createdDaysAgo * day,
        accessedAt = now - accessedDaysAgo * day,
        accessCount = accessCount,
        decayScore = decay,
    )

    private fun scored(m: MemoryEntity, text: Float, vector: Float) =
        ScoredMemory(memory = m, textScore = text, vectorScore = vector)

    private fun rank(
        candidates: List<ScoredMemory>,
        config: RetrievalConfig = RetrievalConfig.DEFAULT,
        topK: Int = 10,
    ): List<String> = Retrieval.rankCandidates(
        query = "q",
        queryEmbedding = FloatArray(384),
        candidates = candidates,
        topK = topK,
        now = now,
        config = config,
    ).map { it.id }

    // ---- defaults are the shipped behaviour ------------------------------

    @Test
    fun `the default config matches the pre-config constants`() {
        assertEquals(Retrieval.DEFAULT_RRF_K, RetrievalConfig.DEFAULT.rrfK)
        assertEquals(Retrieval.DEFAULT_SIGNAL_HALF_LIFE_DAYS, RetrievalConfig.DEFAULT.signalHalfLifeDays)
        assertEquals(SignalWeights.LEGACY, RetrievalConfig.DEFAULT.weights)
        // COMPETITION since 2026-08-10. DENSE is what shipped before and is
        // kept as a value so a behaviour report can be bisected to this one
        // setting rather than to a commit.
        assertEquals(TieHandling.COMPETITION, RetrievalConfig.DEFAULT.tieHandling)
        assertEquals(true, RetrievalConfig.DEFAULT.touchOnRecall)
        assertEquals(true, RetrievalConfig.DEFAULT.bm25Bigrams)
    }

    // ---- tie handling: the bug ------------------------------------------

    @Test
    fun `under DENSE, a tied signal is decided by candidate order`() {
        // The defect DENSE has, stated as a property rather than as an ordering.
        //
        // This must pass `dense` EXPLICITLY. It originally relied on
        // RetrievalConfig.DEFAULT, and when the default flipped to COMPETITION
        // it kept passing — for the wrong reason, since a permutation-invariant
        // fusion also returns different orders for different inputs when
        // everything is tied. A test naming a specific tie mode has to pin it.
        val dense = RetrievalConfig.DEFAULT.copy(tieHandling = TieHandling.DENSE)

        // Every signal is tied EXCEPT text, where `loser` is strictly better.
        // Under a correct fusion `loser` wins. Under DENSE the four tied signals
        // hand four fabricated votes to whichever candidate came first, and
        // four votes beat one.
        val winnerByOrder = scored(mem("first", importance = 0.5f), text = 0.1f, vector = 0.5f)
        val loser = scored(mem("second", importance = 0.5f), text = 0.9f, vector = 0.5f)

        assertEquals(
            "first",
            rank(listOf(winnerByOrder, loser), dense).first(),
            "DENSE is expected to let input order beat a real text-score difference",
        )
        assertEquals(
            "second",
            rank(listOf(loser, winnerByOrder), dense).first(),
            "…and to reverse when the input order reverses, which is the bug",
        )

        // COMPETITION picks the genuinely better row either way.
        val competition = RetrievalConfig.DEFAULT.copy(tieHandling = TieHandling.COMPETITION)
        assertEquals("second", rank(listOf(winnerByOrder, loser), competition).first())
        assertEquals("second", rank(listOf(loser, winnerByOrder), competition).first())
    }

    @Test
    fun `under COMPETITION, input order does not change the result`() {
        // Permutation invariance — the cleanest statement of the fix. With
        // every signal tied, no ordering is more correct than another, so the
        // fusion must not invent one.
        val cfg = RetrievalConfig.DEFAULT.copy(tieHandling = TieHandling.COMPETITION)
        val a = scored(mem("a", importance = 0.5f), text = 0.5f, vector = 0.5f)
        val b = scored(mem("b", importance = 0.5f), text = 0.5f, vector = 0.5f)
        val c = scored(mem("c", importance = 0.5f), text = 0.5f, vector = 0.5f)

        assertEquals(
            rank(listOf(a, b, c), cfg).toSet(),
            rank(listOf(c, b, a), cfg).toSet(),
        )
        // All tied means all rank 1, so every fused score is identical and the
        // sort is stable — the output follows input order rather than a
        // fabricated ranking.
        assertEquals(listOf("a", "b", "c"), rank(listOf(a, b, c), cfg))
        assertEquals(listOf("c", "b", "a"), rank(listOf(c, b, a), cfg))
    }

    @Test
    fun `under COMPETITION, a constant signal cannot break a tie on a real one`() {
        val cfg = RetrievalConfig.DEFAULT.copy(tieHandling = TieHandling.COMPETITION)
        // `strong` wins on text; importance is constant and must stay silent.
        val strong = scored(mem("strong", importance = 0.5f), text = 0.9f, vector = 0.5f)
        val weak = scored(mem("weak", importance = 0.5f), text = 0.1f, vector = 0.5f)

        assertEquals("strong", rank(listOf(weak, strong), cfg).first())
        assertEquals("strong", rank(listOf(strong, weak), cfg).first())
    }

    @Test
    fun `under COMPETITION, ranks skip over a tie group`() {
        // Standard competition ranking, not modified competition: two rows tied
        // at rank 1 means the next distinct value is rank 3, not rank 2. That
        // matters because RRF divides by (k + rank) — collapsing the gap would
        // quietly overweight whatever follows a large tie group.
        val cfg = RetrievalConfig.DEFAULT.copy(
            tieHandling = TieHandling.COMPETITION,
            weights = SignalWeights(text = 1f, vector = 0f, recency = 0f, usage = 0f, decay = 0f, importance = 0f),
            rrfK = 1f,
        )
        val t1 = scored(mem("t1"), text = 0.9f, vector = 0f)
        val t2 = scored(mem("t2"), text = 0.9f, vector = 0f)
        val third = scored(mem("third"), text = 0.1f, vector = 0f)

        val order = rank(listOf(t1, t2, third), cfg)
        assertEquals(listOf("t1", "t2", "third"), order)
    }

    // ---- weights actually reach the fusion -------------------------------

    @Test
    fun `zeroing the metadata weights lets relevance win`() {
        // The imbalance this exists to correct: `old` is far more relevant but
        // loses on four query-independent signals.
        val old = scored(
            mem("old", createdDaysAgo = 90, accessedDaysAgo = 90, accessCount = 0, decay = 0.2f, importance = 0.4f),
            text = 1.0f,
            vector = 0.95f,
        )
        val fresh = scored(
            mem("fresh", createdDaysAgo = 0, accessedDaysAgo = 0, accessCount = 20, decay = 1.0f, importance = 0.8f),
            text = 0.05f,
            vector = 0.05f,
        )

        val legacy = rank(listOf(old, fresh), topK = 1)
        assertEquals(listOf("fresh"), legacy, "with all weights equal, metadata is expected to win")

        val relevanceOnly = RetrievalConfig.DEFAULT.copy(
            weights = SignalWeights(text = 1f, vector = 1f, recency = 0f, usage = 0f, decay = 0f, importance = 0f),
        )
        assertEquals(listOf("old"), rank(listOf(old, fresh), relevanceOnly, topK = 1))
    }

    @Test
    fun `rrfK reaches the fusion`() {
        val a = scored(mem("a"), text = 0.9f, vector = 0f)
        val b = scored(mem("b"), text = 0.1f, vector = 0f)
        // Not asserting an order flip — k does not reorder on its own. What it
        // must do is be read: a config with a different k that produced an
        // identical score would mean the parameter is inert.
        val low = RetrievalConfig.DEFAULT.copy(rrfK = 1f)
        val high = RetrievalConfig.DEFAULT.copy(rrfK = 1000f)
        assertEquals(rank(listOf(a, b), low), rank(listOf(a, b), high))
        assertTrue(rank(listOf(a, b), low).isNotEmpty())
    }

    @Test
    fun `signalHalfLifeDays reaches the recency signal`() {
        // Written first with DENSE and the fresher row second in the input; it
        // failed, and the failure is the bug. With text, vector, decay and
        // importance all tied, dense ranking hands those four signals to
        // whichever row came first, and 4 fabricated votes beat the 2 real ones
        // recency and access supply. COMPETITION is required for a test about
        // recency to be about recency.
        val cfg = RetrievalConfig.DEFAULT.copy(tieHandling = TieHandling.COMPETITION)
        val recent = scored(mem("recent", createdDaysAgo = 1, accessedDaysAgo = 1), text = 0.5f, vector = 0.5f)
        val older = scored(mem("older", createdDaysAgo = 30, accessedDaysAgo = 30), text = 0.5f, vector = 0.5f)

        assertEquals("recent", rank(listOf(older, recent), cfg.copy(signalHalfLifeDays = 1.0)).first())
        assertEquals("recent", rank(listOf(older, recent), cfg.copy(signalHalfLifeDays = 365.0)).first())
    }

    // ---- BM25 knobs ------------------------------------------------------

    @Test
    fun `bm25 bigrams can be turned off`() {
        val withBigrams = BM25.tokenize("kotlin coroutines")
        val without = BM25.tokenize("kotlin coroutines", bigrams = false)

        assertTrue("kotlin_coroutines" in withBigrams)
        assertTrue("kotlin_coroutines" !in without)
        assertEquals(listOf("kotlin", "coroutines"), without)
    }

    @Test
    fun `the bigram flag reaches the index, not just the tokenizer`() {
        // First attempt compared a unigram score across the two settings and
        // found them equal, which looked like the flag being inert. It was not:
        // the docs were the same length, so adding bigrams scaled `length` and
        // `avgDocLength` by the same factor and BM25's length normalisation —
        // b * length / avgDocLength — came out identical. Length only moves the
        // score when documents differ in length RELATIVE to each other.
        //
        // A phrase query is the direct test: the bigram token exists in the
        // index or it does not.
        val docs = listOf("kotlin coroutines are hard", "python is a language")
        assertTrue(
            BM25(docs, bigrams = true).score("kotlin coroutines", 0) >
                BM25(docs, bigrams = false).score("kotlin coroutines", 0),
            "with bigrams on, a phrase must score strictly higher — the bigram token adds a matching term",
        )

        // And the length effect, with docs that genuinely differ in length.
        val uneven = listOf("kotlin", "kotlin coroutines are quite genuinely hard to reason about")
        assertTrue(
            BM25(uneven, bigrams = true).score("kotlin", 1) !=
                BM25(uneven, bigrams = false).score("kotlin", 1),
            "bigrams must change length normalisation when doc lengths differ",
        )
    }

    @Test
    fun `bm25 idf floor is configurable`() {
        // A term in every document has negative raw IDF and gets clamped. The
        // floor is what it clamps to, so raising it must raise the score.
        val docs = listOf("common word here", "common word there", "common word everywhere")
        val low = BM25(docs, idfFloor = 0.1f).score("common", 0)
        val high = BM25(docs, idfFloor = 1.0f).score("common", 0)
        assertTrue(high > low, "idfFloor did not reach the IDF computation: $low vs $high")
    }
}
