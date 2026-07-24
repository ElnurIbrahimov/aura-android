package com.aura.memory

import com.aura.providers.ProviderKeys
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for [CloudEmbedder] — cloud embedding with local fallback.
 *
 * Uses mocked [OkHttpClient] and [ProviderKeys] so no real network is
 * required.
 */
class CloudEmbedderTest {

    private val jsonMediaType = "application/json".toMediaType()

    /**
     * A realistic 768-dim embedding response from Ollama Cloud.
     * nomic-embed-text is 768-dim (see CloudEmbedderTest.dimension
     * for the full model → dim map). 384 was the OLD wrong default
     * before the B2 fix landed; the test fixture was updated to
     * match the real model dimension.
     */
    private val sampleEmbedding = FloatArray(768) { (it % 100) / 100f + 0.5f }

    // ─── helpers ──────────────────────────────────────────────────────────

    private fun mockHttp(responseBody: String): OkHttpClient {
        val response = Response.Builder()
            .request(Request.Builder().url("https://api.ollama.com/api/embeddings").build())
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body(responseBody.toResponseBody(jsonMediaType))
            .build()

        val call = mockk<Call> {
            every { execute() } returns response
        }

        return mockk {
            every { newCall(any()) } returns call
        }
    }

    private fun mockHttpError(code: Int): OkHttpClient {
        val response = Response.Builder()
            .request(Request.Builder().url("https://api.ollama.com/api/embeddings").build())
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message("Error")
            .body("{\"error\":\"failed\"}".toResponseBody(jsonMediaType))
            .build()

        val call = mockk<Call> {
            every { execute() } returns response
        }

        return mockk {
            every { newCall(any()) } returns call
        }
    }

    private fun mockHttpException(): OkHttpClient {
        val call = mockk<Call> {
            every { execute() } throws RuntimeException("Network error")
        }

        return mockk {
            every { newCall(any()) } returns call
        }
    }

    private fun providerKeys(
        key: kotlin.String?,
        model: kotlin.String = "ollama:nomic-embed-text",
    ): ProviderKeys {
        val pk = mockk<ProviderKeys>(relaxed = true)
        every { pk.keyFor("ollama") } returns key
        every { pk.embeddingModel } returns model
        return pk
    }

    /** Elementwise assert on two FloatArrays with a tolerance. */
    private fun assertFloatArrayEquals(expected: FloatArray, actual: FloatArray, delta: Float = 0.0f) {
        assertEquals(expected.size, actual.size, "array size mismatch")
        for (i in expected.indices) {
            assertEquals(expected[i], actual[i], delta, "index $i")
        }
    }

    // ─── tests ────────────────────────────────────────────────────────────

    @Test
    fun `cloud embedding returns float array`() = runTest {
        val embedBody =
            """{"embedding":[${sampleEmbedding.joinToString(",") { it.toString() }}]}"""
        val httpClient = mockHttp(embedBody)
        val keys = providerKeys(key = "sk-test-key")
        val local = mockk<LocalEmbedder>(relaxed = true)

        val sut = CloudEmbedder(local, keys, httpClient)
        val result = sut.embed("test text")

        // Default model is "ollama:nomic-embed-text" (768-dim). The
        // sampleEmbedding was bumped to 768 to match the real
        // model dimension — see sampleEmbedding definition above.
        assertEquals(768, result.size)
        assertFloatArrayEquals(sampleEmbedding, result, 0.001f)
        // Local fallback must NOT have been called
        coVerify(exactly = 0) { local.embed(any()) }
    }

    @Test
    fun `fallback to local when api key is blank`() = runTest {
        val httpClient = mockHttp("""{"embedding":[]}""")
        val keys = providerKeys(key = null)
        val local = mockk<LocalEmbedder>(relaxed = true)
        coEvery { local.embed(any()) } returns FloatArray(384) { 1f }

        val sut = CloudEmbedder(local, keys, httpClient)
        val result = sut.embed("test text")

        assertEquals(384, result.size)
        assertEquals(1f, result[0])
        coVerify { local.embed("test text") }
    }

    @Test
    fun `fallback to local on http error`() = runTest {
        val httpClient = mockHttpError(500)
        val keys = providerKeys(key = "sk-test-key")
        val local = mockk<LocalEmbedder>(relaxed = true)
        coEvery { local.embed(any()) } returns FloatArray(384) { 2f }

        val sut = CloudEmbedder(local, keys, httpClient)
        val result = sut.embed("test text")

        assertEquals(2f, result[0])
        coVerify { local.embed("test text") }
    }

    @Test
    fun `fallback to local on network exception`() = runTest {
        val httpClient = mockHttpException()
        val keys = providerKeys(key = "sk-test-key")
        val local = mockk<LocalEmbedder>(relaxed = true)
        coEvery { local.embed(any()) } returns FloatArray(384) { 3f }

        val sut = CloudEmbedder(local, keys, httpClient)
        val result = sut.embed("test text")

        assertEquals(3f, result[0])
        coVerify { local.embed("test text") }
    }

    @Test
    fun `cache deduplicates identical text`() = runTest {
        val embedBody =
            """{"embedding":[${
                sampleEmbedding.joinToString(",") { it.toString() }
            }]}"""
        val httpClient = mockHttp(embedBody)
        val keys = providerKeys(key = "sk-test-key")
        val local = mockk<LocalEmbedder>(relaxed = true)

        val sut = CloudEmbedder(local, keys, httpClient)

        // First call → goes to cloud
        val first = sut.embed("duplicate text")
        // Second call → should hit cache
        val second = sut.embed("duplicate text")

        assertFloatArrayEquals(first, second)
        // newCall must have been called exactly once (the second call hits the cache)
        verify(exactly = 1) { httpClient.newCall(any()) }
        coVerify(exactly = 0) { local.embed(any()) }
    }

    @Test
    fun `different texts produce different cache entries`() = runTest {
        val embedBodyA =
            """{"embedding":[${
                sampleEmbedding.joinToString(",") { it.toString() }
            }]}"""
        val embedBodyB =
            """{"embedding":[${
                FloatArray(768) { (it % 100 + 50) / 100f + 0.5f }.joinToString(",") { it.toString() }
            }]}"""

        // Return different responses for different request bodies by checking
        // the prompt field in a simple way — we mock sequential calls instead.
        val callA = mockk<Call> {
            every { execute() } returns Response.Builder()
                .request(Request.Builder().url("https://api.ollama.com/api/embeddings").build())
                .protocol(Protocol.HTTP_1_1)
                .code(200).message("OK")
                .body(embedBodyA.toResponseBody(jsonMediaType))
                .build()
        }
        val callB = mockk<Call> {
            every { execute() } returns Response.Builder()
                .request(Request.Builder().url("https://api.ollama.com/api/embeddings").build())
                .protocol(Protocol.HTTP_1_1)
                .code(200).message("OK")
                .body(embedBodyB.toResponseBody(jsonMediaType))
                .build()
        }

        val httpClient = mockk<OkHttpClient> {
            every { newCall(any()) } returnsMany listOf(callA, callB)
        }
        val keys = providerKeys(key = "sk-test-key")
        val local = mockk<LocalEmbedder>(relaxed = true)

        val sut = CloudEmbedder(local, keys, httpClient)

        val a = sut.embed("text A")
        val b = sut.embed("text B")

        // Should be different vectors
        var diffs = 0
        for (i in a.indices) {
            if (a[i] != b[i]) diffs++
        }
        assertTrue(diffs > 0, "Different texts should produce different embeddings")
        verify(exactly = 2) { httpClient.newCall(any()) }
    }

    // ── dimension() regression (MEMORY_AUDIT B2) ────────────────────────
    // Before the fix, dimension() always returned 384 regardless of
    // the configured embedding model. Picking a non-384-dim cloud
    // model (e.g. nomic-embed-text = 768) caused every cloud call
    // to fail the dimension validation in cloudEmbed() and fall
    // back to the local embedder. So users picking any cloud model
    // silently got local 384-dim embeddings.

    @Test
    fun `dimension returns 384 for local-hash-v2 and 384-dim models`() {
        for (model in listOf(
            "local-hash-v2",
            "ollama:all-minilm:l6-v2",
            "ollama:snowflake-arctic-embed:110m",
            "ollama:bge-small-en-v1.5",
        )) {
            val keys = mockk<ProviderKeys>(relaxed = true) {
                every { embeddingModel } returns model
            }
            val local = mockk<LocalEmbedder>(relaxed = true)
            val sut = CloudEmbedder(local, keys, mockk(relaxed = true))
            assertEquals(384, sut.dimension(), "model '$model' should return 384")
        }
    }

    @Test
    fun `dimension returns 768 for nomic-embed-text and 768-dim models`() {
        for (model in listOf(
            "ollama:nomic-embed-text",
            "ollama:nomic-embed-text:v1.5",
            "ollama:all-mpnet-base-v2",
            "ollama:mxbai-embed-large",
            "ollama:bge-base-en-v1.5",
        )) {
            val keys = mockk<ProviderKeys>(relaxed = true) {
                every { embeddingModel } returns model
            }
            val local = mockk<LocalEmbedder>(relaxed = true)
            val sut = CloudEmbedder(local, keys, mockk(relaxed = true))
            assertEquals(768, sut.dimension(), "model '$model' should return 768")
        }
    }

    @Test
    fun `dimension returns 1024 for bge-large and 1024-dim models`() {
        for (model in listOf(
            "ollama:bge-large",
            "ollama:bge-large-en-v1.5",
            "ollama:bge-m3",
            "ollama:cohere-embed-multilingual-v3",
        )) {
            val keys = mockk<ProviderKeys>(relaxed = true) {
                every { embeddingModel } returns model
            }
            val local = mockk<LocalEmbedder>(relaxed = true)
            val sut = CloudEmbedder(local, keys, mockk(relaxed = true))
            assertEquals(1024, sut.dimension(), "model '$model' should return 1024")
        }
    }

    @Test
    fun `dimension returns 1536 for OpenAI text-embedding-3-small`() {
        val keys = mockk<ProviderKeys>(relaxed = true) {
            every { embeddingModel } returns "ollama:text-embedding-3-small"
        }
        val local = mockk<LocalEmbedder>(relaxed = true)
        val sut = CloudEmbedder(local, keys, mockk(relaxed = true))
        assertEquals(1536, sut.dimension())
    }

    @Test
    fun `dimension returns 3072 for OpenAI text-embedding-3-large`() {
        val keys = mockk<ProviderKeys>(relaxed = true) {
            every { embeddingModel } returns "ollama:text-embedding-3-large"
        }
        val local = mockk<LocalEmbedder>(relaxed = true)
        val sut = CloudEmbedder(local, keys, mockk(relaxed = true))
        assertEquals(3072, sut.dimension())
    }

    @Test
    fun `dimension defaults to local embedder dimension when model is blank`() {
        val keys = mockk<ProviderKeys>(relaxed = true) {
            every { embeddingModel } returns ""
        }
        val local = mockk<LocalEmbedder> {
            every { dimension() } returns 512 // hypothetical
        }
        val sut = CloudEmbedder(local, keys, mockk(relaxed = true))
        assertEquals(512, sut.dimension())
    }

    @Test
    fun `dimension defaults to 384 with warning for unknown model`() {
        val keys = mockk<ProviderKeys>(relaxed = true) {
            every { embeddingModel } returns "ollama:totally-new-model-2026"
        }
        val local = mockk<LocalEmbedder>(relaxed = true)
        val sut = CloudEmbedder(local, keys, mockk(relaxed = true))
        // Unknown models fall back to 384 with a Log.w warning —
        // this is a safety net so the embedding pipeline doesn't
        // break for new Ollama catalog entries. The user sees the
        // Log.w in logcat and can add the model to the when{}.
        assertEquals(384, sut.dimension())
    }

    @Test
    fun `cloud embed accepts 768-dim response for nomic-embed-text`() = runTest {
        // Regression test for the dimension validation in cloudEmbed().
        // Before the dimension() fix, this scenario would:
        //  1. cloudEmbed() returns 768-dim vec
        //  2. validation: vec.size (768) != dimension() (384)
        //  3. throws RuntimeException → falls back to local
        //  4. User picked nomic-embed-text but got local 384-dim.
        // After the fix, dimension() returns 768 for nomic-embed-text,
        // the validation passes, and the cloud 768-dim vec is used.
        val vec768 = FloatArray(768) { (it % 50) / 50f }
        val embedBody = """{"embedding":[${vec768.joinToString(",") { it.toString() }}]}"""
        val httpClient = mockHttp(embedBody)
        // Use the production model id format: "<provider>:<model>".
        // The embed() parser splits on ':' and takes the model part
        // after the "ollama" prefix.
        val keys = providerKeys(key = "sk-test-key", model = "ollama:nomic-embed-text")
        val local = mockk<LocalEmbedder>(relaxed = true)

        val sut = CloudEmbedder(local, keys, httpClient)
        val result = sut.embed("test text")

        assertEquals(768, result.size, "should accept 768-dim cloud embedding for nomic-embed-text")
        assertFloatArrayEquals(vec768, result, 0.001f)
        coVerify(exactly = 0) { local.embed(any()) }
    }
}
