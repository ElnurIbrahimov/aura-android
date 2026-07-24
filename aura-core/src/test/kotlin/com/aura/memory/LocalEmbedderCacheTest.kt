package com.aura.memory

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Regression tests for MEMORY_AUDIT C2.
 *
 * Pre-fix, LocalEmbedder had no cache — every call
 * to `embed("I prefer dark mode")` re-tokenized,
 * re-hashed, and re-normalized the text. For batch
 * operations like `rebuildEmbeddings()` over 500+
 * rows with repeated content, this was real CPU.
 *
 * Post-fix, LocalEmbedder caches by text key with
 * LRU eviction. The cache is safe because the
 * embedder is deterministic.
 */
class LocalEmbedderCacheTest {

    @Test
    fun `same text returns the same cached vector instance`() = runTest {
        val embedder = LocalEmbedder(dim = 384)
        val v1 = embedder.embed("I prefer dark mode")
        val v2 = embedder.embed("I prefer dark mode")
        // Same array instance (not just equal content)
        assertSame("Cache hit should return the same FloatArray", v1, v2)
    }

    @Test
    fun `different text returns different vectors`() = runTest {
        val embedder = LocalEmbedder(dim = 384)
        val v1 = embedder.embed("I prefer dark mode")
        val v2 = embedder.embed("I prefer light mode")
        assertNotSame("Different text must produce different cache entries", v1, v2)
    }

    @Test
    fun `cached vectors are still correct (deterministic)`() = runTest {
        val embedder = LocalEmbedder(dim = 384)
        val text = "the quick brown fox"
        val v1 = embedder.embed(text)
        val v2 = embedder.embed(text)
        // Both are normalized to unit length
        var n1 = 0f
        var n2 = 0f
        for (i in v1.indices) {
            n1 += v1[i] * v1[i]
            n2 += v2[i] * v2[i]
        }
        assertEquals(1.0f, kotlin.math.sqrt(n1), 0.001f)
        assertEquals(1.0f, kotlin.math.sqrt(n2), 0.001f)
        // And element-wise identical (same array)
        for (i in v1.indices) {
            assertEquals(v1[i], v2[i], 0.0001f)
        }
    }
}
