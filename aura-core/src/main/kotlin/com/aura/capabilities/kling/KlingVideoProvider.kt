package com.aura.capabilities.kling

import com.aura.capabilities.VideoProvider
import com.aura.capabilities.VideoRequest
import com.aura.capabilities.VideoResult
import com.aura.capabilities.http.CapabilityHttp
import com.aura.capabilities.http.asJsonObjectOrNull
import com.aura.capabilities.http.stringOrNull
import com.aura.providers.ProviderKeys
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Kling AI text/image-to-video. POST /v1/videos/text2video returns task_id; poll
 * GET /v1/videos/text2video/{task_id} until completed.
 * https://app.klingai.com/global/dev/document-api
 */
@Singleton
class KlingVideoProvider @Inject constructor(
    private val client: OkHttpClient,
    private val providerKeys: ProviderKeys,
) : VideoProvider {
    override val prefix = "kling"
    override val displayName = "Kling AI"
    private val apiKey: kotlin.String get() = providerKeys.keyFor(prefix).orEmpty()
    override fun isConfigured(): Boolean = apiKey.isNotBlank()

    /**
     * Kling's API authenticates with a short-lived HS256 JWT minted from
     * an AccessKey/SecretKey pair. The stored key uses the format
     * `accessKey:secretKey` (documented in the Settings key field); when
     * both parts are present we mint a JWT per request. A stored value
     * without a `:` is treated as a legacy raw token and sent as a plain
     * Bearer credential so existing setups keep working.
     */
    internal fun authorizationHeader(storedKey: kotlin.String = apiKey): kotlin.String {
        val parts = storedKey.split(":", limit = 2)
        return if (parts.size == 2 && parts[0].isNotBlank() && parts[1].isNotBlank()) {
            "Bearer ${KlingJwt.mint(accessKey = parts[0], secretKey = parts[1])}"
        } else {
            "Bearer $storedKey"
        }
    }

    override suspend fun generate(req: VideoRequest): VideoResult = withContext(Dispatchers.IO) {
        val model = req.model.ifBlank { "kling-v2-5-turbo" }
        val url = "https://api.klingai.com/v1/videos/text2video"
        val body = CapabilityHttp.buildJsonBody(
            "model_name" to model,
            "prompt" to req.prompt,
            "duration" to req.durationSeconds.toString(),
            "aspect_ratio" to req.aspectRatio,
        )
        val request = okhttp3.Request.Builder()
            .url(url)
            .header("Authorization", authorizationHeader())
            .header("Content-Type", "application/json")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        val createResp = client.newCall(request).execute()
        val createRaw = createResp.use { it.body?.string().orEmpty() }
        CapabilityHttp.classify(createResp, createRaw)
        val taskId = CapabilityHttp.json.parseToJsonElement(createRaw)
            .asJsonObjectOrNull()?.get("data")?.asJsonObjectOrNull()
            ?.get("task_id").stringOrNull()
            ?: throw com.aura.capabilities.CapabilityCatalogException.MalformedResponseException("Kling create response missing task_id")

        // Poll for completion (max ~120s). Kling is async, returns task_id and updates via /v1/videos/text2video/{task_id}.
        repeat(30) {
            currentCoroutineContext().ensureActive()
            delay(4_000L)
            currentCoroutineContext().ensureActive()
            val pollReq = okhttp3.Request.Builder()
                .url("https://api.klingai.com/v1/videos/text2video/$taskId")
                .header("Authorization", authorizationHeader())
                .build()
            val pollResp = client.newCall(pollReq).execute()
            val pollRaw = pollResp.use { it.body?.string().orEmpty() }
            CapabilityHttp.classify(pollResp, pollRaw)
            val data = CapabilityHttp.json.parseToJsonElement(pollRaw)
                .asJsonObjectOrNull()?.get("data")?.asJsonObjectOrNull()
            val status = data?.get("task_status").stringOrNull()
                ?: data?.get("status").stringOrNull()
            if (status == "succeed" || status == "completed" || status == "SUCCESS") {
                val videoUrl = (data?.get("task_result")?.asJsonObjectOrNull()?.get("videos") as? kotlinx.serialization.json.JsonArray)
                    ?.firstOrNull()?.asJsonObjectOrNull()
                    ?.get("url").stringOrNull()
                    ?: data?.get("video_url").stringOrNull()
                return@withContext VideoResult(videoUrl = videoUrl, taskId = taskId)
            }
            if (status == "failed" || status == "FAILED" || status == "error") {
                throw com.aura.capabilities.CapabilityCatalogException.NetworkException(
                    message = "Kling task $taskId failed: $pollRaw",
                )
            }
        }
        throw com.aura.capabilities.CapabilityCatalogException.MalformedResponseException(
            "Kling task $taskId did not complete within ~2 minutes",
        )
    }
}
