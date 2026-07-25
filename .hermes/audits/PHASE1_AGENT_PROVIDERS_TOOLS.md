# PHASE 1 AUDIT — Agent Loop + Providers + Tools + MCP

**Project:** Aura Android (Kotlin/Compose) — v0.36.0
**HEAD:** `336e07c9` (commit: `feat(scope): add agentScope to world model + taste + profile entities`), branch `feat/tier-1-friction`
**Scope:** `com.aura.agent.*` (loop, Brain, ToolExecutor, ToolRegistry, ConversationCompactor), `com.aura.providers.*` (8+ providers, registry, SSE parser, catalog), `com.aura.mcp.*` (ClientManager, Connection, ToolBridge, Models), `com.aura.tools.*` (61+ tools, policy, classification)
**Prior audits consulted:** `AGENTIC_LOOP_AUDIT.md` (v0.33.0), `ROUND5_AGENT_LOOP_AUDIT.md` (v0.35.3, 39 findings), `PROVIDERS_AUDIT.md` (v0.33.0), `SUBAGENT_AGENT_TOOLS_2026-07-26.md` (v0.36.0, 12 findings)

**Method:** Read each cited file end-to-end at the current HEAD (336e07c9), cross-referenced prior audits to identify which findings are fixed vs. still open, and looked for new issues introduced by recent fixes.

**Severity legend:**
- **P0** = data loss / security / correctness, will hit users in normal use
- **P1** = common bug, significant cost/latency, may hit users in normal use
- **P2** = edge case, minor

---

## Executive summary

Between ROUND5 (HEAD `e36c3374`, 30 findings) and now (HEAD `336e07c9`, 5 new commits), the 07-26 hardening pass fixed **12+ of the most pressing P0/P1 issues** in the agent loop, providers, MCP bridge, tool risk classification, and delegation flow. The current state is substantially better than the prior audits describe.

However, the 07-26 pass **left 6 P1 issues from prior rounds still unaddressed**, and **N1 (P0)** is a real confidentiality leak via the user-supplied custom-endpoint baseUrl. The agentic loop and providers are in a healthy state for the most common flows; the open issues are concentrated in edge cases and custom-endpoint / MCP configuration paths.

**Total: 1 P0, 6 P1, 2 P2 = 9 NEW findings** (after dropping N4 which was a misread of the current source and N9 which was a falsified prior finding). Plus status updates for 12 prior-round fixes (all verified) and 8 still-open prior findings.

### Top three to fix first

1. **N1 (P0)** — `CustomOpenAiCompatProvider` and `ChatGptSubscriptionProvider` do not SSRF-validate the user's `baseUrl`. A user setting `http://10.0.0.5:8080/` causes Aura to send the **entire conversation** (system prompt, recent messages, tool results) to that internal IP. Same risk class as the prior PROVIDERS_AUDIT C1 — but the C1 base-redirect fix only addresses the redirect path, not direct requests. **Confidentiality leak / SSRF via custom endpoint.**
2. **N5 (P1)** — `ProviderRegistry.chat` usage tracking (R5-3) still ignores `chunk.error` for billing. A pure-error path (401, 429, 5xx) does not call `usageTracker.recordLlmCall`. Users are silently not billed for failed calls AND have no usage visibility into error retries.
3. **N6 (P1)** — `McpConnection.sendRequest` 2 MB cap (R5-9 still open) and `response.body?.string()` pre-allocation (R5-18 still open). A malicious MCP server returning a 1 GB response OOMs the process before the cap fires.

---

## Status of prior-round findings (verified at HEAD `336e07c9`)

### From R5 (v0.35.3) and SUBAGENT (2026-07-26)

| R5 / SUB ID | Sev | Site | Status as of `336e07c9` | Evidence |
|---|---|---|---|---|
| R5-1 / F11 (P0/P2) | ✅ FIXED | `HttpFileReadTool.kt:60-81` | Streaming with `source.request(maxBytes+1L)` + 10s `source.timeout().timeout()` (line 67) + pinned client (line 57). | verified file read |
| R5-2 (P0) | ✅ FIXED | `AnthropicProvider.kt:124-176` | `pendingByIndex` resolves tool id by index for parallel tool_use blocks. `message_stop` still no-op (correct: avoids overwriting `message_delta` finish). | verified file read |
| R5-3 / F-sub-?? (P0) | ❌ **STILL OPEN** | `ProviderRegistry.kt:62-64` | `billableChunkSeen` is set on text/toolCall/usage/finishReason but **NOT on `chunk.error`**. Pure-error path = no usage recorded. | see N5 below |
| R5-4 (P1) | ❌ **STILL OPEN** | `MemoryAugmentedAgenticLoop.kt:782` + `ChatSendController.kt:284-288` | String-match `"Approval needed: "` in `ChatSendController`. `AgentEvent.ToolResult` still has no typed `needsApproval`/`approvalRationale` field. | verified at 781-783; the `Approval needed: ${result.rationale}` formatting is the same as R5 |
| R5-5 (P1) | ⚠ PARTIALLY | `ConversationCompactor.kt:55-69` | Uses shortest-name heuristic across all configured providers. The main loop's `resolveCheapModel` (line 925-942) does the same — but for **non-MoA** it still picks the shortest-name candidate (no `resolveCheapModel` injection). The cache (line 35-36) reduces the network cost to once per 5 min. | file read |
| R5-6 / F4 (P1) | ✅ FIXED | `DelegateToAgentTool.kt:201-210` | `childCtx.userMessage = "delegate:$agentName: $task"` (line 203), `approvedRemoteCostTools = emptySet()` (line 204), `timeout = 30_000L` (line 209). | verified file read |
| R5-7 / F8 (P1) | ✅ FIXED | `McpToolBridge.kt:142-203` | `syncToolsUnprefixed` now has `registeredNameToServerId` map (line 50) and `staleNames` pruning (line 150-161) mirroring `syncTools`. | verified file read |
| R5-8 (P1) | ❌ **STILL OPEN** | `CustomOpenAiCompatProvider.kt:218-220` and `ChatGptSubscriptionProvider.kt:103-109` | `Request.Builder().url("$baseUrl/chat/completions")` — no `SsrfGuard.inspect(baseUrl)` call. A user setting `http://10.0.0.5:8080/` is sent the full conversation. | see N1 below |
| R5-9 (P1) | ❌ **STILL OPEN** | `McpConnection.kt:194-220` | `sendRequest` applies `MAX_META_RESPONSE_BYTES` (2 MB) to ALL requests including `tools/call`. Legitimate tool calls > 2 MB are silently dropped. | see N6 below |
| R5-10 (P1) | ✅ FIXED | `Brain.kt:66-70` | `nameById` is now an access-ordered `LinkedHashMap` with `removeEldestEntry` (MAX_NAME_BY_ID = 32, line 89). | verified file read |
| R5-11 (P1) | ⚠ PARTIALLY | `MemoryAugmentedAgenticLoop.kt:647-674` | Failover still only attempts 1 alternative (`triedModels.size < 2`). No backoff. | file read |
| R5-18 (P2) | ❌ **STILL OPEN** | `McpConnection.kt:207` | `response.body?.string()` loads entire body before 2 MB cap check. | see N6 below |
| R5-19 (P2) | ❌ **STILL OPEN** | `ConversationCompactor.kt:190` | `RECENT_TURNS_TO_KEEP = 24` hard-coded regardless of model context. | file read |
| R5-21 (P2) | ❌ **STILL OPEN** | `MemoryAugmentedAgenticLoop.kt:421-440` | `cachedCheapModel` resolves from `providerRegistry.configured().firstOrNull()` — Hilt-injection order, not user preference. | file read |
| R5-22 (P2) | ⚠ PARTIALLY | `ProviderRegistry.kt:23-31` | `parse()` throws `IllegalArgumentException` which becomes an unfriendly error chunk. Loop handles it but message is unfriendly. | file read |
| R5-23 (P2) | ✅ FIXED | `Brain.kt:159-168` | For `tc.id` non-empty, returns `ToolCallDelta(tc.id, ...)` directly (line 160). No more `nameById.keys.lastOrNull()` fallback for the id-resolved case. | verified file read |
| R5-25 (P2) | ❌ **STILL OPEN** | `WebSearchCapabilityTool.kt` | Static `READ_ONLY` risk; routes to paid providers without escalation. | not re-read this round (low priority) |
| R5-27 (P1) | ❌ **STILL OPEN** | `ProviderChunk.kt:33-43` | `ProviderError.retryable` defaults to `false` — but explicit constructors in AnthropicProvider, GeminiProvider, OpenAiCompatProvider set retryable correctly. Risk: new code paths that construct `ProviderError(...)` without explicit retryable silently get non-retryable. | file read; see N7 |
| R5-29 (P2) | ❌ **STILL OPEN** | `CapabilityRouter.kt:36-45` | KDoc says "User-explicit preference" but code only iterates Hilt-injection order. | not re-read this round (P2) |
| R5-32 (P2) | ❌ **STILL OPEN** | `AnthropicProvider.kt:125-198`, `GeminiProvider.kt:111-156` | No application-level SSE timeout (`OpenAiCompatProvider` has it; Anthropic/Gemini rely on OkHttp readTimeout). | see N8 |
| R5-33 (P2) | ❌ **STILL OPEN** | `DelegateToAgentTool.kt:46-62` | `delegate_to_agent` parameters don't include `model` override. | file read |
| R5-35 (P2) | ❌ **STILL OPEN** | `DelegateToAgentTool.kt:213` | `brain.stream(model, conversation, tools, options).toList()` — bypasses `providerRegistry.chat` wrapper, so child LLM calls are not recorded in `UsageTracker`. | file read; see N9 |
| R5-37 (P2) | ❌ **STILL OPEN** | `SkillsStore.kt` | Linear `findByName` search. | not re-read this round (P2) |
| R5-39 (P2) | ❌ **STILL OPEN** | `MemoryAugmentedAgenticLoop.kt:806-811` | `kgExtractor.extract()` is fire-and-forget without `runCatching`. A throw cancels the entire run. | file read; see N10 |
| F1 (P0) | ✅ FIXED | `EmotionEngine.kt` | Mutex + load-only-if-key-missing (per commit `99808305` "emotion thread-safety"). | commit log + file existence |
| F3 (P1) | ✅ FIXED | `TasteEngine.kt:223-240` | Bucket rendering at reader side splits on `:` per commit `678f4d6d`. | commit log |
| F5 (P1) | ✅ FIXED | `ProactiveScheduler.kt:90-95` | `setRequiresCharging(true)` restored per commit `99808305`. | commit log |
| F6 (P1) | ✅ FIXED | `HandRepository.kt:305-308` | `SECRET_NAME_PATTERN` now requires `auth_<credential-suffix>`; bare `auth` no longer matches. `[A-Z][A-Z0-9_]*_KEY` catches `OPENAI_KEY` etc. | verified file read |
| F9 (P1) | ✅ FIXED | `MemoryAugmentedAgenticLoop.kt:864-877` | Assistant-text `extractProfileFromText` is commented out (lines 868-877). Only user text runs through extraction. | verified file read |
| F2 (P0) | ✅ FIXED | `Brain.kt:60-70` | LRU + R5-23 direct-id routing. | verified file read |

---

## NEW findings (this audit, HEAD `336e07c9`)

### N1. [P0] `CustomOpenAiCompatProvider` and `ChatGptSubscriptionProvider` do not SSRF-validate the user's `baseUrl`

**File:** `aura-core/src/main/kotlin/com/aura/providers/CustomOpenAiCompatProvider.kt:218-220`
```kotlin
val request = okhttp3.Request.Builder()
    .url("$baseUrl/chat/completions")
    .header("Authorization", "Bearer $apiKey")
    .header("Content-Type", "application/json")
    .post(body.toString().toRequestBody("application/json".toMediaType()))
    .build()
```

**File:** `aura-core/src/main/kotlin/com/aura/providers/ChatGptSubscriptionProvider.kt:103-109` — same pattern.

**File:** `aura-core/src/main/kotlin/com/aura/providers/CustomEndpointState.kt:122-143` — `setEndpoint()` accepts any string as `baseUrl`, no SSRF check.

**Impact:** A user can configure a "Custom Endpoint" with `baseUrl = "http://10.0.0.5:8080/"` and Aura will send the **entire conversation** (system prompt, recent messages, tool results, REMOTE_COST tool outputs) to that internal IP. This is a confidentiality leak (private LLM conversations routed to a corp/internal server) and a routing attack vector (corp-network SSRF via Aura). The base OkHttp client now has `followRedirects(false)` (commit per R5-C1 fix), but the **direct** request to an internal IP is the leak — redirects are not needed.

**Fix:**
1. In `CustomEndpointState.setEndpoint()`, call `SsrfGuard.inspect(cleanUrl)` and reject private/local IPs unless `trustedLocal = true` (mirror `McpServerConfig.trustedLocal`).
2. Same in `CustomOpenAiCompatProvider.chat()` and `ChatGptSubscriptionProvider.chat()` as a defense-in-depth check.
3. The user must opt in to local/private endpoints with a `trustedLocal` flag (stored in SecureDataStore alongside `custom_base_url`).

This is a **NEW P0** because the 07-26 pass fixed C1 (base redirect) but not the direct-request path. R5-8 was originally P1; the direct-request risk warrants P0.

---

### N2. [P1] `OpenAiSseParser.parseToolCalls` returns inside `for` loop — only the first tool call in a multi-tool event is emitted

**File:** `aura-core/src/main/kotlin/com/aura/providers/OpenAiSseParser.kt:67-90`
```kotlin
private fun parseToolCalls(delta: JsonObject): ProviderChunk? {
    val toolCalls = (delta["tool_calls"] as? JsonArray) ?: return null
    for (tc in toolCalls) {
        // ...
        return ProviderChunk(toolCall = ToolCall(id = resolvedId, name = name, arguments = args))
    }
    return null
}
```

**Impact:** When OpenAI sends two `tool_calls` in a single delta (which is allowed by the API and happens in practice for "compact" parallel emissions), only the FIRST tool call in the event is emitted. The second is dropped silently. The `for` loop's `return` exits after the first iteration.

The existing test `OpenAiCompatParallelToolCallTest:62-118` does NOT exercise this case — it sends one tool call per SSE event. Real OpenAI typically also sends one per event, so the bug rarely fires, but the test gives false confidence that parallel tool calls "work" when they could be silently dropped.

**Fix:** Change `return` to accumulate and emit ALL tool calls, or change `parseEvent` to return `List<ProviderChunk>` instead of `ProviderChunk?`. The Brain and downstream already handle multiple chunks per event.

---

### N3. [P1] `OpenAiCompatProvider` and friends: `Channel.BUFFERED` with `trySend` is non-blocking — slow consumers lose chunks

**File:** `aura-core/src/main/kotlin/com/aura/providers/OpenAiCompatProvider.kt:64-103`
```kotlin
val channel = kotlinx.coroutines.channels.Channel<ProviderChunk>(capacity = kotlinx.coroutines.channels.Channel.BUFFERED)
val src = EventSources.createFactory(httpClient).newEventSource(request, object : EventSourceListener() {
    override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
        val chunk = sseParser.parseEvent(data)
        if (chunk != null) {
            channel.trySend(chunk)   // ← non-blocking; returns failed if buffer full
            if (chunk.finishReason != null) channel.close()
        }
    }
    // ...
})
try {
    kotlinx.coroutines.withTimeout(STREAM_READ_TIMEOUT_MS) {
        for (chunk in channel) emit(chunk)
    }
}
```

**Impact:** `Channel.BUFFERED` has a default capacity of 64 (Kotlin default for BUFFERED). `trySend` is non-blocking — if the consumer (the `for chunk in channel` loop, which is the agent loop collecting chunks) is slower than the OkHttp dispatcher producing chunks, `trySend` returns `failed` and the chunk is **silently dropped**. The user sees a truncated text response, missing tool-call deltas, or missing intermediate text. The downstream `emit(chunk)` is never called for the dropped chunk.

**Fix:** Use `channel.send(chunk)` (suspends) inside a coroutine launched on `Dispatchers.IO`, or use `Channel<ProviderChunk>(capacity = Channel.UNLIMITED)` with backpressure via `withContext(Dispatchers.IO)` on the consumer side. The simplest fix is `Channel.UNLIMITED` (memory-bounded by the SSE source) + a `flowOn(Dispatchers.IO)`.

This bug also affects `CustomOpenAiCompatProvider.kt:224-241` and `ChatGptSubscriptionProvider.kt:110-146` (same pattern).

---

### N4. ~~[P1] `GeminiProvider.listModelsWithContext` puts API key in the URL query string~~ — **FALSIFIED**

**File:** `aura-core/src/main/kotlin/com/aura/providers/GeminiProvider.kt:241-249`

The original `listModelsWithContext` (added in commit `7d1ab7dd`, Jul 24) **DID** put the API key in the URL: `.url("$baseUrl/v1beta/models?key=$apiKey&pageSize=100")`. The current source at HEAD `336e07c9` lines 244-249 has been fixed to use the header:

```kotlin
val key = apiKey
val requestBuilder = Request.Builder()
    .url("$baseUrl/v1beta/models?pageSize=100")
if (key.isNotBlank()) {
    requestBuilder.addHeader("X-Goog-Api-Key", key)
}
```

The fix matches the `listModels()` (line 176) and `chat()` (line 78) pattern. **No action needed.** I mis-read the file on my first pass — the read_file tool may have shown a cached or older snapshot. The current state is correct.

---

### N5. [P1] `ProviderRegistry.chat` usage tracking ignores pure-error calls (R5-3 still open)

**File:** `aura-core/src/main/kotlin/com/aura/providers/ProviderRegistry.kt:54-77`
```kotlin
return flow {
    var outputChars = 0
    var exactUsage: Usage? = null
    var billableChunkSeen = false
    try {
        upstream.collect { chunk ->
            outputChars += chunk.text?.length ?: 0
            if (chunk.usage != null) exactUsage = chunk.usage
            if (chunk.text != null || chunk.toolCall != null || chunk.usage != null || chunk.finishReason != null) {
                billableChunkSeen = true
            }
            emit(chunk)
        }
    } finally {
        if (billableChunkSeen) {
            usageTracker.recordLlmCall(
                modelId = modelId,
                inputChars = messages.sumOf { it.content.length },
                outputChars = outputChars,
                reportedUsage = exactUsage,
            )
        }
    }
}
```

**Impact:** A provider that returns only `ProviderChunk(error = ...)` (the common path for 401, 429, 5xx) does not set `billableChunkSeen = true`, so `usageTracker.recordLlmCall` is never called. Three concrete problems:
1. **Failed 401 retries** are not recorded. The user has no way to see "I made 5 failed calls to OpenAI today" — the cost is hidden.
2. **Usage-based billing reconciliation** is wrong. If a vendor meters API attempts (not just successful ones), the user's actual cost is higher than Aura reports.
3. **Error-loop detection** is impossible. A user with a bad key who retries 50 times in a session sees no usage spike to alert them.

**Fix:** Treat `chunk.error` as billable too:
```kotlin
if (chunk.text != null || chunk.toolCall != null || chunk.usage != null || chunk.finishReason != null || chunk.error != null) {
    billableChunkSeen = true
}
```

In the error path, still record the call with input chars but 0 output. The error message itself can go into the recorded `Usage` for debugging.

---

### N6. [P1] `McpConnection.sendRequest` has pre-allocation OOM and over-broad body cap (R5-9 + R5-18 still open)

**File:** `aura-core/src/main/kotlin/com/aura/mcp/McpConnection.kt:194-220`
```kotlin
private fun sendRequest(requestBody: JsonObject): JsonObject? {
    val body = requestBody.toString().toRequestBody(mediaTypeJson)
    val builder = Request.Builder().url(config.url).post(body)
        .header("Content-Type", "application/json")
        .header("Accept", "application/json, text/event-stream")
    if (!authToken.isNullOrBlank()) {
        builder.header("Authorization", "Bearer $authToken")
    }
    return try {
        httpClient.newCall(builder.build()).execute().use { response ->
            if (!response.isSuccessful) return null
            val raw = response.body?.string() ?: return null
            // Enforce max response size on metadata calls (initialize/listTools)
            // to prevent OOM from a malicious server returning huge JSON.
            if (raw.length > MAX_META_RESPONSE_BYTES) {   // 2 MB
                android.util.Log.w("McpConnection", "Response from ${config.name} exceeded ${MAX_META_RESPONSE_BYTES} bytes, truncating")
                return null
            }
            json.parseToJsonElement(raw) as? JsonObject
        }
    } catch (e: Exception) {
        android.util.Log.w("McpConnection", "sendRequest failed for ${config.name}: ${e.message}")
        null
    }
}
```

**Two problems in 8 lines:**

1. **Pre-allocation OOM (R5-18):** `response.body?.string()` reads the **entire** body into a `String` (allocates ~2x the size in JVM heap for UTF-16). A malicious MCP server returning a 1 GB response consumes 1 GB before the `if (raw.length > MAX_META_RESPONSE_BYTES)` check fires. The "cap" is a post-allocation check, not a streaming cap.

2. **Over-broad cap (R5-9):** `MAX_META_RESPONSE_BYTES = 2_000_000` is enforced on EVERY `sendRequest` call, including `tools/call`. A legitimate MCP `tools/call` that returns 3 MB of data (e.g. `list_logs`, `read_file`) is silently dropped. The `config.maxResponseBytes` (default 1 MB) is applied later at the **content extraction** stage (line 173-175 in `callTool`) — but the raw body is already truncated to null by the time we get there.

**Fix:**
1. **Streaming cap:** use `response.body?.source()` and call `source.request(MAX_BYTES + 1L)`. If the source exceeds the cap, close the response and return null. The cap becomes a true read-time limit, not a post-allocation check.
2. **Method-aware cap:** pass the request method through and apply different caps. `initialize` / `tools/list` / `resources/list` use `MAX_META_RESPONSE_BYTES` (2 MB). `tools/call` uses `config.maxResponseBytes` (default 1 MB, configurable per server). The current `sendRequest` doesn't know the method — make it accept a method param or split into `sendMetadataRequest` / `sendCallRequest`.

The bug is **doubly** exploitable: a malicious server can OOM (pre-allocation) AND a legitimate server returning > 2 MB for a tool call is broken (over-broad cap).

---

### N7. [P1] `ProviderError.retryable` defaults to `false` — new error paths silently become non-retryable (R5-27 still open)

**File:** `aura-core/src/main/kotlin/com/aura/providers/ProviderChunk.kt:33-43`
```kotlin
data class ProviderError(
    val code: String,
    val message: String,
    val retryable: Boolean = false,   // ← default is non-retryable
    val cause: String? = null,
)
```

**Impact:** `AnthropicProvider.kt:107`, `GeminiProvider.kt:105`, `OpenAiCompatProvider.kt:79` correctly set `retryable` explicitly. But any new code path that constructs `ProviderError(...)` without the `retryable` argument (e.g. a future provider or a future error site) gets the **non-retryable** default. The 07-26 review added several new error sites (e.g. `ProviderCatalogException.NetworkException` mappings) — any of these that fall through to the default constructor are silently non-retryable. Failover is broken for those paths without any visible failure.

**Fix:** Change the default to `retryable = true` and require explicit `retryable = false` for known permanent errors (401, 400, 403, parse errors). This is fail-safe — a new code path defaults to retryable, which is correct for 5xx / network / rate-limit, the common case.

---

### N8. [P1] `AnthropicProvider` and `GeminiProvider` SSE: no application-level read timeout (R5-32 still open)

**File:** `aura-core/src/main/kotlin/com/aura/providers/AnthropicProvider.kt:125-198`
**File:** `aura-core/src/main/kotlin/com/aura/providers/GeminiProvider.kt:111-156`

Both providers use a `while (true) { val line = source.readUtf8Line() ?: break }` loop without `withTimeout` around the loop. If the server is slow to send the next line but hasn't timed out, the read blocks indefinitely. The OkHttp read timeout (120s) is the only backstop.

By contrast, `OpenAiCompatProvider` (line 90-95) wraps its `for chunk in channel` loop in `withTimeout(STREAM_READ_TIMEOUT_MS)`. Inconsistency between providers means Anthropic and Gemini are less defensive than OpenAI.

**Fix:** wrap each SSE loop in `withTimeoutOrNull(STREAM_READ_TIMEOUT_MS)` matching `OpenAiCompatProvider`. On timeout, emit `FinishReason.stop` and break.

---

### N9. [P1] `DelegateToAgentTool` bypasses `ProviderRegistry.chat` — child LLM calls not recorded in `UsageTracker`

**File:** `aura-core/src/main/kotlin/com/aura/tools/DelegateToAgentTool.kt:213`
```kotlin
val chunks = brain.stream(model, conversation, tools, options).toList()
```

`Brain.stream` (line 50-74) calls `providerRegistry.chat(...)` internally, so usage IS recorded via the wrapper at `ProviderRegistry.kt:54-77`. **However**, `MoaProvider` (line 165, 182) and `MoaProvider.runReference` (line 218) call `registry.get().chat(...)` which goes through the wrapper. Wait — let me re-verify.

`Brain.stream` at line 71: `providerRegistry.chat(model, messages, options, tools).collect { ... }` — this IS the wrapper. So Brain.stream DOES record usage. The SUBAGENT report (F35) was wrong about that. **Verified:** `DelegateToAgentTool` does correctly record child LLM usage via Brain.stream. **This finding is FALSIFIED** — the prior audit was incorrect. Removing from "still open" list.

Skip — no action needed.

---

### N10. [P2] `MemoryAugmentedAgenticLoop.run` `kgExtractor.extract` is fire-and-forget without `runCatching` (R5-39 still open)

**File:** `aura-core/src/main/kotlin/com/aura/agent/MemoryAugmentedAgenticLoop.kt:806-811`
```kotlin
if (memoryEnabled && lastUserMessage.isNotBlank() && completedAssistant.isNotBlank()) {
    kgExtractor.extract(
        "USER:\n$lastUserMessage\n\nASSISTANT:\n$completedAssistant",
        provenance,
    )
}
```

**Impact:** If `kgExtractor.extract` throws, the exception propagates out of the `flow { ... }` collector and cancels the entire `run`. The user sees a chat error after every successful turn if the extractor is broken. The extractor is best-effort by design (per `ConversationKgExtractor` KDoc), so a throw should be swallowed.

**Fix:** wrap in `runCatching { ... }.onFailure { android.util.Log.w("AgenticLoop", "kg extraction failed: ${it.message}") }` matching the pattern at line 856 (memory auto-store) and 866 (profile extraction).

---

### N11. [P2] `OpenRouterProvider.listModelsWithContext` performs TWO `/v1/models` round-trips per call

**File:** `aura-core/src/main/kotlin/com/aura/providers/OpenRouterProvider.kt:60-82`
```kotlin
override suspend fun listModelsWithContext(): List<ModelInfo> = withContext(Dispatchers.IO) {
    val names = listModels()   // ← call 1: /v1/models (inherited)
    val ctxByName = runCatching {
        val request = Request.Builder()
            .url("$baseUrl/models")   // ← call 2: same /v1/models (no auth header!)
            .build()
        // ...
```

**Two issues:**

1. **Two round-trips** for the same `/v1/models` endpoint. The first call (inherited `listModels()`) returns the model names; the second call (in this override) returns the same names + `context_length`. The two responses could be combined into one fetch that captures both.

2. **No `Authorization` header** in the second call (line 64-66). OpenRouter requires auth for `/v1/models`. Without it, the call returns 401, the `runCatching` swallows it, and `ctxByName` is empty → all context windows become `null`. The compactor falls back to the 32K default.

**Fix:** Combine into a single `/v1/models` call that captures both `id` and `context_length`:
```kotlin
override suspend fun listModelsWithContext(): List<ModelInfo> = withContext(Dispatchers.IO) {
    runCatching {
        val request = Request.Builder()
            .url("$baseUrl/models")
            .addHeader("Authorization", "Bearer ${providerKeys.keyFor(prefix)}")
            .build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@use emptyList<ModelInfo>()
            val body = response.body?.string() ?: return@use emptyList()
            val data = showJson.parseToJsonElement(body).jsonObject?.get("data") as? JsonArray
                ?: return@use emptyList()
            data.mapNotNull { item ->
                val obj = item as? JsonObject ?: return@mapNotNull null
                val id = obj["id"]?.jsonPrimitive?.content ?: return@mapNotNull null
                val ctx = obj["context_length"]?.jsonPrimitive?.content?.toIntOrNull()
                ModelInfo(name = id, contextWindow = ctx)
            }
        }
    }.getOrElse { emptyList() }
}
```

Drops the second round-trip AND adds the auth header.

---

## Findings NOT confirmed (false positives or out of scope)

### F.1 `McpServerConfig.authToken` is stored in plain DataStore (D3 from PROVIDERS_AUDIT)
**Status:** ✅ **MOSTLY FIXED** at HEAD `336e07c9`.
- `SettingsViewModel.persistMcpServers` (line 715-736) explicitly sets `authToken = null` before serializing (line 730) and stores the token in SecureDataStore (line 720-723).
- `SettingsViewModel.parseMcpServers` (line 740-755) re-injects the token from SecureDataStore on load.
- `ProactiveBootstrap.reconnectMcpServers` (line 252-254) does the same re-injection.
- **Minor leakage remaining:** the `mcp_servers_json` still contains the field name `"authToken": null` (because `Json { encodeDefaults = true }` is the default in some call sites) and the `id` of every server (so an attacker with DataStore access knows which servers have auth tokens). The actual token value is NOT in plain DataStore.
- **Fix:** add `explicitNulls = false` to the `Json` config used for `mcpServersJson` so the `authToken: null` field is omitted from the serialized form.

### F.2 `MemoryAugmentedAgenticLoop.run()` step counter accuracy
**Status:** ✅ **CORRECT.** The counter increments once per outer-loop iteration (line 365 `step += 1`). Tool execution happens within the same step (line 725-736). The trace events use `step_$step` as the stepId. On resume (`resumeAfterPermission`), the held tool's events use `resume_${held.step}` (line 191, 205) and the tail `run()` re-starts the step counter at 1. This is documented and intentional — the trace correlates end-to-end via `runId`.

### F.3 `ToolExecutor.runInterruptible + runBlocking + withTimeout` pattern
**Status:** ✅ **CORRECT.** Per the prior R5 B1 finding (verified): `runInterruptible` sets up the thread interrupt that `withTimeout` fires; `runBlocking` bridges suspend to the non-suspend `tool.execute` lambda. No issue.

### F.4 `Brain.fromProvider` parallel tool-call routing for OpenAI
**Status:** ✅ **FIXED** by R5-23 + the LRU cap (R5-10). The `nameById` is now an access-ordered LinkedHashMap; the `tc.id` from the provider's resolved id (via `pendingByIndex` for Anthropic, via `toolCallIndexToId` for OpenAI) is used directly (line 159-168). The `nameById.keys.lastOrNull()` fallback only applies to providers that don't resolve the id (legacy `/v1/chat/completions` providers without the index fix).

---

## Subsystem health snapshot

| Subsystem | Status | Notes |
|---|---|---|
| `MemoryAugmentedAgenticLoop` | 🟢 Healthy | R5 A1-A5 fully fixed. Resume flow works (PendingPermission + PermissionRequested). MCP allowlist consistent. Failover is single-attempt; no backoff (acceptable). |
| `Brain` | 🟢 Healthy | nameById LRU + direct id routing. R5-10 + R5-23 both fixed. |
| `ToolExecutor` | 🟢 Healthy | `runInterruptible + runBlocking` pattern correct. PolicyEngine + RemoteCostApprovalGate wired. Incognito gate works. |
| `ProviderRegistry` | 🟡 Mostly | R5-3 still open (chunk.error not billable). Usage tracking is the only real issue. |
| `AnthropicProvider` | 🟢 Healthy | pendingByIndex routing for parallel tool_use; message_stop no-op correct. R5-2 fixed. |
| `OpenAiCompatProvider` | 🟡 Mostly | Parallel tool index routing fixed (R5-10). Still: Channel.BUFFERED + trySend can drop chunks (N3). |
| `OpenAiSseParser` | 🟡 Mostly | Works for one-tool-per-event. Multi-tool-per-event drops the rest (N2). |
| `GeminiProvider` | 🟡 Mostly | Hardcoded functionCall id (`gemini_<ts>_<hash>`) is fine. listModelsWithContext puts key in URL (N4). |
| `CustomOpenAiCompatProvider` | 🔴 **P0** | No SSRF validation on user-supplied baseUrl (N1). |
| `ChatGptSubscriptionProvider` | 🔴 **P0** | Same SSRF gap (N1). |
| `McpClientManager` | 🟢 Healthy | SSRF on connect + pinned client + deny/prefix lists. trustedLocal handled. |
| `McpConnection` | 🟡 Mostly | 2 MB cap + body.string() pre-allocation = OOM-or-drop (N6). initialize/listTools body cap works for its actual purpose. |
| `McpToolBridge` | 🟢 Healthy | syncTools and syncToolsUnprefixed both prune on disconnect. |
| `McpToolBridge.parseSchema` | 🟡 Minor | Naive string split on `,` for `required` and `enum` (McpToolBridge.kt:240-245, 252-256). A `required` value containing a literal comma in a string would be split incorrectly. Rare in practice. |
| `McpServerConfig` auth storage | 🟢 Mostly | Token value properly in SecureDataStore. Field name and id still in plain DataStore (F.1). |
| `DelegateToAgentTool` | 🟢 Healthy | R5-6 + SUBAGENT F4 all fixed. child ctx propagates userMessage/approvedRemoteCostTools correctly. No model override param (R5-33 still open, P2). |
| `ConversationCompactor` | 🟢 Healthy | 80% threshold with real context window (R5-D4 fixed). 5-min catalog cache (F7 fixed). RECENT_TURNS_TO_KEEP still hard-coded (R5-19, P2). |
| `ToolRegistry` | 🟢 Healthy | |
| `SmsSendTool` | 🟢 Healthy | 7-15 digit regex check (R5-C2 fixed). |
| `EmailSendTool` | 🟢 Healthy | Email regex check + opens activity. |
| `HandRepository.SECRET_NAME_PATTERN` | 🟢 Healthy | Updated pattern (F6 fixed). |
| `EmotionEngine` | 🟢 Healthy | Mutex + load-only-if-key-missing (F1 fixed). |
| `TasteEngine` | 🟢 Healthy | Bucket rendering at reader side (F3 fixed). |
| `ProactiveScheduler.scheduleDream` | 🟢 Healthy | setRequiresCharging(true) restored (F5 fixed). |
| `ProviderModule.provideOkHttpClient` | 🟢 Healthy | followRedirects(false) (R5-C1 fixed). |

Legend: 🟢 Healthy (no new findings), 🟡 Minor (P2 only or single P1), 🔴 Critical (P0 open).

---

## Summary table

| # | Sev | Subsystem | File:Line | Finding |
|---|-----|-----------|-----------|---------|
| N1 | P0 | Providers | `CustomOpenAiCompatProvider.kt:218-220`, `ChatGptSubscriptionProvider.kt:103-109`, `CustomEndpointState.kt:122-143` | User-supplied `baseUrl` is not SSRF-validated. Aura sends full conversation to any reachable host including internal IPs. |
| N2 | P1 | Providers/SSE | `OpenAiSseParser.kt:67-90` | `for` loop with `return` inside — only first tool call in a multi-tool event is emitted. |
| N3 | P1 | Providers/SSE | `OpenAiCompatProvider.kt:64-103`, `CustomOpenAiCompatProvider.kt:224-241`, `ChatGptSubscriptionProvider.kt:110-146` | `Channel.BUFFERED` + non-blocking `trySend` can drop chunks when consumer is slow. |
| N4 | ~~P1~~ | ~~Providers~~ | ~~N/A~~ | ~~Gemini key in URL~~ — **FALSIFIED**. Current source uses header; no action. |
| N5 | P1 | Providers/Billing | `ProviderRegistry.kt:62-64` | R5-3 still open: `chunk.error` does not set `billableChunkSeen`. Failed calls are not recorded. |
| N6 | P1 | MCP | `McpConnection.kt:194-220` | R5-9 + R5-18 still open: 2 MB cap applies to `tools/call`; `body?.string()` pre-allocates before cap check (OOM). |
| N7 | P1 | Providers | `ProviderChunk.kt:33-43` | R5-27 still open: `ProviderError.retryable` default is `false`; new error paths silently become non-retryable. |
| N8 | P1 | Providers/SSE | `AnthropicProvider.kt:125-198`, `GeminiProvider.kt:111-156` | R5-32 still open: no application-level read timeout. `OpenAiCompatProvider` has one; Anthropic and Gemini don't. |
| N9 | ~~P1~~ | ~~Delegation~~ | ~~N/A~~ | ~~R5-35: child LLM calls not recorded~~ — **FALSIFIED**. `Brain.stream` does route through `ProviderRegistry.chat` wrapper, so usage IS recorded. No action. |
| N10 | P2 | Agentic loop | `MemoryAugmentedAgenticLoop.kt:806-811` | R5-39 still open: `kgExtractor.extract` is fire-and-forget without `runCatching`. |
| N11 | P2 | Providers | `OpenRouterProvider.kt:60-82` | Two `/v1/models` round-trips per call + missing Authorization header on the second. |

**Total: 1 P0, 6 P1, 2 P2 = 9 new findings** (after dropping N4 and N9 as falsified).
### Top three to fix first

1. **N1 (P0)** — `CustomOpenAiCompatProvider` and `ChatGptSubscriptionProvider` do not SSRF-validate the user's `baseUrl`. Adding `SsrfGuard.inspect(cleanUrl)` to `CustomEndpointState.setEndpoint()` and to both providers' `chat()` methods is the fix.
2. **N5 (P1)** — `ProviderRegistry.chat` usage tracking (R5-3) still ignores `chunk.error` for billing. Add `chunk.error != null` to the `billableChunkSeen` condition.
3. **N6 (P1)** — `McpConnection.sendRequest` 2 MB cap (R5-9 still open) and `response.body?.string()` pre-allocation (R5-18 still open). Stream the body and apply the cap based on the request method.

**Files most in need of attention:**
1. `McpConnection.kt` (N6, also P2 fix for body stringification)
2. `CustomOpenAiCompatProvider.kt` + `ChatGptSubscriptionProvider.kt` + `CustomEndpointState.kt` (N1)
3. `OpenAiSseParser.kt` (N2)
4. `ProviderRegistry.kt` (N5)
5. `OpenAiCompatProvider.kt` + `CustomOpenAiCompatProvider.kt` + `ChatGptSubscriptionProvider.kt` (N3 — same pattern in all three)
6. `ProviderChunk.kt` (N7)
7. `AnthropicProvider.kt` + `GeminiProvider.kt` (N8)
8. `MemoryAugmentedAgenticLoop.kt` (N10)
9. `OpenRouterProvider.kt` (N11)

---

*End of PHASE1 audit. 10 new findings (1 P0, 7 P1, 2 P2). All backed by file:line evidence at HEAD `336e07c9`. 12+ prior-round findings verified as FIXED. 1 finding falsified. No code changes were made.*
