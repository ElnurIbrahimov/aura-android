package com.aura.memory

/**
 * Embedding interface. Produces a normalized FloatArray representation of text.
 *
 * v1: [LocalEmbedder] uses deterministic pseudo-embedding (SHA-256 + n-grams).
 * v2 (this task): [CloudEmbedder] wraps [LocalEmbedder] as fallback and calls
 * Ollama Cloud's embeddings API when a key is configured.
 */
/**
 * A vector plus the identity of whatever actually produced it.
 *
 * The point is that [modelId] describes the vector in hand, not the embedder's
 * configuration — see [Embedder.embedTagged].
 */
data class Embedding(
    val vector: FloatArray,
    val modelId: String,
    val dim: Int,
) {
    // FloatArray uses identity equality, which would make two Embeddings with
    // the same contents unequal. Compare on the tag, which is what callers
    // actually branch on.
    override fun equals(other: Any?): Boolean =
        this === other || (other is Embedding && modelId == other.modelId && dim == other.dim &&
            vector.contentEquals(other.vector))

    override fun hashCode(): Int = 31 * (31 * modelId.hashCode() + dim) + vector.contentHashCode()
}

interface Embedder {

    /** Embed [text] into a unit-normalized vector. */
    suspend fun embed(text: String): FloatArray

    /** Identifier of the model/method used (e.g. "local-hash-v2", "ollama:nomic-embed-text"). */
    fun modelId(): kotlin.String

    /** Dimensionality of the vectors produced by [embed]. */
    fun dimension(): Int

    /**
     * Embed [text] and report which model ACTUALLY produced the vector.
     *
     * [modelId] and [dimension] describe what this embedder is configured to
     * do; on a fallback path they describe what it tried to do. `CloudEmbedder`
     * returns a 384-dim local vector when the network call fails, but
     * `MemoryStore.store` wrote `embeddingModel = "ollama:nomic-embed-text"`
     * and `embeddingVersion = 768` over it regardless — so the row claimed 768
     * dimensions while holding 384, and nothing could tell the difference
     * later.
     *
     * The default is correct for any embedder with a single path. Override it
     * wherever the produced vector might not be the advertised one.
     */
    suspend fun embedTagged(text: String): Embedding =
        Embedding(embed(text), modelId(), dimension())

    /**
     * Whether a stored row's `embeddingModel` matches what this embedder
     * produces now.
     *
     * On the interface deliberately. Four subsystems hold vectors from this
     * embedder — memories, conversations, dream clustering, document chunks —
     * and four independent `!=` comparisons is how the FTS-schema duplication
     * problem started.
     */
    fun isCurrent(rowModelId: String?): Boolean = rowModelId == modelId()

    companion object {
        fun toBytes(vec: FloatArray): ByteArray {
            val out = ByteArray(vec.size * 4)
            for (i in vec.indices) {
                val bits = java.lang.Float.floatToRawIntBits(vec[i])
                out[i * 4] = (bits ushr 24).toByte()
                out[i * 4 + 1] = (bits ushr 16).toByte()
                out[i * 4 + 2] = (bits ushr 8).toByte()
                out[i * 4 + 3] = bits.toByte()
            }
            return out
        }

        fun fromBytes(bytes: ByteArray): FloatArray {
            val n = bytes.size / 4
            val out = FloatArray(n)
            for (i in 0 until n) {
                val b0 = bytes[i * 4].toInt() and 0xff
                val b1 = bytes[i * 4 + 1].toInt() and 0xff
                val b2 = bytes[i * 4 + 2].toInt() and 0xff
                val b3 = bytes[i * 4 + 3].toInt() and 0xff
                val bits = (b0 shl 24) or (b1 shl 16) or (b2 shl 8) or b3
                out[i] = java.lang.Float.intBitsToFloat(bits)
            }
            return out
        }
    }
}
