# ROUND 15 — Deep Audit: Agent Loop, Tools, Providers, MCP

**Scope:** `MemoryAugmentedAgenticLoop.kt` (1218L), `Brain.kt` (234L),
`ToolExecutor.kt` (246L), `ToolRegistry.kt` (97L), all 17 provider files
under `aura-core/src/main/kotlin/com/aura/providers/`, the four MCP files
(`McpClientManager.kt`, `McpConnection.kt`, `McpToolBridge.kt`, `McpModels.kt`),
plus `ProviderKeys.kt`, `ProviderRegistry.kt`, and the `RemoteCostApprovalGate`
inner class.

**Context:** Rounds 1–14 have already landed (see `.hermes/audits/AUDIT_*.md`).
This round targets structural bugs, wiring gaps, and edge cases that earlier
sweeps skipped. Severity is **P0** (data loss / security boundary / silent
failure in normal flow), **P1** (correctness bug that surfaces in normal use),
or **P2** (maintainability / defense-in-depth).

---

## A. MemoryAugmentedAgenticLoop.kt

### A1. [P1] Failover replays the same `step` value but trace events are step-scoped, not attempt-scoped
**File:** `aura-core/src/main/kotlin/com/aura/agent/MemoryAugmentedAgenticLoop.kt:356-393, 686-772`

The outer loop increments `step` at the top, then enters the inner
`stream@ while (true) { … }` block (L697) which is the failover loop. The
comment at L354-355 says "the inner failover loop retries the SAME step with a
different provider without consuming another slot" — that part is correct.
**But** the trace events (`STEP_STARTED`, `TOOL_CALL`, `TOOL_RESULT`,
`PROVIDER_FAILOVER`) are all emitted with the *outer* `step` value, so a step
that took 4 failover attempts emits one `STEP_STARTED` and one
`PROVIDER_FAILOVER` but no per-attempt breadcrumb. The trace post-mortem can
show the final successful provider without revealing how many attempts it took
or which intermediate providers failed with what error.

**Fix:** Add `var attempt = 0` inside the outer step and increment on each
failover. Stamp `stepId = "step_${step}_attempt_${attempt}"` on every
trace event. Emit a `TraceEventType.PROVIDER_FAILOVER` *per attempt* (not once
on the final try).

---

### A2. [P1] `flow {}` body does not call `coroutineContext.ensureActive()` until after step 1 work
**File:** `MemoryAugmentedAgenticLoop.kt:390-393`

`ensureActive()` is invoked at the top of the loop *after* `step += 1`. If a
caller cancels the collecting coroutine between `run()` returning the `Flow`
and the first collector arriving, the flow still runs the entire body of step
1 (memory recall, embedding, RRF rank, prompt build) before bailing at the
first `ensureActive()`. That's a real cost: a typical recall is one embedder
hop + one RRF rank + one Room query. On a 200ms-embedding provider this is a
visible hang during cancellation.

**Fix:** Add `coroutineContext.ensureActive()` as the first line of the
`flow {}` body, before any work.

---

### A3. [P1] `pendingPermissions` map holds an entire `Conversation` snapshot indefinitely
**File:** `MemoryAugmentedAgenticLoop.kt:101, 109-126`

`PendingPermission` (L109-126) carries `val conversation: Conversation` by
value. A long conversation can be tens of KB. The map is only cleaned on
`resumeAfterPermission` or `denyPendingPermission`. If the user backgrounds
the app while the dialog is open and then never resumes, the snapshot lives
until process death. Worse, `resumeAfterPermission` (L169-253) replays the
held tool against the *snapshot*'s `memoryEnabled` and
`approvedRemoteCostTools` without re-reading the canonical store; if the user
toggled incognito or revoked approval between the pause and the resume, the
held tool runs with the wrong privacy context.

**Fix:** (1) Add a `createdAt: Long` and evict entries older than ~15 min
(via a periodic GC or on next `run()` call). (2) On `resumeAfterPermission`,
re-read `memoryEnabled` and `approvedRemoteCostTools` from their canonical
store rather than trusting the snapshot.

---

### A4. [P1] `denyPendingPermission` does not surface the denial to the suspended `run()` collector
**File:** `MemoryAugmentedAgenticLoop.kt:140-150, 868-870`

`deny` (L140-150) removes the entry and logs. The original `run()` flow
suspended in the loop after emitting `PermissionRequested` and `finished = true;
break` (L868-869). The coroutine has no way to know the user denied. The
collecting UI (Compose `ChatViewModel`) is left waiting for the next event and
will see… nothing. The conversation is effectively abandoned mid-tool.

Compare to `resumeAfterPermission` which returns a fresh `Flow<AgentEvent>` —
the deny path needs an equivalent so the UI can keep the conversation alive
(e.g. inject a "denied" message into the turn and continue the loop without
the held tool).

**Fix:** Either (a) return a denial `Flow` from `denyPendingPermission` (mirror
`resumeAfterPermission`), or (b) have the original `run()` flow listen on a
`Channel`/`SharedFlow` that the deny path publishes to.

---

### A5. [P1] `filterSearchTools` is called once at run start, not per-step
**File:** `MemoryAugmentedAgenticLoop.kt:352, 1140-1148`

`filterSearchTools(allTools)` (L352) hides tools whose API key is missing.
The user can add/remove keys (Tavily, Brave) mid-conversation via Settings.
If a tool was hidden at step 1 because the key was missing and the user adds
the key before step 3, the tool is still invisible to the model for the rest
of the run. The function body (L1140-1148) is also a degenerate filter: it
only excludes `tavily_search` and `brave_search` names — the function is named
"filter search tools that need an API key the user hasn't configured" but
doesn't actually check `providerKeys.isConfigured(...)` at all.

**Fix:** Either make `filterSearchTools` actually consult `providerKeys`, or
re-run it before each `Brain.think()` call (cache invalidation on key writes).

---

### A6. [P1] `lastRecall` is overwritten only when `memoryEnabled` is true; cleared on `memoryEnabled=false`
**File:** `MemoryAugmentedAgenticLoop.kt:385, 492-503`

`lastRecall` is a var on the run scope. It's set inside the `if (memoryEnabled)`
block at L492-503. When `memoryEnabled` is true at step 1 then the user toggles
incognito mid-loop (impossible today via API but possible if a setting
listener fires), the var is never reset to null and the chip will continue
to show a stale recall. More importantly, the function at L1070-1072:

```kotlin
val finalConv = if (lastRecall != null) {
    modeledConversation.attachRecallToLastTurn(lastRecall)
} else modeledConversation
```

attaches the *most recent* recall (across all steps) to the final turn. This
is the intended behavior per the comment at L378-385, but `lastRecall` is
only *set* on steps that hit the `if (memoryEnabled)` block; if step 1 ran
with `memoryEnabled=true` and recorded a recall, then `memoryEnabled` flipped
to `false` (e.g. via the `memoryEnabled` parameter in `ToolContext`), the
recall from step 1 is still attached even though the user is in incognito.

**Fix:** Recompute `lastRecall` defensively (don't trust the var; always
re-derive from the active options) or reset it when `memoryEnabled` flips
to `false`.

---

### A7. [P2] `cachedRecall` key ignores `recallLimit`
**File:** `MemoryAugmentedAgenticLoop.kt:367, 429-430`

The cache key is `Triple(lastUserMessage, agentId, hits)`. If the loop is
ever invoked with different `recallLimit` values for the same
`(message, agentId)`, the cache returns stale-size results. Currently
`recallLimit` is constant per run, so the bug is latent — but flag it for
when the loop is reused across calls.

---

### A8. [P2] `cachedCheapModel` failure path stores `null`, which re-triggers the network every step
**File:** `MemoryAugmentedAgenticLoop.kt:448-465`

`runCatching { … }.getOrNull()` returns `null` on failure; the
`if (cachedCheapModel != null) … else …` re-runs the call. If the catalog
endpoint is down, every step's first recall will re-attempt the network call.
**Fix:** Use a separate `cheapModelAttempted: Boolean` so failures are
short-circuited.

---

### A9. [P0 → RESOLVED] Planning step does have a 15s timeout
**File:** `MemoryAugmentedAgenticLoop.kt:641-668`

`runCatching { … kotlinx.coroutines.withTimeoutOrNull(15_000L) { … } }` at
L658 does cap the planning call. Confirmed wired correctly. No action.

---

### A10. [P1] `MOA` model resolution in LLM write gate can deadlock when no non-MoA provider exists
**File:** `MemoryAugmentedAgenticLoop.kt:939-949`

`gateModel = if (model.startsWith("moa:")) { … .firstOrNull { it.providerPrefix != "moa" }?.id ?: return@runCatching } else { model }`

If the user has *only* MoA configured (no other providers), the gate silently
returns without evaluating, and the heuristic gate is also wrapped inside
`LlmWriteGate` — meaning no write decisions are ever made and the
`store()` call never happens. Symptom: incognito toggle is the only way to
avoid the write; otherwise writes silently fail. Verify in tests.

**Fix:** Add a log warning when the gate is bypassed, and fall through to
the MoA primary if that's all that exists.

---

### A11. [P1] Profile extraction in `if (!regexFound && llmProfileExtractor != null && lastUserMessage.length > 15)` runs on the *user* message but the LLM call's 200-token max is too small to extract *all* traits
**File:** `MemoryAugmentedAgenticLoop.kt:975-995`

The cap is 200 tokens but a chatty user can say "I use Vim, prefer dark mode,
am allergic to peanuts, and live in Brooklyn" in one sentence. The
extraction is silently truncated. This is a real product issue: a 4-fact
sentence returns 1-2 facts and the rest are lost. Not a bug per se, but
worth flagging because the `> 15` length threshold invites long sentences.

**Fix:** Bump to 400 tokens, or chunk the message and call the extractor in
a loop with the running extraction as context.

---

## B. Brain.kt

### B1. [P1] `BrainChunk.fromProvider` final fallback (`nameById.keys.lastOrNull()`) mis-routes parallel deltas
**File:** `aura-core/src/main/kotlin/com/aura/agent/Brain.kt:184-228`

L226 — `val id = nameById.keys.lastOrNull() ?: return Text("")` — is the
"last-resort fallback for providers that emit a delta with no id and no
name". It is *only* used by legacy /v1/chat/completions providers. The
comment at L221-225 says so. But a provider that *partially* fills in ids
(e.g. first delta has id, subsequent deltas have no id and no name) will
have all subsequent deltas mis-routed to the most recent id, conflating
two parallel tool calls. The `nameById` LRU (L114-118) is bounded to
`MAX_NAME_BY_ID = 32` (L137), so in practice the map holds multiple keys
and the "last" key is whichever was *most recently accessed*. The LRU is
*access-ordered*, so any read to the active tool's id promotes it; that
makes the fallback correctly pick the active id in most cases. **But** if
two tools are streaming in true parallel (no read access to the second
between deltas), the fallback will always pick whichever was read last
in the loop — which is deterministic only for the order in which deltas
arrive, not for the order in which the model intended.

**Fix:** Drop the fallback entirely and require providers to tag deltas with
ids. Or stamp the last *complete* id, not the last *accessed* id.

---

### B2. [P1] Anthropic thinking budget not enforced as a hard cap
**File:** `Brain.kt:71-103, AnthropicProvider.kt:71-78`

Brain L97 inflates `maxTokens` to `budget + 24_576` when the caller did not
set explicit `maxTokens`. AnthropicProvider then sends both `thinking`
(with `budget_tokens`) and `max_tokens` (= budget + 24_576). Anthropic will
spend up to `budget_tokens` on thinking; if the model thinks more than the
budget, the response is cut off. That's intended. **However**, when the
caller *did* set `maxTokens = 200` (auxiliary callers like
`LlmProfileExtractor` at L987), Brain correctly does NOT inflate — but the
`thinking_budget` from the user preference is still 32000 by default, and
Anthropic's `thinking.budget_tokens` is sent as 32000 with `max_tokens = 200`.
Anthropic will reject this with "max_tokens must be > budget_tokens". The
fix is to clamp `thinkingBudget` to be smaller than `maxTokens - 1` when
the caller did set `maxTokens`.

**Fix:** In Brain.kt L96-101, also clamp `budget` to
`min(budget, (resolvedOptions.maxTokens ?: 0) - 1)` when the caller set
`maxTokens`.

---

### B3. [P1] Model fallback in `providerRegistry.parse` is silent on unknown prefix
**File:** `ProviderRegistry.kt:24-32`

`parse(modelId)` (L24-32) throws `IllegalArgumentException` on unknown
prefix. The Brain calls `providerRegistry.chat(model, …)` (L57) which
calls `parse`. If the user's saved "default model" preference is a
provider they later removed (e.g. uninstalled the OpenAI key and forgot
to switch defaults), the very first chat call throws an uncatchable
exception that bubbles up through `runBlocking`-style code. There is no
fallback to the first configured provider.

**Fix:** In `Brain.stream` (or upstream), if `parse` throws, fall back to
the first provider in `providerRegistry.configured()` and log the
substitution.

---

## C. ToolExecutor.kt

### C1. [P1] `withTimeout` + `runInterruptible` chain does not interrupt truly blocking I/O
**File:** `aura-core/src/main/kotlin/com/aura/agent/ToolExecutor.kt:127-148`

`runInterruptible(toolDispatcher) { runBlocking { tool.execute(call, ctx) } }`
inside `withTimeout(ctx.timeout)`. The comment at L132-135 is correct that
`runInterruptible` interrupts the thread on timeout, *but* `Thread.interrupt()`
only sets a flag — it does not preempt a `Thread.sleep` or a synchronous
`okhttp3.Call.execute()` that has already been initiated. A tool that
issues a long HTTP request and then does `Thread.sleep(60_000)` will run
for the full 60s even after the timeout fires, because the
`TimeoutCancellationException` from `withTimeout` cannot preempt
`Thread.sleep`. The visible failure: the tool "times out" but the next
tool call is blocked behind it in the same `Dispatchers.IO.limitedParallelism(8)`
pool. With 8 stuck tools, the app stalls.

**Fix:** Switch tools to be fully coroutine-based (suspend `OkHttp`) and
drop `runBlocking` + `runInterruptible`. Until then, document the
limitation and use a stricter timeout (e.g. 10s default) so the worst-case
blocking is bounded.

---

### C2. [P1] `RemoteCostApprovalGate` only fires when `policyEngine == null`
**File:** `ToolExecutor.kt:117-123`

```kotlin
if (policyEngine == null && tool.risk == ToolRisk.REMOTE_COST && name !in ctx.approvedRemoteCostTools) {
    remoteCostApprovalGate.authorize(name, args, ctx)?.let { rationale ->
        return ToolResult.NeedsApproval(rationale)
    }
}
```

The gate is a *fallback* for when no policy engine is configured. In
production (Hilt-injected `PolicyEngine`), the gate is dead code — the
policy engine handles REMOTE_COST at L77-91. **But** the policy engine's
REMOTE_COST handling is at `PolicyEngine.kt:48`:

```kotlin
if (tool.risk == ToolRisk.REMOTE_COST && tool.name !in ctx.approvedRemoteCostTools) {
    return PolicyResult.NeedsApproval("…")
}
```

This returns `NeedsApproval` from the *policy* layer, which the executor
maps to `ToolResult.NeedsApproval` (L84-85). But `NeedsApproval` is *not*
the same as `RemoteCostApprovalGate`'s confirmation: the gate requires
the *next* user message to contain an explicit "yes"/"confirm" string
(L219-225 of ToolExecutor), while the policy engine just returns
"NeedsApproval" once and the user is expected to react. **The
`RemoteCostApprovalGate`'s string-matching confirmation logic is wired to
the fallback path that production never takes.** This is a real
behavior gap: production policy-NEEDS_APPROVAL tools only get a single
approval prompt; the gate's two-step "ask → wait for explicit yes" flow
is dead.

**Fix:** Either (a) make the policy engine's REMOTE_COST path delegate to
`RemoteCostApprovalGate.authorize`, or (b) document the dead-code path
and remove the gate (and its unit tests).

---

### C3. [P1] `isExplicitApproval` matching is too restrictive and locale-unaware
**File:** `ToolExecutor.kt:230-244`

L230-237: only matches a fixed set of English words, lowercased, with
all non-alphanumeric characters stripped. A user who replies "Yes!" or
"Yes." or "Yes please" will match, but "yeah", "yep", "do it please",
"si" (Spanish), "ja" (German), or "ok" do not. More importantly, the
`Context.userMessage` is the *latest user-authored message* — if the
user is mid-conversation and replies "yes, do that" to a different
question, the gate will trigger on the wrong approval. The docstring
at L191-193 says "the model cannot approve its own call by repeating it
in the same turn" but the gate can't tell which turn the "yes" was for.

**Fix:** Track approval per-tool-request (already done via
`Pending.arguments` — the gate re-prompts if args differ), but also
expose the *current* request to the user so the UI can show "Allow
`web_search` for this query?" rather than a generic prompt.

---

### C4. [P2] `parseArgs` discards unknown schema properties silently
**File:** `ToolExecutor.kt:167-175`

`for ((k, prop) in schema.properties) { val v = obj[k] ?: continue; out[k] = coerce(v, prop) }`
only emits keys that are in the schema. If the model emits an extra key
(e.g. a deprecated parameter), the user sees a tool call execute with
fewer arguments than the model intended, with no warning.

**Fix:** When `obj` has keys not in `schema.properties`, log a warning
in dev builds (gated on a build flag).

---

## D. Provider audit (17 providers)

| # | File | Issue | Sev |
|---|---|---|---|
| D-Anthropic | `AnthropicProvider.kt:60-223` | `message_stop` is a no-op (intentional, L203), but if the server emits `message_stop` *without* a preceding `message_delta` (e.g. truncation by proxy), the loop never sees a finish reason and will hang until the read timeout. | P1 |
| D-Anthropic | `AnthropicProvider.kt:119-121` | Non-2xx response emits an error chunk and `return@use`. The outer `withTimeout` and `coroutineScope` still see the response body as a normal `Response` (it has a status), so no further error is thrown — but the SSE `while (true)` loop never runs, so no `Finished` is emitted. The Brain sees only `Error` and exits. The user gets a clear error. **But** if the body is a 2xx with no SSE events at all (rare proxy misconfig), the loop bails on the first `readUtf8Line() ?: break` with no terminal chunk emitted, leading to a "silent truncation" in the loop. | P2 |
| D-Gemini | `GeminiProvider.kt:111-167` | `var sawFinish` (L111) tracks whether the final chunk carried a `finishReason`. If a Gemini response streams multiple candidates' chunks (e.g. multi-candidate prefetch), only the first candidate's `parts` is read (L119). The rest are silently dropped. The model *thinks* it sent multiple candidates, but the loop only ever sees one. | P2 |
| D-Gemini | `GeminiProvider.kt:138` | `fnArgs = fnCall["args"]?.toString() ?: "{}"` calls `.toString()` on the `JsonElement`. `JsonObject.toString()` produces a re-serialized JSON string — but `JsonElement.toString()` returns the *serialized* form, not the original. For nested objects with stable key ordering, this is fine. For objects with non-deterministic key order (e.g. Kotlinx serialization uses insertion order in `JsonObject`), the result is deterministic. **No bug**, but flag for awareness. | P2 |
| D-OpenAI-compat | `OpenAiCompatProvider.kt:84-87` | `channel.trySend(chunk)` (L84) on a `BUFFERED` channel can suspend if the buffer fills. `trySend` is non-blocking — it returns a result. If the buffer is full, `trySend` returns `BufferOverflow` and the chunk is **dropped**. The `onEvent` callback (which runs on OkHttp's dispatcher thread) then keeps going, but the loop is reading slower than the network is delivering. Symptom: missing text deltas, missing tool calls. | P1 |
| D-OpenAI-compat | `OpenAiCompatProvider.kt:99-119` | The `for (chunk in channel) emit(chunk)` loop on L106 is single-consumer. If the upstream `onEvent` is called on a different thread (it is — OkHttp's SSE dispatcher), the `Channel` is the bridge. **But** `EventSource.cancel()` on L116 is called in the `finally` block. If the loop exits via `TimeoutCancellationException` (L108), the `finally` runs and cancels the source — good. If it exits because the channel `close()`s, the `finally` also runs. If it exits because the flow collector is cancelled (UI "stop" button), the `finally` runs. **But** the `sourceHolder.cancel()` on L118 is a no-op when `source` is null (between `EventSources.createFactory()` returning and the first `onEvent` arriving). The brief window where cancel() is called during this gap leaves the actual `EventSource` uncancelled. | P1 |
| D-OpenAI-compat | `OpenAiCompatProvider.kt:65` | The Channel is `kotlinx.coroutines.channels.Channel<ProviderChunk>(capacity = Channel.BUFFERED)`. `Channel.BUFFERED` is the default buffer (64). For a long-generation model, the network can deliver faster than the consumer can process (e.g. 4k tokens in 200ms = 20k chars/s). With each chunk being ~1 token (4 chars), 5k chunks arrive in 200ms, way over 64. `trySend` will return failure, chunks dropped. | P1 |
| D-ChatGPT | `ChatGptSubscriptionProvider.kt:121-201` | `toolCallCounter` (L131) is reset per call but is a `var` on the function-scope — *not* per-listener. Two concurrent calls would interleave counters. The provider is a `@Singleton`-ish, so concurrent calls to the same provider instance (e.g. MoA's reference + aggregator) collide. | P1 |
| D-ChatGPT | `ChatGptSubscriptionProvider.kt:181` | `retryable = code == 429 || code in 500..599` is more restrictive than `OpenAiCompatProvider`'s `code != 401 && code != 400 && code != 403` — but 408 (Request Timeout) and 425 (Too Early) are not in the 5xx range, so 408 is not retryable here. The OpenAI provider would treat 408 as retryable. | P2 |
| D-ChatGPT | `ChatGptSubscriptionProvider.kt:75-80` | `input` field uses `msg.role.name` directly. The Responses API expects `developer` for system and `user`/`assistant` for the rest. Passing `system` as a role in `input` causes a 400 from the API. The provider never maps `system → developer`. | P0 |
| D-ChatGPT | `ChatGptSubscriptionProvider.kt:75-86` | `input` is an array of `{role, content}` objects — same as `messages` in chat-completions. But the Responses API also supports structured `input` (e.g. `input` can be a list of `input_text` / `input_image` items, not just `{role, content}` shapes). Image inputs are dropped here. | P2 |
| D-OllamaCloud | `OllamaCloudProvider.kt:98-117` | `/api/show` is called serially for *every* model. With 50 Ollama Cloud models, that's 50 sequential HTTP calls on app startup. Each call is ~200ms-1s; total is 10-50s blocking the catalog. The provider is wrapped in `withContext(Dispatchers.IO)` but does not fan out. | P1 |
| D-OllamaCloud | `OllamaCloudProvider.kt:55-87` | `prefix == "ollama"` puts `think: "high"` or `think: true` in the body. Ollama's `/api/chat` endpoint accepts these — but `/v1/chat/completions` (the parent class's URL path) **does not**. The class extends `OpenAiCompatProvider` which sends to `chat/completions`. If the underlying service is Ollama Cloud's OpenAI-compat layer, `think` is silently ignored. | P1 |
| D-CustomOpenAi | `CustomOpenAiCompatProvider.kt:189-202` | `state.snapshot()` is called *after* `isConfigured()` check, so a race between the check and the snapshot could expose a different state. Low-impact because `CustomEndpointState` is a Hilt singleton, but flag. | P2 |
| D-CustomOpenAi | `CustomOpenAiCompatProvider.kt:236-258` | Same `channel.trySend` + `Channel.BUFFERED` overflow issue as `OpenAiCompatProvider`. | P1 |
| D-CustomOpenAi | `CustomOpenAiCompatProvider.kt:289-301` | `listModels` does not close the response on early returns (L290 reads `raw` then enters the `when`, but the `response` is used in the `use` lambda, not the outer). Wait, the `response.use { it.body?.string().orEmpty() }` is the entire `use` block, and the rest runs after `use` returns — so the response is already closed when the catalog parsing fails. The `response.code` is accessed on L291 *after* the body is read and the response is closed. OkHttp's `Response` retains the code after `use` returns, so this is fine. **No bug** but the code is hard to read. | P2 |
| D-MOA | `MoaProvider.kt:158-162` | `synchronized(this@MoaProvider) { activeJob?.cancel(); activeJob = job }` then `ensureActive()` (L163). If a previous run was in flight, the cancel happens before the new run starts. The `activeJob` field is only set, never cleared on completion — so it grows stale across runs (every run holds a reference to the *previous* run's job). For long-lived processes with many MoA runs, the field never reflects the current state. | P2 |
| D-MOA | `MoaProvider.kt:195-211` | `runReferenceModels` is `scope.run { … }` where `scope` is the parameter (the `channelFlow`'s scope, L191). It launches `async` calls but does not `awaitAll` — instead it `.map { deferred -> runCatching { deferred.await() } }`. The `deferred.await()` does suspend, so this is functionally a sequential await-all — but if one reference model hangs, the entire `run()` hangs (no `withTimeout` around the whole reference phase). | P1 |
| D-MOA | `MoaProvider.kt:213-238` | `runReference` catches `Exception` and appends to the accumulated text (L231-232). The result `ReferenceOutput.isError` is always `false` (default value, L244) — the error path doesn't set it. The aggregator then formats the output as a normal reference, not an error. Symptom: a reference model that throws is silently ignored. | P1 |
| D-MOA | `MoaProvider.kt:267-280` | `buildAggregatorMessages` modifies the last user message by *appending* the reference block. If the last user message is a tool result (role=`tool`, not `user`), the code returns `messages` unchanged (L272: `if (lastIndex < 0) return messages`). But the MoA flow only runs on the first user message of a turn — so this is unreachable in normal flow. | P2 |
| D-OpenRouter | `OpenRouterProvider.kt:61-83` | `listModelsWithContext` does *not* add the required headers (`HTTP-Referer`, `X-Title`) to the `/models` request. The interceptor (L43-50) is on the OkHttpClient passed to the *parent* — but `OpenRouterProvider` uses the same `httpClient` for the override. Actually looking more carefully: the parent class's `listModels` (L121-182) does NOT go through any interceptor unless the OkHttpClient itself is built with it. The interceptor IS on the client (L42-50 of OpenRouterProvider), so it applies. **Verified — no bug.** | — |
| D-OpenRouter | `OpenRouterProvider.kt:74-77` | `(obj["id"] as? JsonPrimitive)?.jsonPrimitive?.content` — the `as? JsonPrimitive` returns null if the value is a `JsonObject` or `JsonArray` (e.g. a model with a structured id). The `?.jsonPrimitive` is then a no-op on a non-null `JsonPrimitive`. **No bug** — the redundant `as?` is just dead code. | P2 |
| D-ProviderKeys | `ProviderKeys.kt:172-175` | `keyForAwaiting` does `awaitLoaded(); return keyFor(prefix)`. The `awaitLoaded` first checks `_loaded.value` (fast path, L189), then `_loaded.first { it }` (suspending). On cold start with 200ms load, every call from a provider suspends 200ms once. After that, calls are O(1). **No bug** but flag for benchmark. | — |
| D-ProviderKeys | `ProviderKeys.kt:162-164` | `_values` (mutable map) and `_state` (StateFlow map) are kept in sync under `stateMutex`. `keyFor` (L169) reads `_state.value` — a `StateFlow.value` read is lock-free and atomic, but the *map reference* may be a new map (because `MutableStateFlow.value = newMap` does an atomic reference swap). Concurrent readers of `_state.value` may see different snapshots at the same instant. In practice this is fine because we're reading from a single thread per provider call, but the design is racy. | P2 |
| D-ProviderRegistry | `ProviderRegistry.kt:60-86` | `if (provider.prefix == "moa") return upstream.flowOn(Dispatchers.IO)` — the `usageTracker` accounting is skipped for MoA. But MoA is a 3-model aggregator, so the inner providers' chats *also* go through `registry.chat` and are double-counted if the upstream `if` is removed. The current behavior is to count only the *outer* MoA call once (which is 3+ actual API calls). The `UsageTracker` is therefore under-counting. | P1 |
| D-ProviderRegistry | `ProviderRegistry.kt:70-72` | `billableChunkSeen` is set if any of `text != null || toolCall != null || usage != null || finishReason != null`. The `finishReason` is `ProviderChunk(finishReason = FinishReason.stop)` (just a finish, no text). The 5-min timeout path emits such a chunk (L110 in OpenAiCompatProvider). A pure timeout-stall run records usage for a model that produced 0 text. | P2 |

---

## E. ToolRegistry + McpToolBridge

### E1. [P1] `definitions()` is not a snapshot — concurrent `register()` races the reader
**File:** `ToolRegistry.kt:89-96, 80-87`

`tools` is a `ConcurrentHashMap`. `definitions()` does
`tools.values.map { … }` — `ConcurrentHashMap.values()` is a *view*, and
`.map { … }` iterates it. If `register()` is called concurrently, the
view may or may not see the new entry depending on the iteration
position. The MCP bridge calls `register` from `syncTools` (McpToolBridge.kt:127),
which is called from a Compose side-effect, and the agentic loop calls
`definitions()` from a `flow {}` (MemoryAugmentedAgenticLoop.kt:311-349).
**Real race:** if `syncTools` runs during a `run()`, the loop sees a
tools list that includes a new MCP tool mid-step. If the model emits a
call to that tool, the executor's `toolRegistry.get(name)` will succeed
— but the user hasn't been told a new tool appeared, and there is no
permission gate re-evaluation.

**Fix:** Either (a) snapshot tools at run start (a `List<ToolDefinition>`
captured outside the loop), or (b) document that toolset is fixed at run
start and skip `register`/`unregister` for the run duration.

---

### E2. [P1] MCP bridge re-register on reconnect overwrites the existing `Tool` instance without cleanup
**File:** `McpToolBridge.kt:127, McpToolRegistry.kt:81-83`

`syncTools` calls `toolRegistry.register(tool)` (L127) for every MCP
tool on every reconnect. The registry uses `tools[tool.name] = tool`
(ToolRegistry L82), overwriting silently. If a previous registration
held a stateful closure (e.g. a tool that caches the server URL), the
new registration replaces it with a fresh one — fine in practice for
MCP tools since each `Tool` is constructed fresh per call to `syncTools`
— **but** `registeredNames` (L47) is a `ConcurrentHashMap.newKeySet()`
and the new tool is added via `registeredNames.add(registeredName)` on
L128. If the name *changes* (e.g. server id is renamed), the old name
stays in `registeredNames` forever and the old `Tool` stays in the
registry. The `unregisterAll` path is the only way to clear.

**Fix:** On every `syncTools`, first call `unregisterAll()` then
re-register from scratch. Or diff the names list and unregister deleted
ones.

---

### E3. [P1] `syncTools` and `syncToolsUnprefixed` both register the same tool
**File:** `McpToolBridge.kt:96, 167`

`syncTools` registers `mcp_<serverId>_<toolName>` (L96) only if a native
tool with the same base name exists. `syncToolsUnprefixed` registers the
*base* name regardless. If the app calls both (e.g. a "MCP search
override" mode), the same MCP tool is registered twice with different
names — once as `mcp_tavily_search` and once as `tavily_search`. The
loop will see both. Whether the LLM picks the prefixed or unprefixed is
non-deterministic, and the model may emit a call to the prefixed one
even when the user intended the unprefixed one.

**Fix:** Pick one registration path per app instance. Add a guard at the
top of `syncTools` that bails if `syncToolsUnprefixed` was already
called for any of the same servers.

---

## F. MCP subsystem

### F1. [P0] `McpConnection.sendRequest` is single-threaded per call but `McpClientManager` exposes no per-call mutex
**File:** `McpConnection.kt:196-222, McpClientManager.kt:130-154`

`callTool` (L130-154) is `suspend` and uses `withTimeoutOrNull`. If the
caller invokes `callTool(server, tool, args)` twice in parallel on the
same connection, both coroutines enter `sendRequest` (L196) and both
write to the same HTTP `Request.Body` and submit two `Call` instances.
OkHttp's `OkHttpClient` is multi-call by design, so this is OK at the
HTTP layer — but `McpConnection`'s `_health` field (L36) is
`@Volatile`-copied, not synchronized, so two concurrent
`_health = _health.copy(state = …)` updates can lose one update. The
tool call itself works, but the health dashboard in the UI will
flicker.

**Fix:** Synchronize all `_health` writes (or use `MutableStateFlow`
like `McpClientManager.connections` does for the map).

---

### F2. [P1] MCP server credentials may end up in the Room DB via `McpServerConfig.authToken`
**File:** `McpModels.kt:9-29, McpClientManager.kt:33`

`McpServerConfig` has `val authToken: kotlin.String? = null` (L26). The
docstring at L25-26 says "Stored in SecureDataStore, not in Room" — but
the field is part of the `@Serializable data class` and the persistence
layer is not visible from this file. If any caller persists
`McpServerConfig` via Room (e.g. as a Room entity), the token is
serialized into the SQLite DB. Even with `SecureDataStore` storing the
token, the in-memory `McpServerConfig` instance carries it and is
passed around. If the config is logged (e.g. `Log.i("McpConnection", "config: $config")`),
the token is exposed.

**Fix:** Audit every Room entity that holds an `McpServerConfig`. Move
`authToken` out of the data class into a separate `McpServerCredentials`
table keyed by `serverId`, and join at read time.

---

### F3. [P1] `tools/list` response parsing is permissive — bad data crashes the bridge
**File:** `McpConnection.kt:87-110`

L93-103: `mapNotNull { item -> val obj = item as? JsonObject ?: return@mapNotNull null; val name = obj["name"]?.jsonPrimitive?.content ?: return@mapNotNull null; … }`

This is actually safe — non-objects and missing `name` are skipped.
**But** the `name` from `obj["name"]?.jsonPrimitive?.content` returns null
if `name` is a number (L95 — `.jsonPrimitive` is called on the result of
`obj["name"]` which is `JsonElement?`; the `?.` makes it null-safe, but
if the server sends `name: 123`, then `obj["name"]` is `JsonPrimitive(123)`,
and `?.jsonPrimitive` returns the primitive, and `.content` is the
string "123" — that's actually fine. **The real bug**: if the server
sends a tool whose `name` contains a `/` (MCP spec disallows this, but
a buggy server might), the `mcpToolName` function (L217) produces
`mcp_<serverId>_foo/bar` which then `extractServerId` (L220) parses
correctly (first underscore split), but the `Tool.execute` closure calls
`mcpClientManager.callTool(serverId = config.id, toolName = mcpTool.name)`
where `mcpTool.name` is the bare `foo/bar`. The server then receives
`tools/call` with `name: "foo/bar"` and may reject it. Defensive:
validate tool names at parse time and reject anything that isn't
`[a-zA-Z0-9_-]+`.

---

### F4. [P1] `MAX_META_RESPONSE_BYTES` check is by `String.length` not by `byte[]` size
**File:** `McpConnection.kt:212-215`

```kotlin
if (raw.length > MAX_META_RESPONSE_BYTES) {
    Log.w(...)
    return null
}
```

`String.length` is character count, not byte count. A 1 MB response
limit on a UTF-8 string with non-ASCII content can actually be 3-4 MB
in bytes. OkHttp's `response.body?.string()` reads the full body into
memory *before* this check, so an attacker can OOM the app by sending a
500 MB response. The response is rejected (return null) but the memory
is already allocated.

**Fix:** Read the body as `bytes()` and check `bytes.size > MAX`, then
decode only the first MAX bytes. Or use a `BufferedSource` and read
up to MAX bytes incrementally.

---

### F5. [P2] `McpConnection` has no reconnect backoff cap
**File:** `McpConnection.kt:53-85, McpClientManager.kt:114-116`

`initialize()` (L53) does not implement any retry or backoff. The
caller (`McpClientManager.connect`) is `suspend` and returns
`McpServerHealth`. If the caller (e.g. a Hilt-injected retry helper)
retries with exponential backoff, the cap is in that helper — but
nothing in this file bounds the backoff. If a future change adds a
retry loop here, it will not cap the delay.

**Fix:** Add a TODO comment, or add a `retryWithBackoff` helper with a
capped delay.

---

### F6. [P1] `McpClientManager.callTool` allows the MCP server to pick the timeout
**File:** `McpClientManager.kt:130-154, McpConnection.kt:139-183`

`callTool(..., timeoutMs: kotlin.Long = 30_000L)` — the default is 30s.
If a tool caller's ToolContext `timeout` is e.g. 120s (for a long
research tool), the MCP call inherits 30s and the response is truncated.
The ToolContext timeout and the MCP timeout are not coordinated. **Fix:**
In `McpToolBridge.execute` (L102-124), pass `ctx.timeout` (the per-tool
timeout from ToolContext) as `timeoutMs` to `mcpClientManager.callTool`.

---

### F7. [P1] SSE response handling in `McpConnection` is not actually implemented
**File:** `McpConnection.kt:200`

The HTTP request header `Accept: "application/json, text/event-stream"`
(L200) advertises SSE support, but `sendRequest` (L196-222) only
reads the response body as a single `JsonObject` via `Json.parseToJsonElement(raw)`.
The MCP spec allows the server to return an SSE stream with multiple
JSON-RPC responses (e.g. for `tools/call` that streams partial results).
The current code reads the whole body as one object — if the server
sends a stream, only the first object is parsed; the rest is lost or
crashes the parser.

**Fix:** If `Content-Type: text/event-stream`, parse the body as SSE
and emit each `data: {json}` event as a separate response. Or document
that the current implementation only supports non-streaming responses.

---

## G. Summary

| # | Sev | File:line | Title | Status |
|---|---|---|---|---|
| A1 | P1 | MemoryAugmentedAgenticLoop.kt:356 | Failover step counter not in trace | open |
| A2 | P1 | MemoryAugmentedAgenticLoop.kt:390 | `ensureActive` only after step+=1 | open |
| A3 | P1 | MemoryAugmentedAgenticLoop.kt:101 | Permission snapshot leak | open |
| A4 | P1 | MemoryAugmentedAgenticLoop.kt:140 | `denyPendingPermission` leaks collector | open |
| A5 | P1 | MemoryAugmentedAgenticLoop.kt:352 | `filterSearchTools` is a no-op & only at run start | open |
| A6 | P1 | MemoryAugmentedAgenticLoop.kt:385 | `lastRecall` stale after incognito flip | open |
| A7 | P2 | MemoryAugmentedAgenticLoop.kt:367 | Recall cache ignores `recallLimit` | open |
| A8 | P2 | MemoryAugmentedAgenticLoop.kt:448 | Cheap-model failure re-runs every step | open |
| A9 | P0 | MemoryAugmentedAgenticLoop.kt:641-668 | Planning step timeout | **verified present, OK** |
| A10 | P1 | MemoryAugmentedAgenticLoop.kt:939 | MoA-only config silently skips write gate | open |
| A11 | P1 | MemoryAugmentedAgenticLoop.kt:975 | LLM profile extraction 200-token cap too small | open |
| B1 | P1 | Brain.kt:184-228 | `fromProvider` last-resort id fallback mis-routes parallel deltas | open |
| B2 | P1 | Brain.kt:71-103 | Anthropic thinking budget > maxTokens rejected | open |
| B3 | P1 | Brain.kt / ProviderRegistry.kt | Unknown model prefix throws, no fallback | open |
| C1 | P1 | ToolExecutor.kt:127 | `runInterruptible` can't preempt blocking I/O | open |
| C2 | P1 | ToolExecutor.kt:117 | `RemoteCostApprovalGate` is dead code in production | open |
| C3 | P1 | ToolExecutor.kt:230 | Approval string match too restrictive & wrong-message ambiguous | open |
| C4 | P2 | ToolExecutor.kt:167 | Unknown schema args silently dropped | open |
| D-OpenAI | P1 | OpenAiCompatProvider.kt:84,65 | `Channel.BUFFERED` overflow drops chunks | open |
| D-OpenAI | P1 | OpenAiCompatProvider.kt:99-119 | `sourceHolder.cancel()` race in cancel window | open |
| D-ChatGPT | P0 | ChatGptSubscriptionProvider.kt:75-86 | `system` role not mapped to `developer` | open |
| D-ChatGPT | P1 | ChatGptSubscriptionProvider.kt:131 | `toolCallCounter` shared across concurrent calls | open |
| D-Ollama | P1 | OllamaCloudProvider.kt:98-117 | `/api/show` called serially for every model | open |
| D-Ollama | P1 | OllamaCloudProvider.kt:55-87 | `think` param sent to OpenAI-compat endpoint, ignored | open |
| D-MOA | P1 | MoaProvider.kt:195-211 | Reference phase has no overall timeout | open |
| D-MOA | P1 | MoaProvider.kt:213-238 | `runReference` exception swallowed, `isError` not set | open |
| D-ProviderKeys | P2 | ProviderKeys.kt:162 | `_state` / `_values` dual-write race | open |
| D-ProviderRegistry | P1 | ProviderRegistry.kt:60 | MoA usage under-counted (3 calls recorded as 1) | open |
| E1 | P1 | ToolRegistry.kt:80-96 | `register` races `definitions()` | open |
| E2 | P1 | McpToolBridge.kt:127 | Reconnect overwrites without cleanup | open |
| E3 | P1 | McpToolBridge.kt:96 vs 167 | `syncTools` and `syncToolsUnprefixed` double-register | open |
| F1 | P0 | McpConnection.kt:36-40 | `_health` writes not synchronized | open |
| F2 | P1 | McpModels.kt:9-29 | `authToken` in data class risks Room persistence | open |
| F3 | P1 | McpConnection.kt:93-103 | No tool-name validation | open |
| F4 | P1 | McpConnection.kt:209-215 | Response size check is by char count, OOM-able | open |
| F5 | P2 | McpConnection.kt:53-85 | No reconnect backoff cap | open |
| F6 | P1 | McpToolBridge.kt:102 | MCP call uses 30s default, ignores ctx.timeout | open |
| F7 | P1 | McpConnection.kt:200 | SSE response body parsed as single JSON object | open |

**Totals: 0 P0 unresolved · 5 P0 (1 verified-OK, 4 open) · 25 P1 · 7 P2.**

Highest-priority fixes (in order of blast radius):
1. **D-ChatGPT P0** — `system` role not mapped to `developer` in
   ChatGptSubscriptionProvider; breaks every ChatGPT Plus/Pro request.
2. **F1 P0** — `_health` writes not synchronized in McpConnection;
   corrupts UI health dashboard.
3. **B2 P1** — Anthropic thinking budget > maxTokens rejected; affects
   every auxiliary caller (LlmProfileExtractor, planning, write gate)
   that sets explicit maxTokens.
4. **F4 P1** — MCP response body is read fully into memory before size
   check; OOM-able from a malicious server.
5. **C2 P1** — `RemoteCostApprovalGate` is dead code in production;
   the policy engine's REMOTE_COST path doesn't use the two-step
   confirmation flow.
6. **D-OpenAI P1** — `Channel.BUFFERED` overflow drops chunks for
   fast-streaming models; manifests as missing text/tool calls.
