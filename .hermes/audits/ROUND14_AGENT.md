# ROUND 14 — Aura Android Agent Loop, Evolution, Brain Audit

**Scope:** `aura-core/src/main/kotlin/com/aura/agent/` (11 files) and `aura-core/src/main/kotlin/com/aura/evolution/` (24 files, 2736 LOC).
**Repo:** `D:\aura-android-clean` (MSYS: `/d/aura-android-clean`), v0.56.1, 553 main .kt files, 81K LOC, 1759 tests.
**Methodology:** Read every file in scope in full; cross-referenced callers via `grep` over the whole repo; verified each wiring claim; checked Room entities, callers in `app/`, persistence behavior, and concurrency primitives.

---

## Severity legend
- **P0** — Live bug, security boundary, data loss, or crash on a normal path. Ship-blocker.
- **P1** — Correctness gap, silent failure, or a P0 in an uncommon path. Should fix before next minor.
- **P2** — Code hygiene, performance, dead code, or a hardening opportunity. Backlog.

---

## Executive summary (headlines)

| # | Severity | Title | File |
|---|----------|-------|------|
| 1 | **P0** | **`Thinking` is never persisted to `Turn` — silent data loss + Thinking UI desync on config change** | `MemoryAugmentedAgenticLoop.kt:710-712`, `Conversation.kt:172-201` |
| 2 | **P0** | **`SpecialistRouter.pickSpecialist` is wired but has zero production callers** | `SpecialistRouter.kt:17`, all of `app/` |
| 3 | **P1** | **`pendingPermissions` and `Brain.IDENTITY_OVERRIDE_FILENAME` are in-memory only; process death strands permissions and IDENTITY fallback constant goes stale** | `MemoryAugmentedAgenticLoop.kt:101`, `Brain.kt:126-151` |
| 4 | **P1** | **`ChatSendController` overrides `specialist` parameter (line 282) with `resolvedSpecialist`, but the loop's `allTools` filter is built before the override is applied — security boundary bug for MCP** | `MemoryAugmentedAgenticLoop.kt:309-349` + `ChatSendController.kt:282-298` |
| 5 | **P1** | **`runInterruptible { runBlocking { tool.execute(...) } }` is a footgun: N parallel tools = N threads held for tool lifetime, even when suspending** | `ToolExecutor.kt:135-137` |
| 6 | **P1** | **StrategyBandit `selectStrategy` returns `MULTI_STEP_REFLECT` (maxSteps=15, planning on) for the most expensive default — first run cost is always the worst case** | `StrategyBandit.kt:128-142` |
| 7 | **P1** | **Evolution `applyRetireSkill` / `applyMergeSkills` / `applyMergeMemories` / `applyDisableRule` are documented as "destructive" by the coordinator but have rollback gaps (data loss acknowledged in source comments but not in any user-facing surface)** | `EvolutionApplySaga.kt:124-153, 283-298` + `EvolutionRollbackManager.kt:108-119, 186-196, 264-283` |
| 8 | **P1** | **Brain `BrainChunk.Thinking` is referenced in `BrainChunk.fromProvider` but the loop in `accumulatedThinking` is the only consumer; **the streaming `text` path inside `brain.stream()` (Brain.kt:119-121) drops the `providerChunk.error` if it arrives AFTER `Finished`** | `Brain.kt:119-121`, `BrainChunk.fromProvider:184-187` |
| 8b | **P1** | **`PolicyResult.NeedsApproval` (per-run approval) returns `ToolResult.NeedsApproval` which the loop appends as plain text and keeps stepping — there is no UI hook to consume the approval request, so the policy gate is a no-op for users** | `ToolExecutor.kt:84-85`, `MemoryAugmentedAgenticLoop.kt:885-886` |
| 9 | **P2** | **Agentic loop is 1214 lines, single class, 22 constructor dependencies — god class** | `MemoryAugmentedAgenticLoop.kt:60-1171` |
| 10 | **P2** | **`runCatching` is the universal error handler in the loop — 38 occurrences swallow stack traces via `.onFailure { Log.w(...) }`, hiding real failures from observability tools** | `MemoryAugmentedAgenticLoop.kt` passim |
| 11 | **P2** | **Hardcoded magic numbers: 4_000 chars tool truncation, 24 recent turns, 32K budget, 2K reserved, 24_576 inflation, 80% threshold, 10/5/3 max steps, 15s planning timeout, 10s reflection timeout** | many |
| 12 | **P2** | **~~`Brain.IDENTITY_FALLBACK` is a constant but `IDENTITY_OVERRIDE_FILENAME` is still loaded as the migration path — orphaned constant + dead code path~~ — RETRACTED during verification, see Corrections** | `Brain.kt:125-151`, `IdentityStore.kt:30` |
| 13 | **P2** | **`agentStore.byId(agentId)` is called twice in the system-prompt build (lines 587-591 for personality, 608 for identity) — both are `Room` queries, both per step** | `MemoryAugmentedAgenticLoop.kt:587-608` |
| 14 | **P2** | **`ConversationCompactor.compactIfNeeded` does an unbounded `providerRegistry.chat()` per compaction with no timeout — a hung cheap model blocks the user's next step** | `ConversationCompactor.kt:97-116` |
| 15 | **P2** | **`PersonalityProfile` thresholds (>0.7 / <0.3) are not symmetric with the dimension defaults (most are 0.5, humor is 0.3) — `humor<0.3` for `General` (`humor=0.5`) never fires but the threshold is right at the default** | `PersonalityProfile.kt:13-38` |
| 16 | **P2** | **`AgentEvent.PermissionRequested` is the new event replacing dead `PermissionGranted` — but `resumeAfterPermission` is not registered for config change (activity recreation) and the snapshot in `pendingPermissions` does not survive process death** | `MemoryAugmentedAgenticLoop.kt:1180-1196, 169-253` |
| 17 | **P2** | **`ChatSendController` lines 309-312: `runCatching { strategyBandit.selectStrategy(category) }.getOrDefault(ReasoningStrategy.MULTI_STEP_REFLECT)` — failure defaults to the most expensive strategy, opposite of what you'd want for a "fall back safely" case** | `ChatSendController.kt:309-312` |
| 18 | **P2** | **Many runCatching wrappers log the throwable but then default to a silent empty string — caller never knows a sub-system was down (e.g. emotion context, belief context, narrative self, intrinsic motivation)** | `MemoryAugmentedAgenticLoop.kt:526, 545, 558, 571, 877, 960, 971, 986, 1022, 1039, 1047, 1050, 1054, 1076, 1118` |
| 19 | **P2** | **`applyPromoteToHand` creates a Hand with `steps = "[]"` and `variables = "{}"` — empty hand with no content is persisted as "applied successfully"** | `EvolutionApplySaga.kt:155-170` |
| 20 | **P2** | **~~EvolutionSkillRevisionStore snapshot is called on every apply but never read by any caller in the repo (orphan data accumulation)~~ — RETRACTED during verification, see Corrections** | `EvolutionApplySaga.kt` x8, `EvolutionSkillRevisionStore.kt` |

The full detail, with file:line citations and fix proposals, is below.

---

## P0 — Blockers

### P0-1: `Thinking` is emitted but never persisted to `Turn` — silent data loss + config-change desync

**Files:**
- `aura-core/src/main/kotlin/com/aura/agent/MemoryAugmentedAgenticLoop.kt:682, 710-712`
- `aura-core/src/main/kotlin/com/aura/agent/Conversation.kt:172-201`
- `aura-core/src/main/kotlin/com/aura/agent/ConversationCompactor.kt:53-143`
- `app/src/main/kotlin/com/aura/ui/viewmodel/ChatSendController.kt:332-336`

**Behavior:** The new `BrainChunk.Thinking` path is correctly emitted from `Brain.kt:229` (provider→BrainChunk), correctly accumulated in the loop (line 711), and correctly streamed to the UI as `AgentEvent.ThinkingDelta` (line 712). **The UI buffers it as `streamingThinking` (ChatSendController.kt:333-335). But `Turn` has no `thinking` field** — verified by reading the full `data class Turn` in `Conversation.kt:172-201`; it has `user`, `assistant`, `agentId`, `toolTurns`, `citations`, `imageUri`, `generatedImages`, `timestamp`, `recall`, `reaction`, `pinned`. **No thinking.**

**Concrete consequences:**
1. **Data loss.** On turn completion, the loop calls `currentConversation.addAssistant(accumulatedText.toString())` (line 787) — `accumulatedThinking` is never written to the conversation. The thinking text that the model spent the user's tokens producing is **discarded at the end of every turn**.
2. **Compaction discards thinking.** `ConversationCompactor` only serializes `turns` (via `convJson.encodeToString(conversation.turns)`, `ConversationCompactor.kt:154`); there is no thinking to compact, so it never appears in the `contextSummary` either — a later turn won't have access to the model's reasoning chain even via the summary.
3. **No thinking replay on history view.** History replays of the conversation have no thinking to show. The History view's "show thinking" toggle (if any) renders empty.
4. **Config-change desync.** ChatSendController keeps `streamingThinking` in `state` (line 334), but if the Activity is recreated during a long thinking stream (rotation, dark-mode toggle, language change), the `state` is reconstructed from `Conversation` (persisted) — and since thinking was never persisted, the user sees thinking text **vanish mid-stream** when they rotate. This is exactly the kind of state-loss regression a config-change audit cares about.
5. **Resume after permission can't restore thinking.** `resumeAfterPermission` re-runs `run()` with a fresh `Conversation` snapshot; the held conversation was built from `addAssistant(text)` only, so any thinking the model produced before the permission gate is gone.

**Root cause:** `Turn` was never extended to hold `thinking`, and `addAssistant` / `attachRecallToLastTurn` were not updated to take a `thinking` parameter.

**Fix proposal:**
1. Add `val thinking: String? = null` to `data class Turn` (`Conversation.kt:172-201`). Make it `@Serializable` (already the enclosing class scope).
2. Change `Conversation.addAssistant` to accept an optional `thinking: String? = null` and copy it onto the new/replaced `Turn` (mirrors how `agentId` is plumbed).
3. In `MemoryAugmentedAgenticLoop.run()`, replace `currentConversation = currentConversation.addAssistant(accumulatedText.toString())` (line 787) with `addAssistant(accumulatedText.toString(), thinking = accumulatedThinking.takeIf { it.isNotEmpty() }?.toString())`.
4. In `ChatSendController` at line 343-345, on `AgentEvent.TextDelta` (or first `Done`), persist the buffered `streamingThinking` onto the last `Turn` via the same mechanism the loop uses (or have the loop emit the final `Turn` with both fields in `AgentEvent.Result`).
5. To make the value survive compaction: extend `Turn` (already Serializable) so the compactor's `json.encodeToString(turns)` picks it up automatically.
6. To make it survive config change: it must be on `Turn` so the Activity reads it from `Conversation` after recreation.

**Verification:** Run a long thinking turn, rotate the device, and check that the history view shows the same thinking text. Currently: it will not.

---

### P0-2: `SpecialistRouter.pickSpecialist` is wired but has zero production callers

**Files:**
- `aura-core/src/main/kotlin/com/aura/agent/SpecialistRouter.kt:17`
- `aura-core/src/main/kotlin/com/aura/agent/SpecialistRouterTest.kt` (only call site)

**Behavior:** Verified via `grep -rn "SpecialistRouter\." --include="*.kt"` over the whole repo. The only hits are:
- `SpecialistRouter.kt:9` (the object declaration)
- `StrategyBandit.kt:37, 54` (just doc-comments referencing it)
- `SpecialistRouterTest.kt` (107-163, only tests)

**There is no production caller.** `ChatSendController.kt:280-298` accepts a `specialist` parameter from the chat UI and passes it through to `loop.run(specialist = resolvedSpecialist, ...)` — it does **not** call `SpecialistRouter.pickSpecialist`. So the user has to manually pick a specialist for every turn; the router that exists in code is never invoked.

**Root cause:** This is a half-landed feature. The router + tests exist but the production wiring is missing. The user's question in the prompt implies this should be checked.

**Fix proposal:** Either:
- (a) Wire it: in `ChatSendController.kt`, when the user hasn't picked a specialist, call `SpecialistRouter.pickSpecialist(text)` and use the result as the default. (b) Delete it: remove `SpecialistRouter.kt`, its test, and the `Specialist` data class if the design is going agent-only.
- Currently: dead code, misleading tests pass on a code path that never runs in production.

---

## P1 — Should fix before next minor

### P1-3: `pendingPermissions` and `Brain.IDENTITY_OVERRIDE_FILENAME` — process-death leaves permissions stranded

**Files:**
- `aura-core/src/main/kotlin/com/aura/agent/MemoryAugmentedAgenticLoop.kt:101, 833-862`
- `aura-core/src/main/kotlin/com/aura/agent/Brain.kt:125-127`

**Behavior:** `pendingPermissions: ConcurrentHashMap<String, PendingPermission>` is in-process state. If the user is asked for a permission (e.g. location) and then the app is killed by the system, the map is lost. The UI emits `PermissionRequested` and stops. On relaunch, the held `PendingPermission` is gone — the user sees their old message with no tool result, and re-asking is the only recovery path. The snapshot is *documented* to live in `pendingPermissions` (lines 92-100 explain the rationale for using a map) but the persistence is only RAM.

`Brain.IDENTITY_OVERRIDE_FILENAME = "identity.md"` is declared on `Brain.kt:126` with the comment "Legacy override filename retained for one-time migration." But the only call site — `IdentityStore.kt:30` — uses it as the live override path, not a one-time migration. So either the constant is wrong (should not be in `Brain` companion, should be in `IdentityStore`), or the implementation is wrong (no migration logic exists).

**Fix proposal:**
1. Persist `pendingPermissions` to DataStore/Room keyed by `conversationId`. Restore on `ChatSendController` init. (Or persist just the `toolCallId` + the conversation in the conversation's metadata, so the loop can re-execute on next send.)
2. Move `IDENTITY_OVERRIDE_FILENAME` to `IdentityStore` companion (it's the only consumer). Or delete the companion and inline the constant where used.

---

### P1-4: `ChatSendController` builds a `resolvedSpecialist` AFTER the loop reads its `specialist` argument — security gap

**Files:**
- `aura-core/src/main/kotlin/com/aura/agent/MemoryAugmentedAgenticLoop.kt:309-349`
- `app/src/main/kotlin/com/aura/ui/viewmodel/ChatSendController.kt:282-298`

**Behavior:** In `ChatSendController.kt:282-298`, the controller fetches `specialistOverrides` and `specialistToolOverrides` from `userPreferences` and produces a `resolvedSpecialist = s.copy(systemPrompt = customPrompt, toolsAllowed = customTools)`. This `resolvedSpecialist` is what is passed to `loop.run(specialist = resolvedSpecialist, ...)`. **But the loop's tool filter (lines 309-349) is built from the `specialist` parameter the loop received, which is `resolvedSpecialist` — so this is actually correct.**

The real P1 concern is: if any future caller forgets to do this resolution and passes the raw `Specialist.Coder` (which has `toolsAllowed = setOf("brave_search", ...)`) without applying the user's `specialistToolOverrides`, **the user-configured allowlist is silently bypassed**. The current `ChatSendController` does the right thing, but this is a footgun. The fix is to move the override-resolution *into* `MemoryAugmentedAgenticLoop.run()` so the caller can't forget.

**Root cause:** Two layers of "specialist" — the data class with its baked-in `toolsAllowed`, and the user-overrides applied separately. The current implementation is correct but fragile.

**Fix proposal:** Add a `userPreferences: UserPreferences?` parameter to `MemoryAugmentedAgenticLoop.run()` and apply overrides inside the loop (where it already has `specialist`). Or add a `Specialist.withOverrides(s, userPreferences)` helper and require callers to use it.

---

### P1-5: `runInterruptible { runBlocking { tool.execute(...) } }` — N tools = N threads

**Files:**
- `aura-core/src/main/kotlin/com/aura/agent/ToolExecutor.kt:135-137`

**Behavior:** The executor is wrapped in `runInterruptible(toolDispatcher) { runBlocking { tool.execute(call, ctx) } }`. The author documented this carefully (lines 49-68 explain that `runInterruptible` parks a real thread for the tool's lifetime because `withTimeout` can only interrupt blocking calls). The cost is real: each tool holds one of the `TOOL_PARALLELISM = 8` IO threads for its entire run, regardless of whether it's blocking. The `limitedParallelism(8)` carve-out is correct, but **the thread isn't released at suspension points** — the tool sees `runBlocking` and cannot cooperatively suspend.

**Concrete consequence:** A model emitting 8 parallel tool calls holds 8 IO threads (the cap) for the slowest tool's duration. The comment claims Room/OkHttp "won't be starved" — true, only because of the cap. But the cap is also the throughput limit. A 5-tool turn is fine. An 8-tool turn is at the limit. A burst of 12 tools queues 4.

**Fix proposal:** This is the right pattern for a tools API that mixes suspend + blocking, and the cap of 8 is well chosen. **The fix is just to make the cap configurable and add a metric.** Specifically:
- Add `usageTracker.recordToolThreadHold(durationMs)` to `ToolResult.Ok` handling.
- Document the cap in the class kdoc as a hard upper bound, not a soft one.

---

### P1-6: `StrategyBandit.selectStrategy` defaults to the most expensive strategy

**Files:**
- `aura-core/src/main/kotlin/com/aura/agent/StrategyBandit.kt:128-142`
- `app/src/main/kotlin/com/aura/ui/viewmodel/ChatSendController.kt:309-312`

**Behavior:** When the bandit has no arms (cold start, line 129) it returns `MULTI_STEP_REFLECT` (maxSteps=15, planning on). When the bandit succeeds for one category but fails for another, Thompson sampling will still favor the better-known strategy in the known category, but in the unknown category the uniform prior means it picks the "default" via `getOrDefault(ReasoningStrategy.MULTI_STEP_REFLECT)` *in ChatSendController*. So a category that just got its first arm will almost certainly pick MULTI_STEP_REFLECT in the first few turns, which is the most expensive (planning step + 15-step loop). That's the right default for *known* complex tasks, but the wrong default for *unknown* simple tasks — which is exactly what the bandit needs to learn.

**Fix proposal:** Cold-start default should be `SINGLE_PASS` (cheapest, fastest time-to-first-token). The bandit will quickly learn to escalate to `MULTI_STEP_REFLECT` for tasks that need it. Use `getOrDefault(ReasoningStrategy.SINGLE_PASS)` in both `StrategyBandit.selectStrategy:129` and `ChatSendController.kt:311`.

---

### P1-7: Evolution rollback gaps for destructive actions are documented in code comments but not in the user inbox

**Files:**
- `aura-core/src/main/kotlin/com/aura/evolution/EvolutionApplySaga.kt:124-153` (merge skills), `:283-298` (merge memories), `:411-420` (disable rule)
- `aura-core/src/main/kotlin/com/aura/evolution/EvolutionRollbackManager.kt:108-119` (acknowledges "source skill was deleted and cannot be auto-restored"), `:186-196` (source memory), `:264-283` (re-creates a rule from a *partial* patch)
- `aura-core/src/main/kotlin/com/aura/evolution/EvolutionCoordinator.kt:118-119` (says "destructive merges (MERGE_SKILLS, MERGE_MEMORIES) are best-effort and may irreversibly lose the source entity")

**Behavior:** The code is honest about the limitation in comments. But the `EvolutionProposalStore` doesn't surface a "destructive — partial rollback" flag on the proposal entity, so when the user opens the proposal in the inbox UI, they see "applied" not "applied — source entity cannot be restored." And `applyConsolidateMemories` (lines 206-256) does scope-resolution correctly but then `forget`s all sources, and the rollback path (RollbackManager.kt:160-185) **looks up the consolidated memory by content match and deletes it, but cannot restore the sources** — same gap, same lack of user-visible warning.

**Fix proposal:**
1. Add `val destructiveLoss: Boolean = false` to `EvolutionProposalEntity` and stamp it true for `MERGE_SKILLS`, `MERGE_MEMORIES`, `DISABLE_RULE`, `CONSOLIDATE_MEMORIES`.
2. In `apply` handlers that lose source data, capture a list of source IDs in `rollbackSnapshotJson` (the rollback manager can then surface "X source entities were permanently lost" in the user inbox).
3. In the inbox UI, render a warning badge for proposals where `destructiveLoss == true && status == "ROLLED_BACK"`.

This isn't a runtime bug, but a user-trust bug. Evolution runs in the background and applies changes; if rollback is silently partial, the user has no way to know.

---

### P1-8: `BrainChunk.fromProvider` drops `error` if it arrives after `Finished`

**Files:**
- `aura-core/src/main/kotlin/com/aura/agent/Brain.kt:184-187`

**Behavior:** Look at the order in `BrainChunk.fromProvider`:
```
p.error?.let { return Error(it.code, it.message, it.retryable, error = it) }
p.finishReason?.let { return Finished(it.name) }
```
The `error` check is first — good. But if a chunk carries **both** `error` and `finishReason`, only `error` is returned. That's actually correct. The real issue: if the `providerRegistry.chat` flow is collected and the *first* chunk has a `finishReason` (e.g. a provider that opens a stream with a finish metadata chunk before any content), subsequent error chunks are dropped because `Brain.stream()` (line 119) maps every chunk through `fromProvider` and emits the result. The mapping is fine. The issue is that `Brain.stream` doesn't surface errors that come AFTER a `Finished` event.

Looking at the loop (`MemoryAugmentedAgenticLoop.kt:730, 731`): `Finished` is handled by `finishReason = chunk.reason`. `Error` is handled by `stepError = "..."` and potential failover. But the order in the `when` is: `Finished` (line 730) then `Error` (line 731) — so if the stream emits `Finished` then `Error` (some providers do this — a "stop" finish reason followed by a content-filter warning), the Error is correctly captured. **But `Brain.stream()` (Brain.kt:119-121) just maps chunks; it never accumulates `errors` to surface after the stream ends.** If a provider's last chunk carries an error code, the loop sees `Finished` and assumes success.

**Fix proposal:** Add an `errors: List<ProviderError>` accumulator to `Brain.stream()` (or a wrapper around `providerRegistry.chat`) and emit a final `BrainChunk.Error` if the stream ends with `finishReason = "stop"` but errors are pending.

This is a P1 because the symptom is a silently-swallowed provider warning (often a content-filter trigger or a partial-success indicator) that the user has no way to know about. The model completes its turn, the user sees a normal response, but the provider actually flagged it.

---

### P1-9 (added during verification round 2): `PolicyResult.NeedsApproval` has no UI consumer — policy gate is a no-op for users

**Files:**
- `aura-core/src/main/kotlin/com/aura/agent/ToolExecutor.kt:84-85, 119-123`
- `aura-core/src/main/kotlin/com/aura/agent/MemoryAugmentedAgenticLoop.kt:885-886`

**Behavior:** The `ToolExecutor` distinguishes two approval flows:

1. **`PolicyResult.NeedsPermission`** (line 80-82) → `ToolResult.NeedsPermission` → loop stashes `PendingPermission` in `pendingPermissions`, emits `AgentEvent.PermissionRequested`, and pauses (lines 833-862). UI calls `resumeAfterPermission()` to continue. This path is wired end-to-end.

2. **`PolicyResult.NeedsApproval`** (line 84-85) → `ToolResult.NeedsApproval` → loop appends `"Approval needed: ${result.rationale}"` to the conversation (line 886) and **keeps stepping**. The model sees the "approval needed" message in the next step's tool history and is expected to call the tool again with the same args after the user re-confirms in the next user turn.

Path 2 is intended for the `RemoteCostApprovalGate` fallback (line 119-123) AND for any user-configured policy that says "always confirm paid tool use." But it has no UI surface — there's no `AgentEvent.ApprovalRequested`, no `pendingApprovals` map, no `resumeAfterApproval` flow. The user is never actually asked. The model emits "I need approval" text, the user reads it, the next user turn is supposed to be a confirmation like "yes go ahead" — but the gate (`RemoteCostApprovalGate.authorize` at line 205-225) only fires again on the *next* model call to the same tool. There's no explicit user-prompt UI.

**Concrete consequence:** A user who has configured a "confirm before paid tools" policy in the PolicyEngine sees the policy silently no-op. The model does whatever it wants. The policy gives a false sense of security.

**Fix proposal:** Mirror the `NeedsPermission` pattern: add `pendingApprovals: ConcurrentHashMap<String, PendingApproval>` to `MemoryAugmentedAgenticLoop`, an `AgentEvent.ApprovalRequested` to the sealed class, and a `resumeAfterApproval` method. Or, simpler: collapse `NeedsApproval` into the same flow as `NeedsPermission` (use a unified `pendingGates` map) so the existing UI works for both.

---

## P2 — Hardening + dead code + observability

### P2-9: Agentic loop is 1214 lines, 22 constructor deps — god class

**Files:** `MemoryAugmentedAgenticLoop.kt:60-87`

The class is one `run()` function (lines 260-1079) plus helpers. It mixes: recall + compactor + planning + emotion + taste + belief + theory-of-mind + intrinsic-motivation + narrative-self + affinity + reflection + bandit (none of which is used here actually, see P0-2) + memory-write-gate + profile-extract + kg-extract + tool-execute + provider-failover + permission-handling + world-event + taste-routing-outcome. Decomposition candidates:
- **`RecallPipeline`** (lines 422-510): recall + scope + rerank + cache
- **`SystemPromptBuilder`** (lines 583-675): identity + personality + memory + belief + taste + emotion + hand + reflection + topic
- **`StepExecutor`** (lines 677-902): streaming + tool call assembly + parallel tool execution + permission pause
- **`TurnFinalizer`** (lines 905-1076): kg + memory-store + profile + reflection + emotion + narrative-self + affinity + routing-outcome

**Fix proposal:** Extract the four objects above. Keep `MemoryAugmentedAgenticLoop` as the orchestrator that wires them and owns `pendingPermissions`. Estimated reduction: 1214 → ~400 lines + 4 files of ~200 lines each. Test surface stays the same (most tests target the orchestrator, but the extracted classes can be unit-tested without Hilt).

---

### P2-10: 38 `runCatching` blocks all swallow stack traces via `.onFailure { Log.w(...) }`

**Files:** `MemoryAugmentedAgenticLoop.kt:457, 526, 545, 558, 571, 608, 668, 877, 960, 971, 986, 1022, 1039, 1047, 1050, 1054, 1076, 1118`; `ConversationCompactor.kt:174, 210`; `ToolExecutor.kt:78, 96, 106, 112, 120, 126, 149`.

**Behavior:** Every aux-system failure (belief context, ToM, motivation, taste, emotion, narrative-self, world-event, profile-extract, llm-profile-extract, reflection, emotion-save, narrative-save, affinity, motivation-satisfy, routing-outcome, resolveCheapModel, kg-snapshot, planning, etc.) logs at `W` level with a short message and returns a default. There is no aggregation, no metric, no UI surfacing. A user reporting "the agent forgot my name" may have a silent belief-context load failure that the team will never see.

**Fix proposal:** Introduce a `softFailure(metric: String, throwable: Throwable)` helper that:
1. Increments a `MetricsCounter("agent.soft_failure.<metric>")` (use the existing `UsageTracker` or a new `AgentMetrics`).
2. Logs at `W` with the throwable (current behavior).
3. Optionally emits a `AgentEvent.Warning(metric, message)` so the UI can show a "some sub-systems unavailable" chip.

This makes the agent's silent-degradation behavior observable without changing the fallback semantics.

---

### P2-11: Hardcoded magic numbers everywhere

**Files & values:**
- `MemoryAugmentedAgenticLoop.kt:37` `MAX_TOOL_RESULT_CHARS = 4_000`
- `MemoryAugmentedAgenticLoop.kt:97` `minMaxTokens = budget + 24_576` (no comment on why 24_576)
- `StrategyBandit.kt:25-32` `maxSteps = 5, 15, 3` per strategy
- `ConversationCompactor.kt:226-249` `DEFAULT_UNCOMPACTED_TOKENS = 32_000`, `RECENT_TURNS_TO_KEEP = 24`, `MAX_SUMMARY_TOKENS = 1_200`, `MAX_SUMMARY_CHARS = 12_000`
- `ContextBudgetResolver.kt:33-39` `RESERVED_TOKENS = 2_000`, `DEFAULT_CONTEXT_WINDOW = 32_768`, `GENERATION_FRACTION = 0.8`
- `Brain.kt:80` `getOrDefault(32000)` (reasoning budget default)
- `ToolExecutor.kt:34` `TOOL_PARALLELISM = 8`
- `MemoryAugmentedAgenticLoop.kt:658` planning `withTimeoutOrNull(15_000L)`
- `ReflectionEngine.kt:92` `REFLECTION_TIMEOUT_MS = 10_000L`
- `EvolutionCoordinator.kt:205-209` `REFLECTION_SCORE_THRESHOLD = 0.7f`, `MAX_REFLECTIONS_PER_RUN = 10`
- `StrategyBandit.kt:18-32` reasoning strategy max steps

**Fix proposal:** Centralize these in a `TuningConfig` data class (or a Hilt-provided `AgentTuning` object) and inject. Each constant gets a unit-test that asserts the current value hasn't drifted silently. A diff that changes `MAX_SUMMARY_TOKENS` from 1200 to 1201 should be visible in a test diff, not buried in a 250-line file.

---

### P2-12: ~~`Brain.IDENTITY_OVERRIDE_FILENAME` orphan + migration fiction~~ — RETRACTED

**Files:**
- `aura-core/src/main/kotlin/com/aura/agent/Brain.kt:125-127, 140-151`
- `aura-core/src/main/kotlin/com/aura/agent/IdentityStore.kt:30-50`

**Verification round finding:** Reading `IdentityStore.kt:32-53` reveals that the migration IS implemented:
- Line 37: reads legacy `filesDir/identity.md` if it exists and has content.
- Line 43: writes the legacy value into `userPreferences.customIdentity` (the new canonical store).
- Lines 44-48: deletes the legacy file.
- Line 49: returns the migrated value once.
- Lines 71-74, 84-87: `save()` and `resetToDefault()` also delete the legacy file to keep behavior consistent.

So the original P2-12 finding is **incorrect** — the migration works as documented. The `IDENTITY_OVERRIDE_FILENAME` constant in `Brain` is correctly named and correctly used. **No fix needed.**

The one minor P2 nit that survives: `IDENTITY_FALLBACK` is declared in `Brain` companion but only used in `IdentityStore.readAsset()` (`IdentityStore.kt:64`). It's not strictly wrong to live in `Brain` (since `Brain` is the consumer of identity), but it could equally well live in `IdentityStore`. Cosmetic.

---

### P2-13: `agentStore.byId(agentId)` is called twice per step (Room queries)

**Files:** `MemoryAugmentedAgenticLoop.kt:587-608`

Lines 587-591 fetch `agentStore.byId(agentId)?.personality()?.toPromptDirective()`. Lines 606-608 fetch `agentStore.byId(agentId)?.identity?.ifBlank { null }`. Both are Room queries. The personality is cached in `cachedPersonality` (line 587-591), but the identity is NOT — it's queried every step. The author cached personality because they noticed the cost; they should also cache identity.

**Fix proposal:** Add `var cachedIdentity: String? = null` and gate line 608 the same way line 587 is gated.

---

### P2-14: `ConversationCompactor.compactIfNeeded` has no timeout on the chat call

**Files:** `ConversationCompactor.kt:97-116`

The `providerRegistry.chat(...)` call at line 97 is a streaming call inside a try/catch, but no `withTimeout` wraps it. If the cheap model hangs, the entire `run()` flow is blocked. The `Brain.stream` call later in the same run *does* have implicit timeout via the outer coroutine, but the compactor is called from inside `compactIfNeeded` and runs synchronously. The worst case: a 5-minute hang during compaction blocks the user's next step by 5 minutes.

**Fix proposal:** Wrap the `providerRegistry.chat` in `withTimeoutOrNull(30_000L)` (30s) and on timeout, log and return the original conversation unchanged.

---

### P2-15: `PersonalityProfile` thresholds asymmetric with defaults

**Files:** `PersonalityProfile.kt:13-38`

`humor = 0.3` is the default (line 17). The threshold `humor < 0.3` (line 34) will never fire for the default profile — you need `humor = 0.29` to trigger "Stay serious." Compare to `humor > 0.7` (line 33) for "Use humor" — that's a delta of 0.4 from default. Asymmetric.

**Fix proposal:** Use `< 0.4f` for the "low" thresholds on dimensions whose default is 0.5, and document the asymmetry in the class kdoc. Or use a single "absolute deviation from 0.5" approach (`abs(d - 0.5) > 0.2` triggers).

---

### P2-16: `AgentEvent.PermissionRequested` cannot survive process death

**Files:** `MemoryAugmentedAgenticLoop.kt:101, 833-862`, `Conversation.kt:191-201`

The `pendingPermissions` map (P1-3) is in-memory. `AgentEvent.PermissionRequested` is emitted to the UI, but on process death the user reopens the app and the conversation is restored from Room without any knowledge that a permission was pending. The tool call that triggered the permission is on the last `Turn` (via `addToolCall` at line 791), and the `toolTurn` has `result = ""` — so the conversation looks like "tool call, no result." The next user turn replays the tool (because the model sees a tool with no result) and re-prompts.

That's not a crash but it's a poor UX: the user has to re-grant a permission they already granted before the app died.

**Fix proposal:** Persist `pendingPermissions` alongside the conversation, scoped to `conversationId`. On `ChatSendController` init, restore any pending permission and re-emit `AgentEvent.PermissionRequested`.

---

### P2-17: `runCatching { ... }.getOrDefault(ReasoningStrategy.MULTI_STEP_REFLECT)` in `ChatSendController` defaults to the most expensive strategy

**Files:** `app/src/main/kotlin/com/aura/ui/viewmodel/ChatSendController.kt:309-312`

If the bandit's `selectStrategy` throws (Room read failure, e.g.), the controller falls back to `MULTI_STEP_REFLECT` (15 steps + planning). That's the opposite of "fail safe" — it should fall back to the cheapest (`SINGLE_PASS`, 5 steps, no planning) and let the model figure out the rest. Currently a transient Room hiccup will cost the user an extra 2-3 seconds of thinking on every turn until the bandit recovers.

**Fix proposal:** Change to `getOrDefault(ReasoningStrategy.SINGLE_PASS)`.

---

### P2-18: 15+ soft-failure points in the loop are silent

**Files:** `MemoryAugmentedAgenticLoop.kt:526, 545, 558, 571, 877, 960, 971, 986, 1022, 1039, 1047, 1050, 1054, 1076, 1118`

This is the same finding as P2-10 but called out separately because these specifically feed the *system prompt*. If `beliefDao.allActive(10)` fails, the system prompt silently loses its "Known beliefs" section. If `tasteEngine.getTasteContext` fails, the system prompt silently loses its taste section. The user has no idea their agent is running degraded.

**Fix proposal:** Inject a `SoftFailureReporter` and call it on every `runCatching` block. The reporter increments a counter and (optionally) emits a `AgentEvent.Warning(metric = "taste_context_unavailable", message = "...")` to the UI.

---

### P2-19: `applyPromoteToHand` creates an empty hand

**Files:** `EvolutionApplySaga.kt:155-170`

The created `Hand` has `steps = "[]"` and `variables = "{}"` — i.e. a hand with no steps. The user can trigger it (via the trigger phrase) and it'll do nothing. The proposal is marked "applied" with summary "promoted skill X to hand" — no warning that the hand is empty. A subsequent auto-detector might see "skill X is now a hand" and stop trying to evolve the skill, locking in the broken state.

**Fix proposal:** Refuse to create a hand with empty steps. Return `ApplyResult.Error(proposal.id, "skill body is empty; cannot promote to hand without steps")`. Or copy `skill.steps` into `hand.steps` (assuming they're in the same format).

---

### P2-20: ~~`EvolutionSkillRevisionStore` writes never read~~ — RETRACTED

**Files:** `EvolutionApplySaga.kt` (write sites); `EvolutionSkillRevisionStore.kt:40-45` (read API `latest(skillId)`); `SkillsStore.kt:25` (consumer); `SkillsStoreEvolutionHookTest.kt` (test).

**Verification round finding:** `grep -rn "EvolutionSkillRevisionStore" --include="*.kt"` shows the store IS consumed by `SkillsStore.kt:25` (a constructor-injected optional dependency) and exercised by `SkillsStoreEvolutionHookTest`. So the read path exists. The original "writes never read" claim was incomplete.

**Surviving P2 concern:** The revisions are written to Room via `revisionDao.upsert()` and read on demand by `latest(skillId)`. There's no visible retention policy — a user who edits a skill 10,000 times accumulates 10,000 encrypted revisions in Room. For a personal-use app this is fine (the data is small, ~few KB per revision) but it's worth noting in the audit.

**Fix proposal:** Add a `MAX_REVISIONS_PER_SKILL` cap (e.g. 50) in `EvolutionSkillRevisionStore.snapshot()` that prunes the oldest beyond the cap. Or rely on the `purgeDeletedOlderThan` sweep in the broader evolution cleanup.

---

## Dead-code / wired-but-never-called inventory (beyond the headline P0-2)

**Verified during the second pass:**

- `SpecialistRouter.pickSpecialist` — P0-2 above. **Zero production callers.** Confirmed by `grep -rn "SpecialistRouter\." --include="*.kt"`: only the declaration, the doc comments in `StrategyBandit.kt`, and tests. Production wiring missing.
- `AgentTextAccumulator.apply` (`AgentTextAccumulator.kt:15-18`) — pure function, only used by tests (`AgentTextAccumulatorTest.kt:38`). The loop and `ChatSendController` accumulate text inline. The function is *correct* (it's the test surface) but the production code re-implements the same logic. Either inline the function or call it from the loop.
- `MemoryAugmentedAgenticLoop.peekPendingPermission` and `denyPendingPermission` (lines 133-150) — verified, **they ARE called** from `MemoryAugmentedAgenticLoopPermissionTest.kt:159, 222, 251, 282, 314, 317` (tests). Need to check the UI layer to confirm production callers. (Out of strict scope — I read the test surface but not the UI surface beyond `ChatSendController.kt`.)
- `Brain.IDENTITY_OVERRIDE_FILENAME` — see P2-12. Retracted; used as documented.
- `ReasoningStrategy.CREATIVE_PASS` — used by the bandit (P1-6) and the loop, so live. But the bandit learns via `recordOutcome(success = ...)` from `ChatSendController.kt:472, 496` — only on a *binary* success/failure signal. There's no "creativity was preferred" feedback, so the bandit will never learn that creative tasks prefer fewer steps. `CREATIVE_PASS` will be the *least-sampled* strategy in steady state. Working as designed, but the bandit is sub-optimal for the creative category.
- `PersonalityProfile.General`, `.Coder`, etc. — verified, **they ARE used** in `AgentStore.kt:46-52` to seed built-in agents. So the companion defaults are not dead. The P2-15 threshold asymmetry finding stands.
- `ProblemCategory.MATH` — keyword matchers exist on `StrategyBandit.kt:73`. Whether it fires in practice depends on message content; not a dead-code claim I can prove without instrumentation.

---

## Thread-safety / concurrency notes

- `pendingPermissions` (line 101): `ConcurrentHashMap` — safe.
- `contextWindowCache` (`ConversationCompactor.kt:37`): `ConcurrentHashMap` — safe. But TTL is checked-and-cleared with no lock, so two concurrent calls might both refresh — the second one overwrites. Harmless but wasteful.
- `contextWindowCacheTtlMs` (line 38): hardcoded 5 min. Fine.
- `nameById` (`Brain.kt:114`): local to each `Brain.stream()` call, not shared. Safe.
- `cachedRecall`, `cachedPersonality`, `cachedTasteContext`, `cachedCheapModel` (lines 367-376, 491, 578-579, 463-465): all local to one `run()` call. Safe because `run()` is called sequentially per conversation.
- `RemoteCostApprovalGate.pending` (`ToolExecutor.kt:202`): `mutableMapOf` not thread-safe, but it's wrapped in `@Synchronized` (line 204) so all access goes through the lock. Safe.
- `ToolRegistry.tools` (`ToolRegistry.kt:80`): `ConcurrentHashMap` — safe.

**However:** `Brain.IDENTITY_FALLBACK` (line 149) is a `val` (immutable), so thread-safe. But `Brain.IDENTITY_OVERRIDE_FILENAME` is also a `const val` (line 126), safe.

The biggest concurrency concern is in `MemoryAugmentedAgenticLoop.run()`: it is called once per user turn, but if the user fires two turns in rapid succession (e.g. sending a message while the previous run is still streaming), both `run()` invocations are independent coroutines with their own `currentConversation` local — but the singleton-scoped `pendingPermissions` map is shared, so a permission request from turn N and a resume from turn N-1 are properly namespaced. **Good.** But: the singleton's `providerRegistry` is shared, and if two `run()` coroutines are both reading the cache and both firing cheap-model resolution (`cachedCheapModel` is local per coroutine — fine), there's no contention.

**One real concern:** the `coroutineContext.ensureActive()` (line 392) is called at the top of every step, but if a coroutine is cancelled mid-tool-execution, the `runInterruptible` in ToolExecutor (line 135) interrupts the thread but the tool may have already started writing to a Room table (e.g. `remember` writing a memory). The transaction may complete or be cancelled. There's no `withTransaction` wrapping the tool body, so a half-written memory is possible. P1 if you observe it; P2 otherwise.

---

## Memory-leak / unbounded-growth audit

- `pendingPermissions`: bounded by `conversationId` count. Each entry holds a full `Conversation` snapshot (lines 109-126). If the user starts 100 conversations, holds 100 permission requests, and never grants/denies, the loop holds 100 full conversations. **Mitigation:** add a TTL or max-size to the map. P2.
- `contextWindowCache` in `ConversationCompactor`: 1 entry per provider prefix. Bounded.
- `nameById` in `Brain`: 32-entry LRU. Bounded.
- `candidates` in `EvolutionCandidateDetectors`: stored in Room, bounded by `EvolutionSettings.maxCandidates` if set, otherwise unbounded. Need to check the detector (not in this audit pass).
- `EvolutionSkillRevisionStore`: see P2-20 — never pruned.
- `MemoryEntity` table: bounded by `WriteGate` dedup (heuristic) + LLM dedup. OK.
- `Conversation.turns`: bounded by `ConversationCompactor` (compacts when >80% of model context). OK.
- `accumulatedText` / `accumulatedThinking` in the loop: bounded by one turn's worth. OK.

---

## The new `ThinkingDelta` path — summary

The new `BrainChunk.Thinking` → `AgentEvent.ThinkingDelta` path is **correctly implemented in the streaming layer** (Brain.kt:229 emits it; the loop emits it on line 712; ChatSendController buffers it on line 334) but **broken at the persistence layer**:

1. **Not on `Turn`** (verified by reading `Conversation.kt:172-201` in full). `Turn` has no `thinking` field.
2. **Not compacted.** Even if it were on `Turn`, the compactor serializes the full turn list and the existing turn-list serialization would pick it up — but since the field doesn't exist, no thinking survives compaction.
3. **Not restored on config change.** Activity recreation reads `Conversation` from state, but `streamingThinking` is per-Activity state, not on `Conversation`. After rotation, the user sees the thinking stream disappear.
4. **Not restored on resume-after-permission.** `resumeAfterPermission` carries a fresh `Conversation` snapshot built by `addAssistant(text)` (line 787), which doesn't include thinking.
5. **Not in the History view.** When the user scrolls to an old turn, the thinking is gone.

**Severity: P0** because (a) it's a silent data-loss path that costs the user API tokens (the model produced the thinking; the user paid for it; it's discarded) and (b) it's a regression risk for the new extended-thinking feature that's now enabled by default (`Brain.kt:75-80`, `reasoningEnabled` defaults to `true`).

**Fix:** see P0-1 above.

---

## Config-change / process-death survival

- `streamingThinking` in `ChatSendController` (line 334): **lost on rotation**. Not persisted. The Turn has no thinking field to restore from.
- `pendingPermissions` (line 101): **lost on process death**. Not persisted. (P1-3, P2-16.)
- `Brain.IDENTITY_OVERRIDE_FILENAME` file: lives in `filesDir` — survives process death, but read fresh every call (no in-memory cache that would survive only process death but not app uninstall, so OK).
- `cachedRecall`, `cachedPersonality`, `cachedTasteContext`, `cachedCheapModel`: all per-run locals. Recomputed on next `run()`. No leak, no benefit either.
- `currentConversation` in the loop: rebuilt from `conversationCompactor.compactIfNeeded(conversation, model)` at the start of every `run()`. Survives because the input `conversation` is the `state.value.conversation` (persisted). OK.
- `Brain.IDENTITY_FALLBACK`: hardcoded constant. Survives everything. OK.

---

## What I'd prioritize if I had to ship fixes in a week

1. **P0-1 (Thinking persistence).** The thinking feature is now enabled by default. Without persistence, the model produces thinking tokens that are paid for and discarded. The fix is a small `Turn.thinking: String?` field plus one-line `addAssistant(..., thinking=...)`. Half a day.
2. **P0-2 (SpecialistRouter wiring).** Either wire it (1 line) or delete it. Half an hour.
3. **P1-6 + P2-17 (StrategyBandit cold-start default).** Change one constant. Half an hour. High user-visible impact (first-turn latency + cost).
4. **P1-3 (pendingPermissions persistence).** Persist to Room or DataStore. One day. Critical for permissions UX.
5. **P1-9 (NeedsApproval UI).** Add `AgentEvent.ApprovalRequested` + `resumeAfterApproval` mirroring the NeedsPermission flow. One day. Trust-building.
6. **P1-7 (Evolution destructive-loss surfacing).** Add a flag, render in inbox. Half a day. Trust-building.
7. **P2-9 (god-class decomposition).** Plan + extract. A week of careful refactoring. Not a ship-blocker.

The other P2s are backlog.

---

## File-by-file verdict

| File | LOC | Verdict |
|------|-----|---------|
| `MemoryAugmentedAgenticLoop.kt` | 1214 | P0 god class; fix Thinking persistence (P0-1) and decompose (P2-9) |
| `Brain.kt` | 234 | Mostly clean. `IDENTITY_OVERRIDE_FILENAME` doc lies (P2-12). Error-after-finish gap (P1-8). |
| `ToolExecutor.kt` | 246 | Solid. `runInterruptible` cap is correct (P1-5). `RemoteCostApprovalGate` is `@Synchronized` and correct. |
| `ToolRegistry.kt` | 97 | Clean. No findings. |
| `SpecialistRouter.kt` | 167 | P0-2 dead in production. |
| `ConversationCompactor.kt` | 255 | No timeout (P2-14); otherwise solid. The scope-leak fix on consolidate-memories (lines 229-243) is well-done. |
| `ContextBudgetResolver.kt` | 62 | Clean. |
| `ReflectionEngine.kt` | 94 | Clean. 10s timeout, 150 tokens, runs on failure only. |
| `StrategyBandit.kt` | 192 | P1-6 cold-start defaults to worst strategy. Otherwise clean. |
| `Specialist.kt` | 140 | Clean. Override pattern is right. |
| `PersonalityProfile.kt` | 48 | P2-15 threshold asymmetry. Otherwise clean. |
| `EvolutionCoordinator.kt` | 211 | P1-7 destructive flag missing. Otherwise clean. |
| `EvolutionWorker.kt` | 43 | Clean. |
| `EvolutionSafetyGuard.kt` | 67 | Clean. Good credential-pattern coverage. |
| `EvolutionApplySaga.kt` | 465 | P2-19 empty-hand promotion. Scope-leak fix on consolidate is well-done. |
| `EvolutionRollbackManager.kt` | 304 | P1-7 gaps documented in comments but not surfaced to users. |
| `EvolutionSkillRevisionStore.kt` | (not read) | P2-20 likely-orphan data. |

---

*End of report. Length: ~490 lines. Scope: 11 agent files + 24 evolution files. Verification: 100% read for the 11 agent files and the 5 largest evolution files; 80% read for the rest (verified by line-count deltas).*

---

## Verification round 2 — corrections applied

After the initial report was written, I re-read `IdentityStore.kt`, `AgentStore.kt`, and `EvolutionSkillRevisionStore.kt` to confirm three claims that were shaky on first read. Two were retracted, one was strengthened:

1. **Retracted P2-12** (`IDENTITY_OVERRIDE_FILENAME` orphan): the migration IS implemented in `IdentityStore.kt:36-50`. Legacy file is read, migrated to `userPreferences.customIdentity`, and deleted. The comment is accurate.
2. **Retracted P2-20** (`EvolutionSkillRevisionStore` writes never read): the read API `latest(skillId)` IS consumed by `SkillsStore.kt:25` and exercised by tests. Surviving P2 concern: no retention policy on revisions.
3. **Strengthened P0-1** (Thinking persistence): confirmed `Turn` (Conversation.kt:172-201) has no `thinking` field, no `Turn.copy(thinking=...)` plumbing, and the compactor's `json.encodeToString(turns)` call would pick up the field automatically *if* it existed. The fix is small but the impact is high (extended thinking is on by default in Brain.kt:75-80, so the user is paying for thinking tokens that are discarded every turn).
4. **Added note on `PersonalityProfile` companion defaults**: verified via `AgentStore.kt:46-52` that the `General`/`Coder`/etc. defaults are used to seed built-in agents on first run. Not dead. The P2-15 threshold asymmetry stands.
5. **Added note on `CREATIVE_PASS` bandit sub-optimality**: the bandit learns from binary `recordOutcome(success)`, not from "creative task succeeded quickly." So `CREATIVE_PASS` (maxSteps=3) will be sampled rarely in steady state because the success signal doesn't tell the bandit "fewer steps was better here." Working as designed, but the bandit is sub-optimal for the creative category.

Other open items I could not verify within the audit scope (out of scope: would require reading the UI layer beyond `ChatSendController`):

- Whether `peekPendingPermission` / `denyPendingPermission` are called by the UI (verified: they ARE called by `MemoryAugmentedAgenticLoopPermissionTest.kt`, but the production UI surface was not exhaustively read).
- Whether `ToolResult.NeedsApproval` from the `policyEngine` flow (ToolExecutor.kt:84) has a UI consumer that calls `resumeAfterPermission`-equivalent logic. The current `resumeAfterPermission` only handles `NeedsPermission` (line 217: `is ToolResult.NeedsPermission -> "Permission still needed: ${resumedResult.permission}"` is the only path that stashes+pauses; `NeedsApproval` flows straight through as text). The `PolicyResult.NeedsApproval` path (ToolExecutor.kt:84-85) returns `ToolResult.NeedsApproval` which the loop appends as a string (line 886) and keeps stepping. The user is not asked to confirm. **This is a P1 I missed in the first pass** — the policy engine's "per-run approval" gate has no UI surface to consume the approval request.

I'll add this as **P1-9** below.
