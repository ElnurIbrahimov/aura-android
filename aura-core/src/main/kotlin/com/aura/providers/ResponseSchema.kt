package com.aura.providers

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * A JSON Schema the model's reply must match, carried on [ChatOptions].
 *
 * Deliberately a raw [JsonObject] rather than [ToolParameters]. `ToolProperty`
 * is a flat `type: String` with no `items` and no nested `properties` — it
 * cannot express `{nodes: [{label, type, properties}], edges: [...]}`, which is
 * exactly the shape `KnowledgeGraphTool` needs, and it is the schema type most
 * likely to grow the same way. Tool schemas and response schemas only look
 * alike; tying them together would force every future response schema through
 * the narrowest of the two.
 *
 * @property name identifies the schema to the provider. OpenAI requires it;
 *   Anthropic uses it as the name of the tool it is forced to call, which is
 *   why it should read like a verb phrase the model can make sense of
 *   (`extract_knowledge_graph`, not `schema_1`).
 * @property schema the JSON Schema itself. Providers may adapt it — Gemini
 *   strips the keywords its OpenAPI subset rejects, via `sanitizeForGemini`.
 * @property strict OpenAI's `strict` flag, which turns schema adherence from a
 *   strong suggestion into a constrained decode. On by default: a caller asking
 *   for a schema wants the schema. Providers without an equivalent ignore it.
 */
@Serializable
data class ResponseSchema(
    val name: String,
    val schema: JsonObject,
    val strict: Boolean = true,
)
