package com.aura.mcp

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
 * Tool risk is [ToolRisk.REMOTE_COST] for MCP tools by default: they
 * call external network endpoints that may consume paid API credits.
 * The local security controls (deny list, prefix allow list, response
 * bounding) are enforced by [McpClientManager] before the call is
 * dispatched.
 */
@Singleton
class McpToolBridge @Inject constructor(
    private val mcpClientManager: McpClientManager,
    private val toolRegistry: ToolRegistry,
) {
    private val json = Json { ignoreUnknownKeys = true }

    /** Track which MCP tool names we've registered so we can clean up. */
    private val registeredNames = ConcurrentHashMap.newKeySet<kotlin.String>()

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
        val staleNames = registeredNames.filter { name ->
            val serverId = extractServerId(name)
            serverId !in currentServerIds
        }
        for (name in staleNames) {
            toolRegistry.unregister(name)
            registeredNames.remove(name)
        }

        // Register tools from connected servers
        for (config in servers) {
            if (!config.enabled) continue
            val connected = mcpClientManager.connectedServerIds()
            if (config.id !in connected) continue

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
                    risk = ToolRisk.REMOTE_COST,
                    parameters = parseSchema(mcpTool.inputSchemaJson),
                    execute = { call, _ ->
                        val result = mcpClientManager.callTool(
                            serverId = config.id,
                            toolName = mcpTool.name,
                            arguments = call.arguments,
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
            }
        }
    }

    /**
     * Register MCP tools with their base name (no prefix) when the user
     * wants them to replace or act as a native tool. This is useful when
     * the user connects a Tavily MCP server and wants `tavily_search`
     * to route through MCP instead of the native tool.
     *
     * Only call this for tools whose base name does NOT collide with
     * an existing native tool, or when you intentionally want to override.
     */
    suspend fun syncToolsUnprefixed(servers: List<McpServerConfig>) {
        for (config in servers) {
            if (!config.enabled) continue
            val connected = mcpClientManager.connectedServerIds()
            if (config.id !in connected) continue

            val tools = mcpClientManager.listTools(config.id)
            for (mcpTool in tools) {
                val tool = Tool(
                    name = mcpTool.name,
                    description = mcpTool.description.ifBlank { "MCP tool: ${mcpTool.name} (${config.name})" },
                    risk = ToolRisk.REMOTE_COST,
                    parameters = parseSchema(mcpTool.inputSchemaJson),
                    execute = { call, _ ->
                        val result = mcpClientManager.callTool(
                            serverId = config.id,
                            toolName = mcpTool.name,
                            arguments = call.arguments,
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
                registeredNames.add(mcpTool.name)
            }
        }
    }

    /** Remove all MCP-registered tools from the registry. */
    fun unregisterAll() {
        for (name in registeredNames) {
            toolRegistry.unregister(name)
        }
        registeredNames.clear()
    }

    /** Names currently registered by this bridge. */
    fun registeredToolNames(): Set<kotlin.String> = registeredNames.toSet()

    private fun mcpToolName(serverId: kotlin.String, toolName: kotlin.String): kotlin.String =
        "mcp_${serverId}_${toolName}"

    private fun extractServerId(registeredName: kotlin.String): kotlin.String? {
        if (!registeredName.startsWith("mcp_")) return null
        val rest = registeredName.removePrefix("mcp_")
        // serverId was lowercased + sanitized, so it won't contain underscores
        // that weren't in the original name. Find the first segment.
        val firstUnderscore = rest.indexOf('_')
        return if (firstUnderscore > 0) rest.substring(0, firstUnderscore) else null
    }

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