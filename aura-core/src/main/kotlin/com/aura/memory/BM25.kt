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
    private val k1: Float = 1.2f,
    private val b: Float = 0.75f,
) {
    private data class DocStats(val tokens: List<String>, val length: Int)

    private val docs: List<DocStats>
    private val avgDocLength: Float
    private val idf: Map<String, Float>

    init {
        docs = documents.map { doc ->
            val tokens = tokenize(doc)
            DocStats(tokens, tokens.size)
        }
        avgDocLength = if (docs.isNotEmpty()) {
            docs.sumOf { it.length }.toFloat() / docs.size
        } else 0f

        // IDF: log((N - df + 0.5) / (df + 0.5)), floored at 0
        val n = docs.size.toFloat()
        val df = mutableMapOf<String, Int>()
        for (doc in docs) {
            val unique = doc.tokens.toSet()
            for (token in unique) {
                df[token] = (df[token] ?: 0) + 1
            }
        }
        idf = df.mapValues { (_, freq) ->
            val raw = ln((n - freq + 0.5f) / (freq + 0.5f))
            // Floor at a small positive value instead of 0 so terms
            // that appear in multiple docs still contribute to the
            // score. This is important for small corpora (personal
            // memory stores with <100 docs) where the standard BM25
            // floor at 0 would zero out common query terms.
            max(0.1f, raw)
        }
    }

    /**
     * Score a single document against [query]. Returns a raw BM25 score
     * (not normalized). Higher is better.
     */
    fun score(query: String, docIndex: Int): Float {
        if (docIndex < 0 || docIndex >= docs.size) return 0f
        val queryTokens = tokenize(query)
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
     * Normalize a raw BM25 score to 0-1 by dividing by the max
     * possible score (the score if every query term had max IDF and
     * perfect TF). This is an approximation — good enough for RRF
     * fusion where only the relative ranking matters.
     */
    fun normalizedScore(query: String, docIndex: Int): Float {
        val raw = score(query, docIndex)
        if (raw <= 0f) return 0f
        // Max possible: sum of IDF for all query terms
        val queryTokens = tokenize(query)
        val maxPossible = queryTokens.mapNotNull { idf[it] }.sum()
        if (maxPossible <= 0f) return 0f
        return (raw / maxPossible).coerceIn(0f, 1f)
    }

    companion object {
        /**
         * Tokenize text for BM25: lowercase, split on non-alphanumeric,
         * filter empty tokens, add bigrams for better signal.
         */
        fun tokenize(text: String): List<String> {
            val lower = text.lowercase()
            val words = lower.split(Regex("[^a-z0-9_\\u00C0-\\uFFFF]+"))
                .filter { it.isNotEmpty() }
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