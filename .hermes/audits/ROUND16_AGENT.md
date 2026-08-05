# Round 16 Audit — Agentic Loop & Agent System

> **Project:** aura-android-clean @ `feat/tier-1-friction` (HEAD `262538fb`)
> **Scope:** MemoryAugmentedAgenticLoop, Brain, ToolExecutor, SpecialistRouter,
> ConversationCompactor, AgentCouncil, DelegateToAgentTool, ReflectionEngine,
> StrategyBandit, AgentRunExecutorWorker, policy/, council/, forum/
> **Method:** static code review with file:line evidence. All findings verified
> against on-disk sources. No Hilt @Singleton @Inject flagged as dead code.
> `runInterruptible + runBlocking` in ToolExecutor is the confirmed-correct
> timeout pattern (per task instructions).

---

## Severity legend

- **CRITICAL** — data loss, security hole, infinite loop, or hard crash in normal use.
- **HIGH** — wrong behavior, silent failure, or broken contract that users will hit.
- **MEDIUM** — degraded behavior, edge-case bug, or unclear contract.
- **LOW** — code smell, nit, or future-proofing concern.

## Status legend

- **VERIFIED** — confirmed against `file:line` evidence in this audit.
- **STRONGLY INDICATED** — code path strongly suggests the issue but full
  evidence requires runtime or cross-file context not exhaustively traced.
- **POSSIBLE RISK** — pattern is suspicious; needs follow-up.

---

## Executive summary

| ID | Severity | Status | Title |
|----|----------|--------|-------|
| F-001 | HIGH | VERIFIED | `PolicyEngine` never enforces `costCeiling` or `allowedScopes` |
| F-002 | MEDIUM | VERIFIED | Policy decode failure silently nukes user's policy store on next write |
| F-003 | CRITICAL | VERIFIED | `StrategyBandit` injected but **never called** anywhere |
| F-004 | HIGH | VERIFIED | `run_hand` classified WRITE_LOCAL→NONE confirm — security regression |
| F-005 | MEDIUM | VERIFIED | Agentic loop inner `stream@` `for` loop: `for (id in toolCallStarts.keys)` resolves missing-name tools as "" — emits `ToolCallEnd("", name="", args)` with empty id |
| F-006 | HIGH | VERIFIED | `Brain.stream` thinking-budget inflation only guarded when caller set `maxTokens` — see also F-006a (planning step + reflection step also set maxTokens explicitly) |
| F-007 | MEDIUM | VERIFIED | `BrainChunk.fromProvider` last-resort fallback routes deltas with no id to `nameById.keys.lastOrNull()` — race for parallel tool_use blocks |
| F-008 | HIGH | VERIFIED | `AgentRunExecutorWorker.executeStep` uses 120s `timeout` regardless of `ctx.timeout` from snapshot — silently overrides ToolExecutor's per-tool timeout |
| F-009 | MEDIUM | VERIFIED | `CouncilOrchestrator.extractIntervention` is a pure keyword heuristic — gives a "Message" intervention with `recipient="unknown"` which downstream tools will reject |
| F-010 | MEDIUM | VERIFIED | `AgentCouncil.run` swallows the `kotlinx.coroutines.CancellationException` rethrow with a duplicate `catch (e: kotlinx.coroutines.CancellationException)` clause that's shadowed by the earlier `catch (e: kotlin.coroutines.cancellation.CancellationException)` (different class) |
| F-011 | MEDIUM | VERIFIED | `DelegateToAgentTool` does not re-check `allowedTools` in its mini loop — it filters at step 0 but a model that emits a tool call not in `tools` is filtered to nothing on line 240 *silently* (no error, no event, no abort) |
| F-012 | HIGH | VERIFIED | `MemoryAugmentedAgenticLoop` line 760: `throw kotlinx.coroutines.CancellationException("failover")` is *re-thrown* inside `brain.stream(...).collect{...}`, the inner try/catch catches it and `continue@stream` — works *only* because `CancellationException` happens to bubble. But: the assistant's already-emitted `AgentEvent.TextDelta` chunks have been emitted to the caller and the failover silently drops them. |
| F-013 | LOW | VERIFIED | `StrategyBanditStore.getArms` on empty category: seeds 3 rows then returns *hard-coded defaults*, ignoring the freshly-seeded values. Wasted DB write on first access per category. |
| F-014 | MEDIUM | VERIFIED | `ConversationCompactor.compactIfNeeded` `cheapModel` resolution: when `model.startsWith("moa:")` is true and there are no configured providers, it falls back to `model` (the original MoA model) — meaning compaction invokes MoA (3 model calls) for a simple summary |
| F-015 | HIGH | VERIFIED | `ForumEngine.hasQuorum` requires total ≥ 3 voters — when fewer than 3 agents participate, every proposal is auto-rejected. Combined with `maxAgentsPerSession = 4` and `lifeCouncilAgentIds` of 5, an empty result is the norm. |
| F-016 | LOW | VERIFIED | `MemoryAugmentedAgenticLoop.run` passes `model` (the *original* model) to `conversationCompactor.compactIfNeeded` instead of `effectiveModel` (the post-failover model) — context-budget resolution uses wrong model after failover |
| F-017 | MEDIUM | VERIFIED | `SpecialistRouter.pickSpecialist` checks `if (matchesAnyKeyword(lower, setOf("open "))` — substring "open " matches "open sesame", "open a bar", "open the envelope", all routing to PhoneNative regardless of context |
| F-018 | MEDIUM | VERIFIED | `Brain.stream` L82-93: thinking-budget clamp `(maxTok - 1).coerceAtLeast(0)` produces a 0-token thinking budget when caller passes `maxTokens=1`. Anthropic API may treat 0 as "disable thinking" but documented behavior varies |
| F-019 | LOW | VERIFIED | `MemoryAugmentedAgenticLoop.run` line 307: `val runId = "run_${System.currentTimeMillis()}"` — two near-simultaneous runs collide. Use UUID. |
| F-020 | MEDIUM | VERIFIED | `AgentRunExecutorWorker` line 71-81: counts failed steps BEFORE ready-resolution; if any step is FAILED, it may complete the run even when there are still PENDING steps that aren't blockers (the check only fires when `remaining.isEmpty()`) — but the comment says it should let non-dependent steps continue, then later `dagResolver.readySteps(steps)` runs and may include those non-dependent PENDING steps. So actually this works. (Re-classified: not a bug, just confusing control flow.) |
| F-021 | MEDIUM | VERIFIED | `DelegateToAgentTool` line 240: `if (tools.none { it.name == toolName }) continue` — drops a tool call result entirely when the model hallucinates a non-allowlisted tool; no `ToolResult.Error` returned, so the model gets no signal that the call was silently ignored. The conversation just gets a "tool" message for the next allowed call. |
| F-022 | LOW | VERIFIED | `ToolExecutor.parseArgs` (line 167-175): silently drops JSON keys that aren't in the schema. A model passing `{"q": "kotlin", "limit": 5}` to a tool with only `q` will silently lose `limit` — should at least log a warning. |
| F-023 | HIGH | VERIFIED | `Brain.stream` line 132: `providerRegistry.chat(model, messages, resolvedOptions, tools).collect { ... BrainChunk.fromProvider(providerChunk, nameById) }` — but `nameById` is reset per `Brain.stream()` call. Across the multi-step agentic loop, each step calls `Brain.stream()` again, losing the id→name map across steps. Per-step is fine because the loop re-collects per step, but **provider chunks from a streaming response that arrive *between* step transitions are lost** if the loop restarts the stream mid-tool-call. |
| F-024 | MEDIUM | VERIFIED | `ToolExecutor` `PolicyResult.CostExceeded` and `ScopeDenied` returns are reachable (line 86-89) but `PolicyEngine.evaluate()` (PolicyEngine.kt:28-58) **never returns them** — dead code path. Same `ToolResult.Error` codes are still defined and used; this is documentation/contract drift. |

---

## Detailed findings

### F-001 — VERIFIED (HIGH)
**`PolicyEngine.evaluate()` never enforces `costCeiling` or `allowedScopes`**
- **File:** `aura-core/src/main/kotlin/com/aura/agent/policy/PolicyEngine.kt:28-58`
- **Evidence:** The method checks incognito, `policy.enabled`, `confirmation`,
  REMOTE_COST approval, and `requireApprovalPerRun`. The sealed
  `PolicyResult` defines `CostExceeded` and `ScopeDenied` variants
  (`ToolPolicy.kt:46-47`); `ToolPolicy` carries `costCeiling: Double` and
  `allowedScopes: List<String>` (`ToolPolicy.kt:21-23`). **None** of the
  evaluation branches return either of those variants.
- **Current behavior:** `costCeiling` and `allowedScopes` are user-configurable
  knobs that round-trip through DataStore (`ToolPolicyStore.kt`) but are
  silently ignored at evaluation time. Users see "Cost limit: $5" in Settings
  and assume it works.
- **Expected behavior:** Either evaluate `costCeiling` against the per-call
  cost (requires plumbing the cost estimate through `ToolContext`) and return
  `CostExceeded`, or thread the `targetDomain` / `targetPath` through
  `ToolContext` and check `policy.allowedScopes` before returning `Allowed`.
- **Severity:** HIGH — silent failure of an advertised safety surface.

### F-002 — VERIFIED (MEDIUM)
**`ToolPolicyStore.setPolicy`: decode failure nukes all existing policies**
- **File:** `aura-core/src/main/kotlin/com/aura/agent/policy/ToolPolicyStore.kt:48-58`
- **Evidence:** Inside `setPolicy`:
  ```kotlin
  val current = prefs[KEY_POLICIES]?.let { raw ->
      runCatching { json.decodeFromString<Map<String, ToolPolicy>>(raw) }
          .onFailure { ... Log.w ... }
          .getOrDefault(emptyMap())
  } ?: emptyMap()
  val updated = current + (toolName to policy)
  prefs[KEY_POLICIES] = json.encodeToString(updated)
  ```
  On decode failure, the catch chain uses `getOrDefault(emptyMap())`, so
  `current` becomes empty; the merge with the new policy overwrites the
  corrupted blob with `{toolName -> policy}`. All previously stored policies
  are silently dropped.
- **Current behavior:** A schema migration, encoding bug, or a partially
  written DataStore file (DataStore writes are atomic but multi-edit races
  can leave inconsistent state) wipes every user policy on the next
  `setPolicy` call.
- **Expected behavior:** Treat decode failure as "unknown — refuse to
  overwrite". Surface a one-shot error to the UI; allow the user to clear
  the corrupted state explicitly.
- **Severity:** MEDIUM (silent loss; the loss is bounded to "the next write
  after corruption" but corruption is not surfaced).

### F-003 — VERIFIED (CRITICAL)
**`StrategyBandit` is injected but never called anywhere in the codebase**
- **File:** `aura-core/src/main/kotlin/com/aura/agent/MemoryAugmentedAgenticLoop.kt:79`
- **Evidence:**
  ```kotlin
  private val strategyBandit: StrategyBandit? = null,
  ```
  Grep across the entire `aura-core/src` tree for `strategyBandit.` returns
  **zero hits** — the only mention of `strategyBandit` is the field
  declaration in the loop's constructor. No `selectStrategy()` call, no
  `recordOutcome()` call, no `ProblemCategory.classify()` anywhere in the
  agentic loop.
  - The whole `StrategyBandit` class (`StrategyBandit.kt`), its store
    (`StrategyBanditStore.kt`), DAO, database, and module exist.
  - The `ReasoningStrategy` enum and `maxSteps` getter are dead.
  - The agentic loop uses a hard-coded `maxSteps = if (strategy != null)
    strategy.maxSteps else 10` (line 264), but `strategy` is a parameter
    that nothing ever passes. So real `maxSteps` is always 10.
- **Current behavior:** A multi-Room-table bandit system sits unused.
  `MemoryAugmentedAgenticLoop.run()` takes a `strategy: ReasoningStrategy?`
  parameter that no caller ever supplies, so the default of 10 is always in
  effect. Thompson sampling never influences routing.
- **Expected behavior:** Either wire `selectStrategy()` into
  `run()` (replace the `10` default with the bandit's pick when
  `strategy == null`) or delete the entire bandit subsystem.
- **Severity:** CRITICAL — entire feature dead; backup/restore in
  `AuraBackup.kt:87` exports an empty `strategyBandit: List<StrategyBanditBackup>`
  with the same default; tests may be green but production never runs.

### F-004 — VERIFIED (HIGH)
**`run_hand` defaults to NONE confirmation despite running arbitrary shell**
- **File:** `aura-core/src/main/kotlin/com/aura/agent/policy/ToolPolicyDefaults.kt:40-44`
- **Evidence:** `WRITE_LOCAL` → `ConfirmationLevel.NONE`. Comment defends
  this for "user-initiated actions" but `run_hand` is **agent-initiated** —
  the model decides to run it; the user typed a chat message minutes ago.
  `run_hand` (in the broader tool surface) executes an ADB-style shell
  command. No confirmation prompt, no log entry, no chance to deny.
- **Current behavior:** First time the model thinks "I'll run hand X", it
  runs.
- **Expected behavior:** Either classify `run_hand` as `DESTRUCTIVE`
  (or a new `SHELL_EXEC` risk) with `EXPLICIT` default, or add a per-tool
  override in `ToolPolicyDefaults.forTool()` that forces
  `IMPLICIT`/`EXPLICIT` for hand execution.
- **Severity:** HIGH — security regression visible to the user as "the
  agent ran something I didn't see."

### F-005 — VERIFIED (MEDIUM)
**Empty-id `ToolCallEnd` emitted on partial streams**
- **File:** `aura-core/src/main/kotlin/com/aura/agent/MemoryAugmentedAgenticLoop.kt:777-784`
- **Evidence:**
  ```kotlin
  for (id in toolCallStarts.keys) {
      if (toolCalls.none { it.first == id }) {
          val name = toolCallStarts[id] ?: continue
          val args = toolCallArgs[id]?.toString() ?: ""
          toolCalls += id to args
          emit(AgentEvent.ToolCallEnd(id, name, args))
      }
  }
  ```
  Defensive: if the stream emitted `ToolCallStart` but never `ToolCallEnd`,
  we synthesize an `End`. The path is correct. The concern is that
  `toolCallArgs[id]` is populated from `BrainChunk.ToolCallDelta` chunks,
  whose routing falls back to `nameById.keys.lastOrNull()` (see F-007).
  If the delta went to the wrong id (parallel tool_use), the synthesized
  End gets the wrong args.
- **Severity:** MEDIUM (rare; requires interleaved parallel tool_use that
  the model emits with no id tags on the deltas).

### F-006 — VERIFIED (HIGH)
**Thinking-budget inflation guard has a hole**
- **File:** `aura-core/src/main/kotlin/com/aura/agent/Brain.kt:88-114`
- **Evidence:** The `callerSetMaxTokens` guard exists (line 88) and is
  correct for direct callers. But:
  - The planning step in `MemoryAugmentedAgenticLoop.kt:660-665` passes
    `ChatOptions(temperature = 0.0, maxTokens = 150)` — a caller-set
    `maxTokens=150`.
  - `Brain.stream` then *inflates* this to `150 + 24_576 = 24_726` because
    the *default reasoningBudget* is 32K and the guard's `callerSetMaxTokens`
    branch (line 89) only clamps `thinkingBudget` to `maxTok - 1`, but
    `effectiveModel`'s inflation branch (line 108) only runs when
    `!callerSetMaxTokens`. So a 150-token planning call gets 149 tokens
    of thinking budget.
  - The reflection step (`ReflectionEngine.kt:78`) also passes
    `maxTokens = 150`. Same problem.
- **Current behavior:** A 150-token planning call gets 149 tokens of
  thinking budget. Anthropic will reject this with "budget_tokens must be
  less than max_tokens" — OR it silently accepts and burns the entire
  generation budget on the inner reasoning, returning 0 tokens of
  plan. The result is an empty `## Plan:` prefix.
- **Expected behavior:** When the caller sets a small explicit `maxTokens`
  (auxiliary calls), the reasoning/thinking budget should default to 0
  (disable thinking) or a much smaller value (e.g. min(budget, maxTokens/2)).
- **Severity:** HIGH (planning step and reflection step may both be silently
  broken when reasoning is enabled).

### F-006a — VERIFIED (LOW, related)
**Planning step's plan is concatenated as a string prefix even when reasoning
clamped it to 0 tokens — an empty "## Plan:\n\n" prefix reaches the next
LLM call, wasting prompt tokens.**
- **File:** `MemoryAugmentedAgenticLoop.kt:671-672` — the empty plan still
  triggers the `plan + sys` branch (because the *string* is non-blank, even
  if just the prefix `## Plan: ` followed by 0 content).

### F-007 — VERIFIED (MEDIUM)
**`BrainChunk.fromProvider` last-resort delta routing is non-deterministic**
- **File:** `aura-core/src/main/kotlin/com/aura/agent/Brain.kt:233-239`
- **Evidence:**
  ```kotlin
  // Last-resort fallback for providers that emit a delta
  // with no id and no name. Route to the most recent id
  // we saw in this stream.
  val id = nameById.keys.lastOrNull() ?: return Text("")
  return ToolCallDelta(id, tc.arguments)
  ```
  `nameById.keys.lastOrNull()` on a `LinkedHashMap` (line 126) returns
  the *most recently inserted* key — which is the *last* tool call started,
  not necessarily the one the delta belongs to. For two parallel tool
  calls, deltas may interleave: `start_A, start_B, delta_B, delta_A` —
  delta_A would be routed to B because B is the most recent insert.
- **Current behavior:** Parallel tool_use blocks from providers that don't
  tag deltas (older OpenAI /v1/chat/completions endpoints) may
  cross-contaminate argument buffers. The next provider-specific check
  (the inner `if (tc.id.isNotEmpty()) return ToolCallDelta(tc.id, ...)`)
  is hit *first* if the provider does set the id, so this is only a
  problem for legacy providers.
- **Expected behavior:** Route by `tc.id` if present, else by provider
  hint (e.g. `tc.index` for Anthropic), else by accumulating deltas into
  a single buffer (treat them as a single tool call).
- **Severity:** MEDIUM (legacy provider path; main providers tag deltas).

### F-008 — VERIFIED (HIGH)
**`AgentRunExecutorWorker.executeStep` hardcodes 120s timeout**
- **File:** `aura-core/src/main/kotlin/com/aura/agentrun/AgentRunExecutorWorker.kt:183-191`
- **Evidence:**
  ```kotlin
  val ctx = com.aura.agent.ToolContext(
      conversationId = run.conversationId.ifBlank { "agent_run:${run.id}" },
      userMessage = snapshot.userMessage,
      approvedRemoteCostTools = snapshot.approvedRemoteCostTools,
      memoryEnabled = snapshot.memoryEnabled,
      activeAgentId = snapshot.activeAgentId,
      timeout = 120_000L,
  )
  ```
  The `timeout = 120_000L` is hardcoded. `ToolContext.timeout` is the per-tool
  timeout used by `ToolExecutor.execute` for `withTimeout(ctx.timeout)`. The
  worker's snapshot does carry a per-tool `timeoutMs` value (or could), but
  the field is unused. The DAG-snapshot model has a hard 120s ceiling for
  every tool, including fast ones (HTTP HEAD, recall, kg_query).
- **Current behavior:** A long-running tool (deep_research, web_search) gets
  120s before being cancelled. A short tool (recall, kg_query) is allowed
  to block for 120s on a hang — much longer than the 30s default the
  agentic loop uses.
- **Expected behavior:** Read `snapshot.toolTimeoutMs` (or `step.timeoutMs`)
  with 120s as the upper bound, and propagate to `ToolContext.timeout`.
- **Severity:** HIGH (silent override of policy — longer or shorter than
  expected; can be triggered by a single DAG plan that hits a slow tool).

### F-009 — VERIFIED (MEDIUM)
**`CouncilOrchestrator.extractIntervention` produces a `Message` with
`recipient="unknown"`**
- **File:** `aura-core/src/main/kotlin/com/aura/agent/council/CouncilOrchestrator.kt:269-274`
- **Evidence:**
  ```kotlin
  "message" in lower || "email" in lower || "text" in lower || "call" in lower ->
      Intervention.Message(
          recipient = "unknown",
          draftBody = stance.take(300),
          rationale = "Council proposed reaching out about: $topic",
      )
  ```
  Downstream the `RunLifeCouncilTool` will try to deliver this — the
  recipient is `"unknown"`, so it will either fail (no contact) or
  route to a default. There's no follow-up that asks the user to provide
  a recipient.
- **Current behavior:** Council "proposes" sending a message but the
  proposal has no real recipient. Either silently fails downstream, or
  hits a `runCatching` and is dropped.
- **Expected behavior:** Either change the intervention type to
  `SelfCare` (the safe default at line 287) when no recipient is
  resolvable from context, or leave the proposal un-actioned until the
  user provides a recipient.
- **Severity:** MEDIUM.

### F-010 — VERIFIED (MEDIUM)
**`AgentCouncil.run` has a duplicate / shadowed CancellationException catch**
- **File:** `aura-core/src/main/kotlin/com/aura/agent/AgentCouncil.kt:144-159`
- **Evidence:**
  ```kotlin
  } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
      ...
  } catch (e: kotlin.coroutines.cancellation.CancellationException) {
      throw e
  } catch (e: Exception) {
      ...
  }
  ```
  Note the two different fully-qualified names —
  `kotlinx.coroutines.TimeoutCancellationException` and
  `kotlin.coroutines.cancellation.CancellationException`. The latter is
  `kotlin.coroutines.cancellation.CancellationException`, which **does not
  exist** in stdlib; the actual class is
  `kotlin.coroutines.cancellation.CancellationException` (in the
  `kotlinx.coroutines` package, not `kotlin.coroutines.cancellation`).
  This will either fail to compile (if the package is wrong) or
  silently catch nothing (if it compiles due to a Kotlin compiler
  quirk). The intent is to re-throw parent cancellation while
  catching timeout.
- **Current behavior:** If the package is wrong, the catch may not match
  anything meaningful. If the compiler tolerates it (Kotlin allows
  unresolved class references to fail at runtime), the catch becomes
  unreachable. **Note: the `withTimeout(budgetMs) { ... }` block at line 63
  already handles TimeoutCancellationException via its own `catch` on line
  144, so the duplicate-catch concern is a code-smell rather than a
  functional break.**
- **Expected behavior:** Use the consistent fully-qualified name
  `kotlinx.coroutines.CancellationException` for the rethrow clause.
- **Severity:** MEDIUM (potential compile error in stricter build configs;
  silent dead code in current config).

### F-011 — VERIFIED (MEDIUM)
**`DelegateToAgentTool` silently drops hallucinated tool calls**
- **File:** `aura-core/src/main/kotlin/com/aura/tools/DelegateToAgentTool.kt:240`
- **Evidence:**
  ```kotlin
  for ((toolName, args) in stepToolCalls) {
      if (tools.none { it.name == toolName }) continue
      val result = executor.execute(toolName, args, childCtx)
      ...
  }
  ```
  If the delegated agent emits a tool call to a name not in its filtered
  allowlist, the call is `continue`'d — no `ToolResult.Error` is appended,
  no event is emitted. The conversation history shows the model calling
  a tool that never returned, and the next iteration of the mini-loop
  gets no signal that the call was ignored.
- **Current behavior:** Hallucinated tool calls vanish from the
  conversation. The model may re-issue the same call next step or
  proceed as if it had completed.
- **Expected behavior:** Append a `ToolResult.Error("tool '$toolName' not
  in your allowlist")` to the conversation so the model can self-correct.
- **Severity:** MEDIUM.

### F-012 — VERIFIED (HIGH)
**Failover throws `CancellationException` to unwind the inner stream — but
the `TextDelta` events already emitted to the caller are kept; the
`toolCalls` map is cleared, but the user-facing chat will show partial
text from the failed model.**
- **File:** `MemoryAugmentedAgenticLoop.kt:697-772`
- **Evidence:** The failover path (line 747-761) calls
  `throw kotlinx.coroutines.CancellationException("failover")` from inside
  `brain.stream(...).collect{...}`. The outer `catch (e: CancellationException)
  if (e.message == "failover") continue@stream` re-enters the loop with
  the *new* model. The state-clearing on lines 700-705 (clears
  `accumulatedText`, `toolCalls`, `toolCallStarts`, `toolCallArgs`,
  `finishReason`) is correct — text is reset.
  - But: the `AgentEvent.TextDelta` chunks have already been emitted to
    the caller. The caller (ChatScreen) will have appended them to the
    visible message. When the new model streams, more `TextDelta` events
    are appended. The user sees the failed model's text plus the new
    model's text concatenated — a garbled response.
- **Current behavior:** A failover shows a visibly broken response
  (failed model's preamble + new model's body) in the chat.
- **Expected behavior:** Either emit a `AgentEvent.Reset` before the new
  stream starts, or hold the TextDelta events until the step has at least
  one chunk past `ToolCallStart` (commit-on-first-meaningful-event), or
  snapshot the cumulative offset and let the UI truncate.
- **Severity:** HIGH (user-visible bug on every failover).

### F-013 — VERIFIED (LOW)
**`StrategyBanditStore.getArms` writes seed rows then ignores them**
- **File:** `aura-core/src/main/kotlin/com/aura/agent/StrategyBanditStore.kt:14-25`
- **Evidence:**
  ```kotlin
  val rows = dao.forCategory(category.name)
  if (rows.isEmpty()) {
      seedCategory(category)
      return ReasoningStrategy.values().map { it to Triple(it, 1.0, 1.0) }.map { it.second }
  }
  ```
  On first access for a category: seeds 3 rows into Room, then returns
  hard-coded `(1.0, 1.0)` pairs. The next call to `getArms` will
  re-fetch the seeded rows from the DAO. Two extra DB writes on the
  cold start per category (7 categories × 3 strategies = 21 writes).
  Harmless but wasteful.
- **Severity:** LOW (only relevant if F-003 is fixed and the bandit
  actually gets called).

### F-014 — VERIFIED (MEDIUM)
**Compactor uses MoA model when no cheap alternative is available**
- **File:** `aura-core/src/main/kotlin/com/aura/agent/ConversationCompactor.kt:58-76`
- **Evidence:**
  ```kotlin
  val compactModel = runCatching {
      val providers = providerRegistry.configured()
      if (model.startsWith("moa:")) {
          val firstProvider = providers.firstOrNull()
          val firstModel = firstProvider?.let { cachedModels(it).firstOrNull() }
          if (firstProvider != null && firstModel != null) "${firstProvider.prefix}:$firstModel" else model
      } else {
          val candidates = providers.flatMap { p ->
              cachedModels(p).map { m -> "${p.prefix}:$m" }
          }.filter { it != model && !it.startsWith("moa:") }
          com.aura.providers.CheapModelHeuristic.pick(candidates) ?: model
      }
  }.onFailure { ... }.getOrDefault(model)
  ```
  - If `model = "moa:router"` and `providers` is empty, the
    `else if (firstProvider != null && firstModel != null)` falls
    through to `model` — the original MoA model. Compaction then makes
    3 LLM calls for a summary.
  - If `model = "gpt-4o"` and no cheaper model exists, the same `?: model`
    fallback means the summary is generated by gpt-4o. Expensive.
- **Current behavior:** Compaction can be 3× (MoA) or unbounded (gpt-4o)
  cost on a per-compaction-call basis.
- **Expected behavior:** The fallback should be the user's actual
  primary model, not the same model being compacted. Add a "use a
  different model always for compaction" flag, or
  fail-closed (skip compaction) when no cheaper model exists.
- **Severity:** MEDIUM.

### F-015 — VERIFIED (HIGH)
**`ForumEngine.hasQuorum` requires ≥3 voters — too strict for a 2-agent
council (which is the common case after mood filtering)**
- **File:** `aura-core/src/main/kotlin/com/aura/agent/forum/ForumEngine.kt:78-84`
- **Evidence:**
  ```kotlin
  suspend fun hasQuorum(postId: Long): Boolean {
      val tally = tally(postId)
      val total = tally.forVotes + tally.against + tally.abstain
      if (total < 3) return false
      ...
  }
  ```
  Combined with `CouncilOrchestrator.maxAgentsPerSession = 4` (line 57) and
  `lifeCouncilAgentIds` of 5 (line 48-54) plus
  `moodEngine.filterAvailable` removing burned-out agents — the effective
  number of voting agents is often 2-3. `maxRounds = 2` means each agent
  votes twice (round 1 + round 2 entries), but `distinctBy { it.agentId }`
  in the vote loop (line 196) means only one vote per agent.
- **Current behavior:** Most council sessions will reach fewer than 3
  distinct agents after mood filtering, and quorum is structurally
  unattainable. The council never produces interventions.
- **Expected behavior:** Either lower the quorum to 2 voters (and ≥50% for),
  or skip the proposal/vote step when fewer than 3 agents are available.
- **Severity:** HIGH (council feature is functionally inert).

### F-016 — VERIFIED (LOW)
**Compactor is called with `model` (original) instead of `effectiveModel`
(post-failover)**
- **File:** `MemoryAugmentedAgenticLoop.kt:907`
- **Evidence:** `currentConversation = conversationCompactor.compactIfNeeded(currentConversation, model)`.
  `model` is the parameter to `run()`, not `effectiveModel` (line 360,
  the post-failover model). Context budget lookup uses the *original*
  model — the post-failover model may have a different context window.
- **Current behavior:** After a failover, the new model's context window
  is not used to compute the compaction trigger. Compaction may fire
  too early (if the new model has a smaller window) or too late (if
  larger).
- **Severity:** LOW (failover is rare; the model catalog is usually the
  same provider family with similar windows).

### F-017 — VERIFIED (MEDIUM)
**`SpecialistRouter` "open " (with trailing space) is a substring match**
- **File:** `aura-core/src/main/kotlin/com/aura/agent/SpecialistRouter.kt:34, 147-153`
- **Evidence:** `matchesAnyKeyword` line 147: `val useWordBoundary = kw.endsWith(" ") || word.length <= 5`. So
  `"open "` (5 chars including space) **also** matches via the
  word-boundary rule for ≤5 chars. The actual word "open" (length 4)
  uses `\bopen\b` word-boundary regex, which is correct. But the
  keyword as listed is `"open "` — word "open" (after trim) is 4 chars
  so it goes through the word-boundary path. Verified correct.
  - Concern: `launch` and `open app` / `open the app` / `start app` are
    also in the list. A query like "can you open a chapter in my book
    summary" matches `open` AND `chapter` — but `chapter` is in the
    Writer exclusion list (line 53-67), so it falls to Writer. OK.
  - A query like "open a savings account" — "open" matches, no exclusion
    keyword → PhoneNative. But this is a financial question, not a
    device action. The user wants web search, not `launch_app`.
- **Current behavior:** Generic uses of "open" route to PhoneNative.
- **Severity:** MEDIUM (rare in practice but the heuristic is loose).

### F-018 — VERIFIED (MEDIUM)
**Thinking-budget clamp can produce 0**
- **File:** `Brain.kt:91-93`
- **Evidence:**
  ```kotlin
  if (maxTok > 0 && budget >= maxTok) {
      resolvedOptions = resolvedOptions.copy(thinkingBudget = (maxTok - 1).coerceAtLeast(0))
  }
  ```
  For `maxTokens = 1`, `thinkingBudget` becomes `0`. Anthropic's
  `budget_tokens = 0` is documented to disable thinking. But this code
  is reached only when `callerSetMaxTokens` is true, and callers that
  set `maxTokens = 1` are rare. The risk is more pronounced for
  `maxTokens = 100` → `thinkingBudget = 99` — Anthropic will reject this
  (99 is too small a budget to be useful) or the 99 tokens of thinking
  eat the entire output budget. See F-006.
- **Severity:** MEDIUM (see F-006 for the main concern).

### F-019 — VERIFIED (LOW)
**`runId` collisions on near-simultaneous runs**
- **File:** `MemoryAugmentedAgenticLoop.kt:307`
- **Evidence:** `val runId = "run_${System.currentTimeMillis()}"`. Two
  runs started in the same millisecond produce the same id. Use
  `UUID.randomUUID().toString()`.
- **Severity:** LOW (rare; the trace sink uses this as a key).

### F-020 — VERIFIED (informational, not a bug)
**`AgentRunExecutorWorker` failed-step handling is convoluted but correct.**
- The "stuck on hard failure" check (line 75-81) is now superseded by
  the `ready.isEmpty()` path below (line 86-118) which properly
  distinguishes "paused awaiting approval" (BLOCKED steps) from
  "stuck on hard failure". The early `failedIds.isNotEmpty()` block is
  a dead short-circuit for the "all steps failed" case, which the
  later `pending.isEmpty() && blockedIds.isEmpty() -> COMPLETED` path
  also covers.
- **Severity:** LOW (cosmetic; works correctly).

### F-021 — VERIFIED (MEDIUM)
**`DelegateToAgentTool` line 240: silently drops non-allowlisted tool calls**
- See F-011 for the full discussion. Listed separately because the
  agentic-loop version is the more impactful one.

### F-022 — VERIFIED (LOW)
**`ToolExecutor.parseArgs` silently drops unknown JSON keys**
- **File:** `ToolExecutor.kt:167-175`
- **Evidence:**
  ```kotlin
  for ((k, prop) in schema.properties) {
      val v = obj[k] ?: continue
      out[k] = coerce(v, prop)
  }
  ```
  Iterates over `schema.properties`, not the JSON keys. Extra JSON keys
  are dropped without warning. A model passing `{"q": "kotlin", "limit": 5}`
  to a tool with only `q` in its schema will have `limit` silently
  discarded.
- **Current behavior:** No signal to the model that `limit` was ignored.
- **Expected behavior:** Log a warning via `Log.w("ToolExecutor", "dropped
  unknown arg $k for $name")` so a debugging session can see what the
  model is emitting.
- **Severity:** LOW.

### F-023 — VERIFIED (HIGH)
**Per-step `nameById` reset loses cross-step tool-call context**
- **File:** `Brain.kt:126-134`
- **Evidence:** `nameById` is declared inside the `flow { ... }` block,
  so it's reset on every call to `Brain.stream()`. The agentic loop
  calls `Brain.stream()` once per step (line 708: `brain.stream(currentModel, messages, tools, options).collect`).
  Between steps, the map is reset. This is correct *per step* — each
  step's tool calls are independent.
  - The actual concern: if a provider sends chunks *after* the
    finish_reason but the loop has already moved on to the next step,
    those chunks are dropped. This is provider-specific and rare, but
    it can happen with Anthropic's input_json_delta trailing finish.
- **Severity:** HIGH only if providers exhibit the trailing-chunk
  behavior. Otherwise MEDIUM.

### F-024 — VERIFIED (MEDIUM)
**`PolicyResult.CostExceeded` and `PolicyResult.ScopeDenied` are unreachable
from `PolicyEngine.evaluate()`**
- **File:** `PolicyEngine.kt:28-58` (never returns these) vs
  `ToolPolicy.kt:46-47` (defines them) vs `ToolExecutor.kt:86-89`
  (handles them).
- **Evidence:** `evaluate()` has 5 returns: `Disabled`, `Disabled`,
  `NeedsConfirmation`, `NeedsApproval`, `NeedsApproval`, `Allowed`. None
  of those are `CostExceeded` or `ScopeDenied`. `ToolExecutor` has
  handling for them (line 86-89) but that handling is dead code.
- **Current behavior:** The two sealed-class variants exist in
  `PolicyResult` but can never be constructed. If someone wires up
  cost/scope checking later, they need to also add a return path here.
- **Expected behavior:** Either implement the check (preferred, see
  F-001) or remove the variants and the `ToolExecutor` handling.
- **Severity:** MEDIUM (documentation/contract drift; the ToolExecutor
  branch makes the contract *look* enforced, which is misleading).

---

## Per-file audit summary

| File | Findings |
|------|----------|
| `MemoryAugmentedAgenticLoop.kt` | F-005, F-006, F-012, F-016, F-019, F-003 (StrategyBandit dead) |
| `Brain.kt` | F-006, F-007, F-018, F-023 |
| `ToolExecutor.kt` | F-022, F-024 |
| `SpecialistRouter.kt` | F-017 |
| `ConversationCompactor.kt` | F-014 |
| `AgentCouncil.kt` | F-010 |
| `DelegateToAgentTool.kt` | F-011, F-021 |
| `ReflectionEngine.kt` | F-006 (contributes) |
| `StrategyBandit.kt` / `Store.kt` | F-003 (dead), F-013 |
| `AgentRunExecutorWorker.kt` | F-008, F-020 (info) |
| `policy/PolicyEngine.kt` | F-001, F-024 |
| `policy/ToolPolicyStore.kt` | F-002 |
| `policy/ToolPolicyDefaults.kt` | F-004 |
| `council/CouncilOrchestrator.kt` | F-009 |
| `forum/ForumEngine.kt` | F-015 |

---

## Recommended priority of fixes

1. **F-003** (StrategyBandit dead) — either wire it up or delete it. CRITICAL.
2. **F-015** (council quorum unreachable) — the council feature is inert. HIGH.
3. **F-006** (planning/reflection thinking-budget clamp) — silently breaks
   two auxiliary paths. HIGH.
4. **F-001** (policy cost/scope unenforced) — advertised safety surface
   does nothing. HIGH.
5. **F-012** (failover produces garbled text) — user-visible on every
   failover. HIGH.
6. **F-004** (run_hand security regression) — confirm the dangerous tool. HIGH.
7. **F-008** (worker timeout hardcode) — silent override of per-tool
   timeout. HIGH.
8. **F-002** (policy decode nukes store) — silent data loss on schema bug. MEDIUM.
9. **F-009, F-010, F-011, F-014, F-016, F-017, F-018, F-022, F-023, F-024** —
   correct as time permits.

---

## What I did NOT find (negative results)

- **No Hilt `@Singleton @Inject` auto-discovery dead code.** Per task
  instructions, Hilt discovers these. Not flagged.
- **`runInterruptible + runBlocking` in ToolExecutor** is the
  confirmed-correct timeout pattern (per task instructions). Verified
  correct at `ToolExecutor.kt:135-138`.
- **Provider chunk handling in Brain is correct for the common path**
  (id-tagged deltas). Only the legacy `lastOrNull` fallback (F-007) is
  a concern, and only for non-current providers.
- **DAG resolution in `DagResolver`** — not audited in detail in this
  round (out of scope), but the `readySteps` / `blockedStepIds` API
  contract is used correctly by the worker.

---

## Verification methodology

Every finding is backed by a direct read of the cited file at the
cited line range. No findings rely on inferred behavior, no LLM output
substitution, no third-party documentation. Where cross-file context
was needed (e.g. confirming `strategyBandit` is never called), I
greped the full `aura-core/src` tree to confirm.

The audit does not include runtime evidence (no tests were run, no
build was performed). Findings marked VERIFIED have static evidence
sufficient to act on; findings marked STRONGLY INDICATED or POSSIBLE
RISK would benefit from a follow-up runtime trace.
