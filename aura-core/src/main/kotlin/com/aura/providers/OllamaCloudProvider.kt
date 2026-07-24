package com.aura.providers

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * OpenAI-compatible chat completions client for Ollama Cloud.
 *
 * This is a thin wrapper around [OpenAiCompatProvider]; all the streaming,
 * tool-call parsing, and request-building logic lives in the parent class.
 *
 * The API key is read from [ProviderKeys] on every [chat] call, not at
 * construction time, so changes the user makes in the Settings UI take
 * effect immediately without restarting the app.
 *
 * [listModelsWithContext] queries Ollama's `/api/show` endpoint per model
 * to get the real context window — Ollama Cloud models vary wildly
 * (8K to 1M+), so the compactor needs the real number, not a guess.
 */
class OllamaCloudProvider(
    override val prefix: String,
    override val displayName: String,
    private val baseUrl: String,
    private val providerKeys: ProviderKeys,
    private val httpClient: OkHttpClient,
) : OpenAiCompatProvider(
    prefix = prefix,
    displayName = displayName,
    baseUrl = baseUrl,
    providerKeys = providerKeys,
    httpClient = httpClient,
) {
    private val showJson = Json { ignoreUnknownKeys = true }

    override suspend fun listModelsWithContext(): List<ModelInfo> = withContext(Dispatchers.IO) {
        val names = listModels()
        // Probe each model for its real context window. Ollama's
        // /api/show returns model_info.<arch>.context_length for
        // most modern models. Failures fall through to null context
        // (compactor uses its default), so a single bad model
        // doesn't break the catalog.
        names.map { name ->
            val contextWindow = runCatching {
                val apiKey = providerKeys.keyFor(prefix).orEmpty()
                val request = Request.Builder()
                    .url("$baseUrl/api/show")
                    .post(
                        "{\"name\":\"$name\"}"
                            .toRequestBody("application/json".toMediaType()),
                    )
                if (apiKey.isNotEmpty()) request.header("Authorization", "Bearer $apiKey")
                httpClient.newCall(request.build()).execute().use { response ->
                    if (!response.isSuccessful) return@use null
                    val body = response.body?.string() ?: return@use null
                    showJson.parseToJsonElement(body).jsonObject["model_info"]
                        ?.jsonObject?.values?.firstOrNull { info ->
                            info.jsonObject["context_length"] != null
                        }?.jsonObject?.get("context_length")
                        ?.jsonPrimitive?.content?.toIntOrNull()
                }
            }.getOrNull()
            ModelInfo(name = name, contextWindow = contextWindow)
        }
    }
}
