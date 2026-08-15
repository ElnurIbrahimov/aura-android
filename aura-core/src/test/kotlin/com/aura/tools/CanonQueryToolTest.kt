package com.aura.tools

import com.aura.agent.ToolCall
import com.aura.agent.ToolContext
import com.aura.agent.ToolResult
import com.aura.creative.CanonFactDao
import com.aura.creative.CanonFactEntity
import com.aura.creative.CreativeBranchEntity
import com.aura.creative.CreativeBranchStore
import com.aura.creative.CreativeProject
import com.aura.creative.CreativeProjectStore
import com.aura.creative.WorldBible
import com.aura.memory.MemoryStore
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertTrue

/**
 * The tool ran `memoryStore.query("$question project:$projectId")` against the
 * user's *personal* memory store. `project:` is not a scope filter — it is
 * literal text inside a BM25 query, so it added noise rather than scoping — and
 * the four canon tables the tool is named after have never held a row.
 */
class CanonQueryToolTest {

    private val memoryStore = mockk<MemoryStore>(relaxed = true)
    private val projectStore = mockk<CreativeProjectStore>(relaxed = true)
    private val branchStore = mockk<CreativeBranchStore>(relaxed = true)
    private val canonFactDao = mockk<CanonFactDao>(relaxed = true)

    private fun tool() = CanonQueryTool(memoryStore, projectStore, branchStore, canonFactDao).tool

    private fun project() = CreativeProject(
        id = "p1", name = "The Lighthouse", description = "", genre = "", tone = "",
        world = WorldBible(), templateId = "novel", turnCount = 0, createdAt = 0L, updatedAt = 0L,
    )

    private fun mainBranch() = CreativeBranchEntity(id = "main", projectId = "p1", name = "main")

    /** `Tool.execute` is `suspend (ToolCall, ToolContext) -> ToolResult`; neither is nullable. */
    private fun call(vararg pairs: Pair<String, Any?>) =
        ToolCall(id = "tc1", name = "canon_query", arguments = mapOf(*pairs))

    private fun ctx() = ToolContext(conversationId = "conv-1")

    @Test
    fun `it answers from canon and never touches personal memory`() = runTest {
        coEvery { projectStore.get("p1") } returns project()
        coEvery { branchStore.createMainBranch("p1") } returns mainBranch()
        coEvery { canonFactDao.activeForBranch("p1", "main") } returns listOf(
            CanonFactEntity(
                id = "f1", projectId = "p1", branchId = "main",
                subjectType = "character", subjectId = "Mira",
                predicate = "location", valueJson = "\"the lighthouse\"",
            ),
        )

        val result = tool().execute(
            call("projectId" to "p1", "question" to "where is Mira"),
            ctx(),
        )

        assertTrue(result is ToolResult.Ok)
        assertTrue((result as ToolResult.Ok).output.contains("Mira"))
        assertTrue(result.output.contains("the lighthouse"))
        coVerify(exactly = 0) { memoryStore.query(any(), any()) }
    }

    @Test
    fun `an empty canon says so rather than inventing an answer`() = runTest {
        coEvery { projectStore.get("p1") } returns project()
        coEvery { branchStore.createMainBranch("p1") } returns mainBranch()
        coEvery { canonFactDao.activeForBranch("p1", "main") } returns emptyList()

        val result = tool().execute(
            call("projectId" to "p1", "question" to "where is Mira"),
            ctx(),
        )

        assertTrue((result as ToolResult.Ok).output.contains("No canon"))
    }
}
