package com.aura.ui.screens.chat

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * A provider error split into something a person can read and the raw
 * text behind it.
 *
 * @property headline one sentence, always safe to show.
 * @property details the original string, or null when it adds nothing
 *   beyond the headline. Shown behind a "Details" disclosure.
 */
data class ErrorPresentation(
    val headline: String,
    val details: String?,
)

private val lenientJson = Json { ignoreUnknownKeys = true; isLenient = true }

/**
 * Turn a raw provider error into a headline plus optional details.
 *
 * Providers answer a rejected request with a JSON envelope, and since
 * OpenAiCompatProvider started surfacing the response body (rather than
 * discarding it and showing a bare "HTTP 400") the whole envelope reached
 * the chat UI verbatim:
 *
 *     HTTP 400: {"error":{"message":"Invalid schema for function
 *     'image_generate': schema must be a JSON Schema of 'type: "object"',
 *     got 'type: null'.","type":"invalid_request_error","param":null,
 *     "code":"invalid_request_error"}}
 *
 * The useful sentence is in there, wrapped in punctuation nobody should
 * have to read. This lifts `error.message` out for the headline and keeps
 * the original as details, so the diagnostic value survives without the
 * chat window turning into a console.
 *
 * Anything that isn't a recognisable envelope is passed through unchanged
 * with no details — a plain message needs no disclosure.
 */
fun presentError(raw: String): ErrorPresentation {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return ErrorPresentation("Something went wrong.", null)

    val braceAt = trimmed.indexOf('{')
    if (braceAt < 0) return ErrorPresentation(trimmed, null)

    val message = runCatching {
        val root = lenientJson.parseToJsonElement(trimmed.substring(braceAt)).jsonObject
        // OpenAI-compatible: {"error":{"message":...}}. Some providers put
        // the message at the top level instead.
        val errorObject = root["error"] as? JsonObject
        (errorObject?.get("message") ?: root["message"])?.jsonPrimitive?.contentOrNull
    }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() }
        ?: return ErrorPresentation(trimmed, null)

    // Keep the HTTP status prefix — "400" tells the user (and us) whether
    // this is their request or the provider falling over.
    val statusPrefix = trimmed.substring(0, braceAt).trim().trimEnd(':').trim()
    val headline = if (statusPrefix.isNotEmpty()) "$statusPrefix — $message" else message
    return ErrorPresentation(headline = headline, details = trimmed)
}
