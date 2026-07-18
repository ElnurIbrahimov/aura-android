# Architecture & DI/Hilt Layer Audit — Ranked Findings

**Project:** aura-android-clean (2 modules, 617 Kotlin files, ~76K LOC)
**Date:** 2026-07-17
**Scope:** God classes, Hilt cycles, Room migrations, constructor drift, substrate wiring gaps

---

## P0 — Must fix before next release

### P0.1 MemoryViewModel constructor drift breaks unit tests

| File | Line | Evidence |
|---|---|---|
| `app/.../ui/viewmodel/MemoryViewModel.kt` | 46–49 | Constructor requires `MemoryStore`, `MemoryFeedbackDao`, `EvolutionHooks?` |
| `app/.../ui/viewmodel/MemoryViewModelTest.kt` | 59, 82, 91, 103, 117, 131, 151, 161, 187, 212 | All create `MemoryViewModel(memoryStore)` — missing `MemoryFeedbackDao` arg |

**Problem:** `MemoryViewModel`'s constructor grew `feedbackDao: MemoryFeedbackDao` and `evolutionHooks: EvolutionHooks?` parameters, but `MemoryViewModelTest` was never updated. Every test call `MemoryViewModel(memoryStore)` passes only 1 of 3 required arguments — **the tests cannot compile**. `feedbackDao` has no default value, so this is a hard compilation error, not a runtime one.

**Fix:** Update test to pass `feedbackDao` mock:
```kotlin
private val feedbackDao = mockk<MemoryFeedbackDao>(relaxed = true)
// ...
val vm = MemoryViewModel(memoryStore, feedbackDao)
```

---

### P0.2 PolicyEngine — fully defined, completely unwired from agent loop

| File | Line | Evidence |
|---|---|---|
| `aura-core/.../agent/policy/PolicyEngine.kt` | 20–57 | `@Singleton` class with `evaluate()` method, full incognito/confirmation/approval logic |
| `aura-core/.../agent/ToolExecutor.kt` | 35–95 | Executor does its OWN incognito gate (line 47), permission check (line 57), approval gate (line 67) — never calls PolicyEngine |
| `aura-core/.../agent/MemoryAugmentedAgenticLoop.kt` | 60–465 | Never imports or calls PolicyEngine |

**Problem:** `PolicyEngine.evaluate()` was designed as the single policy enforcement point, but `ToolExecutor` duplicates all its logic inline. PolicyEngine is dead code — any policy changes must be made in two places (or only one, and they diverge silently). The `ToolPolicy.kt` docstring explicitly states "applied by PolicyEngine before ToolExecutor dispatches" yet ToolExecutor dispatches without it.

**Fix:** Wire `PolicyEngine` into `ToolExecutor.execute()`, replacing the inline incognito/permission/approval gates with a single `engine.evaluate(tool, ctx)` call.

---

### P0.3 TraceSink — defined and tested, zero production callers

| File | Line | Evidence |
|---|---|---|
| `aura-core/.../agent/runtime/TraceSink.kt` | 18–67 | `@Singleton class TraceSink` — full implementation with bounded ring buffer |
| (all production code) | — | `grep -rn "TraceSink" aura-core/src/main/` returns only its own definition |

**Problem:** `TraceSink` is a well-architected observable event ledger, but **no production code emits events to it**. The agent loop (`MemoryAugmentedAgenticLoop`), `ToolExecutor`, and the evolution pipeline should all call `traceSink.emit(...)` at key lifecycle points. Without this, every debugging session starts from scratch.

**Fix:** Inject `TraceSink` into:
- `MemoryAugmentedAgenticLoop` — emit on tool_call_start, tool_result, error, done
- `ToolExecutor` — emit on execute, timeout, error
- `Brain.stream()` — emit on provider errors, retries, failover

---

### P0.4 EvolutionScheduler — built but never started

| File | Line | Evidence |
|---|---|---|
| `aura-core/.../evolution/EvolutionScheduler.kt` | 19–43 | `@Singleton` with `schedule()` and `cancel()` |
| `aura-core/.../evolution/EvolutionWorker.kt` | 16–35 | `@HiltWorker` — ready to run coordinator |
| `app/.../aura/AuraApp.kt` | 16–43 | Calls `ProactiveBootstrap.start()` but NOT `EvolutionScheduler.schedule()` |

**Problem:** The entire evolution pipeline (Coordinator → Worker → Scheduler) is dormant. `EvolutionScheduler.schedule()` is never called during app initialization or anywhere in production code. Evolution settings exist in the UI but toggling them has no effect — no periodic evolution run ever fires. This makes the evolution settings UI in `SettingsScreen.kt` misleading (user thinks evolution is running when it isn't).

**Fix:** Inject `EvolutionScheduler` into `AuraApp` or `ProactiveBootstrap` and call `schedule()` if `evolutionEnabled` preference is true.

---

## P1 — Should fix this iteration

### P1.1 EvolutionDatabase missing MIGRATION_1_2 in migrations array

| File | Line | Evidence |
|---|---|---|
| `aura-core/.../evolution/EvolutionModule.kt` | 51–56 | `MIGRATION_1_2` defined as `private val` |
| `aura-core/.../evolution/EvolutionModule.kt` | 27 | `migrations = arrayOf(MIGRATION_2_3)` — only has v2→v3 |
| `aura-core/.../evolution/EvolutionDatabase.kt` | 21 | `version = 3` |

**Problem:** `MIGRATION_1_2` is defined but NOT included in the migrations array passed to `RoomConfig.builder()`. Any device upgrading from database version 1 (shipped in an earlier build) to version 3 will crash with `IllegalStateException: A migration from 1 to 3 is necessary`. Room requires the full migration chain (1→2→3) or a destructive fallback. `RoomConfig` only provides `fallbackToDestructiveMigrationOnDowngrade()` — not on upgrade.

**Fix:** Add `MIGRATION_1_2` to the array:
```kotlin
migrations = arrayOf(MIGRATION_1_2, MIGRATION_2_3)
```

---

### P1.2 MCP ClientManager — wired to UI settings only, NOT to agent tool system

| File | Line | Evidence |
|---|---|---|
| `aura-core/.../mcp/McpClientManager.kt` | 22–112 | Full MCP client with connect/listTools/callTool |
| `aura-core/.../tools/ToolsModule.kt` | 1–143 | ToolRegistry created with 40+ tools — **zero MCP references** |
| `aura-core/.../agent/ToolRegistry.kt` | — | No MCP import or dynamic registration path |
| `app/.../settings/SettingsViewModel.kt` | 189 | `mcpClientManager: McpClientManager` — UI-only wiring |

**Problem:** Users can configure MCP servers in Settings, and `SettingsViewModel` can test connections, but **MCP tools are never registered in `ToolRegistry`** and **`McpClientManager` is never injected into the agent loop**. The agent cannot call any MCP tool at runtime. The Settings UI creates a false impression of a working feature.

**Fix:** Either:
- (a) Inject `McpClientManager` into `ToolsModule.provideToolRegistry()` and dynamically register all connected MCP server tools, OR
- (b) Create an MCP tool bridge that `MemoryAugmentedAgenticLoop` checks alongside `ToolRegistry`

---

### P1.3 God classes exceed 800 lines (4 files)

| File | Lines | Type | Risks |
|---|---|---|---|
| `app/.../ui/screens/SettingsScreen.kt` | **1,420** | Composable screen | Everything-from-Settings anti-pattern; 42 injects in one `@Composable`; 5 `@Composable` functions with 500+ sloc each |
| `app/.../ui/viewmodel/ChatViewModel.kt` | **1,052** | HiltViewModel | 42 `fun` methods; handles send, model, specialist, TTS, incognito, deep-mode, vision, profile, permissions — violates SRP in one class |
| `app/.../ui/screens/MemoryScreen.kt` | **970** | Composable screen | 7 `@Composable` functions; edit/delete/category/bulk ops in one file |
| `app/.../ui/screens/TasksScreen.kt` | **853** | Composable screen | Task CRUD + reminder CRUD + search + skeleton loading in one file |

**Borderline (P2):**
- `app/.../settings/SettingsViewModel.kt` — **836 lines** — 40+ state fields, 30+ methods
- `aura-core/.../memory/MemoryModule.kt` — **653 lines** — 12 migrations + 15 DAO providers

**Risks:** Single-file classes this size are impossible to review, hard to test (no unit test covers SettingsScreen), and every small change risks breaking unrelated functionality. `ChatViewModel` is particularly dangerous — it orchestrates the entire agent loop, send pipeline, model selection, specialist routing, and TTS.

**Fix:** Split each:
- `SettingsScreen`: extract `AiModelsSection`, `McpConfigSection`, `EvolutionSettingsSection`, `KeyManagementSection` into separate files
- `ChatViewModel`: extract `ChatSendPipeline`, `ModelSelectionManager`, `TtsManager`, `ChatPermissions`
- `MemoryScreen` & `TasksScreen`: extract inline `@Composable` dialogs into separate files

---

### P1.4 MemoryModule.kt — 653 lines, 12 migrations, 15 DAO providers

| File | Line | Evidence |
|---|---|---|
| `aura-core/.../memory/MemoryModule.kt` | 1–654 | 12 MIGRATION_* objects, 15 `@Provides` functions, all in one `object` |

**Problem:** Mirroring issue to the god classes — MemoryModule is a "god module." Adding a new entity (e.g., `MemoryFeedbackEntity`) required touching this file repeatedly: MIGRATION_12_13 definition plus `@Provides fun provideMemoryFeedbackDao()`. This creates merge conflicts and review fatigue.

**Fix:** Extract migrations into `MemoryMigrations.kt` and DAO providers into `MemoryDaoModule.kt` or use `@Binds` with constructor injection instead of `@Provides`.

---

## P2 — Should fix, lower urgency

### P2.1 EvolutionHooks injected as optional everywhere — fragile pattern

| File | Line | Evidence |
|---|---|---|
| `app/.../ui/viewmodel/MemoryViewModel.kt` | 49 | `private val evolutionHooks: EvolutionHooks? = null` |
| `aura-core/.../memory/MemoryStore.kt` | 20 | `private val evolutionHooks: ...EvolutionHooks? = null` |
| `aura-core/.../proactive/MorningBriefBuilder.kt` | 53 | Same pattern |
| `aura-core/.../proactive/ProactiveEvents.kt` | 32 | Same pattern |
| `aura-core/.../skills/SkillsStore.kt` | 25 | Same pattern |
| `aura-core/.../tools/UseSkillTool.kt` | 28 | Same pattern |
| `aura-core/.../evolution/EvolutionModule.kt` | 1–64 | Does NOT explicitly `@Provides` EvolutionHooks |

**Problem:** `EvolutionHooks` is never explicitly provided by any Hilt module. Hilt resolves it via `@Inject constructor` chain, which works but leaves the resolution implicit. Every consumer treats it as nullable with a null default — if Hilt ever fails to resolve it, evolution signals silently disappear. This pattern obscures the dependency graph and makes it impossible to tell at a glance which components depend on evolution.

**Fix:** Add an explicit `@Provides @Singleton fun provideEvolutionHooks(recorder: EvolutionEvidenceRecorder): EvolutionHooks = EvolutionHooks(recorder)` to `EvolutionModule`, and consider making the parameter non-nullable in consumers (it can still be injected `@Nullable` if absolutely necessary).

---

### P2.2 SubagentManager wired to CreativeCouncil and Evolution only — not to main agent loop

| File | Line | Evidence |
|---|---|---|
| `aura-core/.../agents/SubagentManager.kt` | 26–full | `@Singleton` with `@Inject constructor()` |
| `aura-core/.../creative/CreativeCouncil.kt` | 21 | `subagentManager: SubagentManager` — Writer specialist only |
| `aura-core/.../evolution/EvolutionSubagentExecutor.kt` | 20 | `subagentManager: SubagentManager` — evolution only |

**Problem:** SubagentManager is available but never wired into the main `MemoryAugmentedAgenticLoop`. The General specialist cannot delegate tasks to subagents. The line "subagent-driven development" is only accessible through creative writing and evolution — not as a general agent capability.

**Fix:** If subagent task delegation is intended as a general capability, inject `SubagentManager` into `MemoryAugmentedAgenticLoop` or `ToolExecutor` and expose it as `delegate_task` tool.

---

### P2.3 AppModule is empty marker — unused but fine as placeholder

| File | Line | Evidence |
|---|---|---|
| `app/.../di/AppModule.kt` | 1–15 | `object AppModule` — no `@Provides`, no `@Binds` |

**Rating:** P2. Not a bug, but the module serves no purpose. App-scoped providers that logically belong here (e.g., `CrashLogger`, `OkHttpClient`) are instead defined in `ProviderModule` and other core modules, creating a split where app-layer providers live in aura-core. Either populate it or remove it.

---

### P2.4 CapabilityRouter actually IS wired (unexpected positive)

| File | Line | Evidence |
|---|---|---|
| Various production files | — | Wired into 5+ tools (ImageGenCapabilityTool, MediaCapabilityTools, WebSearchCapabilityTool, ProductionPipelineEngine, CreativeStudioViewModel) |

**Note:** Unlike the other substrate gaps, `CapabilityRouter` is properly wired and used. This is flagged as a positive — it's the only substrate component that made it through to actual tool execution.

---

### P2.5 DataStore-backed evolution preferences are written but never read to start scheduler

| File | Line | Evidence |
|---|---|---|
| `app/.../settings/SettingsViewModel.kt` | 419–424 | `setEvolutionEnabled()`, `setEvolutionIntervalHours()`, `setEvolutionShadowEnabled()` write to DataStore |
| `aura-core/.../evolution/EvolutionScheduler.kt` | 19–43 | Exists but `schedule()` never called |
| `aura-core/.../proactive/ProactiveBootstrap.kt` | — | Reads morning-brief prefs and schedules workers — evolution prefs ignored |

**Problem:** The Settings UI for evolution writes to `UserPreferences` DataStore, but no bootstrap code ever reads those prefs to start `EvolutionScheduler`. The stored values are dead data that will never be acted on.

**Fix:** Read evolution prefs in `ProactiveBootstrap` (or a new `EvolutionBootstrap`) and call `EvolutionScheduler.schedule()` / `cancel()` accordingly.

---

## Summary by Severity

| Severity | Count | Key Items |
|---|---|---|
| **P0** | 4 | MemoryViewModel test breakage, PolicyEngine dead code, TraceSink unwired, EvolutionScheduler never started |
| **P1** | 4 | Missing Migration 1→2, MCP unwired from tools, 4 god classes, 1 god module |
| **P2** | 3 (1 positive) | Fragile EvolutionHooks injection, SubagentManager not in main loop, dead evolution DataStore prefs |

**Total actionable findings: 8 (4 P0 + 4 P1)**

### Immediate action items
1. **Fix `MemoryViewModelTest`** — add `MemoryFeedbackDao` mock (blocking CI)
2. **Wire `PolicyEngine` into `ToolExecutor`** — stop duplicated logic
3. **Wire `TraceSink` into agent loop** — gain observability
4. **Start `EvolutionScheduler` from `AuraApp.onCreate()`** — evolution is dead UI without this
5. **Add `MIGRATION_1_2` to EvolutionModule migrations array** — prevents crash on upgrade
6. **Connect `McpClientManager` to `ToolRegistry`** — make MCP tools callable
7. **Split God classes** — start with `ChatViewModel` (hardest to test)
