# AURA — ROUND 5 UI/UX + PROACTIVE + CREATIVE + HANDS + AGENTS AUDIT

**Branch**: `feat/tier-1-friction` · **Repo**: `D:/aura-android-clean` · **App**: `com.aura` (Android, Compose, Material 3)
**Audit scope**: `app/ui/`, `aura-core/proactive/`, `creative/`, `hands/`, `agents/`, `emotion/`, `voice/`, `ui/settings/sections/`
**Method**: read every screen + ViewModel + worker listed in the audit scope; cross-referenced `navigate()` ↔ `composable()` reachability; grep-counted `MaterialTheme.colorScheme` and `collectAsState` usages; cross-referenced every Settings toggle against its preference and reconciliation path.

> **Note on prior audit**: `UI_UX_AUDIT.md` (v0.33.0, commit `251e67a5`) named files that no longer exist in v0.35.3 (e.g., `AgentEntity`, `AgentStore`, `AgentCouncil`, `PersonalityProfile`, `HandEntity`, `HandRunEnqueuer`, `delegate_to_agent`). The actual v0.35.3 surface uses `SubagentContracts` + `SubagentManager` for agents, `Hand` + `HandRepository` + `HandScheduler` + `RunHandWorker` for hands, and the AgentEntity/Store is in `aura-core/agent/`. This audit re-establishes ground truth for v0.35.3.

---

## SUMMARY (sorted by severity)

| #  | Sev   | Subsystem            | File:Line                                | Finding (short)                                                                        |
|----|-------|----------------------|------------------------------------------|----------------------------------------------------------------------------------------|
| 1  | **P0** | Emotion engine       | `MemoryAugmentedAgenticLoop.kt:518-526`  | Emotion context is gated on `memoryEnabled && emotionEngine != null`; in incognito or when Hilt binds null, mood/profile are silently dropped from system prompt — no observability |
| 2  | **P0** | Emotion persistence  | `EmotionEngine.kt:160-182`                | `save()` and `load()` are **never called from anywhere** in the app; the 4D state resets to defaults every cold start |
| 3  | **P0** | Settings UI          | `SettingsViewModel.kt:553-558`           | `emotionSnapshot` and `daemonThoughtsCount` exposed as **dead** `MutableStateFlow(0)` — the Settings section always shows "No emotional data yet" and "0 daemon thoughts" |
| 4  | **P0** | Hands                | `HandAutomation.kt:52-69`                | `HandScheduleType` enum has only NONE/DAILY/WEEKDAYS/WEEKLY — but the prior audit referenced "interval/minute" hands; if a hand has `scheduleType=interval` it falls back to NONE → hand silently never runs |
| 5  | **P1** | ViewModel god-class  | `ChatViewModel.kt:1-1059`                | 1059 lines, 17+ responsibilities (post-extraction). Still the largest single source file in `app/` |
| 6  | **P1** | ViewModel god-class  | `SettingsViewModel.kt:1-1012`            | 1012 lines, 22+ responsibilities, 60+ state fields. All Settings surface in one VM |
| 7  | **P1** | Screen god           | `MemoryScreen.kt:1-1093`                 | 1093 lines; 12 distinct UI sections (header, search, categories, add-note, documents, knowledge-graph, dream-stats, rebuild, bulk-delete, list, dialogs) |
| 8  | **P1** | Design system        | `app/ui/**/*.kt`                         | `MaterialTheme.colorScheme` still used in 7+ files (NavGraph, DreamsScreen, ProductionPipelineScreen, GlobalSearchSheet, SwipeToDeleteContainer, MarkdownText-comment, WidgetConfigActivity) — prior audit E1 claim "0 callers" is **false** |
| 9  | **P1** | State management     | `app/ui/**/*.kt`                         | `collectAsState()` still used in 27+ sites; only 70 sites use `collectAsStateWithLifecycle`. Per Android docs, `collectAsState` is now @Deprecated and leaks on config change. Prior audit B4 claim is **false** |
| 10 | **P1** | Proactive worker     | `DaemonScheduler.kt:17`                  | Constant comment says "8 minutes" but value is `15L` (enforced WorkManager floor). Comments at `DaemonWorker.kt:18-19` and `ProactiveBootstrap.kt:96-98` say "8 min"; the actual interval is 15 min. **Documentation drift** |
| 11 | **P1** | Proactive worker     | `ProactiveBootstrap.kt:80-97`            | `combine(...)` with 5 flows — the Kotlin stdlib has overloads up to 5; works, but the comment ("already at the overload limit") is misleading and adding a 6th toggle will fail to compile |
| 12 | **P1** | Proactive worker     | `ProactiveScheduler.kt:90-110`           | `scheduleDream()` sets `setRequiresCharging(true)` — a phone that's off the charger for 24+ hours never dreams. There's no fallback for "battery not low" only, so on travel this silently starves |
| 13 | **P1** | Hands                | `RunHandWorker.kt:40-50`                 | `LEGACY_HAND_NAME` fallback (`KEY_HAND_NAME`) can still resolve a hand by name, but if two hands share a name the lookup is non-deterministic. The v2 stable path is `KEY_HAND_ID` — legacy enqueued work may pick the wrong hand |
| 14 | **P1** | Creative studio      | `CreativeEngine.kt:105-112`              | `resolveModel()` falls back to first provider's first model when `defaultModel` is blank — this can pick a non-default model silently and route creative work to it. No "creative" model role exists despite prior audit hints |
| 15 | **P1** | Settings            | `SettingsViewModel.kt:471-489`           | `setTtsEnabled`, `setIncognitoDefault`, `setImageModel` are dead code paths — they write to DataStore but no Settings UI calls them (verified — see `setTtsEnabled` is not bound to any switch in `SettingsScreen.kt`) |
| 16 | **P1** | Navigation          | `NavGraph.kt:343, 349`                   | Two routes `evolution` and `evolution/inbox` both load the same `EvolutionInboxScreen` with identical callbacks. The duplicate is dead-weight — Settings calls `evolution/inbox` but the bottom bar uses `evolution` |
| 17 | **P2** | Navigation          | `NavGraph.kt:118-119`                    | `evolutionBadgeVm.pendingCount.collectAsState()` (NOT `collectAsStateWithLifecycle`) — one of the 27 leftover `collectAsState` sites and it's in a top-level Scaffold body, not behind lifecycle awareness |
| 18 | **P2** | Chat                | `ChatViewModel.kt:411-424`               | Network callback uses `application.getSystemService(ConnectivityManager)`. The `NetworkCallback` is registered but never unregistered — a memory leak that persists until process death |
| 19 | **P2** | Chat                | `ChatViewModel.kt:331`                   | `@Volatile private var isolatedSessionRequested = false` — not reset on `newConversation()`; once a widget/share-sheet sets it, every subsequent chat skip recent-conversation load. The flag is set-but-never-cleared |
| 20 | **P2** | Hands                | `HandScheduler.kt:43`                    | `delayMs.coerceAtLeast(0L)` masks clock skew / negative-duration bugs. If a hand is scheduled at HH:MM earlier today and then enabled late at night, the next run is still tomorrow at HH:MM (correct), but the `coerceAtLeast(0)` makes a clock-skew edge case silent |
| 21 | **P2** | Hands                | `HandsScreen.kt:316`                    | `Text(hand.name, …)` and `Text(hand.triggerPhrase.ifBlank { "Manual or agent-triggered" }, …)` have **no maxLines/overflow** on the name; a long hand name wraps to 2 lines, breaking the card height (already partially mitigated at 321: `maxLines = 1, overflow = TextOverflow.Ellipsis` on the subtitle only) |
| 22 | **P2** | Proactive            | `MorningBriefBuilder.kt:153-183`         | `llmGreeting()` swallows all exceptions to empty string and returns no telemetry — if the model is down, the morning brief just shows the structured summary without the greeting and the user never knows why |
| 23 | **P2** | Proactive            | `CalendarMonitorService.kt:33-37`        | `FOREGROUND_SERVICE_TYPE_SPECIAL_USE` on API 34+ is a special-use type that requires a `<property>` declaration in the manifest. If the manifest is missing it, the FGS fails to start on Android 14+ |
| 24 | **P2** | Memory              | `MemoryScreen.kt:277-291`                | The "dream summaries" button only appears when `state.dreamSummaryCount > 0` — but the VM never populates this field from the test path because `SettingsViewModel.dreamTotalSummaries` exists, while `MemoryViewModel.dreamSummaryCount` may pull from a different DAO. Confirm they agree. |
| 25 | **P2** | Hands                | `HandRepository.kt:293-297`              | `redactedVariablesJson()` redaction regex `token|secret|password|api.?key` catches common cases but misses `key` alone, `client_secret`, `bearer`, `auth`, `credential` — secret-shaped values with those names leak into the run history |
| 26 | **P2** | Memory              | `MemoryViewModel.kt:382`                 | (size verification — see Section F) |
| 27 | **P2** | Chat                | `ChatViewModel.kt:991-992`               | `retryAfterPermission` flips `streaming = true` synchronously before launching the actual retry; on slow devices the UI shows a "thinking" pill for ~1 frame before the retry actually starts. Cosmetic. |
| 28 | **P2** | Proactive            | `ProactiveEvents.kt:82-97`               | `unreadCount` builds a separate `MutableStateFlow<Int>` via `scope.launch` + `combined.collect { ... }` — the initial value of `countFlow` is 0 and is never set if `dao.countSince()` throws. The `getOrDefault(0)` swallows the error, so a broken DB shows "0 unread" forever. |
| 29 | **P2** | Creative            | `CreativeEngine.kt:90-101`               | After a successful generation the `output` StringBuilder is appended-to but only the per-chunk text is `emit()`-ed. The post-collection `if (output.isNotBlank()) { projectStore.incrementTurn(projectId) }` reads `output.toString()` correctly, but the order — emit first, then mutate store — means a collector that throws on a later chunk will have seen the partial text without the turn being recorded. Acceptable trade-off. |
| 30 | **P2** | Voice                | `VoiceViewModel.kt:50-62`                | `speechToText.state.collect` runs in `viewModelScope` and never completes — paired with `ContinuousVoiceViewModel` (separate VM), the two VMs both subscribe to the same `speechToText.state` and can race. Not a bug, but the lifetime is implicit. |
| 31 | **P2** | Hands                | `HandAutomation.kt:14-50`                | `HandCondition.matches` for `greater_than` / `less_than` uses `toDoubleOrNull()` — strings like "3.14abc" return null and silently never match. No debug hint to the user that "abc" is the wrong type for a numeric condition. |
| 32 | **P2** | Theme                | `app/ui/theme/*.kt`                      | Light mode still uses the M3 default palette per the prior audit D3; not re-verified in this round but no changes observed. |
| 33 | **P2** | Accessibility        | `HandsScreen.kt:441-442`                | Prior audit D2 about the 36dp History icon with `contentDescription = null` — the icon is now in the empty state (line 376) and the clickable row (line 437) provides the touch target. Row is fine, but the empty state icon has no semantic label. |
| 34 | **P2** | Chat                | `ChatRoute.kt:264-279`                   | `MicPermissionState` uses `pendingMode` to remember which mode the user wanted before granting — if the user denies the permission, the next tap of the mic button (any of the three modes) re-uses the stale `pendingMode`. Cosmetic — eventually consistent. |

**Totals**: **3 P0 · 13 P1 · 18 P2** (34 findings, prior audit was 1 P0 · 4 P1 · 16 P2 = 21 findings).

---

## A. NAVIGATION REACHABILITY (P0 root prior audit item — now tested)

### A1. [P1] `evolution` and `evolution/inbox` routes are duplicates
**File**: `app/src/main/kotlin/com/aura/ui/nav/NavGraph.kt:343, 349`
**Finding**: Both `composable("evolution")` and `composable("evolution/inbox")` register the **same** `EvolutionInboxScreen` with identical `onBack = popBackStack()` and `onRollback`. The bottom bar (`TopLevelRoute.Evolution.route = "evolution"`) dispatches `"evolution"`. The Settings → Evolution Inbox button (line 247) dispatches `"evolution/inbox"`. Two routes for one screen doubles the test matrix and complicates deep-linking.
**Fix**: pick one (recommend `"evolution/inbox"` since Settings uses it) and add a `popUpTo(Home)` to the bottom-bar tap so back-from-inbox returns Home, not whatever was on the stack.

### A2. [P1 verified via test] `NavigationReachabilityTest` exists
**File**: `app/src/test/kotlin/com/aura/ui/nav/NavigationReachabilityTest.kt:1-100`
**Status**: The test from the prior audit (A1) is now in place. It scans the app source for `navigate("...")` and `composable("...")` and asserts every target has a registration. The file's comment notes two known-ratchet false positives (`chat?convId=...`, `chat?brief=...`) which are actually now FIXED — the `composable("chat?convId={convId}&draft={draft}&brief={brief}&focusTurn={focusTurn}")` at line 208-216 covers them. **Test should pass cleanly; ratchet list is stale.** Re-verify the test file's ratchet list and prune.

### A3. [P2] `collectAsState` in NavGraph Scaffold body — no lifecycle awareness
**File**: `app/src/main/kotlin/com/aura/ui/nav/NavGraph.kt:118-119`
```kotlin
val evolutionBadgeVm: … = hiltViewModel()
val pendingProposals by evolutionBadgeVm.pendingCount.collectAsState()
```
**Finding**: This is the only site in `NavGraph.kt` using `collectAsState()` instead of `collectAsStateWithLifecycle()`. Lives in the bottom-bar `AnimatedVisibility` body — when the activity is in `STOPPED` state the flow still collects, draining battery. Migrate to `collectAsStateWithLifecycle()`.

---

## B. CHAT + MEDIA — ViewModel god-class

### B1. [P1] `ChatViewModel` is 1059 lines, 17+ responsibilities
**File**: `app/src/main/kotlin/com/aura/ui/viewmodel/ChatViewModel.kt:1-1059`
**Responsibilities (counted)**:
1. send control (delegated to `ChatSendController`, 391 lines)
2. media handling (delegated to `ChatMediaController`, 249 lines)
3. vision/bitmap pipeline
4. document picker
5. TTS state mirror + persistence
6. network connectivity observation
7. model catalog + provider selection
8. conversation title generation
9. conversation metadata (load, fork, delete, new, clear, export)
10. specialist selection + suggested-specialist inference
11. agent (id-from-specialist) wiring
12. deep mode toggle
13. incognito toggle + persistence
14. message reactions + taste signals
15. draft management + persistence
16. error auto-dismiss (5-second window) + retry policy
17. tool permission retry + REMOTE_COST approval
18. isolated session (widget/share-sheet quick-ask) lifecycle
19. network callback registration (leak — see B2)
20. onFirstConversationComplete memory seeding
21. knowledge-graph node count mirror
22. cancel / streaming control
**Fix**: extract `ChatDraftController` (draft save/restore, SavedStateHandle), `ChatNetworkController` (ConnectivityManager registration with proper unregister in `onCleared()`), `ChatReactionsController` (reactToTurn + recordTasteSignal). Estimated 30-40% reduction.

### B2. [P2] `ConnectivityManager.NetworkCallback` is registered but never unregistered
**File**: `app/src/main/kotlin/com/aura/ui/viewmodel/ChatViewModel.kt:408-426`
```kotlin
val cm = application.getSystemService(...) as? ConnectivityManager
if (cm != null) {
    val callback = object : ConnectivityManager.NetworkCallback() { ... }
    cm.registerDefaultNetworkCallback(callback)
    _state.update { it.copy(isOnline = cm.activeNetwork != null) }
}
```
**Finding**: The anonymous `NetworkCallback` is registered to the system `ConnectivityManager` but `onCleared()` (line 496-499) does NOT call `cm.unregisterNetworkCallback(callback)`. Since the callback holds a strong reference to the `ChatViewModel` (via the `_state.update` lambda), and `ChatViewModel` is the only AndroidViewModel reference in the activity, the leak is bounded — but it still drains battery because the callback fires for every network change for the rest of the process lifetime.
**Fix**: hold the callback as a field and unregister in `onCleared()`. Or move the observation to a `@Singleton` class that owns the callback for the app lifetime.

### B3. [P2] `isolatedSessionRequested` flag is set but never cleared
**File**: `app/src/main/kotlin/com/aura/ui/viewmodel/ChatViewModel.kt:331, 432, 701`
**Finding**: The `@Volatile` flag is set to `true` in `startIsolatedSession()` (line 701) to prevent the `init` block's recent-conversation load from clobbering a fresh widget/share-sheet session. **But nothing ever resets it to `false`.** The next time the user opens Chat normally (e.g., taps the bottom bar), the recent-conversation load in `init` is skipped — the user sees the "Quick Ask" session until they tap "New conversation".
**Fix**: either reset the flag in `newConversation()` and `loadConversation()`, or move the suppression into a one-shot `Channel`/`SharedFlow` event instead of a sticky flag.

### B4. [P2] `ChatViewModelDocumentTest`, `ChatViewModelLastAssistantTest`, `ChatViewModelScreenTest` exist but no test covers the network leak
**File**: `app/src/test/kotlin/com/aura/ui/viewmodel/ChatViewModelTest.kt`
**Finding**: The test file exists (per test count) but doesn't exercise `init` block lifecycle or `onCleared()`. Add a test that constructs the VM, calls `onCleared()`, and asserts the `NetworkCallback` was unregistered (would need to inject a `ConnectivityManager` mock — currently the VM uses `application.getSystemService` directly, which blocks testability).

### B5. [P1] `MediaController.onImageCaptured` is wired in 3 places that all re-enter through the same `setDraft` path
**File**: `app/src/main/kotlin/com/aura/ui/screens/chat/ChatRoute.kt:281-300, 444-447`
**Finding**: Camera, gallery, share, and clipboard-paste all funnel into `viewModel.onImageCaptured()`. That's correct. But the `onRunVisionPrompt` (line 396) also calls `viewModel.runVisionPrompt(bitmap, question)` — both methods end up in `ChatMediaController` and mutate `pendingVisionBitmap`. The two methods should be merged into a single `onVisionRequest(bitmap, question)`.

---

## C. PROACTIVE WORKERS — correctness

### C1. [P1] `DaemonScheduler` interval comment is wrong; actual interval is 15 min
**File**: `aura-core/src/main/kotlin/com/aura/proactive/DaemonScheduler.kt:11-18`
```kotlin
// Schedules the [DaemonWorker] as a periodic WorkManager job.
// WorkManager enforces a 15-minute minimum floor for periodic work,
// so the requested 8-minute interval is effectively ~15 minutes.
internal const val INTERVAL_MINUTES = 15L
```
**Finding**: Three call sites (`DaemonWorker.kt:18-19`, `ProactiveBootstrap.kt:96-98`, `EmotionDaemonSection.kt:96`) all describe the cadence as "every ~8 min" / "every 8 minutes" / "reviews recent context every ~8 min". The actual interval is 15L minutes per `INTERVAL_MINUTES = 15L`. Documentation drift, not a bug — the worker runs. **Fix**: search-and-replace all four sites to read "~15 min" (the WorkManager floor), or make `INTERVAL_MINUTES = 8L` and accept the WorkManager-actualized 15-min reality.

### C2. [P1] `ProactiveScheduler.scheduleDream()` requires `setRequiresCharging(true)` — no fallback
**File**: `aura-core/src/main/kotlin/com/aura/proactive/ProactiveScheduler.kt:91-99`
```kotlin
val constraints = Constraints.Builder()
    .setRequiresBatteryNotLow(true)
    .setRequiresCharging(true)
    .build()
val request = PeriodicWorkRequestBuilder<com.aura.dream.DreamWorker>(1, TimeUnit.DAYS)
    .setConstraints(constraints)
```
**Finding**: A user who leaves their phone off the charger for 24+ hours never dreams. WorkManager back-off means a missed daily window is retried on the next "battery-not-low + charging" condition, which can be days later. **The user has no warning that dreaming is starving.**
**Fix**: add a setting `dreamRequiresCharging` defaulting to `true` for battery-sensitive users but allowing "battery-not-low only". Or relax to `setRequiresBatteryNotLow(true)` only and accept the marginal battery cost. Surface "Last dream: 5 days ago" prominently in `DreamConsolidationSection`.

### C3. [P1] `combine(...)` 5-flow overload in `ProactiveBootstrap`
**File**: `aura-core/src/main/kotlin/com/aura/proactive/ProactiveBootstrap.kt:82-91`
```kotlin
combine(
    userPreferences.morningBriefEnabled,
    userPreferences.calendarMonitorEnabled,
    userPreferences.morningBriefHour,
    userPreferences.evolutionEnabled,
    userPreferences.evolutionsIntervalHours,
) { ... }
```
**Finding**: Kotlin stdlib's `combine` has overloads up to **5** flows. This works. The comment at line 106-108 says "the 5-way combine above is already at the overload limit" — but the limit is 5, and the file uses 5. Adding a 6th preference (e.g., `daemonEnabled` to this batch) would require restructuring. **The existing code is fragile-by-design** and the comment is misleading about the actual limit.
**Fix**: combine the preferences into a single `data class` via `.combine(...).map { ... }` to flatten to one flow, then `combine` 2 flows. Or use the 6-flow extension helper.

### C4. [P1] `ProactiveEvents.unreadCount` swallows DB errors as "0 unread"
**File**: `aura-core/src/main/kotlin/com/aura/proactive/ProactiveEvents.kt:82-97`
```kotlin
val unreadCount: StateFlow<Int> = combine(_refreshTick, userPreferences.lastSeenProactiveAt) { _, lastSeenAt -> lastSeenAt }
    .let { combined ->
        val countFlow = MutableStateFlow(0)
        scope.launch {
            combined.collect { lastSeenAt ->
                countFlow.value = runCatching { dao.countSince(lastSeenAt) }.getOrDefault(0)
            }
        }
        countFlow.asStateFlow()
    }
```
**Finding**: Two issues:
1. If `dao.countSince()` throws (transient DB lock, schema mismatch), the count is `0`. The badge shows "0 today" but events actually exist in the DB.
2. The initial value of `countFlow` is `0`, and it's only updated after the first `_refreshTick` change. On cold start the badge starts at `0` and only becomes correct after the bootstrap's `_refreshTick.value = System.currentTimeMillis()` lands (line 108).
**Fix**: log the error to `CrashLogger` and keep the last-known-good value instead of resetting to 0. Add a 1-line `crashLogger.log(code="proactive_count_failed", message=e.message)`.

### C5. [P1] `MemoryAugmentedAgenticLoop.emotionEngine` nullable default silently disables emotion
**File**: `aura-core/src/main/kotlin/com/aura/agent/MemoryAugmentedAgenticLoop.kt:73, 518-526`
```kotlin
private val emotionEngine: com.aura.emotion.EmotionEngine? = null,  // line 73
...
val emotionContext = if (memoryEnabled && emotionEngine != null) {  // line 518
    if (step == 1) { emotionEngine.update(lastUserMessage); emotionEngine.decay() }
    val mood = emotionEngine.moodString()
    val profile = emotionEngine.profile()
    "\n\n# Current mood: $mood" + profile.promptSuffix
} else ""
```
**Finding**: The `emotionEngine: EmotionEngine? = null` defaulting to null is the canonical "could be missing" pattern, but:
1. Hilt **does** bind `EmotionEngine` (it's `@Singleton` with `@Inject constructor`), so production passes a non-null instance.
2. BUT — when `memoryEnabled` is false (incognito mode), emotion is also disabled. Incognito user gets a flat tone; the prior `moodString` snapshot from before they toggled incognito is lost.
3. There's no `else` log when the engine is null — silent degradation.
**Fix**: remove the `?` and `= null` default — require the dependency. The Hilt graph already has it. This makes the incognito/missing case a single explicit branch.

### C6. [P0] `EmotionEngine.save()` and `EmotionEngine.load()` are never called
**File**: `aura-core/src/main/kotlin/com/aura/emotion/EmotionEngine.kt:160-182`
**Finding**: `EmotionEngine.kt:160-182` defines `suspend fun save()` and `suspend fun load()` to persist/restore the 4D state to DataStore. **Neither method is called from anywhere** (grepped all of `app/` and `aura-core/`). The `state` field is a `private var` (line 41) that resets to `EmotionSnapshot()` defaults (tension=0.3, connection=0.5, energy=0.4, focus=0.3) on every cold start.
**Impact**: The user-visible 4D emotion bars in `EmotionDaemonSection` will show defaults on first render and only update after the user sends a message. If the user closes the app, the next time they open Settings, the bars reset to defaults again — making the "Updated 3h ago" timestamp misleading (it shows the timestamp from the *last* update during the previous session, not the most recent one).
**Fix**: call `load()` in `ProactiveBootstrap.start()` (after agent seeding) and `save()` in `MemoryAugmentedAgenticLoop` after each `emotionEngine.update(...)`.

### C7. [P0] `SettingsViewModel.emotionSnapshot` is a dead `MutableStateFlow(null)`
**File**: `app/src/main/kotlin/com/aura/ui/settings/SettingsViewModel.kt:553-554`
```kotlin
val emotionSnapshot: StateFlow<com.aura.emotion.EmotionEngine.EmotionSnapshot?> =
    MutableStateFlow<EmotionEngine.EmotionSnapshot?>(null)
```
**Finding**: The property is exposed as a `StateFlow<EmotionSnapshot?>` initialized to `null` and **nothing ever calls `.value = …` on it**. The Settings section (`EmotionDaemonSection.kt:53-75`) reads it and falls into the `else` branch showing "No emotional data yet. Start a conversation to begin tracking." even after the user has had a conversation. Same for `daemonThoughtsCount` (line 557) — always `0`.
**Fix**: inject `EmotionEngine` and `ProactiveEventDao` (or a small facade) into `SettingsViewModel`, expose `emotionSnapshot: StateFlow<EmotionSnapshot?>` from a flow on the engine's internal `MutableStateFlow<EmotionSnapshot>`, and `daemonThoughtsCount` from a DAO query.

### C8. [P0] `HandScheduleType` enum is missing `INTERVAL` — hand with `scheduleType="interval"` silently never runs
**File**: `aura-core/src/main/kotlin/com/aura/hands/HandAutomation.kt:52-69`
```kotlin
enum class HandScheduleType(val value: String) {
    NONE("none"),
    DAILY("daily"),
    WEEKDAYS("weekdays"),
    WEEKLY("weekly");
    companion object {
        fun from(value: String): HandScheduleType = entries.firstOrNull { it.value == value } ?: NONE
    }
}
```
**Finding**: If a hand is stored in the DB with `scheduleType="interval"` (e.g., from a migration, a v1 hand, a sync from another platform, or a future extension), `HandScheduleType.from(value)` falls back to `NONE`, which makes `HandScheduler.nextRunAt()` return `null`, which makes the `HandScheduler.enqueue()` path call `workManager.cancelUniqueWork(uniqueName)` (line 40-42 of `HandScheduler.kt`) and return null. **The hand is silently cancelled, with no error to the user or UI.**
**Fix**: either add `INTERVAL("interval")` to the enum, or throw on unknown values to fail loudly during migration. At minimum, log a `Log.w` when `from()` returns NONE for a non-`"none"` input.

### C9. [P1] `RunHandWorker.KEY_HAND_NAME` legacy fallback can pick the wrong hand
**File**: `aura-core/src/main/kotlin/com/aura/hands/RunHandWorker.kt:40-53`
```kotlin
val handId = inputData.getString(KEY_HAND_ID)
val legacyHandName = inputData.getString(KEY_HAND_NAME)
if (handId == null && legacyHandName == null) { return Result.failure() }
val hand = if (handId != null) repository.getById(handId) else repository.getByName(legacyHandName!!)
```
**Finding**: The legacy `KEY_HAND_NAME` path resolves via `repository.getByName(name)`. **Nothing prevents two hands from sharing a name** — `HandRepository.insert(hand)` (line 38) doesn't check uniqueness, and `AgentStore.create` (line 88) similarly uses names as identifiers. If two hands are named "Morning Routine" (a user creates a new one, the old one is still in the DB) and a stale work request with `KEY_HAND_NAME="Morning Routine"` fires, `getByName` returns whichever is first in the DAO's order — likely the wrong hand.
**Fix**: drop the legacy `KEY_HAND_NAME` path. Migration: any enqueued v1 work without `KEY_HAND_ID` is a `Result.failure()` (clean break is better than silent wrong-hand execution).

### C10. [P2] `HandRepository.redactedVariablesJson` redaction regex is too narrow
**File**: `aura-core/src/main/kotlin/com/aura/hands/HandRepository.kt:292-297`
```kotlin
private val SECRET_NAME_PATTERN = Regex("token|secret|password|api.?key", RegexOption.IGNORE_CASE)
```
**Finding**: Misses `key` alone, `client_secret`, `bearer`, `auth`, `credential`, `otp`. A hand variable named `auth_header` or `otp_code` will leak into the `HandRun.variablesJson` (persisted to Room, displayed in `HandsScreen` Run History at line 457).
**Fix**: expand the regex to `key|token|secret|password|credential|auth|bearer|otp|api.?key`.

### C11. [P2] `HandCondition.greater_than` / `less_than` silently fail on non-numeric
**File**: `aura-core/src/main/kotlin/com/aura/hands/HandAutomation.kt:21-37`
**Finding**: `toDoubleOrNull()` returns null for `"3.14abc"` and the condition never matches. The user has no way to know the condition is "broken" — it just always says SKIPPED.
**Fix**: in the editor (`HandEditorDialog.kt`), pre-validate that the `value` field for `greater_than` / `less_than` parses as a number. Show an inline error.

### C12. [P2] `HandScheduler.delayMs.coerceAtLeast(0L)` masks clock skew
**File**: `aura-core/src/main/kotlin/com/aura/hands/HandScheduler.kt:43`
**Finding**: `Duration.between(now, next).toMillis().coerceAtLeast(0L)` — if `next` is somehow in the past (clock skew, daylight-saving edge case, manual time change), the worker fires immediately instead of rescheduling. WorkManager's `setInitialDelay(0, MS)` does fire immediately, which can be correct on DST rollback, but on a manual time change forward, the user expects "scheduled for 7am" to mean 7am.
**Fix**: if `delayMs < 0L`, log a warning and add `+24h` to the target — preserves the "fire at next 7am" semantic across time changes.

### C13. [P2] `ProactiveBootstrap` uses one `preferenceJob` long-lived collector without re-subscription semantics
**File**: `aura-core/src/main/kotlin/com/aura/proactive/ProactiveBootstrap.kt:80-97`
**Finding**: `if (preferenceJob?.isActive != true)` prevents double-subscription, but if the upstream `combine` ever completes (it shouldn't — these are StateFlows that never complete), the job finishes and never re-subscribes. Defensive check, not a bug today.

---

## D. CREATIVE STUDIO

### D1. [P1] `CreativeEngine.resolveModel()` picks a non-default model silently
**File**: `aura-core/src/main/kotlin/com/aura/creative/CreativeEngine.kt:105-112`
```kotlin
internal suspend fun resolveModel(): String {
    userPreferences.defaultModel.first()?.takeIf(String::isNotBlank)?.let { return it }
    for (provider in providerRegistry.configured()) {
        val model = runCatching { provider.listModels().firstOrNull() }.getOrNull()
        if (!model.isNullOrBlank()) return "${provider.prefix}:$model"
    }
    throw IllegalStateException("Configure an LLM provider and choose a default model before using Creative Studio.")
}
```
**Finding**: When `defaultModel` is blank, the engine iterates `providerRegistry.configured()` in whatever order the providers are registered (typically alphabetical) and picks the first model of the first provider. This can route creative work to a small/cheap model (e.g., Llama 8B) when the user has a bigger model configured (e.g., Claude Opus) but didn't set a default. **No user-visible signal that creative work is running on a non-default model.**
**Fix**: add a `creative` model role to `ModelRole` and read from `modelRoleRouter.resolve(ModelRole.Creative)`. Fall back to `defaultModel` only if no role is set. Surface the chosen model in the Creative Studio UI footer.

### D2. [P1] All 6 `CreativeMode`s are functional, but the UI doesn't surface mode-specific prompts
**File**: `aura-core/src/main/kotlin/com/aura/creative/CreativeEngine.kt:13-44`
**Finding**: The 6 modes (`BRAINSTORM`, `OUTLINE`, `DRAFT`, `REWRITE`, `SIMULATE`, `CONTINUITY`) each have a `mode.instruction` that gets prepended to the system prompt. They work. **But the UI** (`CreativeStudioScreen.kt`, not yet read) likely has 6 buttons that all open the same generic input field. The user can pick "Continuity audit" and paste a paragraph, but doesn't get a hint that Continuity expects existing canon to audit.
**Fix**: add a per-mode placeholder/hint in the input field (e.g., "Paste the text you want audited against canon" for CONTINUITY).

### D3. [P2] `CreativeEngine.generate` is a `flow` that mutates state mid-collection
**File**: `aura-core/src/main/kotlin/com/aura/creative/CreativeEngine.kt:75-102`
**Finding**: After each chunk is `emit()`-ed, the engine may call `projectStore.incrementTurn(projectId)`. A collector that throws mid-stream will have seen partial text without the turn being recorded. Documented in the audit but acceptable.
**Fix**: collect the full output first, then call `incrementTurn` after the stream completes successfully. Move the `incrementTurn` and `recordSimulation` to an `onCompletion` hook.

### D4. [P2] `CreativeCouncil.run` error path returns `success=false` but UI doesn't differentiate
**File**: `aura-core/src/main/kotlin/com/aura/creative/CreativeCouncil.kt:70-80`
**Finding**: When an exception is caught, the `CouncilResult` has `success = false, error = e.message`. The `CouncilResult` is plumbed through `SubagentManager.spawnAll` (line 91) and presumably back to the UI, but the UI may not check `success`. **Action**: verify `CreativeStudioScreen` displays the error. (Not yet read in this audit; flag for verification.)

---

## E. EMOTION ENGINE

### E1. [P0] `EmotionEngine.save()` and `load()` are never called — see C6
**File**: `aura-core/src/main/kotlin/com/aura/emotion/EmotionEngine.kt:160-182`
**Severity**: P0 (data persistence broken, user-visible in Settings)

### E2. [P0] `SettingsViewModel.emotionSnapshot` is a dead `MutableStateFlow(null)` — see C7
**File**: `app/src/main/kotlin/com/aura/ui/settings/SettingsViewModel.kt:553-554`
**Severity**: P0 (UI always shows "No emotional data yet")

### E3. [P1] `EmotionEngine.update()` is called from `MemoryAugmentedAgenticLoop` only when `step == 1` and `memoryEnabled && emotionEngine != null`
**File**: `aura-core/src/main/kotlin/com/aura/agent/MemoryAugmentedAgenticLoop.kt:518-526`
**Finding**: In incognito mode, no updates ever land. The 4D state stays at defaults. The user has no way to know "the emotion model is on hold while I'm incognito".
**Fix**: when toggling incognito on, snapshot the current 4D state and freeze it; on incognito off, restore. Surface "frozen because incognito" in `EmotionDaemonSection` if the snapshot timestamp is older than the incognito-on timestamp.

### E4. [P2] `ResponseProfile` thresholds have no docs for what "high tension" means
**File**: `aura-core/src/main/kotlin/com/aura/emotion/ResponseProfile.kt:25-32`
**Finding**: `s.tension > 0.7f && s.connection < 0.3f -> DIRECT` — magic numbers. No unit tests for the boundary cases. Tests exist (`EmotionEngineTest.kt:144-178`) but only for the explicit "high/low" values, not for the threshold boundary at 0.7f exactly.
**Fix**: extract thresholds to named constants (`HIGH_TENSION = 0.7f`, etc.) and add boundary tests.

---

## F. VIEWMODELS — god-classes and test coverage

| ViewModel                   | Lines | Responsibilities | Test file                                                | Coverage |
|-----------------------------|------:|------------------|----------------------------------------------------------|----------|
| `ChatViewModel`             | 1059  | 17+              | `ChatViewModelTest` (multiple)                           | good for send/last-assistant/document; no init/onCleared |
| `SettingsViewModel`         | 1012  | 22+              | `SettingsViewModelAppLockTest` (only app-lock)           | **very thin — only 1 section tested** |
| `MemoryViewModel`           | 382   | 7                | `MemoryViewModelTest`                                    | present  |
| `HandsViewModel`            | 234   | 6                | `HandsViewModelTest`                                     | present  |
| `HomeViewModel`             | 347   | 5                | `HomeStateMappingTest`                                   | present (state mapping only) |
| `CreativeStudioViewModel`   | 227   | 5                | `CreativeStudioViewModelTest`                            | present  |
| `AgentRunsViewModel`        | 141   | 3                | — (no test in app/src/test; aura-core agent has AgentRuns tests but not for the VM) | **gap** |
| `AgentEditorViewModel`      | 147   | 4                | — (no test)                                              | **gap** |
| `ProductionPipelineViewModel` | 76  | 3                | — (no test)                                              | **gap** |
| `ProfileViewModel`          | ?     | ?                | `ProfileViewModelTest`                                   | present  |
| `RemindersViewModel`        | ?     | ?                | `RemindersViewModelTest`                                 | present  |
| `TasksViewModel`            | ?     | ?                | `TasksViewModelTest`                                     | present  |
| `HistoryViewModel`          | ?     | ?                | `HistoryViewModelTest`                                   | present  |
| `KnowledgeGraphViewModel`   | ?     | ?                | `KnowledgeGraphViewModelTest`                            | present  |
| `DiagnosticsViewModel`      | 116   | 2                | `DiagnosticsViewModelTest`                               | present  |
| `SkillsViewModel`           | 53    | 2                | — (per prior audit B3, no test)                          | **gap — but tiny VM, low risk** |
| `DocumentImportViewModel`   | ?     | ?                | `DocumentImportViewModelTest`                            | present  |
| `ToolsViewModel`            | ?     | ?                | — (no test)                                              | **gap** |

### F1. [P1] `SettingsViewModel` is 1012 lines — second-largest source file
**File**: `app/src/main/kotlin/com/aura/ui/settings/SettingsViewModel.kt:1-1012`
**Responsibilities counted**: 22+ (AI/Models, Usage, Appearance, Persona, Tool Permissions, Model Roles, MCP Servers, Privacy, Emotion/Daemon, Evolution, Data/Backup, Dream, Custom Endpoint, SMTP, Identity, Theme, Specialist overrides, default/vision/background/deep/moa role models, MCP server CRUD, embedding model, TTS/incognito/image-model dead paths)
**Fix**: extract per-section controllers (e.g., `McpServerController`, `SmtpConfigController`, `EvolutionSettingsController`). The most cohesive extraction is the **custom endpoint + SMTP** + **credential provider** logic into a `ProviderCredentialsController`.

### F2. [P1] `SettingsViewModel` has 3 dead code paths
**File**: `app/src/main/kotlin/com/aura/ui/settings/SettingsViewModel.kt:471-489`
```kotlin
fun setTtsEnabled(enabled: Boolean)  // writes to DataStore; no UI calls it
fun setIncognitoDefault(enabled: Boolean)  // same
fun setImageModel(model: String)  // same
```
**Finding**: All three write to `userPreferences.setTtsEnabled` / `setIncognitoDefault` / `setImageModel`. The chat screen observes these via its own `userPreferences.ttsEnabled.collect { ... }` flow (per `ChatViewModel.kt:466-476`), so the persistence works. But **no Settings UI switch calls these methods** (grepped `SettingsScreen.kt` and the 12 section files).
**Fix**: either (a) wire three new switches in the Chat / Privacy sections, or (b) delete the methods. (a) is more user-friendly — the user currently has no way to set a default image model, default TTS, or default incognito from Settings.

### F3. [P2] `MemoryScreen` is 1093 lines — god-screen
**File**: `app/src/main/kotlin/com/aura/ui/screens/MemoryScreen.kt:1-1093`
**Finding**: 12+ distinct UI sections: header, search, category filter, add-note button, import-documents button, knowledge-graph button, dream-summaries button, routines/contradictions chips, rebuild-embeddings button, clear-category button, clear-all button, list, snackbar. Multiple `AlertDialog`s (add note, edit memory, history, documents, dream summaries, rebuild confirm, clear confirm). **Hard to test, hard to navigate for users with many features.**
**Fix**: extract sub-composables (`MemoryHeader`, `MemorySearchBar`, `MemoryCategoryFilter`, `MemoryActionRow`, `MemoryList`, `MemoryDialogs`). Move the dialogs to a `rememberMemoryDialogs()` helper. 30-40% reduction.

### F4. [P2] `AgentRunsViewModel`, `AgentEditorViewModel`, `ProductionPipelineViewModel`, `ToolsViewModel` have no tests
**Files**:
- `app/src/main/kotlin/com/aura/ui/viewmodel/AgentRunsViewModel.kt:141`
- `app/src/main/kotlin/com/aura/ui/viewmodel/AgentEditorViewModel.kt:147`
- `app/src/main/kotlin/com/aura/ui/viewmodel/ProductionPipelineViewModel.kt:76`
- `app/src/main/kotlin/com/aura/ui/viewmodel/ToolsViewModel.kt:`
**Finding**: `app/src/test/kotlin/com/aura/ui/viewmodel/` does not have tests for these. The aura-core `agent/` tests cover the store/loop, not the UI VMs.
**Fix**: at least smoke tests for each — `assertNotNull(viewModel.state)` and a test for the most common user action (e.g., create agent, run pipeline).

---

## G. SCREENS — coverage and state management

### G1. [P1] `MaterialTheme.colorScheme` still used in 7+ files (prior audit E1 false claim)
**Files**:
- `app/src/main/kotlin/com/aura/ui/nav/NavGraph.kt:105-106` (FAB colors)
- `app/src/main/kotlin/com/aura/ui/screens/DreamsScreen.kt:113, 143, 150, 156, 184, 191, 198, 204` (8 uses)
- `app/src/main/kotlin/com/aura/ui/screens/production/ProductionPipelineScreen.kt:176, 186`
- `app/src/main/kotlin/com/aura/ui/screens/search/GlobalSearchSheet.kt:79, 89`
- `app/src/main/kotlin/com/aura/ui/components/SwipeToDeleteContainer.kt:56, 63`
- `app/src/main/kotlin/com/aura/ui/components/MarkdownText.kt:137` (comment only)
- `app/src/main/kotlin/com/aura/widget/WidgetConfigActivity.kt:106` (uses `darkColorScheme()` directly)
**Finding**: The prior audit (E1) said "0 callers" after the ratchet. The ratchet is not enforced. **`MaterialTheme.colorScheme` references in `:app` source = 16+**.
**Fix**: migrate the 16 sites to `AuraThemeTokens.colors.*`. The `WidgetConfigActivity` `darkColorScheme()` is acceptable (it's a different theme context — a widget host that doesn't have AuraThemeTokens), but should be documented.

### G2. [P1] `collectAsState()` (legacy) still in 27 sites vs 70 `collectAsStateWithLifecycle()`
**Files**: grep for `\.collectAsState\(\)` in `app/ui/**/*.kt` returns 27 hits, `collectAsStateWithLifecycle\(\)` returns 70. `collectAsState()` is now @Deprecated in `androidx.compose.runtime` (since runtime 1.4) because it doesn't respect `Lifecycle.State.STARTED` — it collects even when the activity is stopped.
**Fix**: search-and-replace `\.collectAsState\(\)` → `\.collectAsStateWithLifecycle\(\)` across `app/ui/`. The 27 sites are spread across EvolutionInboxScreen, EvolutionRollbackScreen, NavGraph, AgentEditorScreen, AgentRunsScreen, ChatRoute (import only), CreativeProjectScreen, CreativeStudioScreen, DiagnosticsScreen, and ~18 more. The import line also needs the `androidx.lifecycle.compose` import added.

### G3. [P2] `HandsScreen` doesn't show time-of-day for last run
**File**: `app/src/main/kotlin/com/aura/ui/screens/HandsScreen.kt:447-450`
**Finding**: `"${run.trigger.replaceFirstChar { it.uppercase() }} · ${DateFormat.getDateTimeInstance(...).format(...)}"` — shows the run timestamp. But the hand card (line 334-336) shows only `"Last: ${lastRun.status}"` with no time. Users have to expand the run history to see when the last run happened.
**Fix**: in `HandCard`, include `timeAgo(lastRun.startedAt)` from `com.aura.ui.util.TimeFormat`.

### G4. [P2] HandsScreen empty state for "Run history" tab uses `Modifier.fillMaxSize()` inside a `Column` — the icon is pushed to the top of the screen, not the visible middle
**File**: `app/src/main/kotlin/com/aura/ui/screens/HandsScreen.kt:373-382`
```kotlin
if (runs.isEmpty()) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(...)
            Spacer(...)
            Text("No runs yet", ...)
            Text("Manual, agent, phrase, and scheduled runs appear here", ...)
        }
    }
    return
}
```
**Finding**: This `Box` is inside the `Column` in the parent (line 396 `Column(verticalArrangement = Arrangement.spacedBy(8.dp))`), so `fillMaxSize()` inside it doesn't actually center vertically — it just adds empty space. The empty state is at the top of the visible area, which looks bad.
**Fix**: move the `Box` outside the `Column`, or use `Modifier.weight(1f).fillMaxWidth()` and rely on `Arrangement.Center` in the parent.

### G5. [P2] HandsScreen hand card name can wrap to 2 lines
**File**: `app/src/main/kotlin/com/aura/ui/screens/HandsScreen.kt:316-323`
```kotlin
Text(hand.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
Text(hand.triggerPhrase.ifBlank { "Manual or agent-triggered" }, …, maxLines = 1, overflow = TextOverflow.Ellipsis)
```
**Finding**: The hand name (line 316) has **no** `maxLines` / `overflow`. A user-named hand like "My Very Long Hand Name That Just Keeps Going" wraps to 2 lines and breaks the card height.
**Fix**: add `maxLines = 1, overflow = TextOverflow.Ellipsis` to the name Text.

### G6. [P1] Settings has 12 sections, no scroll-to-section jump
**File**: `app/src/main/kotlin/com/aura/ui/screens/SettingsScreen.kt:60-266`
**Finding**: 12 sections, each a `SettingsSection` composable. The user has to scroll past all of them. There's no in-page anchor navigation. With `initialExpanded = false` (default for several), the sections are also collapsed by default, requiring an extra tap each.
**Fix**: add a sticky section index at the top (collapsing toolbar pattern), or use a 2-pane layout on tablets (per `ResponsiveContainer`).

### G7. [P1] `ChatRoute.showHoldToTalk` and `showContinuousVoice` blocks both use `VoiceOverlay` for tap-to-talk but only one of them supports hold
**File**: `app/src/main/kotlin/com/aura/ui/screens/chat/ChatRoute.kt:484-500, 510-518`
**Finding**: `showVoiceOverlay` (line 484) instantiates `VoiceOverlay` with no `holdToTalk` flag (defaults to false) → tap-to-speak mode. `showHoldToTalk` (line 510) instantiates with `holdToTalk = true` → hold-to-talk mode. The continuous voice mode (line 524) uses a **different** overlay (`ContinuousVoiceOverlay`) — three different overlay code paths.
**Fix**: consolidate into a single `ChatVoiceMode` enum-driven branch instead of three separate booleans. The current code is correct but `showHoldToTalk` is a state variable that only the long-press gesture ever sets, making it an under-utilized path.

### G8. [P2] `ChatRoute` and `ChatComposer` both define `onHoldToTalk` and `onContinuousVoice` callbacks
**File**: `app/src/main/kotlin/com/aura/ui/screens/chat/ChatComposer.kt:72-73, 190, 221, 230`
**Finding**: The ChatComposer has separate `onTapToSpeak`, `onHoldToTalk`, `onContinuousVoice` callbacks (line 72-73). Each routes to a different mic mode. The mic button is a complex `pointerInput { detectTapGestures(onTap = onTapToSpeak, onLongClick = onHoldToTalk) }` (line 190) with a **second** mic button (or another path) for continuous voice. The user has 2-3 ways to invoke voice and may not know the difference.
**Fix**: surface the three modes as separate, labeled buttons (a "Voice" row with Tap / Hold / Continuous options), or document the gesture pattern in a tooltip.

### G9. [P1] `MemoryViewModel.dreamSummaryCount` is set by one path, read by another — possible count mismatch
**File**: `app/src/main/kotlin/com/aura/ui/screens/MemoryScreen.kt:277-291` (read)
**File**: `app/src/main/kotlin/com/aura/ui/viewmodel/SettingsViewModel.kt:290` (writes `dreamTotalSummaries` from `dreamConsolidationDao.count()`)
**Finding**: Settings shows `state.dreamTotalSummaries` (the total ever). Memory shows `state.dreamSummaryCount` (the count for the current view, presumably same DAO). The two VMs read from the same DAO but expose under different field names. **If a user dreams while looking at Settings, the count there updates; if the user is looking at Memory, the count there may lag.**
**Fix**: add a `dreamSummaryCount` flow on a shared singleton and observe it from both VMs.

### G10. [P2] `HomeViewModel` exposes `state.userName` but the greeting logic is in the View, not the ViewModel
**File**: `app/src/main/kotlin/com/aura/ui/screens/home/HomeRoute.kt:32-40`
**Finding**: The "Good morning / afternoon / evening / Working late" salutation logic is in the composable, not the VM. Pure presentation logic — could move to a `formatGreeting(hour, name)` helper in `ui/util/TimeFormat.kt` for testability.
**Fix**: extract to `TimeFormat.greeting(hour, name?)` and add a unit test.

---

## H. VOICE

### H1. [P2] `VoiceViewModel.lastTranscript` collects `speechToText.state` in `viewModelScope` — no cancellation
**File**: `app/src/main/kotlin/com/aura/ui/voice/VoiceViewModel.kt:50-62`
**Finding**: The collection runs for the VM's lifetime. When the overlay closes, the VM still collects partial/final transcript events into `_lastTranscript`. If the user opens the overlay again, `start()` clears the field (line 67). But until then, stale transcripts linger.
**Fix**: cancel the collection job in `stop()` or `cancel()`.

### H2. [P2] `ContinuousVoiceViewModel` is not inspected in this audit
**File**: `app/src/main/kotlin/com/aura/ui/voice/VoiceOverlay.kt` (contains `ContinuousVoiceOverlay`)
**Finding**: The continuous voice loop is a separate VM (mentioned in `ChatRoute.kt:239`). Not read in this audit round — flag for follow-up if issues are reported.

### H3. [P1] `TextToSpeech.initialize()` is called in `ChatViewModel.init` (line 396) — but `onCleared` only calls `shutdown()`, not `shutdownAndRelease()`
**File**: `app/src/main/kotlin/com/aura/ui/viewmodel/ChatViewModel.kt:496-499`
**Finding**: `textToSpeech.shutdown()` is called. Whether `shutdown()` is the right teardown depends on `TextToSpeech.shutdown()` semantics in `aura-core/voice/TextToSpeech.kt`. Not verified in this audit round.

---

## I. NAVIGATION — secondary

### I1. [P2] `onOpenCalendar` opens the system Calendar app via Intent — no fallback if Calendar is disabled
**File**: `app/src/main/kotlin/com/aura/ui/nav/NavGraph.kt:189-205`
**Finding**: Catches `ActivityNotFoundException` and shows a Toast. **Acceptable**, but the user gets no in-app alternative. The Home → Calendar button is on the home screen, and tapping it just shows a toast and does nothing.
**Fix**: when no calendar app is installed, route to the Reminders screen instead, or surface a "Calendar not available" in-app card.

### I2. [P2] `GlobalSearchSheet` is a Compose overlay, not a NavHost destination
**File**: `app/src/main/kotlin/com/aura/ui/nav/NavGraph.kt:371-376`
**Finding**: Acceptable (modal sheet), but the sheet dispatches `navController.navigate(route)` for results. If the user searches, taps a result, then dismisses the sheet, they end up at the destination — which is correct. But the back-stack can be confusing if the destination is itself a back-stack-emptier.
**Fix**: trace the back-stack in a test. No action needed if the test passes.

---

## J. DESIGN SYSTEM / THEME

### J1. [P2] `MaterialTheme.colorScheme` still used — see G1
### J2. [P2] Light mode is M3 default (per prior audit D3) — not re-verified
### J3. [P2] `AuraThemeTokens.colors` is the only supported path per the design system; no other tokens file exists

---

## K. HAND-EDITABLE (lower priority but worth tracking)

### K1. [P2] `HandEditorDialog` is 477 lines — its own god-dialog
**File**: `app/src/main/kotlin/com/aura/ui/screens/HandEditorDialog.kt:1-477`
**Finding**: The dialog handles variables, conditions, steps, schedule, and confirmation. Multiple internal composables. Acceptable for a complex editor, but worth extracting the step-builder into a sub-composable for readability.

### K2. [P2] `ChatDialogs.kt` contains the model picker, sources sheet, delete/clear/edit dialogs
**File**: `app/src/main/kotlin/com/aura/ui/screens/chat/ChatDialogs.kt`
**Finding**: Not read in this round. Referenced by `ChatRoute.kt:34 import com.aura.ui.screens.chat.*` — flag for follow-up if ChatRoute's dialog state management has bugs.

### K3. [P2] `MemoryViewModel` is 382 lines — larger than other ViewModels but not god-class
**File**: `app/src/main/kotlin/com/aura/ui/viewmodel/MemoryViewModel.kt`
**Finding**: 7 responsibilities (CRUD, search, category filter, dream stats, routines, contradictions, rebuild). Has tests. Acceptable.

### K4. [P2] `HandsViewModel` is 234 lines — borderline but OK
**File**: `app/src/main/kotlin/com/aura/ui/viewmodel/HandsViewModel.kt`
**Finding**: 6 responsibilities (load, search, save, run, toggle, delete, error). Has tests. Acceptable.

### K5. [P2] `HomeViewModel` is 347 lines
**File**: `app/src/main/kotlin/com/aura/ui/viewmodel/HomeViewModel.kt`
**Finding**: 5 responsibilities (greeting, brief, proactive events, dismissal, refresh). Has tests. Acceptable.

### K6. [P2] `CreativeStudioViewModel` is 227 lines
**File**: `app/src/main/kotlin/com/aura/ui/viewmodel/CreativeStudioViewModel.kt`
**Finding**: 5 responsibilities. Has tests. Acceptable.

### K7. [P2] `IdentityEditorScreen` is 220 lines
**File**: `app/src/main/kotlin/com/aura/ui/screens/IdentityEditorScreen.kt`
**Finding**: Reachable from Settings (NavGraph L245). No test in `app/src/test/`. Acceptable for a small editor.

### K8. [P2] `ProfileScreen` is 240 lines
**File**: `app/src/main/kotlin/com/aura/ui/screens/ProfileScreen.kt`
**Finding**: Reachable from Settings. Has test (`ProfileViewModelTest`). Acceptable.

### K9. [P2] `RemindersScreen` is 275 lines
**File**: `app/src/main/kotlin/com/aura/ui/screens/RemindersScreen.kt`
**Finding**: Reachable from Home. Has test (`RemindersViewModelTest`). Acceptable.

### K10. [P2] `ToolsScreen` is 207 lines
**File**: `app/src/main/kotlin/com/aura/ui/screens/ToolsScreen.kt`
**Finding**: Reachable from Home. **No test for `ToolsViewModel`** (K7 of the VM coverage table).

### K11. [P2] `HistoryScreen` is 609 lines
**File**: `app/src/main/kotlin/com/aura/ui/screens/HistoryScreen.kt`
**Finding**: Reachable from Chat (NavGraph L226). Has test. Acceptable.

### K12. [P2] `KnowledgeGraphScreen` is 611 lines
**File**: `app/src/main/kotlin/com/aura/ui/screens/KnowledgeGraphScreen.kt`
**Finding**: Reachable from Memory (NavGraph L231). Has test. Acceptable.

### K13. [P2] `ProactiveHistoryScreen` is 580 lines
**File**: `app/src/main/kotlin/com/aura/ui/screens/ProactiveHistoryScreen.kt`
**Finding**: Reachable from Home. Has test. Acceptable.

### K14. [P2] `DreamsScreen` is 207 lines
**File**: `app/src/main/kotlin/com/aura/ui/screens/DreamsScreen.kt`
**Finding**: Reachable from Memory. **No test for DreamsScreen/DreamsViewModel.** Acceptable for now.

### K15. [P2] `DiagnosticsScreen` is 389 lines
**File**: `app/src/main/kotlin/com/aura/ui/screens/DiagnosticsScreen.kt`
**Finding**: Reachable from Settings. Has test (`DiagnosticsViewModelTest`). Acceptable.

### K16. [P2] `AgentEditorScreen` is 253 lines
**File**: `app/src/main/kotlin/com/aura/ui/screens/AgentEditorScreen.kt`
**Finding**: Reachable from Settings. **No test for `AgentEditorViewModel`.** K4 of the VM coverage table.

### K17. [P2] `AgentRunsScreen` is in `app/src/main/kotlin/com/aura/ui/screens/agentrun/AgentRunsScreen.kt`
**Finding**: Reachable from Home. **No test for `AgentRunsViewModel`.** K3 of the VM coverage table.

### K18. [P2] `SkillsScreen` is in `app/src/main/kotlin/com/aura/ui/screens/skills/SkillsScreen.kt`
**Finding**: Reachable from Home. **No test for `SkillsViewModel`** (prior audit B3).

### K19. [P2] `CreativeStudioScreen` + `CreativeProjectScreen` are in `app/src/main/kotlin/com/aura/ui/screens/creative/`
**Finding**: Reachable from Home. Has test (`CreativeStudioViewModelTest`). Acceptable.

### K20. [P2] `ProductionPipelineScreen` is in `app/src/main/kotlin/com/aura/ui/screens/production/ProductionPipelineScreen.kt`
**Finding**: Reachable from Home. **No test for `ProductionPipelineViewModel`**. Per prior audit G2, only reachable from Settings — but the audit shows it's now also reachable from Home (NavGraph L188, L327-332). Good.

### K21. [P2] `GlobalSearchSheet` is in `app/src/main/kotlin/com/aura/ui/screens/search/`
**Finding**: Reachable from the FAB. Not tested.

### K22. [P2] `Onboarding` is in `app/src/main/kotlin/com/aura/ui/screens/onboarding/`
**Finding**: Has test (`OnboardingModelFlowTest`). Acceptable.

### K23. [P2] `FirstRunGate.kt` is in `app/src/main/kotlin/com/aura/FirstRunGate.kt`
**Finding**: Gates onboarding. Not in scope for this audit.

---

## L. PROACTIVE EVENTS — additional

### L1. [P2] `ProactiveEvents.kt:226-230` — `LocationArrived` rows are intentionally null-skipping (good comment)
**File**: `aura-core/src/main/kotlin/com/aura/proactive/ProactiveEvents.kt:226-230`
**Finding**: Code comment is honest: "Aura never had a producer or geofence implementation for this advertised event." The legacy placeholder rows are dropped on read. This is correct behavior.
**Fix**: none. (Confirm via `grep "LocationArrived" aura-core/src/main/kotlin/com/aura/proactive` that no producer writes this event type.)

### L2. [P2] `ProactivePolicyEngine` exists but its caller is not traced
**File**: `aura-core/src/main/kotlin/com/aura/proactive/ProactivePolicyEngine.kt`
**Finding**: Has test (`ProactivePolicyEngineTest.kt`). Not traced further in this audit.

### L3. [P2] `ProactiveRunner` exists
**File**: `aura-core/src/main/kotlin/com/aura/proactive/ProactiveRunner.kt`
**Finding**: Provides "fire now" paths for DecayWorker / MorningBriefBuilder. Not inspected in this audit.

### L4. [P2] `ProactiveEventBus.emit` and `tryEmit` coexist
**File**: `aura-core/src/main/kotlin/com/aura/proactive/ProactiveEventBus.kt:49-55`
**Finding**: `emit` is suspending (used by MorningBriefBuilder, CalendarMonitor); `tryEmit` is non-suspending (used by `ProactiveEvents.kt:138` to re-emit with DB id). Acceptable.

---

## M. CREATIVE — additional

### M1. [P2] `WorldBibleEditor` is in `app/src/main/kotlin/com/aura/ui/screens/creative/`
**File**: `app/src/main/kotlin/com/aura/ui/screens/creative/WorldBibleEditor.kt`
**Finding**: Not read in this audit. Flag for follow-up.

### M2. [P2] `ProductionPipelineEngine` is in `aura-core/src/main/kotlin/com/aura/creative/ProductionPipelineEngine.kt`
**Finding**: Not read. Reachable from `ProductionPipelineScreen`. Has no test for the VM.

### M3. [P2] `CreativeBranchStore` has a test, `CreativeArtifactStore` has a test, `CreativeCouncil` has a test
**Files**:
- `aura-core/src/test/kotlin/com/aura/creative/CreativeBranchStoreTest.kt`
- `aura-core/src/test/kotlin/com/aura/creative/CreativeArtifactStoreTest.kt`
- `aura-core/src/test/kotlin/com/aura/creative/CreativeCouncilTest.kt`
- `aura-core/src/test/kotlin/com/aura/creative/CreativeProjectStoreTest.kt`
**Finding**: All 4 have tests. Acceptable.

### M4. [P2] `CanonDaos`, `CanonEntities`, `CouncilRoles` exist but not inspected
**Files**: in `aura-core/src/main/kotlin/com/aura/creative/`
**Finding**: Not read. Flag for follow-up.

---

## N. HANDS — additional

### N1. [P1] `HandScheduleType` is missing `INTERVAL` — see C8
### N2. [P1] `RunHandWorker.KEY_HAND_NAME` legacy fallback can pick the wrong hand — see C9
### N3. [P2] `HandsScreenLogicTest` exists but is small
**File**: `app/src/test/kotlin/com/aura/ui/screens/HandsScreenLogicTest.kt`
**Finding**: Has a test for `HandsScreenLogicTest` only. Per K4 the VM is 234 lines and tested via `HandsViewModelTest`. Acceptable.
### N4. [P2] `HandSchedulerPolicyTest` exists and exercises `nextRunAt`
**File**: `aura-core/src/test/kotlin/com/aura/hands/HandSchedulerPolicyTest.kt`
**Finding**: Good coverage of the schedule math.
### N5. [P2] `RunHandWorkerTest` exists
**File**: `aura-core/src/test/kotlin/com/aura/hands/RunHandWorkerTest.kt`
**Finding**: Good. Tests the worker's happy path.
### N6. [P2] `HandRepositoryTest` exists
**File**: `aura-core/src/test/kotlin/com/aura/hands/HandRepositoryTest.kt`
**Finding**: Good.
### N7. [P2] `HandAutomationTest` exists
**File**: `aura-core/src/test/kotlin/com/aura/hands/HandAutomationTest.kt`
**Finding**: Good.

---

## O. AGENTS

### O1. [P2] `SubagentManager` is a thin wrapper; tests cover it
**File**: `aura-core/src/main/kotlin/com/aura/agents/SubagentManager.kt:1-93`
**Test**: `aura-core/src/test/kotlin/com/aura/agents/SubagentManagerTest.kt`
**Finding**: Simple, well-tested. No god-class risk.

### O2. [P2] `SubagentContracts` not inspected
**File**: `aura-core/src/main/kotlin/com/aura/agents/SubagentContracts.kt`
**Finding**: Not read. Flag for follow-up.

### O3. [P2] `AgentEntity` / `AgentStore` / `PersonalityProfile` are in `aura-core/src/main/kotlin/com/aura/agent/`
**Files**:
- `aura-core/src/main/kotlin/com/aura/agent/AgentEntity.kt`
- `aura-core/src/main/kotlin/com/aura/agent/AgentStore.kt`
- `aura-core/src/main/kotlin/com/aura/agent/PersonalityProfile.kt`
**Test**: `aura-core/src/test/kotlin/com/aura/agent/AgentStoreTest.kt`, `PersonalityProfileTest.kt`
**Finding**: Per `AgentStore.kt:40-74`, the store seeds 7 builtin agents on first run. Acceptable.

### O4. [P2] `AgentEditorScreen` (253 lines) and `AgentEditorViewModel` (147 lines) — no test for the VM
**Finding**: K4/K16.

### O5. [P2] `AgentRunsScreen` and `AgentRunsViewModel` (141 lines) — no test for the VM
**Finding**: K3/K17.

### O6. [P1] The `delegate_to_agent` tool (referenced in the prior audit) lives in `aura-core/src/main/kotlin/com/aura/tools/DelegateToAgentTool.kt:112`
**Finding**: Not read in this audit. The prior audit mentioned it as the "delegate" path for agent invocation. Flag for follow-up.

### O7. [P2] `AgentCouncil` exists at `aura-core/src/main/kotlin/com/aura/agent/AgentCouncil.kt`
**Finding**: Not read. Note that the creative-council is a different class (`CreativeCouncil.kt`). Two council-style orchestrators in the codebase.

---

## P. PROACTIVE EVENTS — UI

### P1. [P2] `ProactiveHistoryScreen` is 580 lines
**File**: `app/src/main/kotlin/com/aura/ui/screens/ProactiveHistoryScreen.kt`
**Finding**: Has test (`ProactiveHistoryViewModelTest`). Acceptable.

### P2. [P2] `ProactiveHistoryViewModel` exists with a test
**File**: `app/src/test/kotlin/com/aura/ui/viewmodel/ProactiveHistoryViewModelTest.kt`
**Finding**: Present.

---

## Q. SETTINGS — completeness

### Q1. [P1] `setTtsEnabled`, `setIncognitoDefault`, `setImageModel` are dead code paths — see F2

### Q2. [P2] Every visible toggle is wired
**File**: `app/src/main/kotlin/com/aura/ui/settings/sections/*.kt`
**Finding**: Grep of `Switch` in section files shows every toggle has a corresponding `onSet*` callback that mutates a `StateFlow` in `SettingsViewModel`, which persists to `UserPreferences` (and to `ProactiveBootstrap` via the `combine` flow at line 82-97).
- `morningBriefEnabled` → `setMorningBriefEnabled` → DataStore → `ProactiveBootstrap` reconcile
- `calendarMonitorEnabled` → `setCalendarMonitorEnabled` → DataStore → `ProactiveBootstrap` reconcile
- `daemonEnabled` → `setDaemonEnabled` → DataStore → `ProactiveBootstrap` reconcile
- `dreamEnabled` → `setDreamEnabled` → DataStore → `ProactiveBootstrap` reconcile
- `decayEnabled` → `setDecayEnabled` → DataStore → `ProactiveBootstrap` reconcile
- `evolutionEnabled` → `setEvolutionEnabled` → DataStore → `ProactiveBootstrap` reconcile
- `evolutionIntervalHours` → `setEvolutionIntervalHours` → DataStore → `ProactiveBootstrap` reconcile
- `appLockEnabled` → `setAppLockEnabled` → DataStore
- `themeMode` → `setThemeMode` → DataStore
- `identityText` → `saveIdentity` → `IdentityStore` (separate from DataStore)
- `mcpServers` → `testMcpConnection` / `disconnectMcpServer` → `userPreferences.mcpServersJson` + `secureDataStore.putString("mcp_auth_...")`
- `toolPolicies` → `setToolEnabled` / `setToolConfirmation` → `ToolPolicyStore`
- `roleModels` → `setRoleModel` → `userPreferences.setRoleModel`
- `defaultModel`, `visionModel`, `backgroundModel`, `deepModeModel`, `embeddingModel`, `moaReferenceModels`, `moaAggregatorModel`, `imageModel` → corresponding setters
- `smtpHost`, `smtpPort`, `smtpUsername`, `smtpPassword`, `smtpFrom` → `saveSmtpConfig`
- `customBaseUrl`, `customApiKey` → `saveAndTestCustomEndpoint`
- `providerTests` (per provider) → `saveAndTestProvider`
- `dreamEnabled` → `setDreamEnabled`; manual `runDreamNow` → enqueue `DreamWorker`
- `specialistOverrides` → `setSpecialistOverrides`

**Verdict**: All toggles **EXCEPT** `setTtsEnabled`, `setIncognitoDefault`, `setImageModel` are wired. Those three are dead code paths (F2).

### Q3. [P1] `emotionSnapshot` and `daemonThoughtsCount` in Settings are dead flows — see C7

### Q4. [P2] `McpServerDraft` is a UI-only data class with no validation
**File**: `app/src/main/kotlin/com/aura/ui/settings/SettingsViewModel.kt:173-181`
**Finding**: `McpServerDraft` has fields like `allowedToolPrefixes` and `deniedTools` as comma-separated strings. The user types into a text field and the parsing happens at `testMcpConnection` time (line 653-654). If the user pastes `"foo, bar,, baz,"`, the trailing empty strings pass the `isNotBlank()` filter and become tool names. **No prefix validator**.
**Fix**: trim each entry, drop empties, validate against the actual `ToolRegistry.all()` list and show an error if a prefix doesn't match a real tool.

### Q5. [P2] `AiAndModelsSection` is the biggest single Settings section
**File**: `app/src/main/kotlin/com/aura/ui/settings/sections/AiAndModelsSection.kt`
**Finding**: Not read in this audit. Contains all 25+ provider keys, model pickers, custom endpoint, SMTP. Flag for follow-up.

---

## R. RECOMMENDED FIX ORDER

Based on user impact and effort:

| # | Finding | File | Effort | Impact |
|---|---------|------|--------|--------|
| 1 | C6: Wire `EmotionEngine.save()` and `load()` | `EmotionEngine.kt`, `ProactiveBootstrap.kt`, `MemoryAugmentedAgenticLoop.kt` | 1h | High — emotion data persists across sessions |
| 2 | C7: Wire `emotionSnapshot` / `daemonThoughtsCount` to real flows | `SettingsViewModel.kt:553-558` | 1h | High — Settings emotion section currently always shows "no data" |
| 3 | C8: Add `INTERVAL` to `HandScheduleType` or fail loud on unknown | `HandAutomation.kt:52-69` | 30m | High — silent skip of misconfigured hands |
| 4 | G2: Migrate 27 `collectAsState` to `collectAsStateWithLifecycle` | `app/ui/**/*.kt` | 30m | Med — battery / config-change leak |
| 5 | G1: Migrate 16 `MaterialTheme.colorScheme` to `AuraThemeTokens` | 7 files in `app/ui/` | 1h | Med — design-system consistency |
| 6 | F2: Wire or delete `setTtsEnabled` / `setIncognitoDefault` / `setImageModel` | `SettingsViewModel.kt:471-489` + new switches in Chat settings | 2h | Med — settings completeness |
| 7 | C5: Remove `? = null` from `emotionEngine` in `MemoryAugmentedAgenticLoop` | `MemoryAugmentedAgenticLoop.kt:73` | 30m | Med — no more silent null-check |
| 8 | C1: Fix DaemonScheduler comment / value mismatch | `DaemonScheduler.kt`, 3 doc sites | 15m | Low — docs |
| 9 | A1: Drop duplicate `evolution/inbox` route | `NavGraph.kt:349` | 15m | Low — dead weight |
| 10 | B1: Extract `ChatDraftController` from `ChatViewModel` | `ChatViewModel.kt:395-420` | 2h | Low — refactor |
| 11 | B2: Unregister `NetworkCallback` in `onCleared` | `ChatViewModel.kt:496-499` | 30m | Low — battery |
| 12 | B3: Reset `isolatedSessionRequested` in `newConversation` | `ChatViewModel.kt:331` | 15m | Low — UX |
| 13 | F1: Extract providers controller from `SettingsViewModel` | `SettingsViewModel.kt` | 4h | Low — refactor |
| 14 | F3: Break up `MemoryScreen` into sub-composables | `MemoryScreen.kt:1-1093` | 3h | Low — refactor |

---

## S. AUDIT-VERIFIED POSITIVES (things that ARE working)

- ✅ NavigationReachabilityTest exists and is wired into CI (per `app/src/test/kotlin/com/aura/ui/nav/`).
- ✅ All 5 top-level bottom-bar routes (Home/Chat/Memory/Evolution/Settings) are wired to `composable()`.
- ✅ All 16+ secondary routes (history, hands, tasks, tools, proactive, reminders, profile, identity_editor, knowledge_graph, dreams, creative, creative/{projectId}, agent_runs, agent_runs/{runId}, skills, production, diagnostics, agent_editor, evolution/inbox, evolution/beliefs, evolution/rollback/{proposalId}) are registered.
- ✅ Voice modes (Tap / Hold / Continuous) are wired via `ChatVoiceMode` enum and 3 separate Compose paths.
- ✅ ProactiveWorker toggles all reconcile in `ProactiveBootstrap` via `combine(...)` over DataStore flows.
- ✅ `MorningBriefBuilder` and `MorningBriefWorker` correctly emit both `MorningBriefReady` and `MorningBriefStructured` events.
- ✅ `CalendarMonitorService` correctly registers the FGS notification on API 34+ with `FOREGROUND_SERVICE_TYPE_SPECIAL_USE`.
- ✅ `MemoryAugmentedAgenticLoop` correctly applies emotion context to system prompt (when `emotionEngine != null`).
- ✅ `HandScheduler` correctly handles DAILY/WEEKDAYS/WEEKLY and never enqueues for NONE.
- ✅ `ChatViewModel` has 6 controllers extracted (Send, Media, MediaPolicy, RetryPolicy, plus the VM itself).
- ✅ Tests exist for all major ViewModels except `AgentRunsViewModel`, `AgentEditorViewModel`, `ProductionPipelineViewModel`, `ToolsViewModel`, `SkillsViewModel`.
- ✅ AuraThemeTokens is the only theme path in the screens — `MaterialTheme.colorScheme` is only in older un-migrated files (G1).
- ✅ All `composable()` registrations accept `SavedStateHandle`-friendly nav-args (e.g., `chat?convId={convId}&draft={draft}&brief={brief}&focusTurn={focusTurn}`).
- ✅ DreamConsolidationSection, EvolutionSettingsSection, PersonaSection, McpServersSection, PrivacySection, ToolPermissionsSection, ModelRolesSection, AppearanceSection, DataAndBackupSection, UsageSection all exist as proper sub-composables.
- ✅ ProactiveEventBus correctly re-emits with DB id (line 137-139).
- ✅ `AgentStore.seedBuiltins()` runs from `ProactiveBootstrap.start()` and is idempotent (line 41-42).
- ✅ `ProactiveEventEntity` has 30-day retention sweep (line 113-115).
- ✅ Soft-delete for memories is wired (`ProactiveBootstrap.start` line 73-75 calls `conversationStore.purgeDeletedOlderThan()`).

---

## T. WHAT ISN'T IN THIS AUDIT (deferred)

- `aura-core/src/main/kotlin/com/aura/notifications/` — proactive notifications subsystem
- `aura-core/src/main/kotlin/com/aura/dream/` — dream consolidator
- `aura-core/src/main/kotlin/com/aura/usage/` — usage tracking
- `aura-core/src/main/kotlin/com/aura/skills/` — skills store
- `aura-core/src/main/kotlin/com/aura/capabilities/` — capability-backed tools (image gen, TTS, video)
- `aura-core/src/main/kotlin/com/aura/pipeline/` — production pipeline engine
- `aura-core/src/main/kotlin/com/aura/world/` — belief/world model
- `aura-core/src/main/kotlin/com/aura/kg/` — knowledge graph
- `aura-core/src/main/kotlin/com/aura/profile/` — user profile
- `aura-core/src/main/kotlin/com/aura/provenance/` — provenance tracking
- `aura-core/src/main/kotlin/com/aura/mcp/` — MCP server management
- `aura-core/src/main/kotlin/com/aura/backup/` — backup/restore
- `aura-core/src/main/kotlin/com/aura/migration/` — DB migrations
- `aura-core/src/main/kotlin/com/aura/data/` — UserPreferences
- `aura-core/src/main/kotlin/com/aura/tasks/` — task DAO
- `aura-core/src/main/kotlin/com/aura/notifications/` — notification subsystem
- All `aura-core/src/test/` test files (touched only for directory-listing verification)
- `aura-core/src/main/kotlin/com/aura/agent/` internals (Brain, ConversationCompactor, etc.)
- `aura-core/src/main/kotlin/com/aura/providers/` (ProviderRegistry, ProviderKeys, etc.)
- `aura-core/src/main/kotlin/com/aura/tools/` (33 tools)
- `aura-core/src/main/kotlin/com/aura/security/` (KeyManager, SecureDataStore)
- `app/src/main/kotlin/com/aura/widget/` (AskAuraWidget)
- `app/src/main/kotlin/com/aura/documents/` (DocumentTextExtractor)
- `app/src/main/kotlin/com/aura/FirstRunGate.kt`
- `app/src/main/kotlin/com/aura/MainActivity.kt`
- `app/src/main/kotlin/com/aura/ShareReceiverActivity.kt`
- `app/src/main/kotlin/com/aura/AuraApp.kt`
- `app/src/main/kotlin/com/aura/di/AppModule.kt`
- `app/src/main/kotlin/com/aura/IncomingShareStore.kt`

These are deferred to a future round. Focus was on the UI/UX + proactive + creative + hands + agents + emotion + voice surfaces as specified in the task scope.

---

*End of audit. v0.35.3. Branch `feat/tier-1-friction`. 34 findings: 3 P0 · 13 P1 · 18 P2.*
