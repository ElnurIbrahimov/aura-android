package com.aura.tools

import android.content.Context
import com.aura.agent.ToolCall
import com.aura.agent.ToolContext
import com.aura.agent.ToolResult
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class CodeInterpreterToolTest {

    private lateinit var tool: CodeInterpreterTool
    private val ctx = ToolContext(conversationId = "test")

    @Before
    fun setUp() {
        val context = mockk<Context>(relaxed = true)
        tool = CodeInterpreterTool(context)
    }

    @Test
    fun `definition has correct name`() {
        assertEquals("code_interpreter", tool.definition().name)
    }

    @Test
    fun `definition requires code parameter`() {
        assertTrue(tool.definition().parameters.required.contains("code"))
    }

    @Test
    fun `definition has code property as string`() {
        val codeProp = tool.definition().parameters.properties["code"]
        assertEquals("string", codeProp?.type)
    }

    @Test
    fun `definition describes sandbox and limitations`() {
        val desc = tool.definition().description.lowercase()
        assertTrue("Should mention JavaScript", desc.contains("javascript"))
        assertTrue("Should mention sandbox", desc.contains("sandbox"))
        assertTrue("Should mention timeout", desc.contains("timeout"))
    }

    @Test
    fun `tool returns error when code is missing`() = runTest {
        val call = ToolCall(id = "test", name = "code_interpreter", arguments = emptyMap())
        val result = tool.tool.execute(call, ctx)
        assertTrue(result is ToolResult.Error)
        assertEquals("bad_args", (result as ToolResult.Error).code)
    }

    @Test
    fun `tool returns error when code is blank`() = runTest {
        val call = ToolCall(id = "test", name = "code_interpreter", arguments = mapOf("code" to ""))
        val result = tool.tool.execute(call, ctx)
        assertTrue(result is ToolResult.Error)
        assertEquals("bad_args", (result as ToolResult.Error).code)
    }

    @Test
    fun `tool returns error when code is too large`() = runTest {
        val hugeCode = "x".repeat(50_001)
        val call = ToolCall(id = "test", name = "code_interpreter", arguments = mapOf("code" to hugeCode))
        val result = tool.tool.execute(call, ctx)
        assertTrue(result is ToolResult.Error)
        assertEquals("too_large", (result as ToolResult.Error).code)
    }

    @Test
    fun `tool risk is REMOTE_COST`() {
        assertEquals(com.aura.agent.ToolRisk.REMOTE_COST, tool.tool.risk)
    }

    @Test
    fun `tool category is compute`() {
        assertEquals("compute", tool.tool.category)
    }
}