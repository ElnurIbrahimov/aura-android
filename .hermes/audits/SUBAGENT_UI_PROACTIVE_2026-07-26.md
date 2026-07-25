# Subagent Audit — UI/UX, ViewModels, Compose, Navigation, Proactive, Notifications, Lifecycle

**Date:** 2026-07-26
**Repo:** D:\aura-android-clean
**Branch:** feat/tier-1-friction
**HEAD:** 40f5ca68 (after 2 engineering review passes: 34cf9e1d, 40f5ca68)
**Method:** Deep audit of UI/UX, ViewModels, Compose screens, navigation, proactive workers, notifications, lifecycle.
**Scope:** New real bugs not already fixed by 34cf9e1d or 40f5ca68. Style nits, "consider extracting X", and items already on the prior reports' "Remaining Risks" lists are excluded.

---

## Findings (ordered by severity)

### 1. P0 — `setErrorWithAutoDismiss` never dismisses chat errors (broken UX contract)
**File:** `app/src/main/kotlin/com/aura/ui/viewmodel/ChatViewModel.kt:959-977`

The error auto-dismiss 5s timer compares the raw string to the stored `error` field, but the stored `error` is the *friendly* version. The auto-dismiss never fires for friendly-mapped errors.

```kotlin
private fun setErrorWithAutoDismiss(error: String, retryable: Boolean = false, typed: AuraError? = null) {
    val friendly = com.aura.ui.components.friendlyErrorMessage(error)
    _state.update { it.copy(error = friendly, errorRetryable = retryable, errorTyped = typed) }
    ...
    if (!retryable) {
        viewModelScope.launch {
            kotlinx.coroutines.delay(5_000L)
            if (_state.value.error == error) {       // compares RAW to FRIENDLY
                _state.update { it.copy(error = null, errorRetryable = false, errorTyped = null) }
            }
        }
    }
}
```

`error` parameter is the raw "HTTP 401" / "429" / "timeout" string; the state stores `friendly` like "Your API key is invalid…" — the comparison fails, so the banner sits there until the user manually dismisses it. This breaks the contract in the kdoc ("auto-dismiss non-retryable errors"). Affects every 401/403/429/timeout error path.

**Fix:** store the friendly string in a local, e.g. `val dismissTarget = friendly` and compare with that, or compare against `errorTyped?.code`.

---

### 2. P0 — `BootReceiver` leaves morning brief, evolution, and dream workers un-scheduled after device reboot
**File:** `app/src/main/kotlin/com/aura/proactive/BootReceiver.kt:20-38`

```kotlin
override fun onReceive(context: Context, intent: Intent) {
    if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
    val wm = WorkManager.getInstance(context)
    // Re-enqueue decay worker (every 6h, idempotent UPDATE policy)
    val decayRequest = PeriodicWorkRequestBuilder<DecayWorker>(6, TimeUnit.HOURS).build()
    wm.enqueueUniquePeriodicWork(DecayWorker.UNIQUE_NAME, ...)
    // Re-enqueue daemon worker (every 15 min, idempotent)
    val daemonRequest = PeriodicWorkRequestBuilder<DaemonWorker>(15, TimeUnit.MINUTES).build()
    wm.enqueueUniquePeriodicWork(DaemonScheduler.WORK_NAME, ...)
}
```

The receiver's own kdoc says "Morning brief and calendar monitor are rescheduled by ProactiveBootstrap on the next app launch." But if the user reboots and **never opens the app** (e.g., they expect the morning brief at 7 AM), the morning brief worker is gone (WorkManager state is cleared by some OEMs on cold boot per the kdoc itself) and **never re-scheduled**. The dream worker (24h cycle) and evolution worker (configurable interval) are also not restored here.

For a personal-assistant app whose headline feature is "Aura greets you in the morning", a user who rebooted overnight will get silence. This is a real daily-use break.

**Fix:** also re-enqueue MorningBriefWorker, DreamWorker, and EvolutionWorker in `BootReceiver`, reading the user preferences for hour/interval (or read them inside a `@HiltWorker`-aware path with a dedicated one-shot worker that re-runs `ProactiveBootstrap.reconcile*`).

---

### 3. P0 — `HomeViewModel.rebuildBriefContext` calls `memoryStore.recent(50)` twice in two runCatching blocks; not just a perf miss
**File:** `app/src/main/kotlin/com/aura/ui/viewmodel/HomeViewModel.kt:314-346`

```kotlin
val decayed = runCatching {
    memoryStore.recent(50).filter { it.decayScore < DECAY_FADING_THRESHOLD }.take(5)
}.getOrDefault(emptyList())
val newMems = runCatching {
    memoryStore.recent(50).filter { it.createdAt >= dayAgo }.take(5)
}.getOrDefault(emptyList())
```

Each call hits Room; on a 10k-memory install this is two full table scans per Home load. Worse, this is invoked from `observeMemories()` every time the memory count changes (line 239-246: `memoryStore.observeCount().collect { refresh() }`), so every stored memory triggers a triple-fetch: `recent(5)`, then `recent(50)` twice more. With a healthy install this is 3 Room queries per memory insertion. Combine that with the load-spinner flicker bug (see #4) and Home becomes the noisiest screen on writes.

**Fix:** call `memoryStore.recent(50)` once, partition in-memory.

---

### 4. P0 — Every memory write triggers a Home "loading…" flash and full re-fetch
**File:** `app/src/main/kotlin/com/aura/ui/viewmodel/MemoryViewModel.kt:86-88`

```kotlin
init {
    refresh()
    viewModelScope.launch {
        memoryStore.observeCount().collect { refresh() }  // ← every insert/delete triggers full re-fetch with loading=true
    }
    ...
}
```

`refresh()` sets `_state.value = ...loading = true` then awaits Room. Every memory write — including automatic ones the agent does after every chat turn — flips the Memory screen to a loading skeleton for hundreds of ms. If the user is browsing Memory while chatting in another tab, the list will blank-flash repeatedly.

**Fix:** drop `observeCount().collect { refresh() }`; instead, in the chat-write path, post the new memory directly to the in-memory list, or have MemoryViewModel observe the memory list (not just the count) via a Room Flow that returns the same query as the current filter.

---

### 5. P1 — Clipboard read on every Chat composition (privacy + unwanted-content bug)
**File:** `app/src/main/kotlin/com/aura/ui/screens/chat/ChatComposer.kt:92-120`

```kotlin
LaunchedEffect(Unit) {
    val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
        as? android.content.ClipboardManager
    if (clipboard != null) {
        val clip = clipboard.primaryClip
        if (clip != null && clip.itemCount > 0) {
            val item = clip.getItemAt(0)
            val uri = item.uri
            if (uri != null && uri.toString().startsWith("content://")) {
                runCatching { ... onImagePasted(bitmap) }
            }
        }
    }
}
```

Every time the user opens Chat (or ChatRoute is recomposed), this **reads the system clipboard**. If the user recently copied a password, a private photo, or any content URI from another app, Aura will silently grab it and surface it as a "pasted image" — sending it to vision analysis and into the conversation. The user never consented. This is both a privacy bug and a daily-use surprise ("why did Aura attach this image?").

Also, on API <28 the bitmap is decoded at full resolution (no `setTargetSize`), risking OOM on a screenshot from a high-DPI device.

**Fix:** remove the auto-read. Provide an explicit "Paste" button in the composer (or do nothing — most users won't expect this behavior). The Material `RichTextEditor` pattern is opt-in paste.

---

### 6. P1 — `MemoryViewModel.setQuery` mutates state outside the atomic update
**File:** `app/src/main/kotlin/com/aura/ui/viewmodel/MemoryViewModel.kt:135-156`

```kotlin
fun setQuery(q: String) {
    _state.update { it.copy(query = q) }     // OK
    searchJob?.cancel()
    val trimmed = q.trim()
    if (trimmed.isEmpty()) {
        viewModelScope.launch {
            val results = if (_state.value.categoryFilter != null) { ... }
            _state.update { it.copy(memories = results, loading = false) }
        }
        return
    }
    searchJob = viewModelScope.launch {
        _state.update { it.copy(loading = true) }
        kotlinx.coroutines.delay(250)
        val results = memoryStore.searchByText(trimmed, 50)
        _state.update { it.copy(memories = results, loading = false) }
    }
}
```

Race: if the user types "a" then quickly "ab" then "abc", `setQuery("a")` starts a 250 ms debounce. `setQuery("ab")` cancels that and starts another 250 ms job. `setQuery("abc")` cancels that. Fine. **But** the `if (trimmed.isEmpty())` branch launches a **non-debounced, non-cancellable** coroutine. If the user types "a" then deletes back to empty quickly, the "a" search is still running; it will eventually overwrite the empty-query results. Result: list briefly shows matches for the previous query after the user cleared the search box.

**Fix:** treat the cleared-query case the same as the non-empty case: cancel `searchJob`, then launch the same 250 ms-delayed fetch (with 0 ms for empty), all under `searchJob`.

---

### 7. P1 — `TasksViewModel.load()` stacks a fresh `observeUpcoming` collector on every call
**File:** `app/src/main/kotlin/com/aura/ui/viewmodel/TasksViewModel.kt:47-60`

```kotlin
init { load() }

fun load() {
    _state.value = _state.value.copy(loading = true)
    viewModelScope.launch {
        val tasks = taskDao.all()
        _state.update { it.copy(tasks = tasks, loading = false) }
    }
    viewModelScope.launch {
        reminderStore.observeUpcoming().collectLatest { reminders ->     // ← new collector every call
            _state.update { it.copy(reminders = reminders) }
        }
    }
}
```

Each call to `load()` adds another `viewModelScope.launch { reminderStore.observeUpcoming().collectLatest { ... } }` collector. There's no cancellation. If anything re-enters `load()` (an `onResume` call, a future add, etc.) the collectors accumulate. The reminders list will receive N updates for each underlying event.

**Fix:** keep a `private var remindersJob: Job?` and `cancel()` it at the start of `load()`. Better, move the collector to `init` once.

---

### 8. P1 — `AskAuraWidget.refreshWidgets` leaks a new CoroutineScope per tick
**File:** `app/src/main/kotlin/com/aura/widget/AskAuraWidget.kt:88-100`

```kotlin
CoroutineScope(Dispatchers.IO).launch {
    val recent = try { memoryStore.recent(1).firstOrNull() } catch (e: Exception) { null }
    withContext(Dispatchers.Main) { ... }
}
```

A new `CoroutineScope` is created on every widget update. The system ticks the widget every 30 minutes; `ProactiveBootstrap.reconcile` also broadcasts `ACTION_REFRESH_WIDGET` on every preference change. None of these scopes are ever cancelled. They live for the process lifetime. No real damage for a small app, but with the LLM providers, dream workers, and daemon all creating additional scopes, this is a leak in a leak-prone area. Also, `Coroutines + withContext(Dispatchers.Main)` on a `BroadcastReceiver.onReceive` is fragile: if the process is killed between the IO and Main hop, the `updateAppWidget` is dropped silently. WorkManager should be used instead.

**Fix:** switch to `goAsync()` on the receiver with a bounded timeout, or use WorkManager `OneTimeWorkRequest`.

---

### 9. P1 — `SettingsViewModel` saves the SMTP password to plain DataStore
**File:** `app/src/main/kotlin/com/aura/ui/settings/SettingsViewModel.kt:980-1002`

```kotlin
fun saveSmtpConfig() {
    ...
    userPreferences.setSmtpConfig(
        _state.value.smtpHost,
        _state.value.smtpPort,
        _state.value.smtpUsername,
        _state.value.smtpPassword,   // ← plain DataStore
        _state.value.smtpFrom,
    )
```

Provider API keys are routed through `SecureDataStore` (correctly). The SMTP password is not. This is inconsistent with the rest of the secrets-handling story and means the user's email password is stored alongside other plaintext preferences. Inconsistent with the MCP auth-token handling at line 715-735 of the same file, which does the right thing (SecureDataStore for tokens, plain DataStore for the metadata).

**Fix:** persist the password via `secureDataStore.putString("smtp_password", value)` and read from there in the email-sending code path.

---

### 10. P2 — `ChatTimeline` `visible` is captured once and ignores later `alreadyShown` changes
**File:** `app/src/main/kotlin/com/aura/ui/screens/chat/ChatTimeline.kt:63-72`

```kotlin
val alreadyShown = remember(index, state.conversation.turns.size) {
    index < state.conversation.turns.size - 1
}
val visible = remember { mutableStateOf(alreadyShown) }   // ← only initial value
LaunchedEffect(turn, state.streaming) {
    if (!visible.value) {
        delay(20L)
        visible.value = true
    }
}
```

`remember { mutableStateOf(alreadyShown) }` snapshots the initial value. If a turn is at position N-1 when it mounts, then a new turn is appended, `state.conversation.turns.size` changes and `alreadyShown` re-evaluates to `false`, but `visible` is still `true` from the earlier flip. The reverse — alreadyShown flips true on recomposition — also isn't tracked. In practice the LazyColumn removes the item on append, so this is latent, but the pattern is fragile and would re-bite the next time someone refactors the list to keep the item alive.

**Fix:** use `derivedStateOf` and a single `var visible by remember { mutableStateOf(alreadyShown) }`, then re-derive on `alreadyShown` changes: `LaunchedEffect(alreadyShown) { visible = alreadyShown || /* the delayed-show logic */ }`. Or just trust the LazyColumn to remove items and use `visible` only for the entry animation.

---

### 11. P2 — `CalendarMonitorService` calls `startForeground` without checking if already started
**File:** `aura-core/src/main/kotlin/com/aura/proactive/CalendarMonitorService.kt:31-40`

```kotlin
override fun onCreate() {
    super.onCreate()
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        startForeground(NOTIFICATION_ID, buildNotification(), FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
    } else {
        startForeground(NOTIFICATION_ID, buildNotification())
    }
    calendarMonitor.start()
}
```

`CalendarMonitorService.start(context)` is called from `ProactiveBootstrap.reconcile` *every* time the morning-brief/calendar-monitor preference flow emits (which is every time ANY of the combined prefs change — including brief hour, evolution toggle, evolution interval — see `ProactiveBootstrap.kt:86-100`). Each call goes through `ContextCompat.startForegroundService` which calls `onCreate` again on a sticky service. `startForeground` is called twice (or more) and may throw `ForegroundServiceStartNotAllowedException` on API 34+ when launched from background if the service was destroyed but the call happens from a preference emission.

**Fix:** guard with `if (running)` (track via the singleton), or use `startService` only if the service isn't already in the foreground.

---

### 12. P2 — `ChatRoute.savedDraft` ordering race with `loadConversation`
**File:** `app/src/main/kotlin/com/aura/ui/screens/chat/ChatRoute.kt:221-232`

```kotlin
var savedDraft by rememberSaveable { mutableStateOf("") }
LaunchedEffect(Unit) {
    if (savedDraft.isNotBlank() && state.draft.isBlank()) {
        viewModel.setDraft(savedDraft)
    }
}
LaunchedEffect(state.draft) {
    savedDraft = state.draft
}
```

`rememberSaveable` in the chat composable is restored from the saved bundle on process recreation. The restore-vs-load race: the chat screen is recomposed with a non-blank `savedDraft` AND a fresh `_state.value.conversation` (default empty). The first `LaunchedEffect(Unit)` runs first — `state.draft` is still the empty default before the VM's `userPreferences.ttsEnabled.collect` etc. fire — so the saved draft is pushed into state, then `loadConversation(resumeConversationId)` runs and asynchronously swaps in the loaded conversation, but **the draft was already set into the (now-replaced) conversation's UI state**. Result: the user sees the previous session's draft text in the input bar of the new conversation, even though that conversation never had that draft.

Additionally, the very first `LaunchedEffect(state.draft)` write will overwrite `savedDraft` with whatever the VM's draft sync brings in, but the VM's draft sync runs after the first render, so for a frame the UI flashes the stale text.

**Fix:** gate the restore on the VM's `isFirstConversationComplete`-style flag, or persist the draft keyed by conversation ID in the VM, or use `derivedStateOf` and a single source of truth.

---

## Items intentionally NOT included

- 14 untested backup entities (already flagged in 2026-07-25 review, not new)
- Evolution rollback 13/20 stubs (design limitation, not a bug)
- World model tables without agent scope (architecture concern, already noted)
- 196 silent runCatching (already noted, batch cleanup is a separate pass)
- `setErrorWithAutoDismiss` was a P0 I caught — but the prior reviews did not, so it stays in this report

## Verification status

All 12 findings were verified by reading the source files. No new commits or tests added (per "Write report to disk first, then verify"). If asked, I can implement the fixes for the P0/P1 items (1, 2, 3, 4, 5, 6, 7, 8, 9) in a follow-up pass.
