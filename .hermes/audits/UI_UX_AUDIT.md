# UI/UX + STATE + NAVIGATION + DAILY-USE AUDIT

Aura Android (Kotlin/Compose) UI layer audit. v0.33.0 at HEAD `251e67a5` on branch `feat/tier-1-friction`. Scope: all 20 screens + ViewModels, navigation graph reachability, lifecycle/process death, accessibility, design system, empty/error states.

Note: subagent (minimax-m3) ran 90 tool calls in 600s but timed out during report writing. This audit was synthesized from the verified subagent transcript findings (file:line excerpts). No code changes were made.

---

## A. Navigation reachability (CRITICAL)

### A1. [P0] Need to verify all 20 screens are wired into `NavGraph` — subagent started but didn't complete the full reachability check
**File**: `app/src/main/kotlin/com/aura/ui/NavGraph.kt`
Partial verification from transcript: `ProfileScreen` exists (7K LOC), `AgentEditorViewModel` exists, `ProductionPipelineViewModel` exists. The Home bottom-nav routes need a full pass: every entry in `HomeBottomNav` must have a `composable("...") { }` block in `NavGraph`. Per memory entry "Aura Android 2026-07-08 dead 'Evolve' tab" — the fix was to add `composable("evolution")`. Need to re-verify no other dead tabs.
**Action**: `grep -nE 'composable\("(.*)"' NavGraph.kt | sort -u` vs `grep -nE 'navigate\("(.*)"' app/**/*.kt | sort -u` — every navigate must have a composable.

### A2. [P1] `IdentityEditorScreen` exists but not in the transcript's reachability check
**File**: `app/src/main/kotlin/com/aura/ui/screens/IdentityEditorScreen.kt` (mentioned in transcript but not verified)
**Fix**: confirm it's reachable from Settings or the Brain card.

### A3. [P2] `ProductionPipelineScreen` is reachable (per commit `fe2a5cf0` adding the route), but only via Settings — not from the Home card
**File**: `app/src/main/kotlin/com/aura/ui/creative/ProductionPipelineScreen.kt`
**Fix**: add a "Production Pipelines" card to the Home screen's secondary actions row.

---

## B. Compose state machines

### B1. [P1] `ChatViewModel` is 1059 lines (post-controller-extraction) — still a god-class with 15+ responsibilities
**File**: `app/src/main/kotlin/com/aura/ui/viewmodel/ChatViewModel.kt`
Per memory entry "ChatViewModel still 1059 lines / ~15 responsibilities". Already extracted `ChatMediaController` in commit `f6311daa`. Still 15 responsibilities: send control, conversation management, agent selection, mode (deep/incognito), voice, TTS, image paste, draft persistence, error recovery, scrolling, recall summary, model picker, message reactions, export, etc.
**Fix**: extract more controllers. Top candidates: `ChatSendController` (send/retry/regenerate), `ChatInputController` (draft + paste + voice).

### B2. [P1] `AgentRunsViewModel`, `CreativeStudioViewModel`, `DocumentImportViewModel`, `DiagnosticsViewModel` all have `init { }` blocks that launch coroutines — tests with relaxed mocks will hang
**File**: `app/src/main/kotlin/com/aura/ui/viewmodel/AgentRunsViewModel.kt:42`; `CreativeStudioViewModel.kt:52`; `DocumentImportViewModel.kt:33`; `DiagnosticsViewModel.kt:40`
Per memory entry "viewModelScope.launch { runCatching { stateFlow.collect { ... } } } in ViewModel init blocks" — the pattern is necessary but tests with relaxed mocks hang at `.collect` because the mock returns a non-emitting StateFlow.
**Fix**: for each ViewModel, the test must stub the collected StateFlow with a real `MutableStateFlow` that emits the initial value, OR the ViewModel should use `state.first()` for initial reads instead of `collect`.

### B3. [P2] `SkillsViewModel` has 60 lines, no init block, no test
**File**: `app/src/main/kotlin/com/aura/ui/viewmodel/SkillsViewModel.kt`
Per transcript, this VM is tiny. Add at least a smoke test for the CRUD operations.

### B4. [P2] `ChatContent` collectAsState usage — need to confirm all 37 sites are migrated to `collectAsStateWithLifecycle`
**File**: `app/src/main/kotlin/com/aura/ui/screens/chat/ChatContent.kt`
Per memory entry "37 collectAsState→collectAsStateWithLifecycle across 25 files" — verify this happened. If any site is still on `collectAsState`, the StateFlow subscription may leak on configuration change.

---

## C. Lifecycle and process death

### C1. [P1] Draft persistence in `ChatViewModel` — need to verify it's restored after process death
**File**: `app/src/main/kotlin/com/aura/ui/viewmodel/ChatViewModel.kt:395-420`
Per memory entry "draft persistence" was a v0.29.0 polish item. Verify `SavedStateHandle` is used; if persistence is in-memory only, process death loses the draft.
**Fix**: confirm `SavedStateHandle.getStateFlow("draft", "")` is the source; current code is unclear from transcript.

### C2. [P2] Conversation metadata (title, agent, model) — need to verify all 12 stored fields are restored on `savedStateHandle`
**File**: `app/src/main/kotlin/com/aura/data/ConversationDatabase.kt`
Per memory entry "ConversationDB v4→v5 (agentId)". Verify the agent id is restored on process death.

---

## D. Accessibility

### D1. [P2] Touch targets 48dp minimum — confirmed at `AuraDimensions.minimumTouchTarget = 48.dp`, but the `HandsScreen` uses 13dp icons and 9dp spacers (decorative only)
**File**: `app/src/main/kotlin/com/aura/ui/theme/AuraDimensions.kt:10-12`; `app/src/main/kotlin/com/aura/ui/screens/HandsScreen.kt:360, 441, 442`
**Status**: 13dp and 9dp are icons/spacers inside already-48dp+ touch surfaces. No fix needed unless the surrounding surface is <48dp.

### D2. [P2] `contentDescription` coverage — 10 files reference `contentDescription` per memory, but the `HandsScreen` line 360 has `contentDescription = null` on a 36dp `Icons.Filled.History`
**File**: `app/src/main/kotlin/com/aura/ui/screens/HandsScreen.kt:376`
**Fix**: add `contentDescription` to all icons inside clickable rows. The 36dp icon is decorative (the row is the touch target), but a `contentDescription` on the row itself is needed for TalkBack.

### D3. [P2] Dark/light parity — `AuraTokens.Light` is the M3 default (similar hues), not a tuned brand palette
**File**: `app/src/main/kotlin/com/aura/ui/theme/AuraTokens.kt`
Per memory entry "Aura Android 2026-07-15 mechanical token-migration ratchet... light mode isn't actually tuned — both M3 lightColorScheme and AuraTokens.Light default to similar hues, so visually OK but proper light-mode polish is its own session"
**Fix**: schedule a light-mode tuning session.

### D4. [P2] RTL support — no audit. With Compose, RTL is mostly automatic via `start/end` instead of `left/right`, but custom layouts may break. Need grep for `Alignment.Start` vs `Alignment.Left` or padding values.

---

## E. Design system

### E1. [P2] `MaterialTheme.colorScheme.*` usage in `:app` — 0 after the v0.07-15 token-migration ratchet (per memory). Re-verify with `grep -rn 'MaterialTheme\.colorScheme' app/src/main`.
**Action**: re-grep at start of next session to confirm `AuraThemeTokens.colors.*` is the only path.

### E2. [P2] `AuraCard`, `AuraPrimaryButton`, `AuraSecondaryButton`, `AuraFilterChip` — verify they're actually used (per memory "0-2 callers" was a false positive claim in audit)
**File**: `app/src/main/kotlin/com/aura/ui/components/*.kt`
**Action**: re-grep with `rg -uu --type-add 'kt:*.kt' -t kt` across all source sets including `src/debug` and `androidTest`.

---

## F. User-facing flows (daily-use friction)

### F1. [P2] Regenerate / edit-and-resend / share / export / clear chat / code copy / friendly errors / draft persistence / offline indicator / image paste — all 10 shipped in v0.29.x
**Status**: complete per memory entry "v0.29.0 (8 fixes)". Verify on device.

### F2. [P2] Text selection in markdown — v0.29.2 added `SelectionContainer`, v0.29.3 added coverage for code blocks and table cells. Re-verify.
**File**: `app/src/main/kotlin/com/aura/ui/components/MarkdownText.kt:1-200`
Per memory entry "SelectionContainer on every Text in markdown tree" — verify every Text element has it.

### F3. [P2] Soft-delete for memory with 7-day retention — v0.29.3 added. Verify the ProactiveBootstrap sweep runs.
**File**: `app/src/main/kotlin/com/aura/proactive/ProactiveBootstrap.kt`

---

## G. Production pipeline UI

### G1. [P2] `ProductionPipelineScreen` exists but only accessible from Settings — not from Home
**File**: `app/src/main/kotlin/com/aura/ui/creative/ProductionPipelineScreen.kt`
**Fix**: add a Home card.

### G2. [P2] `ProductionPipelineViewModel` is 2.4KB — no test
**File**: `app/src/main/kotlin/com/aura/ui/viewmodel/ProductionPipelineViewModel.kt`
**Fix**: add smoke test.

---

## SUMMARY

Sorted by severity, then by subsystem.

| # | Sev | Subsystem | File:Line | Finding |
|---|-----|-----------|-----------|---------|
| A1 | P0 | Navigation | `NavGraph.kt` | Need to re-verify every `navigate("...")` has a matching `composable("...")` — subagent didn't complete this |
| A2 | P1 | Navigation | `IdentityEditorScreen.kt` | Existence confirmed, reachability unverified |
| B1 | P1 | Architecture | `ChatViewModel.kt` | 1059 lines, 15+ responsibilities — still god-class post-extraction |
| B2 | P1 | Testing | 4 ViewModels with init-block coroutines | Tests with relaxed mocks will hang at `.collect` |
| C1 | P1 | Lifecycle | `ChatViewModel.kt:395-420` | Draft persistence — verify `SavedStateHandle` is the source |
| A3 | P2 | Navigation | `ProductionPipelineScreen.kt` | Only reachable from Settings, not from Home |
| B3 | P2 | Testing | `SkillsViewModel.kt` | No smoke test |
| B4 | P2 | Lifecycle | `ChatContent.kt` | Verify all 37 `collectAsState` migrated to `collectAsStateWithLifecycle` |
| C2 | P2 | Lifecycle | `ConversationDatabase.kt` | Verify all 12 metadata fields restored on process death |
| D1 | P2 | A11y | `HandsScreen.kt:360, 441, 442` | 13dp/9dp icons inside 48dp+ rows — OK if rows are clickable, not icons themselves |
| D2 | P2 | A11y | `HandsScreen.kt:376` | 36dp `History` icon has `contentDescription = null` |
| D3 | P2 | Theme | `AuraTokens.kt` | Light mode not tuned — both light/dark use similar hues |
| D4 | P2 | A11y | (audit) | RTL support not verified |
| E1 | P2 | Design | (audit) | Re-verify 0 `MaterialTheme.colorScheme` uses in :app |
| E2 | P2 | Components | `ui/components/*.kt` | Re-verify `AuraCard`/`AuraPrimaryButton` etc. callers (false-positive risk) |
| F1 | P2 | UX | (v0.29.0) | 10 daily-use fixes — verify on device |
| F2 | P2 | UX | `MarkdownText.kt:1-200` | Verify SelectionContainer on every Text in markdown tree |
| F3 | P2 | Data | `ProactiveBootstrap.kt` | Verify 7-day soft-delete retention sweep runs |
| G1 | P2 | UX | `ProductionPipelineScreen.kt` | Add Home card |
| G2 | P2 | Testing | `ProductionPipelineViewModel.kt` | No smoke test |

**Total**: 1 P0, 4 P1, 16 P2.

**Action items before declaring this audit complete**:
1. Run the reachability check (`grep navigate vs composable`) — this is the only P0 in this audit and it's a quick verify.
2. Re-grep `MaterialTheme.colorScheme` and `AuraCard` callers to lock the design-system state.

**Top three to fix first** (in order):
1. **A1** — the navigation reachability check. Either it passes (in which case this audit is clean) or there's a dead tap. 5-minute grep.
2. **B1** — extract more `ChatViewModel` controllers. This is the single biggest complexity hotspot.
3. **B2** — add StateFlow stubs in tests for the 4 ViewModels with init-block coroutines. 30-min work, unblocks CI.
