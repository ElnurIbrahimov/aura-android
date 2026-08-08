package com.aura.capabilities.openaicompat

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/**
 * Shared HTTP plumbing for the generic OpenAI-shaped capability adapters.
 *
 * The four adapters differ only in path, request body and how they read the
 * response; everything around that — auth header, JSON media type, error
 * surfacing — is identical, and duplicating it four times is how the error
 * messages drift apart.
 */
internal object OpenAiCompatCapabilityHttp {

    private val jsonMediaType = "application/json".toMediaType()

    val json = Json { ignoreUnknownKeys = true }

    /** POST a JSON body to [endpoint] with a bearer [key]. */
    fun postJson(
        client: OkHttpClient,
        endpoint: String,
        key: String,
        body: JsonObject,
    ): Response = client.newCall(
        Request.Builder()
            .url(endpoint)
            .header("Authorization", "Bearer $key")
            .header("Content-Type", "application/json")
            .post(body.toString().toRequestBody(jsonMediaType))
            .build(),
    ).execute()

    /**
     * Read a successful body, or throw with the provider's own explanation.
     *
     * The error body is included deliberately: the 400 that started this whole
     * line of work ("Model agnes-image-2.1-flash is an image model. Use
     * /v1/images/generations.") was only diagnosable because the provider's
     * text reached the surface.
     */
    fun readOrThrow(response: Response, what: String): String {
        val body = response.body?.string().orEmpty()
        if (!response.isSuccessful) {
            throw RuntimeException("$what HTTP ${response.code}: ${body.take(500)}")
        }
        if (body.isBlank()) throw RuntimeException("$what returned an empty response")
        return body
    }

    /**
     * Extract a hosted URL or inline base64 from an OpenAI-shaped
     * `data[0]` envelope.
     *
     * `contentOrNull`, never `content`: a JSON null is a `JsonPrimitive` whose
     * `content` is the four-character String "null", which is neither null nor
     * blank and would sail through as a real URL. Agnes returns BOTH keys on
     * every response with the unused one null, so this is not hypothetical —
     * it is the same defect `d3550610` fixed in the OpenAI SSE parser.
     */
    fun firstUrlOrB64(body: String, what: String): Pair<String?, String?> {
        val first = json.parseToJsonElement(body).jsonObject["data"]?.jsonArray
            ?.firstOrNull()?.jsonObject
            ?: throw RuntimeException("$what response has no data[0]")
        val url = first["url"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        val b64 = first["b64_json"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        if (url == null && b64 == null) {
            throw RuntimeException("$what response has neither url nor b64_json in data[0]")
        }
        return url to b64
    }
}
