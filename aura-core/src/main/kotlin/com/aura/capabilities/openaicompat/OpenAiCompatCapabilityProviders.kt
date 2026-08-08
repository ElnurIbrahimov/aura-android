package com.aura.capabilities.openaicompat

import android.util.Base64
import com.aura.capabilities.CapabilityKind
import com.aura.capabilities.ImageProvider
import com.aura.capabilities.ImageRequest
import com.aura.capabilities.ImageResult
import com.aura.capabilities.TextToSpeechProvider
import com.aura.capabilities.TtsRequest
import com.aura.capabilities.TtsResult
import com.aura.capabilities.VideoProvider
import com.aura.capabilities.VideoRequest
import com.aura.capabilities.VideoResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient

/**
 * Capability backends synthesized from a chat provider's own model catalog.
 *
 * Every backend used to be a hand-written vendor adapter bound statically in
 * Hilt, so adding one meant implementing a subinterface, adding a `@Binds`, and
 * shipping a build. A user connecting a token that happens to serve images got
 * nothing. These adapters close that: given a `(providerPrefix, modelName)` pair
 * that [com.aura.providers.ModelCapability] classification found in a catalog,
 * plus the conventional endpoint the provider advertises, they speak the
 * OpenAI-shaped API directly.
 *
 * They implement the **existing** `ImageProvider` / `VideoProvider` /
 * `TextToSpeechProvider` interfaces unchanged — those already carried a `model`
 * field, which is the reason this needed no interface churn.
 *
 * These are the fallback tier. Hand-written adapters keep priority in
 * [com.aura.capabilities.CapabilityRegistry] because they encode things a
 * generic client cannot infer: Kling mints a JWT and polls, WorldLabs polls a
 * bespoke operation id, ElevenLabs puts the voice in the path behind an
 * `xi-api-key` header, Stability posts multipart with the model in the path.
 * Discovery fills gaps; it does not take over.
 */

/** Common identity for a discovered backend. */
internal abstract class DiscoveredCapability(
    protected val providerPrefix: String,
    protected val providerDisplayName: String,
    protected val modelName: String,
    protected val endpoint: String,
    protected val apiKey: () -> String?,
    protected val client: OkHttpClient,
) : com.aura.capabilities.CapabilityProvider {
    /**
     * Prefixed so a discovered backend can never collide with a statically
     * bound one in the registry's key space, and so logs say where it came from.
     */
    override val prefix: String get() = "$providerPrefix/$modelName"
    override val displayName: String get() = "$providerDisplayName · $modelName"
    override fun isConfigured(): Boolean = !apiKey().isNullOrBlank()

    protected fun key(): String =
        apiKey()?.takeIf { it.isNotBlank() }
            ?: throw RuntimeException("no API key configured for '$providerPrefix'")
}

internal class OpenAiCompatImageProvider(
    providerPrefix: String,
    providerDisplayName: String,
    modelName: String,
    endpoint: String,
    apiKey: () -> String?,
    client: OkHttpClient,
) : DiscoveredCapability(providerPrefix, providerDisplayName, modelName, endpoint, apiKey, client),
    ImageProvider {

    override val kind: CapabilityKind get() = CapabilityKind.ImageGeneration

    override suspend fun generate(req: ImageRequest): ImageResult = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            put("model", req.model.ifBlank { modelName })
            put("prompt", req.prompt)
            put("n", 1)
            put("size", "${req.width}x${req.height}")
        }
        OpenAiCompatCapabilityHttp.postJson(client, endpoint, key(), body).use { response ->
            val text = OpenAiCompatCapabilityHttp.readOrThrow(response, "$providerPrefix images")
            val (url, b64) = OpenAiCompatCapabilityHttp.firstUrlOrB64(text, "$providerPrefix images")
            ImageResult(
                url = url,
                bytes = b64?.let { Base64.decode(it, Base64.DEFAULT) },
            )
        }
    }
}

internal class OpenAiCompatVideoProvider(
    providerPrefix: String,
    providerDisplayName: String,
    modelName: String,
    endpoint: String,
    apiKey: () -> String?,
    client: OkHttpClient,
) : DiscoveredCapability(providerPrefix, providerDisplayName, modelName, endpoint, apiKey, client),
    VideoProvider {

    override val kind: CapabilityKind get() = CapabilityKind.VideoGeneration

    /**
     * Single-shot only.
     *
     * Video APIs commonly return a job to poll, and the shape of that job is
     * not standardised — which is exactly why Kling has a hand-written adapter.
     * This handles providers that return the asset directly (or a URL to it).
     * If the response carries only an id, the caller gets a clear error rather
     * than a silent empty result, and the fix is a vendor adapter.
     */
    override suspend fun generate(req: VideoRequest): VideoResult = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            put("model", req.model.ifBlank { modelName })
            put("prompt", req.prompt)
            put("seconds", req.durationSeconds)
            put("size", req.aspectRatio)
        }
        OpenAiCompatCapabilityHttp.postJson(client, endpoint, key(), body).use { response ->
            val text = OpenAiCompatCapabilityHttp.readOrThrow(response, "$providerPrefix videos")
            val (url, b64) = OpenAiCompatCapabilityHttp.firstUrlOrB64(text, "$providerPrefix videos")
            VideoResult(
                videoUrl = url,
                bytes = b64?.let { Base64.decode(it, Base64.DEFAULT) },
            )
        }
    }
}

internal class OpenAiCompatSpeechProvider(
    providerPrefix: String,
    providerDisplayName: String,
    modelName: String,
    endpoint: String,
    apiKey: () -> String?,
    client: OkHttpClient,
) : DiscoveredCapability(providerPrefix, providerDisplayName, modelName, endpoint, apiKey, client),
    TextToSpeechProvider {

    override val kind: CapabilityKind get() = CapabilityKind.TextToSpeech

    /** `/audio/speech` answers with raw audio, not a JSON envelope. */
    override suspend fun speak(req: TtsRequest): TtsResult = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            put("model", req.model.ifBlank { modelName })
            put("input", req.text)
            put("voice", req.voice.ifBlank { DEFAULT_VOICE })
            put("response_format", req.format)
        }
        OpenAiCompatCapabilityHttp.postJson(client, endpoint, key(), body).use { response ->
            val bytes = response.body?.bytes()
            if (!response.isSuccessful) {
                throw RuntimeException(
                    "$providerPrefix speech HTTP ${response.code}: " +
                        (bytes?.decodeToString()?.take(500).orEmpty()),
                )
            }
            if (bytes == null || bytes.isEmpty()) {
                throw RuntimeException("$providerPrefix speech returned no audio")
            }
            TtsResult(audio = bytes, mimeType = "audio/${req.format}", extension = req.format)
        }
    }

    private companion object {
        /** OpenAI's default voice name; the one most compatible APIs mirror. */
        const val DEFAULT_VOICE = "alloy"
    }
}
