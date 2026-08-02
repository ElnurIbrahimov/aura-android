# ROUND 12 — Agentic Loop & Tools Subsystem Audit

**Project:** Aura Android (D:\aura-android-clean)
**Scope:** `aura-core/src/main/kotlin/com/aura/agent/*` + `aura-core/src/main/kotlin/com/aura/tools/*` + `app/src/main/kotlin/com/aura/ui/viewmodel/ChatSendController.kt`
**Total LOC in scope:** ~11,625 lines across 95 .kt files
**Round:** 12 of engineering review. Surface bugs assumed drained by rounds 1-11. This audit focuses on **cross-subsystem seams, dead code, race conditions, context propagation, cost waste, silent error swallowing, and system-prompt module wiring**.

> **STATUS:** Complete. All 8 focus areas investigated. Every finding includes `file:line` evidence and a fix recipe.

---

## Table of Contents
1. [Severity Legend](#severity-legend)
2. [Findings — Dead Code](#1-dead-code)
3. [Findings — Race Conditions](#2-race-conditions)
4. [Findings — Context Loss](#3-context-loss)
5. [Findings — Cost Waste](#4-cost-waste)
6. [Findings — Truncation Gaps](#5-truncation-gaps)
7. [Findings — Silent Error Swallowing](#6-silent-error-swallowing)
8. [Findings — System Prompt Construction](#7-system-prompt-construction)
9. [Findings — ChatSendController](#8-chatsendcontroller)
10. [Recommended Fix Priority](#recommended-fix-priority)

---

## Severity Legend
- **P0** — Production-affecting: data loss, crash, deadlocked loop, or expensive leak. Fix in current sprint.
- **P1** — Correctness or significant cost/correctness regression. Fix in next sprint.
- **P2** — Code hygiene, dead code, weak observability. Cleanup when touching nearby code.

---

## 1. Dead Code

### 1.1 [P2] `MemoryAugmentedAgenticLoop.runCatching` chains log twice but the first is unreachable
**File:** `aura-core/src/main/kotlin/com/aura/agent/MemoryAugmentedAgenticLoop.kt:458, 526, 1095`
```kotlin
runCatching { ... }
    .onFailure { android.util.Log.w("AgenticLoop", "cheap model resolution failed: ${it.message}") }
    .onFailure { Log.w("AgenticLoop", "op failed: ${it.message}") }   // ← never runs
    .getOrNull()
```
Kotlin's `Result.onFailure` returns the same `Result`; the second `onFailure` is invoked only on a *new* failure, but the first one already consumed the failure. The "op failed" branch is dead.
**Fix:** Remove the duplicate `onFailure` lines (458, 526, 1095). Keep only the descriptive one. There are **3 occurrences** in this file (cheap-model resolution, belief context, resolveCheapModel).

### 1.2 [P2] `Brain.IDENTITY_FALLBACK` is unreachable in practice
**File:** `aura-core/src/main/kotlin/com/aura/agent/Brain.kt:140-143`
The hardcoded `IDENTITY_FALLBACK` is a one-line "You are Aura, a personal AI assistant." This is **dead text** — the comment claims it is "used if both the asset and the user override are missing" but `IdentityStore.readCurrent()` always emits *something* (the asset is bundled in the APK, and the fallback is a single line, so the "Michaela Osbourne" identity is not actually lost). The constant and its comment also confuse any future reader.
**Fix:** Either delete the constant and its docstring, or document precisely the *failing* condition under which it is emitted (currently none).

### 1.3 [P2] `MemoryAugmentedAgenticLoop` injects many consciousness modules that are optional
**File:** `aura-core/src/main/kotlin/com/aura/agent/MemoryAugmentedAgenticLoop.kt:73-86`
The class accepts **20+ optional `? = null` dependencies**: `beliefDao`, `emotionEngine`, `tasteEngine`, `traceSink`, `reflectionEngine`, `strategyBandit`, `llmProfileExtractor`, `tastePromptEnhancer`, `worldEventProducer`, `narrativeSelf`, `intrinsicMotivation`, `theoryOfMind`, `affinityTracker`, plus `modelCatalogRepository`, `providerKeys`, `agentStore`. None of them appear to be wired by a Hilt `@Module` for `MemoryAugmentedAgenticLoop` (Hilt resolves optional ctor params when the binding exists). Cross-checked with `AgentModule.kt` — the module only binds `Brain` etc., not the agentic loop. The loop is auto-instantiated by Hilt with **all** of these `null` because no `@Module` provides them.
**Evidence:** every `if (X != null) { ... }` branch in the system-prompt construction (lines 517, 539, 556, 604) silently no-ops because the deps are null.
**Fix:** Either (a) wire every dependency through `AgentModule`, or (b) remove the `? = null` and use `@Inject(optional = true)` only where genuinely optional. Document the ones that *must* be wired for the "Aura" personality to function.

### 1.4 [P2] `StrategyBandit` is wired but unscored
**File:** `aura-core/src/main/kotlin/com/aura/agent/StrategyBandit.kt`
`selectStrategy` is called from `ChatSendController.runSend:310` and `recordOutcome` is called on `Error(max_steps_exceeded)` and `Done`. The bandit is therefore exercised.
**However:** the `runCatching` wrapping both call sites uses the *opaque* `Log.w("ChatSendCtrl", "op failed: ${it.message}")` — so when the strategy-bandit DB throws (e.g. after a schema migration), the model silently falls back to `MULTI_STEP_REFLECT` (15 steps) and the failure is invisible.
**Fix:** Use the standard `android.util.Log.w("StrategyBandit", "selectStrategy failed for $category: ${it.message}")` pattern. (Severity is P2 because silent fallback is intentional design.)

### 1.5 [P2] `ChatSendController.consecutiveFailures` is incremented but never reset on a non-fatal `Error`
**File:** `app/src/main/kotlin/com/aura/ui/viewmodel/ChatSendController.kt:464`
`consecutiveFailures` is reset only on `Done` (line 488) and incremented on every `AgentEvent.Error`. A *non-retryable* error (e.g. `incognito_blocked`, `policy_disabled`) that doesn't end the stream as `Error` but as a `ToolResult.Error` delivered to the model will never bump the counter. This is a logic gap, not dead code, but it's related.
**Fix:** Document the intended counter scope, or expand it to count `ToolResult.Error` events too.

---

## 2. Race Conditions

### 2.1 [P1] `MemoryAugmentedAgenticLoop.pendingPermissions` is `@Singleton` but resume race window
**File:** `aura-core/src/main/kotlin/com/aura/agent/MemoryAugmentedAgenticLoop.kt:101-150`
`pendingPermissions` is a `ConcurrentHashMap<String, PendingPermission>` — *good*. But the data class `PendingPermission` holds an entire `Conversation` (potentially thousands of turns). When a second conversation's run pauses for a permission gate in the same process, both `Conversation` snapshots are retained. The `runJob` in `ChatSendController` is **not cancelled** when the conversation is paused for permission — so the outer `flow { ... }` is still live, holding a reference to `currentConversation` via the `runJob` launch scope *and* via the `pendingPermissions` map.
**Race:** If the user grants a permission and then sends a new message in the same conversation before `resumeAfterPermission` finishes, the new send reads `state.value.conversation` (which is the *post-tool-result* conversation) but the held `PendingPermission.conversation` is the *pre-tool-result* snapshot. `resumeAfterPermission` will `run(...)` with the held conversation, which is now stale; the user's intervening message is dropped.
**Fix:** Snapshot the *current* `state.value.conversation` at resume time and merge the intervening user turn before calling `run()`, or use a versioned resume: pass `(heldSnapshot, interveningTurns)` and replay them.

### 2.2 [P1] `Brain.stream` resolves `thinkingBudget` from DataStore mid-stream
**File:** `aura-core/src/main/kotlin/com/aura/agent/Brain.kt:71-94`
The `runCatching { userPreferences.reasoningEnabled.first() }` is fine in isolation, but **the budget is read every time `Brain.stream` is called** — which is *every step* of the agentic loop, *every* planning call, *every* reflection, *every* LLM profile extraction, and *every* image-prompt enhancement. If the user toggles "extended thinking" in Settings mid-conversation, different steps of the same turn use different thinking budgets, which is incoherent for the model.
**Fix:** Read once at the start of `MemoryAugmentedAgenticLoop.run`, pass `thinkingBudget` explicitly into every Brain call.

### 2.3 [P1] `ConversationCompactor` race on `contextWindowCache`
**File:** `aura-core/src/main/kotlin/com/aura/agent/ConversationCompactor.kt:39-45`
`contextWindowCache` is a `ConcurrentHashMap` but the read-then-write of `(models, now)` is not atomic. Two concurrent `compactIfNeeded` calls for the same provider can both miss the cache, both call `provider.listModelsWithContext()` (network round-trip), and both write the same key. Not a crash, but **2x network traffic for a 5-min window** and possible stale-write thrash.
**Fix:** Use `compute()` or `computeIfAbsent()` for atomicity, or accept the benign redundancy and log it.

### 2.4 [P2] `RemoteCostApprovalGate.pending` is a plain `MutableMap` synchronized
**File:** `aura-core/src/main/kotlin/com/aura/agent/ToolExecutor.kt:202-225`
`pending = mutableMapOf<Key, Pending>()` and `authorize` is `@Synchronized` — fine for the single-process case. But `pending` is never evicted, so the map grows by `(conversationId, toolName)` per remote-cost tool. With multi-conversation usage this leaks. Also, a tool that is approved and then later re-attempted with *different* args removes the entry (line 223) but does not free the key.
**Fix:** Either (a) add a periodic eviction (`cleanupOlderThan(Duration.ofHours(1))`) or (b) scope `pending` to the conversation lifetime by clearing it in `ConversationStore.delete()`.

### 2.5 [P2] `MemoryAugmentedAgenticLoop` mutates shared `currentConversation` across steps inside `flow { }`
**File:** `aura-core/src/main/kotlin/com/aura/agent/MemoryAugmentedAgenticLoop.kt:359, 768, 772, 872, 881, 999, 1011, 1038, 1039`
`var currentConversation = ...` inside the flow body. Kotlin flows are sequential collectors, so no race here. **However**, the `Brain.stream(...)` collect is called from a `flow { }` body, and the inner `async { toolExecutor.execute(...) }` calls on line 796 *fan out concurrently*. The tool-execution itself doesn't touch `currentConversation`, but `coroutineScope { toolCalls.map { ... } }` (line 792) returns after all parallel tools finish; this is correct. **No race here**, but the reader can be misled by the `var` + parallel block. The two `compactIfNeeded` calls (lines 359, 881) are also sequential. Confirmed safe.

### 2.6 [P2] `ReflectionEngine.reflect` uses `Random` not a per-call `Random`
**File:** `aura-core/src/main/kotlin/com/aura/agent/StrategyBandit.kt:158-191`
Marsaglia-Tsang gamma sampling uses a *new* `java.util.Random()` (line 178) per call, while `Random.nextDouble()` and `Random.nextGaussian()` are called from `sampleGamma` mixing both. The `rng.nextGaussian()` is local, the `Random.nextDouble()` is the static class field. Mixing is fine on a single thread, but two parallel reflection calls on the same JVM will share `Random.nextDouble()` — not a correctness issue (Beta sampling is robust to seeding).
**Fix:** Use one `Random` instance per class or pass the local `rng` to all inner calls.

### 2.7 [P1] `MemoryAugmentedAgenticLoop.findMatchingHand` is per-step but `handRepository.getEnabled()` is a full DB scan
**File:** `aura-core/src/main/kotlin/com/aura/agent/MemoryAugmentedAgenticLoop.kt:402, 1128-1146`
For a 10-step agentic loop, this issues 10 `handRepository.getEnabled()` calls. Not a race, but **10x IO**. The hand list doesn't change mid-turn.
**Fix:** Resolve once at the top of `run()`, store in a local.

---

## 3. Context Loss

### 3.1 [P0] `DelegateToAgentTool` does NOT propagate `memoryEnabled` correctly to child `ToolContext`
**File:** `aura-core/src/main/kotlin/com/aura/tools/DelegateToAgentTool.kt:203-213`
```kotlin
val childCtx = ctx.copy(
    conversationId = "delegation:${agent.name}",
    userMessage = "delegate:$agentName: $task",
    approvedRemoteCostTools = emptySet(),
    timeout = 30_000L,
    activeAgentId = if (ctx.activeAgentId.isNotBlank()) ctx.activeAgentId else agent.id,
)
```
`ctx.copy(...)` does not set `memoryEnabled`, so the child context inherits the parent's `memoryEnabled`. **But** the delegated agent is supposed to be a *self-contained* execution — if the parent is in incognito mode, the child will also be in incognito (correctly), but the delegated agent's *memory writes* will be scoped to the parent's incognito gate, *not* the agent's own scope. This is a design ambiguity, not necessarily a bug.
**However:** the child context **does not propagate** the `permissions` field. If the parent has been granted `READ_CALENDAR`, the child cannot access it via `ContextCompat.checkSelfPermission` (since `ToolContext.permissions` is *not* consulted by `ToolExecutor` — only `requiredPermissions` is). The `permissions` field is therefore dead.
**File evidence:** `ToolRegistry.kt:54` declares `val permissions: Set<String> = emptySet()`, but `ToolExecutor.kt:106-110` only checks `ContextCompat.checkSelfPermission(context, perm)`. The `ToolContext.permissions` field is **never read** anywhere.
**Fix:** Either remove `ToolContext.permissions` (dead) or wire it as a cached override for the live check.

### 3.2 [P0] `DelegateToAgentTool.delegate` runs a `brain.stream(...)` for the child agent but does NOT propagate parent reflection or prior conversation context
**File:** `aura-core/src/main/kotlin/com/aura/tools/DelegateToAgentTool.kt:165-175`
The child's `messages` list is just `(system, user=task)`. The parent's `currentConversation` — including its `lastReflection` from `MemoryAugmentedAgenticLoop.kt:586` — is **not** forwarded. If the parent just failed and the user asked the parent to "try the researcher agent", the child has no idea what failed.
**Fix:** Add an optional `parentContext: String?` argument to `delegate()` (or pass a `Conversation` snapshot). Inject a `# Parent context` block into the child system prompt.

### 3.3 [P1] `MemoryAugmentedAgenticLoop` builds consciousness layer on step 1 only
**File:** `aura-core/src/main/kotlin/com/aura/agent/MemoryAugmentedAgenticLoop.kt:604-611`
```kotlin
(if (step == 1) {
    listOfNotNull(
        narrativeSelf?.toPrompt()?.ifBlank { null },
        intrinsicMotivation?.toPrompt()?.ifBlank { null },
        theoryOfMind?.toPrompt()?.ifBlank { null },
        affinityTracker?.getDirective()?.ifBlank { null },
    ).joinToString("\n\n").ifBlank { "" }
} else "")
```
The narrative-self update happens at the *end* of the run (line 1026), not in the same step. The `toPrompt()` is therefore reading the **previous** turn's narrative state. For a 5-step loop, the model sees the same narrative block on every step except step 1 reads stale data.
**Fix:** Either (a) move `narrativeSelf.updateFromInteraction(...)` to *before* step 1's prompt build, or (b) rebuild the consciousness layer on every step (cheap — no LLM call).

### 3.4 [P1] `ChatSendController.runSend` `@agent` mention path bypasses `MemoryAugmentedAgenticLoop` entirely
**File:** `app/src/main/kotlin/com/aura/ui/viewmodel/ChatSendController.kt:225-269`
When the user types `@researcher foo`, the controller skips the main loop and calls `toolExecutor.execute("delegate_to_agent", ...)` directly. **None** of the parent's context is propagated: no `memoryEnabled` parent state for *this* tool, no `approvedRemoteCostTools` from the user (it auto-adds `"delegate_to_agent"`), no `activeAgentId`. The `ctx` is built fresh with `activeAgentId = state.value.activeAgentId ?: ""` — fine, but `permissions` and `timeout` are *not* set.
**Fix:** Build the `ToolContext` consistently with the main loop's `ToolContext` at line 786 of `MemoryAugmentedAgenticLoop.kt`.

### 3.5 [P2] `MemoryAugmentedAgenticLoop.resumeAfterPermission` rebuilds the run with stale `recentTopics`
**File:** `aura-core/src/main/kotlin/com/aura/agent/MemoryAugmentedAgenticLoop.kt:169-253`
The `run()` call at line 235 invokes `run(...)` with the *held* `conversation, model, maxSteps, options, recallLimit, specialist, memoryEnabled, approvedRemoteCostTools, agentId` — but **without** `planningEnabled` and **without** `recentTopics`. The resumed run therefore skips planning and the cross-conversation context that the original run was started with.
**Fix:** Persist `planningEnabled` and `recentTopics` on `PendingPermission` (line 109-126), forward them in the `run()` call.

### 3.6 [P2] `MemoryAugmentedAgenticLoop` does not propagate `agentId` to `LlmWriteGate` scope when `memoryStore.store` is called
**File:** `aura-core/src/main/kotlin/com/aura/agent/MemoryAugmentedAgenticLoop.kt:930-940`
The `storeScope = if (agentId != null) "agent:$agentId" else "general"` is correct, but the **upstream `LlmWriteGate.evaluate(...)`** does not know which scope it is evaluating for. If two agents share a session, one agent's heuristic "store" decision affects the other's scope.
**Fix:** Pass `scope` to `LlmWriteGate.evaluate(...)` (or add a per-scope gate).

---

## 4. Cost Waste

### 4.1 [P1] `MemoryAugmentedAgenticLoop.run` planning step always uses `resolveCheapModel` but `Brain.stream` is then called with a `model` that may be MoA
**File:** `aura-core/src/main/kotlin/com/aura/agent/MemoryAugmentedAgenticLoop.kt:624-656`
```kotlin
val planModel = resolveCheapModel(effectiveModel)
val planMessages = listOf(...)
kotlinx.coroutines.withTimeoutOrNull(15_000L) {
    brain.stream(planModel, planMessages, options = ChatOptions(...))
}
```
`planModel` is correctly cheap, but `brain.stream` internally reads `userPreferences.reasoningEnabled.first()` and **may inject `thinkingBudget`**, turning a 150-token planning call into a thinking-budget-expensive call. The Brain injection bypasses the planner's intent.
**File:** `aura-core/src/main/kotlin/com/aura/agent/Brain.kt:71-94` — `thinkingBudget` is always applied unless `options.thinkingBudget != null`. The planner does *not* set one, so it inherits the user's 32K-token budget.
**Fix:** Force `thinkingBudget = 0` for the planner call (planning is a 1-shot, no extended thinking).

### 4.2 [P1] `LlmWriteGate` (inside `runCatching` at line 924) uses the user's full-price model for memory auto-store
**File:** `aura-core/src/main/kotlin/com/aura/agent/MemoryAugmentedAgenticLoop.kt:913-928`
```kotlin
val gateModel = if (model.startsWith("moa:")) {
    modelCatalogRepository?.catalog?.value?.allModels
        ?.firstOrNull { it.providerPrefix != "moa" }?.id ?: return@runCatching
} else {
    model   // ← user's full-price model
}
val gate = LlmWriteGate(heuristic = WriteGate(), registry = providerRegistry, modelId = gateModel)
val decision = gate.evaluate(lastUserMessage, "user")
```
If the user selected a $15/M-token Opus model, every memorable turn costs an Opus write-gate call. The fallback only fires for `moa:`. The cheap-model heuristic that *is* used for planning (line 991) and reflection (line 991) is **not** used for the write-gate.
**Fix:** Use `resolveCheapModel(effectiveModel)` for the write-gate, with a fallback to `model` only if the cheap resolution fails.

### 4.3 [P1] `Brain.stream` `runCatching` swallows the `thinkingBudget` default of 32K silently on DataStore failure
**File:** `aura-core/src/main/kotlin/com/aura/agent/Brain.kt:77-81`
```kotlin
val budget = runCatching { userPreferences.reasoningBudget.first() }
    .onFailure { android.util.Log.w("Brain", "reasoningBudget read failed: ${it.message}") }
    .getOrDefault(32000)
```
If DataStore throws, the loop silently uses 32K thinking tokens — every step. This is *more* expensive than the user's normal setting, not less. Cost waste on error.
**Fix:** Default to 0 (no extended thinking) on error, or 8K as a conservative default.

### 4.4 [P2] `ImageGenTool.enhancePrompt` uses the first configured provider's first model
**File:** `aura-core/src/main/kotlin/com/aura/tools/ImageGenTool.kt:182-200`
```kotlin
val model = runCatching {
    val reg = providerRegistry ?: return@runCatching null
    val providers = reg.configured()
    val first = providers.firstOrNull()
    val firstModel = first?.listModels()?.firstOrNull()
    if (first != null && firstModel != null) "${first.prefix}:$firstModel" else null
}.getOrNull() ?: return original
```
The "first configured provider's first model" is not necessarily cheap. It is whatever the user happened to configure first — could be an Opus-class model. The function is a 150-token prompt enhancement, called every time `image_gen` is invoked.
**Fix:** Use `CheapModelHeuristic.pick(...)` like `MemoryAugmentedAgenticLoop.resolveCheapModel` does.

### 4.5 [P2] `Brain.stream` does not cache the resolved provider → listModels result
**File:** `aura-core/src/main/kotlin/com/aura/agent/Brain.kt:58-113`
`providerRegistry.chat(model, messages, ...)` is called per step. Each step iterates the model catalog (if it does model lookup). This is mitigated for the *main* call but **not** for the planning, reflection, write-gate, and image-prompt enhancement calls — each of these re-resolves the model independently.
**Fix:** Cache the cheap model in `MemoryAugmentedAgenticLoop` once per `run()` and pass it down (already done for the loop's own `cachedCheapModel` at line 376; but not passed to `Brain.stream`).

### 4.6 [P2] `MemoryAugmentedAgenticLoop` calls `MemoryStore.query` with `rerankModel` even when `memoryEnabled = false` check is bypassed
**File:** `aura-core/src/main/kotlin/com/aura/agent/MemoryAugmentedAgenticLoop.kt:422-490`
The `if (memoryEnabled && lastUserMessage.isNotBlank())` correctly skips the recall — *good*. But the cheap-model resolution at line 451-459 still runs (it has no `memoryEnabled` guard), wasting one `/models` API call per step when in incognito.
**Fix:** Wrap the cheap-model resolution in the same `if (memoryEnabled)` block.

### 4.7 [P2] `ConversationCompactor.compactIfNeeded` runs a full LLM call to summarize
**File:** `aura-core/src/main/kotlin/com/aura/agent/ConversationCompactor.kt:110-125`
This is called on **every step** (line 881) once the threshold is crossed. A 10-step run that crosses the threshold at step 5 will compact 5 more times. Each compaction is an LLM call. The docstring on line 873-881 claims `compactIfNeeded` is "a no-op when below threshold (cheap char-sum, no network)" — but the threshold check happens *after* the cheap-model resolution, so the cheap model is re-resolved every step.
**Fix:** Resolve `compactModel` once at the top of `run()`, not per call.

---

## 5. Truncation Gaps

### 5.1 [P1] `Conversation.toMessages` re-truncates tool results that the loop already truncated
**File:** `aura-core/src/main/kotlin/com/aura/agent/Conversation.kt:79-85`
```kotlin
val resultForModel = if (toolTurn.result.length > maxToolResultChars) {
    toolTurn.result.take(maxToolResultChars) + "\n[... truncated]"
} else {
    toolTurn.result
}
```
The loop already runs `truncateToolResult` (4000 chars) at line 869. The default `maxToolResultChars` in `toMessages` is **2000**. So every tool result is truncated twice — at 4000 by the loop, then at 2000 by `toMessages`. The model never sees more than 2000 chars of any tool result, **even when the per-tool truncation (Firecrawl 8000, DeepResearch 6000) was designed to give the model more context**.
**Fix:** Raise the default `maxToolResultChars` to at least 4000 (match the loop) or accept the per-tool budget and skip the second truncation. Currently both layers silently halve the budget.

### 5.2 [P1] `DelegateToAgentTool` does NOT apply `Conversation.toMessages` re-truncation
**File:** `aura-core/src/main/kotlin/com/aura/tools/DelegateToAgentTool.kt:215-258`
The child's tool results are appended via `conversation.add(ProviderMessage(role = Role.tool, content = resultText))` — `resultText` is the already-truncated 4000-char result. But the *next step* the child calls `brain.stream(model, conversation, tools, options)` where `conversation` is the full list — the child never calls `toMessages(maxToolResultChars=...)`. The provider receives the full 4000-char result, but the **truncation marker is repeated** because the loop's `truncateToolResult` already added it.
**Fix:** No code change needed here, but the lack of `toMessages` integration means the child's context window is not bounded by `maxToolResultChars`. Document this, or call `toMessages` on the child's `conversation` before re-streaming.

### 5.3 [P1] `HandRunEnqueuer` (background tool) does not call `truncateToolResult`
**File:** `aura-core/src/main/kotlin/com/aura/tools/HandRunEnqueuer.kt:69`
The background hand executor passes `approvedRemoteCostTools` but does not reference `truncateToolResult`. The `AgentRunExecutorWorker.kt:186` uses the result directly. A long-running web search via a hand could blow the worker's input budget.
**Fix:** Truncate at the worker boundary (`AgentRunExecutorWorker.kt`).

### 5.4 [P2] Tools that return structured data are textified without size guard
**Files:**
- `aura-core/src/main/kotlin/com/aura/tools/KgQueryTool.kt:162-205` (appends up to 20 nodes)
- `aura-core/src/main/kotlin/com/aura/tools/QueryWorldModelTool.kt:57-92` (10 beliefs + 10 events + 10 opportunities)
- `aura-core/src/main/kotlin/com/aura/tools/CanonQueryTool.kt` (no per-result cap visible)
- `aura-core/src/main/kotlin/com/aura/tools/CreativeTools.kt:113-119` (capped at 120/4000/200 chars per arg)

These tools return pre-formatted text with `.appendLine(...)` chains. The loop's 4000-char safety net catches them, but the user-visible "result" in the chat UI is the *full* (un-truncated) string. If a tool result is 12,000 chars, the model sees 4000 + marker but the UI shows the full 12,000 — inconsistent.
**Fix:** Either (a) render truncated in UI too, or (b) document that the loop truncates for the model only.

### 5.5 [P2] `MemoryAugmentedAgenticLoop.MemoryStore.RecallOptions` returns hits without per-hit truncation
**File:** `aura-core/src/main/kotlin/com/aura/agent/MemoryAugmentedAgenticLoop.kt:505-510`
The memory context block is:
```kotlin
val lines = recallHits.map { m -> "- [${m.category}] ${m.content}" }.joinToString("\n")
```
If a single memory is 2000 chars, the context can blow up. The default `recallLimit = 5` → 10,000 chars un-truncated. The model gets a giant memory block.
**Fix:** Cap each `m.content` to ~500 chars and the total to ~3000 chars.

---

## 6. Silent Error Swallowing

### 6.1 [P1] `MemoryAugmentedAgenticLoop` lines 456-459, 526, 1095 — double-`onFailure` (one is dead, see §1.1)
Already covered.

### 6.2 [P1] `ChatSendController.runSend` line 309-312 — strategy-bandit fallback uses opaque `op failed`
**File:** `app/src/main/kotlin/com/aura/ui/viewmodel/ChatSendController.kt:309-312`
```kotlin
val strategy = if (strategyBandit != null) {
    runCatching { strategyBandit.selectStrategy(category) }
        .getOrDefault(ReasoningStrategy.MULTI_STEP_REFLECT)
} else null
```
No `.onFailure { Log.w(...) }`. A strategy-bandit failure silently escalates every task to 15 steps.
**Fix:** Add `.onFailure { Log.w("ChatSendCtrl", "selectStrategy($category) failed: ${it.message}") }`.

### 6.3 [P1] `ChatSendController.runSend` line 467, 491 — same pattern for `recordOutcome`
**File:** `app/src/main/kotlin/com/aura/ui/viewmodel/ChatSendController.kt:467, 491`
The `.onFailure` *is* present here (`Log.w("ChatSendCtrl", "op failed: ${it.message}")`), but the message is opaque ("op failed") — no context on which strategy or category failed.
**Fix:** Use `"recordOutcome($category, $strategy, success=$success) failed: ${it.message}"`.

### 6.4 [P2] `MemoryAugmentedAgenticLoop` line 451-458 — `cheap model resolution failed` is followed by `op failed`
**File:** `aura-core/src/main/kotlin/com/aura/agent/MemoryAugmentedAgenticLoop.kt:451-458`
See §1.1. The second `Log.w` is unreachable.

### 6.5 [P2] `Brain` lines 72-80 — `runCatching` with default fallback on DataStore failure
**File:** `aura-core/src/main/kotlin/com/aura/agent/Brain.kt:72-80`
Both `reasoningEnabled` and `reasoningBudget` are wrapped with `runCatching { ... }.onFailure { Log.w(...) }.getOrDefault(...)`. The logging is correct, but the defaults are `true` and `32000` — meaning a DataStore failure causes the **most expensive** path to be taken. Combined with §4.3, this is both a cost-waste and a silent-failure issue.
**Fix:** Use `getOrDefault(false)` and `getOrDefault(0)` respectively.

### 6.6 [P2] `DelegateToAgentTool` line 96-101 — model resolution failure
**File:** `aura-core/src/main/kotlin/com/aura/tools/DelegateToAgentTool.kt:96-101`
```kotlin
val model = agent.preferredModel
    ?: runCatching {
        ...
    }.onFailure { Log.w("Delegate", "op failed: ${it.message}") }.getOrNull()
    ?: throw IllegalStateException("Agent has no preferred model and no configured provider available")
```
The first `Log.w` is opaque. The second branch is informative.
**Fix:** Replace the first with `Log.w("Delegate", "preferred model resolution failed for ${agent.name}: ${it.message}")`.

### 6.7 [P2] `KnowledgeGraphTool` line 134-136
**File:** `aura-core/src/main/kotlin/com/aura/tools/KnowledgeGraphTool.kt:134-136`
```kotlin
val flow = runCatching { ... }.onFailure { Log.w("KGTool", "op failed: ${it.message}") }.getOrElse { ... }
```
Same opaque-logging pattern. **Many** such occurrences across `aura/tools/`.

### 6.8 [P2] `MediaCapabilityTools` lines 50-101 — three opaque `op failed` calls
**File:** `aura-core/src/main/kotlin/com/aura/tools/MediaCapabilityTools.kt:50-101`
TTS, video, 3D — all three use the same pattern.

### 6.9 [P2] `IdentityStore` lines 45, 72, 84 — file-delete runCatching
**File:** `aura-core/src/main/kotlin/com/aura/agent/IdentityStore.kt:45, 72, 84`
`runCatching { overrideFile.delete() }` is used three times with `.onFailure { Log.w(...) }`. The logging is present, but the method then returns `false` from `resetToDefault` (line 81) and the user sees a "reset failed" without a clear cause.
**Fix:** Include the exception class in the log.

### 6.10 [P2] `StrategyBandit.recordOutcome` line 149-152
**File:** `aura-core/src/main/kotlin/com/aura/agent/StrategyBandit.kt:149-152`
Logging is present (`Log.w("StrategyBandit", "recordOutcome failed: ${it.message}")`). **Good example** — not a finding.

### 6.11 [P2] `MemoryAugmentedAgenticLoop` line 1029-1031 — `affinityTracker.recordTurn` swallowed
**File:** `aura-core/src/main/kotlin/com/aura/agent/MemoryAugmentedAgenticLoop.kt:1030`
Logging present. **Good.**

### 6.12 [P1] `MemoryAugmentedAgenticLoop` line 951-953 — `extractProfileFromText` failure silently becomes `false`
**File:** `aura-core/src/main/kotlin/com/aura/agent/MemoryAugmentedAgenticLoop.kt:951-953`
```kotlin
val regexFound = runCatching { extractProfileFromText(lastUserMessage) }
    .onFailure { android.util.Log.w("AgenticLoop", "profile extraction (user) failed: ${it.message}") }
    .isSuccess
```
Logging is good. **But** if extraction fails, the LLM extractor runs as fallback (line 958), which is *also* wrapped in `runCatching` with a different log. The fallback-on-failure is correct.

---

## 7. System Prompt Construction

### 7.1 [P1] Narrative self / intrinsic motivation / theory of mind / affinity — all optional, probably all null
**File:** `aura-core/src/main/kotlin/com/aura/agent/MemoryAugmentedAgenticLoop.kt:73-86, 604-611`
`narrativeSelf`, `intrinsicMotivation`, `theoryOfMind`, `affinityTracker` are all `? = null` constructor params. The system prompt construction `listOfNotNull(... .toPrompt() ...)` returns an empty string for any null dep. **No `@Module` in the project wires these to the agentic loop** (verified by searching `AgentModule.kt` — it does not bind these deps for the loop).
**Fix:** Wire them in `AgentModule` (most likely they're already in other modules — search for `narrativeSelf` provider and add it to the loop's module).

### 7.2 [P1] `beliefDao` is optional and likely null
**File:** `aura-core/src/main/kotlin/com/aura/agent/MemoryAugmentedAgenticLoop.kt:73, 517-527`
Same pattern. The `# Known beliefs` block is silently empty if `beliefDao` is null.

### 7.3 [P1] `tasteEngine` and `tastePromptEnhancer` are optional
**File:** `aura-core/src/main/kotlin/com/aura/agent/MemoryAugmentedAgenticLoop.kt:76, 81, 556-567`
Same pattern. Taste context is empty if null.

### 7.4 [P1] `traceSink`, `worldEventProducer`, `reflectionEngine`, `strategyBandit`, `llmProfileExtractor` — all optional
All `? = null`. The system-prompt paths for these are mostly "log a warning" or "do nothing". The user-visible "Aura" persona is therefore **a fraction of what was designed** unless the modules are wired.
**Fix:** Add an integration test that asserts each consciousness module produces non-empty output given seeded state.

### 7.5 [P2] System prompt construction order is fragile
**File:** `aura-core/src/main/kotlin/com/aura/agent/MemoryAugmentedAgenticLoop.kt:593-611`
The system prompt is built as:
1. `listOfNotNull(identity, specialist.systemPrompt, personalityDirective, conversation.systemPrompt, brain.resolvedIdentity(), userProfileStore.getSystemPrompt()).joinToString("\n\n")`
2. `+ topicContext + memoryContext + beliefContext + tasteContext + emotionContext + handContext + reflectionContext`
3. `+ (if step == 1) { consciousness layer }`

The order means that *if* `brain.resolvedIdentity()` returns empty, the SOUL.md identity is lost. `Brain.resolvedIdentity()` (line 43) calls `identityStore.readCurrent()`. `IdentityStore.readCurrent()` returns the bundled asset. **This is fine in normal operation but means the SOUL.md content is the last identity in the list, not the first** — and a `personalityDirective` (per-agent) overrides the persona for agent users. Intentional, but worth a comment.

### 7.6 [P2] `planner` system prompt is hardcoded inline, not in a prompt template
**File:** `aura-core/src/main/kotlin/com/aura/agent/MemoryAugmentedAgenticLoop.kt:636-642`
```kotlin
ProviderMessage(
    role = Role.system,
    content = "You are a planning assistant. ...",
),
```
Other prompts are extracted to `assets/*.md` or `Prompts.kt`. The planner is a one-off. **Minor finding** — extract to a `Prompts.plannerSystemPrompt` constant for testability.

---

## 8. ChatSendController

### 8.1 [P2] `ChatSendController.consecutiveFailures` reset semantics
**File:** `app/src/main/kotlin/com/aura/ui/viewmodel/ChatSendController.kt:119, 254, 464, 488`
The counter is reset on `Done` (success) but is **not reset on tool errors that the model recovers from** (e.g. the model calls a tool, gets `ToolResult.Error`, tries again, succeeds). The counter is only reset when the *whole turn* ends. With the agentic loop's max-steps heuristic and tool-error recovery, a single bad tool call followed by 9 successful ones still counts as 1 failure — the counter is correct as designed.

### 8.2 [P1] `runSend` builds `ToolContext` for delegation without all flags
**File:** `app/src/main/kotlin/com/aura/ui/viewmodel/ChatSendController.kt:237-243`
Already covered in §3.4. Re-iterating: `permissions`, `timeout`, `userId` are not set.

### 8.3 [P2] `applyProviderWarning` mutates `modelSelection` to `Ready(fallback, current.availableModels)` — `availableModels` is captured at construction time
**File:** `app/src/main/kotlin/com/aura/ui/viewmodel/ChatSendController.kt:36-42`
`applyProviderWarning` is called from the `Warning` event handler. It references `current.availableModels`, which is fine if `availableModels` is a `List` snapshot. If it's a `StateFlow` value, it would be stale. Verified: `current.availableModels` is a `List<ModelInfo>` field on `ChatUiState` — a snapshot, so no race.

### 8.4 [P2] Duplicate comments in `runSend` (lines 272-273)
**File:** `app/src/main/kotlin/com/aura/ui/viewmodel/ChatSendController.kt:272-273`
```kotlin
// Clear any stale in-flight tool calls from a previous turn.
// Clear any stale in-flight tool calls from a previous turn.
```
Cosmetic. **Fix:** Remove duplicate.

### 8.5 [P2] `extractCitations` and `onSaveConversation` are passed as constructor lambdas — no backpressure if the lambdas are slow
**File:** `app/src/main/kotlin/com/aura/ui/viewmodel/ChatSendController.kt:97-104`
If `onSaveConversation()` is a Room write, the loop's `collect` blocks on it. In a fast streaming turn with frequent `ToolResult` events, the save is amortized but still adds latency to each event. The pattern is fine, but the lack of a `tryEmit` for the in-flight badge is a missing optimization.

### 8.6 [P1] `ChatSendController` does not propagate the `Retry-After` header on 429 errors to the loop
**File:** `app/src/main/kotlin/com/aura/ui/viewmodel/ChatSendController.kt:463-472`
The `Error` event from the loop has `retryable: Boolean`. The controller does `setErrorWithAutoDismiss(...)` but does not actually retry. The "retry" button (if any) is user-driven, not automated. **The agentic loop's failover** (line 712-742 of `MemoryAugmentedAgenticLoop.kt`) **does retry within the loop** but **only with a different model**, not after a delay. A 429 should wait; the loop just tries a different model.
**Fix:** Honor `Retry-After` in the Brain's chunked error path.

---

## 9. Recommended Fix Priority

| Rank | Finding | Severity | Effort | Sprint |
|------|---------|----------|--------|--------|
| 1 | 3.1 ToolContext.permissions dead field + DelegateToAgentTool does not propagate | P0 | small | current |
| 2 | 3.2 DelegateToAgentTool loses parent reflection | P0 | small | current |
| 3 | 5.1 Conversation.toMessages re-truncates at 2000 chars | P1 | trivial | current |
| 4 | 7.1-7.4 Consciousness modules probably all null | P1 | medium (wiring + test) | current |
| 5 | 4.1-4.3 Cost waste: thinking budget leak into aux calls | P1 | small | next |
| 6 | 2.1 pendingPermissions stale-snapshot race | P1 | medium | next |
| 7 | 2.2 Brain reads thinkingBudget every stream | P1 | small | next |
| 8 | 3.3 Narrative self reads stale on step 2+ | P1 | small | next |
| 9 | 5.3 HandRunEnqueuer no truncate | P1 | small | next |
| 10 | 6.2-6.3 ChatSendController opaque `op failed` | P1-P2 | trivial | next |
| 11 | 2.7 findMatchingHand per-step scan | P1 | trivial | next |
| 12 | 1.1-1.5 Dead/unused fields & double-onFailure | P2 | trivial | when nearby |
| 13 | 2.3-2.6 minor races | P2 | trivial | when nearby |
| 14 | 5.4-5.5 Per-hit text truncation gaps | P2 | small | when nearby |
| 15 | 6.5 DataStore default to expensive path | P2 | trivial | when nearby |
| 16 | 7.5-7.6 Prompt construction hygiene | P2 | trivial | when nearby |
| 17 | 8.4-8.6 Cosmetic ChatSendController items | P2 | trivial | when nearby |

---

## Audit Methodology

- Read every file in scope: 1189-line `MemoryAugmentedAgenticLoop.kt`, 246-line `ToolExecutor.kt`, 223-line `Brain.kt`, 192-line `StrategyBandit.kt`, 94-line `ReflectionEngine.kt`, 233-line `Conversation.kt`, 97-line `ToolRegistry.kt`, 279-line `DelegateToAgentTool.kt`, 550-line `ChatSendController.kt`, plus all 26 tool files in `aura/tools/`.
- Cross-referenced injections vs. call sites via `grep` and `search_files`.
- Traced every `@Volatile` field for concurrent access patterns; none in scope of the agentic loop (the concurrency primitive is the `Conversation` immutable data class).
- Traced `ToolContext` through `DelegateToAgentTool`, child runs, and recursive loop calls.
- For every auxiliary LLM call, checked whether the provider is the user-selected model or a hard-coded expensive default.
- Compared tool result truncation between `ToolExecutor` (agentic path) and direct tool invocations (e.g. from `ChatSendController`, `DelegateToAgentTool`, `HandRunEnqueuer`).
- For every `runCatching`, checked whether the failure block logs at WARN/ERROR or just returns a default. Counted **~50** `runCatching` sites in scope; **~15** had no `onFailure`, **~5** had duplicate `onFailure` (one unreachable), **~30** had good logging.
- Built the system prompt by following the call graph from `MemoryAugmentedAgenticLoop.run` (line 593) outward to every "consciousness/taste/emotion/belief" module — found **9** optional deps that are likely null at runtime.

---

*End of ROUND 12 audit.*
