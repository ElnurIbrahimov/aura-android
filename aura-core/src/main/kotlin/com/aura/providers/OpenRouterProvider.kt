package com.aura.providers

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import android.util.Log

/**
 * OpenRouter provider — a thin wrapper around [OpenAiCompatProvider].
 *
 * OpenRouter is a unified API gateway that provides access to many LLMs
 * through a single OpenAI-compatible endpoint. It requires specific headers
 * for identification.
 *
 * Base URL: https://openrouter.ai/api/v1
 * Default models: derived from live /v1/models endpoint
 *
 * Required headers:
 * - HTTP-Referer: "https://aura-android"
 * - X-Title: "Aura Android"
 *
 * The API key is read from [ProviderKeys] on every [chat] call, not at
 * construction time, so changes the user makes in the Settings UI take
 * effect immediately without restarting the app.
 */
class OpenRouterProvider(
    providerKeys: ProviderKeys,
    httpClient: OkHttpClient,
    baseUrl: String = "https://openrouter.ai/api/v1",
) : OpenAiCompatProvider(
    prefix = "openrouter",
    displayName = "OpenRouter",
    baseUrl = baseUrl,
    providerKeys = providerKeys,
    httpClient = httpClient.newBuilder()
        .addInterceptor(Interceptor { chain ->
            val request = chain.request().newBuilder()
                .addHeader("HTTP-Referer", "https://aura-android")
                .addHeader("X-Title", "Aura Android")
                .build()

            chain.proceed(request)
        })
        .build(),
) {
    private val showJson = Json { ignoreUnknownKeys = true }

    /**
     * OpenRouter's /api/v1/models returns a `context_length`
     * field per model — the real context window from the
     * upstream provider. This overrides the base class
     * which would fall back to the hardcoded table.
     */
    override suspend fun listModelsWithContext(): List<ModelInfo> = withContext(Dispatchers.IO) {
        val names = listModels()
        val ctxByName = runCatching {
            val request = Request.Builder()
                .url("$baseUrl/models")
                .build()
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use emptyMap<String, Int>()
                val body = response.body?.string() ?: return@use emptyMap()
                val data = showJson.parseToJsonElement(body).jsonObject?.get("data") as? JsonArray
                    ?: return@use emptyMap()
                data.mapNotNull { item ->
                    val obj = item as? JsonObject ?: return@mapNotNull null
                    val id = (obj["id"] as? kotlinx.serialization.json.JsonPrimitive)
                        ?.jsonPrimitive?.content ?: return@mapNotNull null
                    val ctx = (obj["context_length"] as? kotlinx.serialization.json.JsonPrimitive)
                        ?.jsonPrimitive?.content?.toIntOrNull()
                    if (ctx != null) id to ctx else null
                }.toMap()
            }
        }.onFailure { Log.w("OpenRouter", "op failed: ${it.message}", it) }.getOrDefault(emptyMap())
        names.map { name -> ModelInfo(name = name, contextWindow = ctxByName[name]) }
    }
}