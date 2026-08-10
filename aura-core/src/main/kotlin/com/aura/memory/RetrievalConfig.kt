package com.aura.memory

/**
 * How each fusion signal is weighted in [Retrieval.rankCandidates].
 *
 * [LEGACY] is every weight at 1.0 — exactly what the code did before weights
 * existed, kept so a behaviour change can be bisected to a single value rather
 * than to a commit.
 */
data class SignalWeights(
    val text: Float,
    val vector: Float,
    val recency: Float,
    val usage: Float,
    val decay: Float,
    val importance: Float,
) {
    companion object {
        /** All signals equal. The shipped behaviour up to 2026-08-10. */
        val LEGACY = SignalWeights(1f, 1f, 1f, 1f, 1f, 1f)
    }
}

/**
 * How tied signal values are converted to ranks.
 */
enum class TieHandling {
    /**
     * Dense: tied values still receive distinct consecutive ranks, broken by
     * input order. The shipped behaviour, and a bug — see [COMPETITION].
     */
    DENSE,

    /**
     * Standard competition: equal values share a rank.
     *
     * The correct choice, and not a cosmetic one. Under [DENSE], a signal whose
     * values are largely tied stops measuring itself and starts re-measuring
     * whatever ordered the input — which is `ORDER BY decayScore DESC` from the
     * candidate query. `WriteGate` emits only five distinct importance values
     * with a catch-all at 0.5, so on a 25-candidate pool most rows tie and
     * `importance` was in practice a second `decayScore` vote. The same applies
     * to `decayScore` itself on a fresh install (all 1.0) and to `textScore` in
     * the vector-fallback branch, where every candidate is hardcoded to 0.
     *
     * Under competition ranking a signal that is constant across the pool gives
     * every candidate rank 1 and cancels out of the comparison entirely, which
     * is what "this signal has nothing to say here" should mean.
     */
    COMPETITION,
}

/** Which reranker, if any, runs after fusion. */
enum class RerankMode {
    /** No reranking. */
    OFF,

    /** On-device cross-encoder, when one is available. */
    LOCAL,

    /** The LLM-as-judge reranker, always. */
    LLM,

    /**
     * The LLM reranker only when the caller passed a model. The shipped
     * behaviour: the four tool-initiated callers pass none, so they have never
     * been reranked.
     */
    LLM_IF_MODEL_SET,
}

/**
 * Every tunable in the retrieval pipeline, in one place.
 *
 * These were `const val`s spread across [MemoryStore], [Retrieval] and [BM25].
 * That was fine while nothing measured them and impossible once something does:
 * an eval harness has to be able to run the same corpus under two configs and
 * compare, and a compile-time constant cannot be A/B'd.
 *
 * This is deliberately NOT surfaced in Settings. It exists so the harness can
 * sweep, not so recall can be misconfigured by hand. Promote an individual knob
 * to the UI only once it has earned it.
 *
 * Defaults reproduce the shipped behaviour exactly.
 */
data class RetrievalConfig(
    /** RRF constant. Higher smooths rank disparities; with small pools it also flattens the whole score range. */
    val rrfK: Float = 60f,
    val weights: SignalWeights = SignalWeights.LEGACY,
    /** Half-life for recency and access-recency scoring, in days. */
    val signalHalfLifeDays: Double = 7.0,
    val tieHandling: TieHandling = TieHandling.DENSE,
    /** Upper bound on query terms sent to FTS and probed for document frequency. */
    val maxQueryTerms: Int = 24,
    /** Candidate pool = max(limit * this, rerankPoolSize + 5). */
    val candidateMultiplier: Int = 3,
    val vectorFallbackScanLimit: Int = 2000,
    /**
     * Candidates handed to the reranker.
     *
     * Coupled to [candidateMultiplier] in a way the old constants hid: the pool
     * is `max(limit * multiplier, rerankPoolSize + 5)`, so raising this above
     * the pool does nothing unless the multiplier moves too.
     */
    val rerankPoolSize: Int = 20,
    /** Below this many candidates, reranking is skipped as not worth the call. */
    val rerankMinCandidates: Int = 5,
    val rerankMode: RerankMode = RerankMode.LLM_IF_MODEL_SET,
    val bm25K1: Float = 1.2f,
    val bm25B: Float = 0.75f,
    val bm25IdfFloor: Float = 0.1f,
    /**
     * Whether BM25 tokenises adjacent bigrams alongside unigrams.
     *
     * On by default because that is what shipped, but suspect: only unigrams
     * get a corpus document frequency, so every bigram falls back to
     * candidate-set `df` — the exact defect corpus statistics were introduced
     * to fix, still live for the token class that is rarest by construction.
     * Bigrams also double `docLength`, which depresses every unigram through
     * BM25's length-normalisation denominator.
     */
    val bm25Bigrams: Boolean = true,
    /**
     * Whether a recall bumps `accessedAt`/`accessCount`/`decayScore` on its hits.
     *
     * Production wants this. An eval run must not: `touch` mutates the corpus,
     * so query N+1 would see a corpus altered by query N and results would
     * depend on query order.
     */
    val touchOnRecall: Boolean = true,
    /** Populate a [RetrievalTrace] for each query. Off in production. */
    val trace: Boolean = false,
) {
    companion object {
        /** The shipped behaviour. */
        val DEFAULT = RetrievalConfig()
    }
}

/**
 * What one query actually did, for the eval harness and for diagnosing a recall
 * that went wrong.
 *
 * Only populated when [RetrievalConfig.trace] is on, because the per-signal rank
 * maps are the interesting part and they are not free to keep.
 */
data class RetrievalTrace(
    /** Which branch produced the results. */
    val branch: Branch,
    val queryTerms: List<String> = emptyList(),
    /** The query after rewriting, when rewriting ran. */
    val rewrittenQuery: String? = null,
    val candidateCount: Int = 0,
    val corpusSize: Int = 0,
    /**
     * Candidates whose stored vector was produced by a different embedding
     * model than the current one, and therefore scored 0.
     *
     * Non-zero here after an embedding-model change means the vector signal is
     * silently dead for those rows — the single most likely cause of "recall
     * got worse and nothing in the logs says why".
     */
    val staleVectorCount: Int = 0,
    val rerankRan: Boolean = false,
    val elapsedMs: Long = 0,
) {
    enum class Branch {
        /** FTS4 produced candidates; the normal path. */
        LEXICAL,

        /** No lexical hits, so the vector scan ran and returned early. */
        VECTOR_FALLBACK,

        /** Every query term was a stopword or too short; LIKE fallback. */
        LIKE_FALLBACK,

        /** Nothing matched. */
        EMPTY,
    }
}
