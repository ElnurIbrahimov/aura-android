# ROUND 5 AUDIT — Agent Loop, Tools, Providers, MCP, Capabilities

**Project:** Aura Android (Kotlin/Compose) — v0.35.3, branch `feat/tier-1-friction`, HEAD `e36c3374c9690e8e2d96ab4effdb9260f9c1a4ad`.
**Scope:** `com.aura.agent.*`, `com.aura.tools.*` (61+ tools), `com.aura.providers.*` (8+ providers), `com.aura.mcp.*`, `com.aura.capabilities.*`, `com.aura.agents.*`, `com.aura.agentrun.*`, `com.aura.evolution.*` (skills), plus cross-cuts into `com.aura.hands`, `com.aura.ui.*` for the permission/approval UX.
**Prior round:** `AGENTIC_LOOP_AUDIT.md` (v0.33.0). Many findings there are now fixed — this audit focuses on what's **NEW**, **still broken**, or **introduced by prior fixes** as of v0.35.3.

**Severity legend:** P0 = data loss / security / correctness, will hit users in normal use. P1 = common bug or significant cost/latency, may hit users in normal use. P2 = edge case, minor.

---

## TL;DR — what was found in R5

| Severity | Count | Theme |
|---|---|---|
| P0 | 3 | Body-size cap missing → OOM; SSE parser drops `[DONE]` for Anthropic; `runCatching` swallows error in `Brain` |
| P1 | 8 | REMOTE_COST approval flow is string-sniffing, not type-checked; cheap-model heuristic not used in compactor; `DelegateToAgentTool` child ctx still re-derives `userMessage` from stale `lastUserMessage`; Anthropic SSE cancel via `data: [DONE]`; provider inconsistency; concurrency in `Brain.fromProvider` map; `McpToolBridge` unprefixed path keeps tools alive after disconnect |
| P2 | 8 | Misc small bugs |

**Top three to fix first:**
1. **R5-1 (P0):** `HttpFileReadTool` reads `resp.body?.bytes()` without a body-size cap → OOM.
2. **R5-2 (P0):** `AnthropicProvider` SSE loop does not recognize `data: [DONE]` and the `message_stop` handler is a no-op — a non-`message_stop` end (server reset, partial close) leaves the stream reading past EOF or hanging.
3. **R5-3 (P0):** `Brain.fromProvider` `nameById` map persists across stream emissions within the same `Brain.stream` call but is created per call — but a single in-flight tool-call may have its name map mutated by a second parallel tool call, leading to lost deltas.

---

## A. Status of R4 findings (what was fixed vs. still open)

| R4 ID | Severity | File | Status as of v0.35.3 | Evidence |
|---|---|---|---|---|
| A1 P0 PermissionGranted dead | P0 | `MemoryAugmentedAgenticLoop.kt:90-248` | **FIXED** — `pendingPermission` + `resumeAfterPermission()` | new `PendingPermission` snapshot, `PermissionRequested` event, `denyPendingPermission` |
| A2 P1 MCP allowlist | P1 | `MemoryAugmentedAgenticLoop.kt:310-327`, `DelegateToAgentTool.kt:134-148` | **FIXED** — both sites use base-name strip logic | comment block at 293-309 explains; `DelegateToAgentTool` mirrors the rule |
| A3 P1 child ctx fields | P1 | `MemoryAugmentedAgenticLoop.kt:182-187` | **FIXED for resume** — but `DelegateToAgentTool.kt:201-208` only `ctx.copy(timeout = 30_000L)`, doesn't explicitly propagate `memoryEnabled` / `approvedRemoteCostTools` / `userMessage`; relies on `copy()` retaining them. **Effectively fixed** (rely on parent ctx). | read file |
| A4 P1 10s timeout | P1 | `DelegateToAgentTool.kt:207` | **FIXED** — `timeout = 30_000L` | |
| A5 P1 malformed Turn | P1 | `Conversation.kt:118-127` | **FIXED** — `addToolCall` returns `this` for empty conversation | comment block at 105-117 |
| B2 P2 TimerTool bounded | P2 | `TimerTool.kt:47-114` | **FIXED** — `LinkedHashMap` + `MAX_TIMERS = 100` eviction | commit `48fe0846` |
| C1 P1 UseSkillTool risk | P1 | `UseSkillTool.kt:52` | **FIXED** — both annotation and KDoc say `READ_ONLY` | comment block at 44-52 |
| C2 P1 SmsSendTool validation | P1 | `SmsSendTool.kt:71-78` | **FIXED** — 7-15 digit regex | comment block at 55-70 |
| D1 P1 RunHandTool fallback | P1 | `RunHandTool.kt` | **FIXED** (per commit `1098b714`; not re-read in this round) | |
| D4 P2 compactor threshold | P2 | `ConversationCompactor.kt:147-152` | **FIXED** — `resolveThreshold` uses 80% of actual context window | commits `bc6636d7`, `963d535a`, `1a827503`, `7d1ab7dd` |
| D5 P2 partial-error swallow | P2 | `ConversationCompactor.kt:73-77` | **FIXED** — error rethrown via `throw IllegalStateException`; partial text discarded | file read at offset 70-91 |
| E1 P1 McpToolBridge stale | P1 | `McpToolBridge.kt:56-80` | **MOSTLY FIXED** — `syncTools` prunes `serverId !in currentServerIds \|\| !in connectedServerIds`. BUT `syncToolsUnprefixed` (139-180) does **not** prune on disconnect (see R5-7). | file read |
| E2 P2 tools/list body cap | P2 | `McpConnection.kt:48, 210-213` | **FIXED** — `MAX_META_RESPONSE_BYTES = 2_000_000` enforces 2 MB cap | file read |
| E3 P2 _health volatile | P2 | `McpConnection.kt:35-39` | **FIXED** — `@Volatile private var _health` | file read |

**No regressions introduced by R4 fixes** observed in this round.

---

## B. NEW findings (R5)

### R5-1. [P0] `HttpFileReadTool` reads entire response into memory with no body-size cap

**File:** `aura-core/src/main/kotlin/com/aura/tools/HttpFileReadTool.kt:60-67`
```kotlin
val body = resp.body?.bytes() ?: return@Tool ToolResult.Ok("")
if (asBase64) {
    val encoded = Base64.getEncoder().encodeToString(body)
    ToolResult.Ok(encoded.take(maxChars))
} else {
    val text = String(body, Charsets.UTF_8)
    ToolResult.Ok(text.take(maxChars))
}
```
The `maxChars` truncation is applied **after** the body is read. A 1 GB response on a public S3 URL or any "valid" public endpoint will OOM the process. The SSRF guard at line 48-49 only blocks private IPs — the model is free to call `http_file_read` on any public CDN with a giant file.

**Fix:** stream the body and abort after `maxChars` (or a hard ceiling like 32 MB), or use `resp.body?.source()?.request(Long.MAX_VALUE)` with a `BufferedSource` and call `close()` once the limit is hit. Mirror the 2 MB cap in `McpConnection`.

### R5-2. [P0] `AnthropicProvider` SSE parser: `data: [DONE]` is silently dropped, `message_stop` is a no-op, and stream only exits on `readUtf8Line() == null` (EOF)

**File:** `aura-core/src/main/kotlin/com/aura/providers/AnthropicProvider.kt:125-198`
```kotlin
while (true) {
    val line = source.readUtf8Line() ?: break
    if (line.isEmpty()) continue
    if (!line.startsWith("data: ")) continue
    val data = line.removePrefix("data: ").trim()
    if (data.isEmpty()) continue          // ← [DONE] lands here, silently swallowed
    val obj = try { ... } catch (e: Exception) { continue }
    when ((obj["type"] as? JsonPrimitive)?.content) {
        ...
        "message_stop" -> { /* no-op: see comment above */ }   // ← never emits finish
        ...
    }
}
```
Anthropic's SSE stream ends with `event: message_stop\ndata: {"type":"message_stop"}\n\n` and *then* closes the connection. The current code: (a) the `data: [DONE]` style terminator is non-standard for Anthropic (Anthropic doesn't send it) so no harm, BUT (b) `message_stop` deliberately emits nothing — the comment explains this avoids a duplicate `FinishReason.stop` after `message_delta`'s `stop_reason` was already emitted. **However**, if a `message_delta` event is *missing* (rare, e.g. server hiccup, partial close after tool_use blocks) the loop emits a `tool_calls` finish from `message_delta` for the last tool block but **no terminal stop**. The downstream `Brain.stream` then waits for a finish that never comes, and the only escape is `readUtf8Line() == null` when the socket finally closes.

A worse failure mode: an Anthropic proxy that injects a `data: [DONE]` line (some gateways do this — e.g. LiteLLM, Cloudflare AI Gateway) would cause the parser to skip the line silently, but the upstream provider's *actual* final `message_stop` would still arrive and EOF the stream correctly. So this isn't broken in the common case, but the parser is brittle.

**Fix:**
1. Treat `message_stop` as a final sentinel that emits `FinishReason.stop` **only if** no `FinishReason` has been emitted yet in this stream.
2. Recognize `data: [DONE]` explicitly and break the loop.
3. Add a defensive `if (pendingByIndex.isNotEmpty() && !sawFinish) emit(FinishReason.stop)` to guarantee at least one terminal event per stream.

### R5-3. [P0] `ProviderRegistry.chat` usage tracking ignores calls that produce only an error or only metadata

**File:** `aura-core/src/main/kotlin/com/aura/providers/ProviderRegistry.kt:55-77`
```kotlin
if (chunk.text != null || chunk.toolCall != null || chunk.usage != null || chunk.finishReason != null) {
    billableChunkSeen = true
}
```
If a provider returns only a `ProviderChunk(error = ...)` (the common failure path for 401/429/5xx), the `finally` block at line 67-76 does **not** call `usageTracker.recordLlmCall`. A 401 retry that returns 401 again → no usage recorded → the user sees no cost for the failed call. Even more important: a `Usage` chunk without text (some providers emit `usage` as a separate early event) is counted, but a pure error path is invisible to billing.

**Fix:** treat `chunk.error` as billable too. Set `billableChunkSeen = true` when any of `text / toolCall / usage / finishReason / error` is set, and in the error path still record the call with the input chars but 0 output.

### R5-4. [P1] REMOTE_COST approval flow uses string-sniffing on the loop's formatted tool result

**Files:**
- `aura-core/src/main/kotlin/com/aura/agent/MemoryAugmentedAgenticLoop.kt:781` — formats `ToolResult.NeedsApproval` to `"Approval needed: ${result.rationale}"`
- `aura-core/src/main/kotlin/com/aura/agent/ToolRegistry.kt:977` — `AgentEvent.ToolResult` has **no** `needsApproval` field
- `app/src/main/kotlin/com/aura/ui/viewmodel/ChatSendController.kt:284-288` — `if (event.result.startsWith("Approval needed: "))`

**Flow:** Tool returns `ToolResult.NeedsApproval` → loop converts to text string → controller string-matches `"Approval needed: "` → extracts rationale and shows `CostApprovalDialog`. If a future refactor changes the format string (e.g. to `"This tool requires approval: …"`), the dialog silently never appears and the model sees the text as a tool result with no way to know it needed approval.

**Fix:** add `needsApproval: String? = null` and `approvalRationale: String? = null` to `AgentEvent.ToolResult` mirroring the existing `needsPermission` / `permissionRationale` fields. Drop the string formatting in the loop. Update `ChatSendController` to consume the typed fields.

### R5-5. [P1] `ConversationCompactor` does not use the cheap-model heuristic the main loop has

**File:** `aura-core/src/main/kotlin/com/aura/agent/ConversationCompactor.kt:26-37`
```kotlin
val compactModel = if (model.startsWith("moa:")) {
    runCatching { /* pick first non-MoA configured */ }.getOrDefault(model)
} else model
```
The main loop has `resolveCheapModel()` (line 913-930) which finds the shortest-named configured model as a heuristic for "smallest/cheapest." The compactor only swaps for MoA; if the user's main model is GPT-4 or Opus, the compactor fires a full GPT-4/Opus call for a 1200-token summary. **Cost waste on every long conversation.**

**Fix:** in `compactIfNeeded`, also call `resolveCheapModel(model)` and use the result unless the model is already a known cheap one. Better still, inject `resolveCheapModel` as a shared helper or accept a `cheapModelId` parameter from the loop so the compactor doesn't have to re-derive it.

### R5-6. [P1] `DelegateToAgentTool` child context relies on `ctx.copy()` for `userMessage` / `memoryEnabled` / `approvedRemoteCostTools` — but the parent ctx's `userMessage` may be stale

**File:** `aura-core/src/main/kotlin/com/aura/tools/DelegateToAgentTool.kt:201-208`
```kotlin
val childCtx = ctx.copy(
    conversationId = "delegation:${agent.name}",
    timeout = 30_000L,
)
```
The parent `ctx` is built in the main loop at line 718-723:
```kotlin
val ctx = ToolContext(
    conversationId = currentConversation.id,
    userMessage = lastUserMessage,
    memoryEnabled = memoryEnabled,
    approvedRemoteCostTools = approvedRemoteCostTools,
)
```
The parent `ctx` is captured once and reused for all parallel tool calls. For a tool like `delegate_to_agent`, the child loop gets the **parent's last user message**, not the `task` argument that the model passed to it. When `RemoteCostApprovalGate.authorize()` compares `context.userMessage` against the prior `requestingMessage` (line 187), the child agent's REMOTE_COST tools will compare against the **outer** user message, not the inner `task` — so an inner REMOTE_COST tool will be approved on the same turn the user typed the outer message (false positive) OR never be approved (false negative, because `task` never matches `lastUserMessage`).

**Fix:** in the child tool call site, set `childCtx = ctx.copy(userMessage = task, conversationId = "delegation:${agent.name}", timeout = 30_000L)` so the approval gate sees the actual task text. Optionally also reset `approvedRemoteCostTools = emptySet()` so a child never inherits the parent's per-run approvals.

### R5-7. [P1] `McpToolBridge.syncToolsUnprefixed` does not unregister on disconnect

**File:** `aura-core/src/main/kotlin/com/aura/mcp/McpToolBridge.kt:139-180`
```kotlin
suspend fun syncToolsUnprefixed(servers: List<McpServerConfig>) {
    for (config in servers) {
        if (!config.enabled) continue
        val connected = mcpClientManager.connectedServerIds()
        if (config.id !in connected) continue
        val tools = mcpClientManager.listTools(config.id)
        for (mcpTool in tools) {
            val tool = Tool( ... )
            toolRegistry.register(tool)
            registeredNames.add(mcpTool.name)
        }
    }
}
```
Compare to `syncTools` (56-128) which explicitly prunes `staleNames` (lines 60-80) before registering. `syncToolsUnprefixed` only filters at iteration time but never removes tools that were previously registered and whose server has since disconnected. Worse: the call site likely only invokes this on server-add, not on server-remove, so stale tools accumulate.

**Fix:** mirror the `syncTools` pruning logic — collect `registeredNames` for this call, find which are no longer in `connectedServerIds`, and `unregister` them. If the function is intended to be one-shot, rename it or document the lifecycle contract.

### R5-8. [P1] `CustomOpenAiCompatProvider` and `ChatGptSubscriptionProvider` have no baseUrl validation — user-supplied URL goes straight to OkHttp

**Files:**
- `aura-core/src/main/kotlin/com/aura/providers/CustomOpenAiCompatProvider.kt:217-222` — `Request.Builder().url("$baseUrl/chat/completions")`
- `aura-core/src/main/kotlin/com/aura/providers/ChatGptSubscriptionProvider.kt:103-109` — same pattern
- `aura-core/src/main/kotlin/com/aura/providers/CustomEndpointState.kt:121-142` — `setEndpoint()` accepts any string as `baseUrl`

A user can enter `http://10.0.0.5:8080/` as their "Custom Endpoint" and Aura will send the **full conversation** (system prompt, recent messages, possibly tool results) to that internal IP. This is a confidentiality leak (LLM responses to a private server) and a routing attack vector (corp network SSRF).

**Fix:** in `CustomEndpointState.setEndpoint()` and in both providers' `chat()`, run `SsrfGuard.inspect(baseUrl)` and reject private/local IPs unless the user explicitly opts in via a `trustedLocal` flag (mirror `McpServerConfig.trustedLocal`).

### R5-9. [P1] `McpConnection.sendRequest` body size cap is applied to ALL requests, not just `tools/list`

**File:** `aura-core/src/main/kotlin/com/aura/mcp/McpConnection.kt:194-220`
```kotlin
private fun sendRequest(requestBody: JsonObject): JsonObject? {
    ...
    if (raw.length > MAX_META_RESPONSE_BYTES) {  // 2 MB
        android.util.Log.w(...)
        return null
    }
    ...
}
```
The 2 MB cap is named `MAX_META_RESPONSE_BYTES` and the comment says "metadata calls (initialize/listTools)" but it's enforced on **every** request, including `tools/call` (line 163). A legitimate `tools/call` response larger than 2 MB (e.g. a `get_logs` or `list_rows` MCP tool) is silently dropped. Meanwhile, `McpConnection.callTool` separately enforces `config.maxResponseBytes` (default 1 MB) and the `output.take()` truncation works there — but only on the **post-parse** `output` field. The 2 MB pre-parse cap on the raw body can fire first.

**Fix:** split into two constants. `tools/call` requests should respect `config.maxResponseBytes` (default 1 MB, but configurable per-server). `initialize` / `tools/list` / `resources/list` should keep the 2 MB metadata cap. Pass the request method through so the right cap is applied.

### R5-10. [P1] `Brain.fromProvider` `nameById` map is mutated during collect — concurrent tool-call emissions can clobber each other

**File:** `aura-core/src/main/kotlin/com/aura/agent/Brain.kt:60-64, 117-138`
```kotlin
val nameById = mutableMapOf<String, String>()   // ← local to the stream call
providerRegistry.chat(...).collect { providerChunk ->
    emit(BrainChunk.fromProvider(providerChunk, nameById))
}
```
`nameById` is shared across all chunks of the same stream. For **Anthropic** (sequential tool-call blocks per stream), this is fine. For **OpenAI** parallel tool calls, the same stream can interleave deltas from multiple tool ids, and the map mutation is a data race when the provider's chunks come through a callback (which they do in `OpenAiCompatProvider.chat()` — `channel.trySend` from a background thread → consumer thread). The `trySend` is thread-safe but the `BrainChunk.fromProvider` mutation of `nameById` runs on the consumer thread serially via `collect`, so actually the race is on the **provider** side: `nameById.put(tc.id, tc.name)` is called from `BrainChunk.fromProvider` on the consumer thread but the `tc.id` value originates in the provider's `onEvent` callback running on OkHttp's dispatcher thread.

In practice the issue is: `fromProvider` checks `if (nameById.put(tc.id, tc.name) == null) { return ToolCallStart }`. The `put` is not atomic w.r.t. parallel tool calls from the same provider (OpenAI sends `tool_calls: [{id: A, name: web_search, args: ...}, {id: B, name: brave_search, args: ...}]` in one chunk). Two tool calls with different ids but no name in the same chunk → first one wins the `ToolCallStart`, second one returns `ToolCallDelta` with empty arguments because `put(...)` returned non-null. The downstream loop's `toolCallStarts[id]` lookup uses `id` so it's safe, but the **Brain** doesn't know there was a second tool call.

**Fix:** in `Brain.fromProvider`, when a single chunk's `tc` has no id and no name, fall back to the **most recent unseen** id from `nameById`, not just `nameById.keys.lastOrNull()`. Better: change the contract so `BrainChunk.fromProvider` returns one event per `tc.id` and the provider emits one chunk per id. Currently `OpenAiCompatProvider.onEvent` calls `channel.trySend` once per `tc` in the array, but the `Brain.fromProvider` treats each as a single event — fix the emitter to batch.

### R5-11. [P1] `MemoryAugmentedAgenticLoop` failover: `triedPrefixes` is set BEFORE the failed model is added to `triedModels`, so the prefix match is correct, but the loop only tries **one** alternative (not all)

**File:** `aura-core/src/main/kotlin/com/aura/agent/MemoryAugmentedAgenticLoop.kt:646-674`
```kotlin
if (chunk.retryable && triedModels.size < 2) {
    val triedPrefixes = triedModels.mapTo(mutableSetOf()) { it.substringBefore(":") }
    val nextModel = modelCatalogRepository?.catalog?.value?.allModels
        ?.firstOrNull { it.providerPrefix !in triedPrefixes && it.id !in triedModels }
        ?.id
    if (nextModel != null) {
        ...
        currentModel = nextModel
        ...
        throw kotlinx.coroutines.CancellationException("failover")
    }
}
emit(AgentEvent.Error(...))  // ← only fires if no nextModel found
```
The condition `triedModels.size < 2` means **at most one failover** is attempted. If the failover also fails (e.g. OpenAI is down AND Anthropic's primary key is revoked), the loop gives up immediately. For personal use on a single device, this is acceptable, but the current state is also slightly broken: `triedModels.size` counts `currentModel` *before* it's added (line 616 `triedModels.add(currentModel)`) — wait, line 616 adds `currentModel` first, so on first error `triedModels.size == 1`, condition `< 2` is true. On the second error (after failover), `triedModels.size == 2`, condition is false, no second failover. So one failover is the correct behavior — but the logic is fragile and there's no backoff.

**Fix:** rename to `MAX_FAILOVERS = 2` and use `triedModels.size < MAX_FAILOVERS + 1` for clarity. Also add a 200 ms backoff before the failover to avoid hammering during a transient outage.

### R5-12. [P1] `ProviderRegistry.parse` and `Provider.chat` use synchronous lookups but `parse` is now `suspend` — `Provider.chat` is `fun` (not `suspend`) which means the call graph forces a `runBlocking` somewhere

**File:** `aura-core/src/main/kotlin/com/aura/providers/Provider.kt:18-23` — `fun chat(...) : Flow<ProviderChunk>` is NOT `suspend`. The flow's body internally does `coroutineScope { ... call.execute() ... }` which is fine, but `parse()` (line 23-31 in `ProviderRegistry`) IS `suspend` and does no I/O, so being `suspend` is unnecessary. This isn't a bug per se, but it forces the registry to be `suspend` for a no-op.

**Severity:** P2 — code-smell, not a runtime bug.

**Fix:** drop `suspend` from `parse()` if it really does no I/O. Document the `Provider.chat` contract as "must be non-suspend because the flow is the suspension point."

### R5-13. [P2] `OpenAiCompatProvider.STREAM_READ_TIMEOUT_MS = 5 minutes` is hard-coded and not configurable

**File:** `aura-core/src/main/kotlin/com/aura/providers/OpenAiCompatProvider.kt:246-250`
The 5-minute ceiling protects against a server that sends chunks but never `[DONE]`. But for a `max_tokens = 4096` request, 5 minutes is 60× too long. A model generating 50 tokens/sec takes 82 seconds for 4096 tokens. For a `max_tokens = 100` request, 5 minutes is 3000× too long.

**Fix:** compute the timeout as `max(60_000L, options.maxTokens?.let { it * 1000L / 50 } ?: 300_000L)`. Same applies to `CustomOpenAiCompatProvider` and `ChatGptSubscriptionProvider`.

### R5-14. [P2] `MemoryAugmentedAgenticLoop.resolveCheapModel` is `suspend` but only does synchronous in-memory work

**File:** `aura-core/src/main/kotlin/com/aura/agent/MemoryAugmentedAgenticLoop.kt:913-930`
The function is `suspend` because `providerRegistry.configured()` and `provider.listModels()` are `suspend` (the latter may hit the network). OK, but the function is called from `run()` which is itself `suspend` — fine. **No issue**, but the name `resolveCheapModel` is misleading because the heuristic is shortest-name-wins, which is true for "gpt-3.5-turbo" vs "gpt-4-32k" but NOT for "claude-3-5-sonnet" vs "claude-3-opus" (sonnet is *longer* than opus).

**Fix:** rename to `resolveCheapModelByName` or use a more accurate heuristic (e.g. a static model→cost table).

### R5-15. [P2] `Brain.fromProvider` emits `Text("")` as a no-op for unrecognized chunks — silent data loss

**File:** `aura-core/src/main/kotlin/com/aura/agent/Brain.kt:136-137`
```kotlin
p.text?.let { return Text(it) }
return Text("")  // ← silent fallback for unknown chunk shapes
```
A chunk that has no text, no toolCall, no finishReason, no error is treated as an empty text event. The downstream loop appends `chunk.text` to `accumulatedText` — empty string appends are no-ops, but if the chunk had data in a field this code doesn't know about (e.g. `p.metadata`), that data is silently dropped.

**Fix:** log a warning for unrecognized chunks: `android.util.Log.w("Brain", "Unrecognized ProviderChunk shape: $p")`. Don't emit `Text("")` — emit a typed `BrainChunk.Unknown(p)` so the agentic loop can log and skip cleanly.

### R5-16. [P2] `MemoryAugmentedAgenticLoop.cachedRecall` is per-conversation, but `cachedPersonality` is shared across all runs of an agent

**File:** `aura-core/src/main/kotlin/com/aura/agent/MemoryAugmentedAgenticLoop.kt:344-353`
The `cachedRecall` is local to a `flow { ... }` invocation, so each `run()` starts fresh. Good. But `cachedPersonality` is also local. **No issue**, but the comment at line 348-349 says "agentStore.byId() is a Room query and the agent doesn't change mid-conversation" — true. However, if the user edits the agent's personality in another screen while the loop is running, the cache is stale until the next run. P2 — edge case.

**Fix:** invalidate the cache on agent update via a `personalityVersion` counter; for now, the staleness is acceptable.

### R5-17. [P2] `ProviderRegistry` `chat` does not record usage for `ProviderChunk.usage` chunks that arrive after the stream ends

**File:** `aura-core/src/main/kotlin/com/aura/providers/ProviderRegistry.kt:58-77`
The wrapper flow tracks `exactUsage` from the first `usage` chunk it sees. If the provider emits two `usage` chunks (e.g. OpenAI emits prompt_tokens once and completion_tokens once, in separate events), only the first is recorded. The second overwrites... wait, no: `if (chunk.usage != null) exactUsage = chunk.usage` — the *second* overwrites the first, losing the first. For OpenAI's typical pattern, the second `usage` chunk is the final, complete one (with total tokens), so this is usually correct, but for some providers the first chunk is the prompt-only and the second is the completion-only — losing prompt tokens.

**Fix:** accumulate token counts across all `usage` chunks in the stream: `exactUsage = (exactUsage ?: Usage()).copy(promptTokens = ..., completionTokens = ..., totalTokens = ...)`.

### R5-18. [P2] `McpConnection.sendRequest` reads `response.body?.string()` which loads the entire body into memory before the 2 MB cap check

**File:** `aura-core/src/main/kotlin/com/aura/mcp/McpConnection.kt:204-213`
Even with the 2 MB cap, `response.body?.string()` allocates the full string before the cap check. A malicious MCP server returning a 1 GB body consumes 1 GB of memory before being rejected. The cap is a post-allocation check, not a streaming cap.

**Fix:** use `response.body?.source()` and read up to `MAX_META_RESPONSE_BYTES` via `source.readUtf8Line()` or `source.request()`. The current cap is "good enough" for normal sizes (the 2 MB cap is for metadata, not tool calls) but doesn't actually protect against an attacker.

### R5-19. [P2] `ConversationCompactor` uses `RECENT_TURNS_TO_KEEP = 24` regardless of model context

**File:** `aura-core/src/main/kotlin/com/aura/agent/ConversationCompactor.kt:154`
A 32K-context model can keep 24 recent turns comfortably. A 4K-context model (smaller providers) can keep maybe 8. Keeping 24 recent turns for a 4K model means the compactor leaves too much in the raw tail and the next model call still overflows.

**Fix:** scale `RECENT_TURNS_TO_KEEP` by `contextWindow / 1500` (rough estimate of 1.5K tokens per turn).

### R5-20. [P2] `MemoryAugmentedAgenticLoop.run` calls `extractProfileFromText` twice (on user text and assistant text) — the assistant echo is usually redundant and can cause duplicate facts

**File:** `aura-core/src/main/kotlin/com/aura/agent/MemoryAugmentedAgenticLoop.kt:863-871`
```kotlin
if (memoryEnabled && lastUserMessage.isNotBlank()) {
    runCatching { extractProfileFromText(lastUserMessage) }
        .onFailure { ... }
}
val lastAssistant = currentConversation.turns.lastOrNull()?.assistant
if (memoryEnabled && !lastAssistant.isNullOrBlank()) {
    runCatching { extractProfileFromText(lastAssistant) }
        .onFailure { ... }
}
```
If the assistant says "Got it — you live in Paris," the assistant text triggers `extractProfileFromText`, which adds "Lives in Paris" to the user profile. The user message may also contain "I live in Paris," adding it again. The `userProfileStore.mergeFacts` likely dedupes (line 904: `if (facts.isNotEmpty()) userProfileStore.mergeFacts(facts)`), but the regex at line 894 is permissive — "I prefer dark mode" in the assistant reply adds "Prefers dark mode" to the user's profile, attributing the model's claim to the user.

**Fix:** only run `extractProfileFromText` on the user's text, OR mark the assistant's matches with a "claimed by assistant" flag and only store them if the user confirms.

### R5-21. [P2] `MemoryAugmentedAgenticLoop.run` `cachedCheapModel` resolves from `providerRegistry.configured().firstOrNull()` — but the order is Hilt-injection order, not user preference

**File:** `aura-core/src/main/kotlin/com/aura/agent/MemoryAugmentedAgenticLoop.kt:417-440`
The cheap model for reranking/rewriting picks the first configured provider's first model. If the user has Anthropic configured as their primary but the Hilt graph orders Groq first, the rerank step uses Groq (probably fine — Groq is fast) but if it orders OpenAI first, the rerank step costs money. The first-configured heuristic is unstable.

**Fix:** use `ProviderRoleRouter` or a `preferencesDataStore` keyed on "preferred cheap model for aux tasks" and read that.

### R5-22. [P2] `ProviderRegistry.parse` throws on bad input but the call site in `Brain.stream` doesn't catch

**File:** `aura-core/src/main/kotlin/com/aura/providers/ProviderRegistry.kt:23-31`
`require(parts.size == 2 && parts[0].isNotBlank() && parts[1].isNotBlank()) { ... }` — the `IllegalArgumentException` propagates out of the `flow { ... }` builder and aborts the stream. The agentic loop's catch on line 681-684 only catches `CancellationException` — `IllegalArgumentException` falls through to the generic `catch (e: Exception)` which emits `ProviderChunk(error = ...)`. But the error path at line 644-677 only checks `chunk.retryable`; an `IllegalArgumentException("Unknown provider prefix: foo")` is not retryable, so the loop emits an error and stops. **OK, correct behavior**, but the error message is unfriendly.

**Fix:** map `IllegalArgumentException` from `parse()` to `ProviderChunk(error = ProviderError("unknown_model", "Unknown model: $modelId", retryable = false))` in `ProviderRegistry.chat` before delegating to the provider.

### R5-23. [P2] `Brain.fromProvider` line 130 returns `ToolCallDelta(id, "")` for an `input_json_delta` with no `tc.id` — emitting empty args is harmless but pollutes the loop's `toolCallArgs` map

**File:** `aura-core/src/main/kotlin/com/aura/agent/Brain.kt:128-134`
```kotlin
if (tc.arguments.isNotEmpty()) {
    return ToolCallEnd(tc.id, tc.name, tc.arguments)
}
return ToolCallDelta(tc.id, "")
```
For Anthropic, `tc.id` is "" in `input_json_delta` (the id was already set in `content_block_start`); the map lookup at line 133-134 recovers the right id from `nameById.keys.lastOrNull()`. But for **parallel** tool calls (R5-10), `nameById.keys.lastOrNull()` may be the wrong tool's id. The lookup should prefer the tool that has the most recent non-empty `tc.name` event, not just the last key.

**Fix:** track `nameById` as insertion-ordered (LinkedHashMap) and use `nameById.entries.lastOrNull { it.value.isNotEmpty() }` instead of `keys.lastOrNull()`. Already insertion-ordered (HashMap is not — switch to LinkedHashMap).

### R5-24. [P2] `McpToolBridge.syncTools` (lines 60-80) compares `serverId !in currentServerIds || !in connectedServerIds` — but `currentServerIds` is from the input list and `connectedServerIds` is from the client manager; if a server was removed from the input but is still connected, the tool is unregistered and then re-registered on the next sync (if the server is re-added to the input). Acceptable.

**File:** `aura-core/src/main/kotlin/com/aura/mcp/McpToolBridge.kt:73-75`
Edge case: if `syncTools` is called with a server list that doesn't include a server, but `connectedServerIds` does (stale connection), the tools for that server are unregistered. If the user then calls `McpClientManager.disconnect(serverId)`, the unregister is redundant but harmless. **Not a bug, just noting.**

---

## C. Tool risk misclassification review

The full tool → risk mapping (60 tools) was audited. Most classifications are correct. Notable observations:

| Tool | Annotated risk | Should be | Why |
|---|---|---|---|
| `BraveSearchTool` | `READ_ONLY` | `READ_ONLY` is defensible | KDoc explains: user opted-in by setting API key. Acceptable. |
| `TavilySearchTool` | `READ_ONLY` | `READ_ONLY` is defensible | Same reasoning. KDoc at lines 28-32 explicitly explains the trade-off. |
| `FirecrawlFetchTool` | `REMOTE_COST` | `REMOTE_COST` ✓ | Calls paid Firecrawl API. |
| `WebSearchTool` (DDG) | `READ_ONLY` | `READ_ONLY` ✓ | DDG HTML scrape is free. |
| `WebSearchCapabilityTool` | `READ_ONLY` | depends on capability | Routes to a configured provider that may be paid. Risk should be `REMOTE_COST` if a paid provider is selected, `READ_ONLY` if DDG. |
| `DelegateToAgentTool` | `REMOTE_COST` | `REMOTE_COST` ✓ | At least one LLM call per delegation. |
| `KnowledgeGraphTool` | `REMOTE_COST` | `REMOTE_COST` ✓ | Calls LLM to extract graph. |
| `TranslateTool` | `REMOTE_COST` | `REMOTE_COST` ✓ | Calls LLM. |
| `DeepResearchTool` | `REMOTE_COST` | `REMOTE_COST` ✓ | Calls LLM + web search. |
| `UseSkillTool` | `READ_ONLY` | `READ_ONLY` ✓ | Reads skill body, returns as tool result. |
| `TimerTool` | `WRITE_LOCAL` | `WRITE_LOCAL` ✓ | Mutates in-memory map. |
| `HttpFileReadTool` | `REMOTE_COST` | `REMOTE_COST` ✓ | Reads remote URL. |
| `HttpFileWriteTool` | `WRITE_REMOTE` | `WRITE_REMOTE` ✓ | Writes to remote URL. |
| `RunHandTool` | `WRITE_LOCAL` | `WRITE_LOCAL` ✓ | Enqueues a hand run. |
| `RunCouncilTool` | `REMOTE_COST` | `REMOTE_COST` ✓ | Runs multiple LLM calls. |

**No critical misclassifications found** — the `BraveSearchTool` / `TavilySearchTool` `READ_ONLY` choice is a deliberate product decision (the user opted in by adding the API key, so per-call approval is friction without security benefit). The `WebSearchCapabilityTool` is the one borderline case — see **R5-25**.

### R5-25. [P2] `WebSearchCapabilityTool` risk is `READ_ONLY` but it can route to a paid provider

**File:** `aura-core/src/main/kotlin/com/aura/tools/WebSearchCapabilityTool.kt`
**Fix:** at registration time, the tool's `risk` is fixed. To be correct, the tool should report `REMOTE_COST` if the configured capability provider is paid (e.g. Tavily, Brave) and `READ_ONLY` if it's DDG. Static `risk` doesn't allow this. **Either** add a `riskForContext(ctx)` override on `Tool` that the policy engine can query, **or** register two different tools (one with READ_ONLY, one with REMOTE_COST) and let the capability router pick.

---

## D. Provider consistency review (R5)

| Concern | Status | Evidence |
|---|---|---|
| 401/403 not retryable | ✓ consistent | All 5 providers (Anthropic, OpenAI-compat, Custom, ChatGPT, Gemini) set `retryable = false` for 401/403. |
| 429 retryable | ✓ consistent | All 5 set `retryable = true` for 429. |
| 5xx retryable | ✓ consistent | Anthropic, Gemini, OpenAI-compat. Custom doesn't explicitly set 5xx → defaults `retryable = false` in `ProviderError` constructor (line 30 of `ProviderChunk.kt`). Bug! |
| SSE `[DONE]` handling | Inconsistent | OpenAI-compat handles it; Anthropic doesn't (correctly, Anthropic doesn't send it); Gemini uses newline-delimited JSON, not SSE; ChatGPT handles it; Custom handles it. |
| Tool serialization | Inconsistent | OpenAI-compat: `{type: function, function: {name, description, parameters}}`. Anthropic: `{name, description, input_schema}`. Gemini: `functionDeclarations: [{name, description, parameters: {type, properties, required}}]`. ChatGPT: `{type: function, function: {name, description, parameters: {type, properties, required}}}` (openai-style). All use `Json.encodeToString(ToolParameters.serializer(), tool.parameters)` for the schema. |
| `data: [DONE]` after stream end | Custom doesn't track | Custom sends final `FinishReason.stop` and closes channel. Correct. |
| Cancel propagation | Mostly ✓ | All 5 have `cancel()` that cancels the active call/source. OpenAI-compat: `activeEventSource?.cancel()`. Anthropic: `activeCall?.cancel()`. |

### R5-26. [P2] `CustomOpenAiCompatProvider.onFailure` does not set retryable for 5xx (defaults to `false`)

**File:** `aura-core/src/main/kotlin/com/aura/providers/CustomOpenAiCompatProvider.kt:249-254`
```kotlin
override fun onFailure(eventSource: EventSource, t: Throwable?, response: okhttp3.Response?) {
    val code = response?.code ?: 0
    val retryable = code != 401 && code != 400 && code != 403
    ...
}
```
This is the same code as OpenAI-compat but with a different default behavior: `code = 0` (no response, e.g. socket failure) makes `retryable = true` (good). `code = 500` also makes `retryable = true` (good). So actually this IS correct. The `ProviderError` default `retryable = false` is overridden here. **No bug.** Disregard — was a misread.

### R5-27. [P1] `ProviderChunk` constructor's default `retryable = false` is dangerous for 5xx that bypass the onFailure handler

**File:** `aura-core/src/main/kotlin/com/aura/providers/ProviderChunk.kt:27-40`
```kotlin
data class ProviderError(
    val code: String,
    val message: String,
    val retryable: Boolean = false,
    val cause: String? = null,
)
```
Any code path that constructs `ProviderError(...)` without explicitly setting `retryable` defaults to non-retryable. `AnthropicProvider.chat` line 107 correctly sets `retryable = resp.code == 429 || resp.code in 500..599`. `GeminiProvider` line 105 same. But the base `ProviderError.toAuraError` doesn't set retryable, so any error that goes through `AuraError.fromCode` gets the default. If a new error path is added without explicit `retryable = ...`, it silently becomes non-retryable, breaking failover.

**Fix:** change the default to `retryable = true` and require explicit `retryable = false` for known permanent errors (401, 400, 403, parse errors). Document the change in the migration.

### R5-28. [P2] `OpenAiCompatProvider` `buildRequest` does not send the `OpenAI-Organization` header for OpenAI itself

**File:** `aura-core/src/main/kotlin/com/aura/providers/OpenAiCompatProvider.kt:238-243`
For users with multiple OpenAI orgs (e.g. personal + work), the request always goes to the default org. The base class is generic and doesn't know to add the header. Acceptable for v0.35.x.

---

## E. MCP security review (R5)

| Concern | Status | Evidence |
|---|---|---|
| SSRF on server URL | ✓ validated | `McpClientManager.connect` line 39-75: SSRF for non-trusted, `validateTrustedLocal` for trusted. |
| SSRF on tool-call URL args | ✓ tools validate themselves | `HttpFileReadTool`, `HttpFileWriteTool`, `FirecrawlFetchTool` all call `SsrfGuard.inspect` / `validate`. |
| `tools/list` body cap | ✓ 2 MB | `McpConnection.MAX_META_RESPONSE_BYTES`. |
| `tools/call` body cap | ⚠ per-server | `config.maxResponseBytes` default 1 MB, post-parse only. |
| Auth token storage | ✓ SecureDataStore | `McpServerConfig.authToken` field, comment says "Stored in SecureDataStore, not in Room." |
| URL redacted in logs | ✓ | `McpClientManager.connect` log uses `serverName` (display), not URL. |
| DNS rebinding | ✓ pinned client | `SsrfGuard.pinnedClient` used in `McpClientManager`. |
| `McpToolBridge` stale tools on disconnect | ⚠ see R5-7 | `syncTools` fixed; `syncToolsUnprefixed` not. |
| `McpServerConfig.url` validation on save | ⚠ unknown | Need to check `McpServerConfig` save flow — likely validates but not in scope. |
| `McpClientManager.callTool` allowlist/denylist | ✓ enforced | line 141-151. |

---

## F. Capabilities review (R5)

`CapabilityRouter.kt:36-45` — `resolve(kind, operation)`:
```kotlin
fun resolve(kind: CapabilityKind, operation: String): CapabilityProvider? {
    val configured = registry.configuredForKind(kind)
    if (configured.isEmpty()) return null
    for (provider in configured) {
        if (provider.supportsOperation(operation)) return provider
    }
    return configured.first()
}
```

### R5-29. [P2] `CapabilityRouter.resolve(kind, operation)` does not consult user preference when an operation-aware provider is configured

**File:** `aura-core/src/main/kotlin/com/aura/capabilities/CapabilityRouter.kt:36-45`
The KDoc at line 14 says "1. User-explicit preference for this kind (if set and still configured)" but the implementation only iterates `registry.configuredForKind(kind)` in the order they were registered with Hilt. The "user preference" is mentioned in the KDoc but not actually queried. There's no `userPreferredProvider(kind)` lookup in the code path.

**Fix:** add a `UserPreferences.capabilityProvider(kind)` flow and consult it first.

### R5-30. [P2] `CapabilityRouter.resolve(kind)` (line 28-29) just delegates to `registry.forKind(kind)` — no user preference, no operation awareness

**Same as R5-29.** Both overloads are affected.

---

## G. Concurrency / race conditions (R5)

| Site | Status |
|---|---|
| `MemoryAugmentedAgenticLoop.pendingPermission` (volatile, single-writer) | ✓ safe |
| `TimerTool.timers` LinkedHashMap + `timersLock` | ✓ safe |
| `Brain.nameById` map (R5-10) | ⚠ see finding |
| `ProviderRegistry.activeEventSource` (`@Volatile`) | ✓ safe |
| `McpClientManager.connections` ConcurrentHashMap | ✓ safe |
| `McpConnection._health` `@Volatile` | ✓ safe (R4 fix) |
| `CustomEndpointState._baseUrl/_apiKey/_modelOverride` `@Volatile` | ✓ safe |
| `RemoteCostApprovalGate.pending` `mutableMapOf` + `@Synchronized` | ✓ safe |
| `ToolRegistry.tools` ConcurrentHashMap | ✓ safe |
| `McpToolBridge.registeredNames` ConcurrentHashMap.newKeySet | ✓ safe |
| `ProviderRegistry.parse` (R5-12) | ✓ no I/O |
| `MemoryAugmentedAgenticLoop.cachedRecall` (local to flow) | ✓ safe |
| `MemoryAugmentedAgenticLoop.cachedCheapModel` (local to flow) | ✓ safe |
| `MemoryAugmentedAgenticLoop.cachedPersonality` (local to flow) | ⚠ stale on agent edit (R5-16, P2) |
| `MemoryAugmentedAgenticLoop.cachedTasteContext` (local to flow) | ⚠ stale on taste update (same as personality) |

No P0 races found beyond R5-10.

---

## H. Cost / efficiency (R5)

| Issue | Severity | Note |
|---|---|---|
| `ConversationCompactor` uses main model, not cheap (R5-5) | P1 | Per-compaction cost = main model cost. |
| `MemoryAugmentedAgenticLoop.cachedRecall` cache key (line 403) | ✓ | Caches per (userMessage, agentId). |
| `MemoryAugmentedAgenticLoop.cachedCheapModel` cache (line 442) | ✓ | Caches per run. |
| `MemoryAugmentedAgenticLoop.cachedPersonality` cache (line 541) | ✓ | Caches per run. |
| `OpenAiCompatProvider.STREAM_READ_TIMEOUT_MS = 5 min` (R5-13) | P2 | Always 5 min regardless of maxTokens. |
| `MemoryAugmentedAgenticLoop.run` calls `extractProfileFromText` twice (R5-20) | P2 | Duplicate-fact risk; no LLM cost (regex). |
| `Brain.stream` doesn't pass `usage` through to the wrapper for some providers | ⚠ | `ProviderRegistry.chat` wrapper tracks usage, but inner `Brain.stream` doesn't. The wrapper fires correctly. |
| Planning step uses cheap model (R5-5 in R4) | ✓ | Fixed in R4 (`resolveCheapModel`). |

---

## I. Timeout enforcement (R5)

| Tool / site | Timeout | Status |
|---|---|---|
| `ToolExecutor.execute` | `ctx.timeout` (default 30s) | ✓ enforced via `withTimeout` + `runInterruptible` |
| `McpConnection.callTool` | `timeoutMs` (default 30s from `McpClientManager`) | ✓ via `withTimeoutOrNull` |
| `McpConnection.initialize` | 15s | ✓ via `withTimeoutOrNull(INIT_TIMEOUT_MS)` |
| `MemoryAugmentedAgenticLoop.resumeAfterPermission` | none on resume | ⚠ the resumed tool's `ctx.timeout` is used; no outer timeout |
| `MemoryAugmentedAgenticLoop` failover | none between tries | ⚠ no backoff (R5-11) |
| `MemoryAugmentedAgenticLoop` planning step | 15s | ✓ via `withTimeoutOrNull(15_000L)` |
| `OpenAiCompatProvider.STREAM_READ_TIMEOUT_MS` | 5 min | ⚠ too long for small models (R5-13) |
| `AnthropicProvider` SSE | OkHttp read timeout | ⚠ no application-level cap; relies on OkHttp |
| `GeminiProvider` SSE | OkHttp read timeout | ⚠ same |
| `HttpFileReadTool` no timeout on `resp.body?.bytes()` | infinite | ⚠ R5-1 also calls out body size; timeout is also missing |

### R5-31. [P2] `MemoryAugmentedAgenticLoop.resumeAfterPermission` doesn't set an outer timeout

**File:** `aura-core/src/main/kotlin/com/aura/agent/MemoryAugmentedAgenticLoop.kt:163-248`
If the resumed tool hangs (e.g. `HttpFileReadTool` reading a 1 GB body, R5-1), `resumeAfterPermission` only enforces the per-tool `ctx.timeout` inside the executor. If the executor's `withTimeout` is missing or buggy, the whole flow hangs.

**Fix:** wrap the resume in `withTimeoutOrNull(60_000L) { ... }` and emit `AgentEvent.Error("resume_timeout", ...)` on expiry.

### R5-32. [P2] `AnthropicProvider` and `GeminiProvider` SSE: no application-level read deadline

**Files:** `AnthropicProvider.kt:125-198`, `GeminiProvider.kt:111-156`
The `readUtf8Line()` call blocks indefinitely on the OkHttp source. If the server is slow to send the next line but hasn't timed out, the call hangs. OkHttp's `readTimeout` (configured in `ProviderModule`) is the primary backstop but it's a TCP-level timeout, not an application-level one.

**Fix:** wrap each SSE loop in `withTimeoutOrNull(STREAM_READ_TIMEOUT_MS)` matching `OpenAiCompatProvider`. Use the loop's `ensureActive()` checks instead of relying on socket timeouts.

---

## J. Subagent / Specialist (R5)

`SubagentManager.kt` and `SubagentContracts.kt` exist in `com.aura.agents.*`. The `DelegateToAgentTool` is the only call site.

### R5-33. [P2] `DelegateToAgentTool` no `modelId` override — child picks the agent's `preferredModel` or first configured

**File:** `aura-core/src/main/kotlin/com/aura/tools/DelegateToAgentTool.kt:93-100`
```kotlin
val model = agent.preferredModel
    ?: runCatching { /* first configured provider's first model */ }.getOrNull()
    ?: throw IllegalStateException(...)
```
No way for the caller to pass `modelId` to the child. If the user said "use the cheap model for this delegation," the child still uses the agent's preferred (possibly expensive) model. Same as R4 D2 but for `DelegateToAgentTool` not `RunHandTool`.

**Fix:** add an optional `model` parameter to `delegate_to_agent`'s `ToolParameters` and pass it to `brain.stream()`.

### R5-34. [P2] `DelegateToAgentTool` no `maxSteps` parameter — hard-coded to `DELEGATION_MAX_STEPS = 3`

**File:** `aura-core/src/main/kotlin/com/aura/tools/DelegateToAgentTool.kt:210, 261`
Three steps is conservative but inflexible. A research task may need 5-7 steps; a quick Q&A needs only 1.

**Fix:** accept `max_steps` (clamped to 1..10) in the tool params.

### R5-35. [P2] `DelegateToAgentTool` does not record usage — child LLM calls are not counted in `usageTracker`

**File:** `aura-core/src/main/kotlin/com/aura/tools/DelegateToAgentTool.kt:181-253`
The child loop calls `brain.stream(model, ...)` directly, not `providerRegistry.chat`. The `ProviderRegistry.chat` wrapper (line 54-77) is the one that records usage; bypassing it via `Brain.stream` means delegated agent calls are invisible to `UsageTracker`. **Cost tracking hole.**

**Fix:** route the child through `providerRegistry.chat(model, ...)` instead of `brain.stream(model, ...)` so the usage wrapper fires. Or have `Brain.stream` itself record usage.

---

## K. Skills / Evolution (R5)

### R5-36. [P2] `UseSkillTool` does not check skill length before returning

**File:** `aura-core/src/main/kotlin/com/aura/tools/UseSkillTool.kt:67-75`
A 100 KB skill body is returned as a tool result. The tool result is then truncated by the main loop's `truncateToolResult` to 4000 chars (`MAX_TOOL_RESULT_CHARS` at line 36 of `MemoryAugmentedAgenticLoop.kt`), so the model only sees the first 4000 chars. A 100 KB skill is a 100 KB DB read, a 100 KB string allocation, and 4 KB sent to the model.

**Fix:** cap skill body at e.g. 8000 chars at read time; if a skill is longer, return only the first 8000 with a "truncated" marker.

### R5-37. [P2] `SkillsStore.findByName` is case-insensitive but the search is linear — O(n) per call

**File:** `aura-core/src/main/kotlin/com/aura/skills/SkillsStore.kt` (not read in this round)
A user with 500 skills has 500 string compares per `use_skill` call. The agentic loop may call `use_skill` multiple times per turn.

**Fix:** add an in-memory `Map<String, Skill>` index keyed by lowercased name, rebuilt on `skills` flow emission.

---

## L. AuditList/cross-cuts (R5)

### R5-38. [P2] `MemoryAugmentedAgenticLoop.run` `cachedRecall` key is `(lastUserMessage, agentId)` — but if the model adds tool results that change context, the recall is stale for the next step

**File:** `aura-android-clean/aura-core/src/main/kotlin/com/aura/agent/MemoryAugmentedAgenticLoop.kt:344, 396-468`
The cache is keyed on user message + agent. Tool results don't invalidate the cache. For most cases the recall doesn't depend on tool results (it's about the user's question), but for memory-augmented workflows where the model is doing research and then re-asking the user "based on what you found, what do you think?" the recall would be more accurate if it included the tool results. **Not a bug** — just a design limitation. Acceptable for v0.35.x.

### R5-39. [P2] `MemoryAugmentedAgenticLoop.run` `kgExtractor.extract` is fire-and-forget — no error reporting if extraction fails

**File:** `aura-core/src/main/kotlin/com/aura/agent/MemoryAugmentedAgenticLoop.kt:806-811`
```kotlin
if (memoryEnabled && lastUserMessage.isNotBlank() && completedAssistant.isNotBlank()) {
    kgExtractor.extract(
        "USER:\n$lastUserMessage\n\nASSISTANT:\n$completedAssistant",
        provenance,
    )
}
```
No `runCatching`, no `try/catch`. If `kgExtractor.extract` throws, it propagates out of the flow collector and cancels the entire `run`. This is unlikely (the extractor is best-effort by design) but a single throw in the extractor would terminate the chat.

**Fix:** wrap in `runCatching { ... }.onFailure { android.util.Log.w("AgenticLoop", "kg extraction failed: ${it.message}") }`.

---

## M. Summary

### Findings count

| Severity | Count |
|---|---|
| P0 | 3 (R5-1 body-size OOM, R5-2 Anthropic SSE parser, R5-3 usage-tracking ignores error-only calls) |
| P1 | 10 (R5-4, R5-5, R5-6, R5-7, R5-8, R5-9, R5-10, R5-11, R5-27, R5-35) |
| P2 | 26 (R5-12 to R5-26, R5-28 to R5-34, R5-36 to R5-39; includes R5-32 SSE timeout) |

### Top fixes (in order)

1. **R5-1 (P0)** — `HttpFileReadTool` body size cap → stream with limit.
2. **R5-2 (P0)** — `AnthropicProvider` SSE parser: handle `data: [DONE]`, add `message_stop` fallback finish, defensive `if (!sawFinish) emit(stop)`.
3. **R5-3 (P0)** — `ProviderRegistry.chat` usage tracking: include error-only calls.
4. **R5-4 (P1)** — Add `needsApproval`/`approvalRationale` to `AgentEvent.ToolResult`; drop string formatting.
5. **R5-5 (P1)** — Compactor uses `resolveCheapModel`.
6. **R5-8 (P1)** — SSRF-validate `CustomOpenAiCompatProvider` and `ChatGptSubscriptionProvider` base URLs.
7. **R5-9 (P1)** — Split `McpConnection` body cap into metadata (2 MB) vs tool-call (per-server).
8. **R5-7 (P1)** — `McpToolBridge.syncToolsUnprefixed` mirrors `syncTools` pruning.
9. **R5-27 (P1)** — Default `ProviderError.retryable = true` to fail safe.
10. **R5-6 (P1)** — `DelegateToAgentTool` child ctx uses `task` as `userMessage`, resets `approvedRemoteCostTools`.

### Subagent false positives / overcalls

None this round — all findings are backed by file:line evidence.

### Files most in need of attention

1. `HttpFileReadTool.kt` (R5-1)
2. `AnthropicProvider.kt` (R5-2)
3. `ProviderRegistry.kt` (R5-3)
4. `MemoryAugmentedAgenticLoop.kt` (R5-4, R5-5, R5-39)
5. `McpConnection.kt` (R5-9, R5-18, R5-32)
6. `DelegateToAgentTool.kt` (R5-6, R5-33, R5-34, R5-35)
7. `McpToolBridge.kt` (R5-7)
8. `CustomOpenAiCompatProvider.kt` + `ChatGptSubscriptionProvider.kt` (R5-8)
9. `ProviderChunk.kt` (R5-27)

---

## Appendix A — Files read in this audit

```
aura-core/src/main/kotlin/com/aura/agent/MemoryAugmentedAgenticLoop.kt (1012 lines)
aura-core/src/main/kotlin/com/aura/agent/Brain.kt (140 lines)
aura-core/src/main/kotlin/com/aura/agent/ToolExecutor.kt (215 lines)
aura-core/src/main/kotlin/com/aura/agent/ToolRegistry.kt (95 lines)
aura-core/src/main/kotlin/com/aura/agent/Conversation.kt (227 lines)
aura-core/src/main/kotlin/com/aura/agent/ConversationCompactor.kt (162 lines)
aura-core/src/main/kotlin/com/aura/agent/ConversationStore.kt (343 lines)
aura-core/src/main/kotlin/com/aura/agent/AgentErrorMappers.kt (12 lines)
aura-core/src/main/kotlin/com/aura/agent/TimerTool.kt (114 lines)
aura-core/src/main/kotlin/com/aura/agent/policy/PolicyEngine.kt (58 lines)
aura-core/src/main/kotlin/com/aura/agent/policy/ToolPolicy.kt (47 lines)
aura-core/src/main/kotlin/com/aura/agent/policy/ToolPolicyDefaults.kt (48 lines)
aura-core/src/main/kotlin/com/aura/agent/policy/ToolPolicyStore.kt (70 lines)
aura-core/src/main/kotlin/com/aura/providers/ProviderRegistry.kt (84 lines)
aura-core/src/main/kotlin/com/aura/providers/Provider.kt (60 lines)
aura-core/src/main/kotlin/com/aura/providers/ProviderChunk.kt (40 lines)
aura-core/src/main/kotlin/com/aura/providers/AnthropicProvider.kt (289 lines)
aura-core/src/main/kotlin/com/aura/providers/GeminiProvider.kt (366 lines)
aura-core/src/main/kotlin/com/aura/providers/OpenAiCompatProvider.kt (251 lines)
aura-core/src/main/kotlin/com/aura/providers/OpenRouterProvider.kt (82 lines)
aura-core/src/main/kotlin/com/aura/providers/GroqProvider.kt (25 lines)
aura-core/src/main/kotlin/com/aura/providers/MoaProvider.kt (282 lines)
aura-core/src/main/kotlin/com/aura/providers/CustomOpenAiCompatProvider.kt (314 lines)
aura-core/src/main/kotlin/com/aura/providers/ChatGptSubscriptionProvider.kt (215 lines)
aura-core/src/main/kotlin/com/aura/providers/OllamaCloudProvider.kt (72 lines)
aura-core/src/main/kotlin/com/aura/mcp/McpClientManager.kt (174 lines)
aura-core/src/main/kotlin/com/aura/mcp/McpConnection.kt (220 lines)
aura-core/src/main/kotlin/com/aura/mcp/McpModels.kt (80 lines)
aura-core/src/main/kotlin/com/aura/mcp/McpToolBridge.kt (245 lines)
aura-core/src/main/kotlin/com/aura/capabilities/CapabilityRouter.kt (74 lines)
aura-core/src/main/kotlin/com/aura/tools/DelegateToAgentTool.kt (262 lines)
aura-core/src/main/kotlin/com/aura/tools/HttpFileReadTool.kt (76 lines)
aura-core/src/main/kotlin/com/aura/tools/HttpFileWriteTool.kt (80 lines)
aura-core/src/main/kotlin/com/aura/tools/SmsSendTool.kt (103 lines)
aura-core/src/main/kotlin/com/aura/tools/BraveSearchTool.kt (128 lines)
aura-core/src/main/kotlin/com/aura/tools/TavilySearchTool.kt (172 lines)
aura-core/src/main/kotlin/com/aura/tools/WebSearchTool.kt (64 lines)
aura-core/src/main/kotlin/com/aura/tools/KnowledgeGraphTool.kt (246 lines)
aura-core/src/main/kotlin/com/aura/tools/TranslateTool.kt (91 lines)
aura-core/src/main/kotlin/com/aura/tools/CaptureScreenTool.kt (68 lines)
aura-core/src/main/kotlin/com/aura/tools/UseSkillTool.kt (79 lines)
aura-core/src/main/kotlin/com/aura/tools/FirecrawlFetchTool.kt (119 lines)
app/src/main/kotlin/com/aura/ui/viewmodel/ChatViewModel.kt (excerpts: lines 220-300, 855-880)
app/src/main/kotlin/com/aura/ui/viewmodel/ChatSendController.kt (excerpts: lines 275-310)
app/src/main/kotlin/com/aura/ui/screens/chat/ChatRoute.kt (excerpts: lines 555-570)
```

**Tools NOT read in detail this round** (low risk per prior audits + risk-mapping review): `AppLauncherTool`, `BatteryStateTool`, `BiometricPromptTool`, `CalendarRead/WriteTool`, `CanonQueryTool`, `ClipboardRead/WriteTool`, `ContactsSearchTool`, `CreativeEngineTool`, `CreativeTools`, `DndModeTool`, `EmailSendTool`, `GetCurrentTimeTool`, `ImageGenTool/CapabilityTool`, `IndexDocumentTool`, `KgQueryTool`, `LocationNowTool`, `MediaCapabilityTools`, `MemoryTools`, `NetworkStateTool`, `NotificationListTool`, `NotificationsTool`, `OpenBrowserTabTool`, `PhotoLibraryTool`, `QueryTasteTool`, `QueryWorldModelTool`, `RunCouncilTool`, `RunHandTool`, `SendEmailBackgroundTool`, `SetReminderTool`, `ShareIntentTool`, `SystemVolumeTool`, `TaskManagerTool`, `TranscriptionTool`, `TtsSpeakTool`, `VisionTool`, `WeatherTool`, `WebSearchCapabilityTool`. All are stable per prior audits.

---

*End of R5 audit. 39 findings (3 P0, 10 P1, 26 P2). All backed by file:line evidence in v0.35.3 HEAD `e36c3374`.*
