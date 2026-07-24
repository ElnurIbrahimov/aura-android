# Plan: Fix permission-request resume in agentic loop (A1 audit P0)

## Problem
A1 audit finding (P0): `AgentEvent.PermissionGranted` is dead code. When a tool
returns `ToolResult.NeedsPermission`, the loop currently:
1. Appends `"Permission needed: X — rationale"` to the conversation
2. Continues to the next model step
3. The model has no real tool result to act on — the next response is
   ungrounded, and the held tool never re-runs even when the user
   grants the permission.

Result: every permission request (CalendarRead, CalendarWrite,
NotificationList, SmsSend when added) is a dead end. The user taps
"Allow" and nothing happens.

## Fix shape (one atomic commit)

### 1. New event: `PermissionRequested`
- Replaces the misleading `PermissionGranted` (which suggested the UI
  emits it after granting — wrong direction; the loop emits it to
  request).
- Fields: `toolName, toolCallId, args, permission, rationale`.
- Emitted when the loop decides to pause for a permission grant.

### 2. Instance state on the loop
- `@Volatile private var pendingPermission: PendingPermission? = null`
- `PendingPermission(toolName, toolCallId, args, permission, rationale,
  conversation, model, maxSteps, options, recallLimit, specialist,
  memoryEnabled, approvedRemoteCostTools, agentId, runId, step)`.
- Stores everything needed to continue the run after a resume.

### 3. Pause behavior in the tool-result loop
- When any `toolResult` is `ToolResult.NeedsPermission`:
  - `pendingPermission = PendingPermission(...)` with the full snapshot
  - Emit `AgentEvent.PermissionRequested(...)`
  - Set `finished = true; break` (exits the `for (step in 1..maxSteps)`
    loop cleanly)
  - The `run()` flow then returns via the `done = true` path → emits
    `Result(conversation, recall)` and `Done`.

### 4. New public function: `resumeAfterPermission()`
- Returns `Flow<AgentEvent>`.
- If `pendingPermission == null`, emits a single `Error("no_pending",
  "no tool waiting on permission", retryable = false)` and Done.
- Otherwise:
  - Captures the snapshot, clears `pendingPermission`.
  - Re-executes the held tool via `toolExecutor.execute(name, args, ctx)`
    with a fresh `ToolContext` derived from the snapshot.
  - Emits `ToolExecuting`, then `ToolResult` for the held call.
  - Re-enters the full run loop starting at `step = heldStep + 1`,
    using the held conversation (with the new tool result appended).
- Reuses the same per-step body via a private helper that the original
  `run()` also calls (so the resume is a real continuation, not a
  copy-paste).

### 5. UI hook in `ChatSendController` + `ChatViewModel`
- `ChatUiState` gains `pendingPermissionRequest: PermissionRequestUi?`
  (nullable, null = no pending request).
- `ChatSendController` collects loop events; on `PermissionRequested`:
  - Populate `pendingPermissionRequest` (toolName, permission, rationale).
  - **Do not** auto-call `runSend` — wait for the user.
- New `ChatViewModel.grantPermission()` function:
  - Reads the held request, calls
    `agenticLoop.resumeAfterPermission().collect { ... }` (using the
    same send pipeline shape as `runSend`).
  - On `ToolResult` → existing `onSaveConversation` + taste signals.
  - On `Result` / `Done` → clear `pendingPermissionRequest`.
- New `ChatViewModel.denyPermission()`:
  - Clears `pendingPermissionRequest`.
  - Inserts a user-visible message: "Permission denied for {tool}".
  - The next user turn continues the conversation normally.

### 6. Regression test (`MemoryAugmentedAgenticLoopPermissionTest`)
- Mocks `Brain`, `ToolRegistry`, `ToolExecutor`, `MemoryStore`,
  `ConversationCompactor`.
- Test 1: tool returns `NeedsPermission` → loop emits
  `PermissionRequested`, then `Result`, then `Done`; `pendingPermission`
  is set; `run()` does NOT continue to step 2.
- Test 2: after pause, call `resumeAfterPermission()` → tool runs
  successfully → loop emits `ToolExecuting`, `ToolResult`, then
  continues the agentic loop (step heldStep + 1).
- Test 3: call `resumeAfterPermission()` with no pending → emits
  `Error("no_pending", ...)` and Done.
- Test 4: user denies permission (we don't need a new API for deny —
  `denyPermission()` just clears the field). Verify `pendingPermission`
  is null after deny.

## Files touched
- `aura-core/src/main/kotlin/com/aura/agent/MemoryAugmentedAgenticLoop.kt`
  — new event, instance state, pause logic, `resumeAfterPermission()`,
  refactor the per-step body into a private helper.
- `aura-core/src/test/kotlin/com/aura/agent/MemoryAugmentedAgenticLoopPermissionTest.kt`
  — new file, 4 tests.
- `app/src/main/kotlin/com/aura/ui/viewmodel/ChatSendController.kt`
  — capture `PermissionRequested` event, populate
  `pendingPermissionRequest`.
- `app/src/main/kotlin/com/aura/ui/viewmodel/ChatViewModel.kt`
  — new `grantPermission()` / `denyPermission()` functions.
- `app/src/main/kotlin/com/aura/ui/state/ChatUiState.kt`
  — new `pendingPermissionRequest` field.
- (optional) `app/src/main/kotlin/com/aura/ui/screens/chat/ChatContent.kt`
  — render the permission dialog with Allow / Deny buttons.

## Verification
- `./gradlew :aura-core:testDebugUnitTest --tests "*PermissionTest*"`
  — 4 new tests must pass.
- `./gradlew :aura-core:testDebugUnitTest :app:testDebugUnitTest` —
  full suite, no regressions.
- `./gradlew :app:assembleDebug` — build clean.
- Manual: open the app, ask Aura to read calendar events, see the
  permission dialog, tap Allow, see the event list.

## Out of scope (separate fixes)
- `A2` (MCP allowlist divergence) — separate commit.
- `A3 + A4` (DelegateToAgentTool inner context) — separate commit.
- PROVIDERS A1+A2 (Anthropic streaming) — separate commit.
- PROVIDERS C1 (OkHttp redirects) — separate commit.
- MEMORY A1 (MemoryBackup scope) — separate commit.
