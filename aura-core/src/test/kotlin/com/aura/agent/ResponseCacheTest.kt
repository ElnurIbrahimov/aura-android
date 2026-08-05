package com.aura.agent

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ResponseCacheTest {

    @Test
    fun `put and get roundtrip`() {
        val cache = ResponseCache()
        cache.put("q1", "The capital of France is Paris.")
        assertEquals("The capital of France is Paris.", cache.get("q1"))
    }

    @Test
    fun `normalized keys hit the same entry`() {
        val cache = ResponseCache()
        cache.put(normalizeCacheKey("What time is it?"), "It's 14:30.")
        assertEquals("It's 14:30.", cache.get(normalizeCacheKey("what time is it")))
        assertEquals("It's 14:30.", cache.get(normalizeCacheKey("What  time   is it??")))
    }

    @Test
    fun `expired entries are treated as misses`() {
        val cache = ResponseCache()
        val now = 1_000_000L
        cache.put("q1", "answer", now = now)
        // 25h later — past the 24h TTL
        assertNull(cache.get("q1", now = now + 25L * 60 * 60 * 1000, maxAgeMs = ResponseCache.DEFAULT_TTL_MS))
    }

    @Test
    fun `blank answers are not stored`() {
        val cache = ResponseCache()
        cache.put("q1", "   ")
        assertNull(cache.get("q1"))
    }

    @Test
    fun `LRU evicts oldest access at capacity`() {
        val cache = ResponseCache()
        val now = 1_000_000L
        // Fill to capacity
        for (i in 0 until ResponseCache.MAX_ENTRIES) {
            cache.put("key$i", "answer $i", now = now + i)
        }
        assertEquals(ResponseCache.MAX_ENTRIES, cache.size())
        // Access key0 (oldest) to refresh it, then add one more — key1 should evict
        cache.get("key0", now = now + ResponseCache.MAX_ENTRIES + 1)
        cache.put("overflow", "overflow answer", now = now + ResponseCache.MAX_ENTRIES + 2)
        assertEquals(ResponseCache.MAX_ENTRIES, cache.size())
        assertNull(cache.get("key1", now = now + ResponseCache.MAX_ENTRIES + 3))
        assertEquals("answer 0", cache.get("key0", now = now + ResponseCache.MAX_ENTRIES + 3))
        assertEquals("overflow answer", cache.get("overflow", now = now + ResponseCache.MAX_ENTRIES + 3))
    }

    @Test
    fun `normalizeCacheKey is deterministic`() {
        val a = normalizeCacheKey("Hello, World!")
        val b = normalizeCacheKey("hello world")
        assertEquals(a, b)
        assertTrue(a.isNotBlank())
    }
}
