package com.aura.memory.eval

import com.aura.memory.RetrievalConfig
import com.aura.memory.SignalWeights
import com.aura.memory.TieHandling
import org.junit.Test
import kotlin.math.abs
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The retrieval eval harness.
 *
 * **Exactly four `@Test` methods, each iterating the fixtures internally.**
 * That is a hard constraint, not a style choice: `scripts/check-test-count.sh`
 * gates the "N unit tests" string in README.md and architecture.md against the
 * JUnit XML, so one `@Test` per golden query would force a two-document edit
 * every time a query is added. Fixtures grow; the test count does not.
 *
 * The suite gates on NO REGRESSION against a committed baseline, never on an
 * absolute score. Absolute thresholds get quietly lowered under pressure and
 * stop meaning anything; a baseline diff is a reviewed one-file change that
 * says plainly "this commit moved retrieval quality by this much".
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class RetrievalEvalTest {

    private val runner = RetrievalEvalRunner()

    // ---- 1. quality does not regress -------------------------------------

    @Test
    fun `nDCG at 10 does not regress below the committed baseline`() {
        val cards = mutableListOf<Scorecard>()
        val current = runner.run("current (default config)", RetrievalConfig.DEFAULT)
        cards += current

        // Comparison configs. These do not gate anything — they exist so the
        // report answers "what would this change buy?" on every run, which is
        // the question the harness was built for.
        cards += runner.run(
            "competition tie handling",
            RetrievalConfig.DEFAULT.copy(tieHandling = TieHandling.COMPETITION),
        )
        cards += runner.run(
            "relevance-weighted",
            RetrievalConfig.DEFAULT.copy(
                tieHandling = TieHandling.COMPETITION,
                weights = SignalWeights(
                    text = 1f, vector = 1f, recency = 0.35f,
                    usage = 0.35f, decay = 0f, importance = 0.2f,
                ),
            ),
        )
        cards += runner.run("no bigrams", RetrievalConfig.DEFAULT.copy(bm25Bigrams = false))

        // Gate B runs only when someone has generated vector files locally. When
        // they have not — the normal case, and CI's — the report says so in
        // words rather than omitting the section, because a decision worth five
        // to ten days should not be made against evidence that is merely absent
        // instead of visibly absent.
        val baseline = EvalFixtures.baseline()
        // Gate B twice, at both weightings, because neither alone answers the question.
        //
        // At LEGACY weights a semantic model is one vote of six, against recency, usage,
        // decay and importance — four signals that know nothing about what was asked. At
        // relevance weights with no semantic model there is nothing to amplify. Running
        // only one of the two makes the embedder look worthless for a reason that is
        // actually about the ranking, and the ranking look worthless for a reason that is
        // actually about the embedder.
        val relevanceWeighted = RetrievalConfig.DEFAULT.copy(
            tieHandling = TieHandling.COMPETITION,
            weights = SignalWeights(
                text = 1f, vector = 1f, recency = 0.35f,
                usage = 0.35f, decay = 0f, importance = 0.2f,
            ),
        )
        // And once more with the vector arm in pool SELECTION, not just in scoring.
        //
        // vectorPoolSize is 0 today, measured off against a hash sketch whose vectors are
        // noise. Its own KDoc says what that costs: "without it a memory sharing no word
        // with the query can never be a candidate on the main path". Every synonym-only
        // query in this corpus is exactly that case — the target was never in the pool, so
        // the two runs above were measuring how much a semantic model helps after the
        // candidates were chosen lexically, which is not the question anyone asked.
        //
        // The constant's comment asks for precisely this A/B: "the code path stays for an
        // A/B with a real embedder; the default does not."
        val semanticPool = relevanceWeighted.copy(vectorPoolSize = 25)
        runner.writeReport(
            cards,
            baseline,
            gateB = runner.gateB() +
                runner.gateB(relevanceWeighted).map { it.copy(label = it.label + " @ relevance weights") } +
                runner.gateB(semanticPool).map { it.copy(label = it.label + " @ weights + vector pool") } +
                // minRelevance is 0.15, set at 3 sigma of a 384-dim HASH's noise
                // (|cos| ~= 1/sqrt(384) ~= 0.051). A real sentence embedder puts entirely
                // unrelated English at 0.2-0.4, so that floor admits everything and
                // "nothing is relevant" stops being expressible — which is exactly what
                // correctly-empty being 0.0000 in every row above is reporting. Recalibrate
                // or the better ranking ships alongside an assistant that answers
                // questions it has nothing to say about.
                // Swept, not guessed. The floor is a property of the model's similarity
                // distribution, not a global constant: 0.15 is three sigma of a hash's
                // noise, and a real model puts unrelated English far above it. Each model
                // needs its own, so comparing them all at one value judges the value
                // rather than the models.
                listOf(0.35f, 0.50f, 0.60f, 0.70f).flatMap { floor ->
                    runner.gateB(semanticPool.copy(minRelevance = floor)).map {
                        it.copy(label = it.label + " @ floor %.2f".format(floor))
                    }
                },
        )

        // A missing baseline FAILS. It must not skip: a gate that reports OK
        // over absent data is precisely the defect this repo's history records
        // finding in four separate source-scanning tests.
        assertTrue(
            baseline != null,
            "No baseline.json. Generate one from the current scorecard and commit it in the " +
                "same change; a harness with nothing to compare against gates nothing.",
        )
        baseline!!

        assertTrue(
            current.ndcg10 >= baseline.ndcg10 - baseline.tolerance,
            "nDCG@10 regressed: ${"%.4f".format(current.ndcg10)} < " +
                "${"%.4f".format(baseline.ndcg10)} - ${baseline.tolerance}. " +
                "If this change is a deliberate quality trade, update baseline.json in the same commit.",
        )
        assertTrue(
            current.ndcg5 >= baseline.ndcg5 - baseline.tolerance,
            "nDCG@5 regressed: ${"%.4f".format(current.ndcg5)} vs ${"%.4f".format(baseline.ndcg5)}",
        )
        assertTrue(
            current.recall5 >= baseline.recall5 - baseline.tolerance,
            "recall@5 regressed: ${"%.4f".format(current.recall5)} vs ${"%.4f".format(baseline.recall5)}",
        )
    }

    // ---- 2. silence does not regress either ------------------------------

    @Test
    fun `the zero-result behaviour does not regress`() {
        // Returning nothing is real product behaviour, asserted independently
        // by MemoryStoreQueryTest. A greedier fusion improves nDCG while
        // breaking it, so the two have to be gated together or the first will
        // be traded away for the second without anyone noticing.
        val current = runner.run("current (default config)", RetrievalConfig.DEFAULT)
        val baseline = EvalFixtures.baseline()
        assertTrue(baseline != null, "No baseline.json — see the nDCG test.")
        baseline!!

        assertTrue(
            current.correctlyEmptyRate >= baseline.correctlyEmptyRate - baseline.tolerance,
            "queries that should return nothing started returning something: " +
                "${"%.4f".format(current.correctlyEmptyRate)} vs ${"%.4f".format(baseline.correctlyEmptyRate)}",
        )
    }

    // ---- 3. the fixtures are internally consistent -----------------------

    @Test
    fun `every judged memory id exists in the corpus`() {
        // Fixture integrity. A typo'd id is silently graded 0 for a document
        // that was never in the corpus, which lowers the score for a reason
        // having nothing to do with retrieval and is invisible in the report.
        val corpusIds = EvalFixtures.corpus().map { it.id }.toSet()
        assertTrue(corpusIds.isNotEmpty(), "corpus.jsonl parsed to nothing")

        val queries = EvalFixtures.queries()
        assertTrue(queries.isNotEmpty(), "queries.jsonl parsed to nothing")

        val dangling = queries.flatMap { q -> q.judgments.keys.map { q.qid to it } }
            .filterNot { (_, id) -> id in corpusIds }
        assertTrue(dangling.isEmpty(), "judgments reference ids not in the corpus: $dangling")

        val duplicateIds = corpusIds.size != EvalFixtures.corpus().size
        assertTrue(!duplicateIds, "corpus.jsonl contains duplicate ids")

        val duplicateQids = queries.map { it.qid }.toSet().size != queries.size
        assertTrue(!duplicateQids, "queries.jsonl contains duplicate qids")

        // Grades outside 0..3 would silently distort nDCG's 2^grade gain.
        val badGrades = queries.flatMap { q -> q.judgments.values.map { q.qid to it } }
            .filterNot { (_, g) -> g in 0..3 }
        assertTrue(badGrades.isEmpty(), "grades must be 0..3: $badGrades")

        // At least one should-return-nothing case, or metric 2 gates nothing.
        assertTrue(
            queries.any { q -> q.judgments.none { it.value >= 1 } },
            "the set needs at least one expect-empty query",
        )

        // The class name Gate B keys on must exist, spelled exactly. It is a
        // string matched against fixture data, so a rename or a typo does not
        // fail — it silently reports "—" in the column the whole experiment is
        // about, and the verdict below it computes from a share of zero.
        assertTrue(
            queries.any { it.queryClass == RetrievalEvalRunner.SYNONYM_CLASS },
            "no query has class '${RetrievalEvalRunner.SYNONYM_CLASS}' — Gate B would report nothing " +
                "and conclude 'do not proceed' from missing data. Present classes: " +
                queries.map { it.queryClass }.distinct().sorted(),
        )
    }

    // ---- 4. the run does not mutate the corpus ---------------------------

    @Test
    fun `a full eval run leaves the corpus unmodified`() {
        // `touch` writes accessedAt, accessCount and decayScore on every hit,
        // and all three feed fusion signals — so with it on, query N+1 sees a
        // corpus altered by query N and the whole run depends on query order.
        // RetrievalEvalRunner forces touchOnRecall off; this asserts it stuck.
        val corpus = EvalFixtures.corpus()
        val a = runner.run("run A", RetrievalConfig.DEFAULT, corpus = corpus)
        val b = runner.run("run B", RetrievalConfig.DEFAULT, corpus = corpus)

        assertEquals(a.ndcg10, b.ndcg10, "two identical runs disagreed — something is mutating state")
        assertEquals(a.recall5, b.recall5)
        assertEquals(a.correctlyEmptyRate, b.correctlyEmptyRate)

        // And order-independence within a run: reversing the query order must
        // not change the aggregate, which it would if `touch` were writing.
        val reversed = runner.run(
            "reversed order",
            RetrievalConfig.DEFAULT,
            corpus = corpus,
            queries = EvalFixtures.queries().reversed(),
        )
        // Tolerance, not exact equality. The first version of this compared
        // Doubles exactly and failed at 0.779100786129442 vs 0.7791007861294419
        // — one ulp, from summing the same per-query scores in a different
        // order. That is float addition being non-associative, not the corpus
        // being mutated, and asserting exact equality here would make the test
        // fail for a reason it is not about.
        assertTrue(
            abs(a.ndcg10 - reversed.ndcg10) < 1e-9,
            "query order changed the score — the corpus is being mutated mid-run: " +
                "${a.ndcg10} vs ${reversed.ndcg10}",
        )
    }
}
