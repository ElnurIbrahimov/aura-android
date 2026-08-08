package com.aura.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BM25Test {

    @Test
    fun `empty corpus returns empty ranking`() {
        val bm25 = BM25(emptyList())
        assertTrue(bm25.rank("test", 10).isEmpty())
    }

    @Test
    fun `exact match scores higher than no match`() {
        val docs = listOf(
            "Kotlin coroutine dispatchers on Dispatchers IO",
            "Python async event loop with asyncio",
            "How to bake a chocolate cake from scratch",
        )
        val bm25 = BM25(docs)
        val kotlinScore = bm25.score("kotlin coroutines", 0)
        val cakeScore = bm25.score("kotlin coroutines", 2)
        assertTrue("Kotlin doc should score higher than cake doc", kotlinScore > cakeScore)
    }

    @Test
    fun `rank returns sorted results`() {
        val docs = listOf(
            "Kotlin coroutines and Dispatchers IO",
            "Kotlin flows and channels",
            "Python asyncio event loop",
        )
        val bm25 = BM25(docs)
        val results = bm25.rank("kotlin", 3)
        assertTrue("Should return at least 2 results", results.size >= 2)
        // Both Kotlin docs should rank higher than Python doc
        assertTrue("First result should be a Kotlin doc", results[0].first < 2)
        assertTrue("Scores should be descending", results[0].second >= results[1].second)
    }

    @Test
    fun `topK limits results`() {
        val docs = (1..10).map { "document $it about kotlin" }
        val bm25 = BM25(docs)
        val results = bm25.rank("kotlin", 3)
        assertEquals(3, results.size)
    }

    @Test
    fun `normalized score is between 0 and 1`() {
        val docs = listOf(
            "I prefer dark mode in all my editors",
            "The quick brown fox jumps over the lazy dog",
        )
        val bm25 = BM25(docs)
        val norm = bm25.normalizedScore("dark mode", 0)
        assertTrue("Normalized score should be > 0", norm > 0f)
        assertTrue("Normalized score should be <= 1", norm <= 1f)
    }

    @Test
    fun `no query terms in doc returns 0`() {
        val docs = listOf("The weather is nice today")
        val bm25 = BM25(docs)
        assertEquals(0f, bm25.score("kotlin programming", 0), 0.001f)
    }

    @Test
    fun `empty query returns 0`() {
        val docs = listOf("Some content here")
        val bm25 = BM25(docs)
        assertEquals(0f, bm25.score("", 0), 0.001f)
    }

    @Test
    fun `bigrams improve matching`() {
        val docs = listOf(
            "I love Kotlin coroutine dispatchers",
            "I love Python async dispatchers",
        )
        val bm25 = BM25(docs)
        // "Kotlin coroutine" as a phrase should rank the Kotlin doc higher
        val kotlinScore = bm25.score("kotlin coroutine", 0)
        val pythonScore = bm25.score("kotlin coroutine", 1)
        assertTrue("Kotlin doc should score higher for 'kotlin coroutine'", kotlinScore > pythonScore)
    }

    @Test
    fun `IDF weights rare terms higher`() {
        // Previously this asserted only `rareScore > 0f`, which held whether or
        // not IDF discriminated at all — so it passed happily through the years
        // when MemoryStore handed BM25 a pre-filtered candidate list as its
        // "corpus" and every query term collapsed to the IDF floor. It has to
        // compare a rare term against a common one to mean anything.
        val docs = listOf("rare term here", "common term also", "common term again")
        val bm25 = BM25(
            docs,
            corpusSize = 1000,
            corpusDocFreq = mapOf("rare" to 1, "common" to 900),
        )

        val rareScore = bm25.score("rare", 0)
        val commonScore = bm25.score("common", 1)

        assertTrue("Rare term should have positive score", rareScore > 0f)
        assertTrue(
            "A term in 1 of 1000 docs must outweigh one in 900 of 1000 (rare=$rareScore common=$commonScore)",
            rareScore > commonScore,
        )
    }

    @Test
    fun `corpus statistics beat candidate-set statistics`() {
        // The exact defect. The candidate list is what a LIKE/MATCH prefilter
        // returns: every row contains the query term, so a candidate-set df
        // makes df ~= N, ln((N - df + 0.5) / (df + 0.5)) goes negative, and the
        // floor gives every query term the same weight. Supplying the real
        // corpus counts restores the ordering.
        val candidates = listOf("kotlin and common", "kotlin and common", "kotlin and common")

        val candidateSetIdf = BM25(candidates)
        assertEquals(
            "without corpus stats both terms tie at the IDF floor",
            candidateSetIdf.score("kotlin", 0),
            candidateSetIdf.score("common", 0),
            0.0001f,
        )

        val corpusIdf = BM25(
            candidates,
            corpusSize = 500,
            corpusDocFreq = mapOf("kotlin" to 3, "common" to 480),
        )
        assertTrue(
            "with corpus stats the discriminating term must win",
            corpusIdf.score("kotlin", 0) > corpusIdf.score("common", 0),
        )
    }

    @Test
    fun `normalizedScore does not saturate at 1 for a partial match`() {
        // score() multiplies each term by (k1 + 1) * tf / denom, but the
        // divisor was the bare sum of IDF — so raw routinely exceeded it and
        // coerceIn clamped the result to exactly 1.0. Combined with the IDF
        // collapse that flattened textScore across most candidates, and since
        // Retrieval.rankCandidates fuses by RANK, a tie is the same as no
        // signal at all.
        val docs = listOf("kotlin coroutines structured concurrency guide", "kotlin only")
        val bm25 = BM25(docs, corpusSize = 100, corpusDocFreq = mapOf("kotlin" to 50, "coroutines" to 3))

        val partial = bm25.normalizedScore("kotlin coroutines", 1) // doc has "kotlin", not "coroutines"

        assertTrue("a partial match must not normalize to a perfect 1.0, got $partial", partial < 1.0f)
        assertTrue("a partial match must still score above zero, got $partial", partial > 0f)
    }

    @Test
    fun `tokenize produces words and bigrams`() {
        val tokens = BM25.tokenize("hello world test")
        assertTrue(tokens.contains("hello"))
        assertTrue(tokens.contains("world"))
        assertTrue(tokens.contains("test"))
        assertTrue(tokens.contains("hello_world"))
        assertTrue(tokens.contains("world_test"))
    }

    @Test
    fun `tokenize handles empty string`() {
        val tokens = BM25.tokenize("")
        assertTrue(tokens.isEmpty())
    }

    @Test
    fun `tokenize lowercases input`() {
        val tokens = BM25.tokenize("KOTLIN Coroutines")
        assertTrue(tokens.contains("kotlin"))
        assertTrue(tokens.contains("coroutines"))
    }

    @Test
    fun `multiple query terms accumulate score`() {
        val docs = listOf(
            "Kotlin coroutines with Dispatchers IO for async programming",
            "Kotlin basics",
        )
        val bm25 = BM25(docs)
        val multiTermScore = bm25.score("kotlin coroutines dispatchers", 0)
        val singleTermScore = bm25.score("kotlin", 0)
        assertTrue("Multi-term query should score higher", multiTermScore > singleTermScore)
    }
}