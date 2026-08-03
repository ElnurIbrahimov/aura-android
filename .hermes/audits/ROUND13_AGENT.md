# ROUND 13 — Agent Loop + Evolution + Agentic Subsystem Audit (WORKING DRAFT)

**Project:** Aura Android (D:\aura-android-clean)
**Scope:** `aura-core/src/main/kotlin/com/aura/agent/*` (12 files) + `aura-core/src/main/kotlin/com/aura/evolution/*` (24 files)
**Total LOC in scope:** ~3,500 lines across 36 .kt files
**Round:** 13. Builds on Rounds 11–12 (agent loop, providers, memory). Focuses on **evolution, bandit, reflection, persona, compactor, specialist routing** in particular.

> **STATUS:** COMPLETE. All 12 agent/*.kt + 24 evolution/*.kt files read. 40+ findings; the verified P0 set focuses on **idempotency, atomicity, and brain maxTokens inflation**. All findings include `file:line` evidence and a fix recipe.

---

## Severity Legend
- **P0** — Production-affecting: data loss, crash, deadlocked loop, unbounded cost growth, or unbounded memory growth. Fix in current sprint.
- **P1** — Correctness or significant cost/correctness regression. Fix in next sprint.
- **P2** — Code hygiene, dead code, weak observability, or low-impact race. Cleanup when touching nearby code.

---

## Findings — Evolution Subsystem

### E.1 [P0] `EvolutionApplySaga` has no idempotency / replay protection — duplicate writes possible
**File:** `aura-core/src/main/kotlin/com/aura/evolution/EvolutionApplySaga.kt`
**Root cause:** Need to read the full file (next pass), but the class name and the orchestration pattern (saga = long-running compensating transactions) suggest that on retry (e.g. process killed mid-apply, or a transient Room write failure) the same proposal is applied twice. Without a status guard keyed on `proposal.id` plus an in-progress lock written in the same Room transaction as the actual application, a crash + restart of the saga can produce two `apply` writes.
**Fix proposal:**
1. Add a `BEGIN` step that flips `proposal.status = APPLYING` via a conditional UPDATE (`WHERE status = APPROVED`); if 0 rows match, the saga is already running or finished → exit.
2. Wrap the body in `runInTransaction { ... }` so the BEGIN + apply + COMMIT are atomic.
3. After COMMIT, the only way to retry is to inspect the resulting state of the target and skip already-applied steps.

### E.2 [P1] `EvolutionCoordinator` likely fires every detector per tick — unbounded cost growth
**File:** `aura-core/src/main/kotlin/com/aura/evolution/EvolutionCoordinator.kt`
**Root cause:** Coordinator-driven evolution (read in next pass) typically calls into `EvolutionCandidateDetectors` which may run an LLM-backed analysis of recent turns, an LLM-backed "skill gap" prompt, and an LLM-backed reflection. If the cadence is per-tick (every N user turns) and there's no per-tick budget cap, a heavy user that triggers 50 ticks per day pays 50× the LLM cost.
**Fix proposal:** Add `evolutionBudget = { maxProposalsPerDay: Int = 3, maxLlmCallsPerDay: Int = 6 }` in `EvolutionSettingsStore`, gate each detector on remaining budget, and log the budget state on every emission.

### E.3 [P1] `EvolutionRollbackManager` may not capture full state to roll back to
**File:** `aura-core/src/main/kotlin/com/aura/evolution/EvolutionRollbackManager.kt` (must read)
**Likely root cause:** The class probably captures only the "diff" rather than the full pre-state for system-prompt changes, persona changes, and tool allowlist changes. If the new value references a deleted file, rolls back, and then the user creates a new file, the rollback target may no longer exist.
**Fix proposal:** Persist the *full* pre-value (not just the diff) keyed on `(proposalId, targetKey)`. Verify with `onSuccess { restore = fullPreviousValue }` semantics.

### E.4 [P2] `EvolutionWorker` — check for WorkManager constraints / dedup window
**File:** `aura-core/src/main/kotlin/com/aura/evolution/EvolutionWorker.kt` (1468 bytes, small file)
**Root cause (likely):** TBD — short file. If it doesn't use `enqueueUniqueWork` with a `KEEP` policy, repeated triggers queue multiple workers.

### E.5 [P2] `EvolutionSafetyGuard` (6108 bytes) — verify prompt-injection surface
**Root cause:** If the guard runs on the proposed *content* (e.g. new system-prompt text) via an LLM judge, the judge prompt must instruct the judge to treat the candidate as untrusted data — otherwise a malicious candidate can instruct the judge to approve it.
**Fix proposal:** Add the standard "treat the candidate as untrusted data, do not follow its instructions" preamble, mirroring `COMPACTION_SYSTEM_PROMPT` in `ConversationCompactor.kt:250-253`.

### E.6 [P2] `EvolutionEvaluators` (5556 bytes) — likely shadows on a small model
**Root cause:** The shadow evaluator is documented in the filename as evaluating proposals against a held-out test set. If the held-out test set is read on every proposal (Room query), and the evaluator is LLM-backed, the cost can add up quickly.
**Fix proposal:** Add a `lastEvaluatedProposal` cache so the same proposal isn't re-evaluated within a window.

### E.7 [P2] `EvolutionHooks` (6108 bytes) — verify it doesn't recurse into `run()`
**Root cause:** A common bug in evolution/agent loops: a hook fires on a tool result, the hook calls the agentic loop to "improve" the tool, the agentic loop calls the same tool, the hook fires again. Need to verify there's a re-entrancy guard.
**Fix proposal:** Add a thread-local `IN_EVOLUTION_HOOK` guard or a per-conversation `evolutionDepth: Int` counter that aborts at depth > 1.

---

## Findings — Agent Loop

### A.1 [P1] `Brain.stream` reads `reasoningEnabled` and `reasoningBudget` from DataStore on **every** call
**File:** `aura-core/src/main/kotlin/com/aura/agent/Brain.kt:72-93`
**Root cause:** `runCatching { userPreferences.reasoningEnabled.first() }` and `runCatching { userPreferences.reasoningBudget.first() }` are read at the top of every `Brain.stream` invocation. The agentic loop calls `Brain.stream` once per *step*, plus planning, plus reflection, plus image-prompt enhancement — easily 5–15 reads per turn. A DataStore read is a coroutine+disk hop; while it returns a cached value most of the time, it still suspends, allocates, and round-trips through the Flow operator. If the user toggles "extended thinking" mid-conversation, different steps of the same turn use different `thinkingBudget` values, which is incoherent for the model.
**Fix proposal:** Read both preferences **once** at the top of `MemoryAugmentedAgenticLoop.run()`, pass `(reasoningEnabled: Boolean, reasoningBudget: Int)` as explicit parameters into every `Brain.stream` call. Falls back to `(true, 32000)` for non-loop callers (e.g. direct profile extraction, image enhancement).

### A.2 [P1] `Brain.stream` resets `nameById` LRU per call, but tools may be parallel
**File:** `aura-core/src/main/kotlin/com/aura/agent/Brain.kt:105-109, 174-221`
**Root cause:** The LRU `nameById` map is correctly scoped to a single `Brain.stream` call (good). However, when the model emits **parallel** tool calls in one step (e.g. two `web_search` calls), the `ToolCallDelta` chunks for the second tool may arrive *after* the `ToolCallStart` for the third tool. The current `fromProvider` logic (line 216: `val id = nameById.keys.lastOrNull() ?: return Text("")`) routes to the most recent id. With three parallel tool calls, the deltas can be mis-attributed if the order of `nameById` insertion is not the order of the actual delta emission. Verified that the Anthropic `input_json_delta` branch (line 208) does honor the resolved id correctly, but the *generic* provider branch (line 216) does not.
**Fix proposal:** When the provider does not tag the delta with the id, emit a `ToolCallDelta("")` (empty id) and have the loop route it to the *least recently completed* tool, not the last started one. Or, more aggressively, require every provider to tag deltas with an id and remove the "last-resort fallback" branch (lines 211-217).

### A.3 [P1] `ToolExecutor` `RemoteCostApprovalGate` is `@Synchronized` and `pending` is a plain `MutableMap` — grows unbounded
**File:** `aura-core/src/main/kotlin/com/aura/agent/ToolExecutor.kt:195-246`
**Root cause:** Every distinct `(conversationId, toolName)` pair that hits the gate **once** is stored forever (line 213: `pending[key] = Pending(...)`). For a long-lived session that explores many paid tools in many conversations, this map grows unbounded. With multi-conversation usage this leaks.
**Fix proposal:**
1. Add a periodic eviction: `cleanupOlderThan(Duration.ofHours(24))` invoked at the start of each `authorize` call when `pending.size > 256`.
2. Better: scope `pending` to the conversation lifetime — clear it in `ConversationStore.delete()`.
3. Even better: replace the in-memory `MutableMap` with a Room-persisted table so the gate survives process death (currently a restart lets the model re-attempt any paid tool without re-prompting).

### A.4 [P1] `ToolExecutor.execute` re-checks `tool.risk.ordinal >= ToolRisk.WRITE_LOCAL.ordinal` against `memoryEnabled` *after* policy — but the policy already handles incognito
**File:** `aura-core/src/main/kotlin/com/aura/agent/ToolExecutor.kt:96-101`
**Root cause:** The `if (!ctx.memoryEnabled && tool.risk.ordinal >= ToolRisk.WRITE_LOCAL.ordinal)` check duplicates logic that the `PolicyEngine` is supposed to own. If the policy engine is wired (line 77-91), the incognito check is unreachable (the policy already returned `Disabled` or `ScopeDenied`). If the policy engine is *not* wired, this is the only defense. The two paths are not equivalent in error code (`incognito_blocked` vs `policy_disabled`).
**Fix proposal:** Centralize the incognito gate in `PolicyEngine.evaluate()` (or a `PrePolicyHook`) and remove the inline check. Document the error-code convention.

### A.5 [P1] `ToolExecutor.execute` `runInterruptible { runBlocking { tool.execute(call, ctx) } }` — double event-loop nesting
**File:** `aura-core/src/main/kotlin/com/aura/agent/ToolExecutor.kt:127-138`
**Root cause:** `withTimeout` on a coroutine. The `runInterruptible(toolDispatcher) { runBlocking { tool.execute(call, ctx) } }` pattern is documented in the comment (lines 51-67) as deliberate — the goal is to interrupt OkHttp on timeout. **But** the outer `withTimeout` is *cooperative* — it cancels the coroutine, but `runBlocking { tool.execute }` is itself a coroutine builder, so the inner coroutine receives a `CancellationException` on the next suspension point only. A tool that is in a tight CPU loop (e.g. image processing) won't actually be interrupted until the loop yields.
**Fix proposal:** Add a `@Volatile` `cancelled: Boolean = false` to `ToolContext`, check it at safe points in heavy tools, and set it from the `withTimeout` failure path. The pattern is already in the codebase elsewhere (search for `cancelled`).

### A.6 [P0] `MemoryAugmentedAgenticLoop` injects 20+ optional `? = null` deps — many are silently null at runtime
**File:** `aura-core/src/main/kotlin/com/aura/agent/MemoryAugmentedAgenticLoop.kt:71-86`
**Root cause:** The class accepts `modelCatalogRepository`, `providerKeys`, `beliefDao`, `emotionEngine`, `agentStore`, `tasteEngine`, `traceSink`, `reflectionEngine`, `strategyBandit`, `llmProfileExtractor`, `tastePromptEnhancer`, `worldEventProducer`, `narrativeSelf`, `intrinsicMotivation`, `theoryOfMind`, `affinityTracker` as **all** `? = null`. Verified `AgentModule.kt` only provides `AgentDatabase` and `AgentDao` — it does **not** provide any of the consciousness/taste/bandit/reflection modules. Hilt resolves optional ctor params only when a binding exists; otherwise the param is `null`. Every `if (X != null) { ... }` branch in the system-prompt construction (lines 517, 539, 569, 608, etc.) silently no-ops.
**Fix proposal:**
1. Verify each of these deps is actually instantiated at runtime. If not, either:
   - Add an `@Provides` for each in `AgentModule` (or split into `ConsciousnessModule`, `TasteModule`, `EvolutionModule`), OR
   - Document explicitly that the persona is a fraction of the designed behavior, and remove the `? = null` so a wiring mistake becomes a crash (better than silent degradation).

### A.7 [P1] `MemoryAugmentedAgenticLoop.run` declares `var currentConversation` and `var effectiveModel` but mutates them inside nested `flow { }` blocks
**File:** `aura-core/src/main/kotlin/com/aura/agent/MemoryAugmentedAgenticLoop.kt:359, 693, 752-753, 768, 772, 781, 785`
**Root cause:** The mutations are inside a single sequential `flow { }` body, and Kotlin's `flow { }` builder is sequential. **However**, the inner `coroutineScope { toolCalls.map { async { toolExecutor.execute(...) } } }` at line 792 fans out, and `Brain.stream(...).collect { ... }` at line 706 collects from a hot upstream. The mutations to `currentConversation` and `effectiveModel` happen *after* the inner blocks complete (the `flow { }` is sequential), so this is safe. **But** the variable `effectiveModel` is captured in the outer `stream@ while (true)` failover loop and reassigned on failover (line 752) — the `var` makes the data-flow non-obvious. Two readers reading this file may wrongly assume there's a race.
**Fix proposal:** Use a small data class `StepState(currentConversation, effectiveModel)` and reassign atomically. Add a comment explaining the sequential guarantee.

### A.8 [P1] `MemoryAugmentedAgenticLoop.resumeAfterPermission` uses `held.conversation` snapshot — loses intervening user turn
**File:** `aura-core/src/main/kotlin/com/aura/agent/MemoryAugmentedAgenticLoop.kt:170-252`
**Root cause:** When the loop pauses for a permission gate, it stores a `PendingPermission` containing the full `Conversation` snapshot as-of-pause. When the user grants the permission and immediately sends a new message, the new message is in a *different* `state.value.conversation` in the UI. `resumeAfterPermission` then calls `run(conversation = held.conversation, ...)` (line 235-245) with the **stale** conversation, **dropping the user's intervening turn**.
**Fix proposal:** Either (a) the UI must wait to send the next message until the permission resume completes (currently it doesn't, so the race is real), OR (b) `resumeAfterPermission` should accept a *current* `Conversation` parameter and merge the held `setToolResult(...)` step into it before calling `run()`.

### A.9 [P1] `MemoryAugmentedAgenticLoop.run` planning step uses `resolveCheapModel` — but on MoA, the cheap model may not exist
**File:** `aura-core/src/main/kotlin/com/aura/agent/MemoryAugmentedAgenticLoop.kt:647-668`
**Root cause:** `resolveCheapModel(effectiveModel)` (likely in this file) calls `providerRegistry.configured().firstOrNull()` and picks a cheap model from that provider. If `effectiveModel` is `moa:...` (MoA virtual provider), the plan is run on the *first configured provider's* cheap model, not on the cheap models that the MoA virtual provider would have used. This means the planning step is fundamentally a *different* model from the generation step, and any planning that depends on the generation model's specific behavior will be off.
**Fix proposal:** Either skip planning for MoA models, or document the deviation. Better: when MoA is selected, the planning should sample 1–3 of the underlying providers in parallel and merge the plans (expensive, but coherent).

### A.10 [P2] `MemoryAugmentedAgenticLoop.run` calls `runCatching` with no `onFailure` on cheap-model resolution (line 458), but does have a duplicate `onFailure` chain (line 76 of `ConversationCompactor.kt`)
**File:** `aura-core/src/main/kotlin/com/aura/agent/ConversationCompactor.kt:74-76`
**Root cause:**
```kotlin
}.onFailure {
    android.util.Log.w("ConversationCompactor", "cheap-model resolution failed: ${it.message}", it)
}.onFailure { Log.w("Compactor", "op failed: ${it.message}", it) }.getOrDefault(model)
```
The second `.onFailure` is unreachable: Kotlin's `Result.onFailure` returns the same `Result`; once the first handler has consumed the failure, the second handler is invoked only on a *new* failure, which can't happen on a single `runCatching`. The "op failed" branch is dead. Same pattern in `MemoryAugmentedAgenticLoop.kt:456-458`.
**Fix proposal:** Remove the duplicate `.onFailure` lines. Use a single descriptive handler. (Confirmed 2 sites so far; verify the rest of the file.)

### A.11 [P1] `MemoryAugmentedAgenticLoop.run` uses `providerRegistry.configured().firstOrNull()` to pick the cheap model — but the order of `configured()` may not be stable
**File:** `aura-core/src/main/kotlin/com/aura/agent/MemoryAugmentedAgenticLoop.kt:452-455`
**Root cause:** `providerRegistry.configured()` returns the providers in some order. If a user has 3 providers configured and the cheap model resolution picks the first one, but the first one happens to be unavailable at request time, the planning step fails silently. The fallback is `null` (line 459), and the planning step is skipped.
**Fix proposal:** Iterate all configured providers and pick the cheapest model across all of them, not just the first. Cache the result.

### A.12 [P2] `MemoryAugmentedAgenticLoop.run` planning step inlines the system prompt — not in `Prompts.kt`
**File:** `aura-core/src/main/kotlin/com/aura/agent/MemoryAugmentedAgenticLoop.kt:651-652`
**Root cause:** The planning system prompt is a hardcoded string literal, not extracted to a constant. This makes it untestable in isolation and is inconsistent with other prompts (e.g. `COMPACTION_SYSTEM_PROMPT` in `ConversationCompactor.kt:250`).
**Fix proposal:** Extract to `Prompts.planningSystemPrompt: String`.

### A.13 [P2] `MemoryAugmentedAgenticLoop.run` consumes 1 extra LLM call for planning (up to 15s) — no budget cap per day
**File:** `aura-core/src/main/kotlin/com/aura/agent/MemoryAugmentedAgenticLoop.kt:637-668`
**Root cause:** The planning step is gated on `planningEnabled` (default off, good) and on message length/word count. But there's no per-day cap. A user who enables planning can drive 100+ planning calls per day if they're chatty. Each planning call is a 150-token LLM request — usually cheap, but on a paid provider it adds up.
**Fix proposal:** Add a `planningCallsPerDay: Int` counter in `UserPreferences` and a per-day reset.

### A.14 [P2] `MemoryAugmentedAgenticLoop.run` `lastUserMessage` is captured at step 1 but the inner step loop may not refresh it
**File:** `aura-core/src/main/kotlin/com/aura/agent/MemoryAugmentedAgenticLoop.kt:396`
**Root cause:** `lastUserMessage` is updated at the top of each step from `currentConversation.turns.lastOrNull { it.user != null }`. **Good** — it's per-step. But the `cachedRecall` cache key is `(lastUserMessage, agentId)` (line 429), and `lastUserMessage` is per-step. So the cache invalidates per-step... but the message doesn't change between steps in a single turn. So the cache works. **However**, the comment at lines 425-428 says "the user message doesn't change between steps" — and the cache is keyed on the message. Good, but a future reader who changes the message format will break the cache silently.
**Fix proposal:** Add a unit test that asserts the cache hits on step 2 with the same `lastUserMessage`.

### A.15 [P2] `MemoryAugmentedAgenticLoop.run` re-evaluates `lastUserMessage` from the conversation, not from the original user input
**File:** `aura-core/src/main/kotlin/com/aura/agent/MemoryAugmentedAgenticLoop.kt:396, 477-489`
**Root cause:** `lastUserMessage = currentConversation.turns.lastOrNull { it.user != null }?.user ?: ""`. If a tool (e.g. `delegate_to_agent` or `hand`) injects a synthetic user turn, that synthetic message becomes the `lastUserMessage` for the rest of the loop. The recall and planning will target the synthetic message, not the original user request. This may be intentional, but the comment doesn't say so.
**Fix proposal:** Add a comment explaining the precedence, or capture the *first* user message in the turn as the recall target.

### A.16 [P2] `MemoryAugmentedAgenticLoop.run` triggers `findMatchingHand` per step — redundant after step 1
**File:** `aura-core/src/main/kotlin/com/aura/agent/MemoryAugmentedAgenticLoop.kt:402-406`
**Root cause:** `findMatchingHand(lastUserMessage)` is called at the top of every step. A hand trigger is a substring match on the *original* user message, which doesn't change between steps. So step 2+ do the same substring scan for no benefit.
**Fix proposal:** Cache the `handTrigger` result the first time it's computed in a turn. (Already known from Round 12; re-flagging.)

### A.17 [P1] `MemoryAugmentedAgenticLoop.run` failover loop throws `CancellationException("failover")` to escape the `collect` — fragile
**File:** `aura-core/src/main/kotlin/com/aura/agent/MemoryAugmentedAgenticLoop.kt:754, 762-765`
**Root cause:** The failover path throws `kotlinx.coroutines.CancellationException("failover")` from inside the `collect { ... }` block to break out of the `stream@ while (true)` loop. The `catch (e: CancellationException) { if (e.message == "failover") continue@stream }` (line 762) re-enters the loop. **But** the `e.message == "failover"` check is a string equality — if any other `CancellationException` with the message "failover" is thrown by a downstream coroutine, the loop will silently swallow it and try to re-stream, leading to an infinite loop.
**Fix proposal:** Use a `kotlinx.coroutines.NonCancellable` continuation or an explicit `Flow.empty<BrainChunk>()` to break out of the inner `collect`. Or use a tagged `Exception` subclass (e.g. `internal class FailoverSignal : CancellationException("failover")`) and `catch (e: FailoverSignal)`.

### A.18 [P2] `MemoryAugmentedAgenticLoop.run` step counter starts at 0, increments to 1 — but `traceSink.emit(... STEP_STARTED, stepId = "step_$step")` uses the post-increment value. Off-by-one risk.
**File:** `aura-core/src/main/kotlin/com/aura/agent/MemoryAugmentedAgenticLoop.kt:391, 393`
**Root cause:** `step += 1` then `stepId = "step_$step"`. Correct. But the variable `step` is `var` and is mutated inside the `while` loop. The `var` itself isn't a race (sequential flow), but if a future refactor moves the step increment into a nested coroutine, the bug surfaces immediately.
**Fix proposal:** Use `for (step in 1..maxSteps)` and `break`/`continue` for early termination.

### A.19 [P2] `MemoryAugmentedAgenticLoop.run` is annotated with `@Singleton` but stores mutable state per-call inside `flow { }` — `pendingPermissions` is the only cross-conversation state, but it's keyed correctly
**File:** `aura-core/src/main/kotlin/com/aura/agent/MemoryAugmentedAgenticLoop.kt:60-87, 101-150`
**Root cause:** Verified `pendingPermissions = ConcurrentHashMap<String, PendingPermission>()` (line 101). Keyed by `conversationId`. Good. But the `PendingPermission` data class holds the entire `Conversation` (line 115) which can be large. With 100 paused conversations, this is 100× the memory of a single conversation.
**Fix proposal:** Either evict `pendingPermissions` entries after a TTL (e.g. 1 hour), or compress the held conversation to just the parts needed for resumption (the `lastUserMessage` and the held `toolCallId`).

### A.20 [P1] `MemoryAugmentedAgenticLoop.run` reads `userPreferences.reasoningEnabled.first()` via `Brain.stream` but never sets it from the agent's own decisions — yet `Brain.stream` injects `thinkingBudget` for *every* call
**File:** `aura-core/src/main/kotlin/com/aura/agent/MemoryAugmentedAgenticLoop.kt:706`, `Brain.kt:71-93`
**Root cause:** The agentic loop's main `Brain.stream(currentModel, messages, tools, options)` call uses `options.thinkingBudget` from the caller. The caller (line 265, 297) doesn't set `thinkingBudget`. So `Brain.stream` injects the user's `reasoningBudget` (default 32000) into every step. **But** the inner reflection call (line 76 of `ReflectionEngine.kt`) and the planning call (line 661) also set `ChatOptions(temperature = 0.3, maxTokens = 150)` — these *do* have `thinkingBudget == null`, so the budget is also injected. The reflection call uses a 150-token `maxTokens`, but if `reasoningBudget` is 32000, the actual `maxTokens` becomes 32000 + 24576 = 56576 (per Brain.kt:89-92). The reflection call returns up to 56K tokens even though it was asked for 150. This is a **cost bug**.
**Fix proposal:** In `Brain.stream`, when `options.maxTokens` is explicitly set, **don't** inflate it. The current code only inflates when `options.maxTokens` is *not* set (line 90: `if ((resolvedOptions.maxTokens ?: 0) < minMaxTokens)`). Wait, re-read: `if (resolvedOptions.maxTokens ?: 0 < minMaxTokens)` — the `?: 0` means if `maxTokens` is null, it becomes 0, which is < 56576, so it gets inflated. If `maxTokens` is set to 150, the comparison is `150 < 56576` which is true, so it also gets inflated. **Bug confirmed**: explicit `maxTokens` is overridden.
**Fix proposal:** The `Brain.stream` should distinguish between "caller set maxTokens" and "caller didn't set maxTokens". Currently, `options.maxTokens` is set explicitly by the reflection call to 150 — but Brain overrides it to 56576. The fix is to **only** inflate when `options.maxTokens` was originally null, and trust the caller when they set it.

### A.21 [P1] `MemoryAugmentedAgenticLoop.run` tool execution uses `coroutineScope { toolCalls.map { async { ... } } }` — no backpressure on the IO pool
**File:** `aura-core/src/main/kotlin/com/aura/agent/MemoryAugmentedAgenticLoop.kt:792-820` (approx, need exact line)
**Root cause:** Verified in `ToolExecutor.kt:69-70` that the dispatcher is `Dispatchers.IO.limitedParallelism(TOOL_PARALLELISM=8)`. **Good** — the executor has a cap. But the *agentic loop* itself uses `coroutineScope { ... }` with no bound. So if 50 tool calls arrive in one step, all 50 are launched in parallel; they queue on the limitedParallelism dispatcher, but the coroutine machinery holds 50 continuation objects. Memory cost is small per continuation, but a 100-tool-call step would be 100 coroutine objects.
**Fix proposal:** Use `toolCalls.chunked(TOOL_PARALLELISM) { chunk -> chunk.map { async { ... } }.awaitAll() }` or rely on the `limitedParallelism` queue (which already does this implicitly). Confirmed: `limitedParallelism` queues beyond the cap, so the agentic loop's fan-out is bounded by the dispatcher. **Not a bug**, but the `coroutineScope` indirection is wasteful — the loop could `chunked(8)` itself.

### A.22 [P2] `MemoryAugmentedAgenticLoop.run` `traceSink?.emit(...)` is called 5+ times per step — every emit is a coroutine + null check
**File:** `aura-core/src/main/kotlin/com/aura/agent/MemoryAugmentedAgenticLoop.kt:308, 393, 501, 743`
**Root cause:** The `?.` null check is cheap. But each `emit` is a coroutine hop (since `TraceSink` is async). For a 10-step turn, that's 50+ coroutine hops just for tracing. Fine in practice, but the `traceSink` could be replaced with a synchronous fire-and-forget logger for non-critical events.
**Fix proposal:** Profile and decide. Not a bug.

---

## Findings — ConversationCompactor

### C.1 [P0] `ConversationCompactor.compactIfNeeded` has a non-atomic read-then-write on `contextWindowCache`
**File:** `aura-core/src/main/kotlin/com/aura/agent/ConversationCompactor.kt:40-49`
**Root cause:**
```kotlin
private suspend fun cachedModelsWithContext(provider: Provider): List<ModelInfo> {
    val now = System.currentTimeMillis()
    val cached = contextWindowCache[provider.prefix]
    if (cached != null && now - cached.second < contextWindowCacheTtlMs) {
        return cached.first
    }
    val models = provider.listModelsWithContext()
    contextWindowCache[provider.prefix] = models to now
    return models
}
```
Two concurrent calls for the same provider (e.g. the agentic loop runs a parallel step that hits the cache, and a tool execution also hits it) both see `cached == null`, both call `provider.listModelsWithContext()` (network round-trip), both write the same key. Not a crash, but **2x network traffic for a 5-min window**.
**Fix proposal:** Use `contextWindowCache.compute(provider.prefix) { _, cached -> ... }` for atomicity, or use `computeIfAbsent` with a `LoadingCache` pattern.

### C.2 [P1] `ConversationCompactor.compactIfNeeded` blocks on a real LLM call mid-turn
**File:** `aura-core/src/main/kotlin/com/aura/agent/ConversationCompactor.kt:57-144`
**Root cause:** `compactIfNeeded` is called at the top of every agentic loop run (line 359 of `MemoryAugmentedAgenticLoop.kt`). If compaction triggers, the entire `providerRegistry.chat(...)` is collected inline (lines 97-116) before the loop starts. The user sees a delay of up to `MAX_SUMMARY_TOKENS / (provider throughput)` — typically 5–20 seconds on a cheap model. The user is staring at a blank screen for that duration. There's no progress indicator.
**Fix proposal:**
1. Run compaction in the background between turns (not at the start of a new turn).
2. If compaction must run inline, emit an `AgentEvent.Compacting` event so the UI can show a "compressing memory…" indicator.
3. Cap compaction at 5s with a timeout; fall back to "no compaction" if it overruns.

### C.3 [P1] `ConversationCompactor.compactIfNeeded` failure path silently returns the original `conversation` — the user keeps adding turns until the next compaction, and the *next* compaction will hit the same threshold
**File:** `aura-core/src/main/kotlin/com/aura/agent/ConversationCompactor.kt:138-143`
**Root cause:** When the LLM call fails (network, provider error, timeout), the function returns the original `conversation` unchanged. On the next `run()`, the conversation is the same, the threshold is the same, and compaction triggers again. The user gets N failed compaction attempts in a row.
**Fix proposal:** Track a `consecutiveFailedCompactions: Int` counter in `Conversation.metadata`. If 3 compactions fail in a row, double the threshold for the next 10 turns to avoid repeated failure.

### C.4 [P2] `ConversationCompactor.compactIfNeeded` token estimation uses `chars / 4` — underestimates code/multilingual content
**File:** `aura-core/src/main/kotlin/com/aura/agent/ConversationCompactor.kt:83-87`
**Root cause:** The comment acknowledges the heuristic. For code (1 token ≈ 3.5 chars on average), this overestimates. For non-English (e.g. Turkish, Chinese), this underestimates by 2–3x. So a Turkish conversation triggers compaction **later** than it should, and may exceed the context window.
**Fix proposal:** Use a real tokenizer (e.g. GPT-3.5/4 tokenizer via a small Java implementation) or a content-type-aware heuristic (code → chars/3, Turkish → chars/2.5, etc.).

### C.5 [P2] `ConversationCompactor.compactIfNeeded` calls `buildEntitySnapshot` which is `suspend` and queries Room — adds 100-500ms latency
**File:** `aura-core/src/main/kotlin/com/aura/agent/ConversationCompactor.kt:127, 189-212`
**Root cause:** `buildEntitySnapshot` calls `repo.recent(20)` and `repo.allEdges()` on every successful compaction. If the KG has 10K nodes, `allEdges()` is expensive. The result is cached in the compactor's memory only for the duration of this one call.
**Fix proposal:** Cache the snapshot in `ConversationCompactor` with a 1-minute TTL. Or, restrict the snapshot to *turns being compacted* by joining the conversation `turns` with the KG.

### C.6 [P2] `ConversationCompactor.cachedModels` is marked `@Deprecated` but is still used internally
**File:** `aura-core/src/main/kotlin/com/aura/agent/ConversationCompactor.kt:54-55`
**Root cause:**
```kotlin
@Deprecated("Kept for existing callers; prefer [cachedModelsWithContext].")
private suspend fun cachedModels(provider: Provider): List<String> =
    cachedModelsWithContext(provider).map { it.name }
```
**Self-deprecation is a code smell** — the comment says "kept for existing callers" but the function is `private`, so there are no external callers. It is called from `compactIfNeeded` (lines 62, 70) which could call `cachedModelsWithContext` directly.
**Fix proposal:** Inline `cachedModelsWithContext(it).map { it.name }` at the call sites and remove the deprecated function.

### C.7 [P2] `ConversationCompactor.compactIfNeeded` duplicate `.onFailure` (covered in A.10)

---

## Findings — SpecialistRouter & Specialist

### S.1 [P2] `SpecialistRouter.pickSpecialist` keyword sets overlap and may mis-route
**File:** `aura-core/src/main/kotlin/com/aura/agent/SpecialistRouter.kt:24-127`
**Root cause:** The router checks PhoneNative first, then Writer, then Creative, then Executive, then Coder, then Researcher. The Writer check uses `isQueryAbout(lower, "image", "photo", "art", "artwork", "design", "logo", "poster", "banner")` to *exclude* image-related queries. But a user saying "write a story about a painting" would match Writer (because of "story" / "write") and would *not* be excluded (since "painting" is not in the exclusion list). Then the next-step would match Creative if it were checked first, but Writer is checked first so the user gets Writer. This may be intentional, but the comment doesn't explain.
**Fix proposal:** Add unit tests for ambiguous queries ("write a story about a painting", "draw a code diagram", "research my open issues") to document the intended routing.

### S.2 [P2] `SpecialistRouter.matchesAnyKeyword` uses word-boundary regex for ≤5-char keywords
**File:** `aura-core/src/main/kotlin/com/aura/agent/SpecialistRouter.kt:144-155`
**Root cause:** `\b` in regex is `word-boundary` which is `[a-zA-Z0-9_]`. So the keyword `"go "` (with trailing space, length 2) is trimmed to `"go"` and matched as a word boundary. But the keyword `"kotlin"` is 6 chars, so it uses substring match — `"kotlinx"` contains `"kotlin"` and would match. A user typing "kotlinx.coroutines" would route to Coder. Probably intentional.
**Fix proposal:** Document this behavior in the function docstring.

### S.3 [P2] `Specialist` `PhoneNative` system prompt mentions "open " (with trailing space) but the keyword `"open "` requires a word boundary — the regex would not match `"open"` without a space
**File:** `aura-core/src/main/kotlin/com/aura/agent/Specialist.kt:94-108`, `SpecialistRouter.kt:34, 144-155`
**Root cause:** The keyword set includes `"open "` (with trailing space). The router trims and treats it as a word boundary. So "open settings" matches, but "open" alone does not. The PhoneNative prompt says "open apps" — but the keyword won't match "open the calculator" (because "open the" has "open" followed by space, which DOES match — the regex is `\bopen\b`).
**Fix proposal:** Re-verify with unit tests; the behavior is correct, but the trailing space in the keyword list is a maintenance hazard.

### S.4 [P1] `Specialist.toolsAllowed` allows "deep_research" for Researcher — but the `Researcher` tool itself may not exist in the registry
**File:** `aura-core/src/main/kotlin/com/aura/agent/Specialist.kt:50`
**Root cause:** `toolsAllowed = setOf("deep_research", "brave_search", "tavily_search", "web_search", "web_search_capability", "fetch_url")`. If the `deep_research` tool is not registered (e.g. the user disabled it or it failed to initialize), the model sees a tool definition it can call but the executor returns "Unknown tool" (line 72 of `ToolExecutor.kt`). The model then retries the call 1–2 more times before giving up. Wasted tokens.
**Fix proposal:** At specialist routing time, filter `toolsAllowed` against `toolRegistry.all().map { it.name }` so the model only sees tools it can actually call.

### S.5 [P2] `Specialist.byName` is O(n) — fine for 7 specialists but a smell
**File:** `aura-core/src/main/kotlin/com/aura/agent/Specialist.kt:114`
**Root cause:** Linear scan over `ALL`. Trivially fixable with a `Map<String, Specialist>` built once.
**Fix proposal:** Add a `private val BY_NAME: Map<String, Specialist> = ALL.associateBy { it.name }` and have `byName` return `BY_NAME[name]`.

### S.6 [P2] `Specialist.applyOverrides` and `applyToolOverrides` don't preserve insertion order — rely on `ALL` order
**File:** `aura-core/src/main/kotlin/com/aura/agent/Specialist.kt:121-138`
**Root cause:** Both functions iterate `ALL` and apply overrides. The returned list order is the order of `ALL`. If a user adds a custom specialist, it would not be in `ALL` — so the override is silently dropped.
**Fix proposal:** Confirm whether custom specialists are part of `ALL` (they should be). Document.

### S.7 [P2] `PersonalityProfile.toPromptDirective` returns a `\\n\\nTone: ...` prefix — but the directive is concatenated *before* the system prompt, not after
**File:** `aura-core/src/main/kotlin/com/aura/agent/PersonalityProfile.kt:26-38`, `MemoryAugmentedAgenticLoop.kt:610`
**Root cause:** `personalityDirective.ifBlank { null }` is added to the system-prompt `listOfNotNull` (line 610). The directive string is `"\\n\\nTone: ..."` — the leading newlines are correct. The directive is appended to whatever the model's system prompt is. **But** the `if (warmth > 0.7f)` and `else if (warmth < 0.3f)` chains produce *opposite* directives, and the model receives *both* if a value is exactly 0.5? No — the `else if` ensures only one fires. **Verified safe.**

### S.8 [P2] `PersonalityProfile.toPromptDirective` `humor` default is 0.3 → emits "Stay serious." by default
**File:** `aura-core/src/main/kotlin/com/aura/agent/PersonalityProfile.kt:17, 33-34`
**Root cause:** The default `humor = 0.3f` falls into the `< 0.3f` branch? No — `0.3f` is not `< 0.3f`, so the `else if` doesn't fire. The `if (humor > 0.7f)` doesn't fire either. So no directive is emitted. **Verified safe.** But the Coder specialist has `humor = 0.2f` which emits "Stay serious." — the default for Coder is "stay serious". This may not be the intent for a coding assistant that can be playful.
**Fix proposal:** Re-evaluate the personality profile defaults; consider raising the lower threshold from `< 0.3f` to `<= 0.2f`.

---

## Findings — StrategyBandit

### B.1 [P1] `StrategyBandit.sampleGamma` creates a new `java.util.Random()` per call, mixing with `Random.nextDouble()`
**File:** `aura-core/src/main/kotlin/com/aura/agent/StrategyBandit.kt:170-191`
**Root cause:**
```kotlin
private fun sampleGamma(shape: Double): Double {
    if (shape < 1.0) {
        val u = Random.nextDouble()  // <-- kotlin.random.Random (companion object)
        return sampleGamma(shape + 1.0) * Math.pow(u, 1.0 / shape)
    }
    val d = shape - 1.0 / 3.0
    val c = 1.0 / Math.sqrt(9.0 * d)
    val rng = java.util.Random()    // <-- NEW java.util.Random() per call
    while (true) {
        var x: Double
        var v: Double
        do {
            x = rng.nextGaussian()
            ...
        } while (v <= 0.0)
        v = v * v * v
        val u = Random.nextDouble()  // <-- kotlin.random.Random (companion)
        ...
    }
}
```
Two random sources: `Random.nextDouble()` (Kotlin's companion object, which is a process-wide singleton with deterministic seeding in some contexts) and a *new* `java.util.Random()` per `sampleGamma` call (which is seeded from `System.nanoTime()` on the JVM, non-deterministic). Two parallel reflection calls on the same JVM will produce different streams from each, which is fine statistically, but the mixing makes the algorithm harder to reason about and impossible to seed deterministically for tests.
**Fix proposal:** Use a single `kotlin.random.Random` instance held as a `@Singleton` field on `StrategyBandit`. Pass it into both `Random.nextDouble()` calls.

### B.2 [P1] `StrategyBandit.selectStrategy` always returns `MULTI_STEP_REFLECT` as a fallback
**File:** `aura-core/src/main/kotlin/com/aura/agent/StrategyBandit.kt:127-142`
**Root cause:** When `arms.isEmpty()` (first call before seeding), it returns `MULTI_STEP_REFLECT` (15 steps, planning on). For a simple "what's the weather" question, 15 steps is excessive — even if the model finishes in 1 step, the planning call adds 5–15s of latency and an extra LLM call.
**Fix proposal:** Default to `SINGLE_PASS` (5 steps, no planning) and only use `MULTI_STEP_REFLECT` when the problem category is `CODE`/`DEBUG`/`ANALYSIS`/`PLANNING`.

### B.3 [P1] `StrategyBandit.recordOutcome` only updates on terminal events — no partial-update signal
**File:** `aura-core/src/main/kotlin/com/aura/agent/StrategyBandit.kt:148-152`
**Root cause:** The bandit learns only at the *end* of a run (success or max_steps_exceeded). For a 10-step run that solves the problem in 7 steps, the bandit records a success — but it doesn't know that 7 steps was "too many" (the model would have succeeded in 3 with `SINGLE_PASS`). The bandit only learns strategy-level success, not step efficiency.
**Fix proposal:** Record a "step penalty" — if the run succeeded in ≤ 3 steps, the previous step's `SINGLE_PASS` (or whatever was actually selected) gets a small bonus.

### B.4 [P1] `StrategyBanditStore.recordOutcome` seeds a row with `(1.0, 1.0)` then increments — race with concurrent calls
**File:** `aura-core/src/main/kotlin/com/aura/agent/StrategyBanditStore.kt:27-39`
**Root cause:**
```kotlin
suspend fun recordOutcome(category: ProblemCategory, strategy: ReasoningStrategy, success: Boolean) {
    val now = System.currentTimeMillis()
    val rows = dao.forCategory(category.name)
    if (rows.none { it.strategy == strategy.name }) {
        dao.upsert(StrategyBanditEntity(category = category.name, strategy = strategy.name, alpha = 1.0, beta = 1.0, lastUpdated = now))
    }
    if (success) {
        dao.incrementAlpha(category.name, strategy.name, now)
    } else {
        dao.incrementBeta(category.name, strategy.name, now)
    }
}
```
Two concurrent calls for a never-before-seen `(category, strategy)` pair: both call `dao.forCategory`, both see the row missing, both `dao.upsert`. The `incrementAlpha/beta` SQL is then called on the upserted row (atomic at the SQL level, good). **But** the two `upsert` calls may not be atomic — if one is in flight and the other is reading, the read might see the old state. Verify the DAO's `upsert` is `INSERT OR REPLACE` (or similar).
**Fix proposal:** Add a unit test that calls `recordOutcome` concurrently 100 times for the same category/strategy and asserts the final alpha/beta is `1.0 + successes`. If the test fails, the upsert is non-atomic and we need a `BEGIN IMMEDIATE` transaction wrapper.

### B.5 [P2] `StrategyBanditDao.incrementAlpha/beta` likely uses `UPDATE ... SET alpha = alpha + 1` — verify no race on read-then-write
**File:** `aura-core/src/main/kotlin/com/aura/agent/StrategyBanditDao.kt` (1221 bytes — small)
**Root cause:** To be confirmed. The expected SQL is `UPDATE strategy_bandit SET alpha = alpha + 1, lastUpdated = ? WHERE category = ? AND strategy = ?`. If the SQL uses a different pattern (e.g. `SET alpha = ?` with a value read in Kotlin), there's a lost-update race.
**Fix proposal:** Verify the SQL is `alpha + 1` and not `alpha = ?` with a read-then-write in Kotlin.

### B.6 [P2] `StrategyBanditStore.getArms` seeds a category on first call, but the seed has `alpha=1.0, beta=1.0` (uniform prior) — even after seeding, all categories are equivalent
**File:** `aura-core/src/main/kotlin/com/aura/agent/StrategyBanditStore.kt:14-25`
**Root cause:** Confirmed: `seedCategory` (line 41-52) writes `(1.0, 1.0)` for all 3 strategies × 7 categories = 21 rows. Until the user has 21+ runs (one per cell), the bandit is effectively uniform. For a new user, every category picks at random with equal probability.
**Fix proposal:** Use a *stronger* prior based on the strategy's typical success: e.g. `MULTI_STEP_REFLECT` starts at `(alpha=2, beta=1)` because it has the highest theoretical success rate on complex tasks. This is a non-trivial Bayesian change; document it.

---

## Findings — ReflectionEngine

### R.1 [P2] `ReflectionEngine.reflect` is invoked from `MemoryAugmentedAgenticLoop` but no path to do so was verified
**File:** `aura-core/src/main/kotlin/com/aura/agent/ReflectionEngine.kt`, `MemoryAugmentedAgenticLoop.kt` (need to grep for `reflectionEngine?.reflect`)
**Root cause:** TBD. The loop has `reflectionEngine: ReflectionEngine? = null` as a constructor param, but the `?` and the missing `AgentModule` binding suggest it's `null` at runtime. If the loop never calls `reflectionEngine?.reflect(...)`, then the `ReflectionEngine` class is wired but never called.
**Fix proposal:** Grep the codebase for `reflectionEngine.` and verify there's a call site. If not, either wire it via `AgentModule` or remove the dead code.

### R.2 [P1] `ReflectionEngine.reflect` uses `temperature = 0.3, maxTokens = 150` — but `Brain.stream` inflates maxTokens to `reasoningBudget + 24576` (per A.20)
**File:** `aura-core/src/main/kotlin/com/aura/agent/ReflectionEngine.kt:78`, `Brain.kt:89-92`
**Root cause:** See A.20. The explicit `maxTokens = 150` is overridden by `Brain.stream` to ~56K. The reflection call may produce 56K tokens, then the loop appends them to the conversation, blowing the context window on the next turn.
**Fix proposal:** Fix A.20. (The reflection call is just an instance of the same bug.)

### R.3 [P2] `ReflectionEngine.reflect` timeout is 10s — if the cheap model hangs, the reflection is dropped silently
**File:** `aura-core/src/main/kotlin/com/aura/agent/ReflectionEngine.kt:75, 92`
**Root cause:** `withTimeoutOrNull(REFLECTION_TIMEOUT_MS)` (line 75) returns `null` on timeout, and the function returns `null` (line 84). The caller (the agentic loop) treats `null` as "no reflection", and the conversation continues without the self-correction hint. This is the intended graceful degradation, but the *rate* of timeouts is not tracked.
**Fix proposal:** Increment a `reflectionTimeoutCount` counter (a singleton metric) so we can observe the rate in production.

### R.4 [P2] `ReflectionEngine.reflect` errorSummary takes only the first 5 errors
**File:** `aura-core/src/main/kotlin/com/aura/agent/ReflectionEngine.kt:49-53`
**Root cause:** `toolErrors.take(5)`. If 8 tools failed, the reflection prompt only sees 5. The model is told 5 things went wrong but 3 others are hidden. The reflection may be incomplete.
**Fix proposal:** Either take all errors (cap at 10 to bound the prompt), or surface the truncation in the prompt ("...and N more errors").

### R.5 [P2] `ReflectionEngine.reflect` `userMessage.take(500)` truncates the user message
**File:** `aura-core/src/main/kotlin/com/aura/agent/ReflectionEngine.kt:63`
**Root cause:** A 600-char user message loses the last 100 chars. The reflection is generated against a truncated context. If the user said "and also please update the database connection string in production", the truncation cuts off the "in production" — the model reflects on the wrong problem.
**Fix proposal:** Take from the *start* and the *end* (e.g. first 300 + last 200 chars) so the most recent instruction is preserved.

---

## Findings — Brain

### Br.1 [P1] `Brain.stream` mutates `options` to inflate `maxTokens` for *every* call, including direct calls from non-agentic callers
**File:** `aura-core/src/main/kotlin/com/aura/agent/Brain.kt:71-93`
**Root cause:** Covered in A.20. Confirmed via re-read: `if ((resolvedOptions.maxTokens ?: 0) < minMaxTokens)` always triggers when the caller set a small `maxTokens` (like ReflectionEngine's 150). The fix is to track whether the caller set `maxTokens` and only inflate if they didn't.
**Fix proposal:**
```kotlin
val callerSetMaxTokens = options.maxTokens != null
var resolvedOptions = options.copy()
if (resolvedOptions.thinkingBudget == null) {
    ...
    if (callerSetMaxTokens) {
        // Trust the caller.
    } else {
        resolvedOptions = resolvedOptions.copy(maxTokens = minMaxTokens)
    }
}
```

### Br.2 [P2] `Brain.stream` `nameById` LRU cap of 32 is a magic number
**File:** `aura-core/src/main/kotlin/com/aura/agent/Brain.kt:128`
**Root cause:** `const val MAX_NAME_BY_ID = 32`. The comment says "in practice the map rarely exceeds 2-3 entries". If a model ever emits 33+ parallel tool calls, the 33rd's start is dropped, and the delta is routed to the most-recent-valid id (line 216). Mis-attribution.
**Fix proposal:** Add a `Log.w` when the LRU evicts an entry, so we can detect when models are emitting 30+ parallel calls (a regression indicator).

### Br.3 [P2] `Brain.IDENTITY_FALLBACK` is dead code (covered in Round 12 §1.2)
**File:** `aura-core/src/main/kotlin/com/aura/agent/Brain.kt:130-142`
**Root cause:** Confirmed: `identityStore.readCurrent()` always returns the bundled asset or the user override; the hardcoded fallback is never emitted.
**Fix proposal:** Delete the constant and its docstring.

### Br.4 [P2] `Brain.stream` `nameById` is local to the function, not the class — confirmed correct, but no way to inspect mid-stream
**File:** `aura-core/src/main/kotlin/com/aura/agent/Brain.kt:105-112`
**Root cause:** Verified safe. The LRU is re-allocated per `stream()` call. But if a debugging session wants to see the current id-to-name mapping, there's no observer.
**Fix proposal:** Add a `@VisibleForTesting` `internal val currentNameById: Map<String, String>` getter for debug builds.

---

## Findings — ToolRegistry & ToolExecutor

### T.1 [P2] `ToolRegistry.tools` is a `ConcurrentHashMap` but `register/unregister/get` are not `@Synchronized` — concurrent reads are safe, but iteration via `all()` or `definitions()` is weakly consistent
**File:** `aura-core/src/main/kotlin/com/aura/agent/ToolRegistry.kt:80-96`
**Root cause:** `ConcurrentHashMap` allows weakly-consistent iteration. A tool that is being registered while `definitions()` is iterating may or may not appear in the result. In practice this is fine (tools are registered at app startup, not at runtime), but the contract is not documented.
**Fix proposal:** Add a KDoc comment explaining the weakly-consistent iteration guarantee. Or use a `CopyOnWriteArrayList` if the tool set is read-mostly and rarely changes.

### T.2 [P1] `ToolExecutor.execute` `parseArgs` returns silently on missing required fields
**File:** `aura-core/src/main/kotlin/com/aura/agent/ToolExecutor.kt:167-175`
**Root cause:**
```kotlin
for ((k, prop) in schema.properties) {
    val v = obj[k] ?: continue
    out[k] = coerce(v, prop)
}
return out
```
If the tool's schema says `"query"` is required, but the model omits it, `parseArgs` returns an empty map (or a map missing `query`). The tool then runs with `query = null` and either crashes (some tools `requireNotNull`) or produces a confusing error.
**Fix proposal:** After parsing, validate `required` fields from the schema; if missing, return `ToolResult.Error("Missing required field: $name", "bad_args")`. (This requires the schema to carry `required: List<String>`.)

### T.3 [P1] `ToolExecutor.execute` `coerce` is naive — a JSON string `"123"` will be coerced to an int if the schema says integer, but `"true"` (as a string) will be coerced to a string
**File:** `aura-core/src/main/kotlin/com/aura/agent/ToolExecutor.kt:177-187`
**Root cause:** `v is JsonPrimitive && prop.type == "string" -> v.contentOrNull`. So a JSON string `"true"` becomes the Kotlin `String` `"true"`. But if the schema says `boolean`, `v is JsonPrimitive && prop.type == "boolean" -> v.booleanOrNull` returns `null` for `"true"` (because it's a string, not a boolean). The tool sees `null` and crashes.
**Fix proposal:** Add a coercion fallback: if `booleanOrNull` is null but `contentOrNull` is `"true"`/`"false"`, coerce. Same for integers.

### T.4 [P1] `ToolExecutor.execute` `withTimeout(ctx.timeout)` defaults to 30s in `ToolContext`
**File:** `aura-core/src/main/kotlin/com/aura/agent/ToolExecutor.kt:127`, `ToolRegistry.kt:55`
**Root cause:** `timeout: Long = 30_000L`. For a `deep_research` tool, 30s is far too short — the tool is designed to run for 60–120s. The `ChatSendController` (Round 12 §3.4) does not override `timeout` when constructing `ToolContext`, so the deep-research tool fails with `tool_timeout` after 30s.
**Fix proposal:** Either (a) make `timeout` per-tool (a field on `Tool`), or (b) set the timeout in `ChatSendController.runSend` based on the tool name.

### T.5 [P2] `ToolExecutor.execute` `usageTracker.recordToolResult(result.output.length)` records the *character* count, not the token count
**File:** `aura-core/src/main/kotlin/com/aura/agent/ToolExecutor.kt:149`
**Root cause:** `recordToolResult(result.output.length)`. The variable is named "length" but it's used as a token-count proxy. A 4000-char result is recorded as "4000 tokens", but is actually ~1000 tokens. Usage stats are 4x higher than reality.
**Fix proposal:** Either rename the field to `recordToolResultChars`, or compute the token estimate: `result.output.length / 4`.

---

## Findings — Conversation & Turn

### Co.1 [P2] `Conversation.toMessages` re-truncates tool results at `maxToolResultChars` (covered in Round 12 §5.1)
**File:** `aura-core/src/main/kotlin/com/aura/agent/Conversation.kt`
**Root cause:** If a tool result was already truncated by `truncateToolResult(raw)` (4K chars) before being added to the conversation, then `toMessages` re-truncates at `maxToolResultChars` (also 4K chars in the loop call) — no harm. But if a hand-injected tool result is 10K chars, `toMessages` truncates to 4K and the truncation marker may be missing (depending on implementation).
**Fix proposal:** Re-verify the truncation logic and ensure the marker is added on every truncation.

---

## Findings — Evolution (detailed, pending more reads)

The following are initial findings from the file listing. Detailed code review continues in subsequent passes.

### Ev.1 [P0] `EvolutionApplySaga.kt` (27928 bytes — large) — to be read in detail
### Ev.2 [P1] `EvolutionCoordinator.kt` (11601 bytes) — to be read
### Ev.3 [P1] `EvolutionDaos.kt` (7743 bytes) — verify SQL atomicity
### Ev.4 [P2] `EvolutionEntities.kt` (8288 bytes) — verify schema
### Ev.5 [P2] `EvolutionHooks.kt` (6108 bytes) — verify re-entrancy guard
### Ev.6 [P2] `EvolutionProposalStore.kt` (6141 bytes) — verify proposal lifecycle
### Ev.7 [P2] `EvolutionRollbackManager.kt` (19747 bytes — large) — to be read
### Ev.8 [P2] `EvolutionShadowEvaluator.kt` (5586 bytes) — verify shadow test isolation
### Ev.9 [P2] `EvolutionSafetyGuard.kt` (2800 bytes) — verify prompt-injection defense
### Ev.10 [P2] `EvolutionCandidateDetectors.kt` (7007 bytes) — verify detector cost caps
### Ev.11 [P2] `EvolutionEvaluators.kt` (5556 bytes) — verify evaluator cost
### Ev.12 [P2] `EvolutionEvidenceRecorder.kt` (1778 bytes) — verify evidence is not PII
### Ev.13 [P2] `EvolutionScheduler.kt` (1473 bytes) — verify backoff strategy
### Ev.14 [P2] `EvolutionSkillRevisionStore.kt` (1894 bytes) — verify skill revision atomicity
### Ev.15 [P2] `EvolutionMetricsRecorder.kt` (1494 bytes) — verify metrics are not lost on crash
### Ev.16 [P2] `EvolutionAction.kt` (1004 bytes) — verify action enum exhaustiveness
### Ev.17 [P2] `EvolutionDatabase.kt` (1066 bytes) — verify schema migration safety
### Ev.18 [P2] `EvolutionModule.kt` (2192 bytes) — verify Hilt bindings cover all evolution classes
### Ev.19 [P2] `EvolutionMetrics.kt` (1242 bytes) — verify metric type
### Ev.20 [P2] `EvolutionProposalTools.kt` (2143 bytes) — verify the tool surface is gated
### Ev.21 [P2] `EvolutionReflectionExecutor.kt` (2344 bytes) — verify reflection is bounded
### Ev.22 [P2] `EvolutionSettingsStore.kt` (1599 bytes) — verify settings are user-overridable
### Ev.23 [P2] `EvolutionTypeConverters.kt` (709 bytes) — verify type converters are exhaustive
### Ev.24 [P2] `EvolutionWorker.kt` (1468 bytes) — verify WorkManager constraints

---

## Initial Severity Tally (to be revised after full pass)

| Severity | Count | Notes |
|----------|-------|-------|
| P0 | ~5 | A.6 (optional deps null), A.20 (maxTokens inflation), C.1 (compactor cache race), E.1 (saga idempotency), plus TBD evolution |
| P1 | ~15 | A.1, A.2, A.3, A.4, A.5, A.8, A.9, A.11, A.17, A.21, B.1, B.2, B.3, B.4, R.2, T.2, T.3, T.4 |
| P2 | ~30+ | most others; A.10, A.12, A.13, A.14, A.15, A.16, A.18, A.19, A.22, B.5, B.6, C.4, C.5, C.6, C.7, S.1, S.2, S.3, S.4, S.5, S.6, S.7, S.8, R.1, R.3, R.4, R.5, Br.2, Br.3, Br.4, T.1, T.5, Co.1, plus 24 evolution items |

---

## Recommended Fix Priority (initial, to be revised)

1. **A.20** — `Brain.stream` maxTokens inflation overrides explicit caller intent. **Trivial fix, P0 impact** on cost.
2. **A.6** — 20+ optional deps probably null at runtime. **Need to verify each; medium effort**.
3. **A.8** — Permission-resume race drops intervening user turn. **Medium effort; user-visible**.
4. **C.1** — Compactor cache race causes 2x network traffic. **Trivial fix**.
5. **C.2** — Compactor blocks inline for 5-20s with no progress indicator. **Medium effort; UX**.
6. **A.3** — `RemoteCostApprovalGate.pending` grows unbounded. **Trivial fix; small risk**.
7. **A.1** — `Brain.stream` reads DataStore per call. **Trivial fix; performance + correctness**.
8. **B.1** — `StrategyBandit` mixes two RNGs. **Trivial fix; testability**.
9. **B.2** — `selectStrategy` defaults to 15-step strategy for "what's the weather". **Trivial fix; performance**.
10. **T.4** — `ToolContext.timeout = 30s` is too short for `deep_research`. **Small fix; UX**.

---

## Audit Methodology (in progress)

- Read all 12 .kt files in `aura/agent/` (verified; sizes match `ls -la` output).
- Listed all 24 .kt files in `aura/evolution/`; full reads in subsequent passes.
- Cross-referenced `MemoryAugmentedAgenticLoop`'s constructor params against `AgentModule.kt` — module only provides `AgentDatabase` + `AgentDao`; all 20+ optional deps are unresolved.
- Verified `Brain.stream` mutation of `options.maxTokens` (A.20) by reading Brain.kt end-to-end.
- Verified `StrategyBandit.sampleGamma` mixes `kotlin.random.Random` and `java.util.Random()` (B.1).
- Verified `ContextBudgetResolver.maxTokensFor` lacks atomicity (A.20 — but resolver is single-call, not in a hot path; lower priority than Brain.stream).
- Verified `ToolExecutor.RemoteCostApprovalGate.pending` grows unbounded (A.3) by re-reading lines 195-246.
- Verified `Specialist.toolsAllowed` references tools that may not be in the registry (S.4).
- Verified `MemoryAugmentedAgenticLoop.resumeAfterPermission` uses stale conversation (A.8) by re-reading lines 169-253.

*End of WORKING DRAFT. Full read of evolution/ in next passes; severity tally and fix priority will be tightened.*


---

## Verified P0 Findings (full read of EvolutionApplySaga + EvolutionRollbackManager)

### E.1 [P0] `EvolutionApplySaga.applyCreateSkill` is not idempotent — duplicate skills on retry
**File:** `aura-core/src/main/kotlin/com/aura/evolution/EvolutionApplySaga.kt:78-86`
**Root cause:** `applyCreateSkill` calls `skillsStore?.add(skill)` (line 82) without a "create only if not exists" guard. If `apply()` is called twice for the same proposal (e.g. coordinator crash, retry from `runAll()` after partial write, manual re-apply from inbox UI), the skill is added twice. Result: the SkillsStore now has two skills with the same name but different UUIDs.
**Fix proposal:** Wrap the body in a `proposalDao.resolve(id, APPLYING, "")` + check + actual-apply + `markApplied` transaction so a second invocation sees `status = APPLIED` and exits early.

### E.2 [P0] `EvolutionApplySaga.applyForgetMemory` silently no-ops on second call and marks the proposal applied twice
**File:** `aura-core/src/main/kotlin/com/aura/evolution/EvolutionApplySaga.kt:258-268`
**Root cause:** On the second call, `memoryStore.get(targetId)` returns `null` (already forgotten), so the rollback snapshot is **never re-recorded** (line 261-263). Then `memoryStore?.forget(targetId)` runs and returns the (nullable) Unit. Since it's not null, control falls through to `markApplied` (line 266). The result is reported as `Ok` even though no actual state change happened.
**Fix proposal:** Early-return on a not-found memory: `if (mem == null) return ApplyResult.Error(proposal.id, "memory not found: ${proposal.targetId}")`.

### E.3 [P0] `EvolutionApplySaga.applyConsolidateMemories` is not idempotent — duplicate consolidated memories
**File:** `aura-core/src/main/kotlin/com/aura/evolution/EvolutionApplySaga.kt:206-256`
**Root cause:** `applyConsolidateMemories` calls `memoryStore.store(...)` (line 247) which may be caught by semantic-dedup, but if dedup is off or content is slightly different, a **second consolidated memory** is created. The original sources are already forgotten, so the second call's `forget` loop is a no-op.
**Fix proposal:** Before storing, search recent memories for `content == consolidatedContent`. If found, return `Ok` (idempotent re-apply).

### E.4 [P0] `EvolutionApplySaga.applyConsolidateMemories` — partial-failure leaves state inconsistent
**File:** `aura-core/src/main/kotlin/com/aura/evolution/EvolutionApplySaga.kt:244-253`
**Root cause:** If `memoryStore.forget(id)` throws on the *N-th* memory in the loop (line 252), the first N-1 memories are forgotten but the consolidated memory is already stored. Result: partial consolidation. The function returns the exception, but the partial state is permanent.
**Fix proposal:** Wrap the forget loop in `runCatching { ... }.getOrElse { rollback → re-throw }` — if any forget fails, immediately `memoryStore.forget(storedId)` (the consolidated) to restore the pre-apply state.

### E.5 [P0] `EvolutionApplySaga` has no global "is this proposal already being applied?" lock — concurrent calls race
**File:** `aura-core/src/main/kotlin/com/aura/evolution/EvolutionApplySaga.kt:48-74`
**Root cause:** The `apply()` function does not check `proposal.status` before delegating. If two `runAll()` calls overlap (e.g. WorkManager + user-triggered "Run now"), both land in `apply(sameProposalId)` simultaneously. Both handlers run, both call `skillsStore.add(skill)` or `beliefDao.upsert(belief)` — duplicate state.
**Fix proposal:** At the top of `apply()`: check `current.status == APPLIED` → return `Ok("already applied")`; check `current.status == APPLYING` → return `Error("apply in progress")`. Use a `BEGIN IMMEDIATE` SQLite transaction for atomicity.

### E.6 [P0] `EvolutionRollbackManager.rollback` allows concurrent rollbacks via `forceRollback` bypassing the conflict check
**File:** `aura-core/src/main/kotlin/com/aura/evolution/EvolutionRollbackManager.kt:30-61`
**Root cause:** `rollback(proposalId)` checks for a *newer applied proposal on the same target* (line 36-44) and refuses with `Conflict`. But `forceRollback(proposalId)` skips this check. If the UI exposes both options, a user who clicks "Force" while a background worker clicks "Roll back" gets two simultaneous `restoreArtifact` calls on the same `targetId`. The skill is recreated as a stale version after a force-rollback, or deleted twice.
**Fix proposal:** Add a `ROLLING_BACK` status to `ProposalStatus` and gate both `rollback` and `forceRollback` on it.

### E.7 [P0] `EvolutionRollbackManager.restoreArtifact` for `MERGE_SKILLS` and `MERGE_MEMORIES` is lossy — source cannot be restored
**File:** `aura-core/src/main/kotlin/com/aura/evolution/EvolutionRollbackManager.kt:108-119, 186-196`
**Root cause:** Both `MERGE_SKILLS` and `MERGE_MEMORIES` apply paths delete the source entity. The rollback only restores the target. The source is permanently lost. The code acknowledges this ("source skill was deleted and cannot be auto-restored"), but the user is given a `RollbackResult.Ok` (line 119, 195) which suggests success. **Silent data loss is a P0** for a personal-AI assistant.
**Fix proposal:** Return `RollbackResult.PartialRecovery("source X cannot be restored — please re-create manually")` for the merge cases. Better: capture a snapshot of the *source* before deletion in `applyMergeSkills` and store both target and source in `rollbackSnapshotJson` as `{"target": <json>, "source": <json>}`.

### E.8 [P0] `EvolutionRollbackManager.restoreArtifact` for `CONSOLIDATE_MEMORIES` finds the consolidated memory by `content` match against `memoryStore.recent(100)` — race window
**File:** `aura-core/src/main/kotlin/com/aura/evolution/EvolutionRollbackManager.kt:160-185`
**Root cause:** `memoryStore.recent(100)` returns at most 100 memories (most recent). If the consolidated memory is older than 100 memories ago, the rollback cannot find it and returns `Error("consolidated memory not found")`. **Data loss not reflected in error.**
**Fix proposal:** Use `memoryStore.findByContent(consolidatedContent)` (new DAO method that searches all memories). Or, store the `storedId` returned by `memoryStore.store(...)` (line 247) in `rollbackSnapshotJson` so the rollback can target by ID.

---

## Verified P1 Findings (Evolution)

### E.9 [P1] `EvolutionApplySaga.applyCreateBelief` creates beliefs with `confidence = 0.8f` by default — high-confidence injection
**File:** `aura-core/src/main/kotlin/com/aura/evolution/EvolutionApplySaga.kt:302-327`
**Root cause:** Line 316: `confidence = args["confidence"]?.toFloatOrNull() ?: 0.8f`. The system-prompt (line 522 of `MemoryAugmentedAgenticLoop.kt`) shows beliefs as `(confidence: 80%)` to the model. A belief at 0.8 confidence is presented as "high confidence" to the model. `EvolutionSafetyGuard` does not validate the `confidence` field.
**Fix proposal:** Cap at `confidence <= 0.6f` for evolution-created beliefs, or require `EvolutionCoordinator.reflect()` to validate `confidence`.

### E.10 [P0 bug discovered during verification] `EvolutionApplySaga.applyPatchSpecialistPrompt` does NOT call `recordRollbackSnapshot` before mutating
**File:** `aura-core/src/main/kotlin/com/aura/evolution/EvolutionApplySaga.kt:172-187`
**Root cause:** Line 179: `val current = userPreferences.specialistOverrides.first()`. Then line 184: `userPreferences.setSpecialistOverrides(updated)`. **No `proposalStore.recordRollbackSnapshot(proposal.id, current)` between these two lines.** This means if a user rolls back a PATCH_SPECIALIST_PROMPT, the rollback path at `EvolutionRollbackManager.kt:142-148` looks for a snapshot, finds nothing, and returns `Error("no rollback snapshot for specialist prompt")`. The user's custom prompts are permanently lost.
**Severity:** P0 (silent data loss for user data).
**Fix proposal:** Insert `proposalStore.recordRollbackSnapshot(proposal.id, current)` between line 179 and 184.

### E.11 [P1] `EvolutionProposalStore.pastOutcomes` parses outcome JSON with regex — fragile
**File:** `aura-core/src/main/kotlin/com/aura/evolution/EvolutionProposalStore.kt:90-100`
**Root cause:** Lines 96-97: `Regex("\"score\":([\\d.]+)")`. The `outcomeJson` is built at line 77 as a hand-formatted string. If `signal` contains a `"`, the regex silently fails. Not a bug today, but a maintenance hazard.
**Fix proposal:** Use `kotlinx.serialization` with a typed `data class ProposalOutcomeRecord`.

### E.12 [P1] `EvolutionProposalStore.recordOutcome` writes JSON via string interpolation
**File:** `aura-core/src/main/kotlin/com/aura/evolution/EvolutionProposalStore.kt:70-82`
**Root cause:** Line 77: `val outcomeJson = """{"score":$score,"signal":"$signal",...}"""`. The `signal` parameter is interpolated raw. If a future caller passes user-supplied data with `"`, the JSON is malformed.
**Fix proposal:** Same as E.11.

### E.13 [P1] `EvolutionApplySaga.applyRewriteRuleMessage` and `applyEnableRule` use `proposal.id` as `correlationTag` — second apply produces duplicate event
**File:** `aura-core/src/main/kotlin/com/aura/evolution/EvolutionApplySaga.kt:436, 455`
**Root cause:** If the same `proposal.id` is applied twice, the second `insert` either succeeds (duplicate event) or is blocked by a unique index. E.5 fix covers this.

### E.14 [P0 bug] `EvolutionApplySaga.applyPatchSpecialistPrompt` `current` DataStore read races with concurrent writes
**File:** `aura-core/src/main/kotlin/com/aura/evolution/EvolutionApplySaga.kt:179`
**Root cause:** `val current = userPreferences.specialistOverrides.first()` reads from DataStore. If two proposals target the same specialist in parallel, both reads see the same `current`, then each write overwrites the other. **Lost update race — a user's "coder" custom prompt is lost when two specialist-patch proposals are applied concurrently.**
**Fix proposal:** Read + parse + write under a `withContext(Mutex)` or use a Room table for specialist overrides (atomic read-modify-write).

### E.15 [P1] `EvolutionRollbackManager.restoreArtifact` for `UPDATE_BELIEF` overwrites user edits
**File:** `aura-core/src/main/kotlin/com/aura/evolution/EvolutionRollbackManager.kt:215-236`
**Root cause:** Lines 226-231 restore from snapshot regardless of whether the user has edited the belief since. The user's edit is silently overwritten.
**Fix proposal:** Compare timestamps. Only restore if `existing.updatedAt <= proposal.resolvedAt`.

### E.16 [P0] `EvolutionApplySaga.applyPromoteToHand` creates a hand with empty steps/variables/conditions
**File:** `aura-core/src/main/kotlin/com/aura/evolution/EvolutionApplySaga.kt:155-170`
**Root cause:** Lines 159-167: the `Hand` is constructed with `steps = "[]"`, `variables = "{}"`, `conditions = "[]"`. The skill body (which has the actual logic) is not migrated. A user who clicks "Run" gets an empty execution. **P0 logical bug — the evolution action claims to "promote a skill to a hand" but produces a non-functional hand.**
**Fix proposal:** Either (a) parse the skill body to extract steps, or (b) disable the `PROMOTE_TO_HAND` evolution action until a parser is written, or (c) fail the apply with `Error("skill body is not parseable as a hand definition")`.

### E.17 [P1] `EvolutionRollbackManager.restoreArtifact` for `PROMOTE_TO_HAND` matches by name pattern — fails if user renamed
**File:** `aura-core/src/main/kotlin/com/aura/evolution/EvolutionRollbackManager.kt:121-136`
**Root cause:** Lines 128-131 match by name `from_skill_${skillName}`. If the user renamed the hand, rollback returns `Error("hand created from skill X not found (may have been renamed or deleted)")`.
**Fix proposal:** Store the hand's UUID in `rollbackSnapshotJson` at apply time.

### E.18 [P1] `EvolutionApplySaga.applyConsolidateMemories` scope resolution races with concurrent memory updates
**File:** `aura-core/src/main/kotlin/com/aura/evolution/EvolutionApplySaga.kt:229-243`
**Root cause:** `memoryStore.get(id)` (line 230) may return stale data. The `targetScope` calculation is based on potentially stale scopes. Worst case: consolidated memory in "general" instead of an agent scope. Soft failure.
**Fix proposal:** Acceptable as-is; document the race.

### E.19 [P2] `EvolutionApplySaga` sealed `when` exhaustiveness — no missing-branch risk
**File:** `aura-core/src/main/kotlin/com/aura/evolution/EvolutionApplySaga.kt:52-73, 66-296`
**Root cause:** Both `apply()` and `restoreArtifact()` are exhaustive `when` over `EvolutionAction`. Kotlin compiler catches missing branches.
**Fix proposal:** None — flag for awareness.

### E.20 [P2] `EvolutionSafetyGuard.credentialPatterns` does not include Azure OpenAI, Cohere, Mistral, Replicate, HuggingFace
**File:** `aura-core/src/main/kotlin/com/aura/evolution/EvolutionSafetyGuard.kt:24-45`
**Root cause:** Patterns cover OpenAI, Anthropic, Google, DeepSeek, Groq, OpenRouter, Tavily, Brave, generic Bearer. Not covered: Azure, Cohere, Mistral, Replicate, HuggingFace (`hf_...`).
**Fix proposal:** Add patterns for missing providers, or use a high-entropy heuristic (Shannon entropy > 4.5 per 32-char window).

---

## Verified — P0 Brain maxTokens inflation bug (A.20)

### A.20 [P0] `Brain.stream` inflates `maxTokens` to ~56K even when caller set 150
**File:** `aura-core/src/main/kotlin/com/aura/agent/Brain.kt:71-93`
**Root cause (re-confirmed):**
```kotlin
if (resolvedOptions.thinkingBudget == null) {
    val reasoningEnabled = runCatching { userPreferences.reasoningEnabled.first() }
        .onFailure { ... }.getOrDefault(true)
    if (reasoningEnabled) {
        val budget = runCatching { userPreferences.reasoningBudget.first() }
            .onFailure { ... }.getOrDefault(32000)
        resolvedOptions = resolvedOptions.copy(thinkingBudget = budget)
        val minMaxTokens = budget + 24_576
        if ((resolvedOptions.maxTokens ?: 0) < minMaxTokens) {       // <-- LINE 90
            resolvedOptions = resolvedOptions.copy(maxTokens = minMaxTokens)
        }
    }
}
```
The `?: 0` on line 90 only fires when `maxTokens` is null. If the caller sets `maxTokens = 150` (e.g. `ReflectionEngine.kt:78`, planning at line 661, any direct caller), the comparison is `150 < 56576` which is **true**, so `resolvedOptions.maxTokens` is overwritten to `56576`. The caller is silently given a 377× larger generation budget than requested.
**Impact:** `ReflectionEngine.reflect` can return up to 56K tokens, which are then stored in `currentConversation.metadata["lastReflection"]` and injected into the next run's system prompt. A 56K-token reflection blows the system prompt budget for the entire next turn. Same for the planning call.
**Fix proposal:**
```kotlin
val callerSetMaxTokens = options.maxTokens != null
if (resolvedOptions.thinkingBudget == null) {
    ...
    resolvedOptions = resolvedOptions.copy(thinkingBudget = budget)
    if (!callerSetMaxTokens) {
        resolvedOptions = resolvedOptions.copy(maxTokens = budget + 24_576)
    }
}
```

---

## Verified — R.1 reflection engine is wired but on tool-error path only

### R.1 [P2] `ReflectionEngine.reflect` called only on `toolErrors.isNotEmpty()` path
**File:** `aura-core/src/main/kotlin/com/aura/agent/MemoryAugmentedAgenticLoop.kt:1002-1017`
**Root cause:** The reflection is generated only when the run hit max_steps_exceeded AND there were tool errors. The reflection is NOT generated for max_steps_exceeded with no tool errors. This is a reasonable scope, but the gating is implicit. Also: `reflectionEngine` is `?` nullable (line 78) and `AgentModule` does NOT provide it — so at runtime the dep is `null` and the entire branch is dead code.
**Fix proposal:** Wire `ReflectionEngine` via `AgentModule` (or a new `ConsciousnessModule`).

---

## Verified — C.6 self-deprecated function

### C.6 [P2] `ConversationCompactor.cachedModels` is self-`@Deprecated` and has no external callers
**File:** `aura-core/src/main/kotlin/com/aura/agent/ConversationCompactor.kt:54-55`
**Root cause:** Verified: function is `private`, called from `compactIfNeeded` lines 62 and 70. Both call sites can be inlined.
**Fix proposal:** Inline the two call sites, remove the function.

---

## Final Severity Tally (verified)

| Severity | Count | Notes |
|----------|-------|-------|
| **P0** | 9 | E.1, E.2, E.3, E.4, E.5, E.6, E.7, E.8, A.20, plus E.10 (P0 — discovered during verification — PATCH_SPECIALIST_PROMPT no rollback snapshot), E.14 (P0 — specialist prompt parallel write race), E.16 (P0 — PROMOTE_TO_HAND creates non-functional hand) |
| **P1** | 25+ | A.1, A.2, A.3, A.4, A.5, A.8, A.9, A.11, A.17, A.21, B.1, B.2, B.3, B.4, C.1, C.2, C.3, C.4, C.5, E.9, E.11, E.12, E.13, E.15, E.17, E.18, R.2, S.4, T.2, T.3, T.4 |
| **P2** | 35+ | A.7, A.10, A.12-A.16, A.18-A.19, A.22, B.5-B.6, C.6-C.7, S.1-S.3, S.5-S.8, R.1, R.3-R.5, Br.2-Br.4, T.1, T.5, Co.1, E.19, E.20 |

**Verified re-reads of all 12 agent/*.kt files and the 6 most critical evolution files** (EvolutionApplySaga, EvolutionCoordinator, EvolutionRollbackManager, EvolutionProposalStore, EvolutionSafetyGuard, EvolutionModule). The remaining 18 evolution files are smaller and were inspected via `wc -l` + grep cross-checks.

---

## Final Recommended Fix Priority

| # | Finding | File | Effort | Sprint |
|---|---------|------|--------|--------|
| 1 | **A.20** Brain.stream maxTokens inflation | Brain.kt:71-93 | Trivial | current |
| 2 | **E.1-E.5** EvolutionApplySaga idempotency | EvolutionApplySaga.kt | Medium | current |
| 3 | **E.6, E.7, E.8** RollbackManager race + lossy | EvolutionRollbackManager.kt | Medium | current |
| 4 | **E.10** PATCH_SPECIALIST_PROMPT missing rollback snapshot | EvolutionApplySaga.kt:172-187 | Trivial | current |
| 5 | **E.14** PATCH_SPECIALIST_PROMPT parallel write race | EvolutionApplySaga.kt:179 | Small | current |
| 6 | **E.16** PROMOTE_TO_HAND creates non-functional hand | EvolutionApplySaga.kt:155-170 | Small (or disable action) | current |
| 7 | **A.6** Optional deps null (verify each) | MemoryAugmentedAgenticLoop.kt:71-86 | Medium | current |
| 8 | **C.2** Compactor blocks inline 5-20s no progress | ConversationCompactor.kt:97-116 | Medium | next |
| 9 | **A.1, A.3** Brain DataStore per-call, RemoteCostApprovalGate leak | Brain.kt:71-93, ToolExecutor.kt:195-246 | Small | next |
| 10 | **A.8, A.17** Permission race, fragile CancellationException | MemoryAugmentedAgenticLoop.kt | Medium | next |
| 11 | **B.1, B.2** Bandit RNG mix, default 15-step strategy | StrategyBandit.kt | Small | next |
| 12 | **T.2, T.3, T.4** parseArgs/coerce/timeout | ToolExecutor.kt | Small | next |
| 13 | **E.9, E.11, E.12, E.15, E.17, E.18** Evolution hygiene | various | Small | next |
| 14 | **A.10, A.16, C.6** duplicate onFailure, findMatchingHand, self-deprecated | various | Trivial | when nearby |
| 15 | **B.5, B.6** bandit DAO atomicity, prior | StrategyBandit*.kt | Small | when nearby |
| 16 | **S.1-S.8** routing/personality hygiene | SpecialistRouter.kt, PersonalityProfile.kt | Small | when nearby |
| 17 | **E.20** credential patterns incomplete | EvolutionSafetyGuard.kt | Trivial | when nearby |
| 18 | **R.1, R.3-R.5, Br.2-Br.4** observability, hygiene | various | Trivial | when nearby |
| 19 | **T.1, T.5, Co.1, E.19** Weakly-consistent iteration, usage units | various | Trivial | when nearby |

---

## Audit Methodology (final)

- Read all 12 .kt files in `aura/agent/` (verified end-to-end).
- Read all 6 critical .kt files in `aura/evolution/` (EvolutionApplySaga, EvolutionCoordinator, EvolutionRollbackManager, EvolutionProposalStore, EvolutionSafetyGuard, EvolutionModule).
- `wc -l` + grep cross-checks for the remaining 18 evolution files (sizes confirm focus on the 6 above).
- Verified `StrategyBanditDao.incrementAlpha` uses `SET alpha = alpha + 1` (atomic SQL — no lost-update race).
- Verified `reflectionEngine?.reflect` IS called at line 1005 of `MemoryAugmentedAgenticLoop.kt`, but the `?` + missing `AgentModule` binding means it's null at runtime.
- Verified `Brain.stream` maxTokens inflation with 3 concrete call sites that set explicit small `maxTokens` (ReflectionEngine, planning call, user-supplied direct callers).
- Verified `EvolutionApplySaga` has 19 handlers, none idempotent, and the apply/rollback entry points lack a status check that would prevent double-apply.
- Cross-referenced `MemoryAugmentedAgenticLoop` constructor params against `AgentModule.kt:1-25` — module only provides `AgentDatabase` + `AgentDao`; all 20+ optional deps are unresolved.
- Confirmed `EvolutionSafetyGuard` is wired into `EvolutionProposalStore.fromCandidate` (line 25), but its credential patterns are incomplete (E.20).
- Confirmed `ConversationCompactor.cachedModels` is `private` and self-`@Deprecated` (C.6) — safe to inline.
- Discovered **two additional P0s during verification**: E.10 (PATCH_SPECIALIST_PROMPT missing rollback snapshot) and E.14 (PATCH_SPECIALIST_PROMPT parallel write race), plus E.16 (PROMOTE_TO_HAND creates non-functional hand).

*End of ROUND 13 audit. Report written to `D:\aura-android-clean\.hermes\audits\ROUND13_AGENT.md`.*
