# Aura Android Deep Review — Phase 3: Fix Everything

Branch: `feat/tier-1-friction`  
Base: `df08068d` (v0.38.3, 1,427 tests green)  
Goal: address all P0/P1/P2 findings from the independent deep audit, not just the top 3.

---

## 0. Ground Rules

- Work from current source (`git head`), not memory or stale reports.
- Each numbered item = one atomic commit unless explicitly grouped.
- Add regression tests for every P0 and every changed contract.
- Final gate: `:aura-core:testDebugUnitTest :app:testDebugUnitTest :app:assembleDebug --no-daemon` green.
- Version bump at the end: `0.38.3 → 0.38.4`.

---

## P0 — Real Bugs

### 1. StrategyBandit is inert in production

**Problem:** `ChatSendController` declares `strategyBandit: StrategyBandit? = null` and `ChatViewModel` constructs it manually without passing it. The classifier/selectStrategy/recordOutcome code never runs, so the Room DB, module, and tests are dead infrastructure.

**Evidence:**
- `app/src/main/kotlin/com/aura/ui/viewmodel/ChatSendController.kt:90`
- `app/src/main/kotlin/com/aura/ui/viewmodel/ChatViewModel.kt:358-388` (no `strategyBandit =` arg)
- `ChatSendController.kt:307-322` and `:442-462` are the only call sites; they all null-check.

**Fix:**
- Add `strategyBandit: StrategyBandit?` as a required constructor parameter in `ChatSendController` (remove default).
- Pass `strategyBandit` from `ChatViewModel` constructor injection into `ChatSendController`.
- Verify Hilt provides it (it does via `StrategyBanditModule` + `@Inject` constructor).

**Files:**
- `app/src/main/kotlin/com/aura/ui/viewmodel/ChatSendController.kt`
- `app/src/main/kotlin/com/aura/ui/viewmodel/ChatViewModel.kt`

**Tests:**
- Add `ChatSendControllerStrategyTest` that stubs `strategyBandit.selectStrategy()` returning `CREATIVE_PASS`, assert `loop.run()` is called with `maxSteps=3`.
- Add regression test that `strategyBandit.recordOutcome(..., success=true)` is called on `Done` event.

**Commit:** `fix(controller): inject StrategyBandit into ChatSendController so it actually runs`

---

### 2. ChatGPT + Gemini parallel tool calls still mis-route

**Problem:** `OpenAiSseParser` now tracks `index → id`, but `ChatGptSubscriptionProvider` and `GeminiProvider` parse tool calls independently and don't map `tool_calls[index]`. With two parallel tool calls, argument deltas can land on the wrong `ToolCall`.

**Evidence:**
- `ChatGptSubscriptionProvider.kt`: parses `tool_calls` delta but doesn't read `index` field.
- `GeminiProvider.kt:128`: emits one `functionCall` per part with no id/call tracking.

**Fix:**
- Refactor `ChatGptSubscriptionProvider` to reuse `OpenAiSseParser` (it's the same SSE format).
- Add a tool-call index→id map to `GeminiProvider` for `functionCall` parts, and emit deltas by id.

**Files:**
- `aura-core/src/main/kotlin/com/aura/providers/ChatGptSubscriptionProvider.kt`
- `aura-core/src/main/kotlin/com/aura/providers/GeminiProvider.kt`
- `aura-core/src/main/kotlin/com/aura/providers/OpenAiSseParser.kt` (minor export changes if needed)

**Tests:**
- Extend `ChatGptSubscriptionToolCallTest` with 2 interleaved parallel tool calls.
- Add `GeminiParallelToolCallTest` with 2 `functionCall` parts in one response.

**Commit:** `fix(providers): route parallel tool-call deltas correctly for ChatGPT and Gemini`

---

### 3. OpenAI SSE cancel race wastes billable tokens

**Problem:** `OpenAiCompatProvider.kt:66` creates `src`, line `89` assigns `activeEventSource = src`. A `cancel()` fired between creation and assignment sees `activeEventSource == null` and the remote stream keeps generating.

**Evidence:**
- `OpenAiCompatProvider.kt:66`, `89`, `106`, `174`.

**Fix:**
- Create the `EventSource` with a listener that captures its own `eventSource` reference.
- Or: assign `activeEventSource` atomically before starting the stream using a private factory wrapper.
- Ensure `cancel()` cancels the source created in flight and nulls it.

**Files:**
- `aura-core/src/main/kotlin/com/aura/providers/OpenAiCompatProvider.kt`
- Same pattern in `CustomOpenAiCompatProvider.kt` if applicable.

**Tests:**
- Add `OpenAiCompatCancelRaceTest` using `MockWebServer` + a slow SSE stream; call `cancel()` from another thread immediately after `chat()` returns; assert connection is closed and no further chunks processed.

**Commit:** `fix(providers): eliminate OpenAI SSE cancel race so stop actually aborts the stream`

---

## P1 — Missing Surfaces / Wiring

### 4. Triggers have no user-facing UI

**Problem:** `UserPreferences` stores `triggers`, `TriggerWorker` runs them, but there is no screen to create/edit/delete triggers. `TriggerCondition.LocationEntered` is a TODO stub. Only programmatic usage is possible.

**Evidence:**
- No `TriggersScreen.kt` in `app/src/main/kotlin/com/aura/ui/screens`.
- `aura-core/src/main/kotlin/com/aura/triggers/TriggerEngine.kt:22` returns `null` for `LocationEntered`.

**Fix:**
- Create `TriggersScreen.kt` under `app/src/main/kotlin/com/aura/ui/screens/`:
  - List existing triggers with toggle + delete.
  - "Add trigger" FAB that opens `TriggerEditorDialog`.
  - Editor supports conditions: TimeOfDay, BatteryLow, ChargingStarted, LocationEntered, AppOpened, NotificationReceived.
  - Actions: RunHand, StartChat, ScheduleTask.
  - For LocationEntered: request location permission, use `FusedLocationProviderClient` to geofence a radius.
- Wire `TriggerEngine.LocationEntered` to a real geofence implementation using `GeofencingClient` and a `BroadcastReceiver`.
- Add `TriggersViewModel` with `UserPreferences.triggers` read/write.
- Add a card in `HomeSecondaryActions` or Settings to reach `TriggersScreen`.

**Files:**
- `app/src/main/kotlin/com/aura/ui/screens/TriggersScreen.kt`
- `app/src/main/kotlin/com/aura/ui/screens/TriggerEditorDialog.kt`
- `app/src/main/kotlin/com/aura/ui/viewmodel/TriggersViewModel.kt`
- `aura-core/src/main/kotlin/com/aura/triggers/TriggerEngine.kt`
- `app/src/main/kotlin/com/aura/ui/screens/home/HomeSecondaryActions.kt`
- `app/src/main/kotlin/com/aura/ui/screens/SettingsScreen.kt`

**Tests:**
- `TriggersViewModelTest`: add/edit/delete triggers, verify `UserPreferences.setTriggers` called.
- `TriggerEngineTest`: `LocationEntered` produces a non-null check result when location matches.

**Commit:** `feat(triggers): add TriggersScreen + editor + LocationEntered geofence`

---

### 5. StrategyBandit data is not backed up

**Problem:** `StrategyBanditEntity` lives in `strategy_bandit.db` but has no backup type. Device migration loses learned strategy weights.

**Evidence:**
- `aura-core/src/main/kotlin/com/aura/agent/StrategyBanditEntity.kt` has no `*Backup` data class.
- `aura-core/src/main/kotlin/com/aura/backup/AuraBackup.kt` doesn't reference it.

**Fix:**
- Add `StrategyBanditBackup` data class with fields: `category`, `strategy`, `alpha`, `beta`, `lastUpdated`.
- Add `toBackup()` / `toEntity()` mappers on `StrategyBanditEntity`.
- Add `strategyBandit` list to `AuraBackup` schema v14 (bump `SCHEMA_VERSION`).
- Update `BackupManager.snapshot()` to read all rows from `StrategyBanditStore`.
- Update `BackupManager.restore()` to write them back and `purgeAll()` to clear the table.

**Files:**
- `aura-core/src/main/kotlin/com/aura/agent/StrategyBanditEntity.kt`
- `aura-core/src/main/kotlin/com/aura/agent/StrategyBanditBackup.kt` (new)
- `aura-core/src/main/kotlin/com/aura/backup/AuraBackup.kt`
- `aura-core/src/main/kotlin/com/aura/backup/BackupManager.kt`
- `aura-core/src/main/kotlin/com/aura/agent/StrategyBanditStore.kt`

**Tests:**
- Update `AuraBackupRoundTripTest` to include strategy-bandit rows and assert they survive restore.

**Commit:** `fix(backup): include StrategyBandit weights in backup/restore`

---

### 6. AgentRun DAG branches execute sequentially

**Problem:** `AgentRunExecutorWorker` iterates `ready` steps in a `for` loop. Independent branches in a hand/pipeline run one at a time instead of concurrently.

**Evidence:**
- `aura-core/src/main/kotlin/com/aura/agentrun/AgentRunExecutorWorker.kt:116-150`.

**Fix:**
- Replace sequential `for (step in ready)` with `coroutineScope { ready.map { async { executeStep(...) } }.awaitAll() }`.
- Keep per-step status writes sequential via `Mutex` on `agentRunStore` mutators (already partially guarded; verify).
- Add per-step timeout so one slow step doesn't block the whole batch.

**Files:**
- `aura-core/src/main/kotlin/com/aura/agentrun/AgentRunExecutorWorker.kt`

**Tests:**
- Add `AgentRunExecutorParallelTest` with a 3-branch DAG and a slow tool; assert all three branches start before the first completes.

**Commit:** `perf(agentrun): execute independent ready steps in parallel`

---

## P2 — Cleanup / Coverage

### 7. Placeholder tests still shipping

**Problem:** `SpeechToTextTest.kt` is `assertFalse(false) // placeholder`. `TtsTest.kt` historically was a placeholder (verify current state).

**Evidence:**
- `aura-core/src/test/kotlin/com/aura/voice/SpeechToTextTest.kt:24` (or equivalent).

**Fix:**
- Replace placeholder with real tests:
  - `SpeechToTextTest`: init returns supported languages, transcribe stub emits text, error emits error code.
  - `TtsTest`: speak enqueues utterance, stop cancels queue, onComplete callback fires.
- Delete if the class under test has no testable surface (unlikely; both have public APIs).

**Files:**
- `aura-core/src/test/kotlin/com/aura/voice/SpeechToTextTest.kt`
- `aura-core/src/test/kotlin/com/aura/voice/TtsTest.kt`

**Commit:** `test(voice): replace SpeechToText/TTS placeholder tests with real coverage`

---

### 8. ScheduleScreen still uses `collectAsState()`

**Problem:** Every other screen migrated to `collectAsStateWithLifecycle()` to avoid leaking collectors across config changes. `ScheduleScreen` didn't get migrated.

**Evidence:**
- `app/src/main/kotlin/com/aura/ui/screens/ScheduleScreen.kt` uses `collectAsState()`.

**Fix:**
- Add `lifecycle-runtime-compose` dependency if not already present (it should be from prior migration).
- Replace all `collectAsState()` calls in `ScheduleScreen.kt` with `collectAsStateWithLifecycle()`.

**Files:**
- `app/src/main/kotlin/com/aura/ui/screens/ScheduleScreen.kt`

**Tests:**
- No new tests needed; rely on compile + existing UI test suite.

**Commit:** `refactor(ui): ScheduleScreen uses collectAsStateWithLifecycle`

---

### 9. `runCatching` hygiene pass

**Problem:** 265 `runCatching {` sites across core/app. Spot checks show several network/DB paths swallow failures silently (no `.onFailure { Log.w(...) }`).

**Evidence:**
- `grep -rnE 'runCatching\s*\{' aura-core/src/main app/src/main` = 265.

**Fix:**
- Audit the top 50 `runCatching` sites by risk:
  - Production I/O (network, DB, file, WorkManager) must log via `.onFailure { Log.w(...) }` unless the result is returned to the user.
  - User-facing paths should not swallow; return the error via state/result.
- Fix only the ones that are silently swallowing (estimated 15-20 sites).

**Files:**
- 15-20 files across `aura-core/src/main` and `app/src/main`.

**Tests:**
- No new tests; this is logging hygiene. Verify with lint + existing tests.

**Commit:** `chore: add logging to silent runCatching sites in I/O paths`

---

## Commit Order (dependency-aware)

1. P0 #3 — OpenAI SSE cancel race (no deps)
2. P0 #2 — ChatGPT/Gemini parallel tool calls (no deps)
3. P0 #1 — StrategyBandit injection into `ChatSendController` (no deps, touches app)
4. P1 #6 — AgentRun parallel execution (no deps, touches core)
5. P1 #5 — StrategyBandit backup (depends on P0 #1 but can ship independently; logically pairs with it)
6. P1 #4 — Triggers UI (largest; depends on no other item; ship after the smaller items)
7. P2 #8 — ScheduleScreen lifecycle
8. P2 #7 — Voice placeholder tests
9. P2 #9 — runCatching hygiene

## Final Verification

After all commits:

```bash
export JAVA_HOME="C:/Program Files/Eclipse Adoptium/jdk-21.0.11.10-hotspot"
./gradlew :aura-core:testDebugUnitTest :app:testDebugUnitTest :app:assembleDebug --no-daemon
```

Target: 1,440+ tests, 0 failures.

## Version / Release

- Bump `versionCode` 45 → 46 and `versionName` "0.38.3" → "0.38.4" in `app/build.gradle.kts`.
- Build APK: `app/build/outputs/apk/debug/app-debug.apk` → `releases/aura-debug-v0.38.4.apk`.
- Write `releases/RELEASE_NOTES_v0.38.4.md`.
- Commit, push, create GitHub Release.
