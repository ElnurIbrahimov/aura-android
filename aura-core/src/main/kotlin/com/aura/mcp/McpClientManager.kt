package com.aura.mcp

import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages MCP server connections, tool discovery, and tool invocation.
 *
 * This is the initial lightweight implementation using direct HTTP.
 * When the official Kotlin MCP SDK (io.modelcontextprotocol:kotlin-sdk)
 * is validated on Android, this will be swapped to use the SDK's
 * transport and protocol types while keeping the same public API.
 *
 * Security rules:
 * - HTTPS by default; HTTP only for trusted local endpoints
 * - SSRF validation on every server URL (including trusted local)
 * - DNS-pinned HTTP client prevents redirect-based bypass
 * - Remote risk annotations from MCP servers are hints, not authority
 * - All tool calls pass through local ToolPolicy classification
 * - Response size is bounded
 * - Connection timeout and retry are enforced
 */
@Singleton
class McpClientManager @Inject constructor(
    private val httpClient: okhttp3.OkHttpClient,
) {
    private val connections = ConcurrentHashMap<kotlin.String, McpConnection>()

    /**
     * Connect to an MCP server. Returns the connection health.
     */
    suspend fun connect(config: McpServerConfig, authToken: kotlin.String? = null): McpServerHealth {
        // SSRF validation. Every MCP server URL is validated regardless of
        // trustedLocal. The SsrfGuard blocks localhost, private IPs, cloud
        // metadata endpoints (169.254.x.x), and link-local addresses. For
        // trusted local servers, the user explicitly acknowledged the risk,
        // so we skip the localhost block but still filter non-routable IPs.
        val ssrfResult = if (config.trustedLocal) {
            // For trusted local servers: allow localhost/loopback but still
            // block cloud metadata (169.254.x.x), link-local, and other
            // dangerous non-routable ranges. We use a custom resolver that
            // allows localhost to pass through the guard.
            validateTrustedLocal(config.url)
        } else {
            com.aura.core.url.SsrfGuard.inspect(config.url)
        }

        when (ssrfResult) {
            is com.aura.core.url.SsrfValidation.Blocked -> {
                return McpServerHealth(
                    serverId = config.id,
                    state = McpConnectionState.ERROR,
                    lastError = "SSRF blocked: ${ssrfResult.reason}",
                )
            }
            is com.aura.core.url.SsrfValidation.Safe -> {
                if (!config.trustedLocal && !config.url.startsWith("https://")) {
                    return McpServerHealth(
                        serverId = config.id,
                        state = McpConnectionState.ERROR,
                        lastError = "Non-local servers must use HTTPS",
                    )
                }
                // Use a DNS-pinned client so the actual HTTP call cannot
                // resolve to a different address than what we validated.
                val pinnedClient = com.aura.core.url.SsrfGuard.pinnedClient(httpClient, ssrfResult)
                val connection = McpConnection(config, pinnedClient, authToken)
                val health = connection.initialize()
                if (health.state == McpConnectionState.CONNECTED) {
                    connections[config.id] = connection
                }
                return health
            }
        }
    }

    /**
     * Validate a trusted-local URL. Allows localhost and loopback but still
     * blocks cloud metadata endpoints (169.254.x.x), link-local, site-local
     * private ranges (10.x, 172.16-31, 192.168.x), and other non-routable
     * addresses that are dangerous even on a LAN.
     */
    private fun validateTrustedLocal(url: kotlin.String): com.aura.core.url.SsrfValidation {
        val uri = try {
            java.net.URI(url)
        } catch (e: Exception) {
            return com.aura.core.url.SsrfValidation.Blocked("invalid URL: ${e.message}")
        }
        val scheme = uri.scheme?.lowercase().orEmpty()
        if (scheme != "http" && scheme != "https") {
            return com.aura.core.url.SsrfValidation.Blocked("only http/https URLs are allowed")
        }
        val host = uri.host?.removeSuffix(".")?.lowercase()
            ?: return com.aura.core.url.SsrfValidation.Blocked("URL has no host")

        // Allow localhost explicitly
        if (host == "localhost" || host == "localhost.localdomain" || host == "127.0.0.1" || host == "::1") {
            return com.aura.core.url.SsrfValidation.Safe(
                url = url,
                host = host,
                addresses = listOf(java.net.InetAddress.getByName(host)),
            )
        }

        // For non-localhost hosts, defer to the full SsrfGuard which checks
        // all DNS answers against the non-routable range list.
        return com.aura.core.url.SsrfGuard.inspect(url)
    }

    /**
     * Disconnect from a server and clean up resources.
     */
    suspend fun disconnect(serverId: kotlin.String) {
        connections.remove(serverId)?.disconnect()
    }

    /**
     * List all discovered tools from a connected server.
     */
    suspend fun listTools(serverId: kotlin.String): List<McpToolInfo> {
        val conn = connections[serverId]
            ?: return emptyList()
        return conn.listTools()
    }

    /**
     * Call a tool on a connected MCP server.
     */
    suspend fun callTool(
        serverId: kotlin.String,
        toolName: kotlin.String,
        arguments: Map<kotlin.String, Any?>,
        timeoutMs: kotlin.Long = 30_000L,
    ): McpToolResult {
        val conn = connections[serverId]
            ?: return McpToolResult.Failure("Server $serverId is not connected", "not_connected")
        val config = conn.config

        // Check deny list
        if (toolName in config.deniedTools) {
            return McpToolResult.Failure("Tool $toolName is denied for server ${config.name}", "denied")
        }

        // Check prefix allow list
        if (config.allowedToolPrefixes.isNotEmpty()) {
            val allowed = config.allowedToolPrefixes.any { prefix -> toolName.startsWith(prefix) }
            if (!allowed) {
                return McpToolResult.Failure("Tool $toolName does not match any allowed prefix", "prefix_denied")
            }
        }

        return conn.callTool(toolName, arguments, timeoutMs)
    }

    /**
     * Get health for all connected servers.
     */
    fun healthSnapshot(): List<McpServerHealth> =
        connections.values.map { it.health }

    /**
     * Get a list of all connected server IDs.
     */
    fun connectedServerIds(): List<kotlin.String> =
        connections.filter { it.value.isConnected() }.keys.toList()

    /**
     * List resources from a connected server.
     */
    suspend fun listResources(serverId: kotlin.String): List<McpResourceInfo> {
        val conn = connections[serverId] ?: return emptyList()
        return conn.listResources()
    }
}