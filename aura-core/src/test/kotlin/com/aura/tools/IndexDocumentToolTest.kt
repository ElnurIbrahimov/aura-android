package com.aura.tools

import com.aura.agent.ToolCall
import com.aura.agent.ToolContext
import com.aura.agent.ToolResult
import com.aura.documents.DocumentImportResult
import com.aura.documents.DocumentRepository
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue

class IndexDocumentToolTest {
    private val repo = mockk<DocumentRepository>()
    private val tool = IndexDocumentTool(repo)

    @Test
    fun `index indexes plain text and returns chunk count`() = runTest {
        coEvery { repo.import(any(), any(), any(), any(), any()) } returns
            DocumentImportResult(mockk(relaxed = true), 3)
        val result = tool.tool.execute(
            ToolCall(
                id = "1",
                name = "index_document",
                arguments = mapOf(
                    "id" to "doc1",
                    "name" to "Notes",
                    "text" to "Line one. Line two. Line three.",
                ),
            ),
            ToolContext(conversationId = "c1"),
        )
        assertIs<ToolResult.Ok>(result)
        assertTrue(result.output.contains("3 chunks"))
    }
}
