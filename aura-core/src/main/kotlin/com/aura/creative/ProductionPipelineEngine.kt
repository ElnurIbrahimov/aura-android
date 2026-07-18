package com.aura.creative

import android.content.Context
import com.aura.agentrun.AgentRunExecutorService
import com.aura.agentrun.AgentRunStore
import com.aura.agentrun.StepSpec
import com.aura.capabilities.CapabilityKind
import com.aura.capabilities.CapabilityRouter
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * High-level creative production pipelines: novel, screenplay, short film,
 * trailer, podcast drama, RPG campaign. Each pipeline breaks a project into
 * an ordered sequence of agent-run steps so progress is durable, resumable,
 * and observable in the Agent Runs history screen.
 *
 * After scheduling, triggers [AgentRunExecutorWorker] to actually
 * execute the steps.
 */
@Singleton
class ProductionPipelineEngine @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val agentRunStore: AgentRunStore,
    private val capabilityRouter: CapabilityRouter,
) {

    enum class Pipeline(val displayName: String) {
        NOVEL("Novel"),
        SCREENPLAY("Screenplay"),
        SHORT_FILM("Short Film"),
        TRAILER("Trailer"),
        PODCAST_DRAMA("Podcast Drama"),
        RPG_CAMPAIGN("RPG Campaign"),
    }

    /**
     * Schedule all steps for a pipeline and return the run ID.
     */
    suspend fun schedule(projectId: String, pipeline: Pipeline, brief: String): String {
        val run = agentRunStore.createRun(
            trigger = "pipeline:${pipeline.name.lowercase()}",
            goalDescription = "${pipeline.displayName}: $brief",
            conversationId = "",
        )
        val steps = stepsFor(pipeline, projectId, brief)
        agentRunStore.planSteps(run.id, steps)
        // Trigger the executor worker to process the steps
        AgentRunExecutorService.enqueue(appContext, run.id)
        return run.id
    }

    private fun stepsFor(pipeline: Pipeline, projectId: String, brief: String): List<StepSpec> {
        val escaped = brief.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
        val base = listOf(
            StepSpec(
                toolName = "creative_read_project",
                toolArgs = """{"projectId":"$projectId"}""",
                dependsOn = "[]",
            ),
        )
        val specific = when (pipeline) {
            Pipeline.NOVEL -> listOf(
                creativeStep("brainstorm", projectId, escaped),
                creativeStep("outline", projectId, escaped),
                creativeStep("draft", projectId, escaped),
                creativeStep("continuity", projectId, escaped),
                creativeStep("rewrite", projectId, escaped),
            )
            Pipeline.SCREENPLAY -> listOf(
                creativeStep("outline", projectId, escaped),
                creativeStep("draft", projectId, escaped),
                creativeStep("continuity", projectId, escaped),
            )
            Pipeline.SHORT_FILM -> listOf(
                creativeStep("outline", projectId, escaped),
                creativeStep("draft", projectId, escaped),
                mediaStep("image_generate", projectId, "Generate storyboard imagery for: $escaped"),
                creativeStep("continuity", projectId, escaped),
            )
            Pipeline.TRAILER -> listOf(
                creativeStep("outline", projectId, escaped),
                creativeStep("draft", projectId, escaped),
                mediaStep("tts_speak", projectId, "Narrate trailer voiceover: $escaped"),
            )
            Pipeline.PODCAST_DRAMA -> listOf(
                creativeStep("outline", projectId, escaped),
                creativeStep("draft", projectId, escaped),
                mediaStep("tts_speak", projectId, "Narrate podcast drama: $escaped"),
                creativeStep("continuity", projectId, escaped),
            )
            Pipeline.RPG_CAMPAIGN -> listOf(
                creativeStep("brainstorm", projectId, escaped),
                creativeStep("outline", projectId, escaped),
                creativeStep("draft", projectId, escaped),
                creativeStep("continuity", projectId, escaped),
            )
        }
        // Chain: each step depends on the previous one so outputs
        // are available to subsequent steps and the executor runs
        // them in order rather than all at once.
        val chained = base + specific.mapIndexed { index, step ->
            val prevIndex = index // 0-based from `specific`, base is index -1
            val dependsOn = if (index == 0) "[0]" else "[$index]"
            step.copy(dependsOn = dependsOn)
        }
        // Final step: add a 'beat' to the project canon noting completion.
        // Uses type+name+description (the tool's required args), NOT
        // section+content which the tool does not accept.
        val finalIndex = chained.size
        return chained + listOf(
            StepSpec(
                toolName = "creative_add_world_item",
                toolArgs = """{"projectId":"$projectId","type":"beat","name":"${pipeline.displayName} complete","description":"Pipeline ${pipeline.displayName} completed for: $escaped"}""",
                dependsOn = "[$finalIndex]",
            ),
        )
    }

    private fun creativeStep(stage: String, projectId: String, prompt: String): StepSpec {
        return StepSpec(
            toolName = "creative_engine",
            toolArgs = """{"projectId":"$projectId","stage":"$stage","prompt":"$prompt"}""",
            dependsOn = "[]",
        )
    }

    private fun mediaStep(toolName: String, projectId: String, prompt: String): StepSpec {
        return StepSpec(
            toolName = toolName,
            toolArgs = """{"prompt":"$prompt","projectId":"$projectId"}""",
            dependsOn = "[]",
        )
    }

    fun availablePipelines(): List<Pipeline> {
        val hasImage = capabilityRouter.isAvailable(CapabilityKind.ImageGeneration)
        val hasTts = capabilityRouter.isAvailable(CapabilityKind.TextToSpeech)
        return Pipeline.entries.filter {
            when (it) {
                Pipeline.SHORT_FILM -> hasImage
                Pipeline.TRAILER, Pipeline.PODCAST_DRAMA -> hasTts
                else -> true
            }
        }
    }
}
