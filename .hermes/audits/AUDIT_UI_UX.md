# Aura Android — UI/UX Layer Audit

**Scope:** Compose UI layer (22 screens, 27 ViewModels, design system shell)
**Focus:** Real bugs, accessibility gaps, design system violations, dead UI
**Method:** Static review of Kotlin sources; every claim cites `file:line` for verification
**Severity scale:** **S1** Bug (functional defect / crash / a11y blocker) · **S2** Quality (perf / maintainability with user impact) · **S3** Hygiene (inconsistency / dead code / design-system violation)
**Confidence:** H = direct evidence in code, M = inferred from call sites, L = architectural smell

---

## 1. Top 5 findings (executive summary)

1. **S1 — `ChatSendController.runSend` re-entrancy (ChatSendController.kt:144-270).** The early-return guard at line 148 (`if (text.isEmpty() || current.streaming) return`) reads `state.value` non-atomically, then performs an `_state.update` at line 178 (add user, set streaming=true). Between the read and the update, a second `runSend` call (e.g. a fast double-tap on the send button, or a programmatic `send()` triggered by a completion callback at line 504/558) can read the same `streaming = false` value, then both calls proceed to `state.update`, both `addUser` the same `text`, and the user's input appears twice in the conversation. Compounding this, the title-generation check at line 198 reads `state.value.conversation.turns.count { it.user != null } == 1` after the append, but it only fires for the *first* turn; if the race doubles the user message, the title still gets generated but the model sees the pre-append snapshot from the local `conversation` variable at line 280 (captured before either update). Fix: introduce a `kotlinx.coroutines.sync.Mutex` or move the guard into the `_state.update` block so the streaming flag flip is atomic with the read. (Confidence: H)

2. **S1 — `SettingsViewModel.SettingsUiState.customApiKey` (line 142) is a plain `String` field on a `data class` that holds the user's Custom-Endpoint API key in memory for the lifetime of the VM, and `saveAndTestCustomEndpoint` (line 957) calls `customEndpointState.setEndpoint(url, key)` passing the same value through.** The state object is then collected by Compose via `collectAsStateWithLifecycle` and re-emitted on every keystroke into the API-key `OutlinedTextField` (used in `AiAndModelsSection.kt:113`). The key is therefore retained in `_state.value` (and in any preview snapshot) until the VM is cleared. Severity is amplified because `SettingsUiState.toString()` is auto-generated for the `data class` and will dump every field, including `customApiKey`, on a crash log or a `Log.d(_state)`-style debug print. The same risk applies to `smtpPassword` on line 150. Fix: store sensitive fields in a side `MutableStateFlow<String>` outside the data class, or wrap them in a `RedactedString` type that overrides `toString()`. (Confidence: H — verified on-disk via `od -c` that the type IS `String`; the `***` I initially saw in `read_file` output was a redaction filter, not a real token)

3. **S1 — Accessibility gap: 90 occurrences of `contentDescription = null` on `Icon` / `IconButton` across the UI tree.** This is fine when the icon is decorative and the parent control already labels itself. But the pattern is broken in several places, e.g. `MessageBubble.kt:626` puts `contentDescription = "Open citation $index"` on a clickable `Box` (good), but `MessageBubble.kt:670` declares `BubbleAction` with `contentDescription = label` where `label` may be `"Helpful"` / `"Not helpful"` / `"Copy"` / `"Share"` — these are imperative actions, not labels, so TalkBack announces them as static descriptors rather than actions. Similarly `TasksScreen.kt:99` `"Add task"` is the correct role but `TasksScreen.kt:421` `"Add reminder"` is on a child `Icon` inside a `TextButton` so the button's role overrides it; and `HistoryScreen.kt:454` `IconButton(onClick = onDelete, ...)` puts `"Delete"` on the child `Icon`, leaving the button unlabeled (TalkBack will fall back to the `onClick` lambda's name). (Confidence: H)

4. **S2 — `MessageBubble.AssistantMessage` (MessageBubble.kt:473) recomputes `renderCitationMarkers(text, citations.mapTo(mutableSetOf()) { it.index })` on every recomposition through `remember(text, citations, isStreaming)`.** This is keyed on the actual string + citation set, so it is technically cached. But the bigger recomposition bomb is the **outer `Row` modifier chain** (line 426-434) — it applies `graphicsLayer { translationY = ...; alpha = ... }` on every parent recomposition driven by `state.streaming`, `state.streamingThinking`, and `state.ttsState` reads from `ChatContent`. The `springEased` `Animatable` lives in a `remember { ... }` block (line 408) so the animation state is preserved, but the *lambda passed to `graphicsLayer`* is re-evaluated for **every** assistant message in the visible list on **every** streaming delta that touches the `lastTurn.assistant` field. With a 30-message conversation and a streaming model, this is ~30 `graphicsLayer` writes per token. Fix: hoist the per-message animation into a `Modifier.composed` or extract a stable `MessageAnim` data class. (Confidence: H)

5. **S3 — Six "shell" design-system components are dead code with no importers outside `ui/components/`.** `AuraLoadingState.kt` (34 LOC), `AuraSectionHeader.kt` (59 LOC), `AuraListRow.kt` (62 LOC), `AuraCards.kt` (42 LOC), `AuraButtons.kt` (61 LOC), `AuraChips.kt` (43 LOC) — total **301 LOC** of components that promise to be the shared design system but are not referenced by any screen, ViewModel, dialog, sheet, or preview. They duplicate functionality of `AuraScreenShell` / `AuraScreenHeader` (which IS used in `MemoryScreen.kt:163`, `TasksScreen.kt:110`, etc.). The fact that `AuraLoadingState` re-implements skeleton logic that the rest of the codebase rolls by hand in `HistorySkeletonLoading` (HistoryScreen.kt:486), `MemorySkeletonLoading` (MemoryScreen.kt:780+), and `TasksSkeletonLoading` (TasksScreen.kt:316) is a S2 maintainability smell — the team has two parallel skeleton systems. (Confidence: H, verified by repo-wide grep for import statements)

Other notable findings (full list below): hardcoded chart colors outside the token system, `LazyColumn` items without `key` in `ModelPickerSheet` / `AgentPickerSheet` / `ChatDialogs`, `ChatViewModel.init` registers a `NetworkCallback` but only unregisters it on `onCleared` — fine for Hilt-VM scoping, but a `_state` subscription is launched on `viewModelScope` that never cancels a `runCatching { textToSpeech.state.collect { ... } }` (line 462-468), and `init` launches 7+ `viewModelScope.launch` blocks unconditionally (lines 462, 470, 491, 492, 510, 520, 525, 532, 539) which is noisy but not broken because the VM is process-scoped. The clear top issue is the secret-leak risk on `SettingsUiState.customApiKey`.

---

## 2. Findings by category

### 2.1 State hoisting (S2/S3)

- **S2 — `ChatViewModel.runStartTimeMs` / `lastUserMessage` live on `ChatSendController` (ChatSendController.kt:108-127) but are not exposed.** `runStartTimeMs` is a private `Long` mutated on every `runSend` call. Two concurrent `runSend` calls (which can happen if the user double-taps the send button before `streaming = true` propagates) will overwrite each other's start time, so the duration footer shows the wrong value. Fix: store the start time on the per-run `Job` (e.g. `scope.launch { val start = System.currentTimeMillis(); ... }`) or guard the early return at line 148 with a more aggressive check than `current.streaming`. (Confidence: H)

- **S3 — `ChatRoute.kt:227-237` persists `draft` via `rememberSaveable` but ALSO syncs it back to the ViewModel's `setDraft` on first composition, creating a feedback loop on recomposition.** Specifically, `LaunchedEffect(state.draft) { savedDraft = state.draft }` runs on every `state.draft` change. The VM's `setDraft` is called on every keystroke via `ChatComposer.onDraftChange` (ChatComposer.kt ~line 370). With process death + restore, the user types, the VM updates state, the saveable updates, the VM gets `setDraft(savedDraft)` which is the same value, and `_state.update` is a no-op so no infinite loop — but the saveable persists an empty string between launches because `state.draft` starts blank, the effect sets `savedDraft = ""`, and only the subsequent `LaunchedEffect(Unit)` runs the restore. The order is racy. Fix: drive the saveable from a single source of truth (the VM) and remove the bidirectional sync. (Confidence: M)

- **S3 — `ChatUiState` has 30+ fields (ChatViewModel.kt:212-311), of which `streamingThinking` (line 216) is only meaningful during a stream.** This is a transient value that should live in the composable or a sub-state, not in the VM. It triggers `_state.update` (ChatSendController.kt:333) on every ThinkingDelta, causing a full re-render of any composable that reads `state`. If a header chip or toolbar reads `state` for `streaming` or `ttsEnabled`, it also re-renders. (Confidence: M)

### 2.2 Re-composition bombs (S2)

- **S2 — `ChatContent.kt:90-235` reads the entire `state` object inside a single `Column` chain.** Every banner (offline, deep mode, incognito, TTS, error, save warning) and the timeline all read `state.foo`, so any field mutation invalidates the whole subtree. The `state` is a `data class` with 30+ fields, and Compose's stability inference treats it as stable, so changes only to non-read fields *should* skip — but `state.error` and `state.streaming` change frequently and the `when` block at line 156-175 re-evaluates on every state change. Fix: split `ChatUiState` into `ChatHeaderState` / `ChatTimelineState` / `ChatBannersState` or wrap each banner in a `key(state.error, ...)` block to localize recomposition. (Confidence: M)

- **S2 — `parseMarkdown` in `MarkdownText.kt:183-212` splits text on every character of every line for every keystroke during streaming.** The `StreamingText` component (referenced in `MessageBubble.kt:488`) builds an `AnnotatedString` via this parser on each `text` update. For a 5,000-token assistant response, that's 5000 string splits + 6 regex searches per line. Mitigation: the file is parsed only when `isStreaming` becomes `false` (line 487 `if (isStreaming) StreamingText else MarkdownColumn`), so this is contained — but `StreamingText` itself is unread, so this needs confirmation. (Confidence: M)

- **S2 — `HistoryScreen.kt:88` creates a `SnackbarHostState` via `remember { ... }` (correct) but `HistoryScreen.kt:97` uses `LaunchedEffect(state.lastDeleted?.id) { ... }` to drive the snackbar.** This is correct Compose pattern, but the inner `showSnackbar` call (line 99) suspends the effect; if the user deletes a second conversation before the first snackbar dismisses, the second `LaunchedEffect` does not re-collect (the key only fires once per `lastDeleted?.id`). The second delete's snackbar will not show until the first resolves. (Confidence: L)

### 2.3 Memory leaks / lifecycle (S1/S2)

- **S1 — `ChatViewModel.init` (line 470-488) registers a `NetworkCallback` against the system `ConnectivityManager` inside `viewModelScope.launch` and stores the callback reference in `networkCallback`.** The callback is only unregistered in `onCleared` (line 565-571). For a Hilt-scoped ViewModel this is correct, but the `viewModelScope.launch` means the registration completes asynchronously — if the VM is cleared before the launch resumes, `networkCallback` is null and the callback is never registered (no leak) but the user briefly sees `isOnline = true` (the default) even when offline. Fix: register synchronously in `init` or use a dedicated `MutableStateFlow<Boolean>` initialised to `null` (tri-state: unknown/true/false) so the UI can show a "checking…" state. (Confidence: H)

- **S2 — `ChatRoute.kt:543, 564, 617` use `LaunchedEffect(Unit) { showX = false }` as a side-effect in conditional `else` branches to dismiss overlays when mic permission is revoked.** This works but is fragile: if the user grants permission, opens the overlay, then revokes it via system settings, the `if (showX && hasMicPermission)` block evaluates false and the else branch's `LaunchedEffect(Unit)` fires, closing the overlay. The `Unit` key means the effect only runs once per overlay-instance, so if `showX` is toggled to `true` again with permission still revoked, the effect does not re-fire and the overlay stays open in the conditional. Fix: model this as `LaunchedEffect(showX, hasMicPermission) { if (showX && !hasMicPermission) showX = false }`. (Confidence: H)

- **S2 — `ChatRoute.kt:572-579` runs an infinite `while (voiceCallMode) { delay(1000) ... }` inside `LaunchedEffect(voiceCallMode)`.** The effect cancels when `voiceCallMode` flips to false, but the timer continues to write `voiceCallDurationMs` from the captured `voiceCallStartTime` even if the system time changes (e.g. NTP sync). The duration display can jump backward. Fix: use `SystemClock.elapsedRealtime()` instead of `System.currentTimeMillis()`. (Confidence: M)

- **S2 — `ChatViewModel.kt:497-509` — `viewModelScope.launch { val recent = conversationStore.mostRecent(); if (isolatedSessionRequested) return@launch; ... }`** — the `isolatedSessionRequested` flag is `@Volatile` (line 374) but is read inside a launched coroutine. The race is: `startIsolatedSession()` sets `isolatedSessionRequested = true` then `cancel()` (which does not cancel the `viewModelScope.launch` from init). The init's coroutine checks the flag at line 499, but by then the conversation may have already been set to a default in the `else` branch (line 503) because the read of `mostRecent()` returned null. Fix: read the flag synchronously before launching, or use `MainScope()` for the init flow and `viewModelScope` only for the data fetches. (Confidence: M)

### 2.4 Accessibility gaps (S1/S2)

- **S1 — `MessageBubble.kt:512-561` makes the entire footer `Row` `clickable { showActions = !showActions }` (line 519) with `onClick` and `onLongClick` both setting `showActions`.** This Row contains the timestamp `Text` and a `Spacer(weight=1f)`. There is no `contentDescription` and no `Role.Button` on the Row. TalkBack will announce "12:34 PM" (the timestamp) and not indicate that it's tappable. Users with screen readers have no way to discover reactions, copy, or share. (Confidence: H)

- **S1 — `MessageBubble.kt:294` declares `var copied by remember { mutableStateOf(false) }` in `MessageBubble` (the top-level composable), but the variable is only used in the `AssistantMessage` branch (line 316).** The state is hoisted incorrectly — for the user branch (line 296-302), `copied` is initialised but never observed or changed. Each assistant message has its own copy because `MessageBubble` is called per-row, but for the user branch, every recomposition creates a new `remember` slot that does nothing. This is a memory waste, not a bug, but it's symptomatic of state hoisting in this file. (Confidence: L)

- **S1 — `TasksScreen.kt:97-100` — `FloatingActionButton(onClick = { showAdd = true }) { Icon(Icons.Filled.Add, contentDescription = "Add task") }` — this is the only `Icon` in the file with a `contentDescription`; the rest are decorative.** But the FAB itself is not given a `Modifier.semantics { role = Role.Button }` or a `stateDescription` for "Add task" — TalkBack will announce "Add task, button" which is correct, but the icon's contentDescription and the FAB's label will collide, causing some TalkBack versions to announce "Add task, Add task". Fix: remove the `contentDescription` from the inner `Icon` and rely on the FAB's slot to provide semantics, or set `Icon.contentDescription = null` and let the parent button take it. (Confidence: H)

- **S1 — `HistoryScreen.kt:454` — `IconButton(onClick = onDelete, modifier = Modifier.size(48.dp)) { Icon(Icons.Filled.Delete, "Delete", ...) }`** — the IconButton's `onClick` is wired but the Icon's `contentDescription = "Delete"` is the only label. Modern Material 3 recommends passing the description to the `IconButton` via `Modifier.semantics` or removing it from the Icon to avoid double-announcement. The current code works but is inconsistent with `AuraIconButton.kt:38` which explicitly removes contentDescription from the inner content when the parent provides it. (Confidence: M)

- **S2 — `MarkdownText.kt:442-448` — `ClickableText` (which is deprecated in Material 3) uses `onClick` to open URLs.** Deprecated APIs may lose accessibility features in future Compose releases. There is no `link` modifier or `onClickLink` callback, so URLs are exposed only via the click handler. For a user with switch access or keyboard navigation, there is no way to focus individual links — the entire `AnnotatedString` is one focusable node. Fix: use `Text` with `LinkAnnotation` + `LinkInteractionListener` (Material 3 1.2+). (Confidence: M)

- **S2 — `ChatRoute.kt:684-700` — the "Edit and resend" dialog uses a raw `OutlinedTextField` with no `label` and no `placeholder`.** TalkBack will announce "Outlined text field" with no hint about the content. Fix: add `label = { Text("Message") }` or `placeholder = { Text("Edit message") }`. (Confidence: H)

- **S2 — `HomeContent.kt:165-172` — the search button on the home screen uses `contentDescription = "Search"` but no `stateDescription` to indicate that a search sheet will open.** Minor; not a blocker. (Confidence: L)

- **S3 — Many `Icon` calls use `contentDescription = null` correctly for decorative icons, but 4 cases (MessageBubble.kt:421, 426, 433, 436) are inside an `IconButton` / `Row` with a `clickable` modifier, so `null` is the right call. No fix needed.** (Confidence: H)

### 2.5 Dead UI / unused components (S3)

- **S3 — Confirmed unused: `AuraLoadingState.kt`, `AuraSectionHeader.kt`, `AuraListRow.kt`, `AuraCards.kt`, `AuraButtons.kt`, `AuraChips.kt`.** No screen, dialog, sheet, ViewModel, or preview outside `ui/components/` references any of these. Total: **301 LOC** of code that promises a shared design system but is not used. Recommendation: either delete, or wire them into the screens that currently roll their own equivalents (`MemoryScreen.kt:780+`, `TasksScreen.kt:316+`, `HistoryScreen.kt:486+` all have private `*SkeletonLoading` functions; `MemoryScreen.kt:272-395` builds a custom button stack). (Confidence: H)

- **S3 — `AgentChip.kt` (mentioned in the file list) is used in `ChatContent.kt:196` but `EmptyChatState.kt:169` has a `LazyRow` that re-implements chip rendering inline.** This is a duplication smell, not dead code. (Confidence: M)

- **S3 — `MemoryRecallChip.kt:128` has a `// Single LazyColumn — nesting scrollable LazyColumns inside a Column` comment that explains a workaround.** The note is correct, but the file is one of three places (`MemoryRecallChip`, `ModelPickerSheet.kt:303`, `AgentPickerSheet.kt:66`) that hand-rolls this same pattern. (Confidence: L)

- **S3 — `SettingsConfigController.kt` is referenced by name in the file list but I have not loaded it.** If it's wired up it should appear in `SettingsViewModel`; if not, it's dead. (Confidence: L — unverified)

### 2.6 Design token violations (S2/S3)

- **S2 — `BarChartView.kt:34-39`, `LineChartView.kt:96-98`, `PieChartView.kt:32-38` all hardcode the chart palette as `Color(0xFF2DD4BF)`, `Color(0xFF60A5FA)`, `Color(0xFFF59E0B)`, `Color(0xFFEF4444)`, `Color(0xFF8B5CF6)`, `Color(0xFF10A981)`.** These are not in `AuraTokens.kt` or `AuraSemanticColors.kt`, so light/dark theme changes will not update the charts. Fix: move the palette to a new `AuraChartColors` block in `AuraTokens.kt` and read via `LocalAuraSemanticColors` (or a new composition local). (Confidence: H)

- **S2 — `AgentPresence.kt:150-155` hardcodes `Color(0xFF0F766E)`, `Color(0xFF2DD4BF)`, `Color(0xFF3B82F6)`, `Color(0xFFF59E0B)`, `Color(0xFFEC4899)`, `Color(0xFF06B6D4)`.** Six hex values used as a palette that has no counterpart in the design tokens. (Confidence: H)

- **S2 — `VoiceCallScreen.kt:189` uses `containerColor = Color(0xFFEF4444)` for the end-call button.** The semantic error color is `AuraSemanticColors.error = Color(0xFFF87171)` in dark mode and `Color(0xFFB91C1C)` in light mode. Hardcoding `0xFFEF4444` defeats dark/light switching. (Confidence: H)

- **S2 — `AuraTokens.kt` and `AuraSemanticColors.kt` are TWO parallel token systems.** `AuraTokens.kt` defines `Dark` and `Light` objects with fields like `bgBase`, `surface0-4`, `textPrimary`, `accentPurple`. `AuraSemanticColors.kt` defines `DarkAuraSemanticColors` and `LightAuraSemanticColors` as data classes with overlapping but DIFFERENT values: `AuraTokens.Dark.surface1 = 0xFF121214` vs `DarkAuraSemanticColors.surface1 = 0xFF121214` (coincidentally same), but `AuraTokens.Dark.textPrimary = 0xFFEDEDED` vs `DarkAuraSemanticColors.textPrimary = 0xFFF4F4F5`. So a screen that mixes `AuraThemeTokens.colors` (semantic) with `AuraTokens.Dark.xxx` (raw) will render inconsistent colors. Recommendation: pick one token system and migrate the other into it. The KDoc at `AuraTokens.kt:5-17` says it's for web parity, so keep the raw palette there and use it as the source of truth for `AuraSemanticColors`. (Confidence: H)

- **S3 — `MessageBubble.kt:362` — `RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp, bottomStart = 4.dp, bottomEnd = 24.dp)`** uses raw `dp` values instead of the `AuraSpacing` / `AuraDimensions` tokens. The shape library (`Shapes.kt`) defines three named shapes but they're not used here. (Confidence: M)

- **S3 — `MessageBubble.kt:340-341` — `dampingRatio = 0.65f, stiffness = Spring.StiffnessMediumLow`** — these are motion tokens that should live in a `AuraMotion` object. Currently inline. (Confidence: L)

### 2.7 Loading / error states (S2)

- **S2 — `MemoryScreen.kt:399` — `if (state.loading) { MemorySkeletonLoading() }` else { … } — the skeleton is shown only on full load, not during search updates or category filter changes.** Typing in the search bar will see a hard transition (current list disappears, new list appears after the Room query returns) because the VM does not set `loading = true` for refilters. (Confidence: M)

- **S2 — `ChatContent.kt:156-175` — the timeline branch does not show a skeleton during `state.conversationLoading`.** A blank column appears during conversation restore. (Confidence: H)

- **S2 — `TasksScreen.kt:174-176` — `if (state.loading) TasksSkeletonLoading() else { … }` — same pattern, but `TasksViewModel` also does not differentiate between "first load" and "filter change".** (Confidence: M)

- **S2 — `HomeContent.kt:175-195` does differentiate via `HomeLoadState`, which is good. The skeleton shown there is `AuraSkeleton(height = 176.dp) × 3` — three generic bars that do not mirror the actual card heights. A better skeleton would match the brief card and action grid exactly.** (Confidence: M)

- **S1 — `SettingsViewModel.saveAndTestCustomEndpoint` (line 957-985) sets `customResult` to either `"✓ Verified — N models"` or `"✗ $message"` but does not expose `customResult` failures to the user via a snackbar or banner.** The `customResult` field is rendered by `AiAndModelsSection.kt:113+` (per the grep) but if the user navigates away and back, the result stays on screen. There is no error-vs-success color differentiation in the rendering (need to verify in AiAndModelsSection). (Confidence: M)

- **S1 — `ChatSendController.kt:243-248` — when `toolExecutor.execute("delegate_to_agent", ...)` returns `NeedsPermission` or `NeedsApproval`, the code throws `IllegalStateException`.** This is then caught at line 261-263 and `setErrorWithAutoDismiss("Delegation failed: ${e.message}", false, null)` is called. But `NeedsPermission` should surface a permission dialog, not an error. The error message "Delegation failed: Approval needed: <rationale>" is technically correct but the user has no way to approve it. (Confidence: H)

### 2.8 Material 3 / semantics (S3)

- **S3 — `MarkdownText.kt:438` — `ClickableText` is deprecated in Material 3 1.2+.** The recommended replacement is `Text` with `LinkAnnotation.Url` and `LinkInteractionListener`. (Confidence: H)

- **S3 — `ChatRoute.kt:658-674` — the "Clear chat?" dialog uses raw `androidx.compose.material3.AlertDialog` instead of a themed wrapper.** The rest of the app uses `DeleteConversationDialog` (line 648) for the same flow but with different copy. Two dialog patterns for the same concept. (Confidence: M)

- **S3 — `TasksScreen.kt:262-276` — the "Clear completed tasks" dialog passes `color = AuraThemeTokens.colors.error` to the `Text` inside `TextButton`.** Material 3 discourages coloring button text directly; use `TextButton` with a `colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)` instead. (Confidence: M)

- **S3 — `MessageBubble.kt:189` — `modifier = Modifier.size(size * 0.5f)` where `size: Dp = 36.dp` is hardcoded.** The avatar size should be in `AuraDimensions`. (Confidence: L)

- **S3 — `ChatTimeline.kt:61` — the chat timeline uses `key = { index, turn -> "${turn.timestamp}-$index" }`.** This is stable but uses string interpolation on every item — for a 5000-message conversation, allocating 5000 strings per recomposition. Use `key = { _, turn -> turn.timestamp }` instead, since timestamp is `Long` and unique. (Confidence: M)

### 2.9 Theme inconsistencies (S2/S3)

- **S2 — `MessageBubble.kt:189` — `tint = if (isProactive) { AuraThemeTokens.colors.assistantAccent } else { AuraThemeTokens.colors.textPrimary }`** uses `textPrimary` (near-white) as the icon tint. In dark mode this is fine, in light mode (which has `textPrimary = 0xFF18181B`, near-black) it would invert. The bubble itself is `userBubble` which IS different per theme, but the icon doesn't follow. (Confidence: M)

- **S3 — `ChatRoute.kt:312` — `uri?.let { val bmp = decodeSharedImage(context, it); bmp?.let(viewModel::onImageCaptured) }` — the image picker result is processed on the main thread.** `decodeSharedImage` does `BitmapFactory.decodeStream` which is I/O. Should be `withContext(Dispatchers.IO)`. (Confidence: M)

### 2.10 Performance (S2)

- **S2 — `MessageBubble.kt:473` — `val renderedText = remember(text, citations, isStreaming) { ... }` is correct but `renderCitationMarkers` calls `codeBlockRegex.findAll` to skip code blocks.** For a 10,000-character assistant message with 3 code blocks, this is 3 regex finds + 1 transform. Acceptable, but `renderCitationMarkers` is not memoised across turns — if the same `text` is rendered again (e.g. on theme change), it re-runs. (Confidence: L)

- **S2 — `ChatContent.kt:152-154` — the TTS stop pill (`if (state.ttsState is Speaking)`) re-evaluates the `is` check on every recomposition.** Compose's smart-cast should handle this, but the surrounding Column doesn't read `ttsState`, so the conditional is fine. (Confidence: L — not a real issue)

- **S2 — `HistoryScreen.kt:270` — `items(state.conversations, key = { it.id })` is correct (keyed by `String`).** But `state.conversations` is the full list; if the VM is filtering in-memory, the list mutates and Compose recomposes all visible items. For 1000 conversations, the filter is O(n) on every keystroke. Consider `LazyColumn` with `state.conversations` filtered at the Room level. (Confidence: M)

- **S2 — `ChatViewModel.init` launches 9+ `viewModelScope.launch` blocks unconditionally.** Each is a coroutine that allocates a continuation. For a long-lived process (Hilt VM is process-scoped), this is one-time cost, but the cumulative allocations during chat cold-start may exceed 100KB. (Confidence: L)

### 2.11 Lifecycle bugs (S1/S2)

- **S1 — `ChatViewModel.init` (line 470-488) registers `NetworkCallback` inside a `viewModelScope.launch`.** If the launch is still pending when `onCleared` runs, `networkCallback` is `null` and the callback is never registered (no leak, but the user never sees `isOnline = false` even when offline). Fix: register synchronously in `init` and store the callback in the same line, so `onCleared` always has a non-null handle. (Confidence: H)

- **S2 — `ChatViewModel.onCleared` (line 563-573) calls `textToSpeech.shutdown()`.** This is correct, but the VM is a Hilt-scoped singleton only if no other consumer is holding it; for the chat feature, the VM is created via `hiltViewModel()` in `ChatRoute.kt:165`, which scopes it to the NavBackStackEntry. When the user navigates away, the entry is destroyed and `onCleared` runs. But the `viewModelScope` is cancelled before `onCleared` runs in some Compose versions — the order is not guaranteed. Fix: do not rely on `onCleared` for TTS shutdown; do it in the activity's `onStop` instead. (Confidence: M)

- **S2 — `ChatViewModel.startIsolatedSession` (line 739-771) calls `cancel()` before `_state.update` but `cancel()` is on `ChatSendController`, not on `ChatViewModel`.** If a `runJob` is in flight on the same VM, `cancel()` cancels it. But `isolatedSessionRequested = true` (line 744) does not prevent the `init`'s `viewModelScope.launch` (line 497) from overwriting the conversation. The race is: `startIsolatedSession` sets the flag, then a millisecond later the init coroutine reads `mostRecent()` (non-null because the user just had a conversation) and writes it to `_state`. The isolated session is lost. Fix: check the flag synchronously in `init` before launching the coroutine. (Confidence: H)

### 2.12 Click handlers with no implementation (S2)

- **S2 — `ChatRoute.kt:251-262` — `var showModelPicker by remember { mutableStateOf(false) }` and similar flags for `showAgentPicker`, `showSources`, `showVoiceOverlay`, `showHoldToTalk`, `showContinuousVoice`, `voiceCallMode`, `voiceMuted`, `voiceCallDurationMs`, `voiceCallStartTime`.** All of these are wired to actual dialogs/sheets, so no dead handlers. (Confidence: H)

- **S2 — `HomeContent.kt:127-131` — `onOpenEvolution: () -> Unit = {}`** has a default of no-op. The `homeResolvedItems` helper at line 392 accepts `onOpenEvolution: () -> Unit = {}` and forwards it to `HomeSecondaryActions` (line 494). Tracing through `HomeSecondaryActions.kt` (not read in this audit) likely has the same no-op. If the Home screen has an Evolution card but no handler wired in `HomeRoute.kt`, tapping it is a silent no-op. (Confidence: M)

### 2.13 Other / unverified (S3)

- **S3 — `SettingsScreen.kt:198` — `val coroutineScope = androidx.compose.runtime.rememberCoroutineScope()` — used to launch share/import side-effects.** This is the correct pattern. (Confidence: H)

- **S3 — `ChatViewModel.kt:962-984` — `setErrorWithAutoDismiss` uses `viewModelScope.launch { delay(5_000); ... }`.** If the user sends a new message that succeeds within 5 seconds, the error is auto-dismissed even though the success did not depend on it. Minor; the check at line 979 (`if (_state.value.error == friendly)`) prevents this. (Confidence: M)

- **S3 — `ChatViewModel.kt:649` — `makeActiveModelDefault` calls `userPreferences.setDefaultModel(model)` without error handling.** If the DataStore write fails (rare but possible), the user gets no feedback. (Confidence: L)

---

## 3. Suggested remediation priority

| Priority | Item | Effort | Impact |
|---|---|---|---|
| **P0** | Sanitise / never include `customApiKey` in `SettingsUiState` data class; use a `MutableStateFlow<String>` outside the serializable state (SettingsViewModel.kt:142, 957) | 1 day | High — secret-leak blocker |
| **P0** | Add `String label` param to `MessageBubble` footer Row and a `contentDescription` to its clickable Row (MessageBubble.kt:512) | 1 hr | High — a11y blocker |
| **P0** | Move `customResult` (and any other password-ish field) out of the auto-`toString()` data class | 0.5 day | High — log/crash safety |
| **P1** | `ChatSendController.runStartTimeMs` race / re-entrancy guard (line 108, 144) | 0.5 day | Medium — wrong duration footer |
| **P1** | Delete or wire the 6 unused design-system shell components (AuraLoadingState, AuraSectionHeader, AuraListRow, AuraCards, AuraButtons, AuraChips) | 1 day | Medium — maintainability |
| **P1** | Unify the two parallel token systems (AuraTokens.kt vs AuraSemanticColors.kt) | 2-3 days | Medium — visual inconsistency |
| **P2** | Fix the `startIsolatedSession` race against `init` (ChatViewModel.kt:497, 744) | 0.5 day | Medium — silent session loss |
| **P2** | Replace `ClickableText` with `Text` + `LinkAnnotation` (MarkdownText.kt:438) | 1 day | Medium — future-proofing |
| **P2** | Move chart palette to `AuraChartColors` and read via composition local (BarChartView, LineChartView, PieChartView, AgentPresence) | 0.5 day | Medium — dark/light theme consistency |
| **P3** | Decompose `ChatUiState` (30+ fields) into per-screen sub-states | 1-2 days | Low — perf polish |
| **P3** | Replace inline skeleton implementations with `AuraLoadingState` (or delete it) | 1 day | Low — DRY |

---

## 4. Appendix — files reviewed

**ViewModels (full read):**
- `app/src/main/kotlin/com/aura/ui/viewmodel/ChatViewModel.kt` (1077 LOC)
- `app/src/main/kotlin/com/aura/ui/viewmodel/ChatSendController.kt` (555 LOC)

**Screens (full read):**
- `app/src/main/kotlin/com/aura/ui/screens/chat/ChatRoute.kt` (827 LOC)
- `app/src/main/kotlin/com/aura/ui/screens/chat/ChatContent.kt` (461 LOC)
- `app/src/main/kotlin/com/aura/ui/screens/MemoryScreen.kt` (1096 LOC, lines 1-500 reviewed)
- `app/src/main/kotlin/com/aura/ui/screens/TasksScreen.kt` (867 LOC, lines 1-500 reviewed)
- `app/src/main/kotlin/com/aura/ui/screens/HistoryScreen.kt` (637 LOC, lines 1-500 reviewed)
- `app/src/main/kotlin/com/aura/ui/screens/home/HomeContent.kt` (762 LOC, lines 1-500 reviewed)

**Components (full read):**
- `app/src/main/kotlin/com/aura/ui/components/MessageBubble.kt` (751 LOC)
- `app/src/main/kotlin/com/aura/ui/components/MarkdownText.kt` (762 LOC, lines 1-500 reviewed)
- `app/src/main/kotlin/com/aura/ui/components/AuraScreenShell.kt` (92 LOC)
- `app/src/main/kotlin/com/aura/ui/components/AuraIconButton.kt` (56 LOC)
- `app/src/main/kotlin/com/aura/ui/components/AuraLoadingState.kt` (34 LOC — confirmed dead)
- `app/src/main/kotlin/com/aura/ui/components/AuraSectionHeader.kt` (59 LOC — confirmed dead)

**Theme (full read):**
- `app/src/main/kotlin/com/aura/ui/theme/AuraTokens.kt` (143 LOC)
- `app/src/main/kotlin/com/aura/ui/theme/AuraSemanticColors.kt` (122 LOC)

**Settings (partial read):**
- `app/src/main/kotlin/com/aura/ui/settings/SettingsViewModel.kt` (1135 LOC, lines 1-200 + key sections)

**Targeted searches across `app/src/main/kotlin/com/aura/ui`:**
- `contentDescription = null` — 90 occurrences
- `Color(0xFF...)` outside `theme/` — ~25 occurrences (chart palette, voice call button)
- `viewModelScope.launch` / `rememberCoroutineScope` — ~14 occurrences in screens
- `LazyColumn` / `LazyRow` — 25+ usages, mostly with `key = { it.id }` (good)
- `customApiKey` — 5 references in `settings/`, confirmed `String` type

**Not reviewed (would deepen the audit):**
- `ChatTimeline.kt` (key=verified stable; recomposition read deferred)
- `ChatComposer.kt` (peripheral, not in scope)
- `ChatConversationController.kt` / `ChatMediaController.kt` / `ChatInteractionController.kt` (extracted from ChatViewModel)
- `SettingsScreen.kt` (the screen wrapper, not the VM)
- All `settings/sections/*.kt` files
- `voice/VoiceViewModel.kt` and friends
- `evolution/*.kt`
- `onboarding/OnboardingRoute.kt`
- `home/HomeRoute.kt`, `HomeViewModel.kt`
