package com.aura.tools

import com.aura.agent.AgentCouncil
import com.aura.agent.Tool
import com.aura.agent.ToolResult
import com.aura.agent.ToolRisk
import com.aura.providers.ToolParameters
import com.aura.providers.ToolProperty
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Tool that triggers a multi-agent council. Multiple specialist agents
 * work in parallel on a task, and a director synthesizes their answers.
 *
 * Risk: REMOTE_COST — makes multiple LLM calls (one per agent + director).
 */
@Singleton
class RunCouncilTool @Inject constructor(
    private val agentCouncil: AgentCouncil,
) {
    val tool = Tool(
        name = "run_council",
        description = "Run a multi-agent council on a complex question. Multiple specialist agents work in parallel and a director synthesizes their answers. Use for complex multi-faceted questions.",
        risk = ToolRisk.REMOTE_COST,
        parameters = ToolParameters(
            properties = mapOf(
                "task" to ToolProperty(
                    type = "string",
                    description = "The question or task for the council",
                ),
                "agents" to ToolProperty(
                    type = "string",
                    description = "Comma-separated agent names to include (default: auto-select all)",
                ),
                "context" to ToolProperty(
                    type = "string",
                    description = "Additional context for the council (optional)",
                ),
            ),
            required = listOf("task"),
        ),
        execute = { call, _ ->
            val task = call.arguments["task"] as? String
                ?: return@Tool ToolResult.Error("missing 'task' argument", "bad_args")
            val agentNames = (call.arguments["agents"] as? String)
                ?.split(",")
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                ?: emptyList()
            val context = call.arguments["context"] as? String ?: ""

            try {
                val result = agentCouncil.run(
                    agentIds = agentNames,
                    task = task,
                    context = context,
                )
                if (result.success) {
                    val proposalSummary = result.proposals.mapIndexed { idx, p ->
                        "- Agent $idx: ${p.output.take(100)}..."
                    }.joinToString("\n")
                    ToolResult.Ok("${result.directorOutput}\n\n--- Council proposals ---\n$proposalSummary")
                } else {
                    ToolResult.Error(result.error.ifBlank { "Council failed" }, "council_error")
                }
            } catch (e: Exception) {
                ToolResult.Error("Council failed: ${e.message}", "council_error")
            }
        },
        category = "agents",
    )
}