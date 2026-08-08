package com.aura.tools

import com.aura.agent.Tool
import com.aura.agent.ToolResult
import com.aura.agent.ToolRisk
import com.aura.providers.ProviderKeys
import com.aura.providers.ToolParameters
import com.aura.providers.ToolProperty
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton

/** Maximum decoded audio accepted by transcription providers: 25 MB. */
const val MAX_TRANSCRIPTION_AUDIO_BYTES: Int = 25 * 1024 * 1024

/**
 * Transcription tool: sends base64-encoded audio to a cloud Whisper API
 * and returns the transcribed text.
 *
 * Provider selection order:
 * 1. OpenAI Whisper (whisper-1) — if an OpenAI API key is configured
 * 2. Groq (whisper-large-v3) — if a Groq API key is configured
 *
 * Risk: REMOTE_COST (invokes paid API per call, no phone permissions).
 */
@Singleton
class TranscriptionTool @Inject constructor(
    private val httpClient: OkHttpClient,
    private val providerKeys: ProviderKeys,
    private val providerRegistry: com.aura.providers.ProviderRegistry? = null,
    private val modelCatalogRepository: com.aura.providers.ModelCatalogRepository? = null,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val mediaTypeOctet = "application/octet-stream".toMediaType()

    fun definition() = ToolParameters(
        properties = mapOf(
            "audio_base64" to ToolProperty(
                type = "string",
                description = "Base64-encoded audio data (WAV, MP3, M4A, etc.)",
            ),
            "language" to ToolProperty(
                type = "string",
                description = "Language code (default: 'en'). Optional.",
            ),
        ),
        required = listOf("audio_base64"),
    )

    val tool = Tool(
        name = "transcribe",
        description = "Transcribe audio to text using a cloud Whisper API. " +
            "Provide audio as base64-encoded data and an optional language code. " +
            "Audio must be 25 MB or smaller.",
        risk = ToolRisk.REMOTE_COST,
        parameters = definition(),
        execute = { call, _ ->
            val audioBase64 = call.arguments["audio_base64"] as? String
                ?: return@Tool ToolResult.Error("missing 'audio_base64' argument", "bad_args")
            val language = call.arguments["language"] as? String ?: "en"

            // Size check: estimate decoded bytes from base64 length
            // base64 encodes 3 bytes into 4 chars, so length * 3/4 ≈ decoded size
            val estimatedBytes = (audioBase64.length * 3L) / 4
            if (estimatedBytes > MAX_TRANSCRIPTION_AUDIO_BYTES) {
                return@Tool ToolResult.Error(
                    "Audio too large (${estimatedBytes / (1024 * 1024)} MB). Maximum is 25 MB.",
                    "audio_too_large",
                )
            }

            try {
                val result = transcribeAudio(audioBase64, language)
                ToolResult.Ok(result)
            } catch (e: Exception) {
                ToolResult.Error("transcription failed: ${e.message}", "http_error")
            }
        },
    category = "media")
    /**
     * Decodes a base64 string to a byte array.
     */
    private fun decodeBase64(base64: String): ByteArray {
        return Base64.getDecoder().decode(base64)
    }

    /**
     * Transcribe using any configured provider that offers a transcription
     * model.
     *
     * This used to be a hardcoded OpenAI-then-Groq ladder, so a user with a
     * perfectly capable third provider got "Please configure OpenAI or Groq" —
     * the same shape of problem as image generation only ever reaching OpenAI.
     * `CapabilityKind.Transcription` had no bound provider at all, so it could
     * not be fixed by adding an adapter either.
     *
     * Transcription is a multipart upload, which the discovered
     * [com.aura.capabilities.openaicompat] adapters deliberately do not do, so
     * this resolves against the same information they use — the classified
     * model catalog plus the provider's advertised endpoint — rather than
     * through CapabilityRouter.
     *
     * The model name comes from the catalog because it differs per provider
     * (OpenAI `whisper-1`, Groq `whisper-large-v3`); [FALLBACK_MODEL] covers a
     * provider whose catalog has not loaded yet.
     *
     * @throws RuntimeException if no provider can transcribe.
     */
    private fun transcribeAudio(audioBase64: String, language: String): String {
        val audioBytes = decodeBase64(audioBase64)
        val candidates = transcriptionCandidates()

        if (candidates.isEmpty()) {
            throw RuntimeException(
                "No transcription provider configured. Add an API key for a provider that offers " +
                    "speech-to-text (for example OpenAI or Groq) in Settings.",
            )
        }

        var lastError: Exception? = null
        for (candidate in candidates) {
            try {
                return transcribeWith(audioBytes, language, candidate)
            } catch (e: Exception) {
                // Try the next provider rather than failing outright: a single
                // provider being down should not make the tool unavailable.
                android.util.Log.w("TranscriptionTool", "transcription via ${candidate.prefix} failed: ${e.message}", e)
                lastError = e
            }
        }
        throw lastError ?: RuntimeException("Transcription failed with no provider error recorded")
    }

    /** A provider that can transcribe: endpoint, key and model resolved. */
    private data class TranscriptionCandidate(
        val prefix: String,
        val endpoint: String,
        val key: String,
        val model: String,
    )

    private fun transcriptionCandidates(): List<TranscriptionCandidate> {
        val registry = providerRegistry ?: return emptyList()
        val catalog = modelCatalogRepository?.catalog?.value?.allModels.orEmpty()

        return registry.configured().mapNotNull { provider ->
            val endpoint = provider.transcriptionsEndpoint ?: return@mapNotNull null
            val key = providerKeys.keyFor(provider.prefix)?.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            val model = catalog.firstOrNull {
                it.providerPrefix == provider.prefix &&
                    it.capability == com.aura.providers.ModelCapability.Transcription
            }?.name ?: FALLBACK_MODEL
            TranscriptionCandidate(provider.prefix, endpoint, key, model)
        }
    }

    private fun transcribeWith(
        audioBytes: ByteArray,
        language: String,
        candidate: TranscriptionCandidate,
    ): String {
        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", "audio.mp3", audioBytes.toRequestBody(mediaTypeOctet))
            .addFormDataPart("model", candidate.model)
            .addFormDataPart("language", language)
            .build()

        val req = Request.Builder()
            .url(candidate.endpoint)
            .header("Authorization", "Bearer ${candidate.key}")
            .post(requestBody)
            .build()

        return httpClient.newCall(req).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw RuntimeException("${candidate.prefix} transcription HTTP ${response.code}: ${body.take(500)}")
            }
            if (body.isBlank()) throw RuntimeException("${candidate.prefix} transcription returned an empty response")
            json.parseToJsonElement(body).jsonObject["text"]?.jsonPrimitive?.content
                ?: throw RuntimeException("${candidate.prefix} transcription response missing 'text'")
        }
    }

    private companion object {
        /** Used when the provider's catalog has not been loaded yet. */
        const val FALLBACK_MODEL = "whisper-1"
    }

    // ------------------------------------------------------------------
    // OpenAI Whisper API
    // ------------------------------------------------------------------
    // POST https://api.openai.com/v1/audio/transcriptions
    // Multipart form: file=<audio bytes> model=whisper-1 language=<code>
    // Header: Authorization: Bearer <OPENAI_API_KEY>
    // Response: { "text": "..." }
    // ------------------------------------------------------------------

    private fun transcribeWithOpenAi(
        audioBytes: ByteArray,
        language: String,
        apiKey: String,
    ): String {
        val audioBody = audioBytes.toRequestBody(mediaTypeOctet)
        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", "audio.mp3", audioBody)
            .addFormDataPart("model", "whisper-1")
            .addFormDataPart("language", language)
            .build()

        val req = Request.Builder()
            .url("https://api.openai.com/v1/audio/transcriptions")
            .header("Authorization", "Bearer $apiKey")
            .post(requestBody)
            .build()

        return httpClient.newCall(req).execute().use { response ->
            if (!response.isSuccessful) {
                val errorBody = response.body?.string() ?: ""
                throw RuntimeException("OpenAI Whisper API HTTP ${response.code}: $errorBody")
            }
            val respBody = response.body?.string()
                ?: throw RuntimeException("Empty response from OpenAI Whisper")

            val root = json.parseToJsonElement(respBody).jsonObject
            root["text"]?.jsonPrimitive?.content
                ?: throw RuntimeException("OpenAI Whisper response missing 'text' field")
        }
    }

    // ------------------------------------------------------------------
    // Groq Whisper API (whisper-large-v3)
    // ------------------------------------------------------------------
    // POST https://api.groq.com/openai/v1/audio/transcriptions
    // Multipart form: file=<audio bytes> model=whisper-large-v3 language=<code>
    // Header: Authorization: Bearer <GROQ_API_KEY>
    // Response: { "text": "..." }
    // ------------------------------------------------------------------

    private fun transcribeWithGroq(
        audioBytes: ByteArray,
        language: String,
        apiKey: String,
    ): String {
        val audioBody = audioBytes.toRequestBody(mediaTypeOctet)
        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", "audio.mp3", audioBody)
            .addFormDataPart("model", "whisper-large-v3")
            .addFormDataPart("language", language)
            .build()

        val req = Request.Builder()
            .url("https://api.groq.com/openai/v1/audio/transcriptions")
            .header("Authorization", "Bearer $apiKey")
            .post(requestBody)
            .build()

        return httpClient.newCall(req).execute().use { response ->
            if (!response.isSuccessful) {
                val errorBody = response.body?.string() ?: ""
                throw RuntimeException("Groq Whisper API HTTP ${response.code}: $errorBody")
            }
            val respBody = response.body?.string()
                ?: throw RuntimeException("Empty response from Groq Whisper")

            val root = json.parseToJsonElement(respBody).jsonObject
            root["text"]?.jsonPrimitive?.content
                ?: throw RuntimeException("Groq Whisper response missing 'text' field")
        }
    }
}
