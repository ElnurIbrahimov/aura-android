package com.aura.evolution

import com.aura.agents.SubagentManager
import com.aura.agents.SubagentProgress
import com.aura.agents.SubagentResult
import com.aura.agents.SubagentSpec
import com.aura.providers.ModelRole
import kotlinx.coroutines.flow.SharedFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Wrapper that runs reflection-style evolution subagents through the
 * existing [SubagentManager]. Each subagent gets a tight budget, no tools,
 * and the [ModelRole.EVOLUTION] model. This reuses the orchestration
 * contract without inventing a parallel execution path.
 */
@Singleton
class EvolutionSubagentExecutor @Inject constructor(
    private val subagentManager: SubagentManager,
    private val reflectionExecutor: EvolutionReflectionExecutor,
) {
    val progress: SharedFlow<SubagentProgress> get() = subagentManager.progress

    /**
     * Run a single reflection subagent. [objective] is a short description;
     * [contextText] is the full prompt body.
     */
    suspend fun run(
        objective: kotlin.String,
        contextText: kotlin.String,
        parentRunId: kotlin.String,
        budgetMs: kotlin.Long = 45_000L,
    ): SubagentResult {
        val spec = SubagentSpec(
            role = "evolution_reflector",
            objective = objective,
            contextText = contextText,
            modelRole = ModelRole.EVOLUTION.name,
            toolAllowlist = emptyList(),
            budgetMs = budgetMs,
            maxToolCalls = 0,
            outputSchema = "",
        )
        val task = subagentManager.createTask(spec, parentRunId)
        return subagentManager.spawn(task) { _ ->
            val start = System.currentTimeMillis()
            when (val result = reflectionExecutor.reflect(
                systemPrompt = EVOLUTION_REFLECTOR_SYSTEM_PROMPT,
                userPrompt = buildPrompt(objective, contextText),
            )) {
                is EvolutionReflectionExecutor.Result.Ok -> SubagentResult(
                    taskId = task.id,
                    success = true,
                    output = result.text,
                    rationale = "Reflection completed.",
                    durationMs = System.currentTimeMillis() - start,
                )
                is EvolutionReflectionExecutor.Result.Error -> SubagentResult(
                    taskId = task.id,
                    success = false,
                    error = "${result.code}: ${result.message}",
                    durationMs = System.currentTimeMillis() - start,
                )
            }
        }
    }

    private fun buildPrompt(objective: kotlin.String, contextText: kotlin.String): kotlin.String = """
        Objective: $objective

        Context:
        $contextText

        Respond with a concise, structured answer. Do not ask clarifying questions.
    """.trimIndent()

    private companion object {
        val EVOLUTION_REFLECTOR_SYSTEM_PROMPT = """
            You are Aura's evolution engine. You review candidate improvements to the user's
            assistant (skills, memory organization, proactive rules). You are conservative:
            reject changes that could delete data, expose secrets, or change behavior the user
            has not demonstrated they want. Output a short verdict and rationale.
        """.trimIndent()
    }
}
