package com.aura.mcp

import com.aura.agent.DEFAULT_TOOL_TIMEOUT_MS
import com.aura.agent.Tool
import com.aura.agent.ToolCall
import com.aura.agent.ToolContext
import java.util.concurrent.ConcurrentHashMap
import com.aura.agent.ToolRegistry
import com.aura.agent.ToolResult
import com.aura.agent.ToolRisk
import com.aura.providers.ToolParameters
import com.aura.providers.ToolProperty
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Bridges MCP-discovered tools into the native [ToolRegistry] so the
 * agentic loop can see and call them.
 *
 * MCP tools are registered with a prefixed name: `mcp_<serverId>_<toolName>`
 * to avoid collisions with native tools. If a native tool with the same
 * base name already exists, the MCP version is still registered with the
 * prefix — the LLM sees both and can choose either.
 *
 * Tool risk is derived from the tool's own MCP annotations, not from what it
 * costs. Every MCP tool used to be [ToolRisk.REMOTE_COST] on the reasoning that
 * "they call external network endpoints that may consume paid API credits" —
 * a statement about billing, applied to tools whose capabilities this bridge
 * cannot know.
 *
 * That mattered because `REMOTE_COST` sits *below* `WRITE_LOCAL` in the ordinal
 * order that four separate gates compare against (see [ToolRiskOrdinalAuditTest]).
 * A third-party server exposing a write tool therefore ran during incognito,
 * whose promise is that the session "cannot write memory or profile facts", and
 * was recorded as no world event at all — the model's own history of what it did
 * simply missing those calls.
 *
 * So: `readOnlyHint` keeps `REMOTE_COST`, which is now an accurate statement
 * rather than a lucky one — a read-only tool writes nothing, so it *should* run
 * in incognito, and it still costs, so it should still hit the per-run cost
 * approval. `destructiveHint` maps to [ToolRisk.DESTRUCTIVE]. **Anything
 * unstated becomes [ToolRisk.WRITE_REMOTE]**, above the write boundary, because
 * "nobody said whether this writes" is not the same fact as "this does not
 * write", and only one of the two is safe to assume about someone else's server.
 *
 * The cost of failing closed is a confirmation prompt on unannotated tools. A
 * server that publishes annotations — which the protocol asks for — pays nothing.
 *
 * The local security controls (deny list, prefix allow list, response
 * bounding) are enforced by [McpClientManager] before the call is
 * dispatched.
 */
/**
 * What an MCP tool is allowed to do, as far as its server will say.
 *
 * Deliberately total and deliberately pessimistic about silence. `null` is the
 * common case — annotations are optional in the protocol — and it is the case
 * that decides whether this change is worth anything.
 */
internal fun mcpToolRisk(readOnlyHint: Boolean?, destructiveHint: Boolean?): ToolRisk = when {
    destructiveHint == true -> ToolRisk.DESTRUCTIVE
    readOnlyHint == true -> ToolRisk.REMOTE_COST
    else -> ToolRisk.WRITE_REMOTE
}

@Singleton
class McpToolBridge @Inject constructor(
    private val mcpClientManager: McpClientManager,
    private val toolRegistry: ToolRegistry,
) {
    private val json = Json { ignoreUnknownKeys = true }

    /** Track which MCP tool names we've registered so we can clean up. */
    private val registeredNames = ConcurrentHashMap.newKeySet<kotlin.String>()

    /** Maps registered tool names (both prefixed and bare) back to their owning server id. */
    private val registeredNameToServerId = ConcurrentHashMap<kotlin.String, kotlin.String>()

    /**
     * Discover tools from all connected MCP servers and register them
     * in the [ToolRegistry]. Call this after servers are connected
     * (e.g. at app startup or when the user adds a new server).
     *
     * Servers that are not connected are skipped silently.
     */
    suspend fun syncTools(servers: List<McpServerConfig>) {
        // Unregister tools from servers that are no longer in the list
        val currentServerIds = servers.map { it.id }.toSet()
        val connectedServerIds = mcpClientManager.connectedServerIds()
        val staleNames = registeredNames.filter { name ->
            // Use the ownership map instead of parsing the name —
            // serverIds can contain underscores, so name-based parsing
            // is unreliable (e.g. "mcp_my_server_tool" would extract "my"
            // instead of "my_server").
            registeredNameToServerId[name]?.let { serverId ->
                serverId !in currentServerIds || serverId !in connectedServerIds
            } ?: false
        }
        for (name in staleNames) {
            toolRegistry.unregister(name)
            registeredNames.remove(name)
            registeredNameToServerId.remove(name)
        }

        // Register tools from connected servers
        for (config in servers) {
            if (!config.enabled) continue
            if (config.id !in connectedServerIds) continue

            val tools = mcpClientManager.listTools(config.id)
            for (mcpTool in tools) {
                // Use the base name if no native tool has it; otherwise use the prefixed name.
                // This way an MCP search tool can be called as "tavily_search" by the LLM
                // (overriding the native one) if the user intentionally connected it via MCP.
                val nativeExists = toolRegistry.get(mcpTool.name) != null
                val registeredName = if (nativeExists) mcpToolName(config.id, mcpTool.name) else mcpTool.name

                val tool = Tool(
                    name = registeredName,
                    description = mcpTool.description.ifBlank { "MCP tool: ${mcpTool.name} (${config.name})" },
                    risk = mcpToolRisk(mcpTool.readOnlyHint, mcpTool.destructiveHint),
                    parameters = parseSchema(mcpTool.inputSchemaJson),
                    execute = { call, ctx ->
                        val result = mcpClientManager.callTool(
                            serverId = config.id,
                            toolName = mcpTool.name,
                            arguments = call.arguments,
                            // Same rule as ToolExecutor: an explicit caller
                            // budget wins, otherwise the default. A remote MCP
                            // server declares no budget of its own, so there is
                            // no per-tool value to fall back to here.
                            timeoutMs = ctx.timeout ?: DEFAULT_TOOL_TIMEOUT_MS,
                        )
                        when (result) {
                            is McpToolResult.Success -> {
                                if (result.isError) {
                                    ToolResult.Error(result.output, "mcp_tool_error")
                                } else {
                                    ToolResult.Ok(result.output)
                                }
                            }
                            is McpToolResult.Failure -> {
                                ToolResult.Error(result.message, result.code)
                            }
                            is McpToolResult.Timeout -> {
                                ToolResult.Error("MCP server ${result.serverName} timed out", "mcp_timeout")
                            }
                        }
                    },
                    category = "mcp",
                )
                toolRegistry.register(tool)
                registeredNames.add(registeredName)
                registeredNameToServerId[registeredName] = config.id
            }
        }
    }

    /**
     * Names currently registered by this bridge.
     *
     * Kept although only tests call it: it is the only way to observe what
     * [syncTools] did, and what it does is both halves of the lifecycle —
     * registering a connected server's tools and unregistering the ones whose
     * server left the list or dropped its connection. A registry that silently
     * keeps a disconnected server's tools shows the model tools that cannot run.
     */
    fun registeredToolNames(): Set<kotlin.String> = registeredNames.toSet()

    private fun mcpToolName(serverId: kotlin.String, toolName: kotlin.String): kotlin.String =
        "mcp_${serverId}_${toolName}"

    /**
     * Parse an MCP tool's JSON Schema input schema into [ToolParameters].
     * MCP tools return a JSON Schema object with "properties" and "required".
     */
    private fun parseSchema(schemaJson: kotlin.String): ToolParameters {
        if (schemaJson.isBlank() || schemaJson == "{}") {
            return ToolParameters()
        }
        return try {
            val schema = json.parseToJsonElement(schemaJson).jsonObject
            val properties = schema["properties"]?.jsonObject ?: JsonObject(emptyMap())
            val required = schema["required"]?.let { req ->
                req.toString().removeSurrounding("[", "]")
                    .split(",")
                    .map { it.trim().removeSurrounding("\"") }
                    .filter { it.isNotBlank() }
            } ?: emptyList()

            val props = properties.entries.associate { (key, value) ->
                val propObj = value.jsonObject
                val type = propObj["type"]?.jsonPrimitive?.contentOrNull ?: "string"
                val description = propObj["description"]?.jsonPrimitive?.contentOrNull
                val enumValues = propObj["enum"]?.let { enumArr ->
                    enumArr.toString().removeSurrounding("[", "]")
                        .split(",")
                        .map { it.trim().removeSurrounding("\"") }
                        .filter { it.isNotBlank() }
                } ?: emptyList()
                key to ToolProperty(
                    type = type,
                    description = description,
                    enum = enumValues,
                )
            }
            ToolParameters(properties = props, required = required)
        } catch (e: Exception) {
            // If schema parsing fails, return empty params — the LLM will
            // still see the tool name and description and can try calling it.
            ToolParameters()
        }
    }
}