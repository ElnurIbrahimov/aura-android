package com.aura.agent.council

import com.aura.agent.Tool
import com.aura.agent.ToolResult
import com.aura.agent.ToolRisk
import com.aura.providers.ToolParameters
import com.aura.providers.ToolProperty
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Tool that triggers an emergency life-council debate. Unlike
 * [com.aura.tools.RunCouncilTool] (creative council), this routes
 * through [CouncilOrchestrator] which runs 2 debate rounds with
 * mood/relationship/observation injection, votes, and intervention
 * extraction.
 *
 * Risk: REMOTE_COST — makes multiple LLM calls (one per agent per round).
 */
@Singleton
class RunLifeCouncilTool @Inject constructor(
    private val councilOrchestrator: CouncilOrchestrator,
) {
    val tool = Tool(
        name = "run_life_council",
        description = "Convene an emergency council of life advisors to debate a personal question. Agents argue, vote, and propose a concrete intervention. Use for life decisions, stress, relationship issues, or when the user needs perspective. Returns the debate transcript and any approved proposal.",
        risk = ToolRisk.REMOTE_COST,
        parameters = ToolParameters(
            properties = mapOf(
                "topic" to ToolProperty(
                    type = "string",
                    description = "The question or concern for the council to debate",
                ),
                "context" to ToolProperty(
                    type = "string",
                    description = "Additional context about the user's situation (optional)",
                ),
            ),
            required = listOf("topic"),
        ),
        execute = { call, _ ->
            val topic = call.arguments["topic"] as? kotlin.String
                ?: return@Tool ToolResult.Error("missing 'topic' argument", "bad_args")
            val context = call.arguments["context"] as? kotlin.String ?: ""

            try {
                val result = councilOrchestrator.runSession(topic = topic, context = context)

                val transcript = buildString {
                    appendLine("Council: $topic")
                    appendLine()

                    for (entry in result.debateEntries) {
                        val agentName = entry.agentName.replaceFirstChar { it.uppercase() }
                        appendLine("$agentName:")
                        appendLine("  ${entry.stance.take(400)}")
                        appendLine()
                    }

                    if (result.quorumReached && result.proposal != null) {
                        appendLine("--- PROPOSAL APPROVED ---")
                        appendLine("Intervention: ${result.proposal.summary}")
                        val p = result.proposal
                        when (p) {
                            is Intervention.Schedule ->
                                appendLine("Details: ${p.description}")
                            is Intervention.Message ->
                                appendLine("Draft: ${p.draftBody.take(300)}")
                            is Intervention.Reminder ->
                                appendLine("Reminder: ${p.message}")
                            is Intervention.SelfCare ->
                                appendLine("Suggestion: ${p.suggestion.take(300)}")
                            is Intervention.Memory ->
                                appendLine("Connection: ${p.connection.take(300)}")
                        }
                    } else if (result.voteTally != null) {
                        appendLine("--- VOTE FAILED ---")
                        appendLine("For: ${result.voteTally.forVotes}, Against: ${result.voteTally.against}, Abstain: ${result.voteTally.abstain}")
                        if (!result.dissent.isNullOrBlank()) {
                            appendLine("Dissent: ${result.dissent.take(300)}")
                        }
                    } else {
                        appendLine("No proposal reached quorum.")
                    }
                }

                ToolResult.Ok(transcript)
            } catch (e: Exception) {
                ToolResult.Error("Council failed: ${e.message}", "council_error")
            }
        },
        category = "agents",
    )
}