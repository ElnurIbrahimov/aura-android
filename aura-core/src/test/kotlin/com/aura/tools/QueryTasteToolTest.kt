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

class QueryTasteToolTest {
    private val memoryStore = mockk<MemoryStore>()
    private val tool = QueryTasteTool(memoryStore)

    @Test
    fun `query returns matching taste memories`() = runTest {
        coEvery { memoryStore.query("music category:taste", any()) } returns listOf(
            MemoryEntity(id = "1", content = "Likes jazz", category = "taste", source = "signal"),
        )
        val result = tool.tool.execute(
            ToolCall(id = "1", name = "query_taste", arguments = mapOf("topic" to "music")),
            ToolContext(conversationId = "c1"),
        )
        assertIs<ToolResult.Ok>(result)
        assertTrue(result.output.contains("Likes jazz"))
    }
}
