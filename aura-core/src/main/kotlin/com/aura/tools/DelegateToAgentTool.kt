package com.aura.tools

import com.aura.agent.AgentStore
import com.aura.agent.Brain
import com.aura.agent.BrainChunk
import com.aura.agent.Conversation
import com.aura.agent.Specialist
import com.aura.agent.Tool
import com.aura.agent.ToolContext
import com.aura.agent.ToolResult
import com.aura.agent.ToolRisk
import com.aura.memory.MemoryStore
import com.aura.providers.ChatOptions
import com.aura.providers.ProviderMessage
import com.aura.providers.ToolParameters
import com.aura.providers.ToolProperty
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withTimeout
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Delegates a subtask to another agent. The delegated agent runs with
 * its own identity, tools, memory scope, and personality. It executes
 * a simplified agentic loop (up to 5 steps) and returns its final text
 * response as a tool result.
 *
 * No recursive delegation — delegated agents cannot call
 * delegate_to_agent themselves (the tool is not in their tool list).
 *
 * Risk: REMOTE_COST — each delegation makes at least one LLM call.
 */
@Singleton
class DelegateToAgentTool @Inject constructor(
    private val agentStore: AgentStore,
    private val brain: Brain,
    private val toolRegistry: dagger.Lazy<com.aura.agent.ToolRegistry>,
    private val toolExecutor: dagger.Lazy<com.aura.agent.ToolExecutor>,
    private val memoryStore: com.aura.memory.MemoryStore,
    private val providerRegistry: com.aura.providers.ProviderRegistry,
) {
    val tool = Tool(
        name = "delegate_to_agent",
        description = "Delegate a subtask to a specialist agent. The agent runs with its own tools, memory, and personality. Returns the agent's response.",
        risk = ToolRisk.REMOTE_COST,
        parameters = ToolParameters(
            properties = mapOf(
                "agent_name" to ToolProperty(
                    type = "string",
                    description = "Name of the agent to delegate to (e.g. 'researcher', 'coder', 'writer')",
                ),
                "task" to ToolProperty(
                    type = "string",
                    description = "The subtask to delegate to the agent",
                ),
                "context" to ToolProperty(
                    type = "string",
                    description = "Additional context for the agent (optional)",
                ),
            ),
            required = listOf("agent_name", "task"),
        ),
        execute = { call, ctx ->
            val agentName = call.arguments["agent_name"] as? String
                ?: return@Tool ToolResult.Error("missing 'agent_name' argument", "bad_args")
            val task = call.arguments["task"] as? String
                ?: return@Tool ToolResult.Error("missing 'task' argument", "bad_args")
            val context = call.arguments["context"] as? String ?: ""

            try {
                val result = delegate(agentName, task, context, ctx)
                ToolResult.Ok(result)
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                ToolResult.Error("Agent delegation timed out after 30s", "timeout")
            } catch (e: Exception) {
                ToolResult.Error("Delegation failed: ${e.message}", "delegation_error")
            }
        },
        category = "agents",
    )

    private suspend fun delegate(
        agentName: String,
        task: String,
        context: String,
        ctx: ToolContext,
    ): String = withTimeout(DELEGATION_TIMEOUT_MS) {
        val agent = agentStore.byName(agentName)
            ?: throw IllegalArgumentException("Agent '$agentName' not found. Available agents: ${agentStore.allOnce().joinToString(", ") { it.name }}")

        // Resolve the agent's model: preferred model, or first
        // configured provider's first model.
        val model = agent.preferredModel
            ?: runCatching {
                val providers = providerRegistry.configured()
                val firstProvider = providers.firstOrNull()
                val firstModel = firstProvider?.listModels()?.firstOrNull()
                if (firstProvider != null && firstModel != null) "${firstProvider.prefix}:$firstModel" else null
            }.getOrNull()
            ?: throw IllegalStateException("Agent has no preferred model and no configured provider available")

        // Build the agent's system prompt: identity + personality
        val personalityDirective = agent.personality().toPromptDirective()
        val systemPrompt = listOfNotNull(
            agent.identity,
            personalityDirective.ifBlank { null },
        ).joinToString("\n\n")

        // Resolve the agent's tool allowlist
        val registry = toolRegistry.get()
        val allowedTools = agent.toolSet()
        val tools = if (allowedTools.isEmpty()) {
            registry.definitions()
        } else {
            registry.definitions().filter { def ->
                def.name in allowedTools || def.name.startsWith("mcp_") || def.category == "mcp"
            }
        }.filter { def -> def.name != "delegate_to_agent" } // no recursive delegation

        // Recall memories from the agent's scope
        val scopes = if (agent.memoryScope == "shared") {
            setOf("general")
        } else {
            setOf("general", agent.memoryScope)
        }
        val recallHits = memoryStore.query(task, MemoryStore.RecallOptions(limit = 5, scopeFilter = scopes))
        val memoryContext = if (recallHits.isNotEmpty()) {
            "\n\n# Relevant memories:\n" + recallHits.joinToString("\n") { "- [${it.category}] ${it.content}" }
        } else ""

        // Build messages
        val messages = listOfNotNull(
            ProviderMessage(
                role = ProviderMessage.Role.system,
                content = systemPrompt + memoryContext +
                    if (context.isNotBlank()) "\n\n# Context:\n$context" else "",
            ),
            ProviderMessage(
                role = ProviderMessage.Role.user,
                content = task,
            ),
        )

        // Run a mini agentic loop — up to DELEGATION_MAX_STEPS rounds
        // of model + tool calls. This lets the delegated agent actually
        // use its tools (e.g. researcher can call web_search). Bounded
        // by timeout + step count to prevent runaway delegation.
        val options = ChatOptions(temperature = 0.7, maxTokens = 2048)
        val response = StringBuilder()
        var conversation = messages.toMutableList()

        for (step in 1..DELEGATION_MAX_STEPS) {
            val chunks = brain.stream(model, conversation, tools, options).toList()
            val stepText = StringBuilder()
            val stepToolCalls = mutableListOf<Pair<String, String>>()

            for (chunk in chunks) {
                when (chunk) {
                    is BrainChunk.Text -> stepText.append(chunk.text)
                    is BrainChunk.ToolCallEnd -> {
                        stepToolCalls.add(chunk.name to chunk.arguments)
                    }
                    else -> {}
                }
            }

            response.append(stepText)

            // If no tool calls, this step is the final answer.
            if (stepToolCalls.isEmpty()) break

            // Execute tool calls and append results to conversation.
            // Use the proper 'tool' role for tool results — providers
            // that strictly validate message roles (Anthropic) require
            // this. The assistant message is the text + tool call marker.
            val executor = toolExecutor.get()
            for ((toolName, args) in stepToolCalls) {
                if (tools.none { it.name == toolName }) continue
                val result = executor.execute(toolName, args, com.aura.agent.ToolContext(
                    conversationId = "delegation",
                    timeout = 10_000L,
                ))
                val resultText = when (result) {
                    is ToolResult.Ok -> result.output
                    is ToolResult.Error -> "Error: ${result.message}"
                    else -> "Unknown result"
                }
                // Assistant message: the text produced before the tool call
                conversation.add(ProviderMessage(
                    role = ProviderMessage.Role.assistant,
                    content = stepText.toString().ifBlank { "[calling $toolName]" },
                ))
                // Tool result message — use the 'tool' role so strict
                // providers (Anthropic) don't reject the message sequence.
                conversation.add(ProviderMessage(
                    role = ProviderMessage.Role.tool,
                    content = resultText,
                ))
            }
        }

        response.toString().ifBlank { "Agent '$agentName' produced no response." }
    }

    companion object {
        const val DELEGATION_TIMEOUT_MS = 30_000L
        const val DELEGATION_MAX_STEPS = 3
    }
}