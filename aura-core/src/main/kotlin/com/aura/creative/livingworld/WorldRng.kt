package com.aura.creative.livingworld

/**
 * Deterministic randomness for the world engine.
 *
 * **Content-keyed substreams, not one sequential generator.** With a single
 * `Random` consumed in iteration order, the value any decision receives depends
 * on how many draws every earlier decision happened to take and in what order
 * the collection was walked. Add one rule and every later draw in that tick
 * shifts, so a rewind or a fork stops matching the history it came from. Keying
 * each draw by what it is deciding — this pool, this rule, this subject — makes
 * every decision's randomness independent of everything around it.
 *
 * SplitMix64's finalizer is used as the mixing function: it is a bijection with
 * good avalanche, it needs no state, and it is a handful of instructions.
 */
object WorldRng {
    private val GOLDEN: Long = 0x9E3779B97F4A7C15uL.toLong()
    private val MIX_A: Long = 0xBF58476D1CE4E5B9uL.toLong()
    private val MIX_B: Long = 0x94D049BB133111EBuL.toLong()

    private val FNV_OFFSET: Long = 0xCBF29CE484222325uL.toLong()
    private const val FNV_PRIME: Long = 0x100000001B3L

    fun mix64(value: Long): Long {
        var z = value + GOLDEN
        z = (z xor (z ushr 30)) * MIX_A
        z = (z xor (z ushr 27)) * MIX_B
        return z xor (z ushr 31)
    }

    /** The root seed for one tick of one branch. */
    fun tickSeed(rootSeed: Long, branchSalt: Long, tick: Long): Long =
        mix64(rootSeed xor branchSalt xor mix64(tick))

    /**
     * An independent stream for one decision inside a tick. [key] must describe
     * *what is being decided* (`"pool:territory"`, `"rule:famine:house_vare"`)
     * and must not include a counter or an index into a collection.
     */
    fun substream(tickSeed: Long, key: String): Long = mix64(tickSeed xor stableHash64(key))

    /**
     * FNV-1a over UTF-8 bytes. Implemented here rather than using
     * `String.hashCode()` so the values cannot move with a platform change —
     * a rehash would silently rewrite every world's future.
     */
    fun stableHash64(text: String): Long {
        var hash = FNV_OFFSET
        for (byte in text.toByteArray(Charsets.UTF_8)) {
            hash = hash xor (byte.toLong() and 0xFF)
            hash *= FNV_PRIME
        }
        return hash
    }

    /** A value in `[0, bound)`. */
    fun bounded(seed: Long, bound: Long): Long {
        if (bound <= 1L) return 0L
        val remainder = mix64(seed) % bound
        return if (remainder < 0L) remainder + bound else remainder
    }

    /**
     * Pick an index with probability proportional to its weight. Negative
     * weights count as zero; an all-zero list picks index 0 rather than
     * failing, so a contested claim between two bankrupt factions still
     * resolves.
     */
    fun weightedPick(seed: Long, weights: List<Long>): Int {
        if (weights.isEmpty()) return -1
        val total = weights.sumOf { if (it > 0L) it else 0L }
        if (total <= 0L) return 0
        val roll = bounded(seed, total)
        var accumulated = 0L
        for (index in weights.indices) {
            accumulated += if (weights[index] > 0L) weights[index] else 0L
            if (roll < accumulated) return index
        }
        return weights.lastIndex
    }
}
