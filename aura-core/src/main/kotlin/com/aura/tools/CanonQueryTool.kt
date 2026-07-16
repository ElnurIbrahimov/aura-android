package com.aura.tools

import com.aura.agent.Tool
import com.aura.agent.ToolCall
import com.aura.agent.ToolResult
import com.aura.agent.ToolRisk
import com.aura.creative.CreativeProjectStore
import com.aura.memory.MemoryStore
import com.aura.providers.ToolDefinition
import com.aura.providers.ToolParameters
import com.aura.providers.ToolProperty
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Search local memory for facts tied to project canon.
 */
@Singleton
class CanonQueryTool @Inject constructor(
    private val memoryStore: MemoryStore,
    private val projectStore: CreativeProjectStore,
) {
    fun definition() = ToolDefinition(
        name = "canon_query",
        description = "Ask questions about any creative project's canon using local memory + RAG.",
        parameters = ToolParameters(
            properties = mapOf(
                "projectId" to ToolProperty("string", "Creative project ID"),
                "question" to ToolProperty("string", "Question about canon, plot, characters, or world rules"),
            ),
            required = listOf("projectId", "question"),
        ),
    )

    val tool = Tool(
        name = definition().name,
        description = definition().description,
        risk = ToolRisk.READ_ONLY,
        parameters = definition().parameters,
        category = "creative",
        execute = { call, _ ->
            val projectId = call.arguments["projectId"] as? String
                ?: return@Tool ToolResult.Error("missing 'projectId'", "bad_args")
            val question = call.arguments["question"] as? String
                ?: return@Tool ToolResult.Error("missing 'question'", "bad_args")
            projectStore.get(projectId)
                ?: return@Tool ToolResult.Error("Project not found", "not_found")
            val memories = memoryStore.query("$question project:$projectId", limit = 8)
            val output = buildString {
                appendLine("Relevant canon for: $question")
                if (memories.isEmpty()) {
                    appendLine("No matching canon found.")
                } else {
                    memories.forEach { appendLine("- ${it.content}") }
                }
            }
            ToolResult.Ok(output.trim())
        },
    )
}
