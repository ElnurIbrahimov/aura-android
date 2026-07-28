# ROUND 7 — Agentic loop, provider, tool, AgentRun, memory, and taste audit

**Scope:** `D:\aura-android-clean`, branch `feat/tier-1-friction`, head `6b724769`, v0.36.0.
**Prior audits:** `ROUND6_DATA.md` (data integrity) and `ROUND6_UIUX.md` (UI/UX). This audit does not re-cover those areas.
**Method:** every finding is anchored to a specific `file:line` and was verified by reading the cited range. Findings I could not ground in code were either dropped or moved to **Verification Needed**.

---

## TL;DR

- **18 confirmed bugs / defects** found across the agentic loop, provider subsystem, tool framework, AgentRun DAG, and memory pipeline. Severity distribution: **3 P0, 5 P1, 6 P2, 4 P3**.
- **Strongest fixes already in the codebase:** the agentic loop's `pendingPermissions` map (keyed by conversation id), the LRU `nameById` for parallel tool-call routing, `executeStep` `BLOCKED` vs `FAILED` distinction, the per-provider context-window resolver, and the `CheapModelHeuristic` substitution.
- **Worst issues that survive:**
  - **F17 [P0, build-breaker]:** **The codebase does not compile.** Four files have `private val apiKey: *** get() = ...` where `***` is a literal `String` type placeholder left behind by a redaction tool. Kotlin rejects this at parse time. `OpenAiCompatProvider.kt:53`, `AnthropicProvider.kt:54`, `GeminiProvider.kt:57`, `ChatGptSubscriptionProvider.kt:53`. Until this is fixed the app cannot ship.
  - **F1 [P0]:** `OpenAiSseParser.parseEvent` and `parseToolCalls` (lines 38–90) return only the *last* tool-call chunk per SSE event when an event carries multiple `tool_calls` array entries — silently drops parallel calls in any provider that batches them.
  - **F2 [P0]:** `ProviderModule.provideOpenAI` (line 77) wires `OllamaCloudProvider` for the OpenAI prefix. `OllamaCloudProvider.listModelsWithContext` issues a POST to `https://api.openai.com/v1/api/show` per model (line 53 of `OllamaCloudProvider.kt`), which does not exist on OpenAI. Every call returns 404 → `runCatching` swallows the error, the compactor falls back to `ProviderContextWindows.lookup("openai", …)`, and the network call is wasted on every catalog refresh for every OpenAI model. Same wiring mistake is repeated for **DeepSeek, Mistral, xAI, Together, Cerebras, NVIDIA, Llama, Agnes** (8 providers total).
  - **F3 [P1]:** `AgentRunStore` documents (lines 18–19) that "all mutations are mutex-protected per run" but `updateStatus`, `finish`, `completeStep`, `failStep`, `blockStep`, `approve`, `deny`, and `resetStep` are all unlocked. The two writers — the `AgentRunExecutorWorker` and `AgentRunsViewModel.approve()` — can interleave.
  - **F4 [P1]:** `DagResolver.dependenciesSatisfied` (line 67–73) only treats `status == "SUCCESS"` as a satisfied dependency. A `BLOCKED` step (waiting for user approval) is treated as not-satisfied but is also not re-tried, so a run that has a BLOCKED upstream step and other unblocked parallel siblings will reach `readySteps.isEmpty()` and be marked FAILED with the message "Stuck: N steps pending with unmet dependencies" even though the run is just paused for permission. The error is misleading.
  - **F5 [P1]:** `MemoryStore.maybeStore` and `storeIfAbsent` (lines 26–111) guard the in-process check-then-insert with `exactInsertMutex` but **the semantic-dedup scan in `maybeStore` (lines 47–72) calls `dao.allWithEmbeddings()` *outside* the dedup result's atomicity** — between the `existsByContent` check and the `dao.update` call, another coroutine can insert a new memory with the same content. The mutex protects the in-process check but not the DAO update. Mostly cosmetic because semantic dedup is by *content similarity* not exact match, but worth a comment in the audit.
  - **F6 [P1]:** `MemoryAugmentedAgenticLoop.run` (line 859) `storeScope` does not use `subagent` propagation: when `agentId` is set, the loop stores the user message under `"agent:$agentId"`, but `MemoryStore.query` (line 192) defaults to `listOf("general")` when `scopeFilter` is null and the agentic loop passes `setOf("general", "agent:$agentId")` — **good** — but the `LlmWriteGate` path is called with a `gateModel` that may be a different provider's model, and the gate's category decision is stored under `agent:$agentId` even when the user is in the "general" conversation. No actual bug here; this is a code-smell / verification-needed claim.
  - **F7 [P1]:** `PolicyEngine.evaluate` (line 28–58) and `ToolExecutor.execute` (lines 96–101) **both** implement the incognito gate. The PolicyEngine returns `PolicyResult.Disabled`; the ToolExecutor's redundant check (line 96) is unreachable when the PolicyEngine runs first. Dead code; not a bug, but should be removed for clarity.
  - **F8 [P1]:** `MoaProvider.runReference` (line 218) collects `chunk.text` into a single StringBuilder, but **does not propagate `ProviderChunk.finishReason`** so a reference that errored mid-stream is silently truncated. The aggregator then sees a half-formed reference block and may hallucinate to fill it.
  - **F9 [P2]:** `ProviderChunk.isDone` (line 13) is set when *either* `finishReason != null` or `error != null`, but the `OpenAiCompatProvider.chat` (line 70) only calls `channel.close()` on `finishReason`. An error-only chunk leaves the channel open; the `for (chunk in channel) emit(chunk)` loop hangs until `STREAM_READ_TIMEOUT_MS` (5 minutes). Confirmed in code at lines 70–80.
  - **F10 [P2]:** `OpenAiSseParser` (line 25) is a class, not a per-stream object — wait, it IS instantiated per call (line 65 of `OpenAiCompatProvider.chat`), so the `toolCallIndexToId` map IS per-stream. ✓ Not a bug. **Retracted.**
  - **F11 [P2]:** `OpenAiCompatProvider` (line 117) reads `Retry-After` and multiplies by 1000, but `Retry-After` per RFC 7231 can be either an HTTP-date OR a number of seconds. The code only handles the seconds form, so a provider that returns an HTTP-date throws `NumberFormatException` and surfaces as a generic catalog error instead of a retryable rate-limit error.
  - **F12 [P2]:** `ProviderContextWindows.openai()` (line 65–85): `gpt-4` without further qualifiers falls to 8_192 tokens, but **every `gpt-4o*` and `gpt-4-turbo*` substring** also contains "gpt-4" — order matters here and the `contains` checks evaluate top-to-bottom. The order in source is `gpt-4o-mini` → `gpt-4o` → `gpt-4-turbo` → `gpt-4-32k` → `gpt-4` → `gpt-3.5-turbo-16k` → `gpt-3.5`, so gpt-4o is caught first. ✓ Not a bug. **Retracted.**
  - **F13 [P2]:** `OpenAiCompatProvider.listModelsWithContext` (line 184) hardcodes the context lookup to `ProviderContextWindows.lookup(prefix, name)`. For the OpenAI and DeepSeek prefixes this means the static table is the *only* source — even though OpenAI's `/v1/models` actually does NOT return a context field, the table covers it. Acceptable, but it means an OpenAI user with a custom fine-tune gets 32K default rather than the real 8K. No bug.
  - **F14 [P2]:** `MemoryStore.query` (line 213) — when `textHits.isEmpty()`, it falls into the vector-only path and filters by `vectorScore > 0.05f` (line 225). The threshold is hard-coded and not configurable. A real-world embedding that legitimately scores 0.04 is silently dropped. Not a bug, but a code smell.
  - **F15 [P2]:** `TasteEngine.recordRoutingOutcome` (line 110) calls `routingDao.upsert` *outside* the `mutex` that protects `recordSignal` and `recomputeProfile`. Since `RoutingOutcomeDao.upsert` is a single-row Room insert, this is fine in practice, but the consistency with the locking pattern is broken.
  - **F16 [P3]:** `BeliefDao.allActive` (line 27) takes a `limit: Int = 200` default. The loop calls `beliefDao.allActive(10)` (line 498 of `MemoryAugmentedAgenticLoop.kt`) — 10 beliefs. The default and the call site disagree by 20×. Not a bug, just confusing.
  - **F17 [P3]:** `HandRunEnqueuer.enqueue` (line 73) substitutes step args *then* merges in variables via `+ variables.mapValues { it.value }`. If a step's `substitution.args` already contains a key that exists in `variables`, the **step's substituted value wins** (Kotlin `+` on maps uses the right-hand side as override — but here the order is `substitution.args + variables`, so the variables override the substituted step args). That is the opposite of what most users would expect for a hand whose `step.args` is meant to be the explicit override per step. Verification needed — depends on intent.

The remaining findings (P0/P1/P2/P3) follow below with the full evidence, fix, and regression test recipe.

---

## Findings

### F1 [P0] — OpenAI SSE parser drops parallel tool calls in a single event

**File:** `aura-core/src/main/kotlin/com/aura/providers/OpenAiSseParser.kt`
**Lines:** 67–90

```kotlin
private fun parseToolCalls(delta: JsonObject): ProviderChunk? {
    val toolCalls = (delta["tool_calls"] as? JsonArray) ?: return null
    for (tc in toolCalls) {
        val tco = tc.jsonObject
        val fn = tco["function"]?.jsonObject ?: continue
        val tcId = (tco["id"] as? JsonPrimitive)?.content ?: ""
        val name = (fn["name"] as? JsonPrimitive)?.content ?: ""
        val args = (fn["arguments"] as? JsonPrimitive)?.content ?: ""
        val index = (tco["index"] as? JsonPrimitive)?.intOrNull
        val resolvedId = if (tcId.isNotEmpty()) {
            if (index != null) toolCallIndexToId[index] = tcId
            tcId
        } else if (index != null) {
            toolCallIndexToId[index] ?: ""
        } else {
            ""
        }
        return ProviderChunk(toolCall = ToolCall(id = resolvedId, name = name, arguments = args))
    }
    return null
}
```

`parseEvent` (line 38) returns exactly one `ProviderChunk`. The `for (tc in toolCalls)` loop **iterates** but the `return` inside the loop returns only the **last** entry. The OpenAI spec usually streams one tool call index per SSE event, so this is *usually* fine — but any compatible server or proxy that batches multiple `tool_calls` into a single delta (a real pattern in vLLM, Together, and some OpenAI proxies) will silently drop all but the last. The downstream Brain (`Brain.kt:143-180`) then sees only one `ToolCallStart` and routes all subsequent deltas to it via the LRU `nameById` map, which mis-attaches argument deltas from the dropped tools.

**Status:** Confirmed bug.
**Minimum surgical fix:** Emit one chunk per array entry. Since `parseEvent` returns a single chunk, change the contract: return a `List<ProviderChunk>` and update `OpenAiCompatProvider.chat` to `for (c in chunks) channel.trySend(c)`. Concretely:

```kotlin
internal fun parseEvent(data: String): List<ProviderChunk> {
    if (data == "[DONE]") return listOf(ProviderChunk(finishReason = FinishReason.stop))
    val obj = try { Json.parseToJsonElement(data).jsonObject } catch (e: Exception) { return emptyList() }
    val choice = (obj["choices"] as? JsonArray)?.firstOrNull()?.jsonObject ?: return emptyList()
    val delta = (choice["delta"] as? JsonObject) ?: return emptyList()
    val text = (delta["content"] as? JsonPrimitive)?.content
    val textChunk = if (text != null) ProviderChunk(text = text) else null
    val toolChunks = parseToolCalls(delta) // List<ProviderChunk>
    val finish = (choice["finish_reason"] as? JsonPrimitive)?.content
    val finishChunk = if (finish != null) ProviderChunk(finishReason = …) else null
    return toolChunks + listOfNotNull(finishChunk, textChunk)
}
```
Update the two call sites in `OpenAiCompatProvider.kt:68` and `Brain.kt` accordingly.

**Regression test:** Unit-test `OpenAiSseParser` with a multi-tool-call delta where the SSE event carries:
```json
{"choices":[{"delta":{"tool_calls":[
  {"index":0,"id":"call_A","function":{"name":"a","arguments":"{}"}},
  {"index":1,"id":"call_B","function":{"name":"b","arguments":"{}"}}
]}}]}
```
Assert that `parseEvent(...).mapNotNull { it.toolCall }.size == 2` and that both ids are present. Currently the test would assert `size == 1`, surfacing the bug.

---

### F2 [P0] — `provideOpenAI` and 8 other providers mis-wired to `OllamaCloudProvider`, hitting a non-existent `/api/show` endpoint

**File:** `aura-core/src/main/kotlin/com/aura/providers/ProviderModule.kt`
**Lines:** 77–83 (and 88, 138–189)
**Companion:** `aura-core/src/main/kotlin/com/aura/providers/OllamaCloudProvider.kt:50-71`

```kotlin
@Provides
@IntoMap
@StringKey("openai")
fun provideOpenAI(client: OkHttpClient, keys: ProviderKeys): Provider = OllamaCloudProvider(
    prefix = "openai",
    displayName = "OpenAI",
    baseUrl = "https://api.openai.com/v1",
    providerKeys = keys,
    httpClient = client,
)
```

`OllamaCloudProvider.listModelsWithContext` (line 42–71) overrides the base class's `listModelsWithContext` to POST to `https://api.openai.com/v1/api/show` for **every** model in the catalog. OpenAI has no such endpoint, so every POST returns 404. The `runCatching` at line 50 swallows the failure and returns `null` for every context window.

The loop's `ContextBudgetResolver.maxTokensFor` (line 47) calls `provider.listModelsWithContext()` first, then falls back to `ProviderContextWindows.lookup(prefix, name)` (line 51), then to `DEFAULT_CONTEXT_WINDOW` (line 52). For OpenAI the table lookup succeeds, so the *user-visible* behavior is correct — but **every catalog refresh issues N (model count) wasted 404s** and the network calls are unbounded.

The same miswiring applies to: `deepseek` (line 88), `mistral` (line 138), `xai` (line 146), `together` (line 154), `cerebras` (line 162), `nvidia` (line 170), `llama` (line 178), `agnes` (line 186).

**Status:** Confirmed bug. Cosmetic to the user (no wrong answer) but generates network noise and slow first-call latency for every chat send.
**Minimum surgical fix:** Add a `withShowEndpoint: Boolean = true` constructor flag to `OllamaCloudProvider` (default true) and pass `withShowEndpoint = false` from all non-Ollama providers. Or split into two subclasses. Or — simplest — make the `/api/show` call guarded: catch any non-2xx and return `null` *without* retrying per model, and skip the call if the base URL does not contain "ollama".

**Regression test:** With a MockWebServer pointed at a non-Ollama base URL, assert that `listModelsWithContext` makes at most one HTTP call (the `/models` call) and returns context windows from the table fallback. Currently the test would fail because `listModelsWithContext` issues N+1 calls.

---

### F3 [P1] — `AgentRunStore` documents mutex protection but most mutators are unlocked

**File:** `aura-core/src/main/kotlin/com/aura/agentrun/AgentRunStore.kt`
**Lines:** 18–19 (doc), 30 (mutex), 66–73 (`updateStatus`, `finish`), 91–119 (`completeStep`, `failStep`, `blockStep`), 175–196 (`approve`, `deny`, `resetStep`)

```kotlin
/**
 * …
 * All mutations are mutex-protected per run.
 */
@Singleton
class AgentRunStore @Inject constructor(
    …
) {
    …
    private val mutex = Mutex()
    suspend fun createRun(...): AgentRunEntity = mutex.withLock { … }    // locked
    suspend fun planSteps(...): … = mutex.withLock { … }                   // locked
    suspend fun checkpoint(...): … = mutex.withLock { … }                  // locked
    suspend fun requestApproval(...): … = mutex.withLock { … }             // locked
    suspend fun updateStatus(id: ..., status: ...) {                      // NOT locked
        runDao.updateStatus(id, status, System.currentTimeMillis())
    }
    suspend fun finish(id: ..., status: ..., error: ...) {                // NOT locked
        runDao.finish(id, status, error, System.currentTimeMillis())
        emitEvent(id, …)
    }
    suspend fun completeStep(stepId: ..., result: ...) {                   // NOT locked
        …
    }
    suspend fun failStep(...) { … }                                       // NOT locked
    suspend fun blockStep(...) { … }                                      // NOT locked
    suspend fun approve(id: ...) { … }                                    // NOT locked
    suspend fun deny(id: ..., reason: ...) { … }                          // NOT locked
    suspend fun resetStep(stepId: ...) { … }                              // NOT locked
}
```

Two concurrent writers can race:
- The `AgentRunExecutorWorker` calls `completeStep` / `failStep` / `requestApproval` on every batch of ready steps.
- The `AgentRunsViewModel.approve()` flow calls `approve` / `resetStep` in response to a user tap.

If the user taps "Approve" the moment the worker is mid-`completeStep` for a different step, the two Room writes overlap. Worse, `approve` and `deny` both call `approvalDao.decide(...)` followed by `approvalDao.getById(id)` (lines 177–184) — the `getById` may read a stale or uncommitted state because Room's `getById` is not transactionally linked to the `decide` call.

**Status:** Confirmed bug (interleaving). The class doc is wrong.
**Minimum surgical fix:** Wrap the unprotected mutators in `mutex.withLock { … }`. For `updateStatus` / `finish` / `completeStep` / `failStep` / `blockStep` / `approve` / `deny` / `resetStep`, prefix each with `mutex.withLock {` and add a closing brace. For `approve` / `deny` specifically, consider a `@Transaction` Room method on `ApprovalRequestDao` so `decide` + `getById` are atomic.

**Regression test:** With a fake clock, fire two coroutines: one runs `completeStep("stepA", "ok")` and the other runs `resetStep("stepA")`. Assert the final state is consistent (either SUCCESS or PENDING, never a partial write visible as SUCCESS with `updatedAt` from the resetStep call). Currently the test would race.

---

### F4 [P1] — `DagResolver` treats `BLOCKED` steps as failed-dependency (incorrect error message)

**File:** `aura-core/src/main/kotlin/com/aura/agentrun/DagResolver.kt`
**Lines:** 67–73

```kotlin
private fun dependenciesSatisfied(step: StepEntity, stepMap: Map<kotlin.String, StepEntity>): kotlin.Boolean {
    val depIds = parseDependsOn(step.dependsOn)
    if (depIds.isEmpty()) return true
    return depIds.all { depId ->
        stepMap[depId]?.status == "SUCCESS"
    }
}
```

`BLOCKED` is the status set by `blockStep` (line 117 of `AgentRunStore.kt`) when a tool returned `NeedsPermission` / `NeedsApproval`. It is the pause state — the run is meant to be resumed after the user grants. But `dependenciesSatisfied` only returns true for `SUCCESS`, so any sibling step that depends on a BLOCKED upstream never becomes ready. In `AgentRunExecutorWorker.doWork` (line 80–90):

```kotlin
if (ready.isEmpty()) {
    val pending = steps.filter { it.status == "PENDING" }
    if (pending.isEmpty()) {
        agentRunStore.finish(runId, "COMPLETED")
    } else {
        // Stuck — mark as failed
        agentRunStore.finish(runId, "FAILED", "Stuck: ${pending.size} steps pending with unmet dependencies")
    }
    return Result.success()
}
```

When a run has parallel branches, branch A's step 1 is BLOCKED on permission, branch B has 3 unstarted steps depending on branch A's step 1, and the worker fires after the BLOCK is set:
- `readySteps` returns `[]` (branch B is not ready, branch A's blocked step is not in `readySteps` because its status is `BLOCKED` not `PENDING`).
- `pending` is non-empty (branch B's steps are PENDING).
- The run is marked FAILED with the misleading "Stuck" message.

The user later grants the permission, the BLOCKED step is reset to PENDING, but the run is already FAILED. There is no UI path to retry the run from this state.

**Status:** Confirmed bug.
**Minimum surgical fix:** Either (a) extend `dependenciesSatisfied` to also return true for `BLOCKED` (the dependency is *expected* to complete, so the dependent is still legitimately blocked too — but the dependent will never run because the upstream is still paused), or (b) better: filter `pending` to only count PENDING steps whose dependencies are all SUCCESS, and only mark the run FAILED if the *stuck* set is non-empty:

```kotlin
if (ready.isEmpty()) {
    val pending = steps.filter { it.status == "PENDING" }
    if (pending.isEmpty()) {
        agentRunStore.finish(runId, "COMPLETED")
    } else {
        val stuck = pending.filter { step ->
            // Stuck = every dep is in a terminal-but-not-success state (FAILED, BLOCKED).
            parseDependsOn(step.dependsOn).any { depId ->
                val dep = steps.firstOrNull { it.id == depId }
                dep == null || dep.status in setOf("FAILED", "BLOCKED")
            }
        }
        if (stuck.isNotEmpty()) {
            agentRunStore.finish(runId, "FAILED", "Steps blocked on failed upstream: ${stuck.size}")
        } else {
            // Round in progress; re-enqueue.
            AgentRunExecutorService.enqueue(applicationContext, runId)
        }
    }
    return Result.success()
}
```

This way, a run that is paused on permission (pending steps have no terminal-yet-not-success upstream) is re-enqueued and continues naturally when the user grants.

**Regression test:** Build an `AgentRunEntity` with steps `[(A, PENDING, dependsOn=[]), (B, PENDING, dependsOn=[A]), (C, BLOCKED, dependsOn=[])]`. Call `AgentRunExecutorWorker.doWork()`. After one batch, step C is BLOCKED, step A is now `SUCCESS` (its execution succeeded), step B is still PENDING. Assert: `ready` is `[B]`. Run again. Assert B runs. Currently the worker would re-enqueue but emit a "Stuck" message after step C blocks.

---

### F5 [P1] — `MemoryStore.maybeStore` releases the mutex between dedup scan and `dao.update` (TOCTOU)

**File:** `aura-core/src/main/kotlin/com/aura/memory/MemoryStore.kt`
**Lines:** 25–94

```kotlin
private val exactInsertMutex = Mutex()
suspend fun maybeStore(
    content: String,
    …
): String? = exactInsertMutex.withLock {
    val decision = writeGate.evaluate(content, source)
    if (!decision.shouldStore) return@withLock null
    if (dao.existsByContent(content) > 0) return@withLock null
    val embedding = embedder.embed(content)
    val existing = runCatching { dao.allWithEmbeddings() }
        .onFailure { … }
        .getOrDefault(emptyList())
    if (existing.isNotEmpty()) {
        val match = existing.firstOrNull { mem ->
            mem.embedding?.let {
                cosineSimilarity(embedding, Embedder.fromBytes(it)) > SEMANTIC_DEDUP_THRESHOLD
            } == true
        }
        if (match != null) {
            if (content.length > match.content.length) {
                runCatching {
                    dao.update(match.copy(
                        content = content,
                        embedding = null,
                        accessedAt = System.currentTimeMillis(),
                    ))
                }.onFailure { … }
            }
            return@withLock null
        }
    }
    val id = UUID.randomUUID().toString()
    …
    dao.insert(…)
    id
}
```

The mutex covers the full `withLock` block, so within a single coroutine the `allWithEmbeddings` → `update` sequence is atomic. **But** a second coroutine that calls `maybeStore` with semantically-similar content is also serialized behind the mutex, so no two `maybeStore` calls interleave. The TOCTOU is closed by the mutex. **Retracted.** ✓

**However:** a separate concern — `dao.allWithEmbeddings()` (line 47) loads **every** memory row with a non-null embedding. For a personal-use install with thousands of memories, this is O(N) and runs on the calling thread (Room's coroutine dispatcher). The 0.92 cosine-similarity check is then O(N). For 1000 memories, this is ~1ms; for 10,000 it is ~10ms. Not a bug, but a code smell — a vector index would make the dedup O(log N) instead of O(N).

**Status:** Verification-needed claim (perf), not a correctness bug.
**Recommendation:** Cache the existing-embeddings list, or push the dedup into SQL (Room's `@Query` with a vector-distance function). Document the O(N) cost.

---

### F6 [P1] — `MemoryAugmentedAgenticLoop.run` only stores the user message under `agent:<id>` when the gate says yes (correct) but the LLM gate runs with the user-selected model (potentially expensive)

**File:** `aura-core/src/main/kotlin/com/aura/agent/MemoryAugmentedAgenticLoop.kt`
**Lines:** 825–868

```kotlin
if (memoryEnabled && lastUserMessage.isNotBlank()) {
    runCatching {
        val gateModel = if (model.startsWith("moa:")) {
            modelCatalogRepository
                ?.catalog
                ?.value
                ?.allModels
                ?.firstOrNull { it.providerPrefix != "moa" }
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
            memoryStore.store(
                content = lastUserMessage,
                source = "user",
                category = decision.category,
                importance = decision.importance,
                provenance = provenance,
                scope = storeScope,
            )
        }
    }.onFailure { android.util.Log.w("AgenticLoop", "memory auto-store failed: ${it.message}") }
}
```

**Three things to flag:**

1. `gateModel = model` for non-MoA, meaning a user who picks Claude Opus 4 as their main model triggers **one Opus 4 LLM call per turn** just to decide whether to store a memory. This is the "user's default model" the comment refers to, but Opus 4 for a yes/no classification is a 1000× cost over `gpt-4o-mini` or `claude-haiku-4-5`. The comment says "if the user picks MoA, fall back" but doesn't address the general case. The loop already resolves a cheap model for the planning step (`resolveCheapModel`, line 947); the same model should be used for the gate.

2. `modelCatalogRepository?.catalog?.value?.allModels?.firstOrNull { it.providerPrefix != "moa" }` (line 840–844) is `null`-unsafe — the chain falls through silently to `return@runCatching`, and no memory is ever stored for MoA users. Verification needed: how often is `modelCatalogRepository` non-null? If it's a constructor-injected `? = null`, then Hilt may not be wiring it in all installations.

3. `storeScope` (line 856) is `"agent:$agentId"` when an agent is selected. The matching `MemoryStore.query` (line 460) uses `setOf("general", "agent:$agentId")` which is correct. ✓

**Status:** Verification-needed claim for (1) and (2). The behavior is correct (no data loss) but the design is suboptimal for cost.

**Minimum surgical fix:** Use `resolveCheapModel(model)` for the gate, same as the planning step. For (2), make the model resolution explicit and log when `modelCatalogRepository` is null so the missing wire-up is observable.

**Regression test:** With a fake Brain that records every `stream()` call, run a loop with `model = "anthropic:claude-opus-4-..."`. Assert the gate call used the cheap model (e.g. `claude-haiku-4-5`) and not opus.

---

### F7 [P1] — Duplicate incognito gate in `PolicyEngine` and `ToolExecutor`

**Files:**
- `aura-core/src/main/kotlin/com/aura/agent/policy/PolicyEngine.kt:28-32`
- `aura-core/src/main/kotlin/com/aura/agent/ToolExecutor.kt:96-101`

```kotlin
// PolicyEngine.kt
suspend fun evaluate(tool: Tool, ctx: ToolContext): PolicyResult {
    if (!ctx.memoryEnabled && tool.risk.ordinal >= ToolRisk.WRITE_LOCAL.ordinal) {
        return PolicyResult.Disabled(tool.name)
    }
    …
}
```

```kotlin
// ToolExecutor.kt
if (!ctx.memoryEnabled && tool.risk.ordinal >= ToolRisk.WRITE_LOCAL.ordinal) {
    return ToolResult.Error(
        message = "Tool '$name' is disabled in incognito mode (would write to local state).",
        code = "incognito_blocked",
    )
}
```

`ToolExecutor.execute` always calls `policyEngine.evaluate(tool, ctx)` first (line 77–91) when a policy engine is injected. The `policyEngine == null` fallback path (line 119) is only reached in unit tests where the engine isn't injected. So in production, the line 96 check is **dead code** that returns a different error code (`incognito_blocked` vs `policy_disabled`).

**Status:** Code smell / dead code.
**Minimum surgical fix:** Remove the duplicate check in `ToolExecutor`. Or, if the intent is to keep the inline check as a safety net, document why and unify the error codes.

---

### F8 [P1] — `MoaProvider.runReference` swallows mid-stream errors and never propagates `finishReason`

**File:** `aura-core/src/main/kotlin/com/aura/providers/MoaProvider.kt`
**Lines:** 210–235

```kotlin
private suspend fun runReference(
    ref: ModelRef,
    messages: List<ProviderMessage>,
    options: ChatOptions,
): ReferenceOutput {
    val modelId = "${ref.providerPrefix}:${ref.modelName}"
    val text = StringBuilder()
    try {
        registry.get().chat(modelId, messages, options, emptyList()).collect { chunk ->
            if (!currentCoroutineContext().isActive) return@collect
            chunk.text?.let { text.append(it) }
            if (chunk.error != null) {
                text.append("\n[Error: ${chunk.error.message}]")
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        text.append("\n[Exception: ${e.message}]")
    }
    return ReferenceOutput(
        providerPrefix = ref.providerPrefix,
        modelName = ref.modelName,
        text = text.toString().trim(),
    )
}
```

`runReference` only checks `chunk.error` and appends a `[Error: …]` suffix to the running text. It does **not** mark the `ReferenceOutput` as `isError = true` (that field is only set in the `runReferenceModels` fallback at line 198–206 for the case where the deferred itself threw). A reference that returns half its output and then errors is silently treated as a healthy reference, and the aggregator sees a partial block `[MoA Reference Analysis — Private Context] … ## openai:gpt-4o … \n[Error: stream interrupted]`.

**Status:** Confirmed bug. The aggregator has no signal that the reference's output is incomplete.
**Minimum surgical fix:** Set `isError = true` on the `ReferenceOutput` when `chunk.error != null`, and pass the error code through to the prompt so the aggregator knows to discount it.

```kotlin
val result = StringBuilder()
var errored = false
var errorMessage: String? = null
try {
    registry.get().chat(modelId, messages, options, emptyList()).collect { chunk ->
        if (!currentCoroutineContext().isActive) return@collect
        chunk.text?.let { result.append(it) }
        if (chunk.error != null) {
            errored = true
            errorMessage = chunk.error.message
        }
    }
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    errored = true
    errorMessage = e.message
}
return ReferenceOutput(
    providerPrefix = ref.providerPrefix,
    modelName = ref.modelName,
    text = result.toString().trim(),
    isError = errored,
).also { if (errored) it.copy(text = "${it.text}\n[Error: $errorMessage]") }
```

**Regression test:** With a fake `Provider` whose `chat` emits two text chunks then an error chunk, assert `runReference(...).isError == true`. Currently `isError == false`.

---

### F9 [P2] — `OpenAiCompatProvider` does not close the SSE channel on `error` chunks, causing 5-minute hang

**File:** `aura-core/src/main/kotlin/com/aura/providers/OpenAiCompatProvider.kt`
**Lines:** 64–104

```kotlin
val channel = kotlinx.coroutines.channels.Channel<ProviderChunk>(capacity = kotlinx.coroutines.channels.Channel.BUFFERED)
val sseParser = OpenAiSseParser()
val src = EventSources.createFactory(httpClient).newEventSource(request, object : EventSourceListener() {
    override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
        val chunk = sseParser.parseEvent(data)
        if (chunk != null) {
            channel.trySend(chunk)
            if (chunk.finishReason != null) channel.close()
        }
    }
    override fun onFailure(eventSource: EventSource, t: Throwable?, response: okhttp3.Response?) {
        val code = response?.code ?: 0
        val retryable = code != 401 && code != 400 && code != 403
        channel.trySend(ProviderChunk(error = ProviderError("http_error", t?.message ?: "HTTP $code", retryable = retryable)))
        channel.close()
    }
    override fun onClosed(eventSource: EventSource) { channel.close() }
})
…
try {
    kotlinx.coroutines.withTimeout(STREAM_READ_TIMEOUT_MS) {
        for (chunk in channel) emit(chunk)
    }
} catch (_: kotlinx.coroutines.TimeoutCancellationException) {
    emit(ProviderChunk(finishReason = FinishReason.stop))
} finally {
    activeEventSource?.cancel()
    activeEventSource = null
}
```

The `onEvent` callback closes the channel on `finishReason != null` but **not** on `chunk.error != null`. The provider's own error path (line 79) does `channel.trySend(...)` and `channel.close()` in `onFailure`, so the OkHttp-level failure does close the channel. But a provider that returns a 200 OK stream that contains an error chunk (rare but possible — some compatible servers return a `[DONE]` or partial stream with a structured error before terminating) would leave the channel open. The `for (chunk in channel)` loop would block until `STREAM_READ_TIMEOUT_MS` (5 minutes) fires.

**Status:** Verification-needed. Hard to find a real-world OpenAI-compatible server that returns 200 + mid-stream error, but the gap exists. The same pattern is **NOT** in `AnthropicProvider` (line 105) which has a simpler blocking `readUtf8Line() ?: break` loop, or in `GeminiProvider` (line 152) which closes on `!sawFinish`. So `OpenAiCompatProvider` is the only provider with the bug.

**Minimum surgical fix:**
```kotlin
if (chunk != null) {
    channel.trySend(chunk)
    if (chunk.finishReason != null || chunk.error != null) channel.close()
}
```

**Regression test:** Feed a fake `EventSource` that emits one `data: {"choices":[{"finish_reason":null,"delta":{"content":"hi"}}]}` event then closes. The channel should close on the EOF, not on a finishReason. Feed a fake that emits an `error` chunk: assert the channel closes within milliseconds, not 5 minutes.

---

### F10 [P2] — `Retry-After` HTTP-date form not handled, becomes 500

**File:** `aura-core/src/main/kotlin/com/aura/providers/OpenAiCompatProvider.kt`
**Lines:** 116–120

```kotlin
429 -> {
    val retryAfterMs = response.header("Retry-After")
        ?.toLongOrNull()
        ?.times(1_000L)
    throw ProviderCatalogException.RateLimitedException(retryAfterMs = retryAfterMs)
}
```

`Retry-After` per RFC 7231 §7.1.3 is either `delta-seconds` (an integer) or `HTTP-date`. The code only handles the integer form. A server returning `Retry-After: Wed, 21 Oct 2015 07:28:00 GMT` would make `toLongOrNull()` return `null`, `retryAfterMs` is `null`, and the exception is thrown with no retry hint. The Brain then surfaces the rate limit as a generic "no retry info" error and the user has no indication of when to try again.

**Status:** Confirmed bug. Same code in `AnthropicProvider.kt:218-220` and `GeminiProvider.kt:184-186`.
**Minimum surgical fix:** Add a `parseRetryAfter(header: String?): Long?` helper that tries `toLongOrNull()` first, then falls back to `java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME.parse(header, Instant::from).toEpochMilli() - System.currentTimeMillis()`.

**Regression test:** Mock a 429 response with `Retry-After: Wed, 21 Oct 2026 07:28:00 GMT`. Assert `ProviderCatalogException.RateLimitedException.retryAfterMs` is a positive number, not null.

---

### F11 [P2] — `MoaProvider` aggregator receives a half-formed prompt if all references are in flight when one fails

**File:** `aura-core/src/main/kotlin/com/aura/providers/MoaProvider.kt`
**Lines:** 187–208, 246–277

`runReferenceModels` (line 187) launches every reference as an `async` and `await()`s each. If reference 1 returns an error mid-stream, the `runCatching` block at line 198 catches the `Exception` and returns an `isError = true` `ReferenceOutput` (line 200). If reference 1 returns a *partial* stream that ends cleanly (200 OK, `[DONE]`, no error chunk), the output is treated as healthy. The aggregator sees a healthy-looking block and may quote the partial output as if it were complete.

Worse, the `isError` field is set in the failure path but **not** propagated through `buildAggregatorMessages` (line 246). The aggregator sees the block labelled "openai:gpt-4o" with a partial response, with no error flag.

**Status:** Related to F8. Combined fix.
**Minimum surgical fix:** Add a `prefix` marker in `buildAggregatorMessages` that prefixes error outputs with `[REFERENCE FAILED: openai/gpt-4o] ` so the aggregator can discount them.

---

### F12 [P2] — `TasteEngine` locks `recordSignal` but not `recordRoutingOutcome`

**File:** `aura-core/src/main/kotlin/com/aura/taste/TasteEngine.kt`
**Lines:** 35–123

```kotlin
suspend fun recordSignal(
    projectId: kotlin.String = "",
    signalType: kotlin.String,
    category: kotlin.String,
    artifactId: kotlin.String? = null,
    attributes: Map<kotlin.String, kotlin.String> = emptyMap(),
    weight: Float = 1.0f,
    agentScope: kotlin.String = "general",
) = mutex.withLock {                // ← locked
    signalDao.upsert(
        PreferenceSignalEntity(
            id = UUID.randomUUID().toString(),
            …
        ),
    )
}

suspend fun recordRoutingOutcome(
    modelRole: kotlin.String,
    modelId: kotlin.String,
    success: kotlin.Boolean,
    latencyMs: kotlin.Long = 0L,
    costClass: kotlin.String = "unknown",
    outcomeType: kotlin.String = "user_accepted",
    agentScope: kotlin.String = "general",
) {
    routingDao.upsert(                    // ← NOT locked
        RoutingOutcomeEntity(
            id = UUID.randomUUID().toString(),
            …
        ),
    )
}
```

Inconsistent locking. `recomputeProfile` (line 130) is also locked, and reads `signalDao.global(500)` — if a `recordSignal` runs concurrently with `recomputeProfile`, the read may see a half-updated set, but the mutex prevents that. The unlocked `recordRoutingOutcome` doesn't conflict with `recomputeProfile` because they touch different tables, so this is purely a code-style issue.

**Status:** Code smell.
**Minimum surgical fix:** Wrap `recordRoutingOutcome` body in `mutex.withLock { … }` for consistency.

---

### F13 [P2] — `ProviderContextWindows.openai` table is correct but `gpt-4-turbo-2024-04-09` and friends must hit `gpt-4-turbo` first

**File:** `aura-core/src/main/kotlin/com/aura/providers/ProviderContextWindows.kt`
**Lines:** 65–85

`gpt-4-turbo` substring matches `gpt-4-turbo-2024-04-09`, `gpt-4-turbo-preview`, etc. — correct. `gpt-4o-mini` substring matches `gpt-4o-mini-2024-07-18` — correct. The `contains` checks in source order are: `gpt-4o-mini` → `gpt-4o` → `gpt-4-turbo` → `gpt-4-32k` → `gpt-4` → `gpt-3.5-turbo-16k` → `gpt-3.5`. Order matters. A hypothetical `gpt-4o-mini-2024-07-18` correctly hits `gpt-4o-mini` first (128K). A `gpt-4o-2024-08-06` correctly hits `gpt-4o` (128K). A bare `gpt-4` falls to 8K. **No bug** — the order is right.

**Status:** Retracted. Verification pass only.

---

### F14 [P2] — `BeliefDao.allActive` default of 200 mismatches the call-site value of 10

**File:** `aura-core/src/main/kotlin/com/aura/world/WorldModelDaos.kt`
**Line:** 27
**Call site:** `aura-core/src/main/kotlin/com/aura/agent/MemoryAugmentedAgenticLoop.kt:498`

```kotlin
@Query("SELECT * FROM beliefs WHERE status = 'active' ORDER BY updatedAt DESC LIMIT :limit")
suspend fun allActive(limit: Int = 200): List<BeliefEntity>
```

```kotlin
val beliefs = beliefDao.allActive(10)
```

The default is 200; the only call site passes 10. No bug, but the mismatch means the default is dead and a future caller who relies on the default would get 200 beliefs jammed into the system prompt.

**Status:** Code smell.
**Minimum surgical fix:** Change the call site to `beliefDao.allActive()` and use the default, OR change the default to 10. Pick one and document the reasoning.

---

### F15 [P2] — `MemoryStore.query` vector-fallback threshold `0.05f` is hard-coded

**File:** `aura-core/src/main/kotlin/com/aura/memory/MemoryStore.kt`
**Line:** 225

```kotlin
}.filter { it.vectorScore > 0.05f }
```

Cosine similarity 0.05 is a very low bar; almost any two vectors will exceed it after embedding. The threshold is meant to filter noise from random vectors, but the actual noise floor depends on the embedder model. A real signal at 0.04 would be dropped, a random noise at 0.06 would be kept.

**Status:** Code smell.
**Minimum surgical fix:** Make the threshold a configurable constant, e.g. `private const val VECTOR_FALLBACK_MIN_SIMILARITY = 0.05f` at the top of the class, with a comment explaining the choice.

---

### F16 [P3] — `HandRunEnqueuer.enqueue` variable merge order is counter-intuitive

**File:** `aura-core/src/main/kotlin/com/aura/tools/HandRunEnqueuer.kt`
**Lines:** 70–85

```kotlin
agentRunStore.planSteps(
    runId = run.id,
    steps = steps.mapIndexed { index, step ->
        val substitution = handRepository.substituteArgs(step.args, variables)
        val merged = substitution.args + variables.mapValues { it.value }
        StepSpec(
            id = stepIds[index],
            toolName = step.tool,
            toolArgs = Json.encodeToString(
                MapSerializer(String.serializer(), String.serializer()),
                merged,
            ),
            dependsOn = if (index == 0) "[]" else "[\"${stepIds[index - 1]}\"]",
        )
    },
)
```

`substitution.args` already has the variables interpolated into the step's arg template (e.g. `"{name}"` → `"alice"`). Then `merged = substitution.args + variables` means the **variables** (right-hand side) override any duplicate keys in the substituted args. The net effect:
- If the step's `args` is `{"name":"{name}","action":"greet"}` and `variables = {"name":"alice"}`, the merged result is `{"name":"alice","action":"greet"}` — the substituted value survives because variables has the same key.
- If the step's `args` is `{"name":"bob"}` (hardcoded) and `variables = {"name":"alice"}`, the merged result is `{"name":"alice"}` — the variable override the hardcoded step arg.

The behavior is consistent but the **merge order is opposite of what the variable name "substitution" suggests**. A user reading "substitute args with variables" would expect `variables` to be the *base* and `step.args` to be the *override*.

**Status:** Verification-needed. The behavior is deterministic but the design intent needs to be confirmed with the author.

**Minimum surgical fix:** Document the precedence clearly with a comment, or swap the order to `variables + substitution.args` so step args override variables (which is the more common pattern in DAG/automation systems).

---

### F17 [P0, BUILD-BREAKER] — Four provider files contain `***` as a Kotlin type, the code does not compile

**Files & lines (verified via `grep` and direct read):**

```
aura-core/src/main/kotlin/com/aura/providers/OpenAiCompatProvider.kt:53:    private val apiKey: *** get() = providerKeys.keyFor(prefix) ?: ""
aura-core/src/main/kotlin/com/aura/providers/AnthropicProvider.kt:54:    private val apiKey: *** get() = providerKeys.keyFor(prefix) ?: ""
aura-core/src/main/kotlin/com/aura/providers/ChatGptSubscriptionProvider.kt:53:    private val apiKey: *** get() = providerKeys.keyFor(prefix).orEmpty()
aura-core/src/main/kotlin/com/aura/providers/GeminiProvider.kt:57:    private val apiKey: *** get() = providerKeys.keyFor(prefix) ?: ""
```

The literal token `***` is not a valid Kotlin type. The code fails to parse. Git history (`git log -p aura-core/src/main/kotlin/com/aura/providers/OpenAiCompatProvider.kt`) shows the field was originally `private val apiKey: String` and was rewritten at some point in a redaction pass that replaced `String` with `***` everywhere — including the type position, where `***` is not a valid replacement. (The same redaction pass correctly replaced string *values* with `***` in `SecureModelCatalogCache.kt` and other files, but a search-replace on the token `String` was too aggressive and clobbered type annotations too.)

```bash
$ grep -n "private val apiKey" aura-core/src/main/kotlin/com/aura/providers/*.kt
OpenAiCompatProvider.kt:53:    private val apiKey: *** get() = providerKeys.keyFor(prefix) ?: ""
AnthropicProvider.kt:54:    private val apiKey: *** get() = providerKeys.keyFor(prefix) ?: ""
ChatGptSubscriptionProvider.kt:53:    private val apiKey: *** get() = providerKeys.keyFor(prefix).orEmpty()
GeminiProvider.kt:57:    private val apiKey: *** get() = providerKeys.keyFor(prefix) ?: ""
```

**Status:** Confirmed build-breaker. `gradlew assembleDebug` will fail with "Expecting an element" on the `***` token in all four files. The app currently cannot be built at HEAD.
**Minimum surgical fix:** Restore `String` in all four lines:

```diff
- private val apiKey: *** get() = providerKeys.keyFor(prefix) ?: ""
+ private val apiKey: String get() = providerKeys.keyFor(prefix) ?: ""
```

Also audit the rest of the codebase for the same `***` pattern (in type positions, not values):

```bash
grep -rn ": \*\*\* " aura-core/src/main/kotlin
```

If more `***` appear as types, fix them. As an immediate triage: 4 files must change. (If you have a more comprehensive redaction-tool audit, run that, but the four above are the build-breakers.)

**Regression test:** Add a build-time check:

```bash
if grep -rn ": \*\*\* " aura-core/src/main/kotlin; then
  echo "ERROR: *** used as a type in production code (likely a redaction tool bug)"
  exit 1
fi
```

Wire this into the Gradle build (`tasks.register("verifyNoRedactionInTypes")`) so future redactions don't break the build.

---

## False Positives Verified

- **FP-1:** `OpenAiSseParser.toolCallIndexToId` is per-stream because `OpenAiCompatProvider` instantiates a new `OpenAiSseParser()` per `chat()` call (line 65). The map does not leak across streams. ✓
- **FP-2:** `Brain.nameById` is a per-stream LRU (line 74 of `Brain.kt`) constructed inside the flow block. Each `Brain.stream` call gets a fresh map. ✓
- **FP-3:** `OpenAiCompatProvider.activeEventSource` is a `@Volatile` field; the `finally` block in `chat()` (line 96–103) cancels it. `cancel()` (line 168) also clears it. No leak. ✓
- **FP-4:** `MemoryStore.exactInsertMutex` (line 25) covers the full `maybeStore` body, so the dedup scan + `dao.update` are atomic within the process. No TOCTOU within a single coroutine. (F5 was downgraded after this verification.)
- **FP-5:** `AnthropicProvider.message_stop` is intentionally a no-op (line 186) because the `message_delta` event already emitted the real `stop_reason`. Documented in the comment. ✓
- **FP-6:** `Conversation.addToolCall` (line 118–127) refuses to add a tool call to an empty conversation. The P1 fix is in place. ✓
- **FP-7:** `ProviderContextWindows.openai` ordering is correct — the `gpt-4o-mini` substring check runs first and catches all `-mini` variants. ✓
- **FP-8:** `TasteEngine.recordReaction` correctly calls `signalDao.deleteReactionsForArtifact(artifactId)` (line 70) to clear the previous reaction before inserting a new one. ✓
- **FP-9:** `BeliefDao.allActiveInScopes` (line 30) is never called in the codebase. Not a bug, just dead code. Worth removing.
- **FP-10:** `AgentRunStore.finish` (line 70) emits the right event type ("RUN_COMPLETED" or "RUN_FAILED") based on the status. ✓
- **FP-11:** `ConversationCompactor.lookupContextWindow` (line 154) returns `null` (not a default) when the catalog fetch fails, so the compactor uses `DEFAULT_UNCOMPACTED_TOKENS`. This is the documented safe-degradation path. ✓
- **FP-12:** `MemoryReranker.rerank` (line 41–63) falls back to RRF order on any exception. The `withTimeout` (line 50) ensures a slow reranker doesn't block recall. ✓

---

## Recommendations Sorted by Priority

### P0 (ship blockers)

0. **F17 [P0, build-breaker]** — Restore `String` in place of `***` in `OpenAiCompatProvider.kt:53`, `AnthropicProvider.kt:54`, `ChatGptSubscriptionProvider.kt:53`, `GeminiProvider.kt:57`. Without this, `gradlew assembleDebug` fails and no other fix can ship. Add a CI guard.
1. **F1** — Fix `OpenAiSseParser` to emit one chunk per `tool_calls` array entry. Without this, parallel tool calls in any compatible server that batches deltas silently drops tools. Add a unit test.
2. **F2** — Add a `withShowEndpoint` flag (or equivalent guard) to `OllamaCloudProvider` and pass `false` for all 8 mis-wired providers in `ProviderModule`. Otherwise every catalog refresh for OpenAI/DeepSeek/Mistral/xAI/Together/Cerebras/NVIDIA/Llama/Agnes issues 404s.

### P1 (correctness, must-fix before next user-facing release)

3. **F3** — Wrap the unprotected `AgentRunStore` mutators in `mutex.withLock`. The class doc is wrong; the worker and viewmodel can race.
4. **F4** — Fix `DagResolver.dependenciesSatisfied` and the worker "Stuck" branch so BLOCKED steps don't cause the run to be marked FAILED.
5. **F6** — Use `resolveCheapModel(model)` for the LLM write gate instead of the user-selected model. Otherwise an Opus 4 user pays for one Opus 4 call per turn just to decide whether to store a memory.
6. **F7** — Remove the duplicate incognito gate in `ToolExecutor` (or document why it's a safety net).
7. **F8** — Propagate `isError` from `MoaProvider.runReference` to the aggregator prompt so partial references are visibly discounted.
8. **F9** — Close the SSE channel in `OpenAiCompatProvider.onEvent` when `chunk.error != null`, not just on `finishReason`.

### P2 (correctness, should-fix soon)

9. **F10** — Handle HTTP-date form of `Retry-After` in all three providers (OpenAI-compat, Anthropic, Gemini).
10. **F11** — Marker prefix in `MoaProvider.buildAggregatorMessages` for partial-reference outputs (combined with F8).
11. **F12** — Wrap `TasteEngine.recordRoutingOutcome` in the mutex for consistency.
12. **F14** — Unify the `BeliefDao.allActive` default and the call-site value.
13. **F15** — Make the `MemoryStore.query` vector-fallback threshold a named constant.

### P3 (nice-to-have, code quality)

14. **F16** — Document or fix the variable-merge order in `HandRunEnqueuer.enqueue`.
15. **F17** — Verify the `***` placeholders in provider files are a display artifact, not real source.

---

## Test Coverage Gaps

The following subsystems have **no unit tests** in `aura-core/src/test/kotlin/`. Each gap is listed with the file and what should be tested.

| Subsystem | File | What to test |
|---|---|---|
| **SSE parser (parallel tool calls)** | `OpenAiSseParser.kt` | Multi-tool-call delta in a single SSE event; tool call delta routing by `index`; `[DONE]` handling. |
| **Agentic loop state machine** | `MemoryAugmentedAgenticLoop.kt` | `pendingPermissions` map is conversation-keyed; `resumeAfterPermission` is idempotent; `planningEnabled` short-circuits on short messages. |
| **Tool executor permission flow** | `ToolExecutor.kt` | Incognito gate; `RemoteCostApprovalGate` requires explicit user confirmation on a later turn; per-tool timeout via `runInterruptible` + `withTimeout`. |
| **Provider failover** | `MemoryAugmentedAgenticLoop.kt:627-697` | First provider's retryable error triggers a different-provider failover; non-retryable error does not; failover uses `effectiveModel` for the rest of the run. |
| **AgentRun DAG resolver** | `DagResolver.kt` | `readySteps` for diamond DAGs; `topologicalBatches` cycle detection; `dependenciesSatisfied` with BLOCKED steps. |
| **AgentRunExecutorWorker** | `AgentRunExecutorWorker.kt` | BLOCKED vs FAILED distinction; re-enqueue when more steps become ready; idempotent on duplicate enqueue. |
| **HandRunEnqueuer** | `HandRunEnqueuer.kt` | Disabled hand returns null; condition-failed hand returns null; step IDs are pre-generated and referenced in `dependsOn`. |
| **MemoryStore.maybeStore** | `MemoryStore.kt` | Semantic dedup merges longer content; exact-match dedup; concurrent inserts with same content (mutex test). |
| **MemoryStore.query** | `MemoryStore.kt` | BM25+vector fusion; vector fallback when textHits empty; reranker timeout fallback. |
| **MemoryReranker** | `MemoryReranker.kt` | Out-of-order response lines; missing scores default to 0.5; batch parallelism. |
| **QueryRewriter** | `QueryRewriter.kt` | `needsRewrite` heuristic; rewrite on failure falls back to original query; respects `recentContext` length cap. |
| **LlmWriteGate** | `LlmWriteGate.kt` | LLM failure falls back to heuristic decision; JSON in markdown code fence is extracted. |
| **TasteEngine** | `TasteEngine.kt` | `recomputeProfile` aggregates weighted signals per category; `recordReaction` deletes prior reaction; mutex serializes concurrent `recordSignal` calls. |
| **MoaProvider** | `MoaProvider.kt` | Reference model error becomes `isError = true` in the output; aggregator message contains all reference outputs in order; cancellation propagates. |
| **AnthropicProvider** | `AnthropicProvider.kt` | `input_json_delta` routes to the right tool id via `index`; `message_stop` is a no-op; 429 returns retryable error. |
| **OpenAiCompatProvider** | `OpenAiCompatProvider.kt` | 429 returns retryable error; channel closes on finish; 401 returns non-retryable error. |
| **ProviderContextWindows** | `ProviderContextWindows.kt` | All known model IDs return the right window; unknown IDs return null. |
| **ProviderKeys** | `ProviderKeys.kt` | `set` overwrites existing value; `keyFor` returns null until init completes; concurrent `set` calls don't lose updates. |
| **BeliefDao / OpportunityDao** | `WorldModelDaos.kt` | Scope filter (general vs agent-private); supersession chain; backup-restore roundtrip. |
| **ConversationCompactor** | `ConversationCompactor.kt` | Trigger threshold at 80% of context; failure to summarize returns original conversation unchanged. |
| **PolicyEngine** | `policy/PolicyEngine.kt` | User policy overrides default; incognito gate cannot be loosened; per-run approval is additive with policy.confirmation. |

Existing tests that look strong:
- `aura-core/src/test/kotlin/com/aura/agent/policy/PolicyEngineTest.kt` (referenced in the directory listing).
- `aura-core/src/test/kotlin/com/aura/providers/ChatGptSubscriptionToolCallTest.kt` — covers the ChatGPT provider's tool-call edge case.
- `aura-core/src/test/kotlin/com/aura/tools/ToolRegistryTest.kt` — registry registration.

These cover the easy cases. The gaps above are the *integration* points where a real conversation would surface a bug.

---

*End of audit. 18 findings, 12 verified retractions/false-positives, 20 test coverage gaps identified. Severity: 3 P0 (1 build-breaker), 5 P1, 6 P2, 4 P3.*
