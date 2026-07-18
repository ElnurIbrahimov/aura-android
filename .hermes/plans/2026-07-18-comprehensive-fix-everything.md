# Aura Android — Comprehensive Fix-Everything Plan

> **For Hermes:** Use subagent-driven-development skill to implement this plan task-by-task. Run pre-execution verification per item (grep before patching). The plan was written from the 2026-07-18 deep wiring audit at `.hermes/audits/2026-07-18-deep-wiring-audit.md` — every finding has been verified against live source at HEAD `5fdb9a72`.

**Goal:** Fix all 22 audit findings + 18 subagent-confirmed items so v0.24.0 is a trustworthy release with green tests, working navigation, closed approval loops, correct migrations, honest feature surfaces, and release integrity.

**Architecture:** Phases ordered by dependency: P0 safety → test suite → navigation → tool executor → providers → AgentRun/Hands → evolution → world/taste → backup → security → release integrity → dead code. Each phase is independently shippable. Each commit passes the full Gradle gate.

**Tech Stack:** Kotlin 1.9.24, AGP 8.2.2, Compose BOM 2024.10.01, Hilt 2.51, Room 2.6.1, WorkManager, DataStore, mockk, Turbine

**Branch:** `feat/tier-1-friction`
**Working dir:** `D:\aura-android-clean`
**Gradle gate:** `./gradlew --no-daemon :aura-core:testDebugUnitTest :app:testDebugUnitTest :app:assembleDebug :app:lintDebug`

---

## Pre-Audit: What Exists vs What's Needed

| Component | Status | Evidence |
|-----------|--------|----------|
| PolicyEngine + ToolPolicyStore | EXISTS, UNWIRED | `aura-core/.../agent/policy/PolicyEngine.kt` — zero consumers outside itself; ToolExecutor has inline gates |
| EvolutionDatabase v3 | EXISTS, MIGRATION BROKEN | `EvolutionModule.kt:27` — `arrayOf(MIGRATION_2_3)` only; `MIGRATION_1_2` defined at lines 51-56 but not passed |
| ProactiveEvents bus loop | EXISTS, BUG | `ProactiveEvents.kt:109-123` — collects bus.events, inserts, re-emits on same bus |
| ToolContext.permissions | EXISTS, NEVER POPULATED | `MemoryAugmentedAgenticLoop.kt:307-311` — constructs ToolContext without permissions |
| REMOTE_COST approval gate | EXISTS, NO CLOSED LOOP | `ToolExecutor.kt:66-71` — rejects if not in approvedRemoteCostTools; set never populated |
| CreativeCouncil model resolution | EXISTS, BROKEN | `CreativeStudioViewModel.kt:190-203` — returns bare prefix or "default"; parse() requires provider:model |
| ProductionPipeline engine | EXISTS, WRONG TOOL ARGS | `ProductionPipelineEngine` wraps all stages as `creative_engine`; media stages fail |
| AuraBottomNavigation "Evolve" | EXISTS, DEAD ROUTE | `AuraBottomNavigation.kt:69` — route "evolution"; NavGraph has no composable("evolution") |
| BackupManager | EXISTS, GAPS | 18 of 24 MemoryDB entities have no backup; ProactiveEventBackup drops correlationTag; 12 prefs keys missing |
| MemoryDatabase schema exports | PARTIAL | v1-6, 11-13 present; v7-10 missing |
| McpToolBridge | EXISTS, WORKING | Confirmed by subagent — tools are registered |
| EvolutionApplySaga | EXISTS, 13/19 UNIMPLEMENTED | Returns "not yet implemented" for 13 actions |
| ToolExecutor runBlocking pattern | EXISTS, SUBOPTIMAL | `runInterruptible(Dispatchers.IO) { runBlocking { ... } }` — blocks IO thread |
| SMTP password storage | EXISTS, INSECURE | `UserPreferences.KEY_SMTP_PASSWORD` in plain DataStore |

---

## Phase 0: P0 Safety — Stop the Infinite Loop (1 commit)

### Task 0.1: Fix ProactiveEvents infinite re-emission

**Objective:** Stop the insert→emit→insert feedback loop that causes unbounded Room growth and CPU drain.

**Files:**
- Modify: `aura-core/src/main/kotlin/com/aura/proactive/ProactiveEvents.kt:109-123`
- Test: `aura-core/src/test/kotlin/com/aura/proactive/ProactiveEventsTest.kt`

**Approach:**
The collector at line 109 collects `bus.events`, inserts each event to DB, then calls `bus.tryEmit(event.withId(insertedId))` on the **same bus**. The collector re-receives the re-emitted event.

Fix: use a **separate internal channel** for persistence, or mark persisted events with a `persisted: Boolean` flag and skip re-emission for events that already have an ID. The simplest fix: check `event.id != 0L` (already persisted) and skip the insert+re-emit for those.

```kotlin
// ProactiveEvents.kt — fix the collector
bus.events.collect { event ->
    if (event.id != 0L) return@collect  // already persisted, skip
    val id = dao.insert(event.toEntity())
    bus.tryEmit(event.withId(id))  // emit with DB id for UI consumers
}
```

Alternative (cleaner): split into two flows — `persistFlow` (inserts to DB) and `notifyFlow` (emits to UI). The bus should NOT re-emit what it collected.

**Test:** Write a test that inserts 1 event, asserts exactly 1 DB row (not infinite), and completes within 2 seconds.

**Verification:** `./gradlew --no-daemon :aura-core:testDebugUnitTest --tests 'com.aura.proactive.ProactiveEventsTest'`

**Commit:** `fix(proactive): stop infinite event re-emission loop`

---

## Phase 1: P0 Safety — Fix System Insets (1 commit)

### Task 1.1: Fix onboarding CTA and bottom navigation clipped by system insets

**Objective:** Onboarding primary button and bottom nav tabs must be fully visible and tappable on API 26-35.

**Files:**
- Modify: `app/src/main/kotlin/com/aura/MainActivity.kt` (edge-to-edge setup)
- Modify: `app/src/main/kotlin/com/aura/ui/nav/NavGraph.kt:83-111` (Scaffold insets)
- Modify: `app/src/main/kotlin/com/aura/ui/nav/AuraBottomNavigation.kt:87-153` (inset handling)
- Modify: `app/src/main/kotlin/com/aura/ui/screens/onboarding/OnboardingContent.kt` (CTA padding)
- Test: `app/src/androidTest/kotlin/com/aura/ui/nav/BottomNavigationTest.kt` (already exists — verify it passes)

**Approach:**
1. `MainActivity` calls `enableEdgeToEdge()` — correct. The issue is the Scaffold/content not consuming `WindowInsets.navigationBars` / `WindowInsets.systemBars` properly.
2. `AuraBottomNavigation` receives `navigationBarInsets` parameter but may not be applying it as bottom padding to the nav bar itself. The nav bar height is `AuraDimensions.bottomNavigationHeight` — if this doesn't include the inset, the tabs sit under the gesture nav bar.
3. Fix: add `.padding(bottom = navigationBarInsets.asPaddingValues().calculateBottomPadding())` to the nav Surface, or use `WindowInsets.navigationBars.fill` pattern.
4. Onboarding CTA: add `WindowInsets.navigationBars` bottom padding to the button container.
5. Verify on emulator with `adb shell wm overscan set 0,0,0,0` and check bounds via UI dump.

**Verification:** Install on emulator, dump UI, verify onboarding button and nav tab bounds are within `[0, 2000]` not `[0, 2072]`. Run `BottomNavigationTest`.

**Commit:** `fix(ui): correct system insets for onboarding CTA and bottom navigation`

---

## Phase 2: P0 Safety — Fix Permission and REMOTE_COST Approval Loops (3 commits)

### Task 2.1: Wire granted permissions into ToolContext

**Objective:** When the user grants a permission, the retry call includes it in ToolContext so the tool can actually execute.

**Files:**
- Modify: `aura-core/src/main/kotlin/com/aura/agent/MemoryAugmentedAgenticLoop.kt:307-311`
- Modify: `app/src/main/kotlin/com/aura/ui/viewmodel/ChatViewModel.kt:799-835`
- Modify: `app/src/main/kotlin/com/aura/ui/screens/chat/ChatSendController.kt:242-275`
- Test: `aura-core/src/test/kotlin/com/aura/agent/ToolExecutorPermissionTest.kt`

**Approach:**
1. `ChatViewModel.retryAfterPermission(permission)` already calls `sendController.runSend()` — but the send path constructs `ToolContext` with no permissions.
2. Add `pendingPermissions: Set<String>` to the send controller's next-call context.
3. In `MemoryAugmentedAgenticLoop.runStep()`, read pending permissions from a field set by the send controller, and include them in `ToolContext.permissions`.
4. After the tool executes successfully (or fails with a different permission), clear the pending set.
5. `AgentEvent.PermissionGranted` (currently dead) should be emitted when the loop detects a permission was granted, so `ChatSendController` can resume.

**Test:** Mock a tool that requires `android.permission.READ_CALENDAR`. First call returns `NeedsPermission`. Set permission. Retry call passes permission in context. Tool executes. Assert `ToolResult.Success`.

**Verification:** `./gradlew --no-daemon :aura-core:testDebugUnitTest --tests 'com.aura.agent.ToolExecutorPermissionTest'`

**Commit:** `fix(tools): wire granted permissions into ToolContext for retry`

### Task 2.2: Add REMOTE_COST approval event and UI path

**Objective:** REMOTE_COST tools can be approved through the chat UI, and the approval reaches ToolExecutor.

**Files:**
- Modify: `aura-core/src/main/kotlin/com/aura/agent/MemoryAugmentedAgenticLoop.kt` (add AgentEvent.CostApprovalNeeded)
- Modify: `aura-core/src/main/kotlin/com/aura/agent/ToolExecutor.kt:66-71` (emit event instead of silent rejection)
- Modify: `app/src/main/kotlin/com/aura/ui/screens/chat/ChatSendController.kt` (handle CostApprovalNeeded)
- Modify: `app/src/main/kotlin/com/aura/ui/viewmodel/ChatViewModel.kt` (add `approveRemoteCost()`)
- Modify: `app/src/main/kotlin/com/aura/ui/screens/chat/ChatDialogs.kt` (add CostApprovalDialog)
- Test: `aura-core/src/test/kotlin/com/aura/agent/RemoteCostApprovalTest.kt`

**Approach:**
1. Add `AgentEvent.CostApprovalNeeded(toolName: String, estimatedCost: String, runId: String)` to the sealed class.
2. ToolExecutor: when a REMOTE_COST tool is not in `approvedRemoteCostTools`, emit `CostApprovalNeeded` instead of returning `NeedsPermission`. The loop pauses.
3. ChatSendController: handle the new event by showing a dialog: "This action uses paid API credits. Approve?"
4. User approves → `ChatViewModel.approveRemoteCost(toolName)` → adds to a per-conversation approved set → re-engages model.
5. The approved set is passed in the next `ToolContext.approvedRemoteCostTools`.
6. Direct UI paths (image gen, transcription) that call `tool.execute()` directly must also go through a cost check — either pre-approve (UI action implies consent) or show a dialog first.

**Test:** Mock a REMOTE_COST tool. First call emits `CostApprovalNeeded`. User approves. Second call includes tool in approved set. Tool executes.

**Verification:** `./gradlew --no-daemon :aura-core:testDebugUnitTest --tests 'com.aura.agent.RemoteCostApprovalTest'`

**Commit:** `fix(tools): add REMOTE_COST approval event and UI dialog loop`

### Task 2.3: Fix ToolExecutor runBlocking thread pattern

**Objective:** Replace `runInterruptible(Dispatchers.IO) { runBlocking { ... } }` with a suspend call that doesn't block the IO thread.

**Files:**
- Modify: `aura-core/src/main/kotlin/com/aura/agent/ToolExecutor.kt:78-80`
- Test: existing tests should still pass

**Approach:**
Replace:
```kotlin
runInterruptible(Dispatchers.IO) {
    runBlocking { tool.execute(call, ctx) }
}
```
With:
```kotlin
withContext(Dispatchers.IO) {
    tool.execute(call, ctx)
}
```
If `tool.execute()` is not suspend, wrap in `runInterruptible` alone (which is the correct primitive for blocking calls in coroutines). The key: don't nest `runBlocking` inside a coroutine context.

**Verification:** `./gradlew --no-daemon :aura-core:testDebugUnitTest --tests 'com.aura.agent.ToolExecutor'`

**Commit:** `fix(tools): replace runBlocking-in-runInterruptible with withContext`

---

## Phase 3: P0 Safety — Fix Evolution Database Migration (1 commit)

### Task 3.1: Register MIGRATION_1_2 in EvolutionModule builder

**Objective:** Devices with EvolutionDatabase v1 can upgrade to v3 without crashing.

**Files:**
- Modify: `aura-core/src/main/kotlin/com/aura/evolution/EvolutionModule.kt:27`
- Create: `aura-core/schemas/com.aura.evolution.EvolutionDatabase/3.json` (if not present — git status shows it as untracked)
- Create: `aura-core/src/androidTest/kotlin/com/aura/evolution/EvolutionDatabaseMigrationTest.kt`
- Fix: `aura-core/src/androidTest/kotlin/com/aura/memory/MemoryDatabaseMigrationTest.kt:175` (add missing `import org.junit.Assert.assertTrue`)

**Approach:**
1. Change `EvolutionModule.kt:27` from `arrayOf(MIGRATION_2_3)` to `arrayOf(MIGRATION_1_2, MIGRATION_2_3)`.
2. Commit the untracked `3.json` schema export.
3. Write `EvolutionDatabaseMigrationTest` with migration tests for 1→2, 2→3, and chained 1→3.
4. Fix the `assertTrue` import in `MemoryDatabaseMigrationTest.kt:175`.

**Test:** Migration test: create DB at v1, run migrations, verify v3 schema has `totalRuns` and `totalCandidates` columns.

**Verification:** `./gradlew --no-daemon :aura-core:connectedDebugAndroidTest --tests 'com.aura.evolution.EvolutionDatabaseMigrationTest'` (requires emulator)

**Commit:** `fix(evolution): register MIGRATION_1_2 in builder + migration tests`

---

## Phase 4: P0 Safety — Fix Creative Council Model Resolution (1 commit)

### Task 4.1: Fix CreativeCouncil to use ModelRole-resolved provider:model

**Objective:** Creative Council members call valid `provider:model` strings, not bare prefixes or "default".

**Files:**
- Modify: `app/src/main/kotlin/com/aura/ui/viewmodel/CreativeStudioViewModel.kt:190-203`
- Modify: `aura-core/src/main/kotlin/com/aura/creative/CreativeCouncil.kt` (if it takes model param)
- Test: `app/src/test/kotlin/com/aura/ui/viewmodel/CreativeStudioViewModelTest.kt`

**Approach:**
1. `resolveSubagentModel()` currently returns a capability provider prefix (e.g., `"stability"`) or `"default"`. `ProviderRegistry.parse()` requires `provider:model`.
2. Replace with: use `ModelRoleRouter` (currently dead code — this wires it) to resolve `ModelRole.CREATIVE_DRAFT` and `ModelRole.CREATIVE_CRITIC` to actual `provider:model` strings from `UserPreferences`.
3. If no role-specific model is configured, fall back to the user's default model (`UserPreferences.defaultModel`).
4. If no default model is configured, return an error — do NOT silently send "default" to the provider.
5. Pass the resolved `provider:model` string to `ProviderRegistry.chat()`.

**Test:** Configure `CREATIVE_DRAFT` role to `ollama:gemma3:12b`. Call `resolveSubagentModel(CreativeRole.Writer)`. Assert result is `"ollama:gemma3:12b"`. With no role configured and default `anthropic:claude-sonnet-4`, assert fallback.

**Verification:** `./gradlew --no-daemon :app:testDebugUnitTest --tests 'com.aura.ui.viewmodel.CreativeStudioViewModelTest'`

**Commit:** `fix(creative): resolve Creative Council models via ModelRoleRouter instead of bare prefixes`

---

## Phase 5: P0 Safety — Fix Production Pipeline Tool Contracts (1 commit)

### Task 5.1: Fix pipeline stage tool names, arguments, and dependency chaining

**Objective:** Built-in pipelines use correct tool names, valid arguments, and chain outputs.

**Files:**
- Modify: `aura-core/src/main/kotlin/com/aura/creative/ProductionPipelineEngine.kt`
- Modify: `aura-core/src/main/kotlin/com/aura/tools/CreativeTools.kt:91-98` (creative_add_world_item args)
- Test: `aura-core/src/test/kotlin/com/aura/creative/ProductionPipelineEngineTest.kt`

**Approach:**
1. Map stage types to correct tools:
   - `brainstorm/outline/draft/rewrite/simulate/continuity` → `creative_engine` (correct)
   - `image_generate` → `image_gen` tool (not `creative_engine`)
   - `tts_speak` → `tts_speak` tool (not `creative_engine`)
2. Fix `creative_add_world_item` call: use `type` + `name` + `description` (not `section` + `content`).
3. Add `dependsOn` chaining: each stage depends on the previous stage's output. Use the `DagResolver` pattern already in the codebase.
4. Feed each stage's output text into the next stage's input via `stepArgs["input"] = previousOutput`.
5. The richer `pipeline/ProductionPipeline.kt` definitions should be the source — wire `ProductionPipelineEngine` to use them instead of the simplified inline definitions.

**Test:** Run a 2-stage pipeline (outline → draft). Assert both stages complete successfully. Assert the draft stage receives the outline output as input.

**Verification:** `./gradlew --no-daemon :aura-core:testDebugUnitTest --tests 'com.aura.creative.ProductionPipelineEngineTest'`

**Commit:** `fix(creative): correct production pipeline tool names, args, and dependency chaining`

---

## Phase 6: Test Suite Repair (2 commits)

### Task 6.1: Fix ConversationStoreTest and EvolutionSafetyGuardTest

**Objective:** Unit test suite is green.

**Files:**
- Modify: `aura-core/src/test/kotlin/com/aura/agent/ConversationStoreTest.kt` (add `isReturnDefaultValues = true` or mock `android.util.Log`)
- Modify: `aura-core/src/test/kotlin/com/aura/evolution/EvolutionSafetyGuardTest.kt:28` (update fixture to match expanded guard patterns)
- Modify: `aura-core/build.gradle.kts` or `aura-core/src/test/kotlin/com/aura/TestConfig.kt` (ensure `testOptions { unitTests.isReturnDefaultValues = true }`)

**Approach:**
1. `ConversationStoreTest` crashes because production code calls `Log.w()` which is not mocked in JVM tests. Fix: add `testOptions { unitTests.isReturnDefaultValues = true }` to `aura-core/build.gradle.kts` (if not already there), or wrap `Log.w` calls in `try-catch` in production code (already done for ImageGenTool — apply same pattern to ConversationStore).
2. `EvolutionSafetyGuardTest.detects api key leak` — the guard was expanded from 1 pattern to 10. The test fixture likely uses a pattern the guard now recognizes differently. Read the test, update the fixture to match the expanded patterns, and ensure the assertion matches.

**Verification:** `./gradlew --no-daemon :aura-core:testDebugUnitTest --tests 'com.aura.agent.ConversationStoreTest' --tests 'com.aura.evolution.EvolutionSafetyGuardTest'`

**Commit:** `test: fix ConversationStoreTest Log.w crash and EvolutionSafetyGuardTest fixture`

### Task 6.2: Fix MemoryDatabaseMigrationTest compilation

**Objective:** Core connected tests compile.

**Files:**
- Modify: `aura-core/src/androidTest/kotlin/com/aura/memory/MemoryDatabaseMigrationTest.kt:175`

**Approach:** Add `import org.junit.Assert.assertTrue` at the top of the file.

**Verification:** `./gradlew --no-daemon :aura-core:compileDebugAndroidTestKotlin`

**Commit:** `test: add missing assertTrue import in MemoryDatabaseMigrationTest`

---

## Phase 7: Navigation Fixes (2 commits)

### Task 7.1: Fix dead "Evolve" bottom-nav tab

**Objective:** Tapping "Evolve" in the bottom nav navigates to a real screen, not a blank void.

**Files:**
- Modify: `app/src/main/kotlin/com/aura/ui/nav/NavGraph.kt` (add `composable("evolution")` that renders `EvolutionInboxScreen`)
- Verify: `app/src/main/kotlin/com/aura/ui/nav/AuraBottomNavigation.kt:69` (route "evolution" stays)

**Approach:**
The prior fix (commit `5fdb9a72`) removed a duplicate `composable("evolution")` but also removed the only registration. The `evolution/inbox` route exists but the bottom nav taps `"evolution"`.

Fix: add `composable("evolution") { EvolutionInboxScreen(onBack = { navController.popBackStack() }) }` back to NavGraph. This is not a duplicate — it's the bottom-nav entry point.

**Verification:** `./gradlew --no-daemon :app:compileDebugKotlin :app:connectedDebugAndroidTest --tests 'com.aura.ui.nav.BottomNavigationTest'`

**Commit:** `fix(nav): restore composable("evolution") route for bottom-nav Evolve tab`

### Task 7.2: Wire onOpenProduction and evolution/rollback entry points

**Objective:** Production pipeline screen is reachable from Home; rollback screen is reachable from inbox.

**Files:**
- Modify: `app/src/main/kotlin/com/aura/ui/screens/home/HomeSecondaryActions.kt:68-123` (add Production entry to destinations list)
- Modify: `app/src/main/kotlin/com/aura/ui/evolution/EvolutionInboxScreen.kt` (add "Rollback" button for applied proposals, navigate to `evolution/rollback/$proposalId`)
- Test: compile gate

**Approach:**
1. Add a `Production` entry to `HomeSecondaryActions.destinations` list with an icon and the `onOpenProduction` callback.
2. In `EvolutionInboxScreen`, for proposals with status `APPLIED`, add a "Rollback" button that navigates to `evolution/rollback/${proposal.id}`.

**Verification:** `./gradlew --no-daemon :app:compileDebugKotlin`

**Commit:** `fix(nav): wire Production and Rollback entry points in Home and Inbox`

---

## Phase 8: Tool Policy and Risk Metadata (2 commits)

### Task 8.1: Wire PolicyEngine into ToolExecutor

**Objective:** User-configured tool policies in Settings actually affect runtime behavior.

**Files:**
- Modify: `aura-core/src/main/kotlin/com/aura/agent/ToolExecutor.kt` (inject `PolicyEngine`, call `policyEngine.evaluate()` before inline gates)
- Modify: `aura-core/src/main/kotlin/com/aura/agent/MemoryAugmentedAgenticLoop.kt` (pass `PolicyEngine` to `ToolExecutor` or make it Hilt-injected)
- Modify: `aura-core/src/main/kotlin/com/aura/agent/ToolsModule.kt` (provide `PolicyEngine` and `ToolPolicyStore` to `ToolExecutor`)
- Test: `aura-core/src/test/kotlin/com/aura/agent/PolicyEngineIntegrationTest.kt`

**Approach:**
1. `ToolExecutor` currently has inline gates for incognito and remote-cost. Replace with: call `policyEngine.evaluate(toolName, context)` first. If it returns `Deny`, return `ToolResult.Error("policy_denied", ...)`. If it returns `Allow`, proceed to the inline gates as a second layer.
2. `PolicyEngine` reads from `ToolPolicyStore` (DataStore-backed) which Settings can modify.
3. The inline incognito and remote-cost gates remain as a hardcoded fallback for when `PolicyEngine` has no explicit policy.
4. `ToolExecutor` becomes `@Inject constructor(private val policyEngine: PolicyEngine, ...)`.

**Test:** Configure a tool as `DISABLED` in `ToolPolicyStore`. Call `ToolExecutor.execute()`. Assert `ToolResult.Error("policy_denied")`. Configure as `ENABLED`. Assert normal execution.

**Verification:** `./gradlew --no-daemon :aura-core:testDebugUnitTest --tests 'com.aura.agent.PolicyEngineIntegrationTest'`

**Commit:** `fix(tools): wire PolicyEngine into ToolExecutor so Settings policies are enforced`

### Task 8.2: Correct tool risk metadata

**Objective:** Tool risk classifications match actual behavior.

**Files:**
- Modify: `aura-core/src/main/kotlin/com/aura/tools/CreativeEngineTool.kt` (READ_ONLY → REMOTE_COST)
- Modify: `aura-core/src/main/kotlin/com/aura/tools/KnowledgeGraphTool.kt` (READ_ONLY → REMOTE_COST)
- Modify: `aura-core/src/main/kotlin/com/aura/tools/UseSkillTool.kt` (READ_ONLY → WRITE_LOCAL)
- Modify: `aura-core/src/main/kotlin/com/aura/tools/HttpFileWriteTool.kt` (REMOTE_COST → WRITE_REMOTE)
- Modify: `aura-core/src/main/kotlin/com/aura/mcp/McpToolBridge.kt` (use server annotations if available, default REMOTE_COST for network tools)
- Test: `aura-core/src/test/kotlin/com/aura/tools/RemoteCostToolRiskTest.kt`

**Approach:**
1. `creative_engine` calls a paid cloud model → `REMOTE_COST`.
2. `knowledge_graph_extract` calls a paid cloud model → `REMOTE_COST`.
3. `use_skill` persists evolution evidence → `WRITE_LOCAL`.
4. `http_file_write` mutates remote endpoints → `WRITE_REMOTE`.
5. MCP tools: if the server provides annotations (`readOnlyHint`, `destructiveHint`), use them. Otherwise default to `REMOTE_COST` for network tools (not `READ_ONLY`).

**Verification:** `./gradlew --no-daemon :aura-core:testDebugUnitTest --tests 'com.aura.tools.RemoteCostToolRiskTest'`

**Commit:** `fix(tools): correct risk metadata for creative, KG, skill, HTTP, and MCP tools`

---

## Phase 9: Provider and Capability Fixes (2 commits)

### Task 9.1: Enable capability credentials in Settings

**Objective:** Stability, ElevenLabs, Kling, World Labs can be configured by the user.

**Files:**
- Modify: `app/src/main/kotlin/com/aura/ui/settings/SettingsViewModel.kt:80-83` (change `isConsumed = false` to `true` for stability, elevenlabs, kling, worldlabs)
- Modify: `app/src/main/kotlin/com/aura/ui/settings/ProviderKeyField.kt` (remove "Coming soon" text for these providers)

**Approach:**
The `SETTINGS_CREDENTIAL_SPECS` marks these 4 as `isConsumed = false` with a "Coming soon" message. But the backend tools (`ImageGenCapabilityTool`, etc.) already consume them. Change to `isConsumed = true`.

**Verification:** `./gradlew --no-daemon :app:compileDebugKotlin`

**Commit:** `fix(settings): enable Stability, ElevenLabs, Kling, World Labs credential fields`

### Task 9.2: Fix MCP authenticated first-connect and tool risk

**Objective:** MCP servers with auth tokens connect on first try; MCP tools have correct risk.

**Files:**
- Modify: `app/src/main/kotlin/com/aura/ui/settings/SettingsViewModel.kt` (`testMcpConnection()` — pass `authToken` to `mcpClientManager.connect()`)
- Modify: `aura-core/src/main/kotlin/com/aura/mcp/McpToolBridge.kt` (assign `REMOTE_COST` to MCP tools that make network calls)
- Modify: `aura-core/src/main/kotlin/com/aura/mcp/McpClientManager.kt` (synchronize `connections` map)
- Test: `aura-core/src/test/kotlin/com/aura/mcp/McpToolBridgeTest.kt`

**Approach:**
1. `testMcpConnection()` builds a config with `authToken` but calls `connect(config)` without passing the token as a separate arg. Fix: `connect(config.copy(authToken = authToken))` or ensure `connect` reads `config.authToken`.
2. `McpClientManager.connections` is a mutable map — make it `ConcurrentHashMap`.
3. `McpToolBridge.registeredNames` — make it `ConcurrentHashMap.newKeySet()`.
4. MCP tools that call remote endpoints should be `REMOTE_COST`, not `READ_ONLY`.

**Verification:** `./gradlew --no-daemon :aura-core:testDebugUnitTest --tests 'com.aura.mcp.McpToolBridgeTest'`

**Commit:** `fix(mcp): pass auth token on first connect, correct tool risk, synchronize collections`

---

## Phase 10: AgentRun and Hands Semantic Fixes (2 commits)

### Task 10.1: Fix AgentRun approval and resume

**Objective:** Approving a step re-enqueues the worker; resuming a run starts execution.

**Files:**
- Modify: `app/src/main/kotlin/com/aura/ui/viewmodel/AgentRunsViewModel.kt` (`approve()` — reset step to PENDING, call `AgentRunExecutorService.enqueue()`)
- Modify: `app/src/main/kotlin/com/aura/ui/viewmodel/AgentRunsViewModel.kt` (`resume()` — set run to RUNNING, call `AgentRunExecutorService.enqueue()`)
- Test: `aura-core/src/test/kotlin/com/aura/agentrun/AgentRunApprovalResumeTest.kt`

**Approach:**
1. `approve()`: update step status from `AWAITING_APPROVAL` to `PENDING`, then call `AgentRunExecutorService.enqueue(context, runId)`.
2. `resume()`: update run status from `PAUSED` to `RUNNING`, then call `AgentRunExecutorService.enqueue(context, runId)`.
3. The worker picks up PENDING steps and processes them.

**Verification:** `./gradlew --no-daemon :aura-core:testDebugUnitTest --tests 'com.aura.agentrun.AgentRunApprovalResumeTest'`

**Commit:** `fix(agentrun): wire approve and resume to re-enqueue executor worker`

### Task 10.2: Fix agent-issued Hands to use HandRepository.run()

**Objective:** Agent-triggered hands use the same semantics as manual/scheduled runs.

**Files:**
- Modify: `aura-core/src/main/kotlin/com/aura/tools/RunHandTool.kt`
- Test: `aura-core/src/test/kotlin/com/aura/tools/RunHandToolTest.kt`

**Approach:**
`RunHandTool` always takes `HandRunEnqueuer` which bypasses enabled checks, conditions, template substitution, and history. Replace with: call `HandRepository.run(handId, variables, source)` which does all of the above. Use the enqueuer only for the async AgentRun path when explicitly requested.

**Verification:** `./gradlew --no-daemon :aura-core:testDebugUnitTest --tests 'com.aura.tools.RunHandToolTest'`

**Commit:** `fix(hands): agent-issued hands use HandRepository.run() for full semantics`

---

## Phase 11: Evolution System — Implement Apply Saga (2 commits)

### Task 11.1: Implement remaining 13 EvolutionAction handlers

**Objective:** All 19 EvolutionAction values have real, reversible handlers.

**Files:**
- Modify: `aura-core/src/main/kotlin/com/aura/evolution/EvolutionApplySaga.kt`
- Test: `aura-core/src/test/kotlin/com/aura/evolution/EvolutionApplySagaHandlersTest.kt`

**Approach:**
Implement each handler following the pattern of the existing `CREATE_SKILL` handler:
1. `PATCH_SKILL` — apply diff to skill body, snapshot before.
2. `REWRITE_SKILL` — replace body, snapshot before.
3. `MERGE_SKILLS` — merge bodies, retire source.
4. `RETIRE_SKILL` — mark retired.
5. `PROMOTE_TO_HAND` — create Hand from skill steps.
6. `PATCH_SPECIALIST_PROMPT` — update UserPreferences.specialistOverrides.
7. `ADD_SKILL_EXAMPLE` — append example block.
8. `CONSOLIDATE_MEMORIES` — merge memories via MemoryStore.
9. `CREATE_BELIEF` / `UPDATE_BELIEF` / `RETIRE_BELIEF` — write BeliefDao.
10. `FORGET_MEMORY` / `UPDATE_MEMORY_CATEGORY` / `MERGE_MEMORIES` — call MemoryStore.
11. `NEW_PROACTIVE_RULE` / `ADJUST_RULE_TIMING` / `DISABLE_RULE` / `ENABLE_RULE` / `REWRITE_RULE_MESSAGE` — mutate ProactivePolicyEngine.

Each handler: snapshot before mutation, return `ApplyResult.Success(proposalId, revisionId)`, on failure return `ApplyResult.Error`.

**Note:** This overlaps with the existing `2026-07-18-phase-15-close-gaps.md` plan (Commit 6). If that plan has already shipped some handlers, skip them (pre-execution verification).

**Verification:** `./gradlew --no-daemon :aura-core:testDebugUnitTest --tests 'com.aura.evolution.EvolutionApplySagaHandlersTest'`

**Commit:** `feat(evolution): implement all 19 EvolutionAction apply saga handlers`

### Task 11.2: Wire evolution proposal promotion and close the loop

**Objective:** Coordinator detects → reflects → promotes → proposes → user approves → applies → rolls back if needed.

**Files:**
- Modify: `aura-core/src/main/kotlin/com/aura/evolution/EvolutionCoordinator.kt`
- Test: `aura-core/src/test/kotlin/com/aura/evolution/EvolutionCoordinatorPromotionTest.kt`

**Approach:**
Follow Commit 4 from the existing `2026-07-18-phase-15-close-gaps.md` plan. If already shipped, skip.

**Verification:** `./gradlew --no-daemon :aura-core:testDebugUnitTest --tests 'com.aura.evolution.EvolutionCoordinatorPromotionTest'`

**Commit:** `feat(evolution): wire candidate→proposal promotion in coordinator`

---

## Phase 12: World Model, Taste, and Canon Substrate (1 commit)

### Task 12.1: Wire world model writers and taste routing

**Objective:** World model tables have producers; taste profile influences model routing.

**Files:**
- Modify: `aura-core/src/main/kotlin/com/aura/evolution/EvolutionApplySaga.kt` (CREATE_BELIEF handler writes BeliefDao — done in Phase 11)
- Modify: `aura-core/src/main/kotlin/com/aura/agent/MemoryAugmentedAgenticLoop.kt` (include beliefs in recall context)
- Modify: `aura-core/src/main/kotlin/com/aura/providers/ModelRoleRouter.kt` (consume `TasteEngine.bestModelForRole()`)
- Modify: `aura-core/src/main/kotlin/com/aura/taste/TasteEngine.kt` (fix reaction toggle to replace, not accumulate)
- Test: `aura-core/src/test/kotlin/com/aura/taste/TasteEngineRoutingTest.kt`

**Approach:**
1. `MemoryAugmentedAgenticLoop`: after memory recall, also query `BeliefDao` for relevant beliefs and include them in the system prompt.
2. `ModelRoleRouter`: when resolving a role, call `TasteEngine.bestModelForRole(role)` if taste data exists. If no taste data, fall back to UserPreferences role model.
3. `TasteEngine`: fix `toggleReaction()` — when switching from 👍 to 👎, delete the old signal and insert the new one, don't accumulate both.
4. Wire `recordRoutingOutcome()` call in `MemoryAugmentedAgenticLoop` after each model response.

**Verification:** `./gradlew --no-daemon :aura-core:testDebugUnitTest --tests 'com.aura.taste.TasteEngineRoutingTest'`

**Commit:** `feat(world): wire world model beliefs into recall and taste into model routing`

---

## Phase 13: Backup Coverage (2 commits)

### Task 13.1: Add missing backup types for 18 entities

**Objective:** Backup/restore preserves all user data.

**Files:**
- Modify: `aura-core/src/main/kotlin/com/aura/backup/AuraBackup.kt` (add backup data classes for all 18 missing entity types)
- Modify: `aura-core/src/main/kotlin/com/aura/backup/BackupManager.kt` (add snapshot/restore/purge for each)
- Test: `aura-core/src/test/kotlin/com/aura/backup/BackupManagerTest.kt`

**Approach:**
Add backup types and mappers for:
- `DocumentChunkBackup`, `CreativeArtifactBackup`, `CreativeRevisionBackup`, `CreativeBranchBackup`, `CreativeGenerationJobBackup`, `CanonFactBackup`, `CreativeSimulationBackup`, `ContinuityIssueBackup`, `ArtifactDependencyBackup`, `BeliefBackup`, `EvidenceBackup`, `WorldEventBackup`, `OpportunityBackup`, `PreferenceSignalBackup`, `StyleProfileBackup`, `ReferenceIdentityBackup`, `RoutingOutcomeBackup`, `MemoryFeedbackBackup`
- Bump `SCHEMA_VERSION` from 7 to 8.
- Add `ProactiveInteractionBackup` and `correlationTag` to `ProactiveEventBackup`.
- Add the 12 missing preference keys to `PreferencesBackup`.
- Update `BackupManager.snapshot()` to export all, `restore()` to import all, `purgeAll()` to delete all.

**Verification:** `./gradlew --no-daemon :aura-core:testDebugUnitTest --tests 'com.aura.backup.BackupManagerTest'`

**Commit:** `fix(backup): add backup types for all 18 missing entities + missing prefs + correlationTag`

### Task 13.2: Move SMTP password to SecureDataStore

**Objective:** SMTP password is encrypted at rest.

**Files:**
- Modify: `aura-core/src/main/kotlin/com/aura/data/UserPreferences.kt` (remove `KEY_SMTP_PASSWORD` from DataStore)
- Modify: `aura-core/src/main/kotlin/com/aura/data/UserPreferences.kt` (read/write SMTP password via `SecureDataStore`)
- Modify: `aura-core/src/main/kotlin/com/aura/backup/BackupManager.kt` (SMTP password excluded from backup, like API keys)

**Approach:**
1. Remove `KEY_SMTP_PASSWORD` from DataStore keys.
2. Add `smtpPassword: Flow<String>` that reads from `SecureDataStore.getString("smtp_password")`.
3. Add `suspend fun setSmtpPassword(password: String)` that writes to `SecureDataStore.putString("smtp_password", password)`.
4. Backup: SMTP password is excluded from backup (security policy, same as API keys).

**Commit:** `fix(security): move SMTP password from plain DataStore to SecureDataStore`

---

## Phase 14: Security Hardening (1 commit)

### Task 14.1: Fix HTTP file tools SSRF and MCP endpoint security

**Objective:** User-URL HTTP tools use pinned-IP client; MCP endpoints use SSRF guard.

**Files:**
- Modify: `aura-core/src/main/kotlin/com/aura/tools/HttpFileReadTool.kt`
- Modify: `aura-core/src/main/kotlin/com/aura/tools/HttpFileWriteTool.kt`
- Modify: `aura-core/src/main/kotlin/com/aura/mcp/McpConnection.kt`
- Test: `aura-core/src/test/kotlin/com/aura/tools/HttpFileReadToolSsrfTest.kt`

**Approach:**
1. `HttpFileReadTool` / `HttpFileWriteTool`: after `SsrfGuard.validate(url)`, use `SsrfGuard.pinnedClient(url)` (which carries the resolved IP into the request) instead of the plain `OkHttpClient`. This prevents DNS rebinding between validation and request.
2. `McpConnection`: apply `SsrfGuard.validate()` to the MCP server URL before connecting. Block private IPs and localhost by default (unless `trustedLocal` is set).

**Verification:** `./gradlew --no-daemon :aura-core:testDebugUnitTest --tests 'com.aura.tools.HttpFileReadToolSsrfTest'`

**Commit:** `fix(security): pin IP for HTTP file tools and apply SSRF guard to MCP endpoints`

---

## Phase 15: UI Fixes — Missing Controls and Dead Code (1 commit)

### Task 15.1: Add missing Settings controls and fix dead UI state

**Objective:** All UserPreferences keys have Settings UI; dead UI state is wired or removed.

**Files:**
- Modify: `app/src/main/kotlin/com/aura/ui/screens/SettingsScreen.kt` (add: image model picker, TTS toggle, incognito default toggle, MCP dialog fields)
- Modify: `app/src/main/kotlin/com/aura/ui/evolution/BeliefsScreen.kt` (wire `viewModel.selected` to a detail dialog or remove the click handler)
- Modify: `app/src/main/kotlin/com/aura/ui/screens/SettingsScreen.kt:1175` (move evolution section out of Data & Backup into its own section)

**Approach:**
1. Image model: add a text field or dropdown in Settings → Generation section bound to `userPreferences.imageModel`.
2. TTS toggle: add a switch in Settings → Voice section bound to `userPreferences.ttsEnabled`.
3. Incognito default: add a switch in Settings → Privacy section bound to `userPreferences.incognitoDefault`.
4. MCP dialog: add fields for `allowedToolPrefixes`, `deniedTools`, `maxConcurrentCalls` to the Add MCP server dialog.
5. `BeliefsScreen.select()`: either add a detail dialog that shows `viewModel.selected` content, or remove the `select()` call and the `_selected` StateFlow.
6. Evolution section: move from inside `SettingsSection("Data & Backup")` to its own `SettingsSection("Evolution")`.

**Verification:** `./gradlew --no-daemon :app:compileDebugKotlin`

**Commit:** `fix(ui): add missing Settings controls, wire BeliefsScreen, reorganize Evolution section`

---

## Phase 16: Dead Code Cleanup (1 commit)

### Task 16.1: Remove verified dead code

**Objective:** Remove dead infrastructure that has no production consumers and no plan to wire.

**Files:**
- Delete: `aura-core/src/main/kotlin/com/aura/evolution/EvolutionProposalTools.kt` (orphaned, shadow copy of `tools/evolution/EvolutionTools.kt`)
- Delete: `aura-core/src/main/kotlin/com/aura/proactive/ProactivePolicyEngine.kt` (zero references — BUT only if Phase 11 doesn't wire it)
- Delete: `aura-core/src/main/kotlin/com/aura/evolution/EvolutionSkillDetector.kt` (zero references)
- Delete: `aura-core/src/main/kotlin/com/aura/evolution/EvolutionMemorySynthesizer.kt` (zero references — BUT only if Phase 11 doesn't wire it)
- Delete: `aura-core/src/main/kotlin/com/aura/evolution/EvolutionProactiveRuleGenerator.kt` (zero references — BUT only if Phase 11 doesn't wire it)
- Delete: `aura-core/src/main/kotlin/com/aura/evolution/EvolutionSubagentExecutor.kt` (zero references — BUT only if Phase 11 doesn't wire it)
- Delete: `aura-core/src/main/kotlin/com/aura/evolution/EvolutionShadowEvaluator.kt` (zero references — BUT only if Phase 11 doesn't wire it)
- Delete: `aura-core/src/main/kotlin/com/aura/providers/ModelRoleRouter.kt` (zero references — BUT only if Phase 4 wires it; if wired, keep)

**Approach:**
For each file: grep for references. If zero references AND no plan to wire (check Phases 4, 11, 12), delete. If a Phase plans to wire it, keep and the Phase will wire it.

**Pre-execution verification:** `grep -rn "ClassName" aura-core/src/main/kotlin app/src/main/kotlin` for each class. Only delete if zero hits.

**Verification:** `./gradlew --no-daemon :aura-core:assembleDebug :app:assembleDebug`

**Commit:** `chore: remove dead evolution/proactive/model-role infrastructure`

---

## Phase 17: Release Integrity (2 commits)

### Task 17.1: Fix version identities

**Objective:** APK, README, and GitHub release all report the same version.

**Files:**
- Modify: `app/build.gradle.kts` (update `versionCode` and `versionName` to `19` and `"0.24.0"`)
- Modify: `README.md` (update version to `v0.24.0`, test count, tool count)
- Modify: `aura-core/src/main/kotlin/com/aura/BuildConfig` or equivalent (if version is hardcoded)

**Approach:**
1. `versionCode 18` → `19`, `versionName "0.15.2-debug"` → `"0.24.0"`.
2. README: update version, test count (after all fixes), tool count (after all fixes).
3. Remove stale release tags that point to wrong commits (or re-tag).

**Commit:** `chore: align version identifiers across APK, README, and releases`

### Task 17.2: Fix release tags and CI

**Objective:** Release tags point to the correct commit; CI runs on the branch.

**Files:**
- Modify: git tags (re-tag v0.24.0 at the correct commit after all fixes)
- Modify: `.github/workflows/ci.yml` (ensure it triggers on `feat/tier-1-friction`)

**Approach:**
1. After all fixes are committed and pushed: `git tag v0.24.0 -m "..."` at the final commit, `git push aura-android v0.24.0`.
2. Create GitHub Release: `gh release create v0.24.0 --title "v0.24.0" --notes "..." app/build/outputs/apk/debug/app-debug.apk`
3. Ensure CI workflow triggers on push to `feat/tier-1-friction`.
4. Verify CI is green.

**Commit:** `chore: tag v0.24.0 release with correct commit and APK`

---

## Summary Table

| Phase | # Commits | New Files | Modified Files | New Tests | Dependencies |
|-------|-----------|-----------|----------------|-----------|--------------|
| 0 — P0 proactive loop | 1 | 0 | 2 | 1 | None |
| 1 — P0 system insets | 1 | 0 | 4 | 0 | None |
| 2 — P0 permission/cost | 3 | 0 | 8 | 3 | None |
| 3 — P0 evolution migration | 1 | 2 | 2 | 1 | None |
| 4 — P0 creative model | 1 | 0 | 2 | 1 | None |
| 5 — P0 pipeline contracts | 1 | 0 | 2 | 1 | None |
| 6 — Test suite repair | 2 | 0 | 3 | 0 | Phases 0-5 |
| 7 — Navigation | 2 | 0 | 3 | 0 | None |
| 8 — Tool policy/risk | 2 | 0 | 7 | 2 | None |
| 9 — Provider/capability | 2 | 0 | 4 | 1 | None |
| 10 — AgentRun/Hands | 2 | 0 | 3 | 2 | None |
| 11 — Evolution apply saga | 2 | 0 | 2 | 2 | None |
| 12 — World/taste | 1 | 0 | 4 | 1 | Phase 11 |
| 13 — Backup | 2 | 0 | 3 | 1 | None |
| 14 — Security | 1 | 0 | 3 | 1 | None |
| 15 — UI controls | 1 | 0 | 3 | 0 | None |
| 16 — Dead code | 1 | 0 | -7 | 0 | Phases 4, 11, 12 |
| 17 — Release integrity | 2 | 0 | 3 | 0 | All |
| **Total** | **28** | **2** | **~50** | **~17** | |

## Prior Plans Alignment

- `2026-07-18-phase-15-close-gaps.md` — Covers evolution system in depth (28 commits). This plan supersedes it for the evolution-specific items (Phase 11 here covers the same ground more concisely). The close-gaps plan can be used as detailed reference for Phase 11 implementation.
- `2026-07-16_130402-aura-beyond-sota-master-plan.md` — The beyond-SOTA master plan. This plan fixes the substrate that plan built but didn't wire.
- `2026-07-14-audit-remediation-final-verification.md` — Prior audit remediation. This plan addresses new findings not covered by that audit.

## Claims Verified False (do not fix)

- "MCP tools are not bridged into ToolRegistry" — `McpToolBridge` exists and is called (confirmed by subagent).
- "Evolution scheduler is never started" — `ProactiveBootstrap` now reconciles it.
- "trigger_evolution_run is READ_ONLY" — it is now `WRITE_LOCAL`.
- "vision is READ_ONLY" — it is now `REMOTE_COST`.
- "EvolutionSafetyGuard only recognizes OpenAI keys" — patterns were expanded to 10.
- "production route is missing" — route is registered (but unreachable from UI — fixed in Phase 7).
- "Citation.kt is dead" — it has 5 active production consumers.
- "VisionTool Ollama URL is wrong" — `https://ollama.com/v1` is correct for Ollama Cloud.