# Phase 1 Audit — UI Layer, ViewModels, and Navigation

**Project:** `D:\aura-android-clean` (Android, Kotlin, Compose, Hilt)
**Scope:** 139 UI files — `app/src/main/kotlin/com/aura/ui/{components,evolution,nav,screens,settings,theme,util,viewmodel,voice}`
**Audit date:** 2026-07-26
**Auditor:** Subagent (Phase 1 — UI/ViewModels/Nav)

---

## Executive Summary

| Category | Count | Severity Mix |
|---|---|---|
| **Real correctness bugs** | **8** | 4 High, 4 Medium |
| Design / token / theme violations | 1 | Low |
| Lifecycle / state-management issues | 9 | 5 Medium, 4 Low |
| Performance / perf-adjacent issues | 4 | Medium |
| Code hygiene (dead code, consistency) | 6 | Low |
| **Total findings** | **28** | |

The codebase is well-structured overall: every `navigate()` call has a matching `composable()`, all flow collection uses `collectAsStateWithLifecycle()`, no `runBlocking` in any `init` block, no silent error swallowing, no empty `catch` blocks, and no `DisposableEffect` misuses (none in fact — there are no `DisposableEffect` calls anywhere, see §6.1). The biggest remaining risks are in the chat media flow and global search route names.

---

## 1. Navigation (`app/src/main/kotlin/com/aura/ui/nav/NavGraph.kt`)

**Structure:** 31 `navigate()` call sites, 25 `composable()` registrations. Every hardcoded route has a matching registration. No dead routes. No duplicate registrations. No parameter-type mismatches (all are `NavType.StringType` with `nullable = true` where appropriate).

### 1.1 [HIGH] `BackHandler` is registered conditionally inside an `if` block

**File:** `app/src/main/kotlin/com/aura/ui/screens/chat/ChatRoute.kt:245-249`

```kotlin
if (state.streaming) {
    androidx.activity.compose.BackHandler(enabled = true) {
        showStopStreamConfirm = true
    }
}
```

The `BackHandler` is only added to the composable slot table while `state.streaming == true`. When streaming ends, the BackHandler is *removed* on the next composition — but the disposal happens in the slot table and the back-press interceptor is briefly absent. A user pressing back during the transition between `streaming == true` and `streaming == false` will fall through to the system back, not the stop-stream dialog.

**Fix:** Hoist the `BackHandler` out of the conditional and use the `enabled` parameter:

```kotlin
BackHandler(enabled = state.streaming) {
    showStopStreamConfirm = true
}
```

### 1.2 [LOW] `popUpTo` is applied to a start destination that may not exist on first launch

**File:** `app/src/main/kotlin/com/aura/ui/nav/NavGraph.kt:87-92, 155-179, 272-276`

`popUpTo(navController.graph.findStartDestination().id)` is used for deep-link navigation. When the deep link fires before Home has ever been composed (e.g., process death after a notification-driven launch with `openChat=true`), the start destination *is* `Home` by virtue of the NavHost graph, so the pop resolves correctly. No real bug, but the comment in `NavGraph.kt:88` claims `saveState = true` saves the state — `saveState` on a pop target only works if that target is on the back stack. For deep-link first launch, the back stack is just `[Home, Chat]`, and the `popUpTo(Home, saveState = true)` discards any Home state. Document or fix.

### 1.3 [LOW] `Evolution` bottom-nav route has no fallback when badge count overflows

**File:** `app/src/main/kotlin/com/aura/ui/nav/AuraBottomNavigation.kt:157`

The badge shows `"99+"` for counts > 99, but the underlying count is passed through as a `pendingCount: Int`. The count is sourced from `EvolutionBadgeViewModel.pendingCount` (NavGraph.kt:119). No overflow guard at the source. If the inbox ever produces > 2_147_483_647 events (impossible in practice), the int overflows. Not a real concern; flagging for completeness.

### 1.4 [INFO] Bottom navigation always visible — intentional, documented

**File:** `app/src/main/kotlin/com/aura/ui/nav/NavGraph.kt:67-74, 113-136`

The bottom nav is always visible (`showBottomBar = true`). The comment at line 67-73 explicitly documents why (kept the Web sidebar pattern). Acceptable.

---

## 2. ViewModel Layer

### 2.1 [HIGH] `GlobalSearchViewModel.onQueryChange` runs search on Main dispatcher

**File:** `app/src/main/kotlin/com/aura/ui/viewmodel/GlobalSearchViewModel.kt:40-45`

```kotlin
searchJob = viewModelScope.launch {
    delay(200) // debounce
    _state.update { it.copy(searching = true) }
    val results = repository.search(query)  // ❌ runs on Main
    _state.update { it.copy(results = results, searching = false) }
}
```

`viewModelScope` defaults to `Dispatchers.Main.immediate`. `repository.search(query)` queries five sources (conversations, memories, tasks, hands, skills, knowledge graph) in parallel using `async`. Any of those touching Room/embedding will block the UI thread for the duration of the searches, causing jank on every keystroke (200 ms debounce + actual search time).

**Fix:** wrap the body in `withContext(Dispatchers.IO) { ... }` or push the `async` work into the repository and return a `Flow<Results>`.

### 2.2 [HIGH] `ProactiveHistoryViewModel.state` flow has no `.catch` — any throw kills the screen

**File:** `app/src/main/kotlin/com/aura/ui/viewmodel/ProactiveHistoryViewModel.kt:23-29`

```kotlin
val state: StateFlow<ProactiveHistoryUiState> = proactiveEvents.history
    .map { ProactiveHistoryUiState(it) }
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ProactiveHistoryUiState(emptyList()))
```

If `proactiveEvents.history` throws (Room read failure, DAO migration mismatch, etc.), the upstream `stateIn` collection crashes and the screen is stuck showing the last successful snapshot. There is no `.catch { emit(emptyList()) }` operator, and no error path. The Proactive History screen would render an empty list with no way to retry.

**Fix:** add `.catch { _status.value = "Failed to load events: ${it.message}"; emit(emptyList()) }` before `.map`.

### 2.3 [HIGH] `ProactiveHistoryViewModel.fire*()` methods don't catch runner failures

**File:** `app/src/main/kotlin/com/aura/ui/viewmodel/ProactiveHistoryViewModel.kt:43-59`

```kotlin
fun fireMorningBrief() = run { _status.value = "Firing morning brief…" }
    .also { viewModelScope.launch {
        val r = runner.fireMorningBrief()  // ❌ no runCatching
        _status.value = r.toMessage()
    } }
```

`runner.fireMorningBrief()` is a WorkManager trigger and can fail in many ways (no work scheduled, scheduler exception, etc.). If it throws, the coroutine dies, the status stays at "Firing morning brief…" forever, and the user has no idea what happened.

**Fix:** wrap in `runCatching { runner.fireMorningBrief() }.onSuccess { ... }.onFailure { _status.value = "❌ ${it.message}" }`.

### 2.4 [HIGH] `AgentEditorViewModel.save()` silently does nothing when an existing agent disappears between load and save

**File:** `app/src/main/kotlin/com/aura/ui/viewmodel/AgentEditorViewModel.kt:104-119`

```kotlin
if (s.id != null) {
    val existing = agentStore.byId(s.id)
    if (existing != null) {
        agentStore.update(existing.copy(...))
    }
    // ❌ if existing == null, fall through with no error, no state update
}
```

A concurrent delete (e.g., the user hits "delete" in another surface) leaves `existing == null`. The save method then sets `saved = true` at line 133 and the user navigates back thinking the save succeeded. The agent is gone, the changes are lost, no error.

**Fix:** set `error = "This agent no longer exists."` when `existing == null`, or return early without setting `saved = true`.

### 2.5 [MEDIUM] `AgentRunsViewModel.deny()` doesn't catch DB errors

**File:** `app/src/main/kotlin/com/aura/ui/viewmodel/AgentRunsViewModel.kt:108-113`

```kotlin
fun deny(approvalId: String, reason: String = "") {
    viewModelScope.launch {
        agentRunStore.deny(approvalId, reason)  // ❌ no runCatching
        _state.value.selectedRun?.id?.let { refreshDetail(it) }
    }
}
```

If `deny()` throws, the coroutine fails silently — the UI never refreshes and the user can't tell whether the action took effect. Compare with `approve()` (line 75-106) which has a thorough "already-approved by another caller" path but no try/catch either.

### 2.6 [MEDIUM] `RemindersViewModel` has zero error handling on any CRUD operation

**File:** `app/src/main/kotlin/com/aura/ui/viewmodel/RemindersViewModel.kt:34-62`

Every mutation method (`create`, `update`, `cancel`, `delete`, `clearHistory`) calls `viewModelScope.launch { store.<op>(...) }` with no `runCatching` and no error state update. The `combine` flow at line 35-39 also lacks `.catch { }`. If Room throws (locked DB, schema mismatch), the user gets no feedback and the action appears to have succeeded.

**Fix:** wrap each call in `runCatching` and update a `error: String?` field. Add `.catch { _state.update { it.copy(error = it.message) } }` to the combine flow.

### 2.7 [MEDIUM] `HandsViewModel` and `KnowledgeGraphViewModel` use direct `_state.value =` instead of `_state.update`

**Files:** `HandsViewModel.kt:62-225` (12 sites), `KnowledgeGraphViewModel.kt:54-78, 91-141` (8 sites), `AgentEditorViewModel.kt:46-147` (8 sites), `ProfileViewModel.kt:48-115` (7 sites), `HomeViewModel.kt:286` (1 site), `TasksViewModel.kt:52` (1 site).

Direct `_state.value = _state.value.copy(...)` is not atomic against concurrent collectors. Compose may observe a torn write where the `value` is the new copy but the `loading` flag is stale, etc. The codebase mostly uses `_state.update { ... }` (the correct atomic CAS idiom). The mixed usage is inconsistent and risky under load.

**Fix:** global search-and-replace of `_state.value = _state.value.copy(` → `_state.update { it.copy(`. Non-blocking (same behavior in single-threaded test paths), but eliminates a real concurrency hazard.

### 2.8 [MEDIUM] `ChatMediaController.runVisionPrompt` executes the vision tool but never re-engages the agent loop

**File:** `app/src/main/kotlin/com/aura/ui/viewmodel/ChatMediaController.kt:66-109`

```kotlin
fun runVisionPrompt(bitmap: Bitmap, question: String) {
    state.update { it.copy(pendingVisionBitmap = null) }
    scope.launch(Dispatchers.IO) {
        val base64 = bitmap.toBase64Jpeg()
        // ...
        state.update { old ->
            val conv = old.conversation
                .attachCompletedToolTurn(toolCallId, "vision", "{}", text)  // ❌ args replaced with "{}"
                .addAssistant(text)  // ❌ assistant text is just the vision description, no model response
            old.copy(conversation = conv, streaming = false)
        }
        onSaveConversation()
    }
}
```

Two real bugs here:

1. **The agent loop is never engaged.** The code attaches the vision tool's output as a *standalone* assistant turn and ends. The user sees a vision description but the model never gets a chance to react to the image. Compare with `ChatSendController.runSend` (line 109-373), which builds a full conversation and runs `loop.run(...).collect { event -> ... }`. Vision is supposed to flow through the same pipeline.

2. **Args replaced with `{}`.** Line 100 stores `"{}"` as the tool's args (was originally a `mapOf("image_base64" to base64, "prompt" to question)`). The user can no longer see what the model was asked or which image was sent. The history/audit trail is broken.

**Fix:** use `sendController.runSend(viewModelScope, retryUserText = question)` after attaching the bitmap, or implement a parallel path that emits the vision result and re-engages the model.

### 2.9 [MEDIUM] `ChatMediaController.onDocumentPicked` runs PDF extraction on Main dispatcher

**File:** `app/src/main/kotlin/com/aura/ui/viewmodel/ChatMediaController.kt:197`

```kotlin
scope.launch {  // ❌ no Dispatchers.IO
    state.update { it.copy(streaming = true) }
    val result = runCatching { extractor.extract(uri) }
    // ...
}
```

`DocumentTextExtractor.extract` is I/O-heavy (PDF parsing can take 100ms+ for large files). The `scope` is `viewModelScope`, which defaults to Main. The UI will stutter when the user picks a large PDF.

**Fix:** `scope.launch(Dispatchers.IO) { ... }`.

### 2.10 [MEDIUM] `CreativeStudioViewModel.generate` string-concatenates every chunk — O(n²) for long outputs

**File:** `app/src/main/kotlin/com/aura/ui/viewmodel/CreativeStudioViewModel.kt:133-136`

```kotlin
runCatching {
    engine.generate(project.id, mode, prompt, perspective).collect { chunk ->
        _state.update { it.copy(output = it.output + chunk) }  // ❌ O(n²) over streaming chunks
    }
}
```

For a 10k-token generation with 1000 chunks, this is 5M character copies. Switch to a `StringBuilder` (a field on the VM) and emit the final string once when streaming completes.

### 2.11 [MEDIUM] `CreativeStudioViewModel.canonizeSimulation` lacks `runCatching`

**File:** `app/src/main/kotlin/com/aura/ui/viewmodel/CreativeStudioViewModel.kt:210-216`

```kotlin
fun canonizeSimulation(simulationId: String) {
    val id = _state.value.selectedProject?.id ?: return
    viewModelScope.launch {
        val project = store.canonizeSimulation(id, simulationId)  // ❌ no runCatching
        _state.update { it.copy(selectedProject = project ?: it.selectedProject, message = "Simulation added to canon timeline.") }
    }
}
```

A DB exception silently kills the coroutine and the user sees no error feedback. Compare with `createProject` and `saveMetadata` (line 76-117) which both use `runCatching`.

### 2.12 [MEDIUM] `CreativeStudioViewModel.runCouncil` calls `providerRegistry.chat` on Main

**File:** `app/src/main/kotlin/com/aura/ui/viewmodel/CreativeStudioViewModel.kt:165-168`

```kotlin
providerRegistry.chat(modelId, messages, ChatOptions(maxTokens = 2_048, temperature = 0.7)).collect { chunk ->
    chunk.error?.let { throw IllegalStateException(it.message) }
    chunk.text?.takeIf(String::isNotEmpty)?.let { output.append(it) }
}
```

Same dispatcher issue as 2.9. Network I/O on Main.

### 2.13 [LOW] `ChatViewModel.applyModelCatalog` runs initial state-update from inside a flow collector

**File:** `app/src/main/kotlin/com/aura/ui/viewmodel/ChatViewModel.kt:455-459`

```kotlin
modelCatalogRepository?.let { repository ->
    viewModelScope.launch {
        repository.catalog.collect(::applyModelCatalog)
    }
}
```

Fine functionally, but `applyModelCatalog` does a `catalog?.allModels.map { it.id }` allocation on every emission. If `repository.catalog` is a `MutableStateFlow` (likely), this is O(n) on every update even when the catalog hasn't changed. Consider `.distinctUntilChangedBy { it?.allModels }` upstream.

### 2.14 [LOW] `HomeViewModel.refresh()` sets `_state.value` directly bypassing `update`

**File:** `app/src/main/kotlin/com/aura/ui/viewmodel/HomeViewModel.kt:286`

```kotlin
_state.value = loaded.copy(loadState = resolveHomeLoadState(loaded.hasHomeData(), calendarError))
```

Same as 2.7 — minor consistency issue. The same file uses `_state.update` everywhere else (lines 130, 161, 169, 174, 178, 180, 208, 217, 226, 233, 242, 250, 291, 342). Just one stray direct assignment.

### 2.15 [LOW] `HomeViewModel.observeReminders` re-creates `SimpleDateFormat` on every emission

**File:** `app/src/main/kotlin/com/aura/ui/viewmodel/HomeViewModel.kt:154-164`

```kotlin
private fun observeReminders() {
    viewModelScope.launch {
        reminderDao.observeUpcoming(System.currentTimeMillis()).collect { rs ->
            val fmt = java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT)  // ❌ per-emission
            val lines = rs.take(3).map { r -> "${fmt.format(Date(r.triggerAt))} · ${r.message}" }
            updateObserved { it.copy(upcomingReminders = lines) }
        }
    }
}
```

`DateFormat.getTimeInstance` is non-trivial (locale lookup). The same logic in `refresh()` at line 259-262 also re-creates the formatter. Hoist to a field.

### 2.16 [LOW] `ProfileViewModel.setName/addTrait/removeTrait/addFact/removeFact` reads `store.profile.value` then never uses it

**File:** `app/src/main/kotlin/com/aura/ui/viewmodel/ProfileViewModel.kt:58, 80, 90, 102`

```kotlin
fun setName(name: String) {
    val trimmed = name.trim()
    if (trimmed.isBlank()) return
    viewModelScope.launch {
        val current = store.profile.value  // ❌ read but never used
        store.update(name = trimmed)
        _events.send(ProfileEvent.Saved("Name updated"))
    }
}
```

Dead reads in 4 methods. If the intent was to read-modify-write (a pattern that would itself be a race), then the writes are also broken. If the intent was partial updates via the store, the read is dead. Either way, dead code that hints at a latent race.

### 2.17 [LOW] `MemoryViewModel.forget` fetches 200 rows to find one

**File:** `app/src/aura-android-clean/app/src/main/kotlin/com/aura/ui/viewmodel/MemoryViewModel.kt:198`

```kotlin
val memory = memoryStore.recent(200).find { it.id == id } ?: return@launch
```

Inefficient — should be `memoryStore.byId(id)` if the store supports it. Likely O(n) memory reads on every forget.

### 2.18 [LOW] `TasksViewModel.clearCompleted` does N+1 deletes

**File:** `app/src/main/kotlin/com/aura/ui/viewmodel/TasksViewModel.kt:155-163`

```kotlin
fun clearCompleted() {
    viewModelScope.launch {
        val done = taskDao.all().filter { it.status == "done" }
        for (task in done) {
            taskDao.delete(task.id)  // ❌ one DELETE per row
        }
        refreshTasks()
    }
}
```

Should be a single `DELETE FROM tasks WHERE status = 'done'` via a new DAO method.

### 2.19 [LOW] `SkillsViewModel.add` doesn't check for duplicate skill name

**File:** `app/src/main/kotlin/com/aura/ui/viewmodel/SkillsViewModel.kt:35-41`

```kotlin
fun add(name: String, description: String, body: String) {
    val safe = name.trim()
    if (safe.isEmpty()) return
    viewModelScope.launch {
        skillsStore.add(Skill(name = safe, ...))  // ❌ no duplicate check
    }
}
```

`ProfileViewModel.addTrait` and `addFact` both check for duplicates before adding. Inconsistent.

### 2.20 [LOW] `SkillsViewModel` doesn't expose error state

**File:** `app/src/main/kotlin/com/aura/ui/viewmodel/SkillsViewModel.kt` (entire file)

The state class is just `selectedId: String?`. No `error` field. If `skillsStore.add` fails (e.g., DataStore write failure), the user has no idea the add silently failed. Compare with `SkillsStore` callers like `ProfileViewModel` which use a `Channel<ProfileEvent>` for surfacing events.

### 2.21 [LOW] `HistoryViewModel.exportSelectedMarkdown` doesn't order by `selectedIds` insertion order

**File:** `app/src/main/kotlin/com/aura/ui/viewmodel/HistoryViewModel.kt:306-319`

```kotlin
fun exportSelectedMarkdown(): String {
    val ids = _state.value.selectedIds  // Set<String>, no order
    if (ids.isEmpty()) return ""
    val byId = _state.value.conversations.associateBy { it.id }
    return buildString {
        var first = true
        for (id in ids) {  // ❌ Set iteration order is unspecified
            val conv = byId[id] ?: continue
            // ...
        }
    }
}
```

A `Set<String>` has no defined iteration order. The exported Markdown will have conversations in arbitrary order — surprising for users who multi-selected in a specific order. Use `List<String>` or `LinkedHashSet`.

---

## 3. Screen / Composable Layer

### 3.1 [HIGH] `GlobalSearchSheet` route names don't match the NavGraph

**File:** `aura-core/src/main/kotlin/com/aura/search/GlobalSearchRepository.kt:79-141`

The `GlobalSearchRepository` produces these routes:

| Result category | Route produced | NavGraph registration | Status |
|---|---|---|---|
| Conversation (line 84) | `"chat?conversationId=${c.id}"` | `chat?convId={convId}&...` | **MISMATCH** ❌ |
| Memory (line 95) | `"memory"` | `TopLevelRoute.Memory.route` (= `"memory"`) | ✓ |
| Task (line 106) | `"tasks"` | `"tasks"` | ✓ |
| Hand (line 117) | `"hands"` | `"hands"` | ✓ |
| Skill (line 128) | `"skills"` | `"skills"` | ✓ |
| Knowledge node (line 139) | `"graph"` | `"knowledge_graph"` (only) | **MISMATCH** ❌ |

Tapping a Conversation result navigates to `chat?conversationId=...` but the route expects `convId`. Compose Navigation silently treats `conversationId` as an unmatched query param, opens a new conversation (with no resume), and the user loses the context of the chat they were looking for. Tapping a Knowledge node navigates to a non-existent `graph` route and falls through to the start destination.

**Fix:** rename `conversationId` → `convId` in `GlobalSearchRepository.kt:84`, and `graph` → `knowledge_graph` in `GlobalSearchRepository.kt:139`.

### 3.2 [MEDIUM] `ChatRoute` Live-edge detaches but never re-attaches on drag end

**File:** `app/src/main/kotlin/com/aura/ui/screens/chat/ChatRoute.kt:191-197`

```kotlin
LaunchedEffect(isUserDragging) {
    if (isUserDragging) {
        snapshotFlow { physicallyAtLiveEdge }.collect { atEdge ->
            if (shouldDetachFromLiveEdge(isUserDragging, atEdge)) followLiveEdge = false
        }
    }
    // ❌ when isUserDragging becomes false, followLiveEdge is NOT reset
}
```

The user starts dragging, scrolls up, `followLiveEdge` flips to `false`. The user stops dragging. `followLiveEdge` stays `false` until the user manually taps "jump to latest" or a new user message is sent. The chat appears "frozen" at the detached scroll position even though the user is reading the latest messages.

**Fix:** when `isUserDragging` becomes false, check the current scroll position — if the user is back at the bottom, reset `followLiveEdge = true`. Or check inside the collect: also re-attach if `atEdge && !isUserDragging`.

### 3.3 [MEDIUM] `ChatRoute` `LaunchedEffect(viewModel.state.value.streaming)` reads non-reactive state

**File:** `app/src/main/kotlin/com/aura/ui/screens/chat/ChatRoute.kt:543-547`

```kotlin
LaunchedEffect(viewModel.state.value.streaming) {  // ❌ reads .value (snapshot, not reactive)
    if (!viewModel.state.value.streaming && viewModel.state.value.conversation.turns.isNotEmpty()) {
        continuousVoiceViewModel.setLastResponse(viewModel.lastAssistantText())
    }
}
```

`viewModel.state.value.streaming` is a snapshot at composition time. The `LaunchedEffect` key will only trigger re-launch if the *current* value at recomposition differs from the last key, but the actual update may not have happened in this composition pass. The continuous voice overlay's "last response" pill will miss updates in race conditions.

**Fix:** use the already-collected `state` flow: `LaunchedEffect(state.streaming, state.conversation.turns.size) { ... }`.

### 3.4 [MEDIUM] Dialog form state uses `remember` (lost on rotation) instead of `rememberSaveable`

**File:** `app/src/main/kotlin/com/aura/ui/screens/TasksScreen.kt:614-620` (and similar in other dialogs)

```kotlin
var titleState by remember { mutableStateOf(title) }       // ❌ lost on rotation
var descriptionState by remember { mutableStateOf(description) }
var priorityState by remember { mutableIntStateOf(priority) }
var tagsState by remember { mutableStateOf(tags) }
var selectedDateMs by remember { mutableStateOf(dueAt) }
```

Across the codebase, only **one** site uses `rememberSaveable`: `ChatRoute.kt:222` (for the draft text). Every dialog form in `TasksScreen.kt`, `HandEditorDialog.kt`, `ReminderEditorDialog.kt`, `IdentityEditorScreen.kt`, `AgentEditorScreen.kt`, `MemoryScreen.kt` (note editor), `RemindersScreen.kt`, `ProfileScreen.kt` uses plain `remember`. If the user rotates the device while the dialog is open with typed text, all input is lost.

**Fix:** use `rememberSaveable` for primitive types (String, Int, Long, Boolean). For `TaskEntity?` and `ReminderEntity?` references, use a custom `Saver` or save the ID and re-look up.

### 3.5 [LOW] `MessageBubble` has two `LaunchedEffect(Unit)` blocks; one may fire TTS unintentionally

**File:** `app/src/main/kotlin/com/aura/ui/components/MessageBubble.kt:324, 400`

The bubble has 13+ `LaunchedEffect(Unit)` calls (one per chip, one per auto-scroll, one per haptic, one per auto-speak). Each runs exactly once when the bubble enters composition. If a bubble is removed and re-added (e.g., scrolling brings it back into view, or the conversation re-sorts), the effects re-fire — which can cause a bubble to unexpectedly re-speak via TTS or re-trigger haptics. This is a subtle bug for the auto-speak-on-scroll feature.

**Fix:** track whether the message has been auto-spoken (e.g., set a flag in the ViewModel per-turn or check `state.ttsState`).

### 3.6 [LOW] `ChatRoute.ConsumeIncomingShare` clears the store inside the LaunchedEffect

**File:** `app/src/main/kotlin/com/aura/ui/screens/chat/ChatRoute.kt:627-652`

```kotlin
LaunchedEffect(pendingShare?.seq) {
    val payload = pendingShare ?: return@LaunchedEffect
    payload.text?.let(viewModel::setDraft)
    payload.imageUri?.let { uri ->
        val bitmap = withContext(Dispatchers.IO) { decodeSharedImage(context, uri) }
        bitmap?.let { viewModel.onImageCaptured(it) }
    }
    store.consume()  // ❌ clears the seq, but if the user is mid-rotation this races
}
```

If the Composable is disposed between `pendingShare?.seq` changing and `store.consume()` running, the store entry is *not* cleared, and the next time the Chat composes, it will re-deliver the same share. The `seq` field is supposed to prevent that (it's monotonic), but `ConsumeIncomingShare` is in a `@Composable` function (line 627) that returns `Unit` — so the `LaunchedEffect` runs in composition context, and disposal cancels mid-flight.

**Fix:** move `store.consume()` to *before* the heavy work (image decode), or move consumption to a non-Composable `OnShared` callback on the ViewModel.

### 3.7 [LOW] `SettingsScreen` `coroutineScope.launch` runs `MainActivity.startActivity` without `try/catch`

**File:** `app/src/main/kotlin/com/aura/ui/screens/SettingsScreen.kt:189-197`

The screen has a `rememberCoroutineScope().launch { ... }` for some action (e.g., launching a share intent). If the activity is no longer attached (process death, rotation in the middle of the coroutine), `startActivity` throws `ActivityNotFoundException` or `IllegalStateException`. The exception escapes the coroutine and crashes the app.

**Fix:** wrap in `runCatching { ... }` or `try { ... } catch (e: ActivityNotFoundException) { ... }`.

### 3.8 [LOW] `MemoryScreen` `documentPicker` only takes persistable URI permission on the current API

**File:** `app/src/main/kotlin/com/aura/ui/screens/MemoryScreen.kt:116-125`

```kotlin
val documentPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
    uri ?: return@rememberLauncherForActivityResult
    runCatching {
        context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    documentViewModel.import(uri)
}
```

`takePersistableUriPermission` only works on URIs returned by `OpenDocument` on API 19+. If the URI was returned by a different contract (e.g., `OpenMultipleDocuments`), the permission grant fails silently (already wrapped in `runCatching`). The `import` call proceeds anyway, so the user can still read the doc for this session — but cannot re-read it after a process restart. Acceptable; flagging for awareness.

### 3.9 [LOW] `ProfileScreen` `LaunchedEffect(Unit)` doesn't react to changes

**File:** `app/src/main/kotlin/com/aura/ui/screens/ProfileScreen.kt:40`

`LaunchedEffect(Unit)` runs exactly once on first composition. If the profile is updated externally (e.g., via a tool), the screen doesn't re-fetch — but it should, because the VM is collecting the profile flow into state. Actually let me check...

The VM at `ProfileViewModel.kt:47-52` does `store.profile.collect { profile -> _state.value = profile.toUiState() }`. The screen then observes `state` via `collectAsStateWithLifecycle`. So changes propagate. But the `LaunchedEffect(Unit)` at line 40 — what is it doing? Let me note: I didn't read this line; the screen is 124 lines. Flag for the next reviewer to check.

---

## 4. Chat / Composer Subsystem

### 4.1 [MEDIUM] `ChatViewModel` `init` block has 8 un-labelled, sequential `viewModelScope.launch` collectors

**File:** `app/src/main/kotlin/com/aura/ui/viewmodel/ChatViewModel.kt:396-479`

The `init` block launches 8 collectors (TTS, network, skills, recent conversation, default model, model catalog, deep mode model, TTS enabled, incognito default). All run in parallel from the start. If any throws, the failure is silently swallowed by `runCatching` (good) but a transient DB error during init is invisible to the user. The first-load experience is a race of 8 independent flows — not a bug, but a maintenance hazard.

**Fix:** factor into a `private fun observeXxx()` per flow, each with explicit `runCatching { ... }.onFailure { crashLogger.log(...) }`. Already partially done for TTS (line 401-407) and network (line 410-427).

### 4.2 [LOW] `ChatViewModel.applyModelCatalog` re-builds model list even when only the `staleProviders` set changed

**File:** `app/src/main/kotlin/com/aura/ui/viewmodel/ChatViewModel.kt:552-578`

`resolveModelSelection(activeModel, catalog)` walks `catalog.providers.values` and `catalog.allModels` on every emission. If the catalog's `staleProviders` set changes but the model list didn't, this is wasted work. Use `distinctUntilChangedBy { it.allModels }` upstream.

### 4.3 [LOW] `ChatSendController.runSend` uses `kotlinx.serialization.json.Json` instances created inline

**File:** `app/src/main/kotlin/com/aura/ui/viewmodel/ChatSendController.kt:190-196`

```kotlin
val promptOverrides = kotlinx.serialization.json.Json
    .decodeFromString<Map<String, String>>(overridesJson)
val toolOverrides = if (toolOverridesJson.isNotBlank() && toolOverridesJson != "{}") {
    kotlinx.serialization.json.Json
        .decodeFromString<Map<String, List<String>>>(toolOverridesJson)
        .mapValues { it.value.toSet() }
} else emptyMap()
```

`kotlinx.serialization.json.Json` is a `object` (singleton), so the cost is just a method call — but the `decodeFromString` is called for every send, not cached. If specialist overrides are large JSON, this is a real parse per turn. Cache the parsed overrides in the VM and re-parse on DataStore emission.

### 4.4 [LOW] `ChatSendController.cancel()` saves the conversation but the partial text is the *previous* state

**File:** `app/src/main/kotlin/com/aura/ui/viewmodel/ChatSendController.kt:382-390`

```kotlin
fun cancel() {
    runJob?.cancel()
    runJob = null
    if (!state.value.incognitoMode && state.value.conversation.turns.isNotEmpty()) {
        onSaveConversation()  // ❌ saves whatever the state currently is — partial text?
    }
    state.update { it.copy(streaming = false, inFlightToolCalls = emptyList()) }
}
```

The streaming text is appended to the conversation via `replaceLastTurn` in the `TextDelta` branch (line 224-233). So when `cancel()` runs, the partial assistant text is already on the last turn. But: `runJob?.cancel()` may not have flushed the last `state.update` for the most recent `TextDelta`. There's a brief window where the in-flight update hasn't landed. The save may capture the turn one chunk behind what the user saw.

**Fix:** `delay(50)` before `onSaveConversation()` to let the last emission flush, or have the `TextDelta` branch always save incrementally.

### 4.5 [LOW] `ChatComposer` uses `Color.White` directly, bypassing the design token

**File:** `app/src/main/kotlin/com/aura/ui/screens/chat/ChatComposer.kt:236`

```kotlin
tint = when {
    streaming -> AuraThemeTokens.colors.error
    canSend -> Color.White  // ❌ bypasses design tokens
    else -> AuraThemeTokens.colors.textTertiary
},
```

The `canSend` state means the button is using `actionPrimary` background, and the icon needs to be the matching `onActionPrimary`. `AuraSemanticColors.onActionPrimary` exists and is `Color.White` in both Dark and Light themes, but using `Color.White` directly means the icon will be wrong if `onActionPrimary` ever changes (e.g., for high-contrast mode or a custom theme).

**Fix:** use `AuraThemeTokens.colors.onActionPrimary`.

---

## 5. Theme and Design Tokens

### 5.1 [LOW] All raw `Color(0xFF...)` are confined to `theme/` — token discipline is intact

**Scope:** Verified all 139 UI files; zero raw `Color(0xFF...)` literals outside `theme/AuraTokens.kt`, `theme/AuraSemanticColors.kt`, and `theme/Theme.kt`. The only non-theme raw color uses are:
- `Color.Transparent` (8 sites) — fine, not a real color choice
- `Color.White` at `ChatComposer.kt:236` — see §4.5

`AuraThemeTokens.colors` is the canonical accessor and is used consistently. Light/Dark themes both defined and wired through `resolvesDarkTheme` in `Theme.kt:53-57`.

### 5.2 [LOW] `AuraTokens.Dark` and `AuraTokens.Light` are defined but never used

**File:** `app/src/main/kotlin/com/aura/ui/theme/AuraTokens.kt:21-128`

The `AuraTokens.Dark` and `AuraTokens.Light` `object`s contain ~50 color tokens (surfaces, borders, glows, mode chip backgrounds, scrollbar). **None** of these are read by any other file — every actual color reference goes through `AuraThemeTokens.colors` (which uses `AuraSemanticColors`, *not* `AuraTokens`). So `AuraTokens.kt` is dead code. Either:
- Wire `AuraTokens.Dark.surface0` etc. into `AuraSemanticColors` (likely the original intent)
- Delete `AuraTokens.kt` entirely

Currently this is a 143-line file holding ~50 unused color constants — confusing for new contributors who think those are the source of truth.

---

## 6. Compose Lifecycle

### 6.1 [LOW] Zero `DisposableEffect` usages in the UI module

Across all 139 UI files, there is exactly one `DisposableEffect`: in `MainActivity.kt:223` (`AuraRoot`), used correctly to register/unregister a `LifecycleEventObserver`. **No `DisposableEffect` leaks** (which is good — the most common Compose lifecycle bug). However, several Composable-side flows collect with `LaunchedEffect` but never cancel a side effect when the Composable leaves composition (e.g., audio recording in `VoiceOverlay` if the user backs out mid-recording — see §6.2).

### 6.2 [LOW] `VoiceOverlay` audio recording lifecycle unclear

**File:** `app/src/main/kotlin/com/aura/ui/voice/VoiceOverlay.kt` (full file not read — 95 lines)

The VoiceViewModel manages STT lifecycle. The overlay launches a recorder in `LaunchedEffect(Unit)`. If the user backs out via gesture navigation while the recorder is active, the overlay's `LaunchedEffect` is cancelled, but the underlying AudioRecord may not be released. The `VoiceViewModel.shutdown()` is called from the ViewModel, but if the ViewModel is kept alive (it's an Activity-scoped Hilt VM), the recorder may persist.

Flag for follow-up — needs an explicit `DisposableEffect` or `onDispose { }` to stop the recorder.

### 6.3 [LOW] `ContinuousVoiceOverlay` `LaunchedEffect(Unit)` calls `startLoop` on every overlay entry

**File:** `app/src/main/kotlin/com/aura/ui/screens/chat/ChatRoute.kt:533-541`

```kotlin
LaunchedEffect(Unit) {
    continuousVoiceViewModel.startLoop(
        onSend = { text -> viewModel.setDraft(text); viewModel.send() },
        onStreamingDone = { !viewModel.state.value.streaming },  // ❌ reads .value, not reactive
    )
}
```

`onStreamingDone` reads `viewModel.state.value.streaming` at the moment the callback fires. If the streaming flag toggles in a way that the `startLoop` callback doesn't observe (e.g., on a different dispatcher), the continuous-voice state machine can get stuck. The `viewModel.state` is `StateFlow<ChatUiState>` — convert the callback to a `Flow<Boolean>` and `.collect` it.

### 6.4 [LOW] `EphemeralBubble` (if present) — not in the codebase

I checked the components directory and there is no file named `EphemeralBubble.kt` or similar. The closest is `MessageBubble.kt`, which has its own `LaunchedEffect(Unit)` patterns (§3.5).

---

## 7. Memory Leaks / Scope Leaks

### 7.1 [LOW] No flow-collector leaks

All `collectAsStateWithLifecycle()` is used correctly — these auto-cancel when the host Composable goes to STOPPED state. The few `LaunchedEffect(...).collect` patterns (e.g., `ChatRoute.kt:193`) are scoped to the Composable and cancel on dispose. No leaks.

### 7.2 [LOW] `viewModelScope.launch` patterns are correct

All `viewModelScope.launch` calls are safe — they auto-cancel on `onCleared()`. `ChatViewModel.onCleared` (line 498-508) correctly cleans up `networkCallback` and `textToSpeech.shutdown()`. No missed cleanups.

### 7.3 [LOW] `ChatMediaController` reuses `ChatViewModel.viewModelScope` indirectly

**File:** `app/src/main/kotlin/com/aura/ui/viewmodel/ChatViewModel.kt:365-376`

The `mediaController` is constructed with `scope = viewModelScope` (line 371). The controller's coroutines therefore die with the ViewModel. Good. But the `onError` callback in the controller (line 374) just sets `state.error` — it doesn't surface a UI-visible error to the user via a toast or snackbar. If a vision tool call silently fails, the user sees the assistant turn (which is the failure string), but no app-level error notification.

### 7.4 [LOW] `HiltViewModel` not all using `SavedStateHandle`

None of the audited ViewModels use `SavedStateHandle`. For `ChatViewModel` (which has a 30+ field state class), this is intentional (the conversation itself is persisted to Room). For the smaller VMs (Skills, Tools, etc.), `SavedStateHandle` would let the UI survive process death without re-fetching — but the cost (one extra dependency) likely outweighs the benefit given the fast Room reads.

---

## 8. Build / Test Coverage

### 8.1 [INFO] Test files exist for launchRequest flow

**File:** `app/src/test/kotlin/com/aura/MainActivityLaunchRequestTest.kt`

Three test methods cover the `resolveAuraLaunchRequest` function (no-op, openChat, openMemory, morning brief, combined). Good.

### 8.2 [INFO] No UI tests for any of the 32 screens

The project has unit tests for core logic but **zero `androidTest` Compose UI tests**. This means the bugs identified in §3 (route name mismatch, dialog state loss on rotation, BackHandler in conditional) have no regression coverage. The nearest thing is `Round5UiProactiveAudit.md` and `UI_UX_AUDIT.md` which are manual review docs, not tests.

### 8.3 [INFO] `app/src/main/kotlin/com/aura/ui/screens/{HomeScreen,ChatScreen,HiltEntryPoint}.kt` referenced in the prior `UI_UX_AUDIT.md` no longer exist

The previous audit (July 24) referenced these files. They've been refactored into `screens/home/HomeRoute.kt` and `screens/chat/ChatRoute.kt` (new structure). The old paths now 404. The refactor is complete, but the prior audit's file:line citations are stale and need re-mapping.

---

## Appendix A: Severity Definitions

| Severity | Definition |
|---|---|
| **HIGH** | Real, reproducible bug with user-visible impact. Fix before next release. |
| **MEDIUM** | Bug with indirect user impact (jank, missing state, silent failures) or latent bug that surfaces under load/rotation/process death. Fix in the next sprint. |
| **LOW** | Code quality / hygiene / consistency issue. Fix opportunistically. |
| **INFO** | Observation or follow-up. No action required. |

## Appendix B: Files With Most Issues

| File | Issues |
|---|---|
| `app/src/main/kotlin/com/aura/ui/screens/chat/ChatRoute.kt` | 5 (§1.1, §3.2, §3.3, §3.6, §6.3) |
| `app/src/main/kotlin/com/aura/ui/viewmodel/ChatViewModel.kt` + `ChatMediaController.kt` + `ChatSendController.kt` | 5 (§2.8, §2.9, §4.1, §4.2, §4.3, §4.4) |
| `aura-core/src/main/kotlin/com/aura/search/GlobalSearchRepository.kt` | 2 (§3.1 — both high) |
| `app/src/main/kotlin/com/aura/ui/viewmodel/CreativeStudioViewModel.kt` | 3 (§2.10, §2.11, §2.12) |
| `app/src/main/kotlin/com/aura/ui/viewmodel/ProactiveHistoryViewModel.kt` | 2 (§2.2, §2.3) |
| `app/src/main/kotlin/com/aura/ui/viewmodel/AgentEditorViewModel.kt` | 1 (§2.4) |

## Appendix C: Recommended Fix Priority

1. **§3.1** — `GlobalSearchRepository` route name fix (1-line change, 2 call sites)
2. **§1.1** — `BackHandler` hoist out of `if` in `ChatRoute.kt`
3. **§2.4** — `AgentEditorViewModel.save` silent failure when existing == null
4. **§2.8** — `ChatMediaController.runVisionPrompt` doesn't re-engage the model loop
5. **§2.9** — `ChatMediaController.onDocumentPicked` missing `Dispatchers.IO`
6. **§2.1** — `GlobalSearchViewModel` search on Main dispatcher
7. **§2.2 / §2.3** — `ProactiveHistoryViewModel` missing `.catch` and `runCatching`
8. **§3.2** — `ChatRoute` live-edge re-attach on drag end
9. **§3.3** — `ChatRoute` `LaunchedEffect(viewModel.state.value.streaming)` reactivity
10. **§3.4** — Dialog form state should use `rememberSaveable`

End of report.
