package com.aura.tools

import com.aura.agent.TIMEOUT_HEADROOM_MS
import com.aura.agent.Brain
import com.aura.agent.Tool
import com.aura.agent.ToolContext
import com.aura.agent.ToolResult
import com.aura.agent.ToolRisk
import com.aura.providers.ChatOptions
import com.aura.providers.ProviderMessage
import com.aura.providers.ToolDefinition
import com.aura.providers.ToolParameters
import com.aura.providers.ToolProperty
import javax.inject.Inject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Singleton
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Parallel research tool — multi-agent decomposition for hard questions.
 *
 * Takes a question, splits it into 2-3 research angles, spawns one
 * subagent per angle (each with web-search tool access), then
 * synthesizes the collected findings into a single answer.
 *
 * This wires the SubagentManager (previously used only by the councils)
 * into normal chat. The decomposition and synthesis use the cheap model
 * tier; the per-angle research runs a 3-step mini agentic loop that can
 * call `web_search` / `wikipedia_search` / `ddg_instant_answer`.
 *
 * Decomposition falls back to a keyword heuristic if the model call
 * fails, so the tool still works offline of the cheap model.
 */
@Singleton
class ParallelResearchTool @Inject constructor(
    private val brain: Brain,
    private val subagentManager: com.aura.agents.SubagentManager,
    private val providerRegistry: com.aura.providers.ProviderRegistry,
    private val webSearchTool: WebSearchTool,
    private val wikipediaSearchTool: WikipediaSearchTool,
    private val ddgInstantAnswerTool: DdgInstantAnswerTool,
    private val cheapModelResolver: com.aura.providers.CheapModelResolver? = null,
) {
    companion object {
        const val MAX_ANGLES = 3
        const val MIN_ANGLES = 2
        const val ANGLE_BUDGET_MS = 45_000L
        const val SYNTHESIS_BUDGET_MS = 30_000L
    }

    fun definition() = ToolDefinition(
        name = "parallel_research",
        description = "Research a complex question from multiple angles in parallel using subagents, then synthesize a single answer. " +
            "Use for questions that benefit from several perspectives (comparisons, pros/cons, multi-part research). " +
            "For simple lookups use web_search instead.",
        parameters = ToolParameters(
            properties = mapOf(
                "question" to ToolProperty(type = "string", description = "The research question"),
            ),
            required = listOf("question"),
        ),
    )

    val tool = Tool(
        name = "parallel_research",
        description = definition().description,
        risk = ToolRisk.READ_ONLY,
        parameters = definition().parameters,
        execute = { call, ctx ->
            val question = call.arguments["question"] as? String
                ?: return@Tool ToolResult.Error("missing 'question' argument", "bad_args")
            if (question.length < 20) {
                return@Tool ToolResult.Error(
                    "Question too short for parallel research — use web_search for simple queries.",
                    "bad_args",
                )
            }
            try {
                val answer = runParallelResearch(question, ctx)
                ToolResult.Ok(answer)
            } catch (e: Exception) {
                ToolResult.Error("parallel research failed: ${e.message}", "research_error")
            }
        },
        category = "web",
        // Angles run to ANGLE_BUDGET_MS, then synthesis runs to
        // SYNTHESIS_BUDGET_MS after them — sequential, so the tool's real
        // worst case is their sum, which was 2.5x the executor's old ceiling.
        timeoutMs = ANGLE_BUDGET_MS + SYNTHESIS_BUDGET_MS + TIMEOUT_HEADROOM_MS,
    )

    /** Split a question into [MAX_ANGLES] research angles via cheap model, with keyword fallback. */
    internal suspend fun decompose(question: String, modelId: String): List<String> {
        return try {
            val messages = listOf(
                ProviderMessage(
                    role = ProviderMessage.Role.system,
                    content = "Split the user's question into $MAX_ANGLES distinct research angles. " +
                        "Each angle should look at a different aspect. Return one angle per line, no numbering, no preamble.",
                ),
                ProviderMessage(role = ProviderMessage.Role.user, content = question),
            )
            val chunks = withTimeoutOrNull(10_000L) {
                brain.stream(modelId, messages, emptyList(), ChatOptions(maxTokens = 200, temperature = 0.3))
                    .toList()
            } ?: emptyList()
            val text = chunks.filterIsInstance<com.aura.agent.BrainChunk.Text>()
                .joinToString("") { it.text }.trim()
            val lines = text.lines().map { it.trim() }.filter { it.length in 8..200 }.take(MAX_ANGLES)
            if (lines.size >= MIN_ANGLES) lines else keywordAngles(question)
        } catch (e: Exception) {
            keywordAngles(question)
        }
    }

    /** Deterministic fallback: split on comparison markers or sentence boundaries. */
    internal fun keywordAngles(question: String): List<String> {
        val lower = question.lowercase()
        val comparison = listOf(" vs ", " versus ", " compared to ", " or ")
        for (marker in comparison) {
            val idx = lower.indexOf(marker)
            if (idx > 5 && idx < question.length - 5) {
                return listOf(
                    question.substring(0, idx).trim().take(150),
                    question.substring(idx + marker.length).trim().take(150),
                    "Overview and key differences: $question".take(150),
                )
            }
        }
        // Split on commas / semicolons for list-like questions
        val parts = question.split(Regex("[,;]")).map { it.trim() }.filter { it.length in 8..150 }
        if (parts.size >= MIN_ANGLES) return parts.take(MAX_ANGLES)
        // Last resort: 3 generic angles
        return listOf(
            "Facts and background: $question",
            "Key considerations and trade-offs: $question",
            "Practical implications: $question",
        ).map { it.take(150) }
    }

    /** Run one research angle: a 3-step mini loop that can call web search tools. */
    internal suspend fun researchAngle(
        angle: String,
        modelId: String,
        ctx: ToolContext,
    ): String {
        val messages = mutableListOf(
            ProviderMessage(
                role = ProviderMessage.Role.system,
                content = "You are a research subagent. Investigate this angle: $angle\n" +
                    "Use web_search or wikipedia_search if you need current information. " +
                    "Return a concise factual summary (3-6 sentences) with key facts, numbers, and source URLs.",
            ),
            ProviderMessage(role = ProviderMessage.Role.user, content = angle),
        )
        val tools = listOf(webSearchTool.definition(), wikipediaSearchTool.definition(), ddgInstantAnswerTool.definition())
        val output = StringBuilder()

        for (step in 1..3) {
            val chunks = brain.stream(modelId, messages, tools, ChatOptions(maxTokens = 800, temperature = 0.4)).toList()
            var text = ""
            val toolCalls = mutableListOf<Pair<String, String>>()
            for (chunk in chunks) {
                when (chunk) {
                    is com.aura.agent.BrainChunk.Text -> {
                        if (chunk.text.isNotEmpty()) text += chunk.text
                    }
                    is com.aura.agent.BrainChunk.ToolCallEnd -> toolCalls.add(chunk.name to chunk.arguments)
                    else -> {}
                }
            }
            if (toolCalls.isEmpty()) {
                output.append(text)
                break
            }
            output.append(text)
            for ((name, argsJson) in toolCalls) {
                val result = when (name) {
                    "web_search" -> runTool(webSearchTool.tool, argsJson, ctx)
                    "wikipedia_search" -> runTool(wikipediaSearchTool.tool, argsJson, ctx)
                    "ddg_instant_answer" -> runTool(ddgInstantAnswerTool.tool, argsJson, ctx)
                    else -> "Tool $name unavailable to research subagent."
                }
                messages.add(ProviderMessage(role = ProviderMessage.Role.assistant, content = text))
                messages.add(ProviderMessage(role = ProviderMessage.Role.tool, content = result, name = name))
            }
        }
        return output.toString().trim().ifEmpty { "No findings for angle: $angle" }
    }

    private suspend fun runTool(tool: Tool, argsJson: String, ctx: ToolContext): String {
        return try {
            val parsed = kotlinx.serialization.json.Json.parseToJsonElement(argsJson).jsonObject
                .mapValues { (_, v) -> v.jsonPrimitive.contentOrNull ?: "" }
            val result = tool.execute(
                com.aura.agent.ToolCall(
                    id = java.util.UUID.randomUUID().toString(),
                    name = tool.name,
                    arguments = parsed,
                ),
                ctx,
            )
            when (result) {
                is ToolResult.Ok -> result.output
                is ToolResult.Error -> "Error: ${result.message}"
                else -> "Tool returned no usable result."
            }
        } catch (e: Exception) {
            "Tool execution failed: ${e.message}"
        }
    }

    /** Run the full pipeline: decompose → spawn subagents in parallel → synthesize. */
    internal suspend fun runParallelResearch(question: String, ctx: ToolContext): String {
        val cheapModelId = resolveCheapModel("")
            ?: return "Configure an LLM provider before running parallel research."
        val angles = decompose(question, cheapModelId)

        // Spawn one subagent per angle through the real SubagentManager —
        // the same infrastructure the councils use (budgets, progress
        // events, timeout handling). Each subagent runs a 3-step mini
        // agentic loop with web-search tool access.
        val tasks = angles.mapIndexed { i, angle ->
            subagentManager.createTask(
                com.aura.agents.SubagentSpec(
                    role = "researcher",
                    objective = "Research this angle and return a concise factual summary (3-6 sentences) " +
                        "with key facts, numbers, and source URLs.\n\nAngle: $angle",
                    modelRole = "GENERAL",
                    toolAllowlist = listOf("web_search", "wikipedia_search", "ddg_instant_answer"),
                    budgetMs = ANGLE_BUDGET_MS,
                ),
                parentRunId = "parallel:${java.util.UUID.randomUUID()}",
            )
        }
        val results = subagentManager.spawnAll(tasks) { task ->
            val start = System.currentTimeMillis()
            val output = researchAngle(task.spec.objective, cheapModelId, ctx)
            com.aura.agents.SubagentResult(
                taskId = task.id,
                success = output.isNotBlank(),
                output = output,
                rationale = "Angle research via ParallelResearchTool.",
                durationMs = System.currentTimeMillis() - start,
            )
        }

        val angleResults = results.map { result ->
            if (result.success) result.output else "No findings: ${result.error}"
        }

        val synthesisInput = angles.zip(angleResults).joinToString("\n\n---\n\n") { (angle, result) ->
            "ANGLE: $angle\nFINDINGS:\n$result"
        }

        return try {
            val messages = listOf(
                ProviderMessage(
                    role = ProviderMessage.Role.system,
                    content = "Synthesize the following multi-angle research findings into one coherent answer to the original question. " +
                        "Be balanced, cite the strongest facts, note disagreements between angles. 4-8 sentences.",
                ),
                ProviderMessage(
                    role = ProviderMessage.Role.user,
                    content = "QUESTION: $question\n\n$synthesisInput",
                ),
            )
            val chunks = withTimeoutOrNull(SYNTHESIS_BUDGET_MS) {
                brain.stream(cheapModelId, messages, emptyList(), ChatOptions(maxTokens = 1200, temperature = 0.3))
                    .toList()
            } ?: emptyList()
            val synthesis = chunks.filterIsInstance<com.aura.agent.BrainChunk.Text>()
                .joinToString("") { it.text }.trim()
            if (synthesis.length >= 40) synthesis else synthesisInput
        } catch (e: Exception) {
            synthesisInput
        }
    }

    /**
     * The cheap model for decomposition and synthesis, or null when nothing is
     * configured.
     *
     * Prefers the caller's own model when it has one — already warm, and not a
     * MoA virtual id, which would fan a "cheap" call out across three providers.
     *
     * The previous fallback was "the first configured provider's first model",
     * which is not a cheapness judgement, and it returned that as a **bare model
     * name** with no `provider:` prefix. `ProviderRegistry.parse` requires a
     * fully-qualified `provider:model` and throws otherwise — and the only call
     * site passes a blank `preferred`, so it always took that branch. This tool
     * has been failing at `decompose`.
     */
    private suspend fun resolveCheapModel(preferred: String): String? {
        if (preferred.isNotBlank() && !preferred.startsWith("moa:")) return preferred
        return cheapModelResolver?.resolve()
    }
}
