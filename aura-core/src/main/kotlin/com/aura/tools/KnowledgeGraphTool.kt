package com.aura.tools

import com.aura.agent.Tool
import com.aura.agent.ToolContext
import com.aura.agent.ToolResult
import com.aura.agent.ToolRisk
import com.aura.kg.EdgeType
import com.aura.kg.KgEdge
import com.aura.kg.KgId
import com.aura.kg.KgNode
import com.aura.kg.NodeType
import com.aura.providers.ChatOptions
import com.aura.providers.ProviderMessage
import com.aura.providers.ProviderRegistry
import com.aura.providers.ResponseFormat
import com.aura.providers.ResponseSchema
import com.aura.providers.StructuredJson
import com.aura.providers.ToolParameters
import com.aura.providers.ToolProperty
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Tool that extracts a knowledge graph (nodes + edges) from unstructured text
 * by calling a cloud LLM with a structured JSON prompt.
 *
 * Risk: READ_ONLY (network egress only).
 */
@Singleton
class KnowledgeGraphTool @Inject constructor(
    private val providerRegistry: ProviderRegistry,
    private val cheapModelResolver: com.aura.providers.CheapModelResolver? = null,
) {
    private val json = Json { ignoreUnknownKeys = true }

    private val allowedNodeTypes = NodeType.entries.map { it.name.lowercase() }
    private val allowedEdgeTypes = EdgeType.entries.map { it.name.lowercase() }

    fun definition() = ToolParameters(
        properties = mapOf(
            "text" to ToolProperty(
                type = "string",
                description = "The text to extract knowledge graph nodes and edges from",
            ),
        ),
        required = listOf("text"),
    )

    val tool = Tool(
        name = "knowledge_graph_extract",
        description = "Extract a knowledge graph (nodes and edges) from unstructured text. Returns JSON with nodes and edges.",
        risk = ToolRisk.REMOTE_COST,
        parameters = definition(),
        execute = { call, _ ->
            val text = call.arguments["text"] as? String
                ?: return@Tool ToolResult.Error("missing 'text' argument", "bad_args")

            try {
                val (nodes, edges) = extract(text)
                ToolResult.Ok(buildJsonResult(nodes, edges))
            } catch (e: Exception) {
                ToolResult.Error("Knowledge graph extraction failed: ${e.message}", "extraction_error")
            }
        },
    category = "knowledge")
    suspend fun extract(text: String): Pair<List<KgNode>, List<KgEdge>> {
        val response = callLlm(text)
        return parseResponse(response) ?: Pair(emptyList(), emptyList())
    }

    fun buildJsonResult(nodes: List<KgNode>, edges: List<KgEdge>): String {
        val nodesJson = nodes.joinToString(",", "[", "]") { node -> formatNodeJson(node) }
        val edgesJson = edges.joinToString(",", "[", "]") { edge -> formatEdgeJson(edge) }
        return """{"nodes":$nodesJson,"edges":$edgesJson}"""
    }

    /**
     * Build the extraction prompt and call the provider.
     */
    private suspend fun callLlm(text: String): String {
        val systemPrompt = buildString {
            appendLine("You are a knowledge graph extraction assistant.")
            appendLine("Extract nodes and edges from the given text as a JSON object.")
            appendLine()
            appendLine("Allowed node types: ${allowedNodeTypes.joinToString(", ")}")
            appendLine("Allowed edge types: ${allowedEdgeTypes.joinToString(", ")}")
            appendLine()
            appendLine("Return ONLY valid JSON with this exact structure:")
            appendLine("""{"nodes":[{"label":"...","type":"...","properties":{...}}],"edges":[{"type":"...","source_label":"...","target_label":"...","weight":0.5,"properties":{...}}]}""")
            appendLine()
            appendLine("Rules:")
            appendLine("- 'label' is a concise name for the node.")
            appendLine("- Refer to the speaker as label 'user' with type 'person'. Never use their real name, 'I', or 'me' for that node.")
            appendLine("- 'type' must be one of the allowed node types (default to 'unknown' if unsure).")
            appendLine("- 'properties' is optional (omit for empty).")
            appendLine("- 'source_label' and 'target_label' reference node labels from the nodes array.")
            appendLine("- 'type' for edges must be one of the allowed edge types (default to 'relates_to' if unsure).")
            appendLine("- 'weight' is optional (float 0.0-1.0, omit for default).")
            appendLine("- If no entities are found, return {\"nodes\":[],\"edges\":[]}.")
            appendLine("- Do NOT wrap in markdown code fences.")
        }

        val userPrompt = "Extract a knowledge graph from this text:\n\n$text"

        val messages = listOf(
            ProviderMessage(role = ProviderMessage.Role.system, content = systemPrompt),
            ProviderMessage(role = ProviderMessage.Role.user, content = userPrompt),
        )

        val options = ChatOptions(
            temperature = 0.2,
            maxTokens = 4096,
            responseFormat = ResponseFormat.JSON,
        )

        // Pick a model to drive the extraction.
        //
        // This used to pass the literal string "default", on the belief that
        // ProviderRegistry.parse resolved it to the user's default model. It
        // never did: parse() requires a non-blank `provider:model` pair, so
        // "default" always threw and the catch-all below was the only path
        // that had ever executed — meaning every extraction paid for a live
        // /models listing and then took whatever model happened to be first,
        // which is not a cheapness judgement at all.
        //
        // CheapModelResolver is the shared answer to exactly this question
        // (its KDoc names profile extraction as a target use case): the user's
        // explicit Fast model, else the cheapest model the heuristic can find
        // across configured providers. The first-configured-provider walk is
        // kept only as a genuine last resort, for the case where no catalog
        // could be listed at all. We still do NOT hardcode a provider:model —
        // that was the 2026-07-07 bug where ollama:deepseek-v4-pro was baked
        // in and crashed on users with no Ollama key.
        val model = cheapModelResolver?.resolve()
            ?: providerRegistry.configured()
                .firstOrNull()
                ?.let { p ->
                    val first = p.listModels().firstOrNull() ?: return@let null
                    "${p.prefix}:$first"
                }
            ?: throw IllegalStateException("No configured providers for knowledge graph extraction")

        // `options.responseFormat` was set here for a long time and read by
        // nothing; the schema below is what actually constrains the shape now.
        // The fence-stripping stays as a fallback inside StructuredJson — the
        // `removeSurrounding` pair it replaces silently no-opped unless BOTH
        // delimiters matched, so a reply opening with ```json and closing with
        // a bare ``` came through with its fence intact and failed to parse.
        return StructuredJson.requestJson(
            registry = providerRegistry,
            modelId = model,
            messages = messages,
            options = options,
            schema = KG_SCHEMA,
            timeoutMs = EXTRACTION_TIMEOUT_MS,
            tag = "KGTool",
        ) { it } ?: ""
    }

    /**
     * Parse the LLM response into lists of nodes and edges.
     * Returns null on parse failure (caller falls back to empty result).
     */
    private data class ParsedEdge(
        val type: String,
        val sourceLabel: String,
        val targetLabel: String,
        val weight: Float?,
        val properties: JsonObject?,
    )

    /**
     * `internal` rather than `private`: this is the seam that pins the
     * extractor-output -> [KgId.USER_NODE_ID] contract. The `Rules:` line in
     * [callLlm] that tells the model to label the speaker "user"/"person" is
     * only meaningful if parsing that exact label really produces
     * `KgId.USER_NODE_ID` — a test in this module drives this function
     * directly to verify that, rather than asserting against `KgId.node(...)`
     * (which would just restate the implementation).
     */
    internal fun parseResponse(response: String): Pair<List<KgNode>, List<KgEdge>>? {
        val root = try {
            json.parseToJsonElement(response).jsonObject
        } catch (_: Exception) {
            return null
        }

        val nodesJson = root["nodes"]?.jsonArray ?: return null
        val edgesJson = root["edges"]?.jsonArray ?: return null

        val nodes = nodesJson.mapNotNull { el -> parseNode(el) }
        val edges = edgesJson.mapNotNull { el -> parseEdge(el, nodes) }

        return Pair(nodes, edges)
    }

    private fun parseNode(el: JsonElement): KgNode? {
        val obj = el.jsonObject
        val label = obj["label"]?.jsonPrimitive?.contentOrNull?.trim() ?: return null
        if (label.isBlank()) return null

        val typeStr = obj["type"]?.jsonPrimitive?.contentOrNull ?: "unknown"
        val type = NodeType.from(typeStr)
        val properties = obj["properties"]?.jsonObject ?: JsonObject(emptyMap())

        val id = KgId.node(type, label)
        return KgNode(
            id = id,
            label = label,
            type = type,
            properties = properties,
        )
    }

    private fun parseEdge(el: JsonElement, nodes: List<KgNode>): KgEdge? {
        val obj = el.jsonObject
        val typeStr = obj["type"]?.jsonPrimitive?.contentOrNull ?: "relates_to"
        val edgeType = EdgeType.from(typeStr)
        if (edgeType == EdgeType.UNKNOWN && typeStr != "unknown") return null

        val sourceLabel = obj["source_label"]?.jsonPrimitive?.contentOrNull?.trim() ?: return null
        val targetLabel = obj["target_label"]?.jsonPrimitive?.contentOrNull?.trim() ?: return null

        if (sourceLabel.isBlank() || targetLabel.isBlank()) return null

        // Resolve source/target labels to node IDs
        val sourceNode = nodes.firstOrNull { it.label.equals(sourceLabel, ignoreCase = true) }
            ?: return null
        val targetNode = nodes.firstOrNull { it.label.equals(targetLabel, ignoreCase = true) }
            ?: return null

        val weight = obj["weight"]?.jsonPrimitive?.doubleOrNull?.toFloat()?.coerceIn(0f, 1f) ?: 0.5f
        val properties = obj["properties"]?.jsonObject ?: JsonObject(emptyMap())

        val id = KgId.edge(edgeType, sourceNode.id, targetNode.id)
        return KgEdge(
            id = id,
            type = edgeType,
            sourceId = sourceNode.id,
            targetId = targetNode.id,
            weight = weight,
            properties = properties,
        )
    }

    private fun formatNodeJson(node: KgNode): String {
        val props = if (node.properties.isEmpty()) "" else ",${node.properties.toString()}"
        return """{"id":"${escapeJson(node.id)}","label":"${escapeJson(node.label)}","type":"${node.type.name.lowercase()}"$props}"""
    }

    private fun formatEdgeJson(edge: KgEdge): String {
        val props = if (edge.properties.isEmpty()) "" else ",${edge.properties.toString()}"
        return """{"id":"${escapeJson(edge.id)}","type":"${edge.type.name.lowercase()}","source_id":"${escapeJson(edge.sourceId)}","target_id":"${escapeJson(edge.targetId)}","weight":${edge.weight}$props}"""
    }

    private fun escapeJson(s: String): String = s
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
        .replace("\t", "\\t")

    private companion object {
        /** Generous: 4096 output tokens of graph over a long conversation turn. */
        const val EXTRACTION_TIMEOUT_MS = 30_000L

        /**
         * The shape `parseResponse` already expects. Notably it does NOT
         * constrain `type` with an `enum` of the allowed node/edge types even
         * though those lists exist: the prompt names them, unknown types map to
         * `unknown` by design (see the test of the same name), and a hard enum
         * would make the model drop an entity it could not classify rather than
         * hand back something the mapper can salvage.
         *
         * `properties` is a free-form object, which is the one part Gemini
         * cannot express — its OpenAPI subset wants typed properties. That is
         * survivable: `sanitizeForGemini` passes the bare `{"type":"object"}`
         * through, and a Gemini reply that omits `properties` parses fine
         * because `parseResponse` treats it as optional.
         */
        val KG_SCHEMA = ResponseSchema(
            name = "extract_knowledge_graph",
            schema = buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject {
                    put("nodes", buildJsonObject {
                        put("type", "array")
                        put("items", buildJsonObject {
                            put("type", "object")
                            put("properties", buildJsonObject {
                                put("label", buildJsonObject { put("type", "string") })
                                put("type", buildJsonObject { put("type", "string") })
                                put("properties", buildJsonObject { put("type", "object") })
                            })
                            put("required", buildJsonArray {
                                add(JsonPrimitive("label"))
                                add(JsonPrimitive("type"))
                            })
                        })
                    })
                    put("edges", buildJsonObject {
                        put("type", "array")
                        put("items", buildJsonObject {
                            put("type", "object")
                            put("properties", buildJsonObject {
                                put("type", buildJsonObject { put("type", "string") })
                                put("source_label", buildJsonObject { put("type", "string") })
                                put("target_label", buildJsonObject { put("type", "string") })
                                put("weight", buildJsonObject { put("type", "number") })
                                put("properties", buildJsonObject { put("type", "object") })
                            })
                            put("required", buildJsonArray {
                                add(JsonPrimitive("type"))
                                add(JsonPrimitive("source_label"))
                                add(JsonPrimitive("target_label"))
                            })
                        })
                    })
                })
                put("required", buildJsonArray {
                    add(JsonPrimitive("nodes"))
                    add(JsonPrimitive("edges"))
                })
            },
        )
    }
}
