package com.aura.providers

import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Write the OpenAI Chat Completions `response_format` key, or nothing.
 *
 * Shared by [OpenAiCompatProvider] and [CustomOpenAiCompatProvider], which
 * build their request bodies separately. Only this fragment is shared, not the
 * whole body: `CustomOpenAiCompatProvider` deliberately does not extend
 * `OpenAiCompatProvider`, and its body sits inside an SSRF-guarded path, so a
 * full dedupe is a larger change than it looks and belongs on its own.
 *
 * Emitting nothing in the text case matters. `response_format` is not
 * universally supported across the twelve prefixes that ride
 * `OpenAiCompatProvider`, and a strict endpoint 400s on keys it does not know —
 * so the absent case must stay byte-identical to what shipped before. Same
 * discipline as `injectThinking`, which returns early on a null budget.
 */
internal fun JsonObjectBuilder.putOpenAiResponseFormat(options: ChatOptions) {
    val schema = options.responseSchema
    when {
        schema != null -> put(
            "response_format",
            buildJsonObject {
                put("type", "json_schema")
                put(
                    "json_schema",
                    buildJsonObject {
                        put("name", schema.name)
                        put("strict", schema.strict)
                        put("schema", schema.schema)
                    },
                )
            },
        )

        options.responseFormat == ResponseFormat.JSON -> put(
            "response_format",
            buildJsonObject { put("type", "json_object") },
        )
    }
}
