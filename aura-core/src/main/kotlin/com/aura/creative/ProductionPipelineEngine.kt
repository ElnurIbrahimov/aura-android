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
        val base = listOf(
            StepSpec(
                toolName = "creative_read_project",
                toolArgs = """{"projectId":"$projectId"}""",
                dependsOn = "[]",
            ),
        )
        val specific = when (pipeline) {
            Pipeline.NOVEL -> listOf(
                step("brainstorm", projectId, brief),
                step("outline", projectId, brief),
                step("draft", projectId, brief),
                step("continuity", projectId, brief),
                step("rewrite", projectId, brief),
            )
            Pipeline.SCREENPLAY -> listOf(
                step("outline", projectId, brief),
                step("draft", projectId, brief),
                step("continuity", projectId, brief),
            )
            Pipeline.SHORT_FILM -> listOf(
                step("outline", projectId, brief),
                step("draft", projectId, brief),
                step("image_generate", projectId, brief),
                step("continuity", projectId, brief),
            )
            Pipeline.TRAILER -> listOf(
                step("outline", projectId, brief),
                step("draft", projectId, brief),
                step("tts_speak", projectId, brief),
            )
            Pipeline.PODCAST_DRAMA -> listOf(
                step("outline", projectId, brief),
                step("draft", projectId, brief),
                step("tts_speak", projectId, brief),
                step("continuity", projectId, brief),
            )
            Pipeline.RPG_CAMPAIGN -> listOf(
                step("brainstorm", projectId, brief),
                step("outline", projectId, brief),
                step("draft", projectId, brief),
                step("continuity", projectId, brief),
            )
        }
        return base + specific + listOf(
            StepSpec(toolName = "creative_add_world_item", toolArgs = """{"projectId":"$projectId","section":"notes","content":"Pipeline ${pipeline.displayName} completed for: $brief"}""", dependsOn = "[]"),
        )
    }

    private fun step(stage: String, projectId: String, prompt: String): StepSpec {
        val escaped = prompt.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
        return StepSpec(
            toolName = "creative_engine",
            toolArgs = """{"projectId":"$projectId","stage":"$stage","prompt":"$escaped"}""",
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
