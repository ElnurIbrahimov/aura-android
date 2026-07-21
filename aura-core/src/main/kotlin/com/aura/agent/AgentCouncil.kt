package com.aura.agent

import com.aura.agents.SubagentManager
import com.aura.agents.SubagentSpec
import com.aura.agents.SubagentResult
import com.aura.agents.SubagentTask
import com.aura.providers.ProviderRegistry
import kotlinx.coroutines.withTimeout
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Generalized multi-agent council. Extends the CreativeCouncil pattern
 * to work for any task, not just creative projects.
 *
 * Producers run in parallel as subagents, each with their own identity,
 * tools, and memory scope. A director agent synthesizes the proposals
 * into a final answer.
 *
 * Can be triggered by the [RunCouncilTool] or by the user asking
 * "ask the council: ...".
 */
@Singleton
class AgentCouncil @Inject constructor(
    private val subagentManager: SubagentManager,
    private val agentStore: AgentStore,
    private val providerRegistry: ProviderRegistry,
) {
    data class CouncilResult(
        val directorOutput: String,
        val proposals: List<SubagentResult>,
        val success: Boolean = true,
        val error: String = "",
    )

    /**
     * Run a multi-agent council on [task]. Each agent in [agentIds] runs
     * as a producer in parallel. The first agent (or [directorAgentId])
     * synthesizes the proposals into a final answer.
     *
     * If [agentIds] is empty, auto-selects all non-default builtin agents
     * + the default agent as director.
     */
    suspend fun run(
        agentIds: List<String> = emptyList(),
        task: String,
        context: String = "",
        budgetMs: Long = 120_000L,
        directorAgentId: String? = null,
    ): CouncilResult = try {
        withTimeout(budgetMs) {
        // Resolve agents
        val allAgents = agentStore.allOnce()
        val agents = if (agentIds.isEmpty()) {
            // Auto-select: all non-default builtins + default as director
            allAgents
        } else {
            agentIds.mapNotNull { id -> allAgents.find { it.id == id || it.name == id } }
        }

        if (agents.isEmpty()) {
            return@withTimeout CouncilResult(
                directorOutput = "No agents available for council.",
                proposals = emptyList(),
                success = false,
                error = "no_agents",
            )
        }

        // Split producers and director
        val director = agents.firstOrNull { it.isDefault || it.id == directorAgentId }
            ?: agents.first()
        val producers = agents.filter { it.id != director.id }

        if (producers.isEmpty()) {
            // Only one agent — just run it directly
            val result = runAgent(director, task, context, budgetMs)
            return@withTimeout CouncilResult(
                directorOutput = result.output.ifBlank { "No response from ${director.name}." },
                proposals = listOf(result),
            )
        }

        // Phase 1: producers run in parallel as subagents.
        // Each producer gets the same task + context and runs independently.
        // Budget is split asymmetrically: 70% for producers (shared equally),
        // 30% for the director who needs time to synthesize all proposals.
        val producerBudget = (budgetMs * 0.7).toLong() / producers.size
        val directorBudget = (budgetMs * 0.3).toLong()
        val results = subagentManager.spawnAll(
            producers.map { agent ->
                subagentManager.createTask(
                    SubagentSpec(
                        role = agent.name,
                        objective = "$task${if (context.isNotBlank()) "\n\nContext: $context" else ""}",
                        modelRole = "CONVERSATION",
                        toolAllowlist = agent.toolSet().toList(),
                        budgetMs = producerBudget,
                        maxToolCalls = 5,
                    ),
                    parentRunId = "council",
                )
            },
        ) { task ->
            val agent = producers.find { it.name == task.spec.role } ?: producers.first()
            runAgent(agent, task.spec.objective, context, producerBudget)
        }

        val proposals = results

        // Phase 2: director synthesizes
        val proposalText = proposals.mapIndexed { idx, result ->
            val agentName = producers.getOrNull(idx)?.name ?: "agent-$idx"
            "Agent: $agentName\n${result.output.ifBlank { "(no output)" }}"
        }.joinToString("\n\n---\n\n")

        val directorTask = "Synthesize the best elements from all agent proposals into a final answer. Task: $task\n\nProposals:\n$proposalText"
        val directorResult = runAgent(director, directorTask, "", directorBudget)

        CouncilResult(
            directorOutput = directorResult.output.ifBlank { "Council produced no output." },
            proposals = proposals,
        )
        }
    } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
        CouncilResult(
            directorOutput = "Council timed out after ${budgetMs / 1000}s.",
            proposals = emptyList(),
        )
    }

    /**
     * Run a single agent. Uses the same simplified single-pass approach
     * as DelegateToAgentTool.
     */
    private suspend fun runAgent(
        agent: AgentEntity,
        task: String,
        context: String,
        budgetMs: Long,
    ): SubagentResult {
        val start = System.currentTimeMillis()
        return try {
            val model = agent.preferredModel
                ?: runCatching {
                    val providers = providerRegistry.configured()
                    val firstProvider = providers.firstOrNull()
                    val firstModel = firstProvider?.listModels()?.firstOrNull()
                    if (firstProvider != null && firstModel != null) "${firstProvider.prefix}:$firstModel" else null
                }.getOrNull()
                ?: return SubagentResult(
                    taskId = "council_${agent.name}",
                    success = false,
                    error = "No model available for ${agent.name}",
                    durationMs = System.currentTimeMillis() - start,
                )

            val personality = agent.personality().toPromptDirective()
            val systemPrompt = listOfNotNull(
                agent.identity,
                personality.ifBlank { null },
                if (context.isNotBlank()) "\n\nContext:\n$context" else null,
            ).joinToString("\n\n")

            val messages = listOf(
                com.aura.providers.ProviderMessage(
                    role = com.aura.providers.ProviderMessage.Role.system,
                    content = systemPrompt,
                ),
                com.aura.providers.ProviderMessage(
                    role = com.aura.providers.ProviderMessage.Role.user,
                    content = task,
                ),
            )

            val output = StringBuilder()
            providerRegistry.chat(
                model,
                messages,
                com.aura.providers.ChatOptions(temperature = 0.7, maxTokens = 2048),
            ).collect { chunk ->
                chunk.text?.let { output.append(it) }
            }

            SubagentResult(
                taskId = "council_${agent.name}",
                success = true,
                output = output.toString(),
                durationMs = System.currentTimeMillis() - start,
            )
        } catch (e: Exception) {
            SubagentResult(
                taskId = "council_${agent.name}",
                success = false,
                error = e.message ?: "Unknown error",
                durationMs = System.currentTimeMillis() - start,
            )
        }
    }
}