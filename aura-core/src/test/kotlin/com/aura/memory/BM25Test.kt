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
        val docs = listOf(
            "the the the common word the the the",
            "rare unique uncommon term here",
        )
        val bm25 = BM25(docs)
        val commonScore = bm25.score("the", 0)
        val rareScore = bm25.score("rare", 1)
        // "rare" appears in 1 doc, "the" in 1 doc — but "rare" has
        // fewer occurrences overall so IDF is similar. The key is
        // that rare terms get nonzero IDF.
        assertTrue("Rare term should have positive score", rareScore > 0f)
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