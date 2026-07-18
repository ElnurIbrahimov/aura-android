package com.aura.tools

import android.content.Context
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
     * @return run ID or null if the hand does not exist
     */
    suspend fun enqueue(
        handName: String,
        variablesJson: String = "{}",
        trigger: String,
        conversationId: String = "",
        modelId: String = "",
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

        val steps = handRepository.parseSteps(hand.steps)
        val run = agentRunStore.createRun(
            trigger = trigger,
            goalDescription = "Execute hand '${hand.name}': run automation macro",
            conversationId = conversationId,
            modelId = modelId,
        )
        if (steps.isNotEmpty()) {
            // Apply variable substitution to step args before serializing
            // so the executor sees the final values, not template placeholders.
            agentRunStore.planSteps(
                runId = run.id,
                steps = steps.mapIndexed { index, step ->
                    val substitution = handRepository.substituteArgs(step.args, variables)
                    val merged = substitution.args + variables.mapValues { it.value }
                    StepSpec(
                        toolName = step.tool,
                        toolArgs = Json.encodeToString(
                            MapSerializer(String.serializer(), String.serializer()),
                            merged,
                        ),
                        dependsOn = if (index == 0) "[]" else "[$index]",
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
