package com.aura.tools

import com.aura.agent.TIMEOUT_HEADROOM_MS
import com.aura.agent.AgentStore
import com.aura.agent.Brain
import com.aura.agent.BrainChunk
import com.aura.agent.Conversation
import com.aura.agent.Specialist
import com.aura.agent.Tool
import com.aura.agent.ToolContext
import com.aura.agent.ToolResult
import com.aura.agent.ToolRisk
import com.aura.agent.truncateToolResult
import com.aura.memory.MemoryStore
import com.aura.providers.ChatOptions
import com.aura.providers.ProviderMessage
import com.aura.providers.ToolParameters
import com.aura.providers.ToolProperty
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withTimeout
import javax.inject.Inject
import javax.inject.Singleton
import android.util.Log

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
                ToolResult.Error("Agent delegation timed out after ${DELEGATION_TIMEOUT_MS / 1000}s", "timeout")
            } catch (e: Exception) {
                ToolResult.Error("Delegation failed: ${e.message}", "delegation_error")
            }
        },
        category = "agents",
        timeoutMs = DELEGATION_TIMEOUT_MS + TIMEOUT_HEADROOM_MS,
    )

    suspend fun delegate(
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
            }.onFailure { Log.w("Delegate", "op failed: ${it.message}", it) }.getOrNull()
            ?: throw IllegalStateException("Agent has no preferred model and no configured provider available")

        // Build the agent's system prompt: identity + personality
        val personalityDirective = agent.personality().toPromptDirective()
        val systemPrompt = listOfNotNull(
            agent.identity,
            personalityDirective.ifBlank { null },
        ).joinToString("\n\n")

        // Resolve the agent's tool allowlist.
        // The MCP allowlist rule MUST match the main
        // agentic loop's logic exactly. The main loop
        // (MemoryAugmentedAgenticLoop.kt:310) strips
        // `mcp_<serverId>_<toolName>` to its base name
        // and checks if the base name is in the
        // allowlist. This prevents a user-created
        // specialist (e.g. `allowed=[web_search]`) from
        // silently gaining access to an MCP tool that
        // happens to start with "mcp_" or be in
        // category="mcp" (e.g. an MCP server exposing
        // `delete_file` would NOT be in the allowlist,
        // so the specialist can't use it).
        //
        // Pre-fix (P1 AGENTIC A2): DelegateToAgentTool
        // used the looser check
        //   def.name in allowedTools || def.name.startsWith("mcp_") || def.category == "mcp"
        // which let any MCP tool bypass the agent's
        // allowlist — divergence from the main loop's
        // stricter rule.
        val registry = toolRegistry.get()
        val allowedTools = agent.toolSet()
        val tools = if (allowedTools.isEmpty()) {
            registry.definitions()
        } else {
            registry.definitions().filter { def ->
                if (def.category == "mcp" || def.name.startsWith("mcp_")) {
                    val baseName = if (def.name.startsWith("mcp_")) {
                        // mcp_<serverId>_<toolName> → <toolName>
                        val rest = def.name.removePrefix("mcp_")
                        val firstUnderscore = rest.indexOf('_')
                        if (firstUnderscore > 0) rest.substring(firstUnderscore + 1) else rest
                    } else {
                        def.name
                    }
                    baseName in allowedTools
                } else {
                    def.name in allowedTools
                }
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

        // Pre-fix (P1 AGENTIC A2-A4): the inner ToolContext passed
        // to toolExecutor.execute() was a bare ToolContext with
        // - no memoryEnabled → REMOTE_COST child tools could
        //   never be approved
        // - no approvedRemoteCostTools → same failure mode
        // - no userMessage → policy engine had no anchor for
        //   approval context
        // - 10s timeout killed legitimate 15-30s tools
        //   (brave_search, web_search)
        // - MCP allowlist used `startsWith("mcp_")` while the
        //   main agentic loop uses category check — divergence
        //   meant a child agent could call MCP tools the
        //   parent couldn't (or vice versa).
        //
        // The child context must not inherit the parent's userMessage or
        // per-run REMOTE_COST approvals. A delegated agent's paid-tool
        // requests need their own approval dialog keyed to the delegation
        // task, not the parent's original message.
        val childCtx = ctx.copy(
            conversationId = "delegation:${agent.name}",
            userMessage = "delegate:$agentName: $task",
            approvedRemoteCostTools = emptySet(),
            // Pre-fix had hard-coded 10s. Now use 30s
            // (matching the parent loop's default) so
            // 15-30s tools (brave_search, web_search)
            // have time to complete.
            timeout = 30_000L,
            activeAgentId = if (ctx.activeAgentId.isNotBlank()) ctx.activeAgentId else agent.id,
        )
        val executor = toolExecutor.get()
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
            for ((toolName, args) in stepToolCalls) {
                if (tools.none { it.name == toolName }) {
                    // Signal to the model that the tool is not available
                    // so it can self-correct rather than silently retrying.
                    conversation.add(ProviderMessage(
                        role = ProviderMessage.Role.assistant,
                        content = stepText.toString().ifBlank { "[calling $toolName]" },
                    ))
                    conversation.add(ProviderMessage(
                        role = ProviderMessage.Role.tool,
                        content = "Error: tool '$toolName' is not in your allowed tool set. Available tools: ${tools.joinToString { it.name }}",
                        toolCallId = toolName,
                    ))
                    continue
                }
                val result = executor.execute(toolName, args, childCtx)
                val resultText = when (result) {
                    is ToolResult.Ok -> truncateToolResult(result.output)
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

        val finalResponse = response.toString().ifBlank { "Agent '$agentName' produced no response." }

        // Store the delegation exchange in the agent's memory scope so future
        // delegations to this agent have conversational continuity.
        runCatching {
            memoryStore.maybeStore(
                content = "Delegation to ${agent.name}: task=$task\n\nResponse: ${finalResponse.take(2000)}",
                source = "delegate_to_agent",
                scope = agent.memoryScope,
            )
        }.onFailure { android.util.Log.w("DelegateToAgentTool", "failed to store delegation memory: ${it.message}", it) }

        finalResponse
    }

    companion object {
        const val DELEGATION_TIMEOUT_MS = 30_000L
        const val DELEGATION_MAX_STEPS = 3
    }
}