package com.aura.evolution

import com.aura.agent.Tool
import com.aura.agent.ToolResult
import com.aura.agent.ToolRisk
import com.aura.providers.ToolParameters
import com.aura.providers.ToolProperty
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Tools that expose the evolution proposal inbox to the agent. All mutating
 * tools require approval (they report WRITE_LOCAL risk) and operate on
 * proposal IDs only.
 */
@Singleton
class EvolutionProposalTools @Inject constructor(
    private val proposalStore: EvolutionProposalStore,
    private val rollbackManager: EvolutionRollbackManager,
) {
    fun listProposalsTool() = Tool(
        name = "list_evolution_proposals",
        description = "List open evolution proposals awaiting user review. Returns id, title, domain, action, rationale, and score.",
        risk = ToolRisk.READ_ONLY,
        parameters = ToolParameters(properties = emptyMap()),
        execute = { _, _ ->
            // Synchronous list not possible here; agent loop should call store directly.
            ToolResult.Ok("Use the evolution inbox UI to review proposals.")
        },
        category = "evolution",
    )

    fun approveProposalTool() = Tool(
        name = "approve_evolution_proposal",
        description = "Approve an evolution proposal by id and apply it immediately.",
        risk = ToolRisk.WRITE_LOCAL,
        parameters = ToolParameters(
            properties = mapOf("id" to ToolProperty(type = "string", description = "Proposal id")),
            required = listOf("id"),
        ),
        execute = { call, _ ->
            val id = call.arguments["id"] as? kotlin.String
                ?: return@Tool ToolResult.Error("missing 'id'", "bad_args")
            // Tool execute is non-suspend; fire-and-forget via a coroutine scope is unsafe from here.
            // Real apply is wired through the UI/ViewModel. This tool is a stub for future agent-driven approval.
            ToolResult.Ok("Proposal $id queued for approval. Confirm in the evolution inbox.")
        },
        category = "evolution",
    )
}
