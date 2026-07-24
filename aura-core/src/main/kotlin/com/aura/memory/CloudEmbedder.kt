package com.aura.memory

import com.aura.providers.ProviderKeys
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.security.MessageDigest
import java.util.LinkedHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Cloud semantic embedder with local fallback.
 *
 * Tries to call the Ollama Cloud embeddings API:
 *   POST https://api.ollama.com/api/embeddings
 *   Content-Type: application/json
 *   Authorization: Bearer <ollama-key>
 *
 * Falls back to [LocalEmbedder] if:
 *   - No Ollama Cloud API key is configured
 *   - The network request fails (timeout / HTTP error)
 *   - A parsing error occurs
 *
 * Results are cached in an in-memory LRU keyed by SHA-256 of the input text,
 * so re-embedding the same string (e.g. repeated recall queries) is instant.
 */
@Singleton
class CloudEmbedder @Inject constructor(
    private val localEmbedder: LocalEmbedder,
    private val providerKeys: ProviderKeys,
    private val httpClient: OkHttpClient,
) : Embedder {

    private val json = Json { ignoreUnknownKeys = true }
    private val mediaType = "application/json".toMediaType()

    override fun modelId(): kotlin.String {
        val selected = providerKeys.embeddingModel
        return if (selected.isNotBlank()) selected else "local-hash-v2"
    }

    /**
     * Embedding dimensionality. Returns the dimension for the currently
     * configured model rather than always 384 (the local embedder
     * default). Without this, picking a non-384-dim cloud model
     * (e.g. nomic-embed-text = 768, mxbai-embed-large = 1024,
     * bge-large = 1024) caused every cloudEmbed() call to fall back
     * to the local embedder because the dimension validation
     * in cloudEmbed() compared vec.size against the hard-coded 384.
     *
     * The model id is the part AFTER the provider prefix (e.g. for
     * "ollama:nomic-embed-text" the model is "nomic-embed-text"),
     * matching the parse in embed(). Unknown models default to 384
     * with a runtime warning so new Ollama catalog entries don't
     * break the embedding pipeline.
     */
    override fun dimension(): Int {
        val model = selectedModelName()
        if (model.isBlank()) return localEmbedder.dimension()
        return when (model) {
            // 384-dim models — match LocalEmbedder.
            "local-hash-v2", "all-minilm:l6-v2", "all-minilm:l12-v2",
            "snowflake-arctic-embed:33m", "snowflake-arctic-embed:110m",
            "bge-small", "bge-small-en-v1.5", "bge-small-zh-v1.5" -> 384
            // 768-dim models.
            "nomic-embed-text", "nomic-embed-text:v1.5",
            "all-mpnet-base-v2", "bge-base", "bge-base-en-v1.5",
            "mxbai-embed-large", "snowflake-arctic-embed:335m" -> 768
            // 1024-dim models.
            "bge-large", "bge-large-en-v1.5", "bge-large-zh-v1.5",
            "bge-m3", "cohere-embed-multilingual-v3" -> 1024
            // 1536-dim OpenAI.
            "text-embedding-ada-002", "text-embedding-3-small" -> 1536
            "text-embedding-3-large" -> 3072
            else -> {
                android.util.Log.w(
                    "CloudEmbedder",
                    "Unknown embedding model '$model' — defaulting to 384. " +
                        "Add the model to dimension() in CloudEmbedder.kt to avoid " +
                        "fallback to the local 384-dim embedder.",
                )
                384
            }
        }
    }

    /**
     * Parse the configured model id the same way embed() does:
     * split on ':' and return the part after the 'ollama' prefix
     * (or the whole string if there's no prefix). This keeps
     * dimension() in sync with the cloud-call path.
     */
    private fun selectedModelName(): String {
        val selected = providerKeys.embeddingModel
        if (selected.isBlank()) return ""
        val parts = selected.split(":", limit = 2)
        return if (parts.size == 2 && parts[0] == "ollama") parts[1] else selected
    }

    /** In-memory LRU cache: SHA-256(hex) → FloatArray. */
    private val cache = object : LinkedHashMap<String, FloatArray>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, FloatArray>): Boolean =
            size > MAX_CACHE_ENTRIES
    }

    override suspend fun embed(text: String): FloatArray = withContext(Dispatchers.IO) {
        val cacheKey = sha256Hex(text)

        // 1. Check cache
        synchronized(cache) {
            cache[cacheKey]?.let { return@withContext it }
        }

        // 2. Try cloud only for a selected Ollama catalog model.
        val apiKey = providerKeys.keyFor("ollama")
        val selected = providerKeys.embeddingModel
        val parts = selected.split(":", limit = 2)
        val model = parts.getOrNull(1)?.takeIf {
            parts.firstOrNull() == "ollama" && it.isNotBlank()
        }
        if (!apiKey.isNullOrBlank() && model != null) {
            try {
                val vec = cloudEmbed(text, apiKey, model)
                synchronized(cache) { cache[cacheKey] = vec }
                return@withContext vec
            } catch (_: Exception) {
                // Fall through to local fallback
            }
        }

        // 3. Fallback to local
        val vec = localEmbedder.embed(text)
        synchronized(cache) { cache[cacheKey] = vec }
        vec
    }

    /**
     * Makes the HTTP call to the Ollama Cloud embeddings API.
     * Throws on any failure (network, HTTP error, bad response).
     */
    private fun cloudEmbed(text: String, apiKey: String, model: String): FloatArray {
        val requestBody = buildJsonObject {
            put("model", model)
            put("prompt", text)
        }.toString().toRequestBody(mediaType)

        val request = Request.Builder()
            .url(EMBEDDINGS_URL)
            .addHeader("Authorization", "Bearer $apiKey")
            .post(requestBody)
            .build()

        val response = httpClient.newCall(request).execute()
        val body = response.body?.string() ?: throw RuntimeException("Empty response body")

        if (!response.isSuccessful) {
            throw RuntimeException(
                "Ollama Cloud embeddings returned ${response.code}: $body"
            )
        }

        val obj = json.parseToJsonElement(body).jsonObject
        val embeddingArray = obj["embedding"]?.jsonArray
            ?: // Some Ollama endpoints return { data: [{ embedding: [...] }] }
            obj["data"]?.jsonArray?.firstOrNull()?.jsonObject?.get("embedding")?.jsonArray
            ?: throw RuntimeException("No embedding in response: $body")

        return FloatArray(embeddingArray.size) { i ->
            embeddingArray[i].jsonPrimitive.content.toFloat()
        }.also { vec ->
            // Validate dimension — if the API returns a different dimension
            // than expected (e.g. 768 from a larger model), log and fall back
            // to local embedder rather than storing a mismatched vector.
            if (vec.size != dimension()) {
                android.util.Log.w("CloudEmbedder",
                    "API returned ${vec.size}-dim embedding but expected ${dimension()}. " +
                    "Falling back to local embedder. Change the embedding model in Settings to match.")
                throw RuntimeException("embedding dimension mismatch: ${vec.size} != ${dimension()}")
            }
        }
    }

    /** Full SHA-256 hex string for cache keys. */
    private fun sha256Hex(text: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        val bytes = md.digest(text.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val EMBEDDINGS_URL = "https://api.ollama.com/api/embeddings"
        private const val MAX_CACHE_ENTRIES = 1000
    }
}
