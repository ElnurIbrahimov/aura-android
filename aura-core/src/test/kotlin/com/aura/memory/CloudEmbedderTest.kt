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

    @Test
    fun `local fallback on cloud failure is NOT cached - cloud is retried next call`() = runTest {
        // Regression: a transient cloud failure used to cache the local
        // 384-dim hash vector under the text's cache key. Every later
        // embed of that text then served the degraded local vector even
        // after the outage ended.
        val failingCall = mockk<Call> {
            every { execute() } throws RuntimeException("temporary outage")
        }
        val okBody = """{"embedding":[${sampleEmbedding.joinToString(",") { it.toString() }}]}"""
        val okCall = mockk<Call> {
            every { execute() } returns Response.Builder()
                .request(Request.Builder().url("https://api.ollama.com/api/embeddings").build())
                .protocol(Protocol.HTTP_1_1)
                .code(200).message("OK")
                .body(okBody.toResponseBody(jsonMediaType))
                .build()
        }
        val httpClient = mockk<OkHttpClient> {
            every { newCall(any()) } returnsMany listOf(failingCall, okCall)
        }
        val keys = providerKeys(key = "sk-test-key")
        val local = mockk<LocalEmbedder>(relaxed = true)
        coEvery { local.embed(any()) } returns FloatArray(384) { 9f }

        val sut = CloudEmbedder(local, keys, httpClient)

        // First call: cloud fails → local fallback returned for this call.
        val first = sut.embed("same text")
        assertEquals(384, first.size)
        assertEquals(9f, first[0])

        // Second call: the fallback must NOT have been cached — the cloud
        // is retried and its real vector wins.
        val second = sut.embed("same text")
        assertEquals(768, second.size, "cloud must be retried after a transient failure, not served from cache")
        verify(exactly = 2) { httpClient.newCall(any()) }
    }

    @Test
    fun `cache is keyed by model - switching the embedding model re-embeds`() = runTest {
        val body768 = """{"embedding":[${sampleEmbedding.joinToString(",") { it.toString() }}]}"""
        val body1024 = """{"embedding":[${FloatArray(1024) { 0.25f }.joinToString(",") { it.toString() }}]}"""
        val call768 = mockk<Call> {
            every { execute() } returns Response.Builder()
                .request(Request.Builder().url("https://api.ollama.com/api/embeddings").build())
                .protocol(Protocol.HTTP_1_1)
                .code(200).message("OK")
                .body(body768.toResponseBody(jsonMediaType))
                .build()
        }
        val call1024 = mockk<Call> {
            every { execute() } returns Response.Builder()
                .request(Request.Builder().url("https://api.ollama.com/api/embeddings").build())
                .protocol(Protocol.HTTP_1_1)
                .code(200).message("OK")
                .body(body1024.toResponseBody(jsonMediaType))
                .build()
        }
        val httpClient = mockk<OkHttpClient> {
            every { newCall(any()) } returnsMany listOf(call768, call1024)
        }
        val keys = mockk<ProviderKeys>(relaxed = true)
        every { keys.keyFor("ollama") } returns "sk-test-key"
        every { keys.embeddingModel } returns "ollama:nomic-embed-text"
        val local = mockk<LocalEmbedder>(relaxed = true)

        val sut = CloudEmbedder(local, keys, httpClient)
        val first = sut.embed("same text")
        assertEquals(768, first.size)

        // Same text, DIFFERENT model: the cached 768-dim vector must not
        // be served for a 1024-dim model.
        every { keys.embeddingModel } returns "ollama:bge-large"
        val second = sut.embed("same text")
        assertEquals(1024, second.size, "switching models must bypass the old model's cache entry")
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
    fun `dimension is unknown until a response states it, then it is the response's`() = runTest {
        // The whole defect in one test. Pre-fix, dimension() answered from a
        // hardcoded table and 384 for anything unlisted, so an unlisted model's
        // real vector failed the size check on every call and the embedder fell
        // back to the local hash sketch permanently. Nothing here is told the
        // dimension in advance.
        val vec1024 = FloatArray(1024) { 0.25f }
        val httpClient = mockHttp("""{"embedding":[${vec1024.joinToString(",") { it.toString() }}]}""")
        val local = mockk<LocalEmbedder>(relaxed = true) {
            every { dimension() } returns 384
        }
        val keys = providerKeys(key = "sk-test-key", model = "ollama:some-model-nobody-listed")

        val sut = CloudEmbedder(local, keys, httpClient)
        assertEquals(384, sut.dimension(), "before any response, the honest answer is the local dimension")

        val result = sut.embed("test text")

        assertEquals(1024, result.size, "an unlisted model's real vector must not be rejected")
        assertEquals(1024, sut.dimension(), "the first successful response defines the dimension")
    }

    @Test
    fun `mxbai-embed-large is accepted at its real 1024 dimensions`() = runTest {
        // The table said 768. mxbai-embed-large is 1024. Under the table this
        // model could never produce a cloud vector at all, and every row it
        // "embedded" was a local hash sketch wearing the cloud model's name.
        val vec = FloatArray(1024) { 0.1f }
        val httpClient = mockHttp("""{"embedding":[${vec.joinToString(",") { it.toString() }}]}""")
        val local = mockk<LocalEmbedder>(relaxed = true)
        val keys = providerKeys(key = "sk-test-key", model = "ollama:mxbai-embed-large")

        val sut = CloudEmbedder(local, keys, httpClient)
        val result = sut.embed("test text")

        assertEquals(1024, result.size)
        coVerify(exactly = 0) { local.embed(any()) }
    }

    @Test
    fun `a local fallback vector is tagged with the local model, never the cloud one`() = runTest {
        // The tag is what `isCurrent` compares and what `countNeedingReembed`
        // keys on. Tagging a 384-dim hash sketch as the cloud model makes every
        // such row look current forever, which is how staleVectorCount reported
        // 0 for a corpus with no real vectors in it.
        val httpClient = mockHttpException()
        val local = mockk<LocalEmbedder>(relaxed = true)
        coEvery { local.embed(any()) } returns FloatArray(384) { 0.5f }
        every { local.modelId() } returns "local-hash-v2"
        val keys = providerKeys(key = "sk-test-key", model = "ollama:nomic-embed-text")

        val sut = CloudEmbedder(local, keys, httpClient)
        val tagged = sut.embedTagged("test text")

        assertEquals("local-hash-v2", tagged.modelId)
        assertEquals(384, tagged.dim)
    }

    @Test
    fun `a cloud vector is tagged with the configured model and its real size`() = runTest {
        val embedBody = """{"embedding":[${sampleEmbedding.joinToString(",") { it.toString() }}]}"""
        val httpClient = mockHttp(embedBody)
        val local = mockk<LocalEmbedder>(relaxed = true)
        val keys = providerKeys(key = "sk-test-key", model = "ollama:nomic-embed-text")

        val sut = CloudEmbedder(local, keys, httpClient)
        val tagged = sut.embedTagged("test text")

        assertEquals("ollama:nomic-embed-text", tagged.modelId)
        assertEquals(768, tagged.dim)
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
