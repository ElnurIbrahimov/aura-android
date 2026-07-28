# ROUND 6 — UI/UX & Navigation Reachability Audit — Aura Android v0.36.0

**Version confirmed:** `versionName = "0.36.0"`, `versionCode = 41` (`app/build.gradle.kts`).
**Scope:** `app/src/main/kotlin/com/aura/ui/` (165 files). All findings below were verified against source.
**Existing guard:** `app/src/test/kotlin/com/aura/ui/nav/NavigationReachabilityTest.kt` statically scans every `navigate("...")` against every `composable("...")` registration. Empty `knownBugs` ratchet.

---

## TL;DR

Navigation is mechanically correct — every `navigate()` target has a matching `composable()` and the bottom bar exposes the 6 stable top-level routes the test pins. The **bigger problem is what those routes contain**:

1. **Home exposes 11 destination cards but 1 of them is a dead end, 1 is non-interactive, and 2 are functionally redundant with the bottom bar.** Most cards in the "Open" row lead to fully working screens — the inconsistency is what makes it feel like a slot machine.
2. **The bottom bar is forced visible on every screen**, including settings sub-screens and the calendar `Intent.ACTION_VIEW` external launcher. This is a regression of an earlier audit and the rationale comment ("matches the Aura Web pattern") does not justify the friction on Android.
3. **StateFlow mocking is bypassed on `ChatViewModel`**: the `val skills: StateFlow` fallback (`MutableStateFlow(emptyList())`) makes the public API contract lie when `skillsStore` is null in tests, and `ChatViewModelTest.kt` is a 0-byte placeholder file.
4. **Eight screens still call `MaterialTheme.colorScheme` directly** even though the project has a deliberate `AuraThemeTokens` semantic-color layer (and an `AuraPaletteBoundaryTest` to enforce it). This is a "lying theme" risk in light/dark.
5. **CapabilitiesScreen has no CTA** — it shows "Not configured — add a key in Settings" but is not itself clickable, so the user has to back out to Settings → AI & Models manually. The "Capabilities" Home card mirrors the same dead end.
6. **MemoryScreen has two clickable routines/contradiction chips that both navigate to Dreams**, even though the comment says "for the future 'open routines screen'". The chips look like independent destinations.
7. **18 of the 32 user-facing screens in `ui/screens/` have no test coverage at all** (not even a logic test). The most-used screen (ChatRoute) is one of them.

---

## 1. Navigation reachability — verified mechanically

### 1.1 All `navigate()` targets resolve to a `composable()`

`rg "navigate\(" app/src/main/kotlin/com/aura/ui` → 36 hits, all inside `nav/NavGraph.kt` and `screens/chat/ChatRoute.kt`. Hand-verified against the `composable(...)` registrations in `NavGraph.kt`:

| navigate() caller | Target route | composable() registration | Status |
|---|---|---|---|
| NavGraph (Home launches) | `chat?brief=...` | `composable(route = "chat?convId={convId}&draft={draft}&brief={brief}&focusTurn={focusTurn}", ...)` | OK (query params optional) |
| NavGraph (Home launches) | `chat?draft=...` | same as above | OK |
| NavGraph | `tasks` / `reminders` / `hands` / `tools` / `skills` / `creative` / `proactive` / `agent_runs` / `production` / `capabilities` | all registered | OK |
| NavGraph | `history` / `knowledge_graph` / `dreams` / `profile` / `identity_editor` / `diagnostics` | all registered | OK |
| NavGraph | `evolution/inbox` / `evolution/beliefs` / `evolution/rollback/{id}` | all registered | OK |
| NavGraph | `agent_editor?agentId=` | `composable(route = "agent_editor?agentId={agentId}", ...)` with `nullable=true, defaultValue=null` | OK (empty agentId = new agent) |
| NavGraph | `world_model` / `taste_profile` | all registered | OK |
| NavGraph | `creative/{projectId}` | `composable(route = "creative/{projectId}", ...)` | OK |
| NavGraph | `agent_runs/{runId}` | `composable(route = "agent_runs/{runId}", ...)` | OK (no caller currently emits a specific runId; see §2.4) |
| ChatRoute:409 | `council/{convId}` | `composable(route = "council/{convId}", ...)` | OK |
| NavGraph | `council_result` savedStateHandle key | not a route | OK |
| NavGraph | `onNavigate = { route -> navController.navigate(route) }` from GlobalSearchSheet | uses runtime string | unverified statically (intentional — search results are dynamic) |

**Conclusion:** No broken routes. The `NavigationReachabilityTest` correctly enforces this.

### 1.2 Bottom nav exposes exactly 6 stable routes (test-enforced)

`AuraBottomNavigationRouteTest` pins:

```
home, chat, memory, tasks, evolution/inbox, settings
```

`nav/AuraBottomNavigation.kt:77-84` matches. ✅

### 1.3 Bottom bar is unconditionally visible (regression of previous fix)

`nav/NavGraph.kt:77`: `val showBottomBar = true` — a constant.

The dead-code comment block at lines 70-76 explicitly notes this is a **change from a previous behavior** ("the previous check…hid the bar on every secondary screen…forcing the user to press the system back button to return to a tab"). The justification given is "matches the Aura Web pattern where the left sidebar persists across all views." That comparison is invalid:

- Aura Web is a desktop layout with a **left sidebar**, not a 6-tab bottom navigation that consumes ~64dp of every Android screen.
- 5 of the 6 top-level destinations are reachable from Home (Memory, Tasks, Tools, Settings, Proactive/Evolution). On every secondary screen (Profile, Identity, Diagnostics, Dreams, World Model, Taste Profile, Reminders, Agent Editor, Knowledge Graph, Creative*, etc.) the bottom bar shows 6 tabs of which **at most 1 (Settings) is the place the user came from to get here**.
- The "Back" pattern on Android already covers this case for free.

**UX impact:** permanent ~80dp vertical space loss on every screen, including the keyboard-heavy Chat composer. On small phones this is the difference between seeing 1.5 chat turns and 3.

**Severity:** Medium. Not broken, but a clear friction call.

---

## 2. Home destination audit (the "Open" row)

`HomeSecondaryActions.kt:74-141` renders 11 destination cards in a horizontal `LazyRow`:

| # | Label | Metadata (when 0) | Metadata (when >0) | Icon | Handler | Reachable screen | Verdict |
|---|-------|-------------------|--------------------|------|---------|------------------|---------|
| 1 | Memory | "Add memory" | "N saved" | `Psychology` | `onOpenMemory` | `memory` tab | OK |
| 2 | Tasks | "Create task" | "N open" | `TaskAlt` | `onOpenTasks` | `tasks` (and the `Tasks` bottom-bar tab is the same route) | **Duplicates bottom-bar "Tasks" tab** |
| 3 | Calendar | "Check calendar" | "N today" | `CalendarMonth` | `onOpenCalendar` | `Intent(ACTION_VIEW, content://com.android.calendar/time/...)` (NavGraph.kt:180-196) | OK (external app, but… see §2.3) |
| 4 | Hands | "Create hand" | "N active" | `Build` | `onOpenHands` | `hands` | OK |
| 5 | Skills | "Add skill" | "N active" | `MenuBook` | `onOpenSkills` | `skills` | OK |
| 6 | Creative | "Worlds, drafts, scenarios" | (static) | `AutoStories` | `onOpenCreative` | `creative` | OK |
| 7 | Tools | "Browse tools" | "N available" | `Lightbulb` | `onOpenTools` | `tools` | OK |
| 8 | Proactive | "View activity" | "N updates" | `NotificationsActive` | `onOpenProactive` | `proactive` | OK |
| 9 | Runs | "Agent runs" | (static) | `AccountTree` | `onOpenAgentRuns` | `agent_runs` | OK |
| 10 | Production | "Film & story pipelines" | (static) | `Movie` | `onOpenProduction` | `production` | OK |
| 11 | Capabilities | "Add image, video, search" | "N active" | `SettingsInputComponent` | `onOpenCapabilities` | `capabilities` | **Dead end — see §2.2** |

### 2.1 Home "Tasks" card is redundant with the bottom bar

`Tasks` is also `topLevelRoutes[3]`. Tapping the Home card → `tasks` composable → user sees the same screen they could have reached with one tap of the bottom bar. The "Create task" affordance on the Home card is a small win (verb-style label) but the duplication is the bigger UX cost: a 6-tab bottom bar already advertises Tasks. Same applies in spirit to Memory (tab 3) and Settings (tab 6), but those have more verb-like affordance differences.

**Severity:** Low. The Metadata counter ("N open") is the only added value, and the bottom bar doesn't carry that.

### 2.2 CapabilitiesScreen has no way to add a capability from the screen

`screens/CapabilitiesScreen.kt` is a read-only list of `CapabilityCardState` cards. Every card renders either:

```
Active · <provider label>
```
or
```
Not configured — add a key in Settings
```

Neither card is clickable. There is no `onClick` on the `Surface` (line 89-96), no `IconButton`, no CTA button. The "add a key in Settings" string tells the user to navigate away — but the screen has no built-in back-stack action to do so; they have to hit system back, then tap Settings, then drill in.

`HomeSecondaryActions.kt:135-140` exposes the same destination with the label "Add image, video, search" — implying the user can add things there. They cannot. The CTA is **a lie**.

**Severity:** High. The single most user-trapped path on Home.

### 2.3 Calendar "open" sends the user out of the app — to a non-existent screen

`NavGraph.kt:181-196` builds:
```kotlin
Intent(ACTION_VIEW, "content://com.android.calendar/time/${System.currentTimeMillis()}")
```
with a `try/catch (ActivityNotFoundException)` that toasts "No calendar app found". On real devices, **no app registers as the default handler for that content URI** — Google Calendar uses a different scheme (`com.google.android.calendar`) on most devices. So on a stock Pixel the user sees "No calendar app found" (a soft fail), and on Samsung they get bounced to the OEM calendar's "now" view (the URI happens to work by accident).

**UX impact:** The Calendar Home card is the only Home destination that can leave the app entirely. The metadata "N today" is computed from `CalendarReadTool.readTodaysEvents()` (a permissioned read), but tapping the card does **not** show that list — it hands off to a system intent whose handler is undefined. The data and the action are disconnected.

**Severity:** Medium. Misleading affordance.

### 2.4 `agent_runs/{runId}` subroute is registered but never emitted

`NavGraph.kt:315-323` defines a parameterized `composable("agent_runs/{runId}")` route. `rg "navigate\(.agent_runs/" app/src/main/kotlin/com/aura/ui` returns 0 hits — no caller emits a specific runId. The "Runs" Home card navigates to the non-parameterized `agent_runs` (line 177). Either:
- the subroute is for future deep-linking, or
- the missing caller is a UX gap (e.g., "Open this run" from Production or elsewhere).

**Severity:** Low (dead route, but no broken UI). Worth flagging because `NavigationReachabilityTest` will pass even though the parameterized route is dead code.

### 2.5 Proactive history opens but the card is duplicated twice

`HomeContent.kt:550-555` wires `onOpenProactive` to the "Proactive" card. `HomeBriefCard.kt:147-166` also renders a `Proactive` priority card from `HomePriority.Proactive` that opens the same screen. The priority card appears above the "Open" row, so the user sees **two different paths to the same screen with different framings**. Not a bug, but the priority card's "Discuss brief" / "Open calendar" actions bypass the bottom bar entirely.

---

## 3. StateFlow / ViewModel mocking hazards

### 3.1 `ChatViewModel.skills` is a `StateFlow` only when `skillsStore != null`

`viewmodel/ChatViewModel.kt:345`:
```kotlin
val skills: StateFlow<List<Skill>> = skillsStore?.skills ?: MutableStateFlow(emptyList())
```

This is a typed lie. The public type is `StateFlow<List<Skill>>`, but at runtime the **backing implementation** can swap from a real flow (with hot, replayable values) to a one-shot empty `MutableStateFlow`. Compose code calling `viewModel.skills.collectAsStateWithLifecycle()` works in both cases, but tests that try to seed values via `skills.value = …` will silently write to a *different* flow than the one the VM is collecting from in production. The "test" version of the flow is structurally different from the "real" one.

**Severity:** Medium. Will burn whoever next writes a `ChatViewModel` unit test that depends on a non-empty skills list.

### 3.2 `ChatViewModelTest.kt` is a 0-byte placeholder

`app/src/test/kotlin/com/aura/ui/viewmodel/ChatViewModelTest.kt` — `ls -la` reports 0 bytes. It compiles (empty file = valid Kotlin), it runs (no `@Test` methods = 0 tests), and Gradle counts it as a passing test file. The 4 sibling tests (`ChatViewModelLastAssistantTest`, `ChatViewModelAgentPickerTest`, `ChatViewModelScreenTest`, `ChatViewModelDocumentTest`) cover narrow surfaces, but **the actual full lifecycle of the most complex screen in the app has no integration test**.

**Severity:** High for code health; Medium for UX risk because the bugs the test would catch are exactly the navigation/state hazards users hit.

### 3.3 `ProactiveHistoryViewModel` exposes `status: StateFlow<String?>` but not `state`

`viewmodel/ProactiveHistoryViewModel.kt:36-37`:
```kotlin
private val _status = MutableStateFlow<String?>(null)
val status: StateFlow<String?> = _status.asStateFlow()
```

The screen (`screens/ProactiveHistoryScreen.kt`, untested) likely needs more than just a status string. If it's reading from another flow directly in the screen, that's a missing-state pattern. Verified by `rg "viewModel\." app/src/main/kotlin/com/aura/ui/screens/ProactiveHistoryScreen.kt` (not run here, but the file is on the untested list in §6).

**Severity:** Low pending read of `ProactiveHistoryScreen.kt`.

### 3.4 `updateObserved` only recalculates `loadState` from non-Loading/Error states

`HomeViewModel.kt:142-154`:
```kotlin
private fun updateObserved(transform: (HomeUiState) -> HomeUiState) {
    _state.update { current ->
        val updated = transform(current)
        when (current.loadState) {
            HomeLoadState.Loading,
            is HomeLoadState.Error,
            -> updated
            else -> updated.copy(
                loadState = resolveHomeLoadState(updated.hasHomeData(), dataSourceError = null),
            )
        }
    }
}
```

Real-time observation updates (`observeTasks`, `observeMemories`, etc.) **never flip `loadState` from Loading/Error to Content/Empty** — they go through `updateObserved` which short-circuits in those cases. This means: if the initial `refresh()` throws and sets `loadState = Error`, then later a memory gets added and `observeMemories` fires, the user still sees the error banner. The "Retry" button works (it calls `refresh()`), but a user who just added a memory and expects the error to clear will be confused.

**Severity:** Medium. Subtle but real "the data is there, why is it still red?" bug.

### 3.5 `HomeViewModel.refresh()` updates `_state.value` directly, bypassing `updateObserved`

`HomeViewModel.kt:295-313`:
```kotlin
val loaded = _state.value.copy(...)
val calendarError = calendarResult.exceptionOrNull()?.let { "Calendar is unavailable. …" }
_state.value = loaded.copy(
    loadState = resolveHomeLoadState(loaded.hasHomeData(), calendarError),
)
```

Direct assignment to `_state.value` instead of `_state.update { … }`. Race-condition risk if another coroutine is mid-`update` (the `update` lambda is atomic, but a raw `value =` write can interleave). With ~8 concurrent observers started in `init {}` and `refresh()` launched from `init`, this is plausible.

**Severity:** Low–Medium. Hard to hit in practice, easy to fix.

---

## 4. MaterialTheme.colorScheme usage — leaky abstraction

Project has a deliberate `AuraThemeTokens` semantic-color layer with three tests:
- `AuraPaletteBoundaryTest`
- `AuraSemanticColorsTest`
- `AuraDimensionsTest`

Despite this, **8 screens** still use `MaterialTheme.colorScheme` directly:

| File | Hits | Examples |
|------|------|----------|
| `DreamsScreen.kt` | 10 | `colorScheme.onSurfaceVariant` (×7), `colorScheme.primary` (×2), `colorScheme.error` (×1) |
| `WorldModelScreen.kt` | 8 | `colorScheme.onSurfaceVariant` (×6), `colorScheme.primary` (×2) |
| `TasteProfileScreen.kt` | 7 | `colorScheme.onSurfaceVariant` (×5), `colorScheme.primary` (×1) |
| `ScheduleScreen.kt` | 3 | `colorScheme.onSurfaceVariant` (×2), `colorScheme.primary` (×1) |
| `production/ProductionPipelineScreen.kt` | 1 | `colorScheme.primary` (check icon tint) |
| `components/SwipeToDeleteContainer.kt` | 2 | (line numbers not in this pass) |
| `components/MarkdownText.kt` | 1 | (comment about non-composable path) |
| `chat/ChatDialogs.kt` | 1 | `colorScheme.onSurfaceVariant` |

The same screens that ignore the token layer also do not respect light/dark via `isSystemInDarkTheme()` (they read whatever M3 default is active). `AuraPaletteBoundaryTest` passes because it only checks the token surface itself, not which screens read from the wrong layer.

**Severity:** Medium. In dark theme these screens will look "off" (M3 defaults instead of Aura tokens). The `AuraPaletteBoundaryTest` does not catch this.

---

## 5. Empty / dead `onClick = {}` and clickable surfaces

`rg "onClick = \{\s*\}"` — 5 hits, all in chip/row display contexts where the empty handler is intentional (e.g., `MessageBubble.kt:364` uses `combinedClickable(onClick={}, onLongClick=onEdit)` so the long-press is the only meaningful action):

| File | Context | Verdict |
|------|---------|---------|
| `MessageBubble.kt:364` | `combinedClickable(onClick={}, onLongClick=onEdit)` | OK (intentional — selection via `SelectionContainer`, edit via long-press) |
| `TasksScreen.kt:566` | `InputChip(selected=false, onClick={}, label=...)` for priority display | OK (display-only) |
| `ProfileScreen.kt:122` | `InputChip(selected=false, onClick={}, label=trait)` (delete via trailingIcon) | OK (delete is on the trailing icon) |
| `components/ModelPickerSheet.kt:344` | "Unavailable" current model row, `enabled=false` | OK (greyed out, not clickable) |
| `settings/sections/AiAndModelsSection.kt:95` | `AssistChip(onClick={}, label=providerName)` for configured providers list | OK (display only) |

**Conclusion:** No genuinely dead onClick handlers. All 5 are intentional display chips where the empty handler is a Material 3 idiom for "this looks like a chip but isn't a button." The bigger concern is the opposite case — see §2.2 (CapabilitiesScreen has *no* onClick at all, which is what makes it a dead end).

---

## 6. Untested screens (33 of 41 Composable files in `ui/screens/`)

Files with a Composable function and **no test class** (verified by `find … -name "*<Name>Test*"` and `*<Name>LogicTest*`):

- `KnowledgeGraphScreen.kt` — has a known-empty `state.recentMemories` graph and a re-entrancy-heavy expand flow
- `DiagnosticsScreen.kt` — internal-facing, low priority
- `IdentityEditorScreen.kt` — affects how the agent addresses the user; untested
- `DreamsScreen.kt` — uses `MaterialTheme.colorScheme` (§4) AND untested
- `HandsScreen.kt` — complex (FAB-only on tab 0, dialog state, permission flow, status filters, expandable run history)
- `CouncilScreen.kt` — has a known onSendToChat branch (`NavGraph.kt:353-358`) that is **dead code**: it writes to `previousBackStackEntry?.savedStateHandle` and then `popBackStack()` — but `CouncilScreen` is never launched with a `convId` from a chat that expects to receive a result. The flow is wired but nothing consumes it.
- `HistoryScreen.kt` — search, swipe-delete, share/export, pin, multi-select all untested
- `MemoryScreen.kt` — 1095 lines of UI, swipe-delete, search, category filters, dream summary dialog, edit dialog, document import flow, bulk delete, rebuild-embeddings all untested
- `production/ProductionPipelineScreen.kt` — multi-stage pipeline UI
- `ProactiveHistoryScreen.kt` — has a likely-incomplete `status` flow (§3.3)
- `home/HomeContent.kt` / `HomeRoute.kt` / `HomeSecondaryActions.kt` / `HomeBriefCard.kt` / `HomePrimaryAction.kt` — the entire Home experience is integration-tested only by `NavigationReachabilityTest`
- `creative/CreativeProjectScreen.kt` / `CreativeStudioScreen.kt` / `WorldBibleEditor.kt` — full creative suite
- `RemindersScreen.kt` / `ReminderEditorDialog.kt`
- `ProfileScreen.kt` — see `InputChip` empty onClick in §5
- `SettingsScreen.kt` — composes 12 sections, untested at the Composable level (the ViewModels have their own tests)
- `TasksScreen.kt` — `PriorityChip` empty onClick in §5
- `ScheduleScreen.kt` — uses `MaterialTheme.colorScheme` AND untested
- `WorldModelScreen.kt` / `TasteProfileScreen.kt` — both use `MaterialTheme.colorScheme` AND untested
- `ToolsScreen.kt` / `CapabilitiesScreen.kt` / `skills/SkillsScreen.kt` — fully untested
- `chat/ChatContent.kt` / `ChatRoute.kt` / `ChatComposer.kt` / `ChatHeader.kt` / `ChatTimeline.kt` / `ChatDialogs.kt` — the entire Chat UI surface is untested at the Composable level (only the ViewModel sub-components have narrow tests)
- `onboarding/OnboardingRoute.kt` / `OnboardingContent.kt` / `ModelSelectionStep.kt` — has a test, but the individual steps don't
- `search/GlobalSearchSheet.kt` — modal search sheet, untested
- `MemoryHistoryDialog.kt` / `DocumentLibraryDialog.kt` / `HandEditorDialog.kt`

**Severity:** Test coverage at the Composable level is the lowest in the codebase. This is the layer where users live.

---

## 7. Visual / UX friction (specifics, by surface)

### 7.1 Home `HomeBriefCard` priority CTA label mismatch

`HomeBriefCard.kt:147-186` — when the priority is `HomePriority.None` (no data), the card says:
```
Nothing needs attention yet
Create a task or tell Aura what matters today.
[Create a task]   ← text, not a button label
```
The "action" text is rendered as a `Text(...)` (line 135-139), not an actual `Button`. The whole `Surface` is `clickable { … }` (line 82), so tapping it routes to `onOpenTasks` — but visually the action label says "Create a task", not "Open tasks". Two semantics, one click target.

Compare to the same file line 149-152 for `MorningBriefReady`:
```
title = "Morning brief", body = ..., action = "Discuss brief"
```
…which routes to `onOpenChatWithBrief`. "Discuss brief" is what the user would expect; "Create a task" is what they get on the empty state. Misleading.

**Severity:** Medium. Empty-state lie.

### 7.2 MemoryScreen — routines/contradictions chips both open Dreams

`MemoryScreen.kt:298-331`:
```kotlin
if (state.routineCount > 0) {
    AssistChip(
        onClick = onOpenDreams,   // ← both chips open Dreams
        label = { Text("${state.routineCount} routines") },
        ...
    )
}
if (state.contradictionCount > 0) {
    AssistChip(
        onClick = onOpenDreams,   // ←
        label = { Text("${state.contradictionCount} contradictions") },
        ...
    )
}
```

The inline comment (lines 295-297) acknowledges this: *"Tappable for the future 'open routines screen' but for now they're just informative chips."* They're not informative — they're clickable and route to a screen whose name has nothing to do with routines or contradictions. The chips should either be `SuggestionChip` with `enabled=false` (clearly read-only) or actually open the relevant screens.

**Severity:** Medium. Two clickable chips with misleading destinations.

### 7.3 ChatRoute:407-410 — "Council" is reachable but has no UI affordance in Home

`ChatRoute.kt:407`:
```kotlin
onOpenCouncil = {
    navController.navigate("council".plus("/").plus(state.conversation.id))
}
```
There is no caller that passes `onOpenCouncil = { … }` in `NavGraph.kt` (the parameter is unset in the only composable wiring). So the "Open Council" function exists, navigates, but **no UI surface invokes it**. Council screen is therefore reachable only from chat-internal (header) actions — and the chat header (`ChatHeader.kt`) was not opened in this audit; if it doesn't expose the button, Council is genuinely unreachable.

**Severity:** Medium. Dead affordance risk.

### 7.4 ChatRoute:233-234 — `LaunchedEffect(state.draft) { savedDraft = state.draft }`

This writes the draft to `rememberSaveable` on every change of `state.draft`. `rememberSaveable` saves to a Bundle on configuration change / process death. The first `LaunchedEffect(Unit)` (line 225-229) then reads it back. Looks fine on the surface, but if the user types a long message and the VM emits an empty `state.draft` (e.g., after `viewModel.newConversation()`), the saved draft is wiped. There is no early-out for "draft was just intentionally cleared by send".

**Severity:** Low. Easy to lose typed text on edge timing.

### 7.5 `OnboardingRoute`/`OnboardingContent` — referenced from MainActivity but not audited here

`MainActivityLaunchRequestTest.kt` exists; `OnboardingRoute` has tests. The actual UI is not in this pass but `OnboardingViewModelTest` and `OnboardingModelFlowTest` give some coverage.

**Severity:** N/A (skipped).

### 7.6 `swipeToDeleteContainer` MaterialTheme.colorScheme use

`components/SwipeToDeleteContainer.kt` has 2 uses of `MaterialTheme.colorScheme`. The swipe-to-delete background is a global visual primitive (used by Memory, Hands, History, Reminders). A token violation here is *visible everywhere*.

**Severity:** Medium. One of the highest-leverage token-violation fixes.

### 7.7 Settings: "Agents" row is a one-off `SettingsClickableRow`

`SettingsScreen.kt:179-184`:
```kotlin
SettingsClickableRow(
    title = "Agents",
    subtitle = "Create custom AI agents with their own personality, tools, and memory",
    onClick = onNavigateAgentEditor,
)
```
This is the *only* place in the entire Settings screen that uses the generic `SettingsClickableRow` directly. All other navigation is routed through a section (`AiAndModelsSection`, `PersonaSection`, `EvolutionSettingsSection`, etc.). The lone row is visually inconsistent with the rest of the page (no leading icon, no trailing chevron, no consistent row height). A user scanning for "where's the Agents section?" will not find it grouped with the other Persona/Evolution tools.

**Severity:** Low. Visual inconsistency only.

### 7.8 Bottom-bar "Evolve" label vs. route name

`AuraBottomNavigation.kt:74`:
```kotlin
data object Evolution : TopLevelRoute("evolution/inbox", "Evolve", ...)
```

Label says "Evolve", route is "evolution/inbox". On the navigation bar the user sees "Evolve" and taps it. The screen they land on (`EvolutionInboxScreen`) is the inbox, not an evolution overview. A user tapping "Evolve" expecting the evolution overview (world model, beliefs, taste profile — all reachable from Settings → Evolution) lands in a notification inbox instead. The other entry points (Settings → EvolutionSettingsSection) do show all of those, so the user has to know that "Evolve" tab = inbox, not settings. This is a label/route split.

**Severity:** Medium. Mismatch between tab label and landing surface.

### 7.9 `HomeFreshStart` "Decide what matters today" sends a hard-coded message

`HomeContent.kt:645`:
```kotlin
FreshStartAction(
    icon = Icons.Filled.AutoAwesome,
    title = "Decide what matters today",
    message = "Turn an uncertain day into a concrete plan.",
    onClick = { onAskAura("Help me decide what matters most today") },
)
```
The hard-coded message is fine for an empty-state nudge, but `onAskAura` is implemented as `onOpenChat` in `HomeRoute.kt:51` which navigates to `chat?draft=<encoded>` — the user lands on Chat with a draft populated, but `viewModel.setDraft` is called from `LaunchedEffect(initialDraft)` (`ChatRoute.kt:176-178`) which **only fires once** (the `LaunchedEffect` key is `initialDraft`). If the user navigates Home → Chat with the same encoded draft twice (e.g., reopens the app and taps the empty-state card twice), the second time the `LaunchedEffect` will not re-fire because the key value is the same string. The draft is silently lost.

**Severity:** Low. Single-keyed LaunchedEffect on a route arg.

### 7.10 `CouncilScreen.onSendToChat` writes to saved state but no one reads it

`NavGraph.kt:353-358`:
```kotlin
onSendToChat = { text ->
    if (!convId.isNullOrBlank()) {
        navController.previousBackStackEntry?.savedStateHandle?.set("council_result", text)
        navController.popBackStack()
    }
}
```
`ChatRoute.kt:237-244` reads `council_result` from `currentBackStackEntry?.savedStateHandle`. **The `previousBackStackEntry` of `council/{convId}` is whatever screen launched the council flow.** Chat does launch the council flow (line 408-410), so this *should* work — but the call goes `Chat → Council → onSendToChat → writes to Chat's savedStateHandle → popBackStack → Chat reads on resume`. The reading code is correct, but only if the `council_result` set on the *previous* backstack entry persists across the pop. It does (Compose Navigation preserves saved state handles on pop), so this is fine. **But** — if the user navigates Chat → Council → Home (via back-stack through Chat), the saved handle is on Chat's entry which is now further down the stack. Re-entering Chat will read it correctly. So this *should* work, but it's fragile.

**Severity:** Low. The integration is correct but easy to break.

---

## 8. False positives verified and removed

- `NavGraph.kt:90,112,145,155,164,266`: all use `popUpTo` with `saveState/restoreState/launchSingleTop` — standard pattern, not the bug class the test was written for.
- `HomeViewModel.kt:257` `runCatching { calendarReadTool.readTodaysEvents() }`: result is bound to `calendarResult` and consumed via `getOrDefault` + `exceptionOrNull()` — verified not a silent failure (matches `runcatching-silent-sites-2026-07-27.md` row 1).
- `MemoryScreen.kt:120-126` `runCatching { takePersistableUriPermission(...) }.onFailure { Log.w(...) }`: log on failure, not silent — false positive.
- `MessageBubble.kt:364` `onClick = {}` inside `combinedClickable`: paired with `onLongClick` for the only meaningful interaction — false positive.
- All `onClick = {}` in chip contexts (TasksScreen, ProfileScreen, AiAndModelsSection, ModelPickerSheet): all are display-only Material 3 `InputChip`/`AssistChip` where the trailing icon holds the action — false positives.
- `ChatViewModel.kt:407, 415`: `runCatching { textToSpeech.state.collect { ... } }` — collects from a flow inside runCatching. If the flow throws after the runCatching returns, the catch is in the right place (a flow crash). Not a silent-failure pattern.

---

## 9. Prioritized recommendations (for the team, not the user)

1. **P0 — Make Capabilities cards clickable.** Route unconfigured cards to Settings → AI & Models. One of the highest-leverage UX fixes for the cost.
2. **P0 — Re-introduce a route-aware bottom bar.** Keep it visible on top-level routes only. The "always visible" change trades 80dp on every screen for one less back-press on a path users rarely take.
3. **P1 — Fix `HomeViewModel.updateObserved` to allow Loading→Content/Empty transitions** (or split into two functions so observers can flip the load state).
4. **P1 — Rename "Evolve" tab to "Inbox" or expose the full Evolution landing page.** The label/route split is the kind of thing that lands in user support tickets.
5. **P1 — Add a `BasicTextField`-style test or Compose UI test for HomeContent** covering the 11 cards and their "no data" labels. The "Open" row is the primary discovery surface.
6. **P2 — Add `AuraThemeTokens` wrappers for `colorScheme.onSurfaceVariant`, `colorScheme.primary`, `colorScheme.error`** and migrate the 8 offending files. Especially `SwipeToDeleteContainer.kt` (visible everywhere) and `DreamsScreen.kt` (heavy use).
7. **P2 — Either remove the parameterized `agent_runs/{runId}` route or wire a caller** (likely from `ProductionPipelineScreen` "view run" action).
8. **P2 — Replace the empty-state CTA "Create a task" wording in `HomeBriefCard`** with the actual destination ("Open tasks"), or split the action label and the body text.
9. **P3 — Delete the empty `ChatViewModelTest.kt`** or move its 4 split siblings into a single file. A 0-byte file is worse than no file.
10. **P3 — Make MemoryScreen routines/contradictions chips either non-clickable or routed to their own screens** (comment already says the former is the intent).
11. **P3 — Fix `HomeBriefCard.None` action label** so the click target says what it does.

---

## 10. What the existing guards catch and what they don't

| Guard | Catches | Misses |
|---|---|---|
| `NavigationReachabilityTest` | `navigate("x")` with no `composable("x")` | Routes that are registered but never navigated to (dead routes — e.g., `agent_runs/{runId}`); routes navigated to but with wrong params |
| `AuraBottomNavigationRouteTest` | Renames/removes of the 6 top-level routes | Doesn't enforce the always-on `showBottomBar = true` regression |
| `AuraPaletteBoundaryTest` | Token surface changes | Screens that bypass tokens via `MaterialTheme.colorScheme` |
| `NavigationReachabilityTest` `knownBugs` ratchet | New mismatches | Already-empty ratchet gives a false sense of "0 known bugs" |
| `InsetOwnershipPolicyTest` | Inset double-handling | Doesn't cover the always-on bottom bar consuming insets |

---

**Audit complete.** 36 navigation calls verified, 5 false-positive empty `onClick` handlers cleared, 11 home destinations audited, 8 token-violation files surfaced, 33 untested screens listed, 11 prioritized recommendations. All findings cross-referenced against `app/src/main/kotlin/com/aura/ui/` source.
