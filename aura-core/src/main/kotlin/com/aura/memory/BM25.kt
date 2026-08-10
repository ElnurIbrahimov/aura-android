package com.aura.memory

import kotlin.math.ln
import kotlin.math.max

/**
 * BM25 scorer for memory retrieval. Builds IDF map + document length
 * stats at construction. Scores each document against a query using
 * the standard BM25 formula with k1=1.2, b=0.75.
 *
 * For a personal-use install with hundreds to low-thousands of memories,
 * construction + scoring at query time is <10ms.
 */
class BM25(
    documents: List<String>,
    /**
     * Total documents in the corpus — NOT in [documents].
     *
     * Defaults to `documents.size` for callers that genuinely are scoring a
     * whole corpus (tests, ad-hoc ranking). Production recall must pass the
     * real scoped count: see [corpusDocFreq].
     */
    corpusSize: Int = documents.size,
    /**
     * Corpus-wide document frequency per token, for the query's tokens.
     *
     * Empty means "fall back to counting within [documents]", which is what
     * this class did unconditionally before 2026-08-08 — and which was wrong
     * whenever [documents] was a pre-filtered candidate list rather than the
     * corpus. `MemoryStore.query` handed it the rows that had already matched a
     * query term, so `df` for exactly the discriminating terms approached `N`,
     * `ln((N - df + 0.5) / (df + 0.5))` went negative, and the [IDF_FLOOR]
     * clamp gave every query term the same weight. The lexical signal — one of
     * RRF's six — then ranked candidates essentially arbitrarily.
     */
    private val corpusDocFreq: Map<String, Int> = emptyMap(),
    private val k1: Float = 1.2f,
    private val b: Float = 0.75f,
    /** Lower bound on IDF. Exposed so the eval harness can sweep it. */
    private val idfFloor: Float = IDF_FLOOR,
    /**
     * Whether [tokenize] emits adjacent bigrams alongside unigrams.
     *
     * Suspect, and now measurable: only unigrams receive a corpus document
     * frequency, so every bigram falls back to candidate-set `df` — the exact
     * defect corpus statistics exist to fix, still live for the token class
     * that is rarest by construction. Bigrams also double `length`, which
     * depresses every unigram through the length-normalisation denominator.
     */
    private val bigrams: Boolean = true,
) {
    private data class DocStats(val tokens: List<String>, val length: Int)

    private val docs: List<DocStats>
    private val avgDocLength: Float
    private val idf: Map<String, Float>

    init {
        docs = documents.map { doc ->
            val tokens = tokenizeHere(doc)
            DocStats(tokens, tokens.size)
        }
        avgDocLength = if (docs.isNotEmpty()) {
            docs.sumOf { it.length }.toFloat() / docs.size
        } else 0f

        // Document frequency within the supplied documents — the fallback for
        // any token the caller did not supply a corpus count for (BM25's
        // tokenizer emits bigrams, which are more expensive to count against
        // the corpus than they are worth).
        val localDf = mutableMapOf<String, Int>()
        for (doc in docs) {
            for (token in doc.tokens.toSet()) {
                localDf[token] = (localDf[token] ?: 0) + 1
            }
        }

        // IDF: ln((N - df + 0.5) / (df + 0.5)), floored.
        // N and df come from the corpus when the caller supplied them.
        val n = maxOf(corpusSize, docs.size).toFloat()
        val allTokens = localDf.keys + corpusDocFreq.keys
        idf = allTokens.associateWith { token ->
            val freq = (corpusDocFreq[token] ?: localDf[token] ?: 0).coerceAtMost(n.toInt())
            val raw = ln((n - freq + 0.5f) / (freq + 0.5f))
            // Floor at a small positive value instead of 0 so a term present in
            // most documents still contributes something. With a real corpus df
            // this is a backstop for genuinely ubiquitous terms; with the old
            // candidate-set df it was the normal path for every query term.
            max(idfFloor, raw)
        }
    }

    /**
     * Score a single document against [query]. Returns a raw BM25 score
     * (not normalized). Higher is better.
     */
    fun score(query: String, docIndex: Int): Float {
        if (docIndex < 0 || docIndex >= docs.size) return 0f
        val queryTokens = tokenizeHere(query)
        if (queryTokens.isEmpty()) return 0f
        val doc = docs[docIndex]
        val docLength = doc.length.toFloat()

        // Term frequency in this document
        val tf = mutableMapOf<String, Int>()
        for (token in doc.tokens) {
            tf[token] = (tf[token] ?: 0) + 1
        }

        var score = 0f
        for (token in queryTokens) {
            val tokenTf = tf[token] ?: 0
            if (tokenTf == 0) continue
            val tokenIdf = idf[token] ?: 0f
            if (tokenIdf == 0f) continue
            val denom = tokenTf + k1 * (1 - b + b * docLength / max(avgDocLength, 1f))
            score += tokenIdf * (k1 + 1) * tokenTf / denom
        }
        return score
    }

    /**
     * Rank all documents against [query]. Returns top-K document indices
     * with their scores, sorted descending.
     */
    fun rank(query: String, topK: Int): List<Pair<Int, Float>> {
        if (docs.isEmpty()) return emptyList()
        val scores = docs.indices.map { idx -> idx to score(query, idx) }
        return scores
            .filter { it.second > 0f }
            .sortedByDescending { it.second }
            .take(topK)
    }

    /**
     * Normalize a raw BM25 score to 0-1 by dividing by the maximum the scoring
     * formula can actually produce for this query. Approximate — good enough
     * for RRF fusion, where only the relative ranking matters.
     *
     * The divisor is `sum(idf) * (k1 + 1)`, matching [score], which multiplies
     * each term's contribution by `(k1 + 1) * tf / denom` — a factor that
     * approaches `(k1 + 1)` as `tf` grows. Dividing by the bare `sum(idf)`
     * meant `raw` routinely exceeded the divisor and `coerceIn` clamped the
     * result to exactly 1.0. Combined with the candidate-set IDF collapse, that
     * flattened `textScore` to 1.0 across most candidates and destroyed the
     * lexical rank ordering entirely — RRF ranks by order, so a tie among
     * candidates is the same as no signal.
     *
     * Uses distinct query tokens: a term repeated in the query cannot raise
     * the ceiling more than once.
     */
    fun normalizedScore(query: String, docIndex: Int): Float {
        val raw = score(query, docIndex)
        if (raw <= 0f) return 0f
        val maxPossible = tokenizeHere(query).toSet().sumOf { (idf[it] ?: 0f).toDouble() }.toFloat() * (k1 + 1)
        if (maxPossible <= 0f) return 0f
        return (raw / maxPossible).coerceIn(0f, 1f)
    }

    /** [tokenize], honouring this instance's [bigrams] setting. */
    private fun tokenizeHere(text: String): List<String> = tokenize(text, bigrams)

    companion object {
        /**
         * Lower bound on IDF, so a term present in nearly every document still
         * contributes rather than dropping out of [score] entirely.
         */
        const val IDF_FLOOR = 0.1f

        /**
         * Tokenize text for BM25: lowercase, split on non-alphanumeric,
         * filter empty tokens, optionally add adjacent bigrams.
         *
         * @param bigrams when false, unigrams only. Default true to preserve
         *   the shipped behaviour; see [BM25.bigrams] for why it is worth
         *   measuring the other way.
         */
        fun tokenize(text: String, bigrams: Boolean = true): List<String> {
            val lower = text.lowercase()
            val words = lower.split(Regex("[^a-z0-9_\\u00C0-\\uFFFF]+"))
                .filter { it.isNotEmpty() }
            if (!bigrams) return words
            val out = mutableListOf<String>()
            out.addAll(words)
            // Bigrams for better signal
            for (i in 0 until words.size - 1) {
                out.add("${words[i]}_${words[i + 1]}")
            }
            return out
        }
    }
}