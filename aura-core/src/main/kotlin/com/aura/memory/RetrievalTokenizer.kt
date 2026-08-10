package com.aura.memory

import com.aura.core.util.StopWords

/**
 * The one tokenizer for retrieval.
 *
 * There were two, and they disagreed. `MemoryStore` split the query on
 * whitespace to build the FTS `MATCH` expression and the document-frequency
 * probes; [BM25] split on non-alphanumeric to build its index and score against
 * it. `corpusDocFreq` was therefore **keyed by the FTS term and read by the BM25
 * token**, and any word the two tokenized differently silently fell through.
 *
 * `don't` is the clearest case. The FTS side produced `don't`, computed its
 * corpus document frequency, stored it, and nothing ever read that entry. The
 * BM25 side produced `don` and `t`, found neither in the map, and fell back to
 * candidate-set `df` — where every candidate contains the term by construction,
 * so `df` approaches `N`, IDF goes negative and clamps to the floor, and the
 * term stops discriminating. That is precisely the defect corpus statistics were
 * introduced to fix, still live for every token the two splitters disagreed on:
 * apostrophes, hyphens, slashes, and non-ASCII boundaries.
 *
 * Both halves now derive from [words], so the unigram vocabulary is identical by
 * construction rather than by two definitions that have to be kept in step.
 */
object RetrievalTokenizer {

    /**
     * The shared split. Everything else is a filter or an expansion over this.
     *
     * Matches BM25's historical regex rather than MemoryStore's whitespace
     * split, because it is the stricter of the two: `don't` becomes `don`, `t`
     * on both sides instead of `don't` on one and `don`, `t` on the other. That
     * loses the apostrophe form, which FTS could match — but a term that only
     * one side can see is worth less than a term both agree on, and disagreement
     * is what produced the silent fallback.
     */
    fun words(text: String): List<String> =
        text.lowercase()
            .split(WORD_SPLIT)
            .filter { it.isNotEmpty() }

    /**
     * Terms for the FTS `MATCH` expression and the document-frequency probes.
     *
     * Filtered: stopwords carry no signal and would match nearly every row,
     * flooding the candidate pool with fresh-but-irrelevant hits. Short tokens
     * go for the same reason. Deduplicated, then capped — the cap is a sanity
     * bound against someone pasting a document into the chat, not a real limit.
     */
    fun queryTerms(text: String, maxTerms: Int): List<String> =
        words(text)
            .filter { it.length > 2 }
            .filter { it !in StopWords.ENGLISH }
            .distinct()
            .take(maxTerms)

    /**
     * Tokens for the BM25 index and for scoring against it.
     *
     * Unfiltered on purpose — BM25 handles common terms through IDF, which is
     * the more principled mechanism than a hand-maintained stopword list, and
     * removing them would also shorten `docLength` in a way the length
     * normalisation is not expecting.
     *
     * @param bigrams append adjacent pairs. See [RetrievalConfig.bm25Bigrams]
     *   for why this is worth measuring off.
     */
    fun indexTokens(text: String, bigrams: Boolean = true): List<String> {
        val w = words(text)
        if (!bigrams) return w
        val out = ArrayList<String>(w.size * 2)
        out.addAll(w)
        for (i in 0 until w.size - 1) out.add("${w[i]}_${w[i + 1]}")
        return out
    }

    /**
     * The bigram tokens a query would produce, for probing their corpus
     * document frequency.
     *
     * Bigrams currently get candidate-set `df` because only unigrams are
     * probed — and since bigrams are rarer than unigrams by construction, their
     * candidate-set `df` sits relatively closer to `N`, so the distortion is
     * worst for the most discriminating token class. Exposed so the probe can
     * cover them; whether the extra probes pay for themselves is a question for
     * the eval harness.
     */
    fun queryBigrams(text: String, maxTerms: Int): List<String> {
        val w = words(text).filter { it.length > 2 }
        return (0 until (w.size - 1).coerceAtLeast(0))
            .map { "${w[it]}_${w[it + 1]}" }
            .distinct()
            .take(maxTerms)
    }

    private val WORD_SPLIT = Regex("[^a-z0-9_\\u00C0-\\uFFFF]+")
}
