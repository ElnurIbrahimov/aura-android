# Daily-Use UX Gaps Round 2 — Implementation Plan

> **For Hermes:** Use subagent-driven-development skill to implement this plan task-by-task.

**Goal:** Ship 5 more daily-use fixes that would annoy a user weekly:
text selection in messages, keyboard hide on send, TTS stop button +
visual indicator, undo for delete, response metadata footer.

**Branch:** `feat/tier-1-friction` (continue from v0.29.1)

**Test baseline:** 876 tests, 0 failures

**Ship convention:** Atomic commits per task, push at end, GitHub release at end.

---

## Task 1: Text selection in messages (P0)

**Problem:** User can't highlight a phrase inside a response. Long-press
shows a copy button that copies the whole message, but you can't select
just one sentence.

**Files to modify:**
- `app/src/main/kotlin/com/aura/ui/components/MessageBubble.kt` —
  wrap `Text()` in `SelectionContainer` for both UserBubble and the
  assistant content area (skip the code blocks and citations footer).
- `app/src/main/kotlin/com/aura/ui/components/MarkdownText.kt` —
  wrap the main content `Text()` in `SelectionContainer` while keeping
  the link `ClickableText` for URL handling. Two approaches:
  1. Make MarkdownText output use `Text` with `selectionRange` via
     `SelectionContainer` wrapping `Text` for non-link segments.
  2. Split markdown into selectable + interactive parts.

  **Decision:** Use `SelectionContainer` around the `Text` calls that
  render plain markdown content. The `ClickableText` for links stays
  separate and handles its own clicks. Selection within link text won't
  work, but the 95% case (selecting body text) will.

**Implementation steps:**

1. In MessageBubble.kt, import `SelectionContainer`.
2. In `UserBubble`, wrap the body `Text` in `SelectionContainer`.
3. In `AssistantMessage`, wrap the body `Text` (or the content passed
   to it after markdown rendering) in `SelectionContainer`.
4. In MarkdownText.kt, identify the main `Text` calls (around line 428
   and the segment renderer) and wrap them in `SelectionContainer`.
5. Test: scroll chat, long-press body text, verify text-selection
   toolbar appears, select phrase, copy, paste — should be just the
   phrase.

**Acceptance:** User can long-press any message body, select a phrase,
and copy just that phrase.

---

## Task 2: Keyboard hide on send (P1)

**Problem:** Keyboard stays open after tapping send. User has to
manually dismiss it. ChatGPT hides it immediately.

**Files to modify:**
- `app/src/main/kotlin/com/aura/ui/screens/chat/ChatComposer.kt` —
  call `LocalSoftwareKeyboardController.current?.hide()` inside the
  send handler.
- `app/src/main/kotlin/com/aura/ui/screens/chat/ChatRoute.kt` —
  same for the "Send" callback in ChatRoute.

**Implementation steps:**

1. Import `LocalSoftwareKeyboardController` in ChatComposer.kt.
2. Inside the send `AuraIconButton onClick` block, get the keyboard
   controller and call `.hide()` after `onSend()`.
3. In ChatRoute.kt, the `onSend` callback passed to ChatComposer —
   hide keyboard there too as a belt-and-suspenders measure.
4. Test: type a message, tap send, verify keyboard collapses.

**Acceptance:** After tapping send, the keyboard collapses immediately
without further user action.

---

## Task 3: TTS stop button + visual indicator (P1)

**Problem:** When TTS is speaking, there's no visible way to stop it.
User can't tell which message is being spoken, and the only way to
stop is to wait for it to finish or kill the app.

**Current state:**
- `TextToSpeech.stop()` exists in `aura-core/.../voice/TextToSpeech.kt`
- `TextToSpeech.state` is a `StateFlow<State>` with `Speaking(utteranceId)`
- `ChatViewModel.ttsEnabled` exists but no `ttsState` or `speakingId`

**Files to modify:**
- `aura-core/src/main/kotlin/com/aura/voice/TextToSpeech.kt` —
  no changes needed, the API is already there.
- `app/src/main/kotlin/com/aura/ui/viewmodel/ChatViewModel.kt` —
  add `ttsState` to `ChatUiState`, collect from `textToSpeech.state`,
  add `stopTts()` function.
- `app/src/main/kotlin/com/aura/ui/screens/chat/ChatContent.kt` —
  add a small floating stop-TTS pill above the composer when
  `ttsState is Speaking`. Shows a stop icon + "Tap to stop" text.
- `app/src/main/kotlin/com/aura/ui/components/MessageBubble.kt` —
  highlight the currently-speaking message with a subtle accent
  (e.g. pulsing left border or soft background tint).

**Implementation steps:**

1. In `ChatUiState`, add:
   ```kotlin
   val ttsState: TextToSpeech.State = TextToSpeech.State.Idle,
   ```
2. In `ChatViewModel.init`, add a collector:
   ```kotlin
   viewModelScope.launch {
       textToSpeech.state.collect { state ->
           _state.update { it.copy(ttsState = state) }
       }
   }
   ```
3. Add `fun stopTts()` that calls `textToSpeech.stop()`.
4. In ChatContent.kt, add a small `Surface` (pill-shaped) above the
   composer that's visible only when `ttsState is TextToSpeech.State.Speaking`.
   Tapping it calls `onStopTts()`.
5. In ChatRoute, pass `onStopTts = viewModel::stopTts` to ChatContent.
6. In MessageBubble.kt, accept an `isSpeaking: Boolean = false` param.
   When true, add a 2dp left border in `AuraThemeTokens.colors.actionPrimary`
   to mark it as the currently-speaking message.
7. In ChatTimeline.kt, pass `isSpeaking = state.ttsState is Speaking &&
   speakingUtteranceId matches` to MessageBubble. For simplicity, just
   pass `isSpeaking = state.ttsState is Speaking` to the LAST turn.
8. Test: enable TTS, send a message, watch for stop pill, tap it,
   verify TTS stops.

**Acceptance:** When TTS is speaking, a "Stop reading" pill appears
above the composer. The last message has a subtle accent border. Tapping
the pill stops the speech immediately.

---

## Task 4: Undo for delete (P1)

**Problem:** Delete a conversation → it's gone. No undo. Once is fine,
twice is data loss.

**Approach:** Cache the deleted conversation in `HistoryViewModel` for
5 seconds. If `restoreLastDeleted()` is called within 5s, re-insert
the conversation. Show a Snackbar with "Undo" action.

**Files to modify:**
- `app/src/main/kotlin/com/aura/ui/viewmodel/HistoryViewModel.kt` —
  add `lastDeleted: Conversation?` field, capture in `delete()`,
  add `restoreLastDeleted()` function.
- `app/src/main/kotlin/com/aura/ui/screens/HistoryScreen.kt` —
  wrap the screen in a `Scaffold` with `SnackbarHost`, show snackbar
  on delete with "Undo" action.

**Implementation steps:**

1. In `HistoryViewModel`, add field:
   ```kotlin
   private var lastDeleted: Conversation? = null
   ```
2. In `delete(id: String)`, capture the conversation BEFORE deleting:
   ```kotlin
   val conv = store.get(id) // or load from current state
   store.delete(id)
   lastDeleted = conv
   // Clear after 5 seconds
   viewModelScope.launch {
       delay(5000)
       lastDeleted = null
   }
   _state.update { it.copy(lastDeleted = conv) }
   ```
3. Add `fun restoreLastDeleted()`:
   ```kotlin
   val conv = lastDeleted ?: return
   viewModelScope.launch {
       store.upsert(conv) // need to add this if it doesn't exist
       // refresh list
   }
   lastDeleted = null
   _state.update { it.copy(lastDeleted = null) }
   ```
4. In `HistoryViewModelUiState`, add `lastDeleted: Conversation? = null`.
5. In HistoryScreen, wrap content in `Scaffold(snackbarHost = ...)`.
6. In a `LaunchedEffect(state.lastDeleted)`, when it's set, call
   `snackbarHostState.showSnackbar("Conversation deleted", "Undo")`
   and on Undo action call `viewModel.restoreLastDeleted()`.
7. Need a `conversationStore.upsert(conversation: Conversation)` method
   if it doesn't exist. Check `aura-core/.../agent/ConversationStore.kt`.
8. Test: delete a conversation, see snackbar with Undo, tap Undo,
   verify conversation is back in the list.

**Acceptance:** Deleting a conversation shows a Snackbar with
"Undo". Tapping Undo within 5 seconds restores the conversation.

---

## Task 5: Response metadata footer (P2)

**Problem:** After the model finishes, no info on how long it took,
how many tokens, what model answered. Power users want this.

**Current state:**
- `MemoryAugmentedAgenticLoop` tracks per-turn tool calls and timing
- `ProviderRegistry` has a `billableChunkSeen` mechanism
- No exposed "turn duration" or "token count" in the conversation

**Approach:** Add duration + token estimate + model name as a small
footer under each assistant message. Lightweight: just timing data
captured at the AgentEvent.Done handler.

**Files to modify:**
- `aura-core/src/main/kotlin/com/aura/agent/Conversation.kt` —
  add `durationMs: Long = 0L` and `tokenCount: Int = 0` to the
  Turn data class (or as metadata).
- `aura-core/src/main/kotlin/com/aura/agent/MemoryAugmentedAgenticLoop.kt` —
  capture `System.currentTimeMillis()` at start of run, compute
  duration on Done event, pass into the conversation update.
- `app/src/main/kotlin/com/aura/ui/components/MessageBubble.kt` —
  add `durationMs: Long = 0L` and `modelLabel: String?` (already
  exists) params, render a small footer line if durationMs > 0:
  "1.2s · gpt-4o" in JetBrains Mono, 10sp, textSecondary.
- `app/src/main/kotlin/com/aura/ui/viewmodel/ChatViewModel.kt` —
  when applying Done event, pass the duration from the loop.

**Implementation steps:**

1. In `Conversation.kt` (or wherever Turn is defined), add:
   ```kotlin
   val durationMs: Long = 0L
   ```
2. In `MemoryAugmentedAgenticLoop`, capture start time per run.
3. On `AgentEvent.Done`, include `durationMs` in the final Turn.
4. In `ChatViewModel`, ensure the duration flows into the turn.
5. In `MessageBubble`, add `durationMs: Long = 0L` param. Render
   footer only if `durationMs > 0` and isUser=false.
6. Footer format: "${durationMs/1000.0}s · $modelLabel" using
   `JetBrains Mono` font, 10sp, alpha 0.5 of textSecondary.
7. Test: send a message, watch for footer under the response.

**Acceptance:** Each assistant response shows a small "1.2s · gpt-4o"
footer below the message bubble.

---

## Commit Plan

1. `feat: text selection in chat messages` (Task 1)
2. `feat: hide keyboard on send` (Task 2)
3. `feat: TTS stop button + visual indicator` (Task 3)
4. `feat: undo for conversation delete` (Task 4)
5. `feat: response metadata footer` (Task 5)
6. GitHub release v0.29.2

## Verification

- `./gradlew :aura-core:compileDebugKotlin :app:compileDebugKotlin` — no errors
- `./gradlew :aura-core:testDebugUnitTest :app:testDebugUnitTest` — 876+ pass
- `./gradlew :app:assembleDebug` — APK builds
- git push aura-android feat/tier-1-friction
- gh release create v0.29.2 with APK

## Risk Notes

- **Task 1 (SelectionContainer):** `SelectionContainer` on `Text` with
  link annotations may swallow the click. Need to test: can the user
  still click links after wrapping? If not, move SelectionContainer
  to wrap the Text but exclude link segments.
- **Task 4 (undo):** `ConversationStore.upsert()` may not exist —
  need to verify and add if needed. If `turnsJson` serialization
  changes between versions, upsert may fail.
- **Task 5 (duration):** `Turn` is a data class — adding a field
  breaks every copy of it. Need to add with default value, but
  check all constructor call sites.

## Out of Scope (intentionally deferred)

- Model context window / cost tier display in picker (Task 7 from gap list)
- Inline reply from proactive notification (Task 8)
- Conversation duplicate / branch (Task 9)
- In-chat Ctrl+F search (Task 1 from gap list, complex)
- Date grouping in History (Task 6 from gap list)
