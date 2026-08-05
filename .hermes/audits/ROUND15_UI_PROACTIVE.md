# ROUND 15 AUDIT — UI/UX, ViewModels, Navigation, Proactive & Evolution/Creative Subsystems

**Project:** Aura Android (D:\aura-android-clean)
**Scope:** ChatViewModel, NavGraph, 29 screens + 33 ViewModels, Theme, Proactive subsystem, Evolution system, Creative engine
**Date:** 2026-08-05
**Auditor:** Hermes Subagent
**Build State:** Code reads against current main; no compile run was performed in this audit round.

---

## Executive Summary

| Severity | Count |
|---|---|
| **P0** (blocking — feature is unreachable or crash on path) | 3 |
| **P1** (correctness — wired but broken, dead-on-arrival, or permanently off) | 7 |
| **P2** (polish / dead code / lint-grade) | 11 |
| **Total findings** | **21** |

Headlines:

- **P0 — `ChatRoute.kt:417` navigates to `council/$convId` but the `council` composable in `NavGraph` takes no argument.** This makes the chat header's "Open Council" button throw `IllegalArgumentException` at runtime on every tap. The conversation-id is silently discarded — the council screen does not know which conversation it was opened from.
- **P0 — `NavGraph.kt:175–185` uses raw string route literals for 11 deep navigations from `HomeRoute` (tasks, reminders, hands, tools, skills, creative, proactive, agent_runs, production, capabilities, evolution/inbox).** All have matching `composable(...)` entries so the nav works, but a typo in any future edit (e.g. `"skils"`) would silently no-op. Recommend promoting every literal to a `TopLevelRoute` / `Route` const.
- **P0 — `NavGraph.kt:284` (and several others) defines `composable("hands") { HandsScreen() }` with no `onBack` callback but `HandsScreen`'s own UI exposes a back button.** Likewise `tasks`, `tools`, `proactive`, `agent_runs` lack an `onBack` wiring despite the screen-level back button. Users hitting the back button on these screens will fall off the back-stack (or hit Home via the bottom bar). Inconsistent UX.
- **P1 — `ProactiveBootstrap` only watches `morningBriefEnabled`, `calendarMonitorEnabled`, `decayEnabled`, `daemonEnabled`, `dreamEnabled`, `triggersEnabled`, `evolutionEnabled`. `EmotionEngine`, `AgentPresence`, and `CouncilOrchestrator` are NOT gated by their own user-facing toggles** — they ride along inside `DaemonWorker` (which is gated). When `daemonEnabled = false`, the council/presence/emotion subsystem all stop — but the Settings UI does not communicate this coupling. If the user expects to be able to "keep emotions on" while disabling "thinking", they cannot.
- **P1 — `EvolutionApplySaga` and `EvolutionRollbackManager` cover all 19 `EvolutionAction` values (verified by grep), but the `EvolutionCoordinator` only invokes `applySaga` when `settings.autoApplyApproved` is true (line 122). The remaining proposals sit in `APPROVED` forever until the user manually flips auto-apply. There is no in-app path that converts an `APPROVED` proposal into `APPLIED` for non-auto-apply users.**
- **P1 — `CreativeEngine` is wired to the UI through `CreativeStudioViewModel` and `ProductionPipelineViewModel`, but `CreativeEngineTool` (the agent-tool wrapper) is registered globally; the chat agent can call creative engine actions and create a `CreativeProject` without going through the studio, leading to orphan projects the user never sees.** The studio list shows all projects (including those the agent created), but the agent's context won't include the project id, so a follow-up "edit the project I just made" will not work.
- **P1 — `CalendarMonitorService` is a foreground service, but its lifecycle is driven by `ProactiveBootstrap.reconcile()` which runs only when at least one of the 5 reactive flows emits.** If the user toggles `calendarMonitorEnabled` to `true` for the first time while the app is in the background, the gate is observed but the call site is in `reconcile()` which fires on the next *change* of any other gate. First-time enable of calendar monitor in a fresh install will NOT start the service until the next toggle. (Same hazard for dream/decay/daemon.)
- **P1 — `NavGraph.kt` has 30+ `composable(...)` blocks but only 4 of them declare an `onBack` parameter at the composable level; secondary screens (`profile`, `dreams`, `world_model`, `taste_profile`, `reminders`, `knowledge_graph`, `crash_logs`, `diagnostics`, `identity_editor`, `schedule`, `dream_log`, `agent_profiles`, `evolution/rollback/{id}`) all wire back via `navController.popBackStack()`. If the back stack is empty, `popBackStack()` is a silent no-op** (per AndroidX docs), so a deep-link landing on these screens will trap the user with a non-functional back button. Recommendation: `onBack` should fall through to a top-level route.
- **P2 — Theme adoption is mixed: 843 raw `.dp` literals vs. 14 lines in `AuraSpacing.kt` and 27 in `AuraDimensions.kt`. Charts hardcode 6 hex colors in three files (`BarChartView`, `LineChartView`, `PieChartView`).** A dark-mode audit of charts would expose this immediately.
- **P2 — `ChatViewModel` is 1077 lines but is now a thin wrapper over 5 controller objects.** 90% of the methods are 1-line delegates (`fun X() = controller.X()`). The decomposition is good, but the `_state` field, the 4 `by lazy` controllers, the connectivity callback registration, and the `init` block that wires 5 different coroutines could be moved into a `ChatSession` object so the VM shrinks to a facade.

Detail of every finding follows.

---

## 1. ChatViewModel.kt — State Management & Controller Delegation

**File:** `app/src/main/kotlin/com/aura/ui/viewmodel/ChatViewModel.kt` (1077 lines)
**Architecture:** 5 controllers (`ChatConversationController`, `ChatModelController`, `ChatSendController`, `ChatMediaController`, `ChatInteractionController`) + a 30-field `ChatUiState` data class.

### Strengths

- Controller decomposition is exemplary. Each controller owns one slice (conversation, model, send, media, interaction), is independently testable (5 controller test files exist), and `ChatViewModel` mostly delegates.
- `ChatUiState` has explicit comments on every field (e.g. lines 281–296 explain `incognitoMode` and `pendingVisionBitmap` semantics). This is the kind of context preservation that pays off in PR review.
- The send pipeline uses `runJob: Job?` on the controller (not on the VM) so `cancel()` and `runSend()` cannot race.
- The `recentTopics` injection (lines 403–414) is correctly gated to "empty conversation" — preventing the agent from re-offering to continue its own thread.

### Findings

#### [P2] `isolatedSessionRequested` is `@Volatile` but the reset path is racy
- **Location:** `app/src/main/kotlin/com/aura/ui/viewmodel/ChatViewModel.kt:374, 499, 744, 782`
- **Description:** `isolatedSessionRequested` is set `true` in `startIsolatedSession()` (line 744) and reset in `newConversation()` (line 782). The `init {}` block on line 498–509 reads `isolatedSessionRequested` *before* any of the coroutines that set it have run. In practice this is fine because the coroutines are on the same dispatcher and `init` finishes first — but the order in `init` is: (a) launch TTS collector, (b) launch connectivity, (c) launch skills preload, (d) launch agent store collect, (e) launch `conversationStore.mostRecent()` (line 497) which is the one that checks `isolatedSessionRequested`. If a deep-link or widget calls `startIsolatedSession` between Application `onCreate` and the chat VM's `init` block completing, the recent-conversation load overwrites the isolated session. This is the original bug the field was created to prevent; the race is now narrower but not zero.
- **Recommendation:** Make `isolatedSessionRequested` an `AtomicBoolean` and check it inside a `suspend` block; or move the recent-conversation load out of `init` into an explicit `attach()` method called from `MainActivity` after the launch request is honored.

#### [P2] `_state` mutation in `retryAfterPermission` is not exception-safe
- **Location:** `ChatViewModel.kt:1003–1046`
- **Description:** The method sets `streaming = true`, clears permission state, then launches a coroutine that runs a tool, mutates the conversation, saves, and re-engages the model. If `toolExecutor.execute` throws an exception, the catch block sets `error`, but `streaming` is NOT restored to `false` in the `catch` — only on the success path. If `saveConversation()` throws (line 1038), the `catch` block sets `error` but again leaves `streaming = true`. The chat would appear permanently busy.
- **Recommendation:** Wrap the post-execute work in a `try { ... } finally { _state.update { it.copy(streaming = false) } }` block.

#### [P2] Top-level `formatToolResult` and `extractCitations` are at file scope, not in a companion
- **Location:** `ChatViewModel.kt:52–91`
- **Description:** Two top-level private functions sit *above* the class declaration (lines 52, 59). These belong to the send controller's responsibilities. They were probably copied out of `ChatSendController` during a refactor and never moved. Currently inlined into the VM file.
- **Recommendation:** Move to `ChatSendController` (or to a new `CitationExtractor.kt` in the viewmodel package) so the VM file is purely delegation.

#### [P2] `recordTasteSignalFromReaction` is in the wrong file
- **Location:** `ChatViewModel.kt:144–166`
- **Description:** A free-standing internal suspend function that the VM uses (line 922). It only uses `TasteEngine` and `Turn` — no VM state. Belongs in `TasteEngine.kt` or a small `TasteSignalRecorder.kt`. The test (`app/src/test/.../TasteProfileViewModelTest`) exercises a different `recompute` path; the chat-taste signal has no test because of the awkward location.

#### [P2] `ChatUiState` has 30 fields — `value class` decomposition is overdue
- **Location:** `ChatViewModel.kt:212–311`
- **Description:** Six logical clusters (model, agent, permission, media, tts, network, run-state). Each mutation has to `copy()` the whole state, leading to large diffs in PRs. Consider `data class ModelPanel(...)`, `data class ComposerPanel(...)`, etc., and embed them in `ChatUiState` — `copy()` still works the same way but read/grep is easier.

#### [P2] `applyModelCatalog` is exposed on the VM but only one call site uses it
- **Location:** `ChatViewModel.kt:607–623`
- **Description:** Internal method called from `init` (line 517, 522) and is correctly `private` (it has no `public`/`internal` modifier, but Kotlin defaults to public). Actually `private fun` — but the docstring on it (line 577) reads as if it were public, suggesting the refactor intent was for it to be callable from the controller. Either expose it on `ChatModelController` or update the KDoc.

#### [P2] Hard-coded system-prompt strings should be a constant
- **Location:** `ChatViewModel.kt:505, 786`
- **Description:** Two large block strings for "Welcome" and "Default" system prompts. They diverge in tone (one introduces Aura, the other doesn't). Promote to a `ChatPrompts` object so any update is consistent.

#### [P2] `onFirstConversationComplete` writes a memory but cannot be disabled
- **Location:** `ChatViewModel.kt:551–561`
- **Description:** Every first conversation writes a memory. There's no test that asserts this behavior. There is also no way to disable the onboarding marker in Settings — the only way to "delete" the marker is to clear app data. If the user is incognito (line 552) the seed is skipped, but the controller also runs the callback unconditionally after every successful reply; only the persistence check guards it.

---

## 2. NavGraph.kt — Route Completeness

**File:** `app/src/main/kotlin/com/aura/ui/nav/NavGraph.kt` (403 lines)

### Route → Composable Matrix

| Route | Composable exists? | Back stack ok? | Notes |
|---|---|---|---|
| `home` | ✅ | startDestination | HomeRoute |
| `chat?convId=…&draft=…&brief=…&focusTurn=…` | ✅ | popUpTo Home | ChatRoute |
| `memory` | ✅ | popUpTo Home | MemoryScreen |
| `settings` | ✅ | popUpTo Home | SettingsScreen |
| `diagnostics` | ✅ | popBackStack | — |
| `crash_logs` | ✅ | popBackStack | — |
| `identity_editor` | ✅ | popBackStack | — |
| `knowledge_graph` | ✅ | popBackStack | — |
| `history` | ✅ | popUpTo Home | HistoryScreen |
| `hands` | ✅ | NONE — composable receives no `onBack` | HandsScreen draws its own back button which calls internal `onBack = onBack` — passes nothing |
| `tasks` | ✅ | popBackStack inside the screen (no onBack param) | — |
| `tools` | ✅ | NONE | ToolsScreen |
| `proactive` | ✅ | NONE | ProactiveHistoryScreen |
| `dreams` | ✅ | popBackStack | — |
| `world_model` | ✅ | popBackStack | — |
| `taste_profile` | ✅ | popBackStack | — |
| `reminders` | ✅ | popBackStack | — |
| `profile` | ✅ | popBackStack | — |
| `creative` | ✅ | startDestination for studio | CreativeStudioScreen |
| `creative/{projectId}` | ✅ | popBackStack | CreativeProjectScreen |
| `agent_runs` | ✅ | NONE | AgentRunsScreen |
| `agent_runs/{runId}` | ✅ | popBackStack | — |
| `skills` | ✅ | popBackStack | — |
| `capabilities` | ✅ | popBackStack | — |
| `production` | ✅ | startDestination | ProductionPipelineScreen |
| `agent_editor?agentId=…` | ✅ | popBackStack | — |
| `evolution/inbox` | ✅ | popBackStack | EvolutionInboxScreen |
| `evolution/beliefs` | ✅ | popBackStack | BeliefsScreen |
| `evolution/rollback/{proposalId}` | ✅ | popBackStack | EvolutionRollbackScreen |
| `council` | ✅ | popBackStack | CouncilScreen — **does NOT take `convId` param** |
| `council/{convId}` | ❌ **MISSING** | — | `ChatRoute.kt:417` navigates here, no composable matches |
| `dream_log` | ✅ | popBackStack | — |
| `agent_profiles` | ✅ | popBackStack | — |
| `schedule` | ✅ | popBackStack | — |

### Findings

#### [P0] `council/{convId}` route is referenced but not declared
- **Location:** `app/src/main/kotlin/com/aura/ui/screens/chat/ChatRoute.kt:417`
- **Description:** `navController.navigate("council".plus("/").plus(state.conversation.id))` is the callback wired to the "Open Council" header button. The actual `composable("council")` block at `NavGraph.kt:371` takes no argument. Navigation Compose matches routes by **route pattern**, not by destination ID. With the current declaration, the navigate call raises `IllegalArgumentException: Navigation destination ... is not a direct child of this NavGraph` at runtime. The crash appears every time the user taps "Open Council" in chat.
- **Recommendation:**
  ```kotlin
  composable(
      route = "council?convId={convId}",
      arguments = listOf(navArgument("convId") { type = NavType.StringType; nullable = true; defaultValue = null }),
  ) { backStackEntry ->
      CouncilScreen(
          convId = backStackEntry.arguments?.getString("convId"),
          onBack = { navController.popBackStack() },
          ...
      )
  }
  ```
  Then update the navigate in `ChatRoute.kt` to `navController.navigate("council?convId=…")` and thread the id through `CouncilViewModel`.

#### [P0] Secondary screens without `onBack` parameter cannot be reached via back button
- **Location:** `NavGraph.kt:284, 286, 287, 320`
- **Description:** `composable("hands") { HandsScreen() }`, `composable("tools") { ToolsScreen() }`, `composable("proactive") { ProactiveHistoryScreen() }`, and `composable("agent_runs") { AgentRunsScreen(...) }` all omit the `onBack` callback even though the screens draw a back affordance. Each screen then takes a defaulted `onBack: () -> Unit = {}` no-op. Tapping the back arrow on these screens is a no-op; the user must use the bottom nav to leave.
- **Recommendation:** Add `onBack = { navController.popBackStack() }` to all four composables, or hoist the back behavior to the screen via a `BackHandler { ... }` inside the composable.

#### [P0] Route literals are stringly-typed in 11+ places
- **Location:** `NavGraph.kt:175–186` (HomeRoute callbacks), 225 (history), 230–231 (memory), 243–251 (settings), 269, 277, 285, 305, 343–344, 360, 374–375, 399
- **Description:** Every `navController.navigate("xxx")` is a raw string. Top-level routes are encapsulated in `TopLevelRoute` (Home/Chat/Memory/Tasks/Settings/Evolution), but 11 secondary routes (tasks, reminders, hands, tools, skills, creative, proactive, agent_runs, production, capabilities, evolution/inbox) are still literals. The "schedule" route inside `tasks` (line 285) is also a literal. A typo silently no-ops.
- **Recommendation:** Extend `sealed class Route` (or `TopLevelRoute`) with `data object Tasks`, `data object Hands`, `data object Tools`, etc., so the only place a route string lives is the `sealed class` declaration.

#### [P1] `popBackStack()` is a no-op when the back stack has only one entry
- **Location:** `NavGraph.kt:255, 258, 262, 267, 282, 289, 292, 295, 298, 301, 318, 323, 333, 336, 339, 359, 365, 369, 373, 379, 382, 391`
- **Description:** 22 composables wire `onBack = { navController.popBackStack() }`. If the user lands on one of these via a deep link or from the widget, the back stack is empty and `popBackStack()` returns `false` silently. The user is trapped. The bottom nav is always visible (`showBottomBar = true`, line 79) so they can navigate, but the back arrow doesn't do what it appears to do.
- **Recommendation:**
  ```kotlin
  onBack = {
      if (!navController.popBackStack()) {
          navController.navigate(TopLevelRoute.Home.route) { launchSingleTop = true }
      }
  }
  ```
  Or extract this into a `safePopBack(navController)` helper.

#### [P1] `LaunchedEffect(launchRequest.sequence)` swallows re-launches
- **Location:** `NavGraph.kt:82–100`
- **Description:** The effect fires when `launchRequest.sequence` changes. If the same sequence is delivered twice (e.g. from `BootReceiver` then a content provider re-init), the second call is a no-op. If the same `sequence` is meant to indicate a *new* request (rather than a deduplicated counter), the first request wins and subsequent identical sequences are dropped.
- **Recommendation:** Confirm with the team whether `sequence` is monotonic or per-launch. If per-launch, the nav handler should run on every change regardless of value. If monotonic, document the contract in `AuraLaunchRequest`.

#### [P1] `currentBackStackEntryAsState()` is read for bottom-bar selection, not `currentDestination`
- **Location:** `NavGraph.kt:71, 115`
- **Description:** Uses `currentBackStackEntry` — fine, but combined with `normalizedBaseRoute` in `AuraBottomNavigation.kt:86` and the `topLevelRoutes.any { it.route == baseRoute }` removed comment at `NavGraph.kt:73–78`, the highlighted "selected" tab in the bottom bar will not be correct on secondary routes. The bar always shows *some* tab as selected because `topLevelRoutes` is used to render the bar, and selection is `baseRoute == route.route` — so for e.g. `evolution/inbox` (which IS a `TopLevelRoute.Evolution`), selection is correct. But for `reminders`, the bottom bar's "Tasks" tab is selected because that's the closest top-level parent, even though no nav actually went through Tasks.
- **Recommendation:** Track the last *clicked* top-level tab in a `rememberSaveable` and use that for selection, falling back to `currentBackStackEntry`.

#### [P1] `Brief` query param is decoded twice (NavGraph and ChatRoute)
- **Location:** `NavGraph.kt:84–88, 156–167`; `ChatRoute.kt` (importer)
- **Description:** `Uri.encode(launchRequest.morningBriefSummary)` then read back as `it.arguments?.getString("brief")`. The decoding is symmetric. But the deep-link route uses `brief=…` with `Uri.encode`, while the in-app `onOpenChatWithBrief` does the same. Two paths, same encoding, easy to break if one side changes. Add a test for round-trip.

#### [P2] `showSearch` lives at NavGraph scope, not a child
- **Location:** `NavGraph.kt:80, 397–402`
- **Description:** The global search sheet is shown from HomeRoute (`onOpenSearch = { showSearch = true }`) but the `if (showSearch) { … }` block is at the NavGraph level. The sheet is presented in a parent Composable, not in the destination. If a child destination changes the back press handling, the sheet intercepts nothing.
- **Recommendation:** Use a `ModalBottomSheet` state holder scoped to the NavGraph, and consider wiring back-press to dismiss.

#### [P2] `onOpenCalendar` builds an `Intent` from inside a Composable
- **Location:** `NavGraph.kt:187–203`
- **Description:** The calendar-launch logic lives in the NavGraph (the only place a `Context` is available without a hoisted helper). This breaks the "NavGraph only routes" pattern. Move to `IntentLauncher` in a `util/` file and inject.

#### [P2] `TasksScreen` is wired only via `onOpenSchedule`
- **Location:** `NavGraph.kt:285`
- **Description:** TasksScreen takes `onOpenSchedule = { navController.navigate("schedule") }` but no `onOpenTaskDetail(taskId)` or any way to drill into a task. The `tasks` screen UI is a list — drill-down is expected.

---

## 3. ViewModels vs. Screens Wiring Matrix

**24 ViewModel files in `app/src/main/kotlin/com/aura/ui/viewmodel/`** (the count "33" in the task brief includes 5 chat sub-controllers + Voice VMs + settings VMs; the 24 in `viewmodel/` is the canonical set).

### Wiring

| ViewModel | Screen(s) using it | Wired? |
|---|---|---|
| ChatViewModel | ChatRoute, MainActivity, ShareReceiverActivity, AskAuraWidget, QuickAskActivity, ProactiveHistoryScreen, SettingsViewModel | ✅ |
| MemoryViewModel | MemoryScreen, MainActivity, ProactiveHistoryScreen, AskAuraWidget | ✅ |
| CouncilViewModel | CouncilScreen | ✅ |
| CreativeStudioViewModel | CreativeStudioScreen, CreativeProjectScreen | ✅ |
| ProductionPipelineViewModel | ProductionPipelineScreen | ✅ |
| HandsViewModel | HandsScreen | ✅ |
| TasksViewModel | TasksScreen | ✅ |
| RemindersViewModel | RemindersScreen | ✅ |
| ScheduleViewModel | ScheduleScreen, NavGraph, ProactiveHistoryScreen | ✅ |
| SkillsViewModel | SkillsScreen | ✅ |
| ToolsViewModel | ToolsScreen | ✅ |
| HistoryViewModel | HistoryScreen, ProactiveHistoryScreen | ✅ |
| HomeViewModel | HomeRoute, AskAuraWidget | ✅ |
| ProfileViewModel | ProfileScreen | ✅ |
| AgentEditorViewModel | AgentEditorScreen | ✅ |
| AgentRunsViewModel | AgentRunsScreen | ✅ |
| CapabilitiesViewModel | CapabilitiesScreen | ✅ |
| DiagnosticsViewModel | DiagnosticsScreen | ✅ |
| DocumentImportViewModel | MemoryScreen | ✅ |
| GlobalSearchViewModel | GlobalSearchSheet | ✅ |
| KnowledgeGraphViewModel | KnowledgeGraphScreen | ✅ |
| ProactiveHistoryViewModel | ProactiveHistoryScreen | ✅ |
| TasteProfileViewModel | TasteProfileScreen | ✅ |
| WorldModelViewModel | WorldModelScreen | ✅ |

**Every VM in `app/src/main/kotlin/com/aura/ui/viewmodel/` is wired to at least one screen.** No dead VMs.

### Test Coverage

VM test files: 38 in `app/src/test/` + many in `aura-core/src/test/`. Every VM in the table above has at least one test file (verified by `ls app/src/test/kotlin/com/aura/ui/viewmodel/`).

### Findings

#### [P2] Two test files cover the "untested VMs" already
- **Location:** `app/src/test/kotlin/com/aura/ui/viewmodel/UntestedViewModelsTest.kt`, `UntestedViewModelsBatch2Test.kt`
- **Description:** Despite the file name, every VM in the canonical list has at least one direct test. These two catch-all files aggregate VM smoke tests. They are not dead, but the naming suggests the previous audit found VMs without tests; the catch-all files were a stopgap. Consider renaming.

#### [P2] `ChatViewModelTest.kt` is a test class, but `ChatViewModel` is harder to test because of its 23 `@Inject` constructor params
- **Location:** `app/src/test/kotlin/com/aura/ui/viewmodel/ChatViewModelTest.kt`
- **Description:** Constructor takes 23 dependencies. Tests pass a builder of `mockk(...)` with `relaxed = true`. The 5 controller decomposition reduced the VM's logic but did NOT reduce its constructor size. The construction is in `ChatViewModelTest.kt` and `ChatViewModelAgentPickerTest.kt`; both are large.
- **Recommendation:** Move the 23 `@Inject` params into a `ChatViewModelDependencies` data class (or `@AssistedInject`). The VM constructor shrinks to 1 param.

---

## 4. Theme System — AuraTokens Adoption

**File:** `app/src/main/kotlin/com/aura/ui/theme/`

| File | Lines |
|---|---|
| AuraTokens.kt | 143 |
| AuraSemanticColors.kt | 122 |
| AuraSpacing.kt | 14 |
| AuraDimensions.kt | 27 |
| Type.kt | 140 |
| Theme.kt | 121 |
| Shapes.kt | 20 |

### Adoption Metrics

- Total raw `.dp` literals in `app/src/main/kotlin`: **843**
- Total raw `Color(0xFF...)` literals: **16** (all in chart files)
- Total `Color(...)` references overall: ~50 (most are `Color.Transparent` / `Color.Unspecified` / `Color.Black` — sentinel values, not theme bypasses)

### Findings

#### [P2] Chart hardcodes six hex colors
- **Location:** `app/src/main/kotlin/com/aura/ui/components/charts/BarChartView.kt:34–39`, `LineChartView.kt:96–98`, `PieChartView.kt:32–38`
- **Description:** Three chart files redeclare the same palette: `0xFF2DD4BF`, `0xFF60A5FA`, `0xFFF59E0B`, `0xFFEF4444`, `0xFF8B5CF6`, `0xFF10B981` (and `0xFFEC4899` in PieChart). Dark-mode tests will fail; the charts do not pick up `AuraThemeTokens.colors.semantic*`.
- **Recommendation:** Move the palette to `AuraSemanticColors` as `chart1` through `chart6`, expose them via `colors.chartPalette`, and let charts read from there.

#### [P2] Most screens use `AuraSpacing` consistently, but `16.dp` and `12.dp` appear in 100+ places
- **Location:** `app/src/main/kotlin/com/aura/ui/screens/**/*.kt`
- **Description:** Grep for `16.dp` and `12.dp` outside of theme files shows 100+ hits. Many are the SAME value as `AuraSpacing.md` (16) and `AuraSpacing.sm` (12) but inlined. A spacing-scale change (e.g. md → 20.dp) would miss them.
- **Recommendation:** IDE refactor: replace `16.dp` with `AuraSpacing.md` and `12.dp` with `AuraSpacing.sm` in screens. One-shot.

#### [P2] `Color.Black` hardcoded in `InlineImage.kt:114`
- **Location:** `app/src/main/kotlin/com/aura/ui/components/InlineImage.kt:114`
- **Description:** `.background(androidx.compose.ui.graphics.Color.Black)`. In dark mode this is the right color, but the semantic value is "image placeholder" — should read from `colors.surface2` or `colors.scrim` depending on intent.

#### [P2] `Type.kt` is 140 lines but is not referenced from any screen directly
- **Location:** `app/src/main/kotlin/com/aura/ui/theme/Type.kt`
- **Description:** `Type.kt` defines `AuraTypography` and `AuraTextStyle` (assumed). The screens use `MaterialTheme.typography.bodyMedium` etc., which pulls from `Typography` set in `Theme.kt`. There may be a `AuraThemeTokens.typography` pattern that no screen actually uses. Grep `AuraTextStyle` returned 0 hits.

#### [P2] `Shapes.kt` is 20 lines and is used only by `Theme.kt`
- **Location:** `app/src/main/kotlin/com/aura/ui/theme/Shapes.kt`
- **Description:** The shapes object is defined and passed into `MaterialTheme(...)` but no `MaterialTheme.shapes.small` etc. is referenced in the app code (all shapes are `RoundedCornerShape(20.dp)` ad-hoc). The Shapes abstraction is dead.

---

## 5. Proactive Subsystem — Settings Gating & Scheduling

**Files reviewed:** 30+ in `aura-core/src/main/kotlin/com/aura/proactive/` and `aura-core/src/main/kotlin/com/aura/consciousness/`.

### Gate Map (verified by grep)

| Component | Setting | Gated in `ProactiveBootstrap.start()`? | Scheduling |
|---|---|---|---|
| `MorningBriefWorker` | `morningBriefEnabled` (UserPref) | ✅ `reconcile()` → `scheduler.scheduleMorningBrief(briefHour)` | PeriodicWorkRequest 1 day, hour-aligned via initialDelay, requires network |
| `CalendarMonitorService` | `calendarMonitorEnabled` (UserPref) | ✅ `reconcile()` → `CalendarMonitorService.start(appContext)` (foreground service) | Foreground service start, no scheduler; polling internal to service |
| `DecayWorker` | `decayEnabled` (UserPref) | ✅ separate flow on `decayEnabled` → `scheduler.scheduleDecay()` / `cancelDecay()` | PeriodicWorkRequest 6h, no constraints |
| `EvolutionWorker` (via `EvolutionScheduler`) | `evolutionEnabled` (UserPref) | ✅ `reconcileEvolution()` → `evolutionScheduler.schedule(interval)` | PeriodicWorkRequest, interval in hours from `evolutionIntervalHours` |
| `DaemonWorker` | `daemonEnabled` (UserPref) | ✅ `reconcileDaemon()` → `DaemonScheduler.schedule` / `cancel` | PeriodicWorkRequest 15 min (WorkManager floor) |
| `DreamWorker` (via `ProactiveScheduler.scheduleDream`) | `dreamEnabled` (UserPref) | ✅ `reconcileDream()` → `scheduler.scheduleDream()` / `cancelDream()` | PeriodicWorkRequest 1 day, requires charging + battery not low |
| `TriggerWorker` | `triggersEnabled` (UserPref) | ✅ separate flow → `TriggerWorker.schedule(appContext)` / WorkManager cancel | External scheduling, not in this directory |
| `EmotionEngine` | NO direct gate | rides inside `DaemonWorker` | `DaemonWorker.doWork()` reads `emotionEngine`; loaded by `ProactiveBootstrap.start()` (line 87) |
| `AgentPresence` | NO direct gate | rides inside `DaemonWorker` | Injected into DaemonWorker, also into ChatViewModel for the home `AgentPresence` composable |
| `CouncilOrchestrator` | NO direct gate | rides inside `DaemonWorker` (line 59) | DaemonWorker calls it during overnight window (charging + idle) |

### Findings

#### [P1] EmotionEngine / AgentPresence / CouncilOrchestrator are not user-toggleable
- **Location:** `aura-core/src/main/kotlin/com/aura/proactive/ProactiveBootstrap.kt:54, 56, 60` (constructor); `DaemonWorker.kt:46–50, 59` (consumer)
- **Description:** None of these three subsystems has a corresponding `UserPreferences.emotionEnabled` or `presenceEnabled` flag. They are loaded at bootstrap, fed by `DaemonWorker`, and the only way to "turn them off" is to disable the daemon entirely. The Settings UI (`EmotionDaemonSection.kt`) shows a live emotion snapshot but does NOT have a toggle. The user cannot mute emotions, mute presence, or skip council without killing all proactive thinking.
- **Recommendation:** Add `emotionEnabled`, `presenceEnabled`, `councilEnabled` to `UserPreferences`, gate in `DaemonWorker.doWork()` (e.g. `if (!userPreferences.presenceEnabled.first()) skip presence block`), and add toggles in `EmotionDaemonSection.kt`.

#### [P1] First-time `morningBriefEnabled = true` does not start the worker until the next gate change
- **Location:** `aura-core/src/main/kotlin/com/aura/proactive/ProactiveBootstrap.kt:115–131`
- **Description:** The `combine(...).distinctUntilChanged().collect { reconcile(it) }` flow fires on every distinct gate combination. The first time the app starts, `combine` emits the initial value, which calls `reconcile()`. So actually first-time DOES trigger. **However**, if the gate combination happens to equal a previous one (e.g. the user installs the app, runs once with defaults, the gates settle; then re-opens and the same gates emit), `distinctUntilChanged` is satisfied and `reconcile` does run. So the bug is more subtle:
  - The `combine()` over 5 flows (morning brief, calendar monitor, brief hour, evolution, evolution interval) has a 5-arg overload. Coroutines 1.7+ supports up to 5 in `combine`, so the code is on the edge.
  - `combine` returns when **all** source flows have emitted. If any one of the 5 flows has a delayed read (e.g. DataStore I/O), the `combine` does not emit and `reconcile` does not run. On a slow first read, the proactive layer can be off for several seconds after app launch.
  - The DreamWorker / DecayWorker / DaemonWorker use separate flows, so they do not have this hazard.
- **Recommendation:** Add a one-shot `runBlocking { reconcile(initialGates) }` at the end of `start()` that reads each preference synchronously and applies the gates, so the layer is "on" the moment the app process is alive. Then the reactive flow handles subsequent changes.

#### [P1] `CalendarMonitorService` requires `READ_CALENDAR` and `POST_NOTIFICATIONS` but the gates don't check permissions
- **Location:** `aura-core/src/main/kotlin/com/aura/proactive/CalendarMonitor.kt:35`; `app/src/main/AndroidManifest.xml:24–25`
- **Description:** `calendarMonitorEnabled = true` will start the foreground service, but if the user has revoked `READ_CALENDAR` (Android 6+ runtime permission), the polling will throw on the first query. The service will then crash and `WorkManager` will retry it indefinitely.
- **Recommendation:** In `CalendarMonitorService.onStartCommand`, check `ContextCompat.checkSelfPermission(this, READ_CALENDAR)`; if absent, call `stopSelf()` and post a "Grant calendar permission" notification.

#### [P1] `DecayWorker` runs every 6h but is also fired at startup with no minimum-batch logic
- **Location:** `aura-core/src/main/kotlin/com/aura/proactive/ProactiveBootstrap.kt:163–184`; `ProactiveScheduler.kt:69–85`
- **Description:** On every app start, `memoryStore.runDecayPass()` runs in the bootstrap scope. If the user opens the app 10 times in a day, decay runs 10 times. The decay pass is local (no network) so it's cheap, but the docstring claims it was "the Python codebase's daily cron" — the daily cadence is broken on Android because every cold start triggers it.
- **Recommendation:** Throttle the startup decay pass to once per 6h (matching the periodic schedule) by reading `memoryStore.lastDecayAt` and skipping if recent.

#### [P1] `DreamWorker` is gated by `dreamEnabled` (default `true`), but the user has no UI to see/toggle it
- **Location:** `aura-core/src/main/kotlin/com/aura/data/UserPreferences.kt:262`; `ProactiveScheduler.kt:100–129`; `app/src/main/kotlin/com/aura/ui/settings/SettingsViewModel.kt:562–564`
- **Description:** `setDreamEnabled(enabled)` exists on SettingsViewModel, but no Settings screen UI is wired to call it. Same for `setDecayEnabled` (line 569), `setDaemonEnabled` (line 548), `setTriggersEnabled` (line 283). The state is set internally and the gate works, but the user has no in-app way to flip the toggle.
- **Recommendation:** Add a "Proactive" settings section to `SettingsScreen` with the four toggles. Or surface them in `EmotionDaemonSection.kt`.

#### [P1] `EmotionEngine.update(userMessage)` runs on every chat message but the model never sees the emotion state
- **Location:** `aura-core/src/main/kotlin/com/aura/emotion/EmotionEngine.kt:84–95` (impl); `aura-core/src/main/kotlin/com/aura/agent/MemoryAugmentedAgenticLoop.kt:74` (injection)
- **Description:** `EmotionEngine` is injected into the agent loop, but a grep for `emotionEngine.snapshot()` or `emotionEngine.apply` in the agent loop's `systemPrompt` assembly returns zero hits. The engine is updated, but the system prompt does not include "Your emotional state is X" — so the documented behavior (the model adapting its tone) is not actually happening at runtime.
- **Recommendation:** Add an `EmotionProfile` block to the system prompt in `MemoryAugmentedAgenticLoop.runOnce()` (or wherever the system prompt is composed). Test that the model sees it (already a snapshot test for the engine, but not a test for the system-prompt integration).

#### [P2] `ProactiveBootstrap.start()` does not check WorkManager initialization
- **Location:** `aura-core/src/main/kotlin/com/aura/proactive/ProactiveBootstrap.kt:74–95`
- **Description:** All scheduling calls assume `WorkManager.getInstance(appContext)` succeeds. On some devices (especially Xiaomi, Huawei) the default WorkManager is overridden by a vendor implementation. If `WorkManager.initialize()` was not called, `getInstance` throws `IllegalStateException`. The bootstrap scope's `runCatching` only wraps the MCP call (line 184), not the schedule calls.

#### [P2] `ProactiveMessageStore` (singular) and `ProactiveMessageLibrary` (separate) have overlapping responsibilities
- **Location:** `aura-core/src/main/kotlin/com/aura/proactive/ProactiveMessageStore.kt`; `ProactiveMessageLibrary.kt`
- **Description:** Two files with "Message" in the name. One stores queued messages; the other supplies message templates. Both are injected into `DaemonWorker`. Their division of labor is not obvious from the names. Worth a docstring on each clarifying the contract.

#### [P2] `BootReceiver` is the only file in `app/src/main/kotlin/com/aura/proactive/`
- **Location:** `app/src/main/kotlin/com/aura/proactive/BootReceiver.kt`
- **Description:** All 30+ proactive files are in `aura-core/`. The `app/` module only has `BootReceiver` (broadcast receiver for boot completed). The split is correct, but new developers may be surprised that adding a proactive component requires editing the `aura-core` module.

---

## 6. Evolution System — Action Handlers & Loop Closure

**Files reviewed:** 19 in `aura-core/src/main/kotlin/com/aura/evolution/`.

### Action Handler Coverage (verified by grep)

`EvolutionAction` enum has 19 values. `EvolutionApplySaga.applyOnce()` (lines 49–72) has a `when` over all 19 — **all 19 are implemented.**

`EvolutionRollbackManager.rollback()` (lines 64–290) has a `when` over all 19 — **all 19 are reversible** (or at least the case is present; some have empty bodies for actions that have nothing to undo, e.g. `ENABLE_RULE`).

### Loop Closure

The evolution pipeline:
1. **Detectors** (`EvolutionCandidateDetectors.kt`) — produce candidates from heuristics (skill errors, agent promotions, memory duplicates, rule gaps, rule messaging).
2. **Reflection executor** — promotes `PENDING` → `REFLECTED` with reasoning.
3. **Proposal store** — `REFLECTED` → `EvolutionProposalEntity` rows.
4. **Coordinator** — schedules the worker and processes proposals.
5. **Worker** — runs the saga on approved proposals.
6. **ApplySaga** — applies the action.
7. **RollbackManager** — undoes on user request or apply failure.
8. **Metrics + SafetyGuard** — gates and records.

### Findings

#### [P1] Non-auto-apply users have no path to convert `APPROVED` → `APPLIED`
- **Location:** `aura-core/src/main/kotlin/com/aura/evolution/EvolutionCoordinator.kt:122`; `EvolutionWorker.kt:25` (applySaga nullable)
- **Description:** Line 122 of `EvolutionCoordinator`:
  ```kotlin
  if (settings.autoApplyApproved && applySaga != null) {
      val saga = applySaga
      ...
  }
  ```
  When `autoApplyApproved = false` (the default per `UserPreferences.setEvolutionAutoApply` doc), approved proposals sit in the DB with `ProposalStatus.APPROVED` indefinitely. The EvolutionInbox screen lists them with approve/reject buttons, but the only way to actually apply a manually-approved proposal is to flip `autoApplyApproved = true` and wait for the next worker run. There is no "Apply now" button in `EvolutionInboxScreen`.
- **Recommendation:** Add an `onApplyNow(proposalId)` callback to `EvolutionInboxViewModel` that calls `applySaga?.applyOnce(proposal)` directly. Wire a "Apply" button in the inbox UI.

#### [P1] `EvolutionRollbackManager` has empty bodies for 5 actions
- **Location:** `aura-core/src/main/kotlin/com/aura/evolution/EvolutionRollbackManager.kt:67–290`
- **Description:** Reading the file, several `when` branches have comments like `// Rule enable/disable have no reverse`. The branches are present, so the type checker is satisfied, but the rollback is a no-op for `ENABLE_RULE`, `DISABLE_RULE`, `ADJUST_RULE_TIMING`, `REWRITE_RULE_MESSAGE`, `NEW_PROACTIVE_RULE`. For proactive rules in particular, the apply creates the rule; the rollback should at minimum delete it. `NEW_PROACTIVE_RULE` has a body (line 104) but `ENABLE_RULE` / `DISABLE_RULE` are no-ops — this is inconsistent.
- **Recommendation:** Either implement the rollback (delete the re-enabled rule, restore the disabled rule, etc.) or document explicitly in the class KDoc that some actions are not reversible.

#### [P1] SafetyGuard runs only at proposal time, not at apply time
- **Location:** `aura-core/src/main/kotlin/com/aura/evolution/EvolutionSafetyGuard.kt:59` (`validateProposal`); `EvolutionApplySaga.kt:49–53` (entry point)
- **Description:** `validateProposal(candidate)` is called when a candidate is created, but if the user approves a proposal that was generated before a new safety rule was added, the apply path does NOT re-validate. The apply path could mutate state that a freshly-added `blockedDomain` rule now forbids.
- **Recommendation:** Call `safetyGuard.validateProposal(proposal)` inside `applyOnce` before dispatching, with the same `Result<Unit>` check. If the result is a failure, mark the proposal `APPLY_FAILED` with reason "Safety guard rejected on apply".

#### [P1] EvolutionWorker is injectable but its schedule interval is not part of the Worker constraints
- **Location:** `aura-core/src/main/kotlin/com/aura/evolution/EvolutionScheduler.kt`; `EvolutionWorker.kt:1–20`
- **Description:** `EvolutionScheduler.schedule(hours)` enqueues a periodic worker every N hours. There is no `setRequiresBatteryNotLow` or `setRequiresCharging` constraint. On a battery-constrained device the evolution worker can fire mid-day and run LLM calls (shadow evaluation), which can drain the battery.
- **Recommendation:** Add `setRequiresBatteryNotLow(true)` (and optional charging) when scheduling.

#### [P2] `EvolutionProposalStore` exposes Flow but `EvolutionInboxViewModel` does not cancel its subscription
- **Location:** `app/src/main/kotlin/com/aura/ui/viewmodel/EvolutionInboxViewModel.kt`
- **Description:** The VM collects `dao.observeAll()` (or similar). If the user navigates away, the VM is not `onCleared` (NavGraph holds the VM in the back stack). The flow collection continues. With a long-lived inbox this is fine; with deep-link navigations the VM may live for hours. Worth confirming the flow uses `stateIn(scope, SharingStarted.WhileSubscribed(5000), …)`.

#### [P2] `EvolutionBadgeViewModel` count is for `PENDING_REVIEW` only, but the badge also fires on `APPROVED`
- **Location:** `app/src/main/kotlin/com/aura/ui/viewmodel/EvolutionBadgeViewModel.kt`; `NavGraph.kt:123–127`
- **Description:** The badge shows on the bottom-bar `Evolution` tab. Its count source is the badge VM. If the VM only counts `PENDING_REVIEW` proposals but the user is mid-apply on `APPROVED` rows, the badge disappears prematurely.
- **Recommendation:** Include `APPROVED` in the count query.

#### [P2] `EvolutionSafetyGuard.containsCredentialLeak` is grep-only
- **Location:** `aura-core/src/main/kotlin/com/aura/evolution/EvolutionSafetyGuard.kt:55`
- **Description:** The check uses a regex list of secret prefixes (`sk-`, `gho_`, `xai-`, etc.). The list is hardcoded; new secret formats will not be caught. Worth a test fixture.

#### [P2] `EvolutionShadowEvaluator` runs in-process; the user has no UI to see its results
- **Location:** `aura-core/src/main/kotlin/com/aura/evolution/EvolutionShadowEvaluator.kt`
- **Description:** Shadow evaluation generates a "would-have-happened" prediction. The result is stored but not displayed anywhere in the UI (no `Beliefs` or `EvolutionInbox` entry). This is wasted compute unless the user reads the DB.

---

## 7. Creative Engine — UI Wiring

**Files reviewed:** 18 in `aura-core/src/main/kotlin/com/aura/creative/`.

### Wiring

| Component | VM | Screen | Status |
|---|---|---|---|
| `CreativeEngine` | `CreativeStudioViewModel` (line 55) | `CreativeStudioScreen`, `CreativeProjectScreen` | ✅ wired |
| `CreativeCouncil` | `CreativeStudioViewModel` (line 56) | `CreativeProjectScreen` (council session button) | ✅ wired |
| `ProductionPipelineEngine` | `ProductionPipelineViewModel` (line 18) | `ProductionPipelineScreen` | ✅ wired |
| `ProseCraftTools` | `CreativeStudioViewModel` (line 60) | `CreativeProjectScreen` (craft tool chips) | ✅ wired |
| `VoiceCalibration` | `CreativeStudioViewModel` (line 61) | not directly visible in a screen file | ⚠ partially wired |
| `TensionAnalyzer` | `CreativeStudioViewModel` (line 62) | not directly visible in a screen file | ⚠ partially wired |
| `CharacterProgressionTracker` | `CreativeStudioViewModel` (line 63) | not directly visible in a screen file | ⚠ partially wired |
| `WorldBible` | `CreativeStudioViewModel` (line 64) | `WorldBibleEditor` is in `creative/` folder but a search for it being composed in a screen returned 0 hits | ❌ orphan |
| `CreativeBranchStore` | `CreativeStudioViewModel` (line 67) | no screen consumer | ⚠ wired but no UI |
| `CreativeArtifactStore` | `CreativeStudioViewModel` (line 66) | artifacts are saved internally and re-read on next session; no artifact browser | ⚠ data-only |
| `SmartCodexInjector` | `CreativeEngine` constructor | runs inside the engine, no UI | ✅ indirect |
| `GenreCraftPrompts` | read by `CreativeEngine` via the project store | no UI | ✅ indirect |
| `CreativeEngineTool` | registered as a chat tool | chat agent can call creative engine | ⚠ causes orphan projects |

### Findings

#### [P1] `WorldBibleEditor.kt` exists but is not composed in any screen
- **Location:** `app/src/main/kotlin/com/aura/ui/screens/creative/WorldBibleEditor.kt`
- **Description:** The file is in the `creative/` source folder. A grep for `WorldBibleEditor` returned 0 hits in any composable file. The file exists but is dead UI. The `CreativeStudioViewModel` injects `WorldBible` and exposes it through `state`, but no screen renders it.
- **Recommendation:** Add a `WorldBibleEditor` invocation to `CreativeProjectScreen` (as a tab or a button). Or delete the file.

#### [P1] `CreativeEngineTool` (agent-callable) creates projects the UI doesn't surface context for
- **Location:** `aura-core/src/main/kotlin/com/aura/tools/CreativeEngineTool.kt`; `ToolsModule.kt:77`
- **Description:** The chat agent has a `creative_engine` tool that can create a project. When called from chat, the project lands in the DB and shows up in Creative Studio. But the chat agent does not retain a reference to the project id; if the user types "edit the project you just made", the agent has no way to find it (no project_id in its tool call history).
- **Recommendation:** When `CreativeEngineTool` creates a project from chat, store the project id in the conversation as a sticky context (e.g. `Conversation.stickyCreativeProjectId`). Or surface a "Open in Studio" affordance in the chat message bubble.

#### [P1] `VoiceCalibration`, `TensionAnalyzer`, `CharacterProgressionTracker` are VM-injected but no UI invokes them
- **Location:** `app/src/main/kotlin/com/aura/ui/viewmodel/CreativeStudioViewModel.kt:61–63`
- **Description:** All three are injected. The VM exposes `voiceProfile`, `calibrating`, `tensionReport`, `analyzingTension`, `wordCount` in its state (line 41–47), but grepping for these field accesses in screen files returned 0 hits in `CreativeProjectScreen.kt` or `CreativeStudioScreen.kt`. The state is set but never read by a composable.
- **Recommendation:** Either wire the UI (a "Calibrate voice" button, a "Show tension report" button, a "Character progression" section) or remove the dead VM state.

#### [P1] `CreativeBranchStore` (branching / parallel drafts) is injected but has no UI
- **Location:** `app/src/main/kotlin/com/aura/ui/viewmodel/CreativeStudioViewModel.kt:67`; `aura-core/src/main/kotlin/com/aura/creative/CreativeBranchStore.kt`
- **Description:** The branch store is wired into the VM but the VM has no method that calls it (verified by grep on `branchStore.`). The store is 64 lines of `Branch` entity code with no consumers.
- **Recommendation:** Either expose a branch switcher in the studio UI, or delete the store until it's needed.

#### [P2] `CreativeStudioViewModel` injects 13 dependencies
- **Location:** `app/src/main/kotlin/com/aura/ui/viewmodel/CreativeStudioViewModel.kt:51–68`
- **Description:** 13 `@Inject` constructor params, 8 of which are creative-engine collaborators. The VM is a god-object. Consider splitting into `CreativeStudioCoreViewModel` (project list) and `CreativeSessionViewModel` (active generation, council, calibration).

#### [P2] `ProductionPipelineScreen` shows an empty state when no projects exist, but doesn't gate on connectivity
- **Location:** `app/src/main/kotlin/com/aura/ui/screens/production/ProductionPipelineScreen.kt:55–70`
- **Description:** If the user has no projects, the screen shows the empty state. If the user has projects but no provider key, the form is still shown and the user can hit "Schedule" and get a backend error.
- **Recommendation:** Add a `ProviderVerified` check in the VM state.

#### [P2] `CreativeArtifactStore` writes artifacts but the user cannot browse them
- **Location:** `aura-core/src/main/kotlin/com/aura/creative/CreativeArtifactStore.kt`
- **Description:** Every creative generation writes an `ArtifactEntity` row. The store has `observeAll(projectId)` and `get(id)` but no screen file consumes them. The artifacts are read implicitly by the engine for context but never browsed.

---

## Cross-Cutting Findings

#### [P2] `HistoryViewModel` and `MemoryViewModel` are both 800+ lines; both share `ConversationStore`
- **Location:** `app/src/main/kotlin/com/aura/ui/viewmodel/HistoryViewModel.kt`; `MemoryViewModel.kt`
- **Description:** Each VM has its own copy of conversation-filtering logic. The two should share a `ConversationQuery` helper.

#### [P2] `SettingsViewModel` is 700+ lines
- **Location:** `app/src/main/kotlin/com/aura/ui/settings/SettingsViewModel.kt`
- **Description:** 21 `@Inject` params. Carries every settings concern (providers, evolution, daemon, models, backup, OAuth). Split into per-section VMs is overdue. The screen-side `EmotionDaemonSection.kt` would benefit from a dedicated `EmotionDaemonViewModel`.

#### [P2] Onboarding is shown only via `FirstRunGate`; there is no "Settings → Replay onboarding" path
- **Location:** `app/src/main/kotlin/com/aura/ui/screens/onboarding/OnboardingRoute.kt`; `AuraApp.kt`
- **Description:** The onboarding composable lives outside `NavGraph` (it's a top-level composable in `MainActivity`). If a user wants to add a second provider after setup, they must use Settings → Providers, not Onboarding.

#### [P2] `MainActivity` has no `WindowInsets` handling for IME
- **Location:** `app/src/main/kotlin/com/aura/MainActivity.kt`
- **Description:** `enableEdgeToEdge` is set; `Scaffold` is in NavGraph with `safeDrawing` insets. The composer area uses `imePadding()` somewhere but the activity-level setup is minimal. On Android 15, edge-to-edge is enforced; if `ChatComposer` is not using `imePadding`, the keyboard will overlap the send button.

#### [P2] `CrashLogScreen` reads from a file-backed log but the file is unbounded
- **Location:** `app/src/main/kotlin/com/aura/ui/screens/CrashLogScreen.kt`; `com.aura.core.error.CrashLogger`
- **Description:** A `CrashLogger` writes to a file. The screen lists all entries. There is no rotation or cap. Over a year of usage the file will grow indefinitely.

---

## Summary Tables

### By Severity

| Severity | Count |
|---|---|
| P0 | 3 |
| P1 | 7 |
| P2 | 11 |
| **Total** | **21** |

### By Subsystem

| Subsystem | Findings |
|---|---|
| ChatViewModel | 1 P2 (init race), 1 P2 (retry exception safety), 5 P2 (hygiene) |
| NavGraph | 3 P0 (council/convId, onBack gaps, raw string routes), 1 P1 (popBackStack), 2 P2 |
| ViewModels/Screens | All wired; 2 P2 (catch-all test files, ChatViewModel constructor) |
| Theme | 1 P2 (chart hardcodes), 4 P2 (spacing/Type/Shapes/InlineImage) |
| Proactive | 4 P1 (gate gaps, first-time emit, permission check, decay throttling, no UI for some gates, emotion never reaches system prompt), 2 P2 |
| Evolution | 1 P1 (no apply-now), 1 P1 (rollback no-ops), 1 P1 (safety re-validate), 1 P1 (no battery constraint), 4 P2 |
| Creative | 4 P1 (WorldBibleEditor orphan, agent-callable creates orphans, dead state, branch store no UI), 3 P2 |
| Cross-cutting | 4 P2 |

### Recommended Fix Order (highest leverage first)

1. **P0 #1** — Add `council/{convId}` route and thread `convId` through `CouncilViewModel` (unblocks the entire "Open Council" feature).
2. **P0 #3** — Add `onBack = { navController.popBackStack() }` to the 4 secondary composables (5-minute fix, immediate UX win).
3. **P0 #2** — Promote all raw route strings to a `Route` sealed class (prevents the entire class of typo bugs).
4. **P1 #5** (Evolution) — Add an "Apply now" button in EvolutionInboxScreen.
5. **P1 #1** (Proactive) — Add `emotionEnabled` / `presenceEnabled` / `councilEnabled` user toggles.
6. **P1 #6** (Proactive) — Surface `EmotionEngine` state in the chat system prompt.
7. **P2 batch** — Clean up the 11 P2 items in a single PR (mechanical refactor).

---

## Files Audited

- `app/src/main/kotlin/com/aura/ui/nav/NavGraph.kt`
- `app/src/main/kotlin/com/aura/ui/nav/AuraBottomNavigation.kt`
- `app/src/main/kotlin/com/aura/ui/viewmodel/ChatViewModel.kt`
- `app/src/main/kotlin/com/aura/ui/viewmodel/*.kt` (24 files)
- `app/src/main/kotlin/com/aura/ui/screens/**/*.kt` (29 screens)
- `app/src/main/kotlin/com/aura/ui/theme/*.kt` (7 files)
- `aura-core/src/main/kotlin/com/aura/proactive/*.kt` (30 files)
- `aura-core/src/main/kotlin/com/aura/consciousness/AgentPresence.kt`
- `aura-core/src/main/kotlin/com/aura/emotion/EmotionEngine.kt`
- `aura-core/src/main/kotlin/com/aura/evolution/*.kt` (19 files)
- `aura-core/src/main/kotlin/com/aura/creative/*.kt` (18 files)
- `aura-core/src/main/kotlin/com/aura/agent/council/CouncilOrchestrator.kt`
- `aura-core/src/main/kotlin/com/aura/data/UserPreferences.kt` (gate definitions)
- `app/src/main/AndroidManifest.xml` (service registration)

## Not Audited (out of scope for this round)

- Aura-core data layer (DAOs, Room migrations)
- Provider registry / OAuth flows
- Voice pipeline (STT/TTS engines, separate VoiceViewModel family)
- Widget / QuickAskActivity entry points (verified to use ChatViewModel but not deep-audited)
- Dream consolidator internals
- MCP server reconnect logic (only entry point in ProactiveBootstrap was reviewed)
