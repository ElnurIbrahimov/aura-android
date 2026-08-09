package com.aura.tools

import com.aura.agent.Tool
import com.aura.agent.ToolCall
import com.aura.agent.ToolContext
import com.aura.agent.ToolRegistry
import com.aura.agent.ToolResult
import com.aura.agent.ToolRisk
import com.aura.creative.CreativeEngine
import com.aura.creative.CreativeMode
import com.aura.providers.ToolDefinition
import com.aura.providers.ToolParameters
import com.aura.providers.ToolProperty
import javax.inject.Inject
import javax.inject.Singleton
import android.util.Log

/**
 * Single-entry tool for durable creative production. Reads a project, runs a
 * named creative stage, and writes the result back as an artifact. Used by
 * [com.aura.creative.ProductionPipelineEngine] to turn pipeline steps into agent
 * runs.
 *
 * The artifact part of that sentence was untrue until now: this class never
 * imported the artifact store, so a pipeline stage's output survived only as
 * `StepEntity.result`, truncated to 4,000 characters. A chapter draft was
 * generated, billed, cut to a fifth of its length, and left somewhere the user
 * cannot open. Persisting here also means the next stage has something durable
 * to read, which is what makes a multi-stage pipeline worth running at all.
 */
@Singleton
class CreativeEngineTool @Inject constructor(
    private val engine: CreativeEngine,
    private val artifactStore: com.aura.creative.CreativeArtifactStore? = null,
    private val branchStore: com.aura.creative.CreativeBranchStore? = null,
) {

    fun definition(): ToolDefinition = ToolDefinition(
        name = "creative_engine",
        description = "Run a Creative Studio stage (brainstorm, outline, draft, rewrite, simulate, continuity) on a project and return the result. Supports long-form output (up to ~20K words for draft/simulate). Set thinking_budget to 0 to disable extended thinking, or leave null to use the global default.",
        parameters = ToolParameters(
            properties = mapOf(
                "projectId" to ToolProperty(type = "string", description = "Creative project id"),
                "stage" to ToolProperty(type = "string", description = "One of: brainstorm, outline, draft, rewrite, simulate, continuity"),
                "prompt" to ToolProperty(type = "string", description = "User prompt for this stage"),
                "thinking_budget" to ToolProperty(type = "integer", description = "Extended thinking budget in tokens (0 = off, null = use global default). Set to 32000 for maximum reasoning, 0 for fast generation."),
            ),
            required = listOf("projectId", "stage", "prompt"),
        ),
    )

    val tool = Tool(
        name = definition().name,
        description = definition().description,
        risk = ToolRisk.REMOTE_COST,
        parameters = definition().parameters,
        category = "creative",
        execute = { call, ctx -> execute(call, ctx) },
    )

    private suspend fun execute(call: ToolCall, ctx: ToolContext): ToolResult {
        val projectId = call.arguments["projectId"] as? String ?: return ToolResult.Error("projectId required")
        val stage = call.arguments["stage"] as? String ?: return ToolResult.Error("stage required")
        val prompt = call.arguments["prompt"] as? String ?: return ToolResult.Error("prompt required")
        val thinkingBudget = call.arguments["thinking_budget"] as? Int
        val mode = runCatching { CreativeMode.valueOf(stage.uppercase()) }.onFailure { Log.w("CreativeEngineTool", "runCatching failed: ${it.message}", it) }.getOrNull()
            ?: return ToolResult.Error("Unknown stage: $stage")
        return try {
            val output = StringBuilder()
            engine.generate(projectId, mode, prompt, thinkingBudget = thinkingBudget).collect { chunk ->
                output.append(chunk)
            }
            val text = output.toString()
            if (text.isBlank()) {
                return ToolResult.Ok("Creative engine produced no output for $stage.")
            }
            val artifactId = persist(projectId, mode, prompt, text)
            // The tool result is what the model sees next, and the agent-run
            // executor truncates it to 4,000 chars. Naming the artifact means
            // the full text is reachable even when the summary is not.
            ToolResult.Ok(
                if (artifactId != null) "$text\n\n[Saved to project as artifact $artifactId]" else text,
            )
        } catch (e: Exception) {
            ToolResult.Error("Creative engine failed: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    /**
     * Store the stage output as an artifact, returning its id.
     *
     * Best-effort by design: a failed write must not turn a completed (and
     * already billed) generation into a failed tool call, so the text still
     * reaches the model either way.
     */
    private suspend fun persist(projectId: String, mode: CreativeMode, prompt: String, text: String): String? {
        val artifacts = artifactStore ?: return null
        val branches = branchStore ?: return null
        return runCatching {
            val branchId = branches.createMainBranch(projectId).id
            artifacts.create(
                projectId = projectId,
                branchId = branchId,
                kind = mode.name.lowercase(),
                title = "${mode.label} — ${prompt.take(60)}",
                initialContent = text,
                authorKind = "generation",
                prompt = prompt,
            ).id
        }.onFailure {
            Log.w("CreativeEngineTool", "could not persist ${mode.name} output: ${it.message}", it)
        }.getOrNull()
    }
}
