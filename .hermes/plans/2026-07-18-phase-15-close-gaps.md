# Phase 15 Close-Gaps Plan — Make the Evolution System Actually Learn

**Status:** implementation-ready plan  
**Target repository:** `D:\aura-android-clean`  
**Target branch:** `feat/tier-1-friction`  
**Inference rule:** all LLM work uses only user-configured cloud providers via `ModelRole.EVOLUTION`; no hardcoded model IDs; no model runs on the phone  

---

## 1. Goal

Phase 15 left the evolution substrate in place but not yet *closing the loop*. This plan closes every structural gap so that Aura actually detects patterns, reflects on them with a cloud model, proposes mutations, and lets the user approve/reject/rollback each one.

Non-goals: training model weights, generating APK code, replacing the user's judgment, or running LLMs on-device.

---

## 2. Verified baseline

### 2.1 What already works

- `EvolutionDatabase` v2 with evidence, candidates, proposals, revisions, settings tables.
- Deterministic `EvolutionCandidateDetectors` for skill patch/promotion, memory consolidation, proactive dismissal/engagement.
- `EvolutionReflectionExecutor` + `EvolutionSubagentExecutor` wired to `ModelRole.EVOLUTION`.
- `EvolutionProposalStore` with proposal lifecycle, approval/rejection, apply saga skeleton, rollback.
- `EvolutionSafetyGuard` redacts credentials and blocks auto-apply for risky domains.
- `EvolutionMetrics` + recorder.
- `EvolutionWorker` scheduled by `EvolutionScheduler`.
- Compose UI: `SettingsEvolutionSection`, `EvolutionInboxScreen`, `BeliefsScreen`, `EvolutionRollbackScreen`, routes in `NavGraph`.
- Proactive persistence: `ProactiveEvents` writes `ProactiveEventEntity` rows to DB; `ProactiveInteractionEntity` table exists with `recordInteraction()` helper but **no callers**.
- Memory hooks: `MemoryStore` already calls `evolutionHooks.onMemoryStored/onMemoryRecalled/onMemoryForgotten`.
- Skill store accepts `evolutionHooks` and `skillRevisionStore` but **does not call hooks on invoke/add/update/remove**.
- `UseSkillTool` returns skill body but records no outcome.

### 2.2 What is missing

1. **No candidate → proposal promotion.** `EvolutionCoordinator.runAll()` stops after detectors.
2. **Apply saga only handles `CREATE_SKILL`.** All other `EvolutionAction` values return "not yet implemented".
3. **Skill hooks not emitting evidence.** `SkillsStore` has the dependency but `onSkillInvoked/onSkillFailed` never fire; `onSkillAdded/Updated/Removed` never fire.
4. **Proactive outcomes not recorded.** Notification taps, dismissals, snoozes, and Home-card dismissals never call `recordInteraction()`.
5. **No memory feedback UX.** Users cannot mark a memory as useful/wrong/noise; `MemoryFeedbackDao` is unused.
6. **No proposal detail / diff UI.** Inbox cards show title/summary only.
7. **No shadow baseline / A/B harness.** Metrics count events but do not compare old vs proposed behavior.
8. **No end-to-end proposal creation from chat.** Agent cannot see or act on candidates.
9. **No evolution onboarding / top-level entry.** Buried inside Settings.
10. **World model / beliefs not fed by agent runs.** `BeliefDao`/`EvidenceDao` exist but agent runs do not write evidence.
11. **Creative Council / production pipelines have no UI surface.** `com.aura.production` package does not exist; `ProductionPipelineScreen` is the only UI and is likely empty.
12. **Capability-backed tools not registered.** `CapabilityRouter`, `WebSearchCapabilityTool`, `ImageGenCapabilityTool` exist but are not in `ToolsModule`.

---

## 3. Implementation program — 28 atomic commits

Commit style: `feat(evolution): ...` for core, `feat(app): ...` for UI, `fix(...): ...` for bugs, `test(...): ...` for tests. Each commit must pass `:aura-core:testDebugUnitTest :app:compileDebugKotlin :app:assembleDebug` before the next.

### Commit 1 — Wire skill evidence hooks

**Goal:** every skill invocation, failure, add, update, and remove produces an `EvolutionEvidenceEntity` row.

**Files:**
- `aura-core/src/main/kotlin/com/aura/tools/UseSkillTool.kt`
- `aura-core/src/main/kotlin/com/aura/skills/SkillsStore.kt`

**Changes:**
1. `UseSkillTool`: inject `EvolutionHooks`. After `findByName`, if found call `evolutionHooks?.onSkillInvoked(skill.id, runId = null, conversationId = null, turnTimestamp = null)`. On `ToolResult.Error("skill_not_found", ...)` call `evolutionHooks?.onSkillFailed("_unknown_", "skill_not_found", ...)`. Pass run/turn IDs if available from `ToolExecuteContext`.
2. `SkillsStore`: after `_skills.value = ...` in `add/update/remove`, call `evolutionHooks?.onSkillAdded(skill.id)`, `onSkillUpdated(skill.id)`, `onSkillRemoved(skill.id)`. Also snapshot the previous skill in `update()` to `EvolutionSkillRevisionStore` before mutating.
3. Add unit tests in `UseSkillToolEvolutionHookTest.kt` and `SkillsStoreEvolutionHookTest.kt` verifying evidence rows are written.

**Verification:** `./gradlew :aura-core:testDebugUnitTest --tests 'com.aura.tools.UseSkillToolEvolutionHookTest' --tests 'com.aura.skills.SkillsStoreEvolutionHookTest'`

### Commit 2 — Wire proactive outcome evidence

**Goal:** every user reaction to a proactive nudge is recorded as `ProactiveInteractionEntity` + `EvolutionEvidenceEntity` so the proactive detectors have data.

**Files:**
- `aura-core/src/main/kotlin/com/aura/proactive/ProactiveEvents.kt`
- `app/src/main/kotlin/com/aura/ui/viewmodel/HomeViewModel.kt`
- `app/src/main/kotlin/com/aura/ui/viewmodel/ProactiveHistoryViewModel.kt`
- `app/src/main/kotlin/com/aura/ui/screens/ProactiveHistoryScreen.kt`

**Changes:**
1. Add `val id: Long` to `ProactiveEventBus.Event` sealed subclasses (or use `timestamp` + `stableKey` for events not yet persisted). Persist events in `ProactiveEvents` first, then emit to bus with the DB id.
2. Add `ProactiveEvents.recordInteraction(eventId, action, feedback)` callers:
   - `HomeViewModel.onDismissProactive(...)` → `action = "dismissed"`.
   - `ProactiveHistoryViewModel` expose `onEventAction(eventId, action)`.
   - `ProactiveHistoryScreen` card long-press menu: "This was helpful" / "Not helpful" / "Dismiss" → records `"acted"`, `"dismissed"`, `"feedback"`.
   - MorningBriefReceiver notification actions: "Tell me more" → `acted`, snooze → `snoozed`, dismiss → `dismissed`.
3. In `ProactiveEvents.recordInteraction`, also call `EvolutionHooks.onProactiveDelivered/onProactiveDismissed/onProactiveActionTaken` so evidence table receives the same signal.
4. Tests: `ProactiveOutcomeRecordingTest.kt` covering dismiss → interaction + evidence row.

**Verification:** `./gradlew :aura-core:testDebugUnitTest --tests 'com.aura.proactive.ProactiveOutcomeRecordingTest' :app:compileDebugKotlin`

### Commit 3 — Add memory feedback UI + evidence

**Goal:** user can mark a memory useful/wrong/noise; feedback feeds evolution evidence.

**Files:**
- `app/src/main/kotlin/com/aura/ui/screens/MemoryScreen.kt`
- `app/src/main/kotlin/com/aura/ui/viewmodel/MemoryViewModel.kt`
- `aura-core/src/main/kotlin/com/aura/evolution/EvolutionHooks.kt`
- `aura-core/src/main/kotlin/com/aura/memory/MemoryFeedbackDao.kt`

**Changes:**
1. Add `EvolutionHooks.onMemoryFeedback(memoryId: String, helpful: Boolean?, note: String?)`.
2. `MemoryViewModel`: add `sendFeedback(memoryId, helpful, note)` that writes `MemoryFeedbackEntity` and calls `evolutionHooks.onMemoryFeedback`.
3. `MemoryScreen`: add long-press menu or swipe action on memory row: "Useful" / "Not useful" / "Wrong" / "Add note".
4. Add detector rule: memory with ≥ 2 "not useful" feedback becomes candidate for `FORGET_MEMORY` or `UPDATE_MEMORY_CATEGORY`.
5. Tests: `MemoryFeedbackEvolutionTest.kt`.

**Verification:** `./gradlew :aura-core:testDebugUnitTest --tests 'com.aura.memory.MemoryFeedbackEvolutionTest' :app:compileDebugKotlin`

### Commit 4 — Implement reflection → proposal promotion

**Goal:** `EvolutionCoordinator` reflects on high-score candidates and turns them into real `EvolutionProposalEntity` rows.

**Files:**
- `aura-core/src/main/kotlin/com/aura/evolution/EvolutionCoordinator.kt`
- `aura-core/src/main/kotlin/com/aura/evolution/EvolutionReflectionExecutor.kt`
- `aura-core/src/main/kotlin/com/aura/evolution/EvolutionSubagentExecutor.kt`
- `aura-core/src/main/kotlin/com/aura/evolution/EvolutionProposalStore.kt`
- `aura-core/src/main/kotlin/com/aura/evolution/EvolutionCandidateDetectors.kt`
- `aura-core/src/main/kotlin/com/aura/evolution/EvolutionSettingsStore.kt`

**Changes:**
1. `EvolutionSettingsStore` exposes `evolutionMode: Flow<EvolutionMode>` (OFF/SHADOW/REVIEW_ALL) and per-domain enable flags.
2. `EvolutionCoordinator` constructor adds `proposalStore`, `reflectionExecutor`, `safetyGuard`, `settingsStore`, `memorySynthesizer`, `skillDetector`, `proactiveGenerator`.
3. `runAll()`:
   - If mode == OFF, return empty.
   - Run detectors.
   - Filter candidates by enabled domain and score ≥ 0.5.
   - For each candidate, build a reflection prompt using deterministic evidence summary.
   - Call `reflectionExecutor.reflect(prompt, maxTokens = 800, temperature = 0.2)`.
   - Parse JSON output into `ReflectionResult(action, title, summary, patchJson, confidence)`.
   - Validate with `safetyGuard.canPromote(candidate, result)`.
   - If valid, call `proposalStore.createProposalFromReflection(candidate, result)`.
   - Record metrics: `recordReflection`, `recordPromoted`, `recordRejected`.
4. Add `EvolutionReflectionExecutor.reflect(prompt)` returns raw text; add JSON parse helper in coordinator.
5. Add reflection prompt template v1 in `EvolutionReflectionExecutor` as constant.
6. Tests: `EvolutionCoordinatorPromotionTest.kt` using a fake reflection executor.

**Verification:** `./gradlew :aura-core:testDebugUnitTest --tests 'com.aura.evolution.EvolutionCoordinatorPromotionTest'`

### Commit 5 — Proposal detail / diff UI

**Goal:** user can open a proposal, see evidence list, diff, risk, rollback preview, and approve/reject.

**Files:**
- `app/src/main/kotlin/com/aura/ui/evolution/EvolutionProposalDetailScreen.kt` (new)
- `app/src/main/kotlin/com/aura/ui/evolution/EvolutionProposalDetailViewModel.kt` (new)
- `app/src/main/kotlin/com/aura/ui/evolution/EvolutionInboxScreen.kt`
- `app/src/main/kotlin/com/aura/ui/nav/NavGraph.kt`

**Changes:**
1. Add route `evolution/proposal/{proposalId}`.
2. `EvolutionProposalDetailViewModel`: loads proposal + related evidence + revision snapshot (if skill) + candidate source.
3. Compose screen:
   - Title, domain chip, action chip, status chip, score/confidence.
   - Rationale markdown.
   - Evidence list (kind + timestamp).
   - Diff preview for skill/memory changes.
   - Risk badge (auto-apply blocked vs allowed).
   - Approve / Reject / Rollback buttons.
4. `EvolutionInboxScreen`: make each card clickable to navigate to detail.
5. Tests: compile only for app ViewModels; add core test for `proposalStore.proposalWithEvidence(id)`.

**Verification:** `:app:compileDebugKotlin`

### Commit 6 — Implement remaining apply saga handlers

**Goal:** all `EvolutionAction` values have a real, reversible, tested handler.

**Files:**
- `aura-core/src/main/kotlin/com/aura/evolution/EvolutionApplySaga.kt`
- `aura-core/src/main/kotlin/com/aura/evolution/EvolutionSkillRevisionStore.kt`
- `aura-core/src/main/kotlin/com/aura/skills/SkillsStore.kt`
- `aura-core/src/main/kotlin/com/aura/memory/MemoryStore.kt`
- `aura-core/src/main/kotlin/com/aura/proactive/ProactivePolicyEngine.kt`
- `aura-core/src/main/kotlin/com/aura/world/BeliefDao.kt`
- `aura-core/src/main/kotlin/com/aura/world/EvidenceDao.kt`

**Handlers to implement:**
1. `PATCH_SKILL` — apply unified diff to skill body via line replacement; snapshot before.
2. `REWRITE_SKILL` — replace body; snapshot before.
3. `MERGE_SKILLS` — merge bodies with separator, retire source skill.
4. `RETIRE_SKILL` — remove skill from `SkillsStore` and mark revision as retired.
5. `PROMOTE_TO_HAND` — create `Hand` in `HandsRepository` with skill body as steps.
6. `PATCH_SPECIALIST_PROMPT` — update `UserPreferences.specialistOverrides` for a specialist.
7. `ADD_SKILL_EXAMPLE` — append example block to skill body.
8. `CONSOLIDATE_MEMORIES` / `CREATE_BELIEF` / `UPDATE_BELIEF` / `RETIRE_BELIEF` — use `EvolutionMemorySynthesizer` to write `BeliefEntity` + `EvidenceEntity`.
9. `FORGET_MEMORY` / `UPDATE_MEMORY_CATEGORY` / `MERGE_MEMORIES` — call `MemoryStore` delete/update.
10. `NEW_PROACTIVE_RULE` / `ADJUST_RULE_TIMING` / `DISABLE_RULE` / `ENABLE_RULE` / `REWRITE_RULE_MESSAGE` — mutate `ProactivePolicyEngine` policies.

Each handler:
- Creates a revision/snapshot before mutation.
- Returns `ApplyResult.Success(proposalId, revisionId)`.
- On failure, returns `ApplyResult.Error(proposalId, reason)` and proposal status becomes `APPLY_FAILED`.

Tests: `EvolutionApplySagaHandlersTest.kt` with in-memory fakes for all stores.

**Verification:** `./gradlew :aura-core:testDebugUnitTest --tests 'com.aura.evolution.EvolutionApplySagaHandlersTest'`

### Commit 7 — Rollback for all apply handlers

**Goal:** `EvolutionRollbackManager.rollback(proposalId)` can undo every implemented handler using the revision/snapshot captured before apply.

**Files:**
- `aura-core/src/main/kotlin/com/aura/evolution/EvolutionRollbackManager.kt`
- `aura-core/src/main/kotlin/com/aura/evolution/EvolutionRevisionDao.kt`
- `aura-core/src/main/kotlin/com/aura/evolution/EvolutionApplySaga.kt`

**Changes:**
1. Each apply handler stores enough context in `EvolutionApplicationEntity` (already exists) to reverse: previous skill ciphertext, memory id + old category, belief id, policy rule id.
2. `RollbackManager` reads the application row and dispatches to reverse handlers:
   - skill → restore from `EvolutionSkillRevisionStore` before-snapshot.
   - memory → restore old category or re-insert soft-deleted memory.
   - belief → mark retired or delete.
   - proactive → delete rule or restore old timing.
3. Tests: `EvolutionRollbackAllHandlersTest.kt`.

**Verification:** `./gradlew :aura-core:testDebugUnitTest --tests 'com.aura.evolution.EvolutionRollbackAllHandlersTest'`

### Commit 8 — Shadow mode and baseline metrics

**Goal:** when evolution mode is SHADOW, run reflection but do not create proposals; record what *would* have been proposed and compare to actual user behavior after 7 days.

**Files:**
- `aura-core/src/main/kotlin/com/aura/evolution/EvolutionMetricsRecorder.kt`
- `aura-core/src/main/kotlin/com/aura/evolution/EvolutionShadowRecord.kt` (new entity)
- `aura-core/src/main/kotlin/com/aura/evolution/EvolutionDaos.kt`
- `aura-core/src/main/kotlin/com/aura/evolution/EvolutionDatabase.kt`
- `aura-core/src/main/kotlin/com/aura/evolution/EvolutionCoordinator.kt`

**Changes:**
1. Add `EvolutionShadowRecordEntity` table: candidate id, proposed action, target, confidence, timestamp, wouldApply flag.
2. If mode == SHADOW, coordinator still runs reflection but writes shadow record instead of proposal.
3. `EvolutionMetricsRecorder` computes:
   - `shadowPrecision`: shadow proposals that later matched real user correction.
   - `userCorrectionRate`: evidence of corrections per domain.
   - `proposalAcceptRate`: approved / total proposals.
4. Add `EvolutionShadowMetricsTest.kt`.

**Verification:** `./gradlew :aura-core:testDebugUnitTest --tests 'com.aura.evolution.EvolutionShadowMetricsTest'`

### Commit 9 — Evolution onboarding + top-level entry

**Goal:** new/upgraded users discover evolution; top-level shortcut in Settings / Home.

**Files:**
- `app/src/main/kotlin/com/aura/ui/screens/home/HomeContent.kt`
- `app/src/main/kotlin/com/aura/ui/screens/home/HomeSecondaryActions.kt`
- `app/src/main/kotlin/com/aura/ui/nav/AuraBottomNavigation.kt` (do NOT add a 5th tab; use Home card)
- `app/src/main/kotlin/com/aura/ui/settings/SettingsEvolutionSection.kt`

**Changes:**
1. Home: after 7 active days and mode still SHADOW, show "Evolution summary ready" card that opens inbox.
2. Settings section: add explicit mode picker (OFF / SHADOW / REVIEW_ALL) with explanation; add "Review pending proposals" button; add "Run evolution now" button.
3. Do not add bottom-nav item; keep 4 tabs.
4. Tests: none (UI only, compile gate).

**Verification:** `:app:compileDebugKotlin`

### Commit 10 — Agent-facing evolution tools

**Goal:** agent can list/approve/reject proposals and run evolution sweep.

**Files:**
- `aura-core/src/main/kotlin/com/aura/evolution/EvolutionProposalTools.kt`
- `aura-core/src/main/kotlin/com/aura/tools/ToolsModule.kt`

**Changes:**
1. Replace stub tools with real implementations:
   - `list_evolution_proposals` — returns list from `proposalStore.openProposals()`.
   - `approve_evolution_proposal` — calls `proposalStore.approve(proposalId)`.
   - `reject_evolution_proposal` — calls `proposalStore.reject(proposalId)`.
   - `run_evolution_sweep` — calls `coordinator.runAll()`.
   - `explain_evolution_proposal` — returns proposal + evidence text.
2. All mutating tools report `ToolRisk.WRITE_LOCAL` and require approval.
3. Register tools in `ToolsModule` under `evolution` category.
4. Tests: `EvolutionProposalToolsTest.kt`.

**Verification:** `./gradlew :aura-core:testDebugUnitTest --tests 'com.aura.evolution.EvolutionProposalToolsTest'`

### Commit 11 — World model evidence feed from agent runs

**Goal:** durable agent runs produce `BeliefEntity`/`EvidenceEntity` rows for world-model evolution.

**Files:**
- `aura-core/src/main/kotlin/com/aura/agentrun/AgentRunStore.kt`
- `aura-core/src/main/kotlin/com/aura/world/EvidenceDao.kt`
- `aura-core/src/main/kotlin/com/aura/world/BeliefDao.kt`
- `aura-core/src/main/kotlin/com/aura/evolution/EvolutionHooks.kt`

**Changes:**
1. `EvolutionHooks.onAgentRunCompleted(runId, summary)` creates evidence row with domain MEMORY/ROUTING.
2. `AgentRunStore` calls hook when run reaches terminal state.
3. Add `BeliefEntity` creation helper: if run summary contains user-corrected facts, create belief + evidence.
4. Tests: `AgentRunWorldModelEvidenceTest.kt`.

**Verification:** `./gradlew :aura-core:testDebugUnitTest --tests 'com.aura.agentrun.AgentRunWorldModelEvidenceTest'`

### Commit 12 — Belief-driven retrieval integration

**Goal:** memories and beliefs are both considered during recall.

**Files:**
- `aura-core/src/main/kotlin/com/aura/memory/MemoryStore.kt`
- `aura-core/src/main/kotlin/com/aura/world/BeliefDao.kt`
- `aura-core/src/main/kotlin/com/aura/agent/MemoryAugmentedAgenticLoop.kt`

**Changes:**
1. Add `MemoryStore.recallWithBeliefs(query, scope, limit)` that fetches top memories + top beliefs for the same scope.
2. In `MemoryAugmentedAgenticLoop`, include belief summaries in system context when recalling.
3. Tests: `MemoryStoreBeliefIntegrationTest.kt`.

**Verification:** `./gradlew :aura-core:testDebugUnitTest --tests 'com.aura.memory.MemoryStoreBeliefIntegrationTest'`

### Commit 13 — Creative Council UI

**Goal:** user can start a council session from a creative project and see roles/proposals.

**Files:**
- `app/src/main/kotlin/com/aura/ui/screens/creative/CreativeProjectScreen.kt`
- `app/src/main/kotlin/com/aura/ui/screens/creative/CreativeCouncilScreen.kt` (new)
- `app/src/main/kotlin/com/aura/ui/viewmodel/CreativeCouncilViewModel.kt` (new)
- `app/src/main/kotlin/com/aura/ui/nav/NavGraph.kt`

**Changes:**
1. Add route `creative/{projectId}/council`.
2. `CreativeProjectScreen` add "Run Council" button.
3. `CreativeCouncilScreen`: show roles, progress, and final proposals; approve/reject each.
4. Tests: compile only.

**Verification:** `:app:compileDebugKotlin`

### Commit 14 — Production pipeline UI

**Goal:** the `production` route actually works end-to-end.

**Files:**
- Create package `com.aura.production` in `aura-core`:
  - `ProductionPipelineConfig.kt`
  - `ProductionPipelineRunner.kt` (uses existing `ProductionPipelineEngine`)
- `app/src/main/kotlin/com/aura/ui/screens/production/ProductionPipelineScreen.kt` (rewrite)
- `app/src/main/kotlin/com/aura/ui/viewmodel/ProductionViewModel.kt` (new)
- `aura-core/src/main/kotlin/com/aura/proactive/ProactiveModule.kt` or new `ProductionModule.kt` for DI.

**Changes:**
1. Expose `ProductionPipelineRunner` Hilt singleton.
2. Screen lists available pipeline templates (novel, screenplay, short film, trailer, podcast drama, RPG campaign), shows required inputs, runs pipeline, streams progress.
3. Register runner in DI.
4. Tests: `ProductionPipelineRunnerTest.kt`.

**Verification:** `./gradlew :aura-core:testDebugUnitTest --tests 'com.aura.production.ProductionPipelineRunnerTest' :app:compileDebugKotlin`

### Commit 15 — Register capability-backed tools

**Goal:** agent can call `web_search`, `image_gen`, and other capability-routed tools.

**Files:**
- `aura-core/src/main/kotlin/com/aura/capabilities/CapabilityRouter.kt`
- `aura-core/src/main/kotlin/com/aura/capabilities/WebSearchCapabilityTool.kt`
- `aura-core/src/main/kotlin/com/aura/capabilities/ImageGenCapabilityTool.kt`
- `aura-core/src/main/kotlin/com/aura/tools/ToolsModule.kt`

**Changes:**
1. Wrap each capability tool as a `Tool`.
2. Add to `ToolsModule` registration list.
3. `CapabilityRouter` resolves provider from configured providers, no hardcoded model.
4. Tests: `CapabilityToolsRegistrationTest.kt`.

**Verification:** `./gradlew :aura-core:testDebugUnitTest --tests 'com.aura.capabilities.CapabilityToolsRegistrationTest'`

### Commit 16 — MCP servers settings + tool discovery

**Goal:** user can add/view MCP servers; discovered tools appear in tool picker.

**Files:**
- `aura-core/src/main/kotlin/com/aura/mcp/McpClient.kt` (existing)
- `aura-core/src/main/kotlin/com/aura/mcp/McpServerConfig.kt` (new)
- `aura-core/src/main/kotlin/com/aura/mcp/McpServerDao.kt` (new, or add to existing DB)
- `app/src/main/kotlin/com/aura/ui/settings/SettingsMcpSection.kt` (new)
- `app/src/main/kotlin/com/aura/ui/screens/SettingsScreen.kt`
- `app/src/main/kotlin/com/aura/ui/tools/ToolsScreen.kt`

**Changes:**
1. Add `McpServerEntity` to `EvolutionDatabase` or a new `McpDatabase`.
2. `McpClient` can list tools from configured servers.
3. Settings section: add server URL + auth token; test connection; list discovered tools.
4. ToolsScreen shows MCP tools under separate section.
5. Tests: `McpServerConfigTest.kt`.

**Verification:** `./gradlew :aura-core:testDebugUnitTest --tests 'com.aura.mcp.McpServerConfigTest' :app:compileDebugKotlin`

### Commit 17 — Taste Twin / routing explanation UI

**Goal:** user can see why a model was chosen and edit taste profile.

**Files:**
- `app/src/main/kotlin/com/aura/ui/settings/SettingsTasteSection.kt` (new)
- `app/src/main/kotlin/com/aura/ui/screens/chat/ChatHeader.kt` or model picker
- `aura-core/src/main/kotlin/com/aura/taste/TasteEngine.kt`

**Changes:**
1. Expose `TasteEngine.styleProfileFlow()` and `lastRoutingExplanation()`.
2. Settings section shows profile dimensions (verbosity, creativity, code preference, etc.) with sliders.
3. Model picker or chat header shows a subtle "Why this model?" tooltip.
4. Tests: compile only.

**Verification:** `:app:compileDebugKotlin`

### Commit 18 — Proactive rule editor UI

**Goal:** user can create/edit/disable custom proactive rules.

**Files:**
- `app/src/main/kotlin/com/aura/ui/screens/proactive/ProactiveRuleEditorScreen.kt` (new)
- `app/src/main/kotlin/com/aura/ui/viewmodel/ProactiveRuleViewModel.kt` (new)
- `app/src/main/kotlin/com/aura/ui/screens/ProactiveHistoryScreen.kt`
- `aura-core/src/main/kotlin/com/aura/proactive/ProactivePolicyEngine.kt`

**Changes:**
1. Expose policy list from `ProactivePolicyEngine`.
2. Screen: list rules, toggle enable, edit timing/conditions, delete, add new rule with condition builder.
3. `ProactiveHistoryScreen` add "Rules" tab/button.
4. Tests: `ProactivePolicyEngineRuleCrudTest.kt`.

**Verification:** `./gradlew :aura-core:testDebugUnitTest --tests 'com.aura.proactive.ProactivePolicyEngineRuleCrudTest' :app:compileDebugKotlin`

### Commit 19 — Morning brief timing adaptation

**Goal:** brief time learns from when user dismisses/taps it.

**Files:**
- `aura-core/src/main/kotlin/com/aura/proactive/MorningBriefWorker.kt`
- `aura-core/src/main/kotlin/com/aura/proactive/ProactiveInteractionDao.kt`
- `aura-core/src/main/kotlin/com/aura/data/UserPreferences.kt`

**Changes:**
1. After 5 interactions, compute median engagement hour from `ProactiveInteractionEntity` for morning brief events.
2. If median differs from current `morningBriefHour` by ≥ 1 hour, create evolution candidate `ADJUST_RULE_TIMING`.
3. Do not auto-change; proposal goes to inbox.
4. Tests: `MorningBriefTimingAdaptationTest.kt`.

**Verification:** `./gradlew :aura-core:testDebugUnitTest --tests 'com.aura.proactive.MorningBriefTimingAdaptationTest'`

### Commit 20 — Skill retirement / merge UX

**Goal:** inbox can show skill merge/retire proposals with diff; user can approve.

**Files:**
- `app/src/main/kotlin/com/aura/ui/evolution/EvolutionProposalDetailScreen.kt`
- `aura-core/src/main/kotlin/com/aura/evolution/EvolutionApplySaga.kt`

**Changes:**
1. Detail screen renders "Merge A into B" and "Retire X" proposals with before/after.
2. Already implemented handlers from commit 6; ensure UI binds correctly.
3. Tests: none beyond existing.

**Verification:** `:app:compileDebugKotlin`

### Commit 21 — AgentRun checkpoint/resume UI

**Goal:** user can pause/resume durable runs.

**Files:**
- `app/src/main/kotlin/com/aura/ui/screens/agentrun/AgentRunsScreen.kt`
- `app/src/main/kotlin/com/aura/ui/viewmodel/AgentRunsViewModel.kt` (extract from screen)
- `aura-core/src/main/kotlin/com/aura/agentrun/AgentRunStore.kt`

**Changes:**
1. Refactor screen to use `AgentRunsViewModel`.
2. Add "Pause" / "Resume" / "Approve" buttons per run.
3. `AgentRunStore` exposes `pauseRun`, `resumeRun`, `approveStep`.
4. Tests: `AgentRunPauseResumeTest.kt`.

**Verification:** `./gradlew :aura-core:testDebugUnitTest --tests 'com.aura.agentrun.AgentRunPauseResumeTest' :app:compileDebugKotlin`

### Commit 22 — Unified search

**Goal:** one search surface for memories, skills, hands, tasks.

**Files:**
- `app/src/main/kotlin/com/aura/ui/screens/SearchScreen.kt` (new)
- `app/src/main/kotlin/com/aura/ui/viewmodel/SearchViewModel.kt` (new)
- `app/src/main/kotlin/com/aura/ui/nav/NavGraph.kt`
- `app/src/main/kotlin/com/aura/ui/nav/AuraBottomNavigation.kt` (optional: add search icon to top bar, not bottom nav)

**Changes:**
1. Add route `search`.
2. Search across `MemoryStore.searchByText`, `SkillsStore.skills`, `HandsRepository`, `TasksDao`.
3. Taps navigate to relevant screen.
4. Tests: compile only.

**Verification:** `:app:compileDebugKotlin`

### Commit 23 — Backup/restore for all new tables

**Goal:** evolution, taste, agent runs, proactive interactions, MCP servers are included in backup.

**Files:**
- `aura-core/src/main/kotlin/com/aura/backup/AuraBackup.kt`
- `aura-core/src/main/kotlin/com/aura/backup/BackupManager.kt`

**Changes:**
1. Bump backup schema version.
2. Add tables: evolution candidates/proposals/revisions/settings, agent run entities, proactive interactions, taste signals/profiles/reference identities/routing outcomes, MCP servers.
3. Update export and restore mappers.
4. Tests: `AuraBackupFullSchemaTest.kt`.

**Verification:** `./gradlew :aura-core:testDebugUnitTest --tests 'com.aura.backup.AuraBackupFullSchemaTest'`

### Commit 24 — Process-death and migration hardening

**Goal:** evolution worker survives process death; migrations are safe.

**Files:**
- `aura-core/src/main/kotlin/com/aura/evolution/EvolutionWorker.kt`
- `aura-core/src/main/kotlin/com/aura/evolution/EvolutionApplySaga.kt`
- `aura-core/src/main/kotlin/com/aura/evolution/EvolutionDatabase.kt`

**Changes:**
1. Worker sets `Result.retry()` on non-fatal failures.
2. Apply saga writes progress checkpoints to `EvolutionApplicationEntity`.
3. Add migration tests for Evolution DB v2 → v3 (new shadow table + settings columns).
4. Tests: `EvolutionProcessDeathTest.kt`.

**Verification:** `./gradlew :aura-core:testDebugUnitTest --tests 'com.aura.evolution.EvolutionProcessDeathTest'`

### Commit 25 — Prompt injection defense

**Goal:** reflection prompts cannot be hijacked by user content.

**Files:**
- `aura-core/src/main/kotlin/com/aura/evolution/EvolutionReflectionExecutor.kt`
- `aura-core/src/main/kotlin/com/aura/evolution/EvolutionSafetyGuard.kt`

**Changes:**
1. Delimit evidence snippets with XML tags and a clear instruction to only produce the requested JSON.
2. Validate reflection output is parseable JSON and keys are in allowlist; reject otherwise.
3. `SafetyGuard` strips `<?xml`, `<script`, `\u0000`, and credential patterns from evidence before building prompt.
4. Tests: `EvolutionPromptInjectionTest.kt`.

**Verification:** `./gradlew :aura-core:testDebugUnitTest --tests 'com.aura.evolution.EvolutionPromptInjectionTest'`

### Commit 26 — Visual verification + emulator screenshots

**Goal:** all new UI screens render correctly on device.

**Files:**
- All new `app/src/main/kotlin/com/aura/ui/*` screens.

**Steps:**
1. Build debug APK.
2. Launch emulator.
3. Screenshot: Home evolution card, Settings evolution section, Evolution Inbox, Proposal Detail, Beliefs, Proactive Rules, Production Pipelines, Creative Council, Search, AgentRun detail.
4. Fix any layout issues found.

**Verification:** manual visual review; APK path `releases/aura-debug-v0.20.0.apk`.

### Commit 27 — Full clean gate

**Goal:** all tests green, no regressions.

**Command:**
```bash
./gradlew clean :aura-core:testDebugUnitTest :app:compileDebugKotlin :app:assembleDebug --no-build-cache
```

**Verification:** exit 0.

### Commit 28 — Push + GitHub Release v0.20.0

**Commands:**
```bash
git add -f releases/aura-debug-v0.20.0.apk
git commit -m "build: debug APK v0.20.0"
git push aura-android feat/tier-1-friction
gh release create v0.20.0 --repo ElnurIbrahimov/aura-android --title "Aura Android v0.20.0" --notes "Phase 15 close-gaps: evolution loops actually learn, proactive outcomes, apply/rollback all handlers, production/creative UI, MCP, Taste Twin, unified search." --target feat/tier-1-friction releases/aura-debug-v0.20.0.apk
```

---

## 4. Effort estimate

- Core evolution loop closure: commits 1–12, ~18–24 engineering hours.
- Creative/production UI: commits 13–14, ~6–8 hours.
- Capability/MCP/Taste: commits 15–17, ~8–10 hours.
- Proactive rule/timing: commits 18–19, ~4–5 hours.
- AgentRun + unified search: commits 21–22, ~6–8 hours.
- Backup/hardening/prompt safety: commits 23–25, ~6–8 hours.
- Visual verification + release: commits 26–28, ~3–4 hours.

Total: **51–67 engineering hours** across 28 commits.

---

## 5. Definition of done

- [ ] `:aura-core:testDebugUnitTest` passes (baseline + all new tests).
- [ ] `:app:compileDebugKotlin` passes.
- [ ] `:app:assembleDebug` produces APK.
- [ ] At least one synthetic end-to-end flow runs: invoke skill 5 times → candidate detected → reflection → proposal in inbox → approve → skill updated → rollback works.
- [ ] Proactive dismiss/tap/snooze records evidence and produces candidates.
- [ ] Memory feedback produces candidates.
- [ ] All 15 routes have reachable UI.
- [ ] APK released on GitHub.

---

## 6. Anti-features (deliberate non-goals)

- No fine-tuning of model weights.
- No APK code generation by the evolution loop.
- No auto-apply of skill/memory/proactive mutations without approval in this release.
- No cloud-only providers without user configuration.
- No distribution/Play Store work.
