package com.aura.tools

import com.aura.agent.ToolCall
import com.aura.agent.ToolContext
import com.aura.agent.ToolResult
import com.aura.memory.MemoryEntity
import com.aura.memory.MemoryStore
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue

class QueryWorldModelToolTest {
    private val memoryStore = mockk<MemoryStore>()
    private val tool = QueryWorldModelTool(memoryStore)

    @Test
    fun `query returns world model memories`() = runTest {
        coEvery { memoryStore.query("deadline category:worldmodel", 8) } returns listOf(
            MemoryEntity(id = "1", content = "Project X ships Friday", category = "worldmodel", source = "event"),
        )
        val result = tool.tool.execute(
            ToolCall(id = "1", name = "query_world_model", arguments = mapOf("question" to "deadline")),
            ToolContext(conversationId = "c1"),
        )
        assertIs<ToolResult.Ok>(result)
        assertTrue(result.output.contains("Project X ships Friday"))
    }
}
