package com.aura.memory

import java.util.concurrent.atomic.AtomicInteger

/**
 * An [Embedder] that reports how many times it was asked to embed.
 *
 * On an interface rather than read off [FakeEmbedder] directly, so the eval
 * harness can report embed counts for any test embedder. It was reading a
 * `FakeEmbedder`-typed field, which meant the count silently came back zero for
 * every other embedder — a cost signal that reports zero is worse than none.
 */
interface CountingEmbedder {
    val callCount: AtomicInteger
}

/**
 * Deterministic, non-semantic [Embedder] for unit tests. Produces a
 * 384-dim unit-normalized vector from a hash of every character in
 * the input. Two test inputs that differ in any character get a
 * different vector; identical inputs get identical vectors.
 *
 * The production embedder is [CloudEmbedder] (or was
 * [LocalEmbedder] before we removed it). This class is for tests
 * that need an [Embedder] instance but should not make network
 * calls.
 */
class FakeEmbedder(
    val dim: Int = 384,
    override val callCount: AtomicInteger = AtomicInteger(0),
) : Embedder, CountingEmbedder {
    override fun modelId(): kotlin.String = "fake-embedder"
    override fun dimension(): Int = dim

    override suspend fun embed(text: String): FloatArray {
        callCount.incrementAndGet()
        if (text.isBlank()) return FloatArray(dim)
        val vec = FloatArray(dim)
        for ((i, c) in text.withIndex()) {
            val code = c.code.toLong() and 0xffff
            val seed = (code shl 8) or i.toLong()
            for (k in 0 until 4) {
                val h = (seed * 2654435761L + k).toInt()
                val idx = (h and 0x7fffffff) % dim
                val sign = if ((h ushr 16) and 1 == 1) 1f else -1f
                vec[idx] += sign
            }
        }
        var norm = 0f
        for (v in vec) norm += v * v
        val len = kotlin.math.sqrt(norm)
        if (len > 0f) for (i in vec.indices) vec[i] /= len
        return vec
    }
}
