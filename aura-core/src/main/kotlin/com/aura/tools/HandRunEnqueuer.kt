package com.aura.tools

import android.content.Context
import com.aura.agent.ToolContext
import com.aura.agentrun.AgentRunContextSnapshot
import com.aura.agentrun.AgentRunExecutorService
import com.aura.agentrun.AgentRunStore
import com.aura.agentrun.StepSpec
import com.aura.hands.HandRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * Adapter that converts a saved [com.aura.hands.Hand] into a durable [AgentRun].
 * Used by [RunHandTool] so that hand executions appear in the
 * Agent Runs list and can be resumed/approved like any other run.
 *
 * After enqueueing, triggers [AgentRunExecutorWorker] to actually
 * execute the steps. Without this, the run sits in RUNNING forever.
 */
@Singleton
class HandRunEnqueuer @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val handRepository: HandRepository,
    private val agentRunStore: AgentRunStore,
) {

    /**
     * Queue a hand as an agent run and return the run ID.
     *
     * @param handName the saved hand to execute
     * @param variablesJson optional variable overrides
     * @param trigger one of the [com.aura.hands.HandRunTrigger] values
     * @param conversationId optional originating conversation
     * @param modelId optional originating model
     * @param context optional originating tool context; passed through to
     *   background execution so incognito mode, approved remote-cost tools,
     *   and active agent survive the hand-off.
     * @return run ID or null if the hand does not exist, is disabled, or
     *   fails its conditions.
     */
    suspend fun enqueue(
        handName: String,
        variablesJson: String = "{}",
        trigger: String,
        conversationId: String = "",
        modelId: String = "",
        context: ToolContext? = null,
    ): String? {
        val hand = handRepository.getByName(handName) ?: return null
        // Respect the enabled flag — disabled hands should not execute
        // regardless of whether they're called by the agent, a trigger
        // phrase, or a manual run.
        if (!hand.enabled) return null
        val variables = handRepository.parseVariables(variablesJson)
        // Evaluate conditions before enqueueing — a hand whose conditions
        // don't match should not create a run.
        val conditions = handRepository.decodeConditions(hand.conditions)
        val failedCondition = conditions.firstOrNull { !it.matches(variables) }
        if (failedCondition != null) return null

        val metadata = context?.let {
            AgentRunContextSnapshot(
                userMessage = it.userMessage,
                approvedRemoteCostTools = it.approvedRemoteCostTools,
                memoryEnabled = it.memoryEnabled,
                activeAgentId = it.activeAgentId,
            ).toJson()
        } ?: "{}"

        val steps = handRepository.parseSteps(hand.steps)
        val run = agentRunStore.createRun(
            trigger = trigger,
            goalDescription = "Execute hand '${hand.name}': run automation macro",
            conversationId = conversationId,
            modelId = modelId,
            metadata = metadata,
        )
        if (steps.isNotEmpty()) {
            // Pre-generate step IDs so we can reference them in dependsOn.
            // The DagResolver matches dependencies by step ID (UUID), not
            // by positional index — so "[0]" or "[1]" would never resolve.
            val stepIds = steps.map { java.util.UUID.randomUUID().toString() }
            agentRunStore.planSteps(
                runId = run.id,
                steps = steps.mapIndexed { index, step ->
                    val substitution = handRepository.substituteArgs(step.args, variables)
                    val merged = substitution.args + variables.mapValues { it.value }
                    StepSpec(
                        id = stepIds[index],
                        toolName = step.tool,
                        toolArgs = Json.encodeToString(
                            MapSerializer(String.serializer(), String.serializer()),
                            merged,
                        ),
                        dependsOn = if (index == 0) "[]" else "[\"${stepIds[index - 1]}\"]",
                    )
                },
            )
        }
        // Record a hand run history entry so the run appears in the
        // hand's execution history alongside manual/scheduled runs.
        handRepository.recordRun(hand.name, trigger, run.id)
        // Trigger the executor worker to process the steps
        AgentRunExecutorService.enqueue(appContext, run.id)
        return run.id
    }
}
