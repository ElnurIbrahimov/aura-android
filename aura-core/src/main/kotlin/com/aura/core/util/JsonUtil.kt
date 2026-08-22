package com.aura.core.util

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Build a Firecrawl scrape request body safely. Centralizes the JSON shape
 * so callers don't hand-roll JSON strings.
 */
fun buildFirecrawlBody(url: String, formats: List<String> = listOf("markdown")): String {
    return buildJsonObject {
        put("url", url)
        put("formats", JsonArray(formats.map { JsonPrimitive(it) }))
    }.toString()
}
