package com.aura.providers

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Adapt a JSON Schema produced by [toJsonSchema] to the OpenAPI 3.0 subset
 * Gemini accepts for `functionDeclarations[].parameters` and `responseSchema`.
 *
 * Gemini used to have its own inline renderer here, which emitted only `type`,
 * `description` and `required` — so `enum` never reached the wire and a tool
 * with a constrained parameter got no constraint. Only `tavily_search` carries
 * an enum today, and `filterSearchTools` drops it from every request, so
 * nothing was visibly broken; the next enum added to a live tool would have
 * been dropped silently. Sharing [toJsonSchema] closes that, at the cost of
 * needing this adapter, because the shared renderer emits strict JSON Schema
 * and Gemini rejects several of its keywords outright.
 *
 * Two things this must do that a plain passthrough would not:
 *
 *  - **Strip unsupported keywords.** Gemini 400s on `$schema`, `$ref`, `$defs`,
 *    `additionalProperties`, `patternProperties` and `const`. Nothing generates
 *    them today — `ToolProperty` cannot express them — but `responseSchema`
 *    takes a caller-supplied [JsonObject], so the guard belongs here rather
 *    than in the caller.
 *  - **Keep the type coercion.** The old renderer mapped anything outside
 *    {integer, number, boolean, array} to `"string"`. Every `ToolProperty` in
 *    the tree is currently one of {string, integer, number, boolean}, so the
 *    fallback is inert — but it is the only thing standing between a future
 *    tool declaring an exotic type and a 400 from Gemini, and deleting a
 *    safety net because it has never fired is how it fires.
 */
internal fun sanitizeForGemini(schema: JsonObject): JsonObject = sanitizeObject(schema)

/** JSON Schema keywords Gemini's OpenAPI subset rejects. */
private val UNSUPPORTED_KEYS = setOf(
    "\$schema",
    "\$ref",
    "\$defs",
    "definitions",
    "additionalProperties",
    "patternProperties",
    "const",
)

/**
 * Keys whose values are themselves schemas (recurse) rather than plain data.
 * `properties` is handled separately: its values are schemas but its *keys*
 * are user-chosen parameter names that must never be treated as keywords.
 */
private val SCHEMA_VALUED_KEYS = setOf("items")

/** Types Gemini accepts verbatim. Anything else becomes `"string"`. */
private val SUPPORTED_TYPES = setOf("string", "integer", "number", "boolean", "array", "object")

private fun sanitizeObject(schema: JsonObject): JsonObject = buildJsonObject {
    schema.forEach { (key, value) ->
        when {
            key in UNSUPPORTED_KEYS -> Unit

            key == "type" -> put("type", JsonPrimitive(coerceType(value)))

            key == "properties" -> put(
                "properties",
                buildJsonObject {
                    (value as? JsonObject)?.forEach { (propName, propSchema) ->
                        // propName is a parameter name, not a keyword — never filtered.
                        put(propName, sanitizeValue(propSchema))
                    }
                },
            )

            key in SCHEMA_VALUED_KEYS -> put(key, sanitizeValue(value))

            else -> put(key, value)
        }
    }
}

private fun sanitizeValue(value: JsonElement): JsonElement = when (value) {
    is JsonObject -> sanitizeObject(value)
    is JsonArray -> buildJsonArray { value.forEach { add(sanitizeValue(it)) } }
    else -> value
}

/**
 * Map a declared type onto Gemini's accepted set, falling back to `"string"`.
 * A non-primitive `type` (JSON Schema permits an array of types; Gemini does
 * not) also collapses to `"string"` rather than being passed through.
 */
private fun coerceType(value: JsonElement): String {
    val declared = (value as? JsonPrimitive)?.contentOrNull
        ?: (value as? JsonArray)?.firstOrNull()?.jsonPrimitive?.contentOrNull
    return if (declared in SUPPORTED_TYPES) declared!! else "string"
}
