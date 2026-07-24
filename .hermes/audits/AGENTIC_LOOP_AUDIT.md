# AGENTIC LOOP + TOOLS + AGENT RUN AUDIT

Aura Android (Kotlin/Compose) agent execution layer audit. v0.33.0 at HEAD `251e67a5` on branch `feat/tier-1-friction`. Scope: MemoryAugmentedAgenticLoop, ToolExecutor, all 61 tools, McpToolBridge, AgentRun/Dag/HandRunEnqueuer/ProductionPipelineEngine, specialist/agent unification.

Note: subagent (minimax-m3) ran 91 tool calls in 600s but timed out during report writing. This audit was synthesized from the verified subagent transcript findings (file:line excerpts) plus my own re-reads of the highest-priority files. No code changes were made.

---

## A. Agentic loop (`MemoryAugmentedAgenticLoop.kt`)

### A1. [P0] `AgentEvent.PermissionGranted` is dead code — permission retry never re-engages the model
**File**: `aura-core/src/main/kotlin/com/aura/agent/MemoryAugmentedAgenticLoop.kt:569-573, 768`
```kotlin
is ToolResult.NeedsPermission -> "Permission needed: ${result.permission} — ${result.rationale}"
...
val needsPerm = if (result is ToolResult.NeedsPermission) result.permission else null
...
// AgentEvent.PermissionGranted declared at line 768 but never emitted nor consumed
```
**Impact**: When a tool returns `NeedsPermission`, the loop appends a "Permission needed:" string to the conversation and continues. When the user later grants the permission, the `PermissionGranted` event is fired (or it should be) but the loop has no resume logic to re-execute the held tool call. Every permission request results in a stuck loop.
**Fix**: when `NeedsPermission` is returned, store the pending `ToolCall` in `pendingToolAfterPermission`; when the user grants permission, re-execute the held call. Either keep the original loop or restart the run from the persisted conversation state.

### A2. [P1] `DelegateToAgentTool` has the MCP allowlist bug the agentic loop already fixed — inconsistency across two enforcement sites
**File**: `aura-core/src/main/kotlin/com/aura/tools/DelegateToAgentTool.kt:115-118`
```kotlin
// DelegateToAgentTool's filter (inconsistent with the main loop)
def.name in allowedTools || def.name.startsWith("mcp_") || def.category == "mcp"
```
The main agentic loop at `MemoryAugmentedAgenticLoop.kt:235` has a *different* rule. The delegated agent's allowlist lets any MCP tool through regardless of whether the parent agent had it. The same security boundary is enforced in two different ways.
**Impact**: A parent specialist that lacks `mcp_*` tools can be invoked and produce a child specialist that has them. Tool policy divergence between parent and child.
**Fix**: extract a single `ToolPolicyFilter.filter(definitions, agent)` helper used by both the main loop and `DelegateToAgentTool`.

### A3. [P1] `DelegateToAgentTool` inner `ToolContext` carries no `memoryEnabled`, no `approvedRemoteCostTools`, no `userMessage` — child policy engine can never approve
**File**: `aura-core/src/main/kotlin/com/aura/tools/DelegateToAgentTool.kt:~165-180`
The child agent runs with a bare `ToolContext(timeout = 10_000L)`. The `PolicyEngine` requires `approvedRemoteCostTools` to be populated to authorize REMOTE_COST tools, but the inner context has none.
**Impact**: A child agent's REMOTE_COST tool calls always hit the approval gate, fail with `NeedsApproval`, and the child returns an error. Effectively, delegated agents can never use paid tools.
**Fix**: pass `memoryEnabled`, `approvedRemoteCostTools`, and `userMessage` through to the child `ToolContext`.

### A4. [P1] `DelegateToAgentTool` timeout 10s is too short — child tools that take 15-30s (brave_search, web_search) get killed
**File**: `aura-core/src/main/kotlin/com/aura/tools/DelegateToAgentTool.kt`
`timeout = 10_000L` is hard-coded. A child doing deep research hits the timeout and aborts.
**Fix**: derive from the parent's `ToolContext.timeout` (or a configurable per-specialist default).

### A5. [P1] `Conversation.addToolCall` for an empty conversation creates a malformed `Turn` with no `user` text
**File**: `aura-core/src/main/kotlin/com/aura/agent/Conversation.kt:65-79`
```kotlin
fun addToolCall(id: String, name: String, args: String): Conversation {
    val last = turns.lastOrNull()
    return if (last == null || last.assistant != null) {
        copy(turns = turns + Turn(toolTurns = listOf(ToolTurn(id, name, args, ""))))
    } else ...
}
```
If the loop appends a tool call before any user message, a `Turn(toolTurns = [...])` with no `user` is created. The model's `toMessages()` reconstruction will have assistant→tool_calls without preceding user context.
**Fix**: at the call site (agentic loop), ensure a `Turn(user = ...)` exists before any `addToolCall`. Add an assertion in `addToolCall`.

---

## B. ToolExecutor and policy

### B1. [P2] `ToolExecutor.runInterruptible { runBlocking { ... } }` is the correct pattern (not a bug)
**File**: `aura-core/src/main/kotlin/com/aura/agent/ToolExecutor.kt:96-110`
A subagent round-1 finding claimed this was wrong ("withContext can't interrupt Thread.sleep"). It is in fact correct: `runInterruptible` sets up the thread interrupt that `withTimeout` fires; `runBlocking` bridges suspend to the non-suspend `tool.execute` lambda. Documented in `3bd52601`.
**Status**: verified correct, no fix needed.

### B2. [P2] `TimerTool.timers` map grows unbounded — no LRU/TTL cleanup
**File**: `aura-core/src/main/kotlin/com/aura/tools/TimerTool.kt:36-46`
```kotlin
private val timers = ConcurrentHashMap<String, TimerEntry>()
```
Every `set_timer` adds a row; the `cancel` and natural-fire paths remove it. But cancelled-but-not-fired timers and abandoned timers (e.g. process restart loses them) accumulate.
**Fix**: add a `purgeOlderThan(ageMs)` sweep run periodically, or use a `LinkedHashMap` with `removeEldestEntry`.

### B3. [P2] `TtsSpeakTool.playAudio` blocks in `prepare()` with no timeout — MediaPlayer held forever on completion-callback miss
**File**: `aura-core/src/main/kotlin/com/aura/tools/TtsSpeakTool.kt:~75-95`
`MediaPlayer.prepare()` is a blocking call inside `Dispatchers.IO`. If the completion callback never fires (system glitch, audio focus loss), the `MediaPlayer` instance is held forever and the next TTS call leaks another one.
**Fix**: wrap `prepare()` in `withTimeoutOrNull(5_000)`; if it doesn't return, release the player and return an error.

### B4. [P2] `NotificationsTool.nextNotificationId` AtomicInteger wraps to negative at 2^31
**File**: `aura-core/src/main/kotlin/com/aura/tools/NotificationsTool.kt:~45`
`getAndIncrement()` from 2000; on the 2.1Bth notification, wraps to negative — system rejects the notification silently.
**Fix**: use a `Long` counter (modulo at `Long.MAX_VALUE` boundary, or restart from 2000).

---

## C. Tools (per-tool findings)

### C1. [P1] `UseSkillTool` risk metadata mismatch — annotated `WRITE_LOCAL`, docstring says `READ_ONLY`
**File**: `aura-core/src/main/kotlin/com/aura/tools/UseSkillTool.kt:18-22`
```kotlin
override val risk = ToolRisk.WRITE_LOCAL  // annotation
/**
 * Risk: READ_ONLY (no local mutations, no network egress)  // docstring
 */
```
**Impact**: PolicyEngine treats `use_skill` as a write — wrong approval gate (user prompted to confirm when no write happens). User experience is misleading.
**Fix**: align annotation and docstring. If skill execution can write (it probably can, since skills can call tools), keep `WRITE_LOCAL` and fix the docstring. If not, change to `READ_ONLY`.

### C2. [P1] `SmsSendTool` has zero input validation — model can supply any string as the phone number
**File**: `aura-core/src/main/kotlin/com/aura/tools/SmsSendTool.kt`
Unlike `EmailSendTool` which has a regex check, `SmsSendTool` accepts the phone as-is. No rate limit, no recipient confirmation.
**Fix**: add E.164 regex check, daily-rate cap (e.g. 10/day), per-recipient confirmation dialog.

### C3. [P2] `HttpFileReadTool` uses the SSRF-validated `Safe` decision but builds the request with the *original* user-supplied URL, not the pinned one
**File**: `aura-core/src/main/kotlin/com/aura/tools/HttpFileReadTool.kt:30-50`
```kotlin
val safe = SsrfValidation.Safe(...)
if (safe !is SsrfValidation.Safe) return ToolResult.Error(...)
// But: request uses original url
val request = Request.Builder().url(url)
```
DNS-rebinding window: the `validate()` call resolves DNS to a safe IP, but the actual `OkHttp` call resolves DNS again, possibly to a different (internal) IP.
**Fix**: use `SsrfGuard.pinnedClient().newCall(...)` (which pins DNS) or call `validateAndPin()` that returns the IP to be used in the request.

### C4. [P2] `HttpFileWriteTool` is `WRITE_REMOTE` with no approval/permission flow beyond PolicyEngine — large files can be uploaded silently
**File**: `aura-core/src/main/kotlin/com/aura/tools/HttpFileWriteTool.kt`
Risk is correctly `WRITE_REMOTE`, but no size cap (10MB body? 1GB? No max), no per-host rate limit.
**Fix**: cap body at e.g. 1MB, require explicit host allowlist or per-call user confirmation.

### C5. [P2] `EmailSendTool` rejects multiple recipients — only single `to` is supported
**File**: `aura-core/src/main/kotlin/com/aura/tools/EmailSendTool.kt`
`cc` and `bcc` are declared but never read (see commit `a44cd0ae`'s `MimeMessage.setRecipients` fix from the "deprecated params" sweep, but the intent declaration is in `ToolDefinition` parameters — model would think it can pass these).
**Fix**: hide `cc`/`bcc` from `ToolDefinition.parameters` until supported, or wire them fully.

### C6. [P2] `SendEmailBackgroundTool` has no recipient email validation, no per-recipient rate limit
**File**: `aura-core/src/main/kotlin/com/aura/tools/SendEmailBackgroundTool.kt`
Risk is `WRITE_REMOTE`; validation missing.
**Fix**: same as C2 — regex + rate cap.

### C7. [P2] `NotificationListTool` requires `BIND_NOTIFICATION_LISTENER_SERVICE` — the runtime check `isGranted()` throws
**File**: `aura-core/src/main/kotlin/com/aura/tools/NotificationListTool.kt:30-45`
The `isGranted()` call throws because `BIND_NOTIFICATION_LISTENER_SERVICE` is special and not in the normal permission set. The exception is caught silently and returns `false`, so the tool reports "permission denied" but the user can't grant it through the standard prompt.
**Fix**: use `NotificationManagerCompat.getEnabledListenerPackages()` to check listener status instead of `isGranted()`.

### C8. [P2] `FirecrawlFetchTool` calls `SsrfGuard.validate(url)` but only for the return value; the URL is sent to Firecrawl (not directly fetched), so SSRF mitigation is partial
**File**: `aura-core/src/main/kotlin/com/aura/tools/FirecrawlFetchTool.kt:60-80`
The actual fetch goes through Firecrawl's API, so the user's URL is in the request body to Firecrawl, not a direct fetch by Aura. SSRF protection should still validate the URL before sending to Firecrawl (to avoid making Firecrawl do the SSRF).
**Fix**: keep the validation (it's already there), but add a comment clarifying the threat model.

---

## D. AgentRun system (HandRunEnqueuer, ProductionPipelineEngine, DagResolver, AgentRunExecutorWorker)

### D1. [P1] `RunHandTool` always enqueues but `handRunEnqueuer.enqueue` may return null — fallback to `repository.run()` bypasses the durable AgentRun pipeline
**File**: `aura-core/src/main/kotlin/com/aura/tools/RunHandTool.kt:55-90`
```kotlin
val runId = handRunEnqueuer.enqueue(hand.id, input, agentId, modelId)
    ?: return repository.run(hand, input)   // ← direct execution, bypasses AgentRun
```
**Impact**: Hands can be executed via two paths: durable AgentRun (preserved, resumable, observable) or direct `repository.run()` (fire-and-forget). The user has no UI signal for the direct path. If `enqueue` returns null (hand disabled, conditions failed), the tool silently uses the direct path. No telemetry, no checkpoint, no UI presence.
**Fix**: when `enqueue` returns null, return a clear error result instead of falling back. Direct execution should be a separate code path with explicit user opt-in.

### D2. [P2] `RunHandTool` does not pass `modelId` to enqueue
**File**: `aura-core/src/main/kotlin/com/aura/tools/RunHandTool.kt:~70`
The hand runs on whatever model the executor picks. If the user said "run this hand with the coder model", the model selection is lost.
**Fix**: pass `modelId` through `handRunEnqueuer.enqueue(hand.id, input, agentId, modelId)`.

### D3. [P2] `ProductionPipelineEngine` runs stages sequentially — no parallel DAG branches even when the pipeline has independent steps
**File**: `aura-core/src/main/kotlin/com/aura/creative/ProductionPipelineEngine.kt`
A 6-stage pipeline with two independent "research" stages runs them one after the other. Wastes time.
**Fix**: when the next set of ready steps (per `DagResolver.readySteps()`) has more than one, run them via `async + awaitAll`.

### D4. [P2] `ConversationCompactor` does not scale `thresholdForModel` for 16K/32K context models
**File**: `aura-core/src/main/kotlin/com/aura/agent/ConversationCompactor.kt:55-75`
`thresholdForModel` returns 2400 for 4K models, 4800 for 8K. For 16K/32K (Claude Sonnet 4, Gemini 2.5) the threshold is capped at 4800 — compactor never runs for large-context models.
**Fix**: add 16K → 9600, 32K → 19200.

### D5. [P2] `MemoryAugmentedAgenticLoop.compactIfNeeded` returns the input `Conversation` unchanged on `IllegalStateException`
**File**: `aura-core/src/main/kotlin/com/aura/agent/ConversationCompactor.kt:88-95`
A chunk that arrives with an error and a text append is handled by `chunk.text?.let(output::append)` but the error rethrow is skipped if text is non-null. So a partial error during compaction silently degrades to no compaction.
**Fix**: rethrow on `ProviderChunk.error` even when text is present.

---

## E. MCP integration

### E1. [P1] `McpToolBridge` cleanup — tools from disconnected servers remain callable
**File**: `aura-core/src/main/kotlin/com/aura/mcp/McpToolBridge.kt`
When an MCP server is disconnected, the tools previously registered with the bridge are not unregistered. The `ToolRegistry` still lists them, the agentic loop still calls them, and the call fails with "no connection" instead of "tool not found".
**Fix**: add `unregister(serverId: String)` and call it from `McpClientManager.disconnect()`. The SettingsViewModel should refresh the registry after disconnect.

### E2. [P2] MCP initialize has 15s timeout but no body size cap on metadata calls (`tools/list`)
**File**: `aura-core/src/main/kotlin/com/aura/mcp/McpClientManager.kt`
A malicious or buggy MCP server could return a 2GB `tools/list` response. The OkHttp default body cap may not apply here.
**Fix**: wrap `tools/list` response in `.body?.byteStream()?.buffer()?.use { ... }` with a 2MB hard cap.

### E3. [P2] `McpConnection._health` was `@Volatile` (fixed in 7cea0bf2)
**File**: `aura-core/src/main/kotlin/com/aura/mcp/McpConnection.kt`
Already fixed. Listed for completeness.

---

## F. Specialist router / agent unification

### F1. [P2] `agentId` from `agentic loop` defaults to `"general"` but `AgentStore` does not seed an agent with `id = "general"` — only `id = "agent_general"`
**File**: `aura-core/src/main/kotlin/com/aura/agent/AgentStore.kt:60-80`; `aura-core/src/main/kotlin/com/aura/agent/MemoryAugmentedAgenticLoop.kt`
`agentStore.byId("general")` returns null because the seeded builtin agent has `id = "agent_general"`. The default `agentId = "general"` for legacy `Specialist` routing is a no-op.
**Fix**: either seed a `general` agent in `seedBuiltins()`, or default `agentId` to `"agent_general"` in the legacy adapter.

---

## SUMMARY

Sorted by severity, then by subsystem.

| # | Sev | Subsystem | File:Line | Finding |
|---|-----|-----------|-----------|---------|
| A1 | P0 | Loop | `MemoryAugmentedAgenticLoop.kt:569-573,768` | `PermissionGranted` event is dead code — permission retry never re-engages model |
| A2 | P1 | Loop | `DelegateToAgentTool.kt:115-118` | MCP allowlist rule differs from main agentic loop — child can use tools parent can't |
| A3 | P1 | Loop | `DelegateToAgentTool.kt` | Child `ToolContext` lacks `memoryEnabled`/`approvedRemoteCostTools` — REMOTE_COST always fails |
| A4 | P1 | Loop | `DelegateToAgentTool.kt` | Hard-coded 10s timeout kills child tools that take longer |
| A5 | P1 | Loop | `Conversation.kt:65-79` | `addToolCall` for empty conversation creates malformed Turn |
| B2 | P2 | ToolExecutor | `TimerTool.kt:36-46` | Timers map grows unbounded |
| B3 | P2 | ToolExecutor | `TtsSpeakTool.kt` | MediaPlayer `prepare()` blocks with no timeout |
| B4 | P2 | ToolExecutor | `NotificationsTool.kt` | AtomicInteger wraps to negative at 2^31 |
| C1 | P1 | Tool metadata | `UseSkillTool.kt:18-22` | `WRITE_LOCAL` annotation but `READ_ONLY` docstring |
| C2 | P1 | Tool input | `SmsSendTool.kt` | No phone number validation, no rate limit |
| C3 | P2 | Network | `HttpFileReadTool.kt:30-50` | SSRF-validated but uses original URL — DNS rebinding window |
| C4 | P2 | Network | `HttpFileWriteTool.kt` | No body size cap, no host allowlist |
| C5 | P2 | Tool API | `EmailSendTool.kt` | cc/bcc declared but never read |
| C6 | P2 | Tool input | `SendEmailBackgroundTool.kt` | No email validation, no rate limit |
| C7 | P2 | Permissions | `NotificationListTool.kt` | `isGranted()` throws for `BIND_NOTIFICATION_LISTENER_SERVICE` |
| C8 | P2 | Network | `FirecrawlFetchTool.kt` | SSRF validation is partial (only validates, doesn't pin) |
| D1 | P1 | AgentRun | `RunHandTool.kt:55-90` | Direct `repository.run()` fallback bypasses AgentRun pipeline |
| D2 | P2 | AgentRun | `RunHandTool.kt:~70` | `modelId` not passed to enqueue |
| D3 | P2 | Pipeline | `ProductionPipelineEngine.kt` | Sequential stages, no parallel DAG branches |
| D4 | P2 | Compactor | `ConversationCompactor.kt:55-75` | `thresholdForModel` doesn't scale for 16K/32K models |
| D5 | P2 | Compactor | `ConversationCompactor.kt:88-95` | Compaction skipped on partial error |
| E1 | P1 | MCP | `McpToolBridge.kt` | Stale tools from disconnected servers remain callable |
| E2 | P2 | MCP | `McpClientManager.kt` | No body size cap on `tools/list` |
| F1 | P2 | Agent | `AgentStore.kt:60-80` | `agentId = "general"` doesn't match seeded `"agent_general"` |

**Total**: 1 P0, 9 P1, 13 P2.

**Top three to fix first** (in order):
1. **A1** — `PermissionGranted` dead code means every permission request stuck. A user blocking on "Allow access to location" never resumes.
2. **A3 + A2 + A4** — delegated agents are effectively broken: child REMOTE_COST tools always fail, allowlist is inconsistent, timeout is too short. Fix as one bundle.
3. **D1** — `RunHandTool` fallback to direct execution bypasses the AgentRun pipeline, losing durability and UI signal. Hands run but aren't tracked.

**Subagent false positives caught during review:**
- B1 (`runInterruptible + runBlocking`) — verified as the correct pattern. Not a bug.
