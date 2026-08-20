package com.aura.memory.onnx

import com.aura.memory.EmbedKind
import com.aura.memory.Embedder
import com.aura.memory.Embedding

/**
 * Uses the on-device model when it is there, and says so when it is not.
 *
 * Three embedders exist and they are not interchangeable. [OnDeviceEmbedder] is a real
 * semantic model that needs a 137 MB download; `CloudEmbedder` needs a key and a network;
 * `LocalEmbedder` is a hash sketch that always works and understands nothing. This picks
 * per call, because the download can complete while the app is running and the next
 * embedding should use the better model without a restart.
 *
 * The important property is honesty about which one answered. [embedTagged] reports the
 * model that actually produced the vector, `MemoryStore.store` writes that tag onto the
 * row, and `Embedder.isCurrent` compares it back — so a hash vector written during the
 * download is excluded from cosine scoring rather than silently compared against nomic
 * vectors in a different space, and `rebuildEmbeddings` finds and repairs it later.
 * `CloudEmbedder`'s KDoc records what happens when that tagging lies: rows become invisible
 * to `countNeedingReembed` and are never repaired.
 *
 * [modelId] and [dimension] describe what this is configured to do *right now*, which
 * changes the day the download lands. That is deliberate and is what makes every previously
 * stored vector correctly become stale at that moment.
 */
class RoutedEmbedder(
    private val onDevice: OnDeviceEmbedder,
    private val fallback: Embedder,
    private val modelStore: EmbeddingModelStore,
) : Embedder {

    private fun useOnDevice(): Boolean = modelStore.isReady() && onDevice.isAvailable()

    override fun modelId(): String =
        if (useOnDevice()) onDevice.modelId() else fallback.modelId()

    override fun dimension(): Int =
        if (useOnDevice()) onDevice.dimension() else fallback.dimension()

    override suspend fun embed(text: String): FloatArray = embed(text, EmbedKind.DOCUMENT)

    /**
     * Embed for a specific role.
     *
     * The role only reaches [OnDeviceEmbedder], because only it is asymmetric. The fallback
     * embedders take the bare text — prefixing a hash sketch's input would change its
     * vector for no reason, and a cloud model that was not trained with these prefixes
     * would be handed a phrase it has never seen.
     */
    suspend fun embed(text: String, kind: EmbedKind): FloatArray =
        if (useOnDevice()) {
            runCatching { onDevice.embed(text, kind) }
                .onFailure {
                    // A model that fails mid-session must not take recall down with it. The
                    // fallback is worse, and the tagging below records that it was used, so
                    // the row is repaired rather than left wrong.
                    android.util.Log.w(TAG, "on-device embed failed, falling back: ${it.message}", it)
                }
                .getOrElse { fallback.embed(text) }
        } else {
            fallback.embed(text)
        }

    override suspend fun embedTagged(text: String): Embedding = embedTagged(text, EmbedKind.DOCUMENT)

    /** Embed and report which model actually produced the vector. */
    suspend fun embedTagged(text: String, kind: EmbedKind): Embedding {
        if (!useOnDevice()) return fallback.embedTagged(text)
        return runCatching {
            val vec = onDevice.embed(text, kind)
            Embedding(vec, onDevice.modelId(), vec.size)
        }.getOrElse {
            android.util.Log.w(TAG, "on-device embed failed, falling back: ${it.message}", it)
            fallback.embedTagged(text)
        }
    }

    private companion object {
        const val TAG = "RoutedEmbedder"
    }
}
