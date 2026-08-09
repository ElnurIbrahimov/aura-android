package com.aura.creative

import android.content.Context
import com.aura.agentrun.AgentRunContextSnapshot
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
            // Without this every step ran under AgentRunContextSnapshot's default
            // toolTimeoutMs of 60_000. ToolExecutor wraps each tool in
            // withTimeout(ctx.timeout), so every `creative_engine` stage — a
            // full chapter draft, budgeted at 28,672 output tokens — was being
            // killed at one minute and returning `tool_timeout`. The generative
            // stages of these pipelines have never completed.
            metadata = AgentRunContextSnapshot(toolTimeoutMs = PIPELINE_TOOL_TIMEOUT_MS).toJson(),
        )
        val steps = stepsFor(pipeline, projectId, brief)
        agentRunStore.planSteps(run.id, steps)
        // Trigger the executor worker to process the steps
        AgentRunExecutorService.enqueue(appContext, run.id)
        return run.id
    }

    private fun stepsFor(pipeline: Pipeline, projectId: String, brief: String): List<StepSpec> {
        val escaped = brief.replace("\\", "\\\\").replace("\"", "\\\"")
            .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t")
        // P2 fix: stage-specific prompts instead of the same brief for every stage
        val baseStep = StepSpec(
            id = UUID.randomUUID().toString(),
            toolName = "creative_read_project",
            toolArgs = """{"projectId":"$projectId"}""",
            dependsOn = "[]",
        )
        val specific = when (pipeline) {
            Pipeline.NOVEL -> listOf(
                creativeStep("brainstorm", projectId, "Brainstorm 5-8 distinct novel concepts based on this premise. Each should have a different dramatic engine. Premise: $escaped"),
                creativeStep("outline", projectId, "Create a chapter-by-chapter outline for a novel based on the brainstorm results. Structure as beats with escalation, reversals, and setup/payoff. Premise: $escaped"),
                creativeStep("draft", projectId, "Write the first full chapter draft. Open in medias res. Write in scenes. Aim for 3000-5000 words. Premise: $escaped"),
                creativeStep("continuity", projectId, "Check the draft against the project canon. List any contradictions, timeline issues, or unexplained changes. Premise: $escaped"),
                creativeStep("rewrite", projectId, "Rewrite the draft tightening prose, improving dialogue, and strengthening scene endings. Target 60-70% of original word count without losing meaning. Premise: $escaped"),
            )
            Pipeline.SCREENPLAY -> listOf(
                creativeStep("outline", projectId, "Create a scene-by-scene screenplay outline. Act 1 (setup), Act 2 (escalation), Act 3 (resolution). Premise: $escaped"),
                creativeStep("draft", projectId, "Write the first full screenplay scene in proper format (INT/EXT, action lines, dialogue). Premise: $escaped"),
                creativeStep("continuity", projectId, "Check the screenplay draft for continuity issues against canon. Premise: $escaped"),
            )
            Pipeline.SHORT_FILM -> listOf(
                creativeStep("outline", projectId, "Create a beat sheet for a short film. 5-7 scenes maximum. One protagonist, one transformation. Premise: $escaped"),
                creativeStep("draft", projectId, "Write the short film screenplay. Visual, economical, one turning point. Premise: $escaped"),
                mediaStep("image_generate", "Generate storyboard imagery for key scenes: $escaped"),
                creativeStep("continuity", projectId, "Check the short film for continuity issues. Premise: $escaped"),
            )
            Pipeline.TRAILER -> listOf(
                creativeStep("outline", projectId, "Create a trailer beat sheet: hook, escalation, tone, climax shot. 6-8 beats. Premise: $escaped"),
                creativeStep("draft", projectId, "Write the trailer script: voiceover, scene descriptions, music cues. Premise: $escaped"),
                mediaStep("tts_speak", "Narrate the trailer voiceover with dramatic pacing: $escaped"),
            )
            Pipeline.PODCAST_DRAMA -> listOf(
                creativeStep("outline", projectId, "Create a podcast drama episode outline. Cold open, 3 acts, cliffhanger. Premise: $escaped"),
                creativeStep("draft", projectId, "Write the podcast drama script: narrator, dialogue, sound cues. Premise: $escaped"),
                mediaStep("tts_speak", "Narrate the podcast drama with character voices: $escaped"),
                creativeStep("continuity", projectId, "Check the podcast drama for continuity issues. Premise: $escaped"),
            )
            Pipeline.RPG_CAMPAIGN -> listOf(
                creativeStep("brainstorm", projectId, "Brainstorm 5-8 campaign concepts with different conflict engines. Premise: $escaped"),
                creativeStep("outline", projectId, "Create a campaign outline: 10-15 sessions, each with a hook, conflict, and consequence. Premise: $escaped"),
                creativeStep("draft", projectId, "Write the first session: setting introduction, NPC introductions, inciting incident, and 3 encounter hooks. Premise: $escaped"),
                creativeStep("continuity", projectId, "Check the campaign materials against world canon. Premise: $escaped"),
            )
        }
        // Assign UUIDs to each specific step.
        val specificWithIds = specific.map { it.copy(id = UUID.randomUUID().toString()) }
        // Chain: each step depends on the previous one's UUID so the
        // executor runs them in order, not all at once.
        val chained = mutableListOf(baseStep)
        for ((i, step) in specificWithIds.withIndex()) {
            val prevId = if (i == 0) baseStep.id!! else specificWithIds[i - 1].id!!
            chained.add(step.copy(dependsOn = "[\"$prevId\"]"))
        }
        // Final step: add a 'beat' to the project canon noting completion.
        val finalStepId = UUID.randomUUID().toString()
        val lastStepId = chained.last().id!!
        return chained + listOf(
            StepSpec(
                id = finalStepId,
                toolName = "creative_add_world_item",
                toolArgs = """{"projectId":"$projectId","type":"beat","name":"${pipeline.displayName} complete","description":"Pipeline ${pipeline.displayName} completed for: $escaped"}""",
                dependsOn = "[\"$lastStepId\"]",
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

    /**
     * A media-generating stage (`image_generate`, `tts_speak`).
     *
     * Deliberately takes no project id: neither tool declares one in its schema
     * (`image_generate` is prompt/size/negative_prompt), so `ToolExecutor.parseArgs`
     * dropped it with a "Dropped unknown arg" warning and the generated asset was
     * never attached to the project anyway. Sending it only made the discard
     * look like wiring. Attaching media to a project is a real feature and needs
     * the artifact store, not an extra argument.
     */
    private fun mediaStep(toolName: String, prompt: String): StepSpec {
        return StepSpec(
            toolName = toolName,
            toolArgs = """{"prompt":"$prompt"}""",
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

    private companion object {
        /**
         * Per-tool timeout for pipeline steps, replacing
         * [AgentRunContextSnapshot]'s 60-second default.
         *
         * Five minutes, not ten: `AgentRunExecutorWorker` is a `CoroutineWorker`,
         * and WorkManager's own execution window is roughly ten minutes. A tool
         * allowed to run for the whole window would be killed by the system
         * mid-call with no chance for the worker to record a result or
         * re-enqueue. Five leaves the worker half its budget to finish the step
         * and hand off, and is still generous for a chapter draft, which runs
         * in one to four minutes at these output sizes.
         */
        const val PIPELINE_TOOL_TIMEOUT_MS = 300_000L
    }
}
