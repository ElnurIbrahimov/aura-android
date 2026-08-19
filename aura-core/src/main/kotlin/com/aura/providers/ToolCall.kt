package com.aura.providers

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ToolCall(
    val id: String,
    val name: String,
    val arguments: String, // raw JSON string; parsed lazily
)

@Serializable
/**
 * Lightweight, public-safe description of a tool — name, what it
 * does, the arguments it accepts, and the UI category it belongs
 * to. Returned by [com.aura.agent.ToolRegistry.definitions] for
 * the LLM prompt and for the Tools browser screen. Never includes
 * the `execute` lambda or risk metadata — those are private to the
 * executor.
 */
data class ToolDefinition(
    val name: String,
    val description: String,
    val parameters: ToolParameters,
    /**
     * UI category used by the Tools browser screen. See
     * [com.aura.agent.ToolCategories] for the list of valid values.
     * Empty string = "other" (default if a tool hasn't been
     * categorized yet).
     */
    val category: String = "",
)

@Serializable
data class ToolParameters(
    val type: String = "object",
    val properties: Map<String, ToolProperty> = emptyMap(),
    val required: List<String> = emptyList(),
)

@Serializable
data class ToolProperty(
    val type: String,
    val description: String? = null,
    val enum: List<String> = emptyList(),
    @SerialName("default") val defaultValue: kotlinx.serialization.json.JsonElement? = null,
)

/**
 * Render [ToolParameters] as a JSON Schema object for the wire.
 *
 * This must NOT go through `Json.encodeToString(ToolParameters.serializer())`.
 * The default `Json` instance has `encodeDefaults = false`, so kotlinx omits
 * any field still holding its declared default — which silently dropped
 * `"type": "object"` (and `"properties": {}` on no-argument tools) from every
 * tool schema. Schema-validating providers reject that outright:
 *
 *   HTTP 400 "Invalid schema for function 'image_generate': schema must be a
 *   JSON Schema of 'type: "object"', got 'type: null'."
 *
 * Flipping `encodeDefaults = true` would fix `type` but start emitting
 * `"enum": []` and `"default": null`, which strict validators also reject
 * (JSON Schema requires `enum` to be non-empty). So the shape is built
 * explicitly: required keys always present, optional keys only when they
 * carry a value.
 *
 * Shared by the OpenAI-compatible `function.parameters` and Anthropic's
 * `input_schema` — both take the same JSON Schema shape.
 */
internal fun ToolParameters.toJsonSchema(): kotlinx.serialization.json.JsonObject =
    kotlinx.serialization.json.buildJsonObject {
        put("type", kotlinx.serialization.json.JsonPrimitive(type))
        put(
            "properties",
            kotlinx.serialization.json.buildJsonObject {
                properties.forEach { (name, property) ->
                    put(
                        name,
                        kotlinx.serialization.json.buildJsonObject {
                            put("type", kotlinx.serialization.json.JsonPrimitive(property.type))
                            property.description?.let {
                                put("description", kotlinx.serialization.json.JsonPrimitive(it))
                            }
                            if (property.enum.isNotEmpty()) {
                                put(
                                    "enum",
                                    kotlinx.serialization.json.JsonArray(
                                        property.enum.map { kotlinx.serialization.json.JsonPrimitive(it) },
                                    ),
                                )
                            }
                            property.defaultValue?.let { put("default", it) }
                        },
                    )
                }
            },
        )
        if (required.isNotEmpty()) {
            put(
                "required",
                kotlinx.serialization.json.JsonArray(
                    required.map { kotlinx.serialization.json.JsonPrimitive(it) },
                ),
            )
        }
    }

/**
 * [toJsonSchema] for callers outside this package.
 *
 * The renderer is `internal` so nothing outside the provider layer builds tool
 * schemas by hand; the realtime session legitimately needs the same shape and
 * would otherwise reimplement it — which is precisely the divergence that let
 * Gemini drop `enum` from every tool schema.
 */
fun toolParametersJson(parameters: ToolParameters): kotlinx.serialization.json.JsonObject =
    parameters.toJsonSchema()
