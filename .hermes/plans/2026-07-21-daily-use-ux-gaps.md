# Daily-Use UX Gaps Implementation Plan

> **For Hermes:** Use subagent-driven-development skill to implement this plan task-by-task.

**Goal:** Fix 10 daily-use friction points that would annoy a user every day.

**Architecture:** Surgical Compose + ViewModel changes. No new Room migrations, no new infrastructure. All changes build on existing ChatViewModel, MessageBubble, ChatComposer, and ChatHeader.

**Tech Stack:** Kotlin, Compose, Hilt, existing ChatViewModel

---

## Pre-Audit: What Exists vs What's Needed

| Feature | Status | Evidence |
|---------|--------|----------|
| Retry last message | EXISTS (VM) not wired (UI) | `ChatViewModel.retryLast()` at line 827 — no button in ChatHeader or error state |
| Edit-and-resend | DOES NOT EXIST | No `editMessage` in ChatViewModel, no edit affordance in MessageBubble |
| Copy button on code blocks | DOES NOT EXIST | `CodeBlock` composable at MarkdownText.kt:578 — no copy button, only language label |
| Share response | DOES NOT EXIST | MessageBubble has `onCopy` but no `onShare` / ACTION_SEND |
| Conversation export from chat | DOES NOT EXIST | HistoryScreen has bulk export, ChatHeader has no export menu item |
| Quick-clear chat | DOES NOT EXIST | Only `newConversation()` (creates new) and delete from header |
| Friendly error messages | DOES NOT EXIST | Error state shows raw `error: String?` — "http_error: HTTP 429" |
| Offline indicator | DOES NOT EXIST | NetworkStateTool exists for the agent, no UI banner |
| Draft persistence | DOES NOT EXIST | ChatComposer uses `remember`, not `rememberSaveable` |
| Image paste from keyboard | DOES NOT EXIST | No clipboard image detection in ChatComposer |

---

## Phase 1: Chat actions (regenerate, edit-resend, share, clear)

### Task 1: Wire retryLast to error state and chat header

**Objective:** Add a "Regenerate" button to the error state and the chat header overflow menu.

**Files:**
- Modify: `app/src/main/kotlin/com/aura/ui/screens/chat/ChatHeader.kt` — add DropdownMenuItem
- Modify: `app/src/main/kotlin/com/aura/ui/screens/chat/ChatContent.kt` — wire error retry button to `retryLast`

**Approach:**
1. ChatHeader already has a DropdownMenu (line 179). Add a `DropdownMenuItem(text = "Regenerate", icon = Icons.Filled.Refresh, onClick = onRegenerate)`.
2. ChatContent error state (line 228) already has `retryable` param — wire the retry button to `onRegenerate` which calls `viewModel::retryLast`.
3. Pass `onRegenerate` from ChatRoute to ChatHeader and ChatContent.

**Commit message:** `feat(chat): wire regenerate button to retryLast`

### Task 2: Edit-and-resend user message

**Objective:** Long-press a user message to edit it and resend.

**Files:**
- Modify: `app/src/main/kotlin/com/aura/ui/components/MessageBubble.kt` — add long-press on user bubbles
- Modify: `app/src/main/kotlin/com/aura/ui/viewmodel/ChatViewModel.kt` — add `editAndResend(turnIndex: Int, newText: String)`
- Modify: `app/src/main/kotlin/com/aura/ui/screens/chat/ChatContent.kt` — pass edit callback
- Modify: `app/src/main/kotlin/com/aura/ui/screens/chat/ChatRoute.kt` — add edit dialog state

**Approach:**
1. ChatViewModel: add `editAndResend(turnIndex, newText)` — trims conversation to `turnIndex`, replaces the user message, re-sends.
2. MessageBubble: `UserBubble` gets `combinedClickable(onLongClick = onEdit)`.
3. ChatRoute: show an `EditMessageDialog` with a text field pre-filled with the original message, "Send" button calls `editAndResend`.

**Commit message:** `feat(chat): edit-and-resend user messages`

### Task 3: Share response via ACTION_SEND

**Objective:** Add a "Share" button to assistant message bubbles.

**Files:**
- Modify: `app/src/main/kotlin/com/aura/ui/components/MessageBubble.kt` — add share action to assistant bubble action row
- Modify: `app/src/main/kotlin/com/aura/ui/screens/chat/ChatContent.kt` — pass share callback

**Approach:**
1. MessageBubble assistant bubble already has an action row with Copy. Add a Share icon button next to it.
2. `onShare` callback creates an `Intent(ACTION_SEND)` with `type = "text/plain"` and `EXTRA_TEXT = text`, wrapped in `Intent.createChooser`.
3. Requires `LocalContext` — pass from ChatContent.

**Commit message:** `feat(chat): share response via system share sheet`

### Task 4: Conversation export from chat header

**Objective:** Add "Export as Markdown" to the chat header overflow menu.

**Files:**
- Modify: `app/src/main/kotlin/com/aura/ui/screens/chat/ChatHeader.kt` — add DropdownMenuItem
- Modify: `app/src/main/kotlin/com/aura/ui/viewmodel/ChatViewModel.kt` — add `exportConversation(): String` returning markdown
- Modify: `app/src/main/kotlin/com/aura/ui/screens/chat/ChatRoute.kt` — wire export to ACTION_SEND

**Approach:**
1. ChatViewModel: `exportConversation()` iterates turns, formats as markdown (`### User\n\n{text}\n\n### Assistant\n\n{text}`).
2. ChatHeader: add `DropdownMenuItem(text = "Export", icon = Icons.Filled.FileDownload, onClick = onExport)`.
3. ChatRoute: on export, create a temp file, write markdown, launch ACTION_SEND with the file URI.

**Commit message:** `feat(chat): export conversation as markdown`

### Task 5: Quick-clear chat

**Objective:** Add "Clear chat" to header menu that keeps the conversation but wipes all turns.

**Files:**
- Modify: `app/src/main/kotlin/com/aura/ui/screens/chat/ChatHeader.kt` — add DropdownMenuItem
- Modify: `app/src/main/kotlin/com/aura/ui/viewmodel/ChatViewModel.kt` — add `clearConversation()`

**Approach:**
1. ChatViewModel: `clearConversation()` — creates a fresh `Conversation()` with the same ID, saves to store, clears UI state.
2. ChatHeader: `DropdownMenuItem(text = "Clear chat", icon = Icons.Filled.CleaningServices, onClick = onClear)`.
3. ChatRoute: confirmation dialog before clearing.

**Commit message:** `feat(chat): quick-clear chat from header menu`

---

## Phase 2: Code blocks + error messages

### Task 6: Copy button on code blocks

**Objective:** Add a "Copy" button to every code block in rendered markdown.

**Files:**
- Modify: `app/src/main/kotlin/com/aura/ui/components/MarkdownText.kt` — `CodeBlock` composable

**Approach:**
1. Add a `var copied by remember { mutableStateOf(false) }` inside `CodeBlock`.
2. Add an `IconButton(onClick = { clipboard.setPrimaryClip(...); copied = true })` in the language label row, aligned to the end.
3. Icon: `Icons.Filled.ContentCopy` when not copied, `Icons.Filled.Check` when copied (auto-reset after 2s via LaunchedEffect).
4. Requires `LocalContext` for clipboard access.

**Commit message:** `feat(markdown): copy button on code blocks`

### Task 7: Friendly error messages

**Objective:** Replace raw HTTP error codes with human-readable messages.

**Files:**
- Modify: `app/src/main/kotlin/com/aura/ui/viewmodel/ChatViewModel.kt` — map error strings
- Create: `app/src/main/kotlin/com/aura/ui/components/ErrorMessageMapper.kt` — pure function

**Approach:**
1. Create `fun friendlyErrorMessage(raw: String): String` — maps:
   - "http_error: HTTP 429" → "Rate limited. Try again in a minute."
   - "http_error: HTTP 401" → "Your API key is invalid. Check Settings → AI & Models."
   - "http_error: HTTP 403" → "Access denied. Your API key may be expired."
   - "http_error: HTTP 500" → "The AI provider is having issues. Try again."
   - "missing_api_key" → "No API key configured. Go to Settings → AI & Models."
   - "not_configured" → "This provider isn't set up yet. Go to Settings."
   - "tool_timeout" → "A tool took too long to respond. Try again."
   - fallback → raw message
2. ChatViewModel: apply mapper when setting `error` in state.

**Commit message:** `feat(chat): friendly error messages instead of raw HTTP codes`

---

## Phase 3: Resilience

### Task 8: Offline indicator banner

**Objective:** Show a banner when the device is offline, before the user tries to send.

**Files:**
- Create: `app/src/main/kotlin/com/aura/ui/components/OfflineBanner.kt`
- Modify: `app/src/main/kotlin/com/aura/ui/screens/chat/ChatRoute.kt` — render banner above chat content
- Modify: `app/src/main/kotlin/com/aura/ui/viewmodel/ChatViewModel.kt` — observe connectivity

**Approach:**
1. ChatViewModel: inject `ConnectivityManager` via `@ApplicationContext`, expose `isOnline: StateFlow<Boolean>`.
2. `OfflineBanner` composable: small amber banner with "You're offline" + cloud-off icon, shown when `!isOnline`.
3. ChatRoute: render `OfflineBanner` above `ChatContent` when `!state.isOnline`.

**Commit message:** `feat(chat): offline indicator banner`

### Task 9: Draft persistence across process death

**Objective:** Survive app kill — typed text is restored when the app reopens.

**Files:**
- Modify: `app/src/main/kotlin/com/aura/ui/screens/chat/ChatComposer.kt` — use `rememberSaveable` for draft
- Modify: `app/src/main/kotlin/com/aura/ui/screens/chat/ChatRoute.kt` — hoist draft to rememberSaveable

**Approach:**
1. Change `draft` state from `remember` to `rememberSaveable` in ChatRoute (hoist up from ChatComposer).
2. Pass `draft` + `onDraftChange` down to ChatComposer as before.
3. `rememberSaveable` survives config changes AND process death (writes to Bundle).

**Commit message:** `feat(chat): persist draft text across process death`

### Task 10: Image paste from keyboard

**Objective:** When the user pastes an image from the clipboard (e.g. screenshot copied in Gboard), detect it and attach it as a vision image.

**Files:**
- Modify: `app/src/main/kotlin/com/aura/ui/screens/chat/ChatComposer.kt` — detect image paste
- Modify: `app/src/main/kotlin/com/aura/ui/viewmodel/ChatViewModel.kt` — accept pasted bitmap

**Approach:**
1. ChatComposer: `BasicTextField` gets a `TextFieldClipboardManager` or we intercept the keyboard paste via `LocalClipboardManager`.
2. On text change, check `clipboardManager.getText()` — if text is null but clipboard has an image URI, decode it.
3. Alternative simpler approach: add a `keyboardOptions = KeyboardOptions(capitalization = sentences)` with a `pasteDetector` LaunchedEffect that polls clipboard on focus.
4. On image detected: call `onImagePasted(bitmap)` which sets `pendingVisionBitmap` in ChatUiState (same as the existing image attachment flow).

**Commit message:** `feat(chat): detect pasted images from keyboard clipboard`

---

## Verification Gate

After each phase:
```bash
./gradlew :aura-core:testDebugUnitTest :app:testDebugUnitTest :app:assembleDebug
```

After all phases:
```bash
./gradlew :app:lintDebug
```

## Commit Summary

| Task | Phase | Commit message |
|------|-------|----------------|
| 1 | 1 | feat(chat): wire regenerate button to retryLast |
| 2 | 1 | feat(chat): edit-and-resend user messages |
| 3 | 1 | feat(chat): share response via system share sheet |
| 4 | 1 | feat(chat): export conversation as markdown |
| 5 | 1 | feat(chat): quick-clear chat from header menu |
| 6 | 2 | feat(markdown): copy button on code blocks |
| 7 | 2 | feat(chat): friendly error messages instead of raw HTTP codes |
| 8 | 3 | feat(chat): offline indicator banner |
| 9 | 3 | feat(chat): persist draft text across process death |
| 10 | 3 | feat(chat): detect pasted images from keyboard clipboard |

## Notes

- No Room migrations needed — all changes are in the UI and ViewModel layers.
- No new permissions needed — ACTION_SEND, clipboard, and ConnectivityManager are already available.
- Tests: add ChatViewModelTest cases for `editAndResend`, `exportConversation`, `clearConversation`, `friendlyErrorMessage`. Existing tests must stay green.
- APK release at the end via `gh release create`.