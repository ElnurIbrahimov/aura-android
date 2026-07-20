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
 * Query the user's taste profile (preferences, style, reference identities) stored as memories.
 */
@Singleton
class QueryTasteTool @Inject constructor(
    private val memoryStore: MemoryStore,
) {
    fun definition() = ToolDefinition(
        name = "query_taste",
        description = "Ask what the user likes, prefers, or finds high-quality. Returns taste signals and style profiles from memory.",
        parameters = ToolParameters(
            properties = mapOf(
                "topic" to ToolProperty("string", "Topic to ask about, e.g. 'music', 'writing style', 'visual design'"),
            ),
            required = listOf("topic"),
        ),
    )

    val tool = Tool(
        name = definition().name,
        description = definition().description,
        risk = ToolRisk.READ_ONLY,
        parameters = definition().parameters,
        category = "memory",
        execute = { call, _ ->
            val topic = call.arguments["topic"] as? String ?: return@Tool ToolResult.Error("missing 'topic'", "bad_args")
            val memories = memoryStore.query("$topic category:taste", com.aura.memory.MemoryStore.RecallOptions(limit = 8))
            val output = buildString {
                appendLine("Taste results for: $topic")
                if (memories.isEmpty()) appendLine("No taste signals recorded yet.")
                else memories.forEach { appendLine("- ${it.content}") }
            }
            ToolResult.Ok(output.trim())
        },
    )
}
