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

/**
 * Single-entry tool for durable creative production. Reads a project, runs a
 * named creative stage, and writes the result back as an artifact.
 * Used by [ProductionPipelineEngine] to turn pipeline steps into agent runs.
 */
@Singleton
class CreativeEngineTool @Inject constructor(
    private val engine: CreativeEngine,
    private val registry: ToolRegistry,
) {

    fun definition(): ToolDefinition = ToolDefinition(
        name = "creative_engine",
        description = "Run a Creative Studio stage (brainstorm, outline, draft, rewrite, continuity) on a project and return the result.",
        parameters = ToolParameters(
            properties = mapOf(
                "projectId" to ToolProperty(type = "string", description = "Creative project id"),
                "stage" to ToolProperty(type = "string", description = "One of: brainstorm, outline, draft, rewrite, continuity"),
                "prompt" to ToolProperty(type = "string", description = "User prompt for this stage"),
            ),
            required = listOf("projectId", "stage", "prompt"),
        ),
    )

    val tool = Tool(
        name = definition().name,
        description = definition().description,
        risk = ToolRisk.READ_ONLY,
        parameters = definition().parameters,
        category = "creative",
        execute = { call, ctx -> execute(call, ctx) },
    )

    private suspend fun execute(call: ToolCall, ctx: ToolContext): ToolResult {
        val projectId = call.arguments["projectId"] as? String ?: return ToolResult.Error("projectId required")
        val stage = call.arguments["stage"] as? String ?: return ToolResult.Error("stage required")
        val prompt = call.arguments["prompt"] as? String ?: return ToolResult.Error("prompt required")
        val mode = runCatching { CreativeMode.valueOf(stage.uppercase()) }.getOrNull()
            ?: return ToolResult.Error("Unknown stage: $stage")
        return try {
            val output = StringBuilder()
            engine.generate(projectId, mode, prompt).collect { chunk ->
                output.append(chunk)
            }
            ToolResult.Ok(output.toString().ifBlank { "Creative engine produced no output for $stage." })
        } catch (e: Exception) {
            ToolResult.Error("Creative engine failed: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    fun register() = registry.register(tool)
}
