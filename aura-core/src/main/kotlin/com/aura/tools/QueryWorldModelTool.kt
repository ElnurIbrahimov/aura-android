package com.aura.tools

import com.aura.agent.Tool
import com.aura.agent.ToolCall
import com.aura.agent.ToolResult
import com.aura.agent.ToolRisk
import com.aura.memory.MemoryStore
import com.aura.providers.ToolDefinition
import com.aura.providers.ToolParameters
import com.aura.providers.ToolProperty
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Query the user's accumulated world model (beliefs, events, opportunities) stored as memories.
 */
@Singleton
class QueryWorldModelTool @Inject constructor(
    private val memoryStore: MemoryStore,
) {
    fun definition() = ToolDefinition(
        name = "query_world_model",
        description = "Ask questions about the user's world model: beliefs, events, opportunities, and tracked assumptions.",
        parameters = ToolParameters(
            properties = mapOf(
                "question" to ToolProperty("string", "Question about beliefs, events, opportunities, or context"),
            ),
            required = listOf("question"),
        ),
    )

    val tool = Tool(
        name = definition().name,
        description = definition().description,
        risk = ToolRisk.READ_ONLY,
        parameters = definition().parameters,
        category = "memory",
        execute = { call, _ ->
            val question = call.arguments["question"] as? String ?: return@Tool ToolResult.Error("missing 'question'", "bad_args")
            val memories = memoryStore.query("$question category:worldmodel", limit = 8)
            val output = buildString {
                appendLine("World-model results for: $question")
                if (memories.isEmpty()) appendLine("No matching entries found.")
                else memories.forEach { appendLine("- ${it.content}") }
            }
            ToolResult.Ok(output.trim())
        },
    )
}
