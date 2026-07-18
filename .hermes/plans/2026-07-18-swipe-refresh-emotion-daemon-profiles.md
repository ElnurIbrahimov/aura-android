# Aura Android — Swipe/Refresh/Emotion/Daemon/Profiles Plan

5 items, ~12 commits. Ordered by surgery size (smallest first).

---

## Item 5: Swipe-to-delete (2 commits)

### 5.1 SwipeToDismiss on MemoryScreen + TasksScreen
- Add `SwipeToDismissBox` (Material 3) to memory list items in `MemoryScreen.kt`
- Add `SwipeToDismissBox` to task list items in `TasksScreen.kt`
- Each swipe reveals a red delete background + calls `viewModel.delete(id)`
- Both ViewModels already have `delete()` methods
- Add `rememberSwipeToDismissBoxState` per item

### 5.2 SwipeToDismiss on HandsScreen + HistoryScreen
- Add `SwipeToDismissBox` to hand list items in `HandsScreen.kt` → `HandsViewModel.delete()`
- Add `SwipeToDismissBox` to history list items in `HistoryScreen.kt` → `HistoryViewModel.delete()`
- Verify both ViewModels have delete methods (add if missing)

---

## Item 6: Pull-to-refresh (1 commit)

### 6.1 PullToRefreshBox on AgentRuns + EvolutionInbox + ProactiveHistory
- Add `PullToRefreshBox` (Material 3 `pullToRefresh` API) wrapping the LazyColumn in:
  - `AgentRunsScreen.kt` → `viewModel.loadRuns()`
  - `EvolutionInboxScreen.kt` → `viewModel.load()`
  - `ProactiveHistoryScreen.kt` → `viewModel.load()`
- Each ViewModel already has a load/refresh method
- Add `pullToRefresh` import, `PullToRefreshState`

---

## Item 7: Emotional State Engine (3 commits)

### 7.1 EmotionEngine core
- New: `aura-core/src/main/kotlin/com/aura/emotion/EmotionEngine.kt`
- Tracks 4 dimensions: tension (0-1), connection (0-1), energy (0-1), focus (0-1)
- Each dimension has: current value, inertia (how fast it changes), decay rate (how fast it returns to baseline)
- `update(turn: ConversationTurn)` — adjusts dimensions based on user message signals:
  - Short messages → tension up
  - Questions → focus up
  - Long detailed messages → connection up, energy up
  - Expressions of frustration → tension up, energy up
  - Expressions of satisfaction → tension down, connection up
  - Code requests → focus up
- `decay()` — called on each turn, moves values toward baseline by decayRate
- `snapshot(): EmotionSnapshot` — returns current 4D state
- `profile(): ResponseProfile` — maps emotion to one of 6 profiles (see 9.1)
- Persisted in DataStore as JSON (4 floats + timestamp)
- `@Singleton`, injected into `MemoryAugmentedAgenticLoop`

### 7.2 Wire EmotionEngine into agentic loop
- Inject `EmotionEngine` into `MemoryAugmentedAgenticLoop` (optional param)
- After each user message: `emotionEngine.update(lastUserMessage)`
- After each turn: `emotionEngine.decay()`
- Include emotion snapshot in system prompt as a "Current mood context" section
- The model sees: `# Current mood: tension=0.3, connection=0.7, energy=0.5, focus=0.8`
- This gives the model context to adapt its tone without hard rules

### 7.3 EmotionEngine tests
- Test dimension updates for various message types
- Test decay returns to baseline
- Test persistence (save/load snapshot)
- Test profile mapping thresholds

---

## Item 8: DAEMON Background Thinking (3 commits)

### 8.1 DaemonWorker
- New: `aura-core/src/main/kotlin/com/aura/proactive/DaemonWorker.kt`
- `CoroutineWorker` that runs every ~8 minutes via `PeriodicWorkRequest`
- On each tick:
  1. Load the last conversation (or last N turns)
  2. Load active beliefs + recent memories
  3. Build a "thinking" prompt: "Here's what happened recently. Any insights?"
  4. Call the configured background model (from `UserPreferences.backgroundModel`)
  5. If the model returns something substantive, post a proactive event
  6. If nothing, stay silent (don't spam)
- Uses `ProviderRegistry.chat()` directly (not the agentic loop — no tools)
- Posts via `ProactiveEventBus.emit()` with type "daemon_insight"
- Respects `UserPreferences.daemonEnabled` (new toggle, default false)

### 8.2 DaemonScheduler + Settings toggle
- New: `aura-core/src/main/kotlin/com/aura/proactive/DaemonScheduler.kt`
- `schedule(context)` — enqueues PeriodicWorkRequest (8 min interval)
- `cancel(context)` — cancels the worker
- Add `daemonEnabled` to `UserPreferences` (default false)
- Wire into `ProactiveBootstrap` — schedule/cancel based on preference
- Add Settings toggle in `SettingsScreen` + `SettingsViewModel`

### 8.3 DaemonWorker tests
- Test that worker produces a proactive event when model returns text
- Test that worker stays silent when model returns empty
- Test daemonEnabled=false skips execution

---

## Item 9: Adaptive Response Profiles (2 commits)

### 9.1 ResponseProfile enum + mapping
- New: `aura-core/src/main/kotlin/com/aura/emotion/ResponseProfile.kt`
- 6 profiles (Kira's IRIS-inspired):
  1. NEUTRAL — default, balanced tone
  2. WARM — high connection, low tension → friendly, expansive
  3. FOCUSED — high focus, mid energy → concise, technical
  4. ENERGETIC — high energy, high tension → dynamic, fast-paced
  5. CALM — low energy, low tension → slow, gentle, reassuring
  6. DIRECT — high tension, low connection → terse, no-nonsense
- `EmotionEngine.profile()` returns the active profile based on thresholds:
  - tension > 0.7 && connection < 0.3 → DIRECT
  - tension > 0.6 && energy > 0.6 → ENERGETIC
  - connection > 0.7 && tension < 0.3 → WARM
  - focus > 0.7 && energy < 0.4 → FOCUSED
  - energy < 0.3 && tension < 0.3 → CALM
  - else → NEUTRAL
- Each profile has a `promptSuffix: String` appended to the system prompt

### 9.2 Wire profiles into specialist system prompts
- In `MemoryAugmentedAgenticLoop`, after building the system prompt, append:
  `emotionEngine?.profile()?.promptSuffix?.takeIf { it.isNotBlank() }`
- Example: DIRECT adds "\n\nTone: Be terse and direct. No pleasantries."
- This is additive — it doesn't replace the specialist prompt, just adapts tone
- Add Settings toggle for "Adaptive responses" (default true)

---

## Summary

| Item | Commits | Est. |
|------|---------|------|
| 5. Swipe-to-delete | 2 | 30 min |
| 6. Pull-to-refresh | 1 | 15 min |
| 7. Emotion engine | 3 | 1.5 h |
| 8. DAEMON thinking | 3 | 1.5 h |
| 9. Response profiles | 2 | 45 min |
| Total | 11 | ~5 h |