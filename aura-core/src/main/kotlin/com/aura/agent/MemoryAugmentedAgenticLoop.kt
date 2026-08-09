package com.aura.agent

import com.aura.provenance.ConversationProvenance
import com.aura.hands.HandRepository
import com.aura.kg.ConversationKgExtractor
import com.aura.memory.LlmWriteGate
import com.aura.memory.MemoryStore
import com.aura.memory.WriteGate
import com.aura.providers.ChatOptions
import com.aura.providers.ProviderMessage
import com.aura.providers.ProviderMessage.Role
import com.aura.providers.ModelCatalogRepository
import com.aura.providers.ToolDefinition
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.ensureActive
import android.util.Log

/**
 * Maximum length of a tool result that gets appended to the
 * conversation history. Tool results that exceed this budget are
 * truncated and marked so the model can ask for the rest if it
 * needs more detail. Without this, a single web search or
 * deep-research tool could blow the context window — 8k tokens
 * at 4 chars/token = 32k chars per result.
 *
 * Per-tool truncation (Firecrawl 8k, DeepResearch 6k) is the
 * first line of defense; this is the safety net for everything
 * else.
 */
private const val MAX_TOOL_RESULT_CHARS = 4_000

private const val TOOL_RESULT_TRUNCATION_MARKER =
    "\n\n[...truncated, ask the assistant to retrieve the full result if needed]"

/**
 * Tools whose execution counts as genuine information-seeking for the
 * CURIOSITY drive. Checked against [Conversation.ToolTurn.name] after a
 * completed turn. Deliberately excludes `recall` — reading existing
 * memory is not new-information seeking — and action tools (calendar,
 * email, timers), which used to satisfy CURIOSITY under the old
 * "any tool ran" rule.
 */
private val CURIOSITY_TOOLS = setOf(
    "web_search", "web_search_capability", "deep_research", "parallel_research",
    "brave_search", "tavily_search", "ddg_instant_answer", "searxng_search",
    "wikipedia_search", "fetch_url", "read_url", "kg_query", "knowledge_graph_extract",
)

/**
 * Tools whose execution counts as exercising a skill, for the COMPETENCE
 * drive. Checked against [Conversation.ToolTurn.name] after a completed turn.
 *
 * COMPETENCE's intensity comes from `DriveSignals.lowConfidenceSkillCount`, so
 * it rises whenever weak skills exist. Nothing satisfied it before 2026-08-08,
 * which left it monotonically increasing and permanently at the top of
 * `mostUrgent()`. Running a skill — directly, through a hand, or via a
 * delegated agent — is the observable act of practising one.
 */
private val COMPETENCE_TOOLS = setOf(
    "use_skill", "run_hand", "delegate_to_agent", "code_interpreter",
)

/**
 * Upper bound on how long the loop honors a 429 Retry-After before
 * retrying the same model. Servers occasionally send absurd values
 * (minutes); the user is waiting on a chat, so cap the pause.
 */
private const val MAX_RETRY_AFTER_WAIT_MS = 10_000L

/**
 * Truncate a tool result string to fit the conversation-history budget.
 * Long results (deep research, web fetches, photo library) are
 * truncated at [MAX_TOOL_RESULT_CHARS] with a marker so the model
 * knows to ask for the rest if it needs more detail.
 */
internal fun truncateToolResult(raw: String): String =
    if (raw.length > MAX_TOOL_RESULT_CHARS) {
        raw.take(MAX_TOOL_RESULT_CHARS) + TOOL_RESULT_TRUNCATION_MARKER
    } else {
        raw
    }

/**
 * The memory-augmented agentic loop. Pre-pends relevant memories to the system
 * prompt before each model call, and auto-stores memorable user facts after.
 * Also extracts a knowledge graph from each assistant turn (best-effort).
 */
@Singleton
class MemoryAugmentedAgenticLoop @Inject constructor(
    private val brain: Brain,
    private val toolRegistry: ToolRegistry,
    private val toolExecutor: ToolExecutor,
    private val memoryStore: MemoryStore,
    private val kgExtractor: ConversationKgExtractor,
    private val userProfileStore: com.aura.profile.UserProfileStore,
    private val handRepository: com.aura.hands.HandRepository,
    private val providerRegistry: com.aura.providers.ProviderRegistry,
    private val conversationCompactor: ConversationCompactor,
    private val modelCatalogRepository: ModelCatalogRepository? = null,
    private val beliefDao: com.aura.world.BeliefDao? = null,
    private val emotionEngine: com.aura.emotion.EmotionEngine? = null,
    private val agentStore: AgentStore? = null,
    private val tasteEngine: com.aura.taste.TasteEngine? = null,
    private val traceSink: com.aura.agent.runtime.TraceSink? = null,
    private val reflectionEngine: ReflectionEngine? = null,
    private val strategyBandit: StrategyBandit? = null,
    private val llmProfileExtractor: com.aura.profile.LlmProfileExtractor? = null,
    private val tastePromptEnhancer: com.aura.taste.TastePromptEnhancer? = null,
    private val worldEventProducer: com.aura.world.WorldEventProducer? = null,
    private val narrativeSelf: com.aura.consciousness.NarrativeSelf? = null,
    private val intrinsicMotivation: com.aura.consciousness.IntrinsicMotivation? = null,
    private val theoryOfMind: com.aura.consciousness.TheoryOfMind? = null,
    private val affinityTracker: com.aura.consciousness.AffinityTracker? = null,
    private val responseCache: ResponseCache? = null,
    private val reasoningTree: ReasoningTree? = null,
    private val userPreferences: com.aura.data.UserPreferences? = null,
    private val driveSignals: com.aura.consciousness.DriveSignals? = null,
    private val cheapModelResolver: com.aura.providers.CheapModelResolver? = null,
    private val modelRoleRouter: com.aura.providers.ModelRoleRouter? = null,
) {
    /**
     * Tools the loop paused on because they returned
     * [ToolResult.NeedsPermission], keyed by conversation id. The loop stashes
     * the full run snapshot so [resumeAfterGate] can continue the
     * agentic run after the user grants. Entries are removed on resume and on
     * deny.
     *
     * Keyed rather than a single field because the loop is a `@Singleton`: one
     * slot meant a second conversation hitting a permission gate silently
     * overwrote the first conversation's snapshot, stranding that run with no
     * way to resume. It also meant the last snapshot — which holds an entire
     * [Conversation] — was retained for the process lifetime.
     */
    private val pendingGates = java.util.concurrent.ConcurrentHashMap<String, PendingGate>()

    /** Which gate paused the run — drives the dialog kind and the resume grant. */
    enum class GateKind { PERMISSION, CONFIRMATION, APPROVAL }

    /** Local destructuring helper for building a [PendingGate] from a ToolResult. */
    internal data class GateDescriptor(
        val kind: GateKind,
        val permission: String,
        val level: String,
        val rationale: String,
    )

    /**
     * Snapshot of the run at the moment the loop paused on a gate
     * (runtime permission, policy confirmation, or remote-cost approval).
     * Carries everything [resumeAfterGate] needs to continue the run from
     * `step + 1`: the held tool, the conversation as-of-pause, the model,
     * and all options/flags.
     */
    data class PendingGate(
        val kind: GateKind,
        val toolName: String,
        val toolCallId: String,
        val args: String,
        /** Android permission string (PERMISSION kind only). */
        val permission: String = "",
        /** ConfirmationLevel name (CONFIRMATION kind only). */
        val confirmationLevel: String = "",
        val rationale: String,
        val conversation: Conversation,
        val model: String,
        val maxSteps: Int,
        val options: ChatOptions,
        val recallLimit: Int,
        val specialist: Specialist?,
        val memoryEnabled: Boolean,
        val approvedRemoteCostTools: Set<String>,
        val confirmedTools: Set<String>,
        val agentId: String?,
        val runId: String,
        val step: Int,
    )

    /**
     * Read-only view of the gate held for [conversationId], if any. UI uses
     * this to drive the gate dialog (Allow / Deny). Returns null when no
     * tool is waiting for that conversation.
     */
    fun peekPendingGate(conversationId: String): PendingGate? =
        pendingGates[conversationId]

    /**
     * Drop the held gate for [conversationId] without resuming. Called by
     * the UI when the user taps Deny. The dangling tool call is dropped
     * from the wire by Conversation.toMessages, so no strict-provider 400
     * results from a denial.
     */
    fun denyPendingGate(conversationId: String) {
        val held = pendingGates.remove(conversationId) ?: return
        android.util.Log.i(
            "AgenticLoop",
            "gate denied for tool=${held.toolName} kind=${held.kind}",
        )
    }

    /**
     * Resume a run that paused because a tool returned [ToolResult.NeedsPermission].
     *
     * The flow emits, in order:
     * - [AgentEvent.ToolExecuting] for the held tool
     * - [AgentEvent.ToolResult] for the held tool
     * - a fresh `run()`-shaped stream that continues the agentic loop from
     *   `heldStep + 1` with the held conversation (now containing the
     *   tool's actual output)
     *
     * If no tool is waiting for [conversationId], emits a single
     * `Error("no_pending", ...)` and `Done` so the UI gets a clear signal.
     *
     * Concurrency: `remove` is atomic, so two concurrent resumes for the same
     * conversation cannot both replay the tool — the loser sees null and
     * emits no_pending.
     */
    fun resumeAfterGate(conversationId: String): Flow<AgentEvent> = flow {
        val held = pendingGates.remove(conversationId)
        if (held == null) {
            emit(
                AgentEvent.Error(
                    code = "no_pending",
                    message = "no tool waiting on a gate",
                    retryable = false,
                ),
            )
            emit(AgentEvent.Done)
            return@flow
        }

        // The grant depends on the gate kind: a confirmation adds the tool
        // to the per-conversation confirmed set, an approval to the
        // remote-cost set. A runtime permission needs no ctx grant — the
        // tool re-reads PackageManager state, which the user just changed.
        val grantedConfirmed =
            if (held.kind == GateKind.CONFIRMATION) held.confirmedTools + held.toolName
            else held.confirmedTools
        val grantedApproved =
            if (held.kind == GateKind.APPROVAL) held.approvedRemoteCostTools + held.toolName
            else held.approvedRemoteCostTools

        // Replay the held tool with the grant in context.
        val resumedCtx = ToolContext(
            conversationId = held.conversation.id,
            userMessage = held.conversation.turns.lastOrNull { it.user != null }?.user ?: "",
            memoryEnabled = held.memoryEnabled,
            approvedRemoteCostTools = grantedApproved,
            confirmedTools = grantedConfirmed,
        )
        traceSink?.emit(
            held.runId,
            com.aura.agent.runtime.TraceEventType.TOOL_CALL,
            stepId = "resume_${held.step}",
            toolName = held.toolName,
        )
        emit(AgentEvent.ToolExecuting(held.toolCallId, held.toolName, held.args))
        val resumedResult = runCatching {
            toolExecutor.execute(held.toolName, held.args, resumedCtx)
        }.onFailure { e ->
            android.util.Log.w("AgenticLoop", "resumed tool ${held.toolName} threw: ${e.message}", e)
        }.getOrElse { e ->
            ToolResult.Error("resumed tool threw: ${e.message ?: e::class.java.simpleName}", "exception")
        }
        traceSink?.emit(
            held.runId,
            com.aura.agent.runtime.TraceEventType.TOOL_RESULT,
            stepId = "resume_${held.step}",
            toolName = held.toolName,
            success = resumedResult is ToolResult.Ok,
        )
        val resumedText = when (resumedResult) {
            is ToolResult.Ok -> resumedResult.output
            is ToolResult.Error -> "Error: ${resumedResult.message}"
            is ToolResult.NeedsPermission -> "Permission still needed: ${resumedResult.permission}"
            is ToolResult.NeedsApproval -> "Approval needed: ${resumedResult.rationale}"
            is ToolResult.NeedsConfirmation -> "Confirmation still needed: ${resumedResult.rationale}"
        }
        val truncated = truncateToolResult(resumedText)
        val resumedConversation = held.conversation.setToolResult(held.toolCallId, truncated)
        emit(
            AgentEvent.ToolResult(
                id = held.toolCallId,
                name = held.toolName,
                arguments = held.args,
                result = truncated,
            ),
        )

        // Continue the agentic loop from step+1 with the resumed conversation.
        // We re-use the same per-step body via a fresh run() flow seeded with
        // the resumed conversation. The resumed run uses the same runId so
        // trace events correlate end-to-end.
        val tail = run(
            conversation = resumedConversation,
            model = held.model,
            maxSteps = held.maxSteps,
            options = held.options,
            recallLimit = held.recallLimit,
            specialist = held.specialist,
            memoryEnabled = held.memoryEnabled,
            approvedRemoteCostTools = grantedApproved,
            confirmedTools = grantedConfirmed,
            agentId = held.agentId,
        )
        // Drain `tail` with one twist: the resumed run's first step is
        // logically `heldStep + 1`, but the run() flow doesn't know that.
        // We don't need to renumber — trace events use a per-run step
        // counter that starts at 1. The held tool's events have already
        // been emitted with the "resume_${held.step}" stepId, so the trace
        // is still coherent.
        tail.collect { emit(it) }
    }


    /**
     * Run the agentic loop, optionally overriding the base system prompt
     * with a [Specialist]'s system prompt.
     */
    fun run(
        conversation: Conversation,
        model: String,
        strategy: ReasoningStrategy? = null,
        maxSteps: Int = if (strategy != null) strategy.maxSteps else 10,
        options: ChatOptions = ChatOptions(),
        recallLimit: Int = 5,
        specialist: Specialist? = null,
        /**
         * Session-level write flag. When false:
         *   - The user message is NOT auto-stored.
         *   - The assistant turn is NOT fed to the profile extractor.
         *   - The knowledge-graph extractor does NOT run.
         *   - WRITE_LOCAL tools (e.g. `remember`) are refused by ToolExecutor.
         * Read-only tools (recall, web_search, kg_query) and the agent's
         * general reasoning still run. Used by the incognito toggle in
         * ChatScreen so the user can have a private session without any
         * long-term writes.
         */
        memoryEnabled: Boolean = true,
        /**
         * Per-conversation set of REMOTE_COST tool names the user
         * has explicitly approved. Passed into [ToolContext] so
         * [ToolExecutor] lets them through without re-prompting.
         */
        approvedRemoteCostTools: Set<String> = emptySet(),
        /**
         * Per-conversation set of tool names whose policy confirmation
         * the user granted. Mirror of [approvedRemoteCostTools] for the
         * ConfirmationLevel gate.
         */
        confirmedTools: Set<String> = emptySet(),
        /**
         * Agent ID for per-agent memory scoping. When set, recall
         * filters to the agent's private scope + shared ("general").
         * When null, defaults to "general" only (current behavior).
         */
        agentId: String? = null,
        /**
         * Whether to make a separate planning call before step 1. Costs an
         * extra LLM round-trip (up to 15s) on every qualifying user message
         * in exchange for better tool selection. Opt-in — see
         * [com.aura.data.UserPreferences.planningEnabled].
         */
        planningEnabled: Boolean = false,
        /**
         * Keywords from the user's recent conversation topics (computed
         * by [com.aura.agent.ConversationStore.recentTopics]). Injected
         * into the system prompt so the agent has cross-conversation
         * continuity — it knows what the user has been discussing.
         */
        recentTopics: String = "",
    ): Flow<AgentEvent> = flow {
        val runId = "run_${java.util.UUID.randomUUID()}"
        traceSink?.emit(runId, com.aura.agent.runtime.TraceEventType.RUN_STARTED, redactedPayload = "model=$model, agentId=$agentId")
        // Neuromodulated sampling: map the emotional state onto LLM
        // sampling parameters (temperature/topP/maxTokens) so mood affects
        // generation, not just the prompt's tone directive. Only adjusts —
        // never overrides an explicit caller choice.
        val effectiveOptions = emotionEngine?.applySampling(options) ?: options
        // Response cache fast path (ported from Python Aura's response
        // cache): if this exact short question was answered recently with
        // a pure text reply (no tool calls), replay it instantly instead
        // of a full model round-trip. Only fires for short questions in a
        // fresh conversation — anything tool-driven or context-heavy
        // misses and runs normally.
        val lastUserTurn = conversation.turns.lastOrNull { it.user != null }
        val lastUserText = lastUserTurn?.user.orEmpty()
        val cacheKey = lastUserText.takeIf { it.length in 2..120 }
            ?.let { normalizeCacheKey(it) + "|" + model }
        val cachedAnswer = if (cacheKey != null && conversation.turns.size <= 2 && memoryEnabled) {
            responseCache?.get(cacheKey)
        } else null
        if (cachedAnswer != null) {
            traceSink?.emit(runId, com.aura.agent.runtime.TraceEventType.RUN_COMPLETED, redactedPayload = "response_cache_hit")
            emit(AgentEvent.TextDelta(cachedAnswer))
            val withReply = conversation.addAssistant(cachedAnswer)
            emit(AgentEvent.Result(withReply, null))
            emit(AgentEvent.Done)
            return@flow
        }
        // After a normal run completes, record the answer for repeat
        // questions (done below where the final text is known).
        val allTools = specialist?.let { s ->
            val allowed = s.toolsAllowed
            if (allowed.isEmpty()) toolRegistry.definitions()
            else toolRegistry.definitions().filter { def ->
                // Exact match for native tools, plus a controlled pass
                // for MCP tools. Until v0.30.x the filter used
                //   def.name.startsWith("mcp_") || def.category == "mcp"
                // which let *any* MCP tool bypass the specialist's
                // allowlist. That was a security boundary: a user who
                // created a "research" specialist with `allowed=[web_search]`
                // and then connected an MCP server exposing `delete_file`
                // would see the specialist silently gain file-deletion
                // capability without any UI indication.
                //
                // The correct rule: an MCP tool is allowed if its
                // unprefixed base name (what the MCP server actually
                // exposes) is in the specialist's allowlist. If the
                // specialist doesn't have a base name match, the tool
                // is hidden — even though the registry exposes it. This
                // matches the user-visible intent of "this specialist
                // can only call these tools."
                if (def.category == "mcp" || def.name.startsWith("mcp_")) {
                    val baseName = if (def.name.startsWith("mcp_")) {
                        // mcp_<serverId>_<toolName> → <toolName>.
                        // serverIds MAY contain underscores (McpToolBridge
                        // registers mcp_<serverId>_<toolName> verbatim and
                        // tracks ownership via registeredNameToServerId).
                        // The first segment after the prefix is the server
                        // id; the remainder is the tool name. If the server
                        // id itself contains an underscore, this splits at
                        // the FIRST one, which still yields the correct
                        // tool name for allowlist matching (the base name
                        // is what the MCP server actually exposes).
                        val rest = def.name.removePrefix("mcp_")
                        val firstUnderscore = rest.indexOf('_')
                        if (firstUnderscore > 0) rest.substring(firstUnderscore + 1) else rest
                    } else {
                        // Tool was registered unprefixed because there
                        // was no native collision — base name == def.name.
                        def.name
                    }
                    baseName in allowed
                } else {
                    def.name in allowed
                }
            }
        } ?: toolRegistry.definitions()
        // Hide search tools that need an API key the user hasn't configured.
        // The LLM should only see search tools that will actually work.
        val tools = filterSearchTools(allTools)
        // The step counter tracks model attempts. It is incremented at the
        // top of the outer step loop; the inner failover loop retries the
        // SAME step with a different provider without consuming another slot.
        var step = 0
        var finished = false
        var lastUserMessage = ""
        var currentConversation = conversationCompactor.compactIfNeeded(conversation, model)
        var effectiveModel = model
        // Where this run's tool-call step numbering starts.
        //
        // `step` restarts at 1 on every run(), and resumeAfterGate continues a
        // paused run by calling run() again with the held conversation. Tagging
        // the resumed run's first step as 1 would merge its calls into the
        // group the original step 1 already owns, which is exactly the
        // fabricated-parallel-batch bug this tagging exists to prevent. Seeding
        // from the highest step already recorded on the turn keeps the sequence
        // monotonic across the pause. Zero for a fresh run.
        val toolStepOffset = currentConversation.turns.lastOrNull()
            ?.toolTurns.orEmpty()
            .maxOfOrNull { it.step } ?: 0

        // Recall cache: keyed by (userMessage, agentId). Prevents re-running
        // the full RRF + embedding + DB query pipeline on every step of a
        // multi-step agentic loop. The user message doesn't change between
        // steps — only tool results are appended — so the recall is the
        // same on step 2 as step 1.
        var cachedRecall: Triple<String, String?, List<com.aura.memory.MemoryEntity>>? = null
        // Cached personality directive — resolved once per agent per run.
        // Without this, agentStore.byId() hits Room on every step of the
        // agentic loop (5-10 steps per turn).
        var cachedPersonality: String? = null
        var cachedTasteContext: String? = null
        // Cached cheap model ID — avoids 2 live /models API calls per step
        // (rerankModel + rewriteModel resolution). The available models
        // don't change mid-conversation.
        var cachedCheapModel: kotlin.String? = null

        // Tracks the most recent recall across all steps. The agentic loop
        // can perform multiple model steps for one user turn — for example,
        // one with tools and one without; we capture the recall
        // from the last step that actually performed recall so the
        // chip shows the most relevant memories for the final
        // assistant text. Null when no step recalled anything
        // (or memoryEnabled=false in incognito mode).
        var lastRecall: com.aura.agent.RecallSummary? = null

        // Track tool errors for reflection on failure.
        val toolErrors = mutableListOf<Pair<kotlin.String, kotlin.String>>()

        while (!finished && step < maxSteps) {
            step += 1
            coroutineContext.ensureActive()
            traceSink?.emit(runId, com.aura.agent.runtime.TraceEventType.STEP_STARTED, stepId = "step_$step")

            // 1) Recall relevant memories for the last user message
            lastUserMessage = currentConversation.turns.lastOrNull { it.user != null }?.user ?: ""

            // 1b) Trigger-phrase hand check: if an enabled hand's phrase is a
            // substring of the user's message, prepend a run_hand tool call
            // so the model executes it. This is the wiring that makes a
            // hand's advertised trigger phrase actually fire.
            val handTrigger = findMatchingHand(lastUserMessage)
            val handContext = handTrigger?.let { hand ->
                "\n\n# Triggered automation:\nThe user triggered hand '${hand.name}'. " +
                    "Run it with run_hand(name=\"${hand.name}\")."
            } ?: ""

            // Single recall pass per step. Earlier revisions of this loop
            // called memoryStore.query() twice — once to capture the IDs
            // for the recall chip, and again to build the memoryContext
            // injected into the system prompt. Each call is an embedder
            // hit (cloud round-trip) plus an RRF rank. We now compute the
            // hits once and reuse the list for both the chip and the
            // context block. The semantic dedup check in MemoryStore is
            // still applied at write time; here we only read.
            //
            // Skip in incognito mode — we don't want to surface "Aura
            // used N memories" when the user opted out of memory
            // entirely. The MemoryStore call itself is read-only so this
            // gate is just about the chip and the persisted Turn.recall
            // field.
            val recallHits: List<com.aura.memory.MemoryEntity> =
                if (memoryEnabled && lastUserMessage.isNotBlank()) {
                    // Check recall cache — same user message + agent = same results.
                    // The agentic loop runs multiple steps per turn (one per
                    // tool round-trip), all with the same lastUserMessage.
                    // Caching saves RRF ranking + embedding + DB query on
                    // steps 2-N.
                    if (cachedRecall != null && cachedRecall.first == lastUserMessage && cachedRecall.second == agentId) {
                        cachedRecall.third
                    } else {
                        val scopes = if (agentId != null) {
                            setOf("general", "agent:$agentId")
                        } else {
                            setOf("general")
                        }
                        // A small model for reranking and query rewriting.
                        // Both are short auxiliary calls — a yes/no judgment
                        // and a one-line rewrite — not generation.
                        //
                        // Ranked by CheapModelHeuristic rather than by name
                        // length, which picks "gpt-4o" over "gpt-4o-mini",
                        // i.e. exactly the wrong model. Resolution also honours
                        // the user's explicit Fast model, which nothing read
                        // before. Still cached per run: the catalog is behind a
                        // TTL cache now, but this also avoids re-resolving on
                        // every step of a multi-step turn.
                        val cheapModel = cachedCheapModel
                            ?: cheapModelResolver?.resolve()
                        val rerankModel = cheapModel
                        val rewriteModel = cheapModel
                        // Cache the resolved model for subsequent steps.
                        if (cachedCheapModel == null && rerankModel != null) {
                            cachedCheapModel = rerankModel
                        }
                        // Build recent context for query rewriting —
                        // last 3 turns of conversation for pronoun/deictic
                        // resolution.
                        val recentContext = currentConversation.turns
                            .takeLast(3)
                            .joinToString("\n") { turn ->
                                listOfNotNull(
                                    turn.user?.take(200)?.let { "user: $it" },
                                    turn.assistant?.take(200)?.let { "assistant: $it" },
                                ).joinToString("\n")
                            }
                        val hits = memoryStore.query(
                            lastUserMessage,
                            com.aura.memory.MemoryStore.RecallOptions(
                                limit = recallLimit,
                                scopeFilter = scopes,
                                rerankModel = rerankModel,
                                rewriteModel = rewriteModel,
                                recentContext = recentContext,
                            ),
                        )
                        cachedRecall = Triple(lastUserMessage, agentId, hits)
                        hits
                    }
                } else emptyList()
            val stepHandIds: List<String> = handTrigger?.let { listOf(it.id) } ?: emptyList()
            if (memoryEnabled) {
                // Only persist a non-null recall when memory is on
                // for this turn. The chip is hidden otherwise.
                lastRecall = com.aura.agent.RecallSummary(
                    memoryIds = recallHits.map { it.id },
                    handIds = stepHandIds,
                    noResults = recallHits.isEmpty() && stepHandIds.isEmpty(),
                )
                if (recallHits.isNotEmpty()) {
                    traceSink?.emit(runId, com.aura.agent.runtime.TraceEventType.MEMORY_RECALLED, stepId = "step_$step", redactedPayload = "${recallHits.size} memories")
                }
            }

            val memoryContext = if (recallHits.isNotEmpty()) {
                val lines = recallHits.map { m ->
                    "- [${m.category}] ${m.content}"
                }.joinToString("\n")
                "\n\n## Relevant memories:\n$lines"
            } else ""

            // Include active world-model beliefs in the system context
            // so the agent has access to resolved assertions (not just
            // raw memories). Beliefs are confidence-weighted and
            // supersede-able, making them more reliable than raw recall
            // for stable facts about the user.
            val beliefContext = if (memoryEnabled && beliefDao != null) {
                runCatching {
                    val beliefs = beliefDao.allActive(10)
                    if (beliefs.isEmpty()) "" else {
                        val lines = beliefs.map { b ->
                            "- ${b.subject} ${b.predicate}: ${b.valueJson} (confidence: ${"%.0f".format(b.confidence * 100)}%)"
                        }.joinToString("\n")
                        "\n\n## Known beliefs:\n$lines"
                    }
                }.onFailure { android.util.Log.w("AgenticLoop", "belief context load failed: ${it.message}", it) }.getOrDefault("")
            } else ""

            // Update emotion state from the user's message and include
            // the mood context + adaptive profile in the system prompt.
            //
            // The mutating update()/decay() run ONCE per user turn (step 1
            // only). A single user turn can drive several loop steps (one
            // per tool round-trip), all reading the same lastUserMessage —
            // nudging the mood on every step would over-weight one message
            // by the number of tools it happened to trigger. The mood/profile
            // read is still injected on every step (cheap, reflects current
            // state) so later steps see the freshly-updated mood.
            val emotionContext = if (memoryEnabled && emotionEngine != null) {
                if (step == 1) {
                    emotionEngine.update(lastUserMessage)
                    emotionEngine.decay()
                    // Consciousness: update user mental model from message, then
                    // persist. Without the save the model is discarded on every
                    // process death, and toPrompt() stays silent until
                    // sampleCount >= 3 — which it could almost never reach.
                    runCatching {
                        theoryOfMind?.updateFromMessage(lastUserMessage)
                        theoryOfMind?.save()
                    }.onFailure { android.util.Log.w("AgenticLoop", "ToM update failed: ${it.message}", it) }
                    // Intrinsic motivation: assess drives from observable signals.
                    // No LLM; DB cost is bounded by DriveSignals' TTL cache —
                    // at most 3 indexed COUNT queries per 5 minutes, so the
                    // hot path almost always reads an in-memory snapshot.
                    runCatching {
                        val hoursSince = if (currentConversation.turns.isNotEmpty()) {
                            ((System.currentTimeMillis() - currentConversation.turns.last().timestamp) / (1000f * 60 * 60)).coerceAtLeast(0f)
                        } else 0f
                        val signals = driveSignals?.let { ds -> runCatching { ds.get() }.getOrNull() }
                        intrinsicMotivation?.assess(
                            kgGapCount = signals?.kgGapCount ?: 0,
                            lowConfidenceSkillCount = signals?.lowConfidenceSkillCount ?: 0,
                            hoursSinceLastInteraction = hoursSince,
                            contradictionCount = signals?.contradictionCount ?: 0,
                        )
                        intrinsicMotivation?.save()
                    }.onFailure { android.util.Log.w("AgenticLoop", "motivation assess failed: ${it.message}", it) }
                }
                val mood = emotionEngine.moodString()
                val profile = emotionEngine.profile()
                "\n\n# Current mood: $mood" + profile.promptSuffix
            } else ""

            // Taste context: learned preferences from user signals.
            // Only computed on step 1 (like emotion) — tastes don't
            // change mid-turn. Falls back to empty string if no
            // TasteEngine is configured or no signals exist yet.
            val tasteContext = if (step == 1 && tasteEngine != null) {
                val tasteScopes = if (agentId != null) listOf("general", "agent:$agentId") else listOf("general")
                val rawTaste = runCatching { tasteEngine.getTasteContext(tasteScopes) }.onFailure { android.util.Log.w("AgenticLoop", "taste context failed: ${it.message}", it) }.getOrDefault("")
                // Enhance on step 1 so the FIRST step's system prompt
                // gets the explicit instructions, not just step 2+.
                val enhancedTaste = if (tastePromptEnhancer != null && rawTaste.isNotBlank()) {
                    val enhanced = tastePromptEnhancer.enhance("", rawTaste)
                    if (enhanced.isNotBlank()) enhanced else rawTaste
                } else rawTaste
                cachedTasteContext = enhancedTaste
                enhancedTaste
            } else cachedTasteContext ?: ""


            // 2) Build messages
            // Resolve the active agent's personality directive (if any).
            // Cached after first resolution — agentStore.byId() is a Room
            // query and the agent doesn't change mid-conversation.
            if (cachedPersonality == null && agentId != null) {
                cachedPersonality = runCatching {
                    agentStore?.byId(agentId)?.personality()?.toPromptDirective()
                }.onFailure { android.util.Log.w("AgenticLoop", "personality resolution failed: ${it.message}", it) }.getOrNull() ?: ""
            }
            val personalityDirective = cachedPersonality ?: ""

            // Inject prior reflection from a failed previous run in this
            // conversation. The reflection tells the model what went wrong
            // last time and what to try differently. Only present when a
            // prior run in this conversation hit max_steps_exceeded with
            // tool errors.
            val priorReflection = currentConversation.metadata["lastReflection"] as? kotlin.String
            val reflectionContext = if (!priorReflection.isNullOrBlank()) {
                "\n\n## Previous attempt reflection:\n$priorReflection"
            } else ""
            val topicContext = if (recentTopics.isNotBlank()) {
                "\n\n## Recent topics: $recentTopics\nIf relevant, offer to continue where the user left off."
            } else ""

            // Everything Aura retrieved from its own stores goes in one
            // delimited section under an untrusted-data preamble.
            //
            // These four blocks used to be appended bare to the system prompt,
            // indistinguishable from Aura's own identity and the specialist's
            // instructions. That matters because their content is
            // attacker-reachable in one hop: the model reads a page with
            // read_url, judges a line memorable, calls remember, and the line
            // comes back as `## Relevant memories` inside the system message on
            // a later turn. Beliefs and taste are LLM-derived from the same
            // material. Conversation.toMessages and both summarisation prompts
            // already framed their content this way; the highest-trust region
            // of the prompt was the one place that did not.
            //
            // Deliberately NOT included: emotion, hands, reflection, and the
            // consciousness blocks. Those are computed by Aura from the user's
            // own input or authored by the user directly — they are not
            // retrieved content and framing them as untrusted would be wrong.
            val retrievedContext = listOf(topicContext, memoryContext, beliefContext, tasteContext)
                .filter { it.isNotBlank() }
                .joinToString("")
            val framedRetrievedContext = if (retrievedContext.isBlank()) {
                ""
            } else {
                "\n\n# Retrieved context\n" + PromptFraming.UNTRUSTED_CONTEXT_PREAMBLE + retrievedContext
            }

            val messages = buildList {
                val sys = listOfNotNull(
                    if (agentId != null) runCatching { agentStore?.byId(agentId)?.identity?.ifBlank { null } }.onFailure { android.util.Log.w("AgenticLoop", "identity resolution failed: ${it.message}", it) }.getOrNull() else null,
                    specialist?.systemPrompt,
                    personalityDirective.ifBlank { null },
                    currentConversation.systemPrompt,
                    brain.resolvedIdentity().ifBlank { null },
                    userProfileStore.getSystemPrompt().ifBlank { null },
                ).joinToString("\n\n") + framedRetrievedContext + emotionContext + handContext + reflectionContext +
                    // Consciousness layer: NarrativeSelf, IntrinsicMotivation, TheoryOfMind.
                    // All are heuristic, no LLM cost. Injected on step 1 only.
                    (if (step == 1) {
                        listOfNotNull(
                            narrativeSelf?.toPrompt()?.ifBlank { null },
                            intrinsicMotivation?.toPrompt()?.ifBlank { null },
                            theoryOfMind?.toPrompt()?.ifBlank { null },
                            affinityTracker?.getDirective()?.ifBlank { null },
                        ).joinToString("\n\n").ifBlank { "" }
                    } else "")

                // 2.5) Planning step: ask the model to outline its approach before
                // calling tools. The plan is injected as a system prefix so the
                // model sees its own strategy. One cheap LLM call improves tool
                // selection accuracy across all tasks. Falls back silently on error.
                // Skip for short messages — "hi", "thanks", "what time is it" don't
                // need a planning round-trip. Threshold: < 20 chars or < 4 words.
                //
                // Gated on [planningEnabled] (default off). The call adds up to
                // 15s before the user sees a token and bills a second request on
                // every turn, which is a bad default for conversational use; users
                // doing tool-heavy work turn it on in Settings.
                val effectivePlanning = strategy?.enablePlanning ?: planningEnabled
        val needsPlan = effectivePlanning &&
                    lastUserMessage.length > 20 &&
                    lastUserMessage.split(Regex("\\s+")).filter { it.isNotBlank() }.size > 3
                val plan = if (step == 1 && needsPlan) {
                    generatePlanPrefix(lastUserMessage, effectiveModel)
                } else ""

                val resolvedSys = if (plan.isNotBlank()) plan + sys else sys
                if (resolvedSys.isNotBlank()) add(ProviderMessage(role = Role.system, content = resolvedSys))

                addAll(currentConversation.toMessages(includeSystemPrompt = false, maxToolResultChars = MAX_TOOL_RESULT_CHARS))
            }

            // 3) Stream the model step
            val toolCalls = mutableListOf<Pair<String, String>>()
            val toolCallStarts = mutableMapOf<String, String>()
            val toolCallArgs = mutableMapOf<String, StringBuilder>()
            val accumulatedText = StringBuilder()
            val accumulatedThinking = StringBuilder()
            var finishReason: String? = null
            var stepError: String? = null

            // Provider failover: if the primary model returns a retryable
            // error (5xx, 429, network timeout), try the next configured
            // provider before giving up. Don't failover on 401 (bad key)
            // or 400 (bad request) — retrying won't help.
            //
            // The outer step loop already counted this as a step; the inner
            // failover loop retries the SAME step with a different provider
            // without consuming another maxSteps slot.
            var currentModel = effectiveModel
            val triedModels = mutableSetOf<String>()
            var retriedAfterBackoff = false

            stream@ while (true) {
                triedModels.add(currentModel)
                stepError = null
                accumulatedText.clear()
                accumulatedThinking.clear()
                toolCalls.clear()
                toolCallStarts.clear()
                toolCallArgs.clear()
                finishReason = null

                try {
                    brain.stream(currentModel, messages, tools, effectiveOptions).collect { chunk ->
                        when (chunk) {
                            is BrainChunk.Thinking -> {
                                accumulatedThinking.append(chunk.text)
                                emit(AgentEvent.ThinkingDelta(chunk.text))
                            }
                            is BrainChunk.Text -> {
                                accumulatedText.append(chunk.text)
                                emit(AgentEvent.TextDelta(chunk.text))
                            }
                            is BrainChunk.ToolCallStart -> {
                                toolCallStarts[chunk.id] = chunk.name
                                emit(AgentEvent.ToolCallStart(chunk.id, chunk.name))
                            }
                            is BrainChunk.ToolCallDelta -> {
                                val id = chunk.id.ifEmpty { toolCallStarts.keys.lastOrNull() ?: "" }
                                toolCallArgs.getOrPut(id) { StringBuilder() }.append(chunk.argumentsDelta)
                            }
                            is BrainChunk.ToolCallEnd -> {
                                // Providers that deliver a complete call in one chunk
                                // never emit ToolCallStart — record the name here too,
                                // or the ToolTurn (and its wire replay) loses it.
                                if (chunk.name.isNotEmpty()) toolCallStarts[chunk.id] = chunk.name
                                toolCalls += chunk.id to chunk.arguments
                                emit(AgentEvent.ToolCallEnd(chunk.id, chunk.name, chunk.arguments))
                            }
                            is BrainChunk.Finished -> { finishReason = chunk.reason }
                            is BrainChunk.Error -> {
                                stepError = "${chunk.code}: ${chunk.message}"
                                val retryAfterMs = chunk.error?.retryAfterMs
                                if (chunk.retryable && retryAfterMs != null && !retriedAfterBackoff) {
                                    // The server told us exactly how long to
                                    // wait (429 Retry-After). Honor it (capped)
                                    // and retry the SAME model once before
                                    // falling over — a rate limit is transient
                                    // and the user's chosen model is still the
                                    // best model to answer with.
                                    retriedAfterBackoff = true
                                    kotlinx.coroutines.delay(minOf(retryAfterMs, MAX_RETRY_AFTER_WAIT_MS))
                                    emit(AgentEvent.ResetText)
                                    throw kotlinx.coroutines.CancellationException("failover")
                                }
                                if (chunk.retryable && triedModels.size < 2) {
                                    // Failover: pick a model from a DIFFERENT provider
                                    // (not just a different prefix — we want a different
                                    // provider entirely to avoid trying two models from
                                    // the same provider that might share the same failure
                                    // mode), preferring a configured provider that carries
                                    // a model of the same family as the one that failed.
                                    val nextModel = selectFailoverModel(currentModel, triedModels)
                                    if (nextModel != null) {
                                        val failedModel = currentModel
                                        traceSink?.emit(runId, com.aura.agent.runtime.TraceEventType.PROVIDER_FAILOVER, stepId = "step_$step", redactedPayload = "$failedModel→$nextModel")
                                        emit(
                                            AgentEvent.Warning(
                                                message = "Provider $failedModel failed (${chunk.code}); using $nextModel for this chat.",
                                                fromModel = failedModel,
                                                toModel = nextModel,
                                            ),
                                        )
                                        currentModel = nextModel
                                        effectiveModel = nextModel
                                        currentConversation = currentConversation.copy(model = nextModel)
                                        // Clear the UI's accumulated text from the
                                        // failed model so the user doesn't see
                                        // garbled concatenated output.
                                        emit(AgentEvent.ResetText)
                                        throw kotlinx.coroutines.CancellationException("failover")
                                    }
                                }
                                emit(AgentEvent.Error(chunk.code, chunk.message, chunk.retryable, chunk.error?.toAuraError()))
                            }
                        }
                    }
                    break@stream
                } catch (e: kotlinx.coroutines.CancellationException) {
                    if (e.message == "failover") continue@stream
                    throw e
                }
            }

            if (stepError != null) { finished = true; break }

            // Resolve in-progress tool calls
            for (id in toolCallStarts.keys) {
                if (toolCalls.none { it.first == id }) {
                    val name = toolCallStarts[id] ?: continue
                    val args = toolCallArgs[id]?.toString() ?: ""
                    toolCalls += id to args
                    emit(AgentEvent.ToolCallEnd(id, name, args))
                }
            }

            if (accumulatedText.isNotEmpty()) {
                val thinkingText = accumulatedThinking.toString().ifBlank { null }
                currentConversation = currentConversation.addAssistant(accumulatedText.toString(), thinking = thinkingText)
            }
            for ((id, args) in toolCalls) {
                val name = toolCallStarts[id] ?: ""
                // Tag with the step that produced the call. When this step
                // emitted no text, addAssistant above was skipped and these
                // calls land on the turn a previous step already owns —
                // `step` is what keeps them from being replayed as if the
                // model had asked for everything at once. See ToolTurn.step.
                currentConversation = currentConversation.addToolCall(id, name, args, toolStepOffset + step)
            }

            if (toolCalls.isEmpty() || finishReason == "stop" || finishReason == "length") {
                // Guard: if the stream produced no text, no tool calls, and
                // no error, the model returned an empty response. Emit an
                // error so the user sees something instead of silence.
                if (accumulatedText.isEmpty() && stepError == null && toolCalls.isEmpty()) {
                    emit(AgentEvent.Error("empty_response", "The model returned an empty response. Try again or switch models.", true, null))
                }
                finished = true
                break
            }

            // Execute tool calls in parallel. The model sends them in one
            // batch, meaning it doesn't expect one's output to feed into
            // the next. Running them concurrently cuts wall time for
            // multi-tool turns (e.g. two web searches in 1 turn = half
            // the latency). coroutineScope ensures all complete before
            // we process results and continue the loop.
            val ctx = ToolContext(
                conversationId = currentConversation.id,
                userMessage = lastUserMessage,
                memoryEnabled = memoryEnabled,
                approvedRemoteCostTools = approvedRemoteCostTools,
                confirmedTools = confirmedTools,
            )
            val toolResults = coroutineScope {
                toolCalls.map { (id, args) ->
                    val name = toolCallStarts[id] ?: return@map null
                    emit(AgentEvent.ToolExecuting(id, name, args))
                    async {
                        traceSink?.emit(runId, com.aura.agent.runtime.TraceEventType.TOOL_CALL, stepId = "step_$step", toolName = name)
                        val result = toolExecutor.execute(name, args, ctx)
                        traceSink?.emit(runId, com.aura.agent.runtime.TraceEventType.TOOL_RESULT, stepId = "step_$step", toolName = name, success = result is ToolResult.Ok)
                        Triple(id, name to args, result)
                    }
                }.filterNotNull().awaitAll()
            }
            // Write completed sibling results FIRST, then pause on a gate.
            // The tools ran in one parallel batch: pausing before writing
            // the siblings discarded results whose side effects had
            // already happened (email sent, event created) — the model
            // never saw them and could re-issue them after resume.
            val isGate = { r: ToolResult ->
                r is ToolResult.NeedsPermission ||
                    r is ToolResult.NeedsConfirmation ||
                    r is ToolResult.NeedsApproval
            }
            for ((id, nameAndArgs, result) in toolResults) {
                val (name, args) = nameAndArgs
                if (isGate(result)) continue // held (first) or dropped from the wire (rest)
                val rawResultText = when (result) {
                    is ToolResult.Ok -> {
                        // Record a world event for state-mutating tools.
                        val tool = toolRegistry.get(name)
                        val risk = tool?.risk ?: com.aura.agent.ToolRisk.READ_ONLY
                        if (risk.ordinal >= com.aura.agent.ToolRisk.WRITE_LOCAL.ordinal) {
                            runCatching {
                                worldEventProducer?.recordToolExecution(
                                    toolName = name,
                                    toolRisk = risk,
                                    resultSummary = result.output,
                                    agentScope = if (agentId != null) "agent:$agentId" else "general",
                                )
                            }.onFailure { android.util.Log.w("AgenticLoop", "world event record failed: ${it.message}", it) }
                        }
                        result.output
                    }
                    is ToolResult.Error -> {
                        toolErrors.add(name to result.message)
                        "Error: ${result.message}"
                    }
                    // Unreachable (gates continue above); kept for when-exhaustiveness.
                    is ToolResult.NeedsPermission -> "Permission needed: ${result.permission} — ${result.rationale}"
                    is ToolResult.NeedsApproval -> "Approval needed: ${result.rationale}"
                    is ToolResult.NeedsConfirmation -> "Confirmation needed: ${result.rationale}"
                }
                val resultText = truncateToolResult(rawResultText)
                currentConversation = currentConversation.setToolResult(id, resultText)
                // Mid-loop compaction: the conversation grows by ~4k chars
                // per tool result. Without re-compacting, a 10-step run can
                // blow past the model's input budget and fail on the next
                // call. compactIfNeeded is a no-op when below threshold
                // (cheap char-sum, no network), so it is safe every step.
                currentConversation = conversationCompactor.compactIfNeeded(currentConversation, effectiveModel)
                emit(AgentEvent.ToolResult(id, name, args, resultText))
            }
            // Pause on the first gated result (permission, confirmation, or
            // remote-cost approval) — one typed pause/resume path for all
            // three. The conversation snapshot now includes every sibling
            // result written above. Additional gated results in the same
            // batch stay dangling; toMessages drops them from the wire and
            // the model can re-issue them after resume.
            val gated = toolResults.firstOrNull { (_, _, r) -> isGate(r) }
            if (gated != null) {
                val (id, nameAndArgs, result) = gated
                val (name, args) = nameAndArgs
                val (kind, permission, level, rationale) = when (result) {
                    is ToolResult.NeedsPermission ->
                        GateDescriptor(GateKind.PERMISSION, result.permission, "", result.rationale)
                    is ToolResult.NeedsConfirmation ->
                        GateDescriptor(GateKind.CONFIRMATION, "", result.level, result.rationale)
                    is ToolResult.NeedsApproval ->
                        GateDescriptor(GateKind.APPROVAL, "", "", result.rationale)
                    else -> error("unreachable")
                }
                pendingGates[currentConversation.id] = PendingGate(
                    kind = kind,
                    toolName = name,
                    toolCallId = id,
                    args = args,
                    permission = permission,
                    confirmationLevel = level,
                    rationale = rationale,
                    conversation = currentConversation,
                    model = effectiveModel,
                    maxSteps = maxSteps,
                    options = options,
                    recallLimit = recallLimit,
                    specialist = specialist,
                    memoryEnabled = memoryEnabled,
                    approvedRemoteCostTools = approvedRemoteCostTools,
                    confirmedTools = confirmedTools,
                    agentId = agentId,
                    runId = runId,
                    step = step,
                )
                emit(
                    AgentEvent.GateRequested(
                        toolName = name,
                        toolCallId = id,
                        args = args,
                        kind = kind,
                        permission = permission,
                        level = level,
                        rationale = rationale,
                    ),
                )
                finished = true
            }
        }

        val sourceTurnTimestamp = currentConversation.turns.lastOrNull()?.timestamp ?: 0L
        val provenance = ConversationProvenance(currentConversation.id, sourceTurnTimestamp)

        // Extract one complete labeled turn. Calling the debounced extractor once
        // avoids the previous user-then-assistant overwrite race.
        val completedAssistant = currentConversation.turns.lastOrNull()?.assistant.orEmpty()
        if (memoryEnabled && lastUserMessage.isNotBlank() && completedAssistant.isNotBlank()) {
            kgExtractor.extract(
                "USER:\n$lastUserMessage\n\nASSISTANT:\n$completedAssistant",
                provenance,
            )
        }

        // 4) Auto-store the user's last message via LLM write gate (best-effort, non-blocking)
        //    Skipped when memoryEnabled is false (incognito mode).
        //    The LLM gate wraps the heuristic gate: heuristic runs first
        //    (fast, no network), and if it says "store", the LLM makes the
        //    final decision with better category + importance. If the LLM
        //    call fails, the heuristic decision is used as fallback.
        if (memoryEnabled && lastUserMessage.isNotBlank()) {
            runCatching {
                // Model for the gate call: prefer the user's configured
                // background/cheap model (Settings → "Background tasks") —
                // a yes/no classification never needs the main model.
                // Fallback: the user's default model, unless that is MoA
                // (expensive — 3 API calls for a yes/no), in which case use
                // the first configured non-MoA provider's first model.
                // The heuristic gate is the pre-filter; the LLM gate only
                // runs when the heuristic says "store", so this is one
                // lightweight call per memorable turn — not per message.
                val backgroundModel = runCatching { userPreferences?.backgroundModel?.first() }
                    .getOrNull()
                val gateModel = if (!backgroundModel.isNullOrBlank()) {
                    backgroundModel
                } else if (model.startsWith("moa:")) {
                    modelCatalogRepository
                        ?.catalog
                        ?.value
                        ?.allModels
                        // isChatUsable: the catalog carries image/video/speech
                        // models now, and this picks a model to send a chat
                        // request to.
                        ?.firstOrNull { it.providerPrefix != "moa" && it.capability.isChatUsable }
                        ?.id
                        ?: return@runCatching
                } else {
                    model
                }
                val gate = LlmWriteGate(
                    heuristic = WriteGate(),
                    registry = providerRegistry,
                    modelId = gateModel,
                )
                val decision = gate.evaluate(lastUserMessage, "user")
                if (decision.shouldStore) {
                    val storeScope = if (agentId != null) "agent:$agentId" else "general"
                    // Route through maybeStore so the auto-store path gets
                    // exact-content + semantic dedup. The gate decision's
                    // category/importance are passed through, so maybeStore
                    // skips its own heuristic gate and only dedups.
                    memoryStore.maybeStore(
                        content = lastUserMessage,
                        source = "user",
                        scope = storeScope,
                        provenance = provenance,
                        category = decision.category,
                        importance = decision.importance,
                    )
                }
            }.onFailure { android.util.Log.w("AgenticLoop", "memory auto-store failed: ${it.message}", it) }
        }

        // 5) Extract user profile from the conversation.
        //    First-person patterns ("my name is", "I live in", "I prefer")
        //    are in the USER's text, not the assistant's response. Run
        //    extraction on the user message first, then on the assistant
        //    text as a secondary path (the assistant may echo user facts).
        if (memoryEnabled && lastUserMessage.isNotBlank()) {
            // First pass: fast regex extraction (name, location, job, preferences).
            val regexFound = runCatching { extractProfileFromText(lastUserMessage) }
                .onFailure { android.util.Log.w("AgenticLoop", "profile extraction (user) failed: ${it.message}", it) }
                .isSuccess
            // Second pass: LLM extraction for things regex misses
            // ("I use Vim", "I'm allergic to peanuts", "my wife's name is Sarah").
            // Only fires when regex found nothing AND the LLM extractor is available.
            // Uses a cheap model, 200 tokens, 5s timeout — non-blocking.
            if (!regexFound && llmProfileExtractor != null && lastUserMessage.length > 15) {
                runCatching {
                    val profileModel = resolveCheapModel(effectiveModel)
                    val extraction = llmProfileExtractor.extract(lastUserMessage, profileModel)
                    if (extraction != null) {
                        extraction.name?.let { userProfileStore.update(name = it) }
                        if (extraction.traits.isNotEmpty()) userProfileStore.mergeTraits(extraction.traits)
                        if (extraction.facts.isNotEmpty()) userProfileStore.mergeFacts(extraction.facts)
                    }
                }.onFailure { android.util.Log.w("AgenticLoop", "LLM profile extraction failed: ${it.message}", it) }
            }
        }
        // Only the user's own text is a reliable source for profile facts.
        // The assistant may echo user facts, but it may also hallucinate them
        // (e.g., "I am Claude"), which would be persisted as a user fact.
        // The user-message extraction above covers the real signal.
        //
        // val lastAssistant = currentConversation.turns.lastOrNull()?.assistant
        // if (memoryEnabled && !lastAssistant.isNullOrBlank()) {
        //     runCatching { extractProfileFromText(lastAssistant) }
        //         .onFailure { android.util.Log.w("AgenticLoop", "profile extraction (assistant) failed: ${it.message}", it) }
        // }

        if (!finished) {
            emit(AgentEvent.Error("max_steps_exceeded", "Hit max steps ($maxSteps) without finishing.", retryable = false))
            traceSink?.emit(runId, com.aura.agent.runtime.TraceEventType.RUN_FAILED, errorCode = "max_steps_exceeded")
            // Reflection: generate a short "what went wrong, what to try
            // differently" note and store it on the conversation. The
            // next run() for this conversation will inject it into the
            // system prompt so the model self-corrects instead of
            // repeating the same mistakes.
            if (reflectionEngine != null && toolErrors.isNotEmpty()) {
                runCatching {
                    val reflectionModel = resolveCheapModel(effectiveModel)
                    val reflection = reflectionEngine.reflect(
                        userMessage = lastUserMessage,
                        toolErrors = toolErrors,
                        maxSteps = maxSteps,
                        model = reflectionModel,
                    )
                    if (!reflection.isNullOrBlank()) {
                        currentConversation = currentConversation.copy(
                            metadata = currentConversation.metadata + ("lastReflection" to reflection),
                        )
                    }
                }.onFailure { android.util.Log.w("AgenticLoop", "reflection generation failed: ${it.message}", it) }
            }
        } else {
            traceSink?.emit(runId, com.aura.agent.runtime.TraceEventType.RUN_COMPLETED)
            // Clear stale reflection from a prior failed run — the model
            // succeeded this time, so the reflection is no longer relevant.
            // Without this, a one-time failure's reflection would be injected
            // into every future run of this conversation forever.
            if (currentConversation.metadata.containsKey("lastReflection")) {
                currentConversation = currentConversation.copy(
                    metadata = currentConversation.metadata - "lastReflection",
                )
            }
        }
        // Persist emotion state after each turn so it survives cold starts.
        if (memoryEnabled && emotionEngine != null) {
            runCatching { emotionEngine.save() }
                .onFailure { android.util.Log.w("AgenticLoop", "emotion save failed: ${it.message}", it) }
        }
        // Consciousness: post-turn bookkeeping. The narrative self is NOT
        // updated per turn anymore — the old "Discussed: X → Y" heuristic
        // wrote conversation snippets into every future system prompt; the
        // real narrative update happens during Dream consolidation
        // (DreamConsolidator's narrative phase calls updateFromDream).
        if (memoryEnabled) {
            // Record affinity: increase score per turn.
            runCatching { affinityTracker?.recordTurn() }
                .onFailure { android.util.Log.w("AgenticLoop", "affinity record failed: ${it.message}", it) }
            // Intrinsic motivation: CURIOSITY is satisfied only when a
            // genuinely information-seeking tool ran this turn ("any tool
            // ran" let a calendar read scratch the curiosity itch).
            val turnTools = currentConversation.turns.lastOrNull()?.toolTurns.orEmpty()
            val ranCuriosityTool = turnTools.any { it.name in CURIOSITY_TOOLS }
            if (ranCuriosityTool) {
                runCatching { intrinsicMotivation?.satisfy(com.aura.consciousness.IntrinsicMotivation.DriveType.CURIOSITY) }
                    .onFailure { android.util.Log.w("AgenticLoop", "motivation satisfy failed: ${it.message}", it) }
            }
            // COMPETENCE is satisfied by actually exercising a skill. Until
            // 2026-08-08 nothing called satisfy() for it, so its intensity —
            // driven by DriveSignals.lowConfidenceSkillCount — could only ever
            // rise, and its lastSatisfiedAt never moved. A drive that cannot be
            // satisfied is not a drive; it is a constant that eventually wins
            // mostUrgent() and stays there.
            if (turnTools.any { it.name in COMPETENCE_TOOLS }) {
                runCatching { intrinsicMotivation?.satisfy(com.aura.consciousness.IntrinsicMotivation.DriveType.COMPETENCE) }
                    .onFailure { android.util.Log.w("AgenticLoop", "motivation satisfy failed: ${it.message}", it) }
            }
            // Every completed user turn IS social contact — satisfy SOCIAL
            // honestly instead of letting it climb between daemon cycles.
            runCatching { intrinsicMotivation?.satisfy(com.aura.consciousness.IntrinsicMotivation.DriveType.SOCIAL) }
                .onFailure { android.util.Log.w("AgenticLoop", "motivation satisfy failed: ${it.message}", it) }
            // satisfy() mutates in-memory state; persist once for the whole
            // post-turn block rather than after each call.
            runCatching { intrinsicMotivation?.save() }
                .onFailure { android.util.Log.w("AgenticLoop", "motivation save failed: ${it.message}", it) }
        }

        // Persist the recall summary on the conversation's last
        // turn so the chat UI can render the chip on history
        // replays and so the loop stays the single source of
        // truth for what was recalled.
        val modeledConversation = currentConversation.copy(model = effectiveModel)
        val finalConv = if (lastRecall != null) {
            modeledConversation.attachRecallToLastTurn(lastRecall)
        } else modeledConversation
        runCatching {
            tasteEngine?.recordRoutingOutcome(
                modelRole = agentId?.let { "agent:$it" } ?: "general",
                modelId = effectiveModel,
                success = true,
                latencyMs = 0L,
                costClass = "chat",
                outcomeType = "loop_completed",
                agentScope = agentId?.let { "agent:$it" } ?: "general",
            )
        }.onFailure { android.util.Log.w("AgenticLoop", "routing outcome failed: ${it.message}", it) }
        emit(AgentEvent.Result(finalConv, lastRecall))
        // Cache the reply for repeat questions: only pure-text answers in
        // fresh conversations (no tool calls — a tool answer depends on
        // external state and would go stale; conversation ≤ 3 turns so the
        // answer is self-contained).
        if (responseCache != null && cacheKey != null && memoryEnabled) {
            val lastAssistant = finalConv.turns.lastOrNull()?.assistant.orEmpty()
            val usedTools = finalConv.turns.lastOrNull()?.toolTurns?.isNotEmpty() == true
            val originalTurns = conversation.turns.size
            if (lastAssistant.length >= 40 && !usedTools && originalTurns <= 2) {
                runCatching { responseCache.put(cacheKey, lastAssistant) }
                    .onFailure { android.util.Log.w("AgenticLoop", "response cache put failed: ${it.message}", it) }
            }
        }
        emit(AgentEvent.Done)
    }

    /** Lightweight regex-based profile extraction. Updates name, traits, and facts. */
private suspend fun extractProfileFromText(text: String) {
        val facts = mutableListOf<String>()
        Regex("""(?:my name is|i'm|i am|call me)\s+([A-Z][a-z]+(?:\s+[A-Z][a-z]+)?)""", RegexOption.IGNORE_CASE)
            .find(text)?.groupValues?.getOrNull(1)?.let { name ->
                userProfileStore.update(name = name.trim())
            }
        Regex("""(?:i (?:live|am|work) (?:in|at|from))\s+([A-Z][a-zA-Z\s,]+?)(?:\.|,|\s+(?:and|but|so|because)|\s*$)""", RegexOption.IGNORE_CASE)
            .find(text)?.groupValues?.getOrNull(1)?.let { loc -> facts.add("Lives in ${loc.trim()}") }
        Regex("""(?:i(?:'m| am) an?\s+|i work as (?:an?\s+)?)([a-z]+(?:\s+[a-z]+){0,3})""", RegexOption.IGNORE_CASE)
            .find(text)?.groupValues?.getOrNull(1)?.let { job -> facts.add("Works as ${job.trim()}") }
        Regex("""(?:i (?:prefer|like|love))\s+(.+?)(?:\.|,|\s+(?:and|but|so|because)|\s*$)""", RegexOption.IGNORE_CASE)
            .find(text)?.groupValues?.getOrNull(1)?.let { pref -> facts.add("Prefers ${pref.trim()}") }
        if (facts.isNotEmpty()) userProfileStore.mergeFacts(facts)
    }

    /**
     * Resolve a cheap model for auxiliary tasks (planning, compaction,
     * write gate). If the user's model is MoA (3-model virtual provider),
     * fall back to the first configured non-MoA provider's first model.
     * This prevents a 150-token planning step from costing 3 API calls.
     */
    /**
     * A cheap model for auxiliary work — the plan prefix, reflection, profile
     * extraction. The user's main model may be an expensive flagship, and
     * spending it on a 150-token plan wastes money and adds latency.
     *
     * Delegates to the shared [com.aura.providers.CheapModelResolver] so the
     * user's explicit Fast-model setting is honoured and every caller ranks the
     * same way; [userModel] stays the fallback so the work still happens.
     */
    private suspend fun resolveCheapModel(userModel: kotlin.String): kotlin.String =
        cheapModelResolver?.resolve(fallback = userModel, exclude = userModel) ?: userModel

    /**
     * Pick the failover target after [failedModel] returned a retryable
     * error. Only models whose provider is actually CONFIGURED are
     * eligible — the catalog can carry hydrated cache entries for a
     * provider whose key has since been removed, and failing over to one
     * of those guarantees a second failure. Among eligible models on
     * untried providers, prefer one sharing the failed model's family
     * (e.g. "groq:llama-3.3-70b" → "openrouter:llama-3.1-8b") so the
     * conversation continues on a similar model; otherwise take the first
     * eligible model in catalog order.
     */
    internal fun selectFailoverModel(
        failedModel: String,
        triedModels: Set<String>,
    ): String? {
        val catalog = modelCatalogRepository?.catalog?.value ?: return null
        val configuredPrefixes = runCatching {
            providerRegistry.configured().map { it.prefix }.toSet()
        }.getOrDefault(emptySet())
        val triedPrefixes = triedModels.mapTo(mutableSetOf()) { it.substringBefore(":") }
        val eligible = catalog.allModels.filter {
            it.providerPrefix !in triedPrefixes &&
                it.id !in triedModels &&
                it.providerPrefix in configuredPrefixes &&
                // Failing over to an image model would turn a transient
                // provider error into a permanent 400.
                it.capability.isChatUsable
        }
        if (eligible.isEmpty()) return null
        val family = modelFamily(failedModel)
        val sameFamily = family?.let { f -> eligible.firstOrNull { modelFamily(it.id) == f } }
        return (sameFamily ?: eligible.first()).id
    }

    /**
     * The "family" of a model id — the first alphabetic token of the
     * model name after the provider prefix ("groq:llama-3.3-70b" →
     * "llama", "openai:gpt-4o" → "gpt"). Used to keep failover on a
     * similar model when one is available from another provider.
     */
    private fun modelFamily(modelId: kotlin.String): kotlin.String? {
        val name = modelId.substringAfter(":", modelId).lowercase()
        return Regex("[a-z]{2,}").find(name)?.value
    }

    /**
     * Hide the standalone Tavily and Brave search tools from the model.
     *
     * Since the M5 consolidation `web_search` dispatches to Tavily or Brave
     * internally when a key is configured, falling back to DuckDuckGo when it
     * isn't. Offering the backends separately alongside it only creates
     * selection ambiguity — three tools that do the same job, two of which
     * fail without a key. So the model sees exactly one search tool, always.
     *
     * Both stay registered in the [ToolRegistry]: direct dispatch, the Tools
     * screen, hands, and tests still reach them by name. This filter only
     * shapes what goes into the `tools` array on the wire.
     *
     * The KDoc here used to describe key-conditional hiding ("hidden unless a
     * Tavily key is configured"), which the body never did, and an early
     * `if (providerKeys == null) return tools` made the mismatch worse by
     * showing both tools in exactly the case — no keys available — where they
     * were guaranteed to fail. The guard and the `suspend` modifier were both
     * doing nothing and are gone; the `providerKeys` injection went with them,
     * since that guard was its only remaining reader.
     */
    private fun filterSearchTools(tools: List<ToolDefinition>): List<ToolDefinition> =
        tools.filter { def -> def.name != "tavily_search" && def.name != "brave_search" }

    /**
     * Generate the system-prompt plan prefix for a turn. Uses the cheap
     * model tier (a 150-token outline, not a generation task — if the
     * user selected MoA, the planning step would fire 3 API calls for a
     * 2-sentence plan). For long questions, MCTS-lite expands 2-3
     * distinct approach branches, scores them, and commits to the best
     * instead of a single linear plan. Falls back to the linear plan on
     * any error. Returns "" when nothing useful was produced.
     *
     * Three jobs here, and until now one model did all of them:
     *  - the **linear plan** is a 150-token outline — cheap work, so the Planner
     *    role, falling back to the cheap model;
     *  - the tree's **expansion** is where reasoning quality changes the answer,
     *    since it invents the approaches — the Reasoning role;
     *  - the tree's **scoring** is a 20-token "reply with a number between 0 and
     *    1", fanned out once per branch. Running that on a reasoning model is
     *    pure waste, so it stays on the cheap model.
     */
    private suspend fun generatePlanPrefix(lastUserMessage: kotlin.String, effectiveModel: kotlin.String): kotlin.String =
        runCatching {
            val cheapModel = resolveCheapModel(effectiveModel)
            val planModel = modelRoleRouter?.explicit(com.aura.providers.ModelRole.PLANNER) ?: cheapModel
            val expandModel = modelRoleRouter?.explicit(com.aura.providers.ModelRole.REASONING)
                ?: modelRoleRouter?.explicit(com.aura.providers.ModelRole.PLANNER)
                ?: cheapModel
            val treePlan = if (lastUserMessage.length >= com.aura.agent.ReasoningTree.MIN_MESSAGE_LENGTH) {
                kotlinx.coroutines.withTimeoutOrNull(20_000L) {
                    reasoningTree?.bestApproach(lastUserMessage, expandModel, scoreModel = cheapModel)
                }
            } else null
            if (treePlan != null) {
                "## Approach: $treePlan\n\n"
            } else {
                val planMessages = listOf(
                    ProviderMessage(
                        role = ProviderMessage.Role.system,
                        content = "You are a planning assistant. Given the user's request, outline your approach in 1-2 sentences. What information do you need? What tools will you use? Be specific.",
                    ),
                    ProviderMessage(role = ProviderMessage.Role.user, content = lastUserMessage),
                )
                val planBuilder = StringBuilder()
                // Timeout: planning is auxiliary — if the cheap model
                // hangs, don't block the user's real answer.
                kotlinx.coroutines.withTimeoutOrNull(15_000L) {
                    brain.stream(
                        planModel, planMessages,
                        options = ChatOptions(temperature = 0.0, maxTokens = 150),
                    ).collect { chunk ->
                        if (chunk is BrainChunk.Text) planBuilder.append(chunk.text)
                    }
                }
                val raw = planBuilder.toString().trim()
                if (raw.isNotBlank()) "## Plan: $raw\n\n" else ""
            }
        }.onFailure { android.util.Log.w("AgenticLoop", "planning step failed: ${it.message}", it) }.getOrDefault("")

    /**
     * Find the first enabled hand whose trigger phrase is contained in the
     * user's message. Substring match; case-insensitive; blank phrases are
     * ignored. If multiple hands match, the newest one wins (getEnabled
     * orders by createdAt DESC).
     */
    private suspend fun findMatchingHand(userMessage: String): com.aura.hands.Hand? {
        if (userMessage.isBlank()) return null
        val lower = userMessage.lowercase()
        return handRepository.getEnabled().firstOrNull { hand ->
            if (hand.triggerPhrase.isBlank()) return@firstOrNull false
            // Support multi-phrase triggers: "git status|git log" matches
            // either phrase. Each phrase is matched with word boundaries
            // to prevent false positives like "git" matching "widget".
            val phrases = hand.triggerPhrase.split("|").map { it.trim() }.filter { it.isNotBlank() }
            phrases.any { phrase ->
                val escaped = Regex.escape(phrase.lowercase())
                // Use UNICODE_CHARACTER_CLASS so \b respects non-ASCII
                // word characters (Azerbaijani ə, ü, ş, etc.). Without
                // this, \b is ASCII-only and trigger phrases with
                // non-Latin characters at the boundary never match.
                Regex("\\b$escaped\\b", RegexOption.IGNORE_CASE).containsMatchIn(lower)
            }
        }
    }
}

sealed class AgentEvent {
    data class TextDelta(val text: String) : AgentEvent()
    /**
     * Emitted before a provider failover re-streams from scratch.
     * The UI must clear any accumulated text/thinking from the failed
     * model so the user doesn't see garbled concatenated output.
     */
    data object ResetText : AgentEvent()
    data class ThinkingDelta(val text: String) : AgentEvent()
    data class ToolCallStart(val id: String, val name: String) : AgentEvent()
    data class ToolCallEnd(val id: String, val name: String, val arguments: String) : AgentEvent()
    data class ToolExecuting(val id: String, val name: String, val arguments: String = "") : AgentEvent()
    data class ToolResult(val id: String, val name: String, val arguments: String, val result: String, val needsPermission: String? = null, val permissionRationale: String? = null) : AgentEvent()
    /**
     * Emitted when the loop paused on a gate — a runtime permission, a
     * policy confirmation, or a remote-cost approval. The UI shows the
     * dialog matching [kind] and on Allow calls
     * [MemoryAugmentedAgenticLoop.resumeAfterGate] (Deny →
     * [MemoryAugmentedAgenticLoop.denyPendingGate]). `toolName` + `args`
     * let the UI render the call; `toolCallId` correlates the eventual
     * `ToolResult` with the held request.
     */
    data class GateRequested(
        val toolName: String,
        val toolCallId: String,
        val args: String,
        val kind: MemoryAugmentedAgenticLoop.GateKind,
        /** Android permission string (PERMISSION kind only). */
        val permission: String = "",
        /** ConfirmationLevel name (CONFIRMATION kind only). */
        val level: String = "",
        val rationale: String,
    ) : AgentEvent()
    data class Error(val code: String, val message: String, val retryable: Boolean, val typedError: com.aura.core.error.AuraError? = null) : AgentEvent()
    data class Warning(
        val message: String,
        val fromModel: String? = null,
        val toModel: String? = null,
    ) : AgentEvent()
    data class Result(
        val conversation: com.aura.agent.Conversation,
        /**
         * The recall summary for the turn that just completed.
         * Null when the loop skipped recall (incognito mode).
         * Stored on the conversation's last turn by the UI layer
         * so the chip renders on history replays.
         */
        val recall: com.aura.agent.RecallSummary? = null,
    ) : AgentEvent()
    data object Done : AgentEvent()
}