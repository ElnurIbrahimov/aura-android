package com.aura.memory

import android.util.Log
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
     * Embedding dimensionality, learned from the wire rather than declared here.
     *
     * This used to be a hardcoded model→dimension table with a 384 default for
     * anything unlisted, and every consequence of that was silent. A 768- or
     * 1024-dim model absent from the table failed the size check in
     * [cloudEmbed] on every call and fell back to the local hash sketch
     * permanently — and because `dimension()` was also what [embedTagged] used
     * to decide whether the cloud had answered, the row was still tagged with
     * the CLOUD model id. `isCurrent()` then returned true for every one of
     * those rows, `countNeedingReembed` returned zero, and
     * `RetrievalTrace.staleVectorCount` reported 0 for a corpus whose entire
     * vector signal was a hash of the text. The table was also simply wrong:
     * `mxbai-embed-large` was listed at 768 and is 1024.
     *
     * A model's dimension is a fact its own first response states, so nothing
     * here needs it in advance and nothing here guesses. Before the first
     * successful call for a model this reports the local embedder's dimension,
     * which is the honest answer: that is the size of the vector this embedder
     * would produce right now.
     */
    override fun dimension(): Int =
        observedDimensions[selectedModelName()] ?: localEmbedder.dimension()

    /**
     * Dimension per model, learned from that model's first successful response
     * and kept for the process lifetime.
     *
     * Keyed by model because the user can change the embedding model in
     * Settings at any moment, and a dimension learned for one says nothing
     * about the next. The key is [selectedModelName]'s form — the part after
     * the `ollama:` prefix — which is the same string [cloudEmbed] receives as
     * `model`, so the write and the read cannot drift apart. Concurrent
     * because [embedTagged] runs on `Dispatchers.IO` and several recalls can be
     * in flight at once.
     */
    private val observedDimensions = java.util.concurrent.ConcurrentHashMap<String, Int>()

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

    /**
     * In-memory LRU cache: "modelId:SHA-256(hex)" → FloatArray. The key
     * includes the model id so switching the embedding model in Settings
     * never serves a stale vector from the previous model (same text,
     * different model → different dimension AND different space).
     */
    private val cache = object : LinkedHashMap<String, FloatArray>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, FloatArray>): Boolean =
            size > MAX_CACHE_ENTRIES
    }

    /**
     * [embedTagged] does the work; this is the untagged view of it.
     *
     * The two used to be independent, and [embedTagged] recovered "did the
     * cloud answer?" by comparing `vec.size` against `dimension()`. That
     * heuristic cannot tell a cloud model from the local fallback whenever the
     * two share a dimension — which is every 384-dim model, and, once
     * `dimension()` stopped guessing, every model before its first successful
     * call. Deriving one function from the other removes the guess entirely:
     * the branch that produced the vector is the branch that names it.
     */
    override suspend fun embed(text: String): FloatArray = embedTagged(text).vector

    /**
     * Embed [text] and report which model ACTUALLY produced the vector.
     *
     * `MemoryStore.store` writes this tag into `embeddingModel` and
     * `Embedder.isCurrent` compares it back, so a local-fallback vector tagged
     * with the cloud model is not a cosmetic error: the row becomes invisible
     * to `countNeedingReembed` and is never repaired, and `staleVectorCount`
     * reports 0 for a corpus whose vectors mean nothing.
     */
    override suspend fun embedTagged(text: String): Embedding = withContext(Dispatchers.IO) {
        val apiKey = providerKeys.keyFor("ollama")
        val selected = providerKeys.embeddingModel
        val parts = selected.split(":", limit = 2)
        val model = parts.getOrNull(1)?.takeIf {
            parts.firstOrNull() == "ollama" && it.isNotBlank()
        }
        val cloudConfigured = !apiKey.isNullOrBlank() && model != null
        val digest = sha256Hex(text)

        if (cloudConfigured) {
            // Cache key is scoped to the model, so switching models in Settings
            // never serves a vector from the previous one.
            val cloudCacheKey = "$selected:$digest"
            synchronized(cache) { cache[cloudCacheKey] }?.let {
                return@withContext Embedding(it, modelId(), it.size)
            }
            try {
                val vec = cloudEmbed(text, apiKey!!, model!!)
                synchronized(cache) { cache[cloudCacheKey] = vec }
                return@withContext Embedding(vec, modelId(), vec.size)
            } catch (e: Exception) {
                // Every cloud embed failure — auth, network, rate limit, a 404
                // on the model, a parse error — used to be swallowed here, so a
                // user who had configured nomic-embed-text silently stored
                // 384-dim hash sketches instead and nothing said so.
                Log.w("CloudEmbedder", "cloud embed failed for model=$model, falling back to local", e)
            }
            // Returned but NOT cached: a cached fallback would masquerade as a
            // cloud vector for this text and keep degrading recall long after
            // the outage ended.
            val fallback = localEmbedder.embed(text)
            return@withContext Embedding(fallback, localEmbedder.modelId(), fallback.size)
        }

        // No cloud model configured — the local embedder IS the configured
        // embedder, so caching under its own model key is safe.
        val localCacheKey = "$LOCAL_MODEL_ID:$digest"
        synchronized(cache) { cache[localCacheKey] }?.let {
            return@withContext Embedding(it, localEmbedder.modelId(), it.size)
        }
        val vec = localEmbedder.embed(text)
        synchronized(cache) { cache[localCacheKey] = vec }
        Embedding(vec, localEmbedder.modelId(), vec.size)
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
            // An empty vector is not merely a small one: recorded as this
            // model's dimension it would make every later response "mismatch"
            // and pin the model to the local fallback for the whole process.
            if (vec.isEmpty()) {
                throw RuntimeException("Ollama Cloud returned an empty embedding for model=$model")
            }
            // The FIRST successful response defines this model's dimension;
            // later ones are checked against it. Checking against a hardcoded
            // table instead is what made every unlisted model — and
            // mxbai-embed-large, which the table had at the wrong size — fail
            // permanently and invisibly. See dimension().
            val known = observedDimensions.putIfAbsent(model, vec.size)
            if (known != null && known != vec.size) {
                android.util.Log.w(
                    "CloudEmbedder",
                    "model=$model returned a ${vec.size}-dim embedding after previously " +
                        "returning $known; refusing it so the store keeps one shape per model",
                )
                throw RuntimeException(
                    "embedding dimension changed for model=$model: got ${vec.size}, previously $known",
                )
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
        /** Cache-key namespace for vectors produced by [LocalEmbedder]. */
        private const val LOCAL_MODEL_ID = "local-hash-v2"
    }
}
