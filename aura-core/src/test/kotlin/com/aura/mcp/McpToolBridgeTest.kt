package com.aura.mcp

import com.aura.agent.Tool
import com.aura.agent.ToolRegistry
import com.aura.agent.ToolRisk
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class McpToolBridgeTest {

    private lateinit var mcpClientManager: McpClientManager
    private lateinit var toolRegistry: ToolRegistry
    private lateinit var bridge: McpToolBridge

    @Before
    fun setUp() {
        mcpClientManager = mockk(relaxed = true)
        toolRegistry = ToolRegistry()
        bridge = McpToolBridge(mcpClientManager, toolRegistry)
    }

    private fun mockTool(serverId: kotlin.String, name: kotlin.String, desc: kotlin.String = ""): McpToolInfo =
        McpToolInfo(serverId = serverId, name = name, description = desc, inputSchemaJson = "{}", serverName = "Test")

    @Test
    fun `unregisterAll clears registered tools`() = runTest {
        val config = McpServerConfig(id = "srv1", name = "Test", url = "http://localhost:3000")
        every { mcpClientManager.connectedServerIds() } returns listOf("srv1")
        coEvery { mcpClientManager.listTools("srv1") } returns listOf(mockTool("srv1", "search", "Search the web"))

        bridge.syncTools(listOf(config))
        assertEquals(1, bridge.registeredToolNames().size)

        bridge.unregisterAll()
        assertEquals(0, bridge.registeredToolNames().size)
        assertNull(toolRegistry.get("search"))
    }

    @Test
    fun `disabled server is skipped`() = runTest {
        val config = McpServerConfig(id = "srv1", name = "Test", url = "http://localhost:3000", enabled = false)
        every { mcpClientManager.connectedServerIds() } returns listOf("srv1")
        coEvery { mcpClientManager.listTools("srv1") } returns listOf(mockTool("srv1", "search", "Search"))

        bridge.syncTools(listOf(config))
        assertEquals(0, bridge.registeredToolNames().size)
    }

    @Test
    fun `disconnected server is skipped`() = runTest {
        val config = McpServerConfig(id = "srv1", name = "Test", url = "http://localhost:3000")
        every { mcpClientManager.connectedServerIds() } returns emptyList()

        bridge.syncTools(listOf(config))
        assertEquals(0, bridge.registeredToolNames().size)
    }

    @Test
    fun `tool with no native collision uses base name`() = runTest {
        val config = McpServerConfig(id = "srv1", name = "Test", url = "http://localhost:3000")
        every { mcpClientManager.connectedServerIds() } returns listOf("srv1")
        coEvery { mcpClientManager.listTools("srv1") } returns listOf(mockTool("srv1", "custom_search", "Custom search"))

        bridge.syncTools(listOf(config))
        assertNotNull(toolRegistry.get("custom_search"))
        assertTrue(bridge.registeredToolNames().contains("custom_search"))
    }

    @Test
    fun `tool with native collision uses prefixed name`() = runTest {
        toolRegistry.register(Tool(
            name = "search",
            description = "Native search",
            risk = ToolRisk.READ_ONLY,
            parameters = com.aura.providers.ToolParameters(),
            execute = { _, _ -> com.aura.agent.ToolResult.Ok("native") },
        ))

        val config = McpServerConfig(id = "srv1", name = "Test", url = "http://localhost:3000")
        every { mcpClientManager.connectedServerIds() } returns listOf("srv1")
        coEvery { mcpClientManager.listTools("srv1") } returns listOf(mockTool("srv1", "search", "MCP search"))

        bridge.syncTools(listOf(config))
        assertNotNull(toolRegistry.get("mcp_srv1_search"))
        assertTrue(bridge.registeredToolNames().contains("mcp_srv1_search"))
    }

    @Test
    fun `stale tools are unregistered when server removed`() = runTest {
        val config1 = McpServerConfig(id = "srv1", name = "Test1", url = "http://localhost:3000")
        every { mcpClientManager.connectedServerIds() } returns listOf("srv1")
        coEvery { mcpClientManager.listTools("srv1") } returns listOf(mockTool("srv1", "tool_a", "A"))

        bridge.syncTools(listOf(config1))
        assertEquals(1, bridge.registeredToolNames().size)

        bridge.syncTools(emptyList())
        assertEquals(0, bridge.registeredToolNames().size)
        assertNull(toolRegistry.get("tool_a"))
    }

    @Test
    fun `MCP tools are registered as REMOTE_COST`() = runTest {
        val config = McpServerConfig(id = "srv1", name = "Test", url = "http://localhost:3000")
        every { mcpClientManager.connectedServerIds() } returns listOf("srv1")
        coEvery { mcpClientManager.listTools("srv1") } returns listOf(mockTool("srv1", "expensive_op", "Costs money"))

        bridge.syncTools(listOf(config))
        val tool = toolRegistry.get("expensive_op")
        assertNotNull(tool)
        assertEquals(ToolRisk.REMOTE_COST, tool!!.risk)
        assertEquals("mcp", tool.category)
    }

    @Test
    fun `blank description gets fallback`() = runTest {
        val config = McpServerConfig(id = "srv1", name = "MyServer", url = "http://localhost:3000")
        every { mcpClientManager.connectedServerIds() } returns listOf("srv1")
        coEvery { mcpClientManager.listTools("srv1") } returns listOf(mockTool("srv1", "tool_x", ""))

        bridge.syncTools(listOf(config))
        val tool = toolRegistry.get("tool_x")
        assertNotNull(tool)
        assertTrue(tool!!.description.contains("MCP tool"))
        assertTrue(tool.description.contains("MyServer"))
    }

    @Test
    fun `registeredToolNames returns empty set initially`() {
        val names = bridge.registeredToolNames()
        assertEquals(0, names.size)
    }

    private fun assertNull(value: Any?) {
        assertTrue("Expected null but was $value", value == null)
    }

    private fun assertNotNull(value: Any?) {
        assertTrue("Expected non-null", value != null)
    }
}