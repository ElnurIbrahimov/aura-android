package com.aura.memory.onnx

import com.aura.memory.EmbedKind
import com.aura.memory.Embedder
import com.aura.memory.Embedding
import com.aura.memory.RetrievalConfig
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Which embedder answered, and whether the settings followed it.
 *
 * These two have to move together or retrieval gets worse rather than better. The eval
 * measured the pairing: a semantic model at the hash-era defaults buys +0.011 nDCG@10 on
 * paraphrase queries, and the semantic settings applied to hash vectors buy +0.004 — the
 * `vectorPoolSize` arm was measured OFF originally for exactly that reason, scoring 0.4837
 * against 0.7976 when fed hash noise. Only together do they buy +0.311.
 *
 * So the failure this guards is not a crash. It is the 137 MB model finishing its download
 * and retrieval quietly getting *worse*, because the settings still assume a hash — or the
 * settings switching while the vectors are still hashes, which is worse again.
 */
class RoutedEmbedderTest {

    private val hash = object : Embedder {
        override suspend fun embed(text: String) = FloatArray(384) { 0.1f }
        override fun modelId() = "local-hash-v2"
        override fun dimension() = 384
    }

    private fun store(ready: Boolean) = mockk<EmbeddingModelStore>(relaxed = true).also {
        every { it.isReady() } returns ready
    }

    private fun onDevice(available: Boolean) = mockk<OnDeviceEmbedder>(relaxed = true).also {
        every { it.isAvailable() } returns available
        every { it.modelId() } returns OnDeviceEmbedder.MODEL_ID
        every { it.dimension() } returns 768
    }

    @Test
    fun `without the model it reports the fallback, not the model it wishes it had`() {
        val router = RoutedEmbedder(onDevice(false), hash, store(false))

        assertEquals("local-hash-v2", router.modelId())
        assertEquals(384, router.dimension())
    }

    @Test
    fun `with the model it reports the model`() {
        val router = RoutedEmbedder(onDevice(true), hash, store(true))

        assertEquals(OnDeviceEmbedder.MODEL_ID, router.modelId())
        assertEquals(768, router.dimension())
    }

    @Test
    fun `a half-downloaded model is not used`() {
        // The store says no even though the embedder object exists. Loading a truncated
        // ONNX file fails deep inside the runtime with nothing useful to say, so the
        // decision is made on the file, not on the object.
        val router = RoutedEmbedder(onDevice(true), hash, store(false))

        assertEquals("local-hash-v2", router.modelId())
    }

    @Test
    fun `a vector made by the fallback is tagged as the fallback`() = runTest {
        // The property everything downstream rests on. MemoryStore writes this tag onto
        // the row and Embedder.isCurrent compares it back, so a hash vector written during
        // the download is excluded from cosine scoring and repaired later. Tagging it as
        // the good model instead would make the row invisible to countNeedingReembed and
        // it would never be repaired — CloudEmbedder's KDoc records that exact bug.
        val router = RoutedEmbedder(onDevice(false), hash, store(false))

        val tagged: Embedding = router.embedTagged("anything")

        assertEquals("local-hash-v2", tagged.modelId)
        assertEquals(384, tagged.dim)
    }

    @Test
    fun `the settings follow the embedder, in both directions`() {
        // Neither half is useful without the other, and each is harmful with the wrong one.
        assertNotEquals(
            RetrievalConfig.DEFAULT.vectorPoolSize,
            RetrievalConfig.SEMANTIC.vectorPoolSize,
            "the semantic preset must open the vector pool — at 0 a memory sharing no word " +
                "with the query can never even be a candidate",
        )
        assertEquals(0, RetrievalConfig.DEFAULT.vectorPoolSize, "hash vectors in selection are noise")
        assertEquals(25, RetrievalConfig.SEMANTIC.vectorPoolSize)

        assertEquals(0.15f, RetrievalConfig.DEFAULT.minRelevance, "three sigma of a 384-dim hash's noise")
        assertEquals(0.50f, RetrievalConfig.SEMANTIC.minRelevance, "swept per model; nomic peaks here")

        assertTrue(
            RetrievalConfig.SEMANTIC.weights.recency < RetrievalConfig.SEMANTIC.weights.vector,
            "the semantic preset must out-weigh the signals that know nothing about the query",
        )
        assertEquals(
            1f, RetrievalConfig.DEFAULT.weights.recency,
            "the default is LEGACY — every signal at 1.0, which nobody chose",
        )
    }

    @Test
    fun `the query and document roles reach the model that cares about them`() = runTest {
        // nomic is asymmetric and the fallbacks are not. A hash sketch handed
        // "search_query: " would embed those two words as content, changing its vector for
        // no reason, so the role stops at the router when the model is not in use.
        val seen = mutableListOf<String>()
        val recording = object : Embedder {
            override suspend fun embed(text: String): FloatArray {
                seen += text
                return FloatArray(384)
            }
            override fun modelId() = "local-hash-v2"
            override fun dimension() = 384
        }

        RoutedEmbedder(onDevice(false), recording, store(false)).embed("a question", EmbedKind.QUERY)

        assertEquals(listOf("a question"), seen, "the prefix must not reach a symmetric embedder")
    }
}
