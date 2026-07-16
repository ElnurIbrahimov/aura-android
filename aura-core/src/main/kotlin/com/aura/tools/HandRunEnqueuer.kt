package com.aura.tools

import com.aura.agentrun.AgentRunStore
import com.aura.agentrun.StepSpec
import com.aura.hands.HandRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * Adapter that converts a saved [com.aura.hands.Hand] into a durable [AgentRun].
 * Used by [RunHandTool] so that hand executions appear in the
 * Agent Runs list and can be resumed/approved like any other run.
 */
@Singleton
class HandRunEnqueuer @Inject constructor(
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
        val variables = handRepository.parseVariables(variablesJson)
        val steps = handRepository.parseSteps(hand.steps)
        val run = agentRunStore.createRun(
            trigger = trigger,
            goalDescription = "Execute hand '${hand.name}': run automation macro",
            conversationId = conversationId,
            modelId = modelId,
        )
        if (steps.isNotEmpty()) {
            agentRunStore.planSteps(
                runId = run.id,
                steps = steps.map { step ->
                    val merged = step.args + variables
                    StepSpec(
                        toolName = step.tool,
                        toolArgs = Json.encodeToString(
                            MapSerializer(String.serializer(), String.serializer()),
                            merged,
                        ),
                        dependsOn = "[]",
                    )
                },
            )
        }
        return run.id
    }
}
