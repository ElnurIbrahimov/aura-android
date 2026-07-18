# UI/UX & Testing Layer Audit — aura-android-clean

**Date:** 2026-07-17  
**Scope:** 21 screens, 25 ViewModels, ~280 test files, custom design system  
**Context:** 76K LOC, Kotlin/Compose superapp, untracked APK v0.18.0, v0.20.0 release commit  

---

## RANKED FINDINGS

---

### P0 — SHIP-BLOCKING: Must fix before v0.20.0 release

---

#### F1. `collectAsState()` used instead of `collectAsStateWithLifecycle()` — 31 occurrences across 15 screens

**Severity: P0** — Lifecycle-aware collection is the #1 Compose runtime requirement. The `collectAsState()` calls keep collecting flows even when the UI is in the background (lifecycle < STARTED), causing unnecessary work, memory pressure, and potential ANRs when the user returns.

**Evidence:**
- `app/src/main/kotlin/com/aura/ui/screens/chat/ChatRoute.kt:177` — `val state by viewModel.state.collectAsState()`
- `app/src/main/kotlin/com/aura/ui/screens/MemoryScreen.kt:96` — `val state by viewModel.state.collectAsState()`
- `app/src/main/kotlin/com/aura/ui/screens/HistoryScreen.kt:78`
- `app/src/main/kotlin/com/aura/ui/screens/HandsScreen.kt:93`
- `app/src/main/kotlin/com/aura/ui/screens/KnowledgeGraphScreen.kt:76`
- `app/src/main/kotlin/com/aura/ui/screens/DiagnosticsScreen.kt:67`
- `app/src/main/kotlin/com/aura/ui/screens/ProactiveHistoryScreen.kt:88,110`
- `app/src/main/kotlin/com/aura/ui/screens/SettingsScreen.kt:209,210,1157`
- `app/src/main/kotlin/com/aura/ui/screens/TasksScreen.kt:80`
- `app/src/main/kotlin/com/aura/ui/screens/ToolsScreen.kt:56`
- `app/src/main/kotlin/com/aura/ui/screens/RemindersScreen.kt:60`
- `app/src/main/kotlin/com/aura/ui/screens/home/HomeRoute.kt:30`
- `app/src/main/kotlin/com/aura/ui/screens/IdentityEditorScreen.kt:67`
- `app/src/main/kotlin/com/aura/ui/screens/skills/SkillsScreen.kt:53-54`
- `app/src/main/kotlin/com/aura/ui/screens/production/ProductionPipelineScreen.kt:48`
- `app/src/main/kotlin/com/aura/ui/screens/creative/CreativeProjectScreen.kt:63`
- `app/src/main/kotlin/com/aura/ui/screens/creative/CreativeStudioScreen.kt:55`
- `app/src/main/kotlin/com/aura/ui/evolution/EvolutionInboxScreen.kt:43-45`
- `app/src/main/kotlin/com/aura/ui/evolution/EvolutionRollbackScreen.kt:34`
- `app/src/main/kotlin/com/aura/ui/screens/agentrun/AgentRunsScreen.kt:51`
- `app/src/main/kotlin/com/aura/ui/voice/VoiceOverlay.kt:51`
- `app/src/main/kotlin/com/aura/ui/screens/onboarding/OnboardingRoute.kt:194`

**Fix:** Replace all `collectAsState()` with `collectAsStateWithLifecycle()`. Correct usage:
```kotlin
import androidx.lifecycle.compose.collectAsStateWithLifecycle
val state by viewModel.state.collectAsStateWithLifecycle()
```

---

#### F2. Hardcoded color values bypassing the theme token system — 7 locations

**Severity: P0** — Design tokens exist (`AuraTokens`, `AuraSemanticColors`) but several screens bake raw hex colors. When the theme changes (dark→light, or mood accent changes), these bypassed values stay frozen.

**Evidence:**
- `app/src/main/kotlin/com/aura/ui/screens/HandsScreen.kt:537` — `Color(0xFF5FD3A8)` (should be `AuraThemeTokens.colors.success`)
- `app/src/main/kotlin/com/aura/ui/screens/HandsScreen.kt:539` — `Color(0xFFE4B865)` (should be `AuraThemeTokens.colors.warning`)
- `app/src/main/kotlin/com/aura/ui/screens/HandsScreen.kt:540` — `Color(0xFFE4B865)` (same)
- `app/src/main/kotlin/com/aura/ui/screens/KnowledgeGraphScreen.kt:607` — `Color(0xFF4DB6AC)` (LOCATION color bypasses token system)
- `app/src/main/kotlin/com/aura/ui/screens/KnowledgeGraphScreen.kt:608` — `Color(0xFFE573A9)` (EMOTION color bypasses token system)
- `app/src/main/kotlin/com/aura/ui/components/MarkdownText.kt:275` — `Color(0x1A808080)` (inline code background)
- `app/src/main/kotlin/com/aura/ui/components/MarkdownText.kt:349` — `Color(0x1A808080)` (same, duplicated in code block rendering)

**Fix:**  
- HandsScreen: Replace raw green/yellow with `AuraThemeTokens.colors.success` / `AuraThemeTokens.colors.warning`
- KnowledgeGraphScreen: Add `LOCATION`/`EMOTION` color slots to `AuraSemanticColors` data class, then reference them
- MarkdownText: Move inline-code background to a semantic token

---

#### F3. `AuraListRow` — completely orphaned component (0 callers)

**Severity: P0** — Dead code adds maintenance burden. If it's meant to replace ad-hoc row layouts, no screen uses it.

**Evidence:**
- `app/src/main/kotlin/com/aura/ui/components/AuraListRow.kt` — defines `AuraListRow` composable
- ZERO imports of `AuraListRow` anywhere in `app/src/` — confirmed by search.

**Fix:** Delete `AuraListRow.kt`, or add a work item to migrate screens (ToolsScreen, SkillsScreen, etc.) to use it. Prefer deletion for v0.20.0.

---

#### F4. `AuraSecondaryButton` and `AuraEditorSheet` — orphaned components (0 callers)

**Severity: P0**

**Evidence:**
- `app/src/main/kotlin/com/aura/ui/components/AuraButtons.kt:40` — `AuraSecondaryButton` defined, ZERO callers outside own file
- `app/src/main/kotlin/com/aura/ui/components/AuraEditorSheet.kt:18` — `AuraEditorSheet` defined, ZERO callers outside own file

**Fix:** Delete or add adoption work items.

---

### P1 — HIGH: Should fix before release

---

#### F5. Nine screens with zero dedicated test coverage

**Severity: P1** — These screens have no Compose UI tests and no unit tests for their ViewModels. Regressions won't be caught.

**Evidence (screens with 0 tests):**

| Screen | ViewModel | Test file |
|--------|-----------|-----------|
| `AgentRunsScreen.kt` | `AgentRunsViewModel.kt` | ❌ NONE |
| `IdentityEditorScreen.kt` | (via owner ViewModel) | ❌ NONE |
| `ProactiveHistoryScreen.kt` | `ProactiveHistoryViewModel.kt` | ❌ NONE |
| `ProductionPipelineScreen.kt` | `ProductionPipelineViewModel.kt` | ❌ NONE |
| `SkillsScreen.kt` | `SkillsViewModel.kt` | ❌ NONE |
| `ToolsScreen.kt` | `ToolsViewModel.kt` | ❌ NONE |
| `BeliefsScreen.kt` | `BeliefsViewModel.kt` | ❌ NONE |
| `EvolutionInboxScreen.kt` | `EvolutionInboxViewModel.kt` | ❌ NONE |
| `EvolutionRollbackScreen.kt` | (no dedicated VM) | ❌ NONE |
| `WorldBibleEditor.kt` | (CreativeStudioVM) | ❌ NONE |
| `DocumentLibraryDialog.kt` | `DocumentImportViewModel` test exists for import, not dialog | ❌ NONE |

**Fix:** Add at minimum ViewModel unit tests for the 6 untested ViewModels (AgentRunsViewModel, SkillsViewModel, ToolsViewModel, ProactiveHistoryViewModel, ProductionPipelineViewModel, BeliefsViewModel, EvolutionInboxViewModel).

---

#### F6. Four activities with zero test coverage

**Severity: P1** — Activities handle critical Android lifecycle entry points.

| Activity | Test file |
|----------|-----------|
| `MainActivity.kt` | ❌ NONE (only `MainActivityLaunchRequestTest` exists, doesn't test activity) |
| `ShareReceiverActivity.kt` | ❌ NONE |
| `WidgetConfigActivity.kt` | ❌ NONE |
| `QuickAskActivity.kt` | ❌ NONE |

**Fix:** Add Activity-scenario tests for `ShareReceiverActivity` (critical: intent parsing regression would break app shortcuts/web share) and at minimum a smoke test for `MainActivity`.

---

#### F7. Widely overlapping iconography with `contentDescription = null` — accessibility + icon confusion in LazyRows

**Severity: P1** — Items inside `LazyColumn`/`items` blocks use the same Material icon for different actions without accessible labels, causing screen-reader silence and developer confusion at a glance.

**Evidence (icon reuse in list items):**
- `MemoryScreen.kt:631` — `Icons.Filled.Delete` in memory items `contentDescription = "Forget"`
- `MemoryScreen.kt:624` — `Icons.Filled.Edit` alongside Delete in same row
- `TasksScreen.kt:526 + 533` — `Icons.Filled.Edit` + `Icons.Filled.Delete` in task items
- `RemindersScreen.kt:262 + 266 + 269` — `Icons.Filled.Add`, `Icons.Filled.Edit`, `Icons.Filled.Delete` switch between history/active mode
- `HistoryScreen.kt:376` — `Icons.Filled.Delete` with readable `"Delete"` label
- `ProfileScreen.kt:124 + 182` — `Icons.Filled.Delete` in trait list + fact list
- `ProactiveHistoryScreen.kt:456` — `Icons.Filled.CheckCircle` for success with `contentDescription = null` (line 457)
- Throughout `Icons.Filled.Add` used 15 times, `Icons.Filled.Delete` used 15 times, `Icons.Filled.Close` used 8 times

**Fix:** Every `Icon()` call must have an explicit, unique `contentDescription`. Use `contentDescription = null` ONLY for purely decorative icons. For action icons in list rows, add: `contentDescription = "Delete ${item.name}"` etc.

---

#### F8. Hardcoded alpha overrides on theme tokens — 30+ locations

**Severity: P1** — `AuraThemeTokens.colors.xxx.copy(alpha = y.zf)` is used extensively, which works for dark mode but breaks when the token itself is already semi-transparent (e.g., `aiThinking = Color(0x268B5CF6)` with alpha applied again would compound transparency). Also these alpha values are arbitrary and not part of the design-token contract.

**Selected evidence:**
- `app/src/main/kotlin/com/aura/ui/components/AuraInlineStatus.kt:37` — `.copy(alpha = 0.12f)`
- `app/src/main/kotlin/com/aura/ui/components/FollowUpSuggestionChips.kt:41`
- `app/src/main/kotlin/com/aura/ui/components/MessageBubble.kt:133,150,564,568`
- `app/src/main/kotlin/com/aura/ui/components/MemoryRecallChip.kt:67,69`
- `app/src/main/kotlin/com/aura/ui/screens/chat/ChatRoute.kt` — multiple `.copy()`
- `app/src/main/kotlin/com/aura/ui/screens/KnowledgeGraphScreen.kt:609` — `textPrimary.copy(alpha = 0.68f)`
- `app/src/main/kotlin/com/aura/ui/screens/ProactiveHistoryScreen.kt` — `textPrimary.copy(alpha = 0.85f, 0.38f, 0.45f, 0.7f)`
- `app/src/main/kotlin/com/aura/ui/screens/HistoryScreen.kt:371` — `error.copy(alpha = 0.6f)`

**Fix:** Add named alpha-token slots to `AuraSemanticColors` for commonly used alpha variants (e.g., `textPrimaryDim: Color`, `surface2Alpha: Color`). Then replace `.copy(alpha = ...)` calls with the token.

---

### P2 — MEDIUM: Fix post-release but track

---

#### F9. `LaunchedEffect(Unit)` for cleanup side effects — lifecycle mismatch

**Severity: P2** — `LaunchedEffect(Unit)` runs *once* when the composable enters composition. Using it for cleanup that should happen on navigation away is correct for initial setup but the pattern of having multiple `LaunchedEffect(Unit)` blocks in a single composable for one-shot side effects is fragile—if the key doesn't match the dependency, the effect runs at the wrong time.

**Evidence:**
- `app/src/main/kotlin/com/aura/ui/screens/chat/ChatRoute.kt:447` — `LaunchedEffect(Unit) { showVoiceOverlay = false }`
- `app/src/main/kotlin/com/aura/ui/screens/chat/ChatRoute.kt:468` — `LaunchedEffect(Unit) { showHoldToTalk = false }`
- `app/src/main/kotlin/com/aura/ui/screens/chat/ChatRoute.kt:481` — `LaunchedEffect(Unit) { doSetup }`
- `app/src/main/kotlin/com/aura/ui/screens/chat/ChatRoute.kt:497` — `LaunchedEffect(Unit) { showContinuousVoice = false }`
- `app/src/main/kotlin/com/aura/ui/screens/ProfileScreen.kt:40` — `LaunchedEffect(Unit) { ... }`

These 5 separate `LaunchedEffect(Unit)` blocks in ChatRoute could be reduced to 1, or use `SideEffect` / `DisposableEffect` for cleanup.

**Fix:** Consolidate sidebar effects into lifecycle-aware blocks. Use `DisposableEffect` for setup/teardown that must happen on leave.

---

#### F10. `HomeViewModel` has test (`HomeStateMappingTest`) but Home screen composables (`HomeContent`, `HomeRoute`, `HomePrimaryAction`, `HomeSecondaryActions`, `HomeBriefCard`) have zero Compose UI tests

**Severity: P2**

**Evidence:** Only `HomeContentTest.kt` (androidTest) and `HomeStateMappingTest.kt` (unit) exist. `HomePrimaryAction`, `HomeSecondaryActions`, `HomeBriefCard` are untested.

**Fix:** Add tests for the stateless composables (`HomeBriefCard`, `HomePrimaryAction`, `HomeSecondaryActions`).

---

#### F11. `CreativeStudioViewModelTest` exists but `CreativeStudioScreen.kt` has no Compose UI test — also `WorldBibleEditor.kt` and `CreativeProjectScreen.kt` have no tests at all

**Severity: P2**

**Evidence:**
- `CreativeStudioViewModelTest.kt` exists (242 lines) — covers ViewModel logic
- `CreativeStudioScreen.kt` — no androidTest
- `CreativeProjectScreen.kt` — no test at all
- `WorldBibleEditor.kt` — no test at all

**Fix:** Add Compose UI tests for Creative Studio screen interactions.

---

#### F12. `HistoryScreen.kt` uses `Icons.Filled.Close` for search clear and `Icons.Filled.Delete` for conversation delete in the same list row — potential collision with memory search

**Evidence:**
- `HistoryScreen.kt:168` — `Icons.Filled.Close` for clear search
- `HistoryScreen.kt:376` — `Icons.Filled.Delete` for delete conversation

These are in different UI areas but share LazyRow taxonomy. Desktop screen-reader navigation could confuse them.

**Fix:** Ensure all `contentDescription` values are unique and descriptive. Use `Icons.Filled.CloseSmall` for search clear to differentiate.

---

#### F13. `MemoryScreen.kt` LazyRow item has multiple IconButtons with `contentDescription = null`

**Evidence:** `MemoryScreen.kt:616-649` — 6 IconButtons in a row with `Icons.Filled.History`, `Icons.Filled.Edit`, `Icons.Filled.Delete`, `Icons.Filled.ThumbUp`, `Icons.Filled.ThumbDown`. While some have `contentDescription`, `History` has `"Edit history"`, `ThumbDown` has `"Not helpful"` — acceptable, but patterns vary by screen and shouldn't.

**Fix:** Enforce `contentDescription` audit via Compose lint rule.

---

#### F14. `ToolsScreen` and `SkillsScreen` share identical `LazyColumn` layout but no shared component extraction

**Severity: P2** — Code duplication. `ToolsScreen.kt` and `SkillsScreen.kt` both have nearly identical LazyColumn layouts with search bars, category headers, and items. The only difference is the data source.

**Evidence:**
- `app/src/main/kotlin/com/aura/ui/screens/ToolsScreen.kt` — CategoryHeader + ToolRow (100 lines)
- `app/src/main/kotlin/com/aura/ui/screens/skills/SkillsScreen.kt` — CategoryHeader + SkillRow (identical structure)

**Fix:** Extract shared `SearchableListLayout` composable.

---

## Summary Statistics

| Category | Count |
|----------|-------|
| P0 findings | 4 |
| P1 findings | 4 |
| P2 findings | 6 |
| Screens with zero tests | 11 |
| ViewModels with zero tests | 9 |
| Activities with zero tests | 4 |
| Orphaned components | 3 (`AuraListRow`, `AuraSecondaryButton`, `AuraEditorSheet`) |
| `collectAsState()` → `collectAsStateWithLifecycle()` needed | 31 call sites |
| Hardcoded color values | 7 locations |
| Alpha-override usages | 30+ locations |

---

## Scoring Methodology

- **P0: Ship-blocking** — Can cause crashes, data loss, accessibility lawsuit risk, or major feature regression
- **P1: High** — Will cause visible bugs, accessibility gaps, or missed regressions in production
- **P2: Medium** — Technical debt, code health, minor UX friction, or test-coverage gaps with low blast radius
