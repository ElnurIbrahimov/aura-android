package com.aura.providers

import okhttp3.OkHttpClient

/**
 * Groq provider — a thin wrapper around [OpenAiCompatProvider].
 *
 * Base URL: https://api.groq.com/openai/v1/
 * Models: discovered live via /v1/models endpoint (no hardcoded defaults).
 *
 * The API key is read from [ProviderKeys] on every [chat] call, not at
 * construction time, so changes the user makes in the Settings UI take
 * effect immediately without restarting the app.
 */
class GroqProvider(
    providerKeys: ProviderKeys,
    httpClient: OkHttpClient,
    baseUrl: String = "https://api.groq.com/openai/v1",
) : OpenAiCompatProvider(
    prefix = "groq",
    displayName = "Groq",
    baseUrl = baseUrl,
    providerKeys = providerKeys,
    httpClient = httpClient,
)
