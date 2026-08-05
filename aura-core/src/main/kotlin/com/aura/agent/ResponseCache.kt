package com.aura.agent

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Small LRU cache for repeated simple questions (ported concept from
 * Python Aura's `_get_response_cache`).
 *
 * The agentic loop checks the cache BEFORE running: if the normalized
 * user message is short (no tool-driven work), recent (TTL), and was
 * previously answered without any tool calls, the cached answer is
 * streamed instantly instead of paying a full model round-trip.
 *
 * Key = normalized message + model id. Normalization is case folding,
 * whitespace collapsing, and punctuation stripping — "What time is it?",
 * "what time is it" and "What  time   is it??" all hit the same entry.
 *
 * Thread-safe: [lock] guards all access. LRU order is tracked via an
 * explicit access list (LinkedHashMap accessOrder semantics are fragile
 * across Kotlin override signatures, so eviction is manual and
 * deterministic: the least-recently-accessed key is evicted on overflow).
 */
@Singleton
class ResponseCache @Inject constructor() {

    private data class Entry(
        val answer: String,
        val createdAt: Long,
    )

    private val lock = Any()
    private val cache = HashMap<String, Entry>()
    private val accessOrder = ArrayDeque<String>()

    /**
     * Return a cached answer if present and fresh, else null.
     * @param maxAgeMs entries older than this are treated as misses.
     */
    fun get(key: String, now: Long = System.currentTimeMillis(), maxAgeMs: Long = DEFAULT_TTL_MS): String? =
        synchronized(lock) {
            val entry = cache[key] ?: return null
            if (now - entry.createdAt > maxAgeMs) {
                cache.remove(key)
                accessOrder.remove(key)
                null
            } else {
                // Refresh LRU position
                accessOrder.remove(key)
                accessOrder.addLast(key)
                entry.answer
            }
        }

    /** Store an answer under the key, evicting the LRU entry if full. */
    fun put(key: String, answer: String, now: Long = System.currentTimeMillis()) {
        if (answer.isBlank()) return
        synchronized(lock) {
            if (cache.containsKey(key)) {
                cache[key] = Entry(answer, now)
                accessOrder.remove(key)
                accessOrder.addLast(key)
            } else {
                cache[key] = Entry(answer, now)
                accessOrder.addLast(key)
                while (accessOrder.size > MAX_ENTRIES) {
                    val eldest = accessOrder.removeFirst()
                    cache.remove(eldest)
                }
            }
        }
    }

    /** Number of live entries (test/diagnostics helper). */
    fun size(): Int = synchronized(lock) { cache.size }

    fun clear() = synchronized(lock) {
        cache.clear()
        accessOrder.clear()
    }

    companion object {
        const val MAX_ENTRIES = 50
        const val DEFAULT_TTL_MS = 24L * 60 * 60 * 1000 // 24h
    }
}

/**
 * Normalize a user message for cache keying: lowercase, collapse
 * whitespace, strip punctuation everywhere (not just at the edges).
 * Deterministic and cheap.
 */
internal fun normalizeCacheKey(text: String): String =
    text.trim()
        .lowercase()
        .replace(Regex("[.,!?;:…\"'()\\[\\]{}]"), "")
        .replace(Regex("\\s+"), " ")
        .trim()
