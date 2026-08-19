package com.aura.agent

import com.aura.providers.ToolDefinition
import com.aura.providers.ToolParameters
import com.aura.providers.ToolProperty
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Risk level a tool carries. Drives permission gating and confirmation UX.
 * - READ_ONLY: no state mutation and no paid remote execution.
 * - REMOTE_COST: invokes a metered remote API but does not mutate state.
 * - WRITE_LOCAL: changes local state. Examples: reminders, app settings.
 * - WRITE_REMOTE: mutates state outside the device.
 * - PRIVACY: reads or transmits personal data.
 * - DESTRUCTIVE: irreversible.
 */
enum class ToolRisk {
    READ_ONLY, REMOTE_COST, WRITE_LOCAL, WRITE_REMOTE, PRIVACY, DESTRUCTIVE;

    /**
     * Whether this risk describes changing something, rather than seeing something.
     *
     * Named rather than compared. Two call sites used to ask
     * `risk.ordinal >= WRITE_LOCAL.ordinal`, which reads as "at least a local write" and is
     * not what the enum says: PRIVACY sits at 4, above WRITE_LOCAL's 2, so every notification,
     * contact and screen read a privacy tool returned was summarised into `world_events` —
     * plaintext SQLite, no purge, carried in backups, and readable back by `query_world_model`,
     * which is READ_ONLY and asks no confirmation. `NotificationCaptureStore` and
     * `docs/architecture/privacy-boundaries.md` both promise that data is never persisted.
     *
     * A privacy tool reads something sensitive; it changes nothing. Ordering an enum by
     * severity and then treating that order as a category is the mistake, so the category is
     * spelled out here and the order is free to mean whatever it means.
     */
    val mutatesState: Boolean
        get() = this == WRITE_LOCAL || this == WRITE_REMOTE || this == DESTRUCTIVE
}

data class Tool(
    val name: String,
    val description: String,
    val risk: ToolRisk,
    val requiredPermissions: List<String> = emptyList(),
    val parameters: ToolParameters = ToolParameters(),
    val execute: suspend (ToolCall, ToolContext) -> ToolResult,
    /**
     * Top-level group the tool belongs to, used by the Tools
     * browser screen. Categories are stable strings — see
     * [com.aura.tools.ToolCategories]. Empty string = "other".
     */
    val category: String = "",
    /**
     * How long this tool needs before [ToolExecutor] cancels it, when the
     * caller states no budget of its own ([ToolContext.timeout]).
     *
     * Declared here rather than left to the caller because the caller does not
     * know: `MemoryAugmentedAgenticLoop` builds one [ToolContext] for every
     * tool in a step, so a single number there is either too tight for
     * `deep_research` or too loose for everything else. It was too tight —
     * `deep_research` budgets 120s internally and was being killed at 30s,
     * mid-pipeline, after paying for the searches, on every single call.
     *
     * `ProductionPipelineEngine` found and fixed this same defect on the
     * agent-run path, where its KDoc records that "the generative stages of
     * these pipelines have never completed". The chat loop never got the fix.
     * [ToolTimeoutConsistencyTest] now pins the invariant that was violated:
     * no tool's own internal budget may exceed the budget it declares here.
     *
     * Appended last, after [category], so the 76 existing `Tool(` sites
     * compile untouched.
     */
    val timeoutMs: Long = DEFAULT_TOOL_TIMEOUT_MS,
)

/**
 * Budget a tool gets when neither it nor its caller asks for more.
 *
 * 30s is the historical value that [ToolContext.timeout] defaulted to, kept so
 * that making timeouts explicit changed no tool's behaviour except the ones
 * that were provably being truncated.
 */
const val DEFAULT_TOOL_TIMEOUT_MS: Long = 30_000L

/**
 * Margin between a tool's own internal budget and the executor budget it declares.
 *
 * The two timeouts must not be equal. `DelegateToAgentTool` and `KnowledgeGraphTool`
 * both budget exactly 30s internally against the old 30s executor default, and the
 * executor's clock always started first — so the tool's own timeout, and the useful
 * message it would have returned, were unreachable. The inner one has to win.
 */
const val TIMEOUT_HEADROOM_MS: Long = 15_000L

data class ToolCall(val id: String, val name: String, val arguments: Map<String, Any?>)

sealed class ToolResult {
    data class Ok(val output: String) : ToolResult()
    data class Error(
        val message: String,
        val code: String = "tool_error",
        val typedError: com.aura.core.error.AuraError? = null,
    ) : ToolResult()
    data class NeedsPermission(val permission: String, val rationale: String) : ToolResult()
    data class NeedsApproval(val rationale: String) : ToolResult()

    /**
     * The tool's policy requires user confirmation before running
     * (ConfirmationLevel IMPLICIT / EXPLICIT / BIOMETRIC). Typed —
     * this used to be encoded as a `"$level:confirm:$name"` magic
     * string inside [NeedsApproval], which the UI mis-parsed into
     * the wrong dialog and could never satisfy.
     */
    data class NeedsConfirmation(
        val level: String,
        val toolName: String,
        val rationale: String,
    ) : ToolResult()
}

data class ToolContext(
    val conversationId: String,
    val userId: String = "default",
    /** Latest user-authored message for tools that require explicit consent. */
    val userMessage: String = "",
    val permissions: Set<String> = emptySet(),
    /**
     * Caller-imposed budget for every tool in this context, or null to let each
     * tool use its own [Tool.timeoutMs].
     *
     * Nullable rather than defaulted, because "the caller did not say" and "the
     * caller said 30s" are different facts and the old `= 30_000L` made them
     * indistinguishable. Every construction site that omitted this was silently
     * asserting a 30s ceiling it had never considered, which is how
     * `deep_research` came to be unable to finish.
     *
     * Callers that genuinely own the budget still set it — `RunHandWorker`
     * (120s), `AgentRunExecutorWorker` (per-run snapshot), and the timeout tests
     * — and an explicit value always wins over the tool's own.
     */
    val timeout: Long? = null,
    /**
     * Tool names explicitly approved by a first-party UI action. This is
     * narrower than a boolean: approval for one metered tool cannot authorize
     * another tool in the same resumed automation.
     */
    val approvedRemoteCostTools: Set<String> = emptySet(),
    /**
     * Tool names whose policy confirmation the user granted this
     * conversation. Mirror of [approvedRemoteCostTools] for the
     * ConfirmationLevel gate — without it, PolicyEngine returned
     * NeedsConfirmation forever and confirmation-gated tools could
     * never run.
     */
    val confirmedTools: Set<String> = emptySet(),
    /**
     * Session-level write flag. When false, the tool executor refuses to
     * run tools whose risk >= WRITE_LOCAL — this is the privacy boundary
     * used by the incognito toggle. READ_ONLY tools (e.g. recall, web_search)
     * still run so the user can keep using the assistant without writing
     * anything to local state.
     */
    val memoryEnabled: Boolean = true,
    /** Agent selected by the user in the parent conversation. */
    val activeAgentId: String = "",
)

/**
 * Holds all tools. The agentic loop looks them up by name and dispatches.
 * Mirrors aura/core/tool_executor.py + aura/toolsets.py.
 */
@Singleton
class ToolRegistry @Inject constructor() {
    private val tools: MutableMap<String, Tool> = ConcurrentHashMap()

    /**
     * Bumped on every mutation so [definitions] can tell a stale cache from a
     * current one without locking readers out.
     */
    private val version = AtomicInteger(0)

    @Volatile
    private var cachedDefinitions: Pair<Int, List<ToolDefinition>>? = null

    fun register(tool: Tool) { tools[tool.name] = tool; version.incrementAndGet() }
    fun unregister(name: String) { tools.remove(name); version.incrementAndGet() }
    fun get(name: String): Tool? = tools[name]
    fun all(): List<Tool> = tools.values.toList()
    fun names(): List<String> = tools.keys.toList()
    fun byRisk(min: ToolRisk): List<Tool> = tools.values.filter { it.risk.ordinal >= min.ordinal }

    /**
     * The tool schemas that go on the wire, **sorted by name**.
     *
     * The ordering is the point, not the caching. [tools] is a
     * `ConcurrentHashMap`, whose `values` iteration order is undefined and
     * shifts as the table resizes — and MCP tools register after startup
     * (`McpToolBridge.syncTools`), so the table does resize mid-process. Every
     * provider puts the tool array at the head of the request, and providers
     * that cache prompt prefixes hash those bytes: a reordered array is a
     * different prefix, so an unstable order silently costs the cache with no
     * error anywhere. Sorting makes the array a pure function of the tool set.
     *
     * The cache is incidental — 78 small allocations once or twice per run is
     * nothing — but it is free once the version counter exists, and it keeps
     * the sort off the hot path.
     */
    fun definitions(): List<ToolDefinition> {
        val current = version.get()
        cachedDefinitions?.let { (cachedVersion, defs) ->
            if (cachedVersion == current) return defs
        }
        val defs = tools.values
            .sortedBy { it.name }
            .map { t ->
                ToolDefinition(
                    name = t.name,
                    description = t.description,
                    parameters = t.parameters,
                    category = t.category,
                )
            }
        // Stamped with the version read BEFORE the snapshot, so a registration
        // racing this build invalidates the cache rather than being swallowed.
        cachedDefinitions = current to defs
        return defs
    }
}
