# SUBAGENT AUDIT — Agentic Loop, ToolExecutor/ToolRegistry, Brain, Providers, Tool Implementations
**Date:** 2026-07-26
**Project:** Aura Android (Kotlin/Compose) — v0.35.3 → v0.36.0, branch `feat/tier-1-friction`
**HEAD:** `40f5ca68` (post 34cf9e1d + 40f5ca68 review passes)
**Method:** Read the diff between `34cf9e1d^` and `40f5ca68`, cross-reference with ENGINEERING_REVIEW_2026-07-25.md, AGENTIC_LOOP_AUDIT.md, and ROUND5_AGENT_LOOP_AUDIT.md, then verify each candidate finding by reading the actual current source. Skip anything already documented as fixed or flagged.

**Prior passes consulted:** `ENGINEERING_REVIEW_2026-07-25.md` (10 confirmed fixes, 7 ambiguities flagged), `ENGINEERING_REVIEW_2026-07-18.md` (6 fixes), `AGENTIC_LOOP_AUDIT.md` (v0.33.0), `ROUND5_AGENT_LOOP_AUDIT.md` (v0.35.3 with 30 findings). The 07-25 review and 07-18 review together fixed 16 issues. R5 had 30 findings, of which ~13 were carried forward to the 07-25 review pass and resolved.

**Severity legend:** P0 = data loss / security / correctness, will hit users in normal use. P1 = common bug or significant cost/latency. P2 = edge case, minor.

---

## Executive summary

After the 07-25 review pass, the recent two commits (34cf9e1d and 40f5ca68) are mostly **safe plumbing** — HttpFileReadTool streaming, memory_feedback backup purge, emotion persistence wiring, NetworkCallback unregister, isolatedSessionRequested reset, and a handful of small fixes. The fixes are correct in isolation.

However, **the TasteEngine aggregation fix introduced a regression in taste-context rendering** (P1 — affects every chat turn), **the ProactiveScheduler.scheduleDream() change silently removed the `setRequiresCharging(true)` constraint** (P1 — battery drain on phones left unplugged), and **the HandRepository SECRET_NAME_PATTERN extension causes widespread false positives on common English words like `author`, `authority`, `authentic`** (P1 — data loss in hand history).

Three P0/P1 issues that the 07-25 review did not address: (1) `DelegateToAgentTool` child ToolContext still inherits the parent's `userMessage`, so a delegated agent's REMOTE_COST tool can never be approved by the user (R5-6), (2) `McpToolBridge.syncToolsUnprefixed` does not unregister tools on disconnect (R5-7), (3) `Brain.fromProvider`'s `nameById.keys.lastOrNull()` lookup mis-routes Anthropic parallel tool-call deltas (R5-23).

**Total: 2 P0, 7 P1, 3 P2 = 12 findings.**

---

## Findings (sorted by severity, then subsystem)

### F1. [P0] `EmotionEngine.update()` / `decay()` / `load()` are not thread-safe — `state` is mutated without synchronization
**File:** `aura-core/src/main/kotlin/com/aura/emotion/EmotionEngine.kt:41, 67, 114, 129, 175`
```kotlin
private var state = EmotionSnapshot()
// ... in update():
val s = state.copy()  // READ
state = EmotionSnapshot(...)  // WRITE
```
**What changed in recent review:** The 07-25 pass wired `emotionEngine.save()` into the agentic loop (line 879-883 of MemoryAugmentedAgenticLoop.kt) and `emotionEngine.load()` into both `ProactiveBootstrap.start()` (line 70-72) and `SettingsViewModel.init` (line 255-260). All three of these can run concurrently:
- `update()`/`decay()` are called from the agentic loop on step 1 (background Dispatcher).
- `load()` is called from `ProactiveBootstrap` on `Dispatchers.IO` at app start.
- `load()` is also called from `SettingsViewModel.init` on `viewModelScope` (main).
- `snapshot()` is read by the agentic loop and by the Settings UI on the main thread.

`update()` reads `state`, then computes a new value, then writes `state`. If two calls race, the second write clobbers the first. Same race for `decay()` and `load()`. The `state` field is not `@Volatile` and not behind a Mutex.

**Impact:** A user who opens the chat while the agentic loop is in flight (or who navigates to Settings mid-conversation) can have their emotion state silently reset to defaults, or have one of the two callers' updates lost. Also, if `load()` runs after `update()` from the loop, the user's in-flight emotion is wiped. There's a real window where this races because both `load()` and `update()` are non-suspending in `update()`/`decay()` — `load()` is `suspend` but the actual `state = EmotionSnapshot(...)` assignment is not atomic.

**Fix:** Wrap every `state = ...` and `state.copy()` in a `Mutex.withLock { ... }`, or use `@Volatile var state` plus `AtomicReference<EmotionSnapshot>`. The least-invasive change: `private val mutex = Mutex()` and replace each read-then-write with `mutex.withLock { ... }`. Also change the 4 default-value constants in `load()` to only apply if `prefs[KEY_TENSION] == null` (the current code overwrites `state` even when all four keys are missing, which silently discards any in-flight state from `update()`).

---

### F2. [P0] `Brain.fromProvider` `nameById.keys.lastOrNull()` mis-routes Anthropic parallel tool-call deltas
**File:** `aura-core/src/main/kotlin/com/aura/agent/Brain.kt:60, 117-138`
```kotlin
val nameById = mutableMapOf<String, String>()   // local to stream
...
// In fromProvider, delta path (Anthropic input_json_delta):
val id = nameById.keys.lastOrNull() ?: return Text("")
return ToolCallDelta(id, tc.arguments)
```
**What changed in recent review:** Nothing — this bug was documented in R5-23 as a P2 and never addressed. The 07-25 review did not touch `Brain.kt`.

**Impact:** When Anthropic sends two parallel `tool_use` blocks in one stream, `nameById` accumulates entries in insertion order (`{"A": "search1", "B": "search2"}`). The first `input_json_delta` for tool A correctly routes to A (it's the last key). But the next `input_json_delta` for tool A arrives AFTER a delta for tool B was processed, so `keys.lastOrNull()` is now "B" — the A delta gets routed to B. The agentic loop's `toolCallArgs` map ends up with A's arguments under key B and vice versa. The tool calls execute with the wrong arguments.

**Fix:** Change line 133 to use insertion-ordered `LinkedHashMap` (currently `mutableMapOf` is already LinkedHashMap by default, so insertion order IS preserved — but `.keys.lastOrNull()` doesn't pick "the most recent tool that has not yet seen its delta"). Better: track per-id delta state. The cleanest fix is to pass `tc.id` through from the provider (the `BrainChunk.fromProvider` already supports it — line 122-130 checks `tc.id.isNotEmpty()`). For Anthropic, the `id` field in `input_json_delta` is empty, but `pendingByIndex` already resolves it to the right id. The Brain needs to be passed that resolved id. Currently `AnthropicProvider.content_block_delta` (line 188-191) resolves the id from `pendingByIndex[index]`, but then `Brain.fromProvider` (line 133) throws away that id and re-derives from `nameById.keys.lastOrNull()`. The fix: when the resolved id is non-empty, use it directly. Add a new `BrainChunk.ToolCallDelta` variant that carries the resolved id from the provider, or have the provider skip emitting when the id is empty (and rely on the next `content_block_start` to re-emit the ToolCallStart with the id).

---

### F3. [P1] `TasteEngine.recomputeProfile` bucket fix breaks `getTasteContext()` rendering — output now says "tone:concise" instead of "concise"
**Files:**
- `aura-core/src/main/kotlin/com/aura/taste/TasteEngine.kt:147-153` (the fix)
- `aura-core/src/main/kotlin/com/aura/taste/TasteEngine.kt:223-240` (the broken consumer)

The 07-25 fix (line 147-153) changed aggregation from `attrs[value] = current + signal.weight` to:
```kotlin
val bucket = "$key:$value"
val current = attrs.getOrDefault(bucket, 0f)
attrs[bucket] = current + signal.weight
```
This is correct for the aggregation goal (distinguishing `tone:concise` from `style:concise`). But `getTasteContext()` (line 230-237) uses the inner keys directly in the system prompt:
```kotlin
val top = categoryAttrs.entries
    .sortedByDescending { it.value }
    .take(3)
    .joinToString(", ") { (k, _) -> k }   // ← k is now "tone:concise", not "concise"
lines.add("- $category: prefers $top")
```

**Before the fix:** output was `- writing: prefers concise, warm, friendly`.
**After the fix:** output is `- writing: prefers tone:concise, tone:warm, style:concise`.

The model now sees `tone:concise` as a "preference" — meaningless noise that the model may try to literally obey. **This bug fires on every chat turn for any user with a non-empty taste profile.**

**Impact:** Every chat with an established taste profile injects a confusing instruction into the system prompt. The model may try to literally adopt `tone:concise` as a writing style (e.g., prepend "tone:concise" to responses, or write in a different voice than the user actually prefers).

**Fix:** Either (a) at the writer side, change the bucket format to avoid the colon — e.g. `bucket = "${key}__${value}"` and split on `__` at the reader side, or (b) at the reader side, split the composite key back: `val parts = k.split(":", limit = 2); "${parts.getOrNull(0) ?: "value"}: ${parts.getOrNull(1) ?: k}"`. The cleanest fix is to store the bucket as a structured `(key, value)` pair in a typed map (e.g., `Map<Pair<String, String>, Float>` or a small `data class Bucket(val key: String, val value: String)`), and only stringify at output time.

---

### F4. [P1] `DelegateToAgentTool` child `ToolContext` inherits parent's `userMessage` — child REMOTE_COST tools cannot be approved
**File:** `aura-core/src/main/kotlin/com/aura/tools/DelegateToAgentTool.kt:201-208`
```kotlin
val childCtx = ctx.copy(
    conversationId = "delegation:${agent.name}",
    timeout = 30_000L,
)
```
**What changed in recent review:** R5-6 documented this. The 07-25 review did not touch `DelegateToAgentTool`. The `ctx.copy(...)` retains the parent's `userMessage`.

**Impact:** `RemoteCostApprovalGate.authorize` (ToolExecutor.kt:178-194) compares `context.userMessage` to the prior `pending[key].requestingMessage`. For a delegated agent, the child's `userMessage` is the parent's last message (e.g., "research the impact of X on Y"). When the child then wants to call a REMOTE_COST tool (e.g., brave_search), the gate sees `context.userMessage == existing.requestingMessage` (the parent's message that triggered the delegation). The check `context.userMessage != existing.requestingMessage` (line 188) fails — the child is **never approved**, regardless of how the user responds to the parent.

User-facing: a delegated researcher agent that needs to do paid web research will fail with a "May consume paid API credits. Reply with explicit confirmation" error every time, even if the user said "yes, go ahead" to the parent. The child can't see the user's reply because the parent's `userMessage` is frozen at the parent's last user turn.

**Fix:** `childCtx = ctx.copy(conversationId = "delegation:${agent.name}", timeout = 30_000L, userMessage = "delegate:$agentName: $task")` — the child's `userMessage` should be the `task` argument passed to `delegate_to_agent`, not the parent's last user message. Also consider resetting `approvedRemoteCostTools = emptySet()` so the child never inherits per-run approvals from the parent.

---

### F5. [P1] `ProactiveScheduler.scheduleDream()` removed `setRequiresCharging(true)` constraint — dream worker now runs on battery
**File:** `aura-core/src/main/kotlin/com/aura/proactive/ProactiveScheduler.kt:90-95`
```kotlin
// Before (HEAD~1):
fun scheduleDream() {
    val constraints = Constraints.Builder()
        .setRequiresBatteryNotLow(true)
        .setRequiresCharging(true)   // ← removed
        .build()
// After (HEAD 40f5ca68):
fun scheduleDream() {
    val constraints = Constraints.Builder()
        .setRequiresBatteryNotLow(true)
        // setRequiresCharging(true) removed
        .build()
```
**What changed in recent review:** The 07-25 review removed the `setRequiresCharging(true)` constraint as part of the DaemonScheduler KDoc fix. The diff shows it's in `ProactiveScheduler.kt`, not `DaemonScheduler.kt`. **This is a real regression** — the dream worker is a heavy 9-phase process that runs an LLM call (per `DreamConsolidator.updateProfileFromConsolidated` calling `userProfileStore.update()` plus 8 other phases). Running this on a phone on battery with the screen off will drain the battery.

**Impact:** A user with the dream worker scheduled daily will see a battery hit at the configured time even if the phone is unplugged. For users with `dreamEnabled = true` (the default), this is every day.

**Fix:** Restore `.setRequiresCharging(true)`. The previous constraint was correct — dream work is heavy, runs the LLM, and the user opted in to the dream cycle, not to running it on battery.

---

### F6. [P1] `HandRepository.SECRET_NAME_PATTERN` false-positives on common English words — hand history loses `author`, `authority`, `oauth` etc. as "[redacted]"
**File:** `aura-core/src/main/kotlin/com/aura/hands/HandRepository.kt:301`
```kotlin
private val SECRET_NAME_PATTERN = Regex("token|secret|password|api.?key|credential|bearer|auth|\\bkey\\b|client.?secret|private.?key|access.?key", RegexOption.IGNORE_CASE)
```
**What changed in recent review:** The pattern was extended from 4 keywords (`token|secret|password|api.?key`) to 11 keywords, including bare `auth` and `\bkey\b`.

**Impact:** Confirmed false positives (verified with Python regex simulation):
- `author`, `authority`, `authorname`, `author_id`, `authentic`, `authorial`, `authored`, `coauthor` → all REDACTED (because `auth` matches as substring).
- `oauth`, `oauth_token`, `auth_user`, `userauth`, `authcode` → all REDACTED.
- More importantly: `OPENAI_KEY` (one of the most common env var names) is NOT redacted because `\bkey\b` doesn't match around underscores (word boundary on the underscore side).

The `redactedVariablesJson` function (line 292-297) uses this pattern to redact variable VALUES in the recorded hand history. So a user with a hand that uses `{{author}}` or `{{authority}}` as a template variable will see `[redacted]` in the run history — **silent data loss** in the audit trail.

**Fix:** Require `auth` to be at a word boundary AND followed by a credential suffix, e.g., `auth.?token|auth.?code|auth.?key|auth.?secret|auth.?password` instead of bare `auth`. Same for `key` — use `key` with `_(token|secret|password|key|credential)` requirement. Better: anchor the whole pattern with `\b` at the start so `author` (where `auth` is followed by `or`) doesn't match: `\\b(token|secret|password|credential|bearer|api.?key|client.?secret|private.?key|access.?key)\\b` plus a separate check for `auth_xxx` or `auth-token` style names. Also fix `\bkey\b` — drop it in favor of `key` only when it appears as `KEY` env var: `[A-Z]+_KEY` (which catches `OPENAI_KEY`, `ANTHROPIC_KEY`, etc.).

---

### F7. [P1] `ConversationCompactor` calls `provider.listModels()` for every configured provider on every compaction — 3x-5x network round-trips
**File:** `aura-core/src/main/kotlin/com/aura/agent/ConversationCompactor.kt:32-45`
```kotlin
val compactModel = runCatching {
    val providers = providerRegistry.configured()
    if (model.startsWith("moa:")) {
        val firstProvider = providers.firstOrNull()
        val firstModel = firstProvider?.listModels()?.firstOrNull()  // network call
        ...
    } else {
        val candidates = providers.flatMap { p ->
            p.listModels().map { m -> "${p.prefix}:$m" }  // N network calls
        }.filter { it != model && !it.startsWith("moa:") }
        candidates.minByOrNull { it.substringAfter(":").length } ?: model
    }
}.getOrDefault(model)
```
**What changed in recent review:** The 07-25 review extended the compactor to also pick a cheap model from any configured provider (was: only for MoA fallback). The new code calls `p.listModels()` for **every configured provider** (line 41-42). For a user with OpenAI + Anthropic + Ollama configured, this is 3 sequential network round-trips on **every compactor invocation** (which happens once per long conversation, but the cost compounds).

**Impact:** Latency. Each `listModels()` call is 100-500ms (HTTPS round-trip to `/v1/models`). 3 providers = 300-1500ms added to compactor latency. The previous behavior (only fetch the first provider) was already wasteful for MoA fallback but is now a forced 3-way hit for everyone.

**Fix:** Cache the result of `listModels()` in `providerRegistry` or in a `ModelCatalogCache` (which already exists per `ProviderModule.kt:21-22` but is for a different purpose). Alternatively, read from the same cache the agentic loop uses (`ModelCatalogRepository.catalog.value.allModels`) instead of re-fetching. The simplest fix: take a `ModelCatalogRepository` dependency and use `catalog.allModels` directly.

---

### F8. [P1] `McpToolBridge.syncToolsUnprefixed` does not prune tools on disconnect — stale tools remain callable (R5-7 still open)
**File:** `aura-core/src/main/kotlin/com/aura/mcp/McpToolBridge.kt:139-180`
**What changed in recent review:** Nothing — R5-7 was not addressed. The 07-25 review fixed `syncTools` (line 56-128) but `syncToolsUnprefixed` (line 139-180) is the second code path and has no `staleNames` cleanup.

**Impact:** When a user disconnects an MCP server (or it crashes), tools registered with bare names via `syncToolsUnprefixed` (e.g., `tavily_search` from a Tavily MCP server to override the native tool) remain in the `ToolRegistry`. The agentic loop can still see them in `registry.definitions()`, the policy engine evaluates them with their MCP-set risk, and the model can call them. The call fails with "MCP server X not connected" instead of "tool not found" — the model wastes a turn retrying.

The fix from R5-7 stands: add `staleNames` pruning at the top of `syncToolsUnprefixed` mirroring lines 60-80 of `syncTools`.

**Fix:**
```kotlin
suspend fun syncToolsUnprefixed(servers: List<McpServerConfig>) {
    val currentServerIds = servers.map { it.id }.toSet()
    val connectedServerIds = mcpClientManager.connectedServerIds()
    val stale = registeredNames.filter { name ->
        // Unprefixed tools are owned by a single server; derive it from the
        // call site (we know which config registered this name, but the
        // simpler approach is to track a parallel map of name → serverId).
        ...
    }
    ...
}
```
The implementation requires a parallel `registeredNameToServerId` map (currently only `registeredNames: Set<String>` exists). Either add the map, or have `syncToolsUnprefixed` track its own set and call `unregister` for any name whose server is gone.

---

### F9. [P1] `MemoryAugmentedAgenticLoop.extractProfileFromText` runs on assistant text — can persist hallucinated facts as user facts
**File:** `aura-core/src/main/kotlin/com/aura/agent/MemoryAugmentedAgenticLoop.kt:867-871`
```kotlin
val lastAssistant = currentConversation.turns.lastOrNull()?.assistant
if (memoryEnabled && !lastAssistant.isNullOrBlank()) {
    runCatching { extractProfileFromText(lastAssistant) }
        .onFailure { ... }
}
```
**What changed in recent review:** Nothing — R5-20 was not addressed. The 07-25 review did not touch this code.

**Impact:** The assistant may say "I noticed you live in Paris" or "Sounds like you prefer dark mode" — these are the model's echo, not the user's claim. The regex `(?:i (?:live|am|work) (?:in|at|from))\s+([A-Z][a-zA-Z\s,]+?)(?:\.|,|...)` doesn't fire on "you live in Paris" (the regex requires first-person "I"), so the assistant-text extraction is mostly safe for the location regex. BUT the `i'm|i am|call me` regex (line 900) **will** match the assistant's "I'm an AI assistant" or "I am Claude" — if the model says "I am Claude" anywhere, `userProfileStore.update(name = "Claude")` is called. Real bug.

**Fix:** Either (a) only run `extractProfileFromText` on user text, or (b) add a `claimedBy: String` parameter to `mergeFacts` and only persist user-claimed facts. The cleanest is (a) — drop the second `runCatching { extractProfileFromText(lastAssistant) }` block entirely. The first-person echo from the assistant is not a reliable signal of user fact.

---

### F10. [P2] `HttpFileReadTool` `source.request(maxBytes + 1L)` blocks for up to 120s if server streams without Content-Length and never EOFs
**File:** `aura-core/src/main/kotlin/com/aura/tools/HttpFileReadTool.kt:63-73`
```kotlin
val maxBytes = maxChars * 4L
val source = resp.body?.source() ?: return@Tool ToolResult.Ok("")
source.request(maxBytes + 1L)  // ← blocks until maxBytes+1 bytes buffered OR EOF
val bodyBytes = if (source.buffer.size > maxBytes) {
    source.readByteArray(maxBytes)
} else {
    source.readByteArray()
}
```
**What changed in recent review:** The 07-25 review fixed the OOM by replacing `resp.body?.bytes()` with `source.request() + readByteArray()`. The streaming is correct for content-length responses. But for chunked transfer encoding (no Content-Length) and an upstream that doesn't EOF promptly, `source.request(byteCount)` blocks until either `byteCount` bytes are buffered OR the upstream returns EOF. With a misbehaving server (or a streaming endpoint that never closes), the only backstop is the OkHttp `readTimeout` of 120 seconds (ProviderModule.kt:38).

**Impact:** A user reading a 100GB streaming endpoint gets a 120s hang before timeout. The whole agent loop is blocked on this tool call. The `withTimeout(ctx.timeout)` wrapper in ToolExecutor is per-tool timeout (default 30s per the DelegateToAgentTool child context, default 30s for normal loop tools) but `withTimeout` doesn't cancel OkHttp's blocking read on the IO thread — it just stops the wait on the Future.

**Fix:** Cap the body size at a hard ceiling (e.g., 32 MB) instead of `maxChars * 4L`. The current `maxChars` is bounded at 32000 (line 54), so `maxBytes = 128_000` (128 KB) for the cap. If a server streams more than 128KB without EOF, the call hangs for 120s. Better: pass an `okio.Timeout` to `source.timeout().timeout(10_000, MILLISECONDS)` so `source.request()` itself times out after 10s.

---

### F11. [P2] `HttpFileReadTool` imports `okio.buffer` but never uses it — dead import
**File:** `aura-core/src/main/kotlin/com/aura/tools/HttpFileReadTool.kt:13`
The 07-25 review added the `okio.buffer` import when introducing the `source.request()` approach but the buffer extension is never called.

**Fix:** Remove `import okio.buffer` from the imports block.

---

### F12. [P2] `Provider.chat` `STREAM_READ_TIMEOUT_MS = 5 * 60 * 1000` is hard-coded — same value across all 5 OpenAI-compat providers
**Files:**
- `aura-core/src/main/kotlin/com/aura/providers/OpenAiCompatProvider.kt:249`
- `aura-core/src/main/kotlin/com/aura/providers/CustomOpenAiCompatProvider.kt:267` (same constant)
- `aura-core/src/main/kotlin/com/aura/providers/ChatGptSubscriptionProvider.kt:166`
- (also AnthropicProvider and GeminiProvider may have similar — verified R5)

**What changed in recent review:** Nothing — R5-13 was not addressed.

**Impact:** A user with a `max_tokens = 100` request on a fast model (e.g., Groq Llama 8B) that should finish in 200ms has a 5-minute ceiling. If the model is stuck in a reasoning loop or the server has a bug, the user waits 5 minutes for the timeout to fire. For a `max_tokens = 4096` request on a slow model, 5 minutes is correct.

**Fix:** Derive the timeout from `options.maxTokens`: `max(60_000L, options.maxTokens?.let { it * 1000L / 50 } ?: 300_000L)`. A 50 tokens/sec assumption is conservative; for a 100-token request, 2000ms is enough. The 60s floor protects against very small requests stalling on server-side.

---

## Already-fixed (mentioned for completeness; NOT new findings)

These were fixed by the 07-25 review and verified during this audit:
- HttpFileReadTool OOM (P0) — streaming fix is correct for Content-Length responses.
- EmotionEngine persistence (P0) — save/load wired into loop + bootstrap (but see F1 above for thread-safety regression).
- SettingsViewModel dead flows (P0) — emotion + daemon thoughts now wired.
- TasteEngine aggregation bucket (P1) — fix is correct at the writer; consumer needs the matching fix in F3.
- BackupManager memory_feedback purge (P1) — `memoryFeedbackDao.deleteAll()` added.
- ChatViewModel NetworkCallback leak (P2) — unregister in onCleared.
- isolatedSessionRequested reset (P2) — reset in newConversation.
- TimerTool unbounded (P2) — MAX_TIMERS=100 FIFO eviction.
- UseSkillTool risk annotation (P1) — both annotation and KDoc say READ_ONLY.
- SmsSendTool validation (P1) — 7-15 digit regex check.
- RunHandTool fallback (P1) — falls back to error, not direct repo.run().
- MemoryAugmentedAgenticLoop A1-A5 — all 5 prior round-4 findings addressed.
- McpToolBridge.syncTools pruning (E1) — works for prefixed names (F8 is the unprefixed gap).
- McpConnection tools/list body cap (E2) — 2 MB cap.
- McpConnection._health volatile (E3) — @Volatile.
- ConversationCompactor threshold per-model (D4) — 80% of actual context window.
- ConversationCompactor partial error (D5) — rethrown on error.

## Still-open from prior audits (mentioned for completeness; NOT new findings, but worth tracking)

These were documented in ROUND5 but not addressed by the 07-25 review pass:
- R5-4 (P1) `ToolResult.NeedsApproval` → AgentEvent.ToolResult has no typed field; ChatSendController string-matches `"Approval needed: "` (ToolRegistry.kt:977, ChatSendController.kt:284-288).
- R5-5 (P1) Compactor doesn't use the cheap-model heuristic when user model is GPT-4/Opus (now PARTIALLY addressed by F7 — but the cheap-model heuristic is now even more wasteful because of the multi-provider fan-out).
- R5-9 (P1) McpConnection 2 MB body cap applies to ALL requests including `tools/call`, not just metadata.
- R5-17 (P2) ProviderRegistry usage tracker overwrites the first usage chunk with the second — prompt_tokens may be lost.
- R5-19 (P2) ConversationCompactor `RECENT_TURNS_TO_KEEP = 24` doesn't scale for small-context models.
- R5-21 (P2) `cachedCheapModel` uses Hilt-injection order, not user preference.
- R5-25 (P2) WebSearchCapabilityTool risk is `READ_ONLY` but routes to paid providers.
- R5-29 (P2) CapabilityRouter doesn't consult user preference.

## Verification log

All 12 findings verified by:
1. Reading the diff between `34cf9e1d^` and `40f5ca68` (32 files, +3246/-69 lines).
2. Reading the current source of each cited file: `EmotionEngine.kt`, `Brain.kt`, `TasteEngine.kt`, `DelegateToAgentTool.kt`, `ProactiveScheduler.kt`, `HandRepository.kt`, `ConversationCompactor.kt`, `McpToolBridge.kt`, `MemoryAugmentedAgenticLoop.kt`, `HttpFileReadTool.kt`, `OpenAiCompatProvider.kt`.
3. Cross-referencing the previous audits to confirm "already fixed" vs "still open".
4. Python regex simulation of `SECRET_NAME_PATTERN` to enumerate false positives.
5. Static analysis of the `Brain.fromProvider` flow for parallel tool-call routing.

No code was modified. No tests were run (verification was by code reading; the audit was scoped to finding bugs, not fixing them).

## Top three to fix first

1. **F1 (P0) EmotionEngine thread safety** — silent emotion state loss affects every chat turn once the Settings screen is open while the agentic loop is running. The 07-25 review wired emotion into two new call sites without addressing the underlying lack of synchronization. Easy fix (Mutex or @Volatile + AtomicReference).
2. **F3 (P1) TasteEngine getTasteContext broken rendering** — affects every chat turn for any user with a taste profile. The 07-25 review's fix was correct at the writer but missed the matching fix at the reader. Trivial fix (split the composite key on `:`, or change the bucket delimiter).
3. **F5 (P1) ProactiveScheduler removed `setRequiresCharging(true)`** — battery drain regression that the 07-25 review introduced as a side effect of the DaemonScheduler KDoc cleanup. One-line fix: restore the constraint.
