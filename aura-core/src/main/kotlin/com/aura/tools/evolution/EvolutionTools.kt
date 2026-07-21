package com.aura.tools.evolution

import com.aura.agent.Tool
import com.aura.agent.ToolContext
import com.aura.agent.ToolResult
import com.aura.agent.ToolRisk
import com.aura.evolution.EvolutionApplySaga
import com.aura.evolution.EvolutionCoordinator
import com.aura.evolution.EvolutionProposalStore
import com.aura.evolution.EvolutionRollbackManager
import com.aura.providers.ToolParameters
import com.aura.providers.ToolProperty
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ApproveEvolutionProposalTool @Inject constructor(
    private val proposalStore: EvolutionProposalStore,
    private val applySaga: EvolutionApplySaga,
) {
    val tool = Tool(
        name = "approve_evolution_proposal",
        description = "Approve a pending evolution proposal and apply the change. Provide the proposal id from the inbox.",
        risk = ToolRisk.WRITE_LOCAL,
        parameters = ToolParameters(
            properties = mapOf(
                "proposalId" to ToolProperty(type = "string", description = "id of the pending proposal"),
            ),
            required = listOf("proposalId"),
        ),
        execute = { call, _ ->
            val proposalId = call.arguments["proposalId"] as? String
                ?: return@Tool ToolResult.Error("missing 'proposalId' argument", "bad_args")
            proposalStore.approve(proposalId)
            val proposal = proposalStore.getById(proposalId)
                ?: return@Tool ToolResult.Error("proposal not found", "not_found")
            when (val result = applySaga.apply(proposal)) {
                is EvolutionApplySaga.ApplyResult.Ok ->
                    ToolResult.Ok(buildJson(mapOf("proposalId" to result.proposalId, "status" to "applied", "summary" to result.summary)))
                is EvolutionApplySaga.ApplyResult.Error ->
                    ToolResult.Error(result.message)
                is EvolutionApplySaga.ApplyResult.NotYetImplemented ->
                    ToolResult.Error("action not implemented")
            }
        },
        category = "evolution",
    )
}

@Singleton
class RollbackEvolutionTool @Inject constructor(
    private val rollbackManager: EvolutionRollbackManager,
) {
    val tool = Tool(
        name = "rollback_evolution_change",
        description = "Undo a previously applied evolution proposal. Provide the proposal id.",
        risk = ToolRisk.WRITE_LOCAL,
        parameters = ToolParameters(
            properties = mapOf(
                "proposalId" to ToolProperty(type = "string", description = "id of the applied proposal"),
            ),
            required = listOf("proposalId"),
        ),
        execute = { call, _ ->
            val proposalId = call.arguments["proposalId"] as? String
                ?: return@Tool ToolResult.Error("missing 'proposalId' argument", "bad_args")
            when (val result = rollbackManager.rollback(proposalId)) {
                is EvolutionRollbackManager.RollbackResult.Ok ->
                    ToolResult.Ok(buildJson(mapOf("proposalId" to proposalId, "status" to "rolled_back", "summary" to result.summary)))
                is EvolutionRollbackManager.RollbackResult.Error ->
                    ToolResult.Error(result.message)
                is EvolutionRollbackManager.RollbackResult.Conflict ->
                    ToolResult.Error("conflict: ${result.message}")
            }
        },
        category = "evolution",
    )
}

@Singleton
class TriggerEvolutionRunTool @Inject constructor(
    private val coordinator: EvolutionCoordinator,
) {
    val tool = Tool(
        name = "trigger_evolution_run",
        description = "Run the deterministic evolution pipeline now to detect candidates and promote high-confidence ones. Does not auto-apply.",
        risk = ToolRisk.WRITE_LOCAL,
        parameters = ToolParameters(),
        execute = { _, _ ->
            val result = coordinator.runAll()
            ToolResult.Ok("""{"candidateCount":${result.candidateCount},"promotedCount":${result.promotedCount},"durationMs":${result.durationMs}}""")
        },
        category = "evolution",
    )
}

private fun buildJson(entries: Map<String, String>): String {
    val body = entries.entries.joinToString(",") { (k, v) ->
        "\"$k\":\"" + v.jsonEscape() + "\""
    }
    return "{$body}"
}

private fun String.jsonEscape(): String = replace("\\", "\\\\").replace("\"", "\\\"")