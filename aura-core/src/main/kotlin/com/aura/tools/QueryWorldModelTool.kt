package com.aura.tools

import com.aura.agent.Tool
import com.aura.agent.ToolResult
import com.aura.agent.ToolRisk
import com.aura.providers.ToolDefinition
import com.aura.providers.ToolParameters
import com.aura.providers.ToolProperty
import com.aura.world.BeliefDao
import com.aura.world.OpportunityDao
import com.aura.world.WorldEventDao
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Query the user's accumulated world model (beliefs, events, opportunities).
 *
 * Previously this tool searched the memory store with a text query like
 * "$question category:worldmodel" — but world model data lives in separate
 * Room tables (beliefs, world_events, opportunities), not in the general
 * memory store. The old implementation always returned "No matching entries
 * found" because no memories had category "worldmodel".
 *
 * Now queries the actual DAOs directly.
 */
@Singleton
class QueryWorldModelTool @Inject constructor(
    private val beliefDao: BeliefDao,
    private val worldEventDao: WorldEventDao,
    private val opportunityDao: OpportunityDao,
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
            val question = call.arguments["question"] as? String
                ?: return@Tool ToolResult.Error("missing 'question'", "bad_args")
            val output = buildString {
                appendLine("World-model results for: $question")
                appendLine()

                // Active beliefs
                val beliefs = runCatching { beliefDao.allActive(10) }.getOrDefault(emptyList())
                if (beliefs.isNotEmpty()) {
                    appendLine("## Beliefs (${beliefs.size})")
                    beliefs.forEach { b ->
                        append("- ${b.subject} ${b.predicate}: ${b.valueJson} (confidence: ${"%.0f".format(b.confidence * 100)}%)")
                        val superseded = runCatching { beliefDao.history(b.subject, b.predicate) }
                            .getOrDefault(emptyList())
                            .filter { it.status == "superseded" }
                        if (superseded.isNotEmpty()) {
                            append(" (previously: ")
                            append(superseded.joinToString(", ") { it.valueJson })
                            append(")")
                        }
                        appendLine()
                    }
                    appendLine()
                }

                // Recent unconsumed events
                val events = runCatching { worldEventDao.unconsumed(10) }.getOrDefault(emptyList())
                if (events.isNotEmpty()) {
                    appendLine("## Recent Events (${events.size})")
                    events.forEach { e ->
                        appendLine("- [${e.eventType}] ${e.summary}")
                    }
                    appendLine()
                }

                // Pending opportunities
                val now = System.currentTimeMillis()
                val opportunities = runCatching { opportunityDao.pending(now, 10) }.getOrDefault(emptyList())
                if (opportunities.isNotEmpty()) {
                    appendLine("## Opportunities (${opportunities.size})")
                    opportunities.forEach { o ->
                        appendLine("- [${o.kind}] ${o.title} (urgency: ${"%.0f".format(o.urgency * 100)}%, benefit: ${"%.0f".format(o.benefit * 100)}%)")
                    }
                    appendLine()
                }

                if (beliefs.isEmpty() && events.isEmpty() && opportunities.isEmpty()) {
                    appendLine("No world-model entries found. The world model is built from conversation patterns over time.")
                }
            }
            ToolResult.Ok(output.trim())
        },
    )
}