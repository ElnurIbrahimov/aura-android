# Test Gap & Stale Test Analysis — Aura Android

**Branch:** feat/tier-1-friction  
**Date:** 2026-07-22  
**Test Baseline:** 1,115 tests, 0 failures  
**Repo:** D:\aura-android-clean (657 .kt files, ~60K LOC)

---

## 1. New Code Without Test Coverage

### 1.1 `ErrorMessageMapper.kt` — ❌ NO TESTS AT ALL
- **File:** `app/.../components/ErrorMessageMapper.kt` (lines 1–29)
- **Function:** `friendlyErrorMessage(raw: String): String`
- **What's missing:** The entire file is a pure function with 8 `when` branches (429, 401, 403, 500/502/503, missing_api_key, not_configured, tool_timeout, timeout, network) — zero tests.
- **Why it matters:** This is used in production by `ChatViewModel.setErrorWithAutoDismiss()` (line 909) and `ChatSendController` (line 367). A regression here shows raw HTTP codes to users.
- **Recommendation:** Add `ErrorMessageMapperTest.kt` covering all 8 branches + the else fallback + edge cases (e.g., "HTTP 429", "Error 429 - Too Many Requests").

### 1.2 `ChatSendController.kt` — ❌ NO DEDICATED TEST FILE
- **File:** `app/.../viewmodel/ChatSendController.kt` (391 lines)
- **What's missing:** The send pipeline handles response duration tracking, TTS integration, MoA escalation, specialist overrides, in-flight tool call tracking, error handling — no dedicated unit tests.
- **Why it matters:** This is the core send pipeline. `ChatViewModelTest` tests the VM method wrappers but not the controller's internal logic (duration tracking, `correctionPatterns`, `shouldEscalate`, `consecutiveFailures`).
- **Recommendation:** Extract `ChatSendControllerTest.kt` testing:
  - `runStartTimeMs` / `lastRunDurationMs` tracking → `onRunComplete` callback
  - `correctionPatterns` matching on various user correction messages
  - `shouldEscalate` triggers at `consecutiveFailures >= 3`
  - TTS `speak()` called on `AgentEvent.Done` when `ttsEnabled`

### 1.3 `ToolPolicyDefaults.kt` — ❌ NO TESTS
- **File:** `aura-core/.../policy/ToolPolicyDefaults.kt` (48 lines)
- **What's missing:** `forTool()` maps 5 `ToolRisk` values to `ToolPolicy` with specific confirmation levels. Zero tests.
- **Why it matters:** This was the source of regression #1 in commit 4f40e406 — WRITE_LOCAL/WRITE_REMOTE/PRIVACY had IMPLICIT confirmation that broke 28 write tools. A unit test would have caught it.
- **Recommendation:** `ToolPolicyDefaultsTest` verifying:
  - `DESTRUCTIVE` → `ConfirmationLevel.EXPLICIT`
  - `WRITE_LOCAL`, `WRITE_REMOTE`, `PRIVACY` → `ConfirmationLevel.NONE`
  - `READ_ONLY`, `REMOTE_COST` → `ConfirmationLevel.NONE`

### 1.4 `AgentRunsViewModel.kt` — ❌ NO TEST FILE
- **File:** `app/.../viewmodel/AgentRunsViewModel.kt` (129 lines)
- **What's missing:** The `approve()` method had a critical bug (commit 109649c4) — it flipped approval status before looking it up from `pendingApprovals`. No test for this ViewModel exists.
- **Why it matters:** This VM manages the entire durable agent-run lifecycle. The approval retry fix was a one-liner that should have had a regression test.
- **Recommendation:** `AgentRunsViewModelTest` testing:
  - `approve()` looks up before flipping (regression guard)
  - `approve()` calls `resetStep()` and `enqueue()`
  - `deny()`, `cancel()`, `resume()` state transitions

---

## 2. Tests That Test the Wrong Thing (Shallow Assertions)

### 2.1 `TtsTest.kt` — Entire File Is a Placeholder
- **File:** `app/.../viewmodel/TtsTest.kt` (37 lines)
- **Problem:** Three tests only validate `ChatUiState.copy(ttsEnabled = ...)` — they test Kotlin's data class `copy()` function, not any application logic. The `makeVm()` helper returns `null` and is unused (line 37: `Placeholder; we test state transitions only`).
- **Why it matters:** This file is counted in the 1,115 tests but adds zero coverage. It provides false confidence about TTS testing.
- **Recommendation:** Replace with tests for `ChatViewModel.stopTts()`, `ttsState` mirroring, and `textToSpeech.stop()` delegation.

### 2.2 `ConversationStoreTest.delete delegates to DAO` (line 72–77)
- **File:** `aura-core/.../ConversationStoreTest.kt`
- **Problem:** Only verifies that `dao.softDelete("id1", any())` was called. Doesn't verify that `any()` is a reasonable timestamp (not 0, not negative).
- **Why it matters:** A null/zero `deletedAt` would fail the `WHERE deletedAt IS NULL` filter, making the conversation invisible AND un-purgeable.
- **Recommendation:** Capture the timestamp and assert it's close to `System.currentTimeMillis()`.

### 2.3 `PolicyEngineTest.kt` — Only Tests 3 of 5 Risk Levels
- **File:** `aura-core/.../policy/PolicyEngineTest.kt`
- **Problem:** Tests `READ_ONLY`, `WRITE_LOCAL`, `REMOTE_COST` — but not `WRITE_REMOTE`, `PRIVACY`, or `DESTRUCTIVE` risk levels.
- **Why it matters:** The `ToolPolicyDefaults.forTool()` behavior for `DESTRUCTIVE` (EXPLICIT confirmation) goes untested. A regression here means destructive tools could run without confirmation.
- **Recommendation:** Add tests for `DESTRUCTIVE` → `NeedsConfirmation(EXPLICIT)` and `PRIVACY` → `Allowed` (when `memoryEnabled=true`).

---

## 3. Soft-Delete Migration (e21d6d55) — Test Gaps

### 3.1 ❌ `restore()` Has No Test
- **Source:** `ConversationStore.kt` line 85–88
- **Current test coverage:** `ConversationStoreTest` has no test for `restore()`.
- **Why it matters:** If `restore()` fails (e.g., returns null or doesn't clear the tombstone), the "Undo" snackbar can't recover deleted conversations.
- **Recommended test:** `restore clears the deletedAt tombstone and returns the conversation`

### 3.2 ❌ `purgeDeletedOlderThan()` Has No Test
- **Source:** `ConversationStore.kt` line 95–98
- **Current test coverage:** `ConversationStoreTest` has no test for `purgeDeletedOlderThan()`.
- **Why it matters:** The 7-day retention sweep is the belt-and-suspenders cleanup. If retentionMs is wrong (e.g., 0 or negative), the purge could delete everything or nothing.
- **Recommended test:** `purgeDeletedOlderThan calls dao.purgeDeletedBefore with correct cutoff` + edge case for `retentionMs = 0`.

### 3.3 ❌ `recentVisible` / `searchVisible` DAO Queries Not Tested
- **Source:** `ConversationDao.kt` lines 29–30, 63–73
- **Current test coverage:** `ConversationStoreTest.recent` calls `dao.recentVisible()` but doesn't verify that soft-deleted rows are actually excluded from results.
- **Why it matters:** If `recentVisible` accidentally returns tombstones, the History screen shows deleted conversations.
- **Recommended test:** Mock `recentVisible` to return only visible rows, `recent` to include tombstones — verify `ConversationStore.recent()` filters correctly.

### 3.4 ❌ `ProactiveBootstrap.purgeDeletedOlderThan()` Call Not Verified
- **Source:** `ProactiveBootstrap.kt` line 73–75
- **Current test coverage:** `ProactiveBootstrapTest` has 9 tests but none verify that `conversationStore.purgeDeletedOlderThan()` is called during `start()`.
- **Why it matters:** The 7-day purge on startup is a critical recovery path. If it stops being called, tombstones accumulate forever.
- **Recommended test:** Mock `conversationStore` and verify `purgeDeletedOlderThan()` is called at least once during `start()`.

### 3.5 ❌ `HistoryViewModel.restoreLastDeleted()` Has No Test
- **Source:** `HistoryViewModel.kt` lines 192–212
- **Current test coverage:** `HistoryViewModelTest` tests `delete` but not `restoreLastDeleted()`.
- **Why it matters:** This is the Undo snackbar handler. If it's broken, the user can't undo a deletion.
- **Recommended test:** `restoreLastDeleted calls store.restore with the lastDeleted hint id and clears lastDeleted`

### 3.6 ❌ `MIGRATION_5_6` Not Tested
- **Source:** `ConversationModule.kt` lines 60–65
- **Current test coverage:** No migration test for 5→6 adding the `deletedAt` column.
- **Why it matters:** Without a migration test, an invalid SQL statement or column type mismatch could silently break on upgrade.
- **Recommended test:** Automatic migration test from schema 5 to 6 verifying `deletedAt` column exists and is nullable.

### 3.7 ⚠️ `delete delegates to DAO` Doesn't Verify `purgeDeletedOlderThan` Side Effect
- **Source:** `HistoryViewModel.kt` line 182
- **Current test:** `HistoryViewModelTest.delete calls store_delete then reloads` (line 113–120) only verifies `store.delete()` and `store.recentPinnedFirst()`. Doesn't verify `store.purgeDeletedOlderThan()` is called.
- **Recommendation:** Strengthen the existing test.

---

## 4. Daily-Use UX (b7f42bd4, 07aa77a4, 2da2e868) — Test Gaps

### 4.1 ❌ `exportConversation()` Has No Test
- **Source:** `ChatViewModel.kt` lines 693–713
- **Current test coverage:** `HistoryViewModelTest` tests `exportMarkdown()` but `ChatViewModel.exportConversation()` is untested.
- **Why it matters:** Export is a user-facing feature with "## User" / "## Aura" sections. A regression could produce malformed markdown.

### 4.2 ❌ `clearConversation()` Has No Test
- **Source:** `ChatViewModel.kt` lines 674–691
- **Current test coverage:** `ChatViewModelTest` has `newConversation` but not `clearConversation`.
- **Why it matters:** Clear is a destructive action (keeps conversation but removes turns). If it fails, user loses turns but conversation persists with stale data.

### 4.3 ❌ `editAndResend()` Has No Test
- **Source:** `ChatViewModel.kt` lines 715–731
- **Current test coverage:** None.
- **Why it matters:** This truncates the conversation and re-sends — a complex operation with edge cases (out-of-bounds index, streaming guard).

### 4.4 ❌ `lastResponseDurationMs` Tracking Has No Test
- **Source:** `ChatSendController.kt` lines 88, 114, 336–338
- **Current test coverage:** `ChatViewModelTest` doesn't verify that `lastResponseDurationMs` is set after a completed run.
- **Why it matters:** Duration tracking is a new feature; a regression would show "0s" on every response.

### 4.5 ❌ `isOnline` / Offline Indicator Has No Test
- **Source:** `ChatViewModel.kt` lines 324–341
- **Current test coverage:** No tests for online/offline state transitions.
- **Why it matters:** The offline banner is user-facing; a stuck "You're offline" banner is confusing.

### 4.6 ❌ TTS State Mirror (`ttsState`) Has No Test
- **Source:** `ChatViewModel.kt` lines 316–322
- **Current test coverage:** `ChatViewModelTest.tts can be toggled` only tests `ttsEnabled` (a boolean). Doesn't test `ttsState` (the TTS state enum mirror).
- **Why it matters:** The "Tap to stop reading" pill depends on `ttsState` being correctly mirrored from `TextToSpeech.state`.

### 4.7 ❌ `stopTts()` Has No Test
- **Source:** `ChatViewModel.kt` lines 538–540
- **Current test coverage:** Not tested.
- **Why it matters:** The stop button is user-facing; if `textToSpeech.stop()` isn't called, the pill is misleading.

### 4.8 ❌ `deleteCurrentConversation()` Has No Test
- **Source:** `ChatViewModel.kt` lines 738–764
- **Current test coverage:** Not tested.
- **Why it matters:** Two code paths (incognito vs. normal) with `runCatching` — unbranched.

---

## 5. Flaky Tests

### 5.1 🟡 `ProactiveBootstrapTest` — Real-Time Polling
- **File:** `aura-core/.../ProactiveBootstrapTest.kt`
- **Pattern:** `awaitVerification()` helper (lines 183–196) spins with `Thread.sleep(20)` + `System.currentTimeMillis()` + deadline of 2_000ms.
- **Flake risk:** **HIGH.** On a slow CI runner, 2 seconds may not be enough for `start()` to schedule all workers. On a fast runner, the thread sleeps waste 40-200ms per assertion. 6 tests use this pattern.
- **Recommendation:** Replace with `kotlinx.coroutines.test.advanceUntilIdle()` or inject a `TestDispatcher`. The `start()` method launches coroutines in a `SupervisorJob` scope — route it through the test dispatcher.

### 5.2 🟡 `ToolExecutorTimeoutTest.blocking tool is interrupted` — `Thread.sleep(3_000L)`
- **File:** `aura-core/.../ToolExecutorTimeoutTest.kt`
- **Pattern:** Line 103 uses `Thread.sleep(3_000L)` to simulate a blocking tool.
- **Flake risk:** **MEDIUM.** 3-second real wall-clock delay in a unit test. On a busy CI runner with shared CPU, the timeout (100ms) might fire before `Thread.sleep` even starts. The assertion `elapsedMs < 1_000L` on line 119 could flake under load.
- **Recommendation:** Use `kotlinx.coroutines.delay` (interruptible) instead of `Thread.sleep`, or lower the sleep duration with a tighter timeout.

### 5.3 🟡 `AnthropicProviderTest` / `GeminiProviderTest` — `delay(50)`
- **File:** `AnthropicProviderTest.kt` line 170, `GeminiProviderTest.kt` line 179
- **Pattern:** `delay(50)` in tests.
- **Flake risk:** **LOW-MEDIUM.** `delay(50)` with `StandardTestDispatcher` should be virtual time, but if mixed with `Dispatchers.IO` it becomes real wall-clock time. Depends on dispatcher configuration.

### 5.4 🟡 `UserProfileStoreTest` — `delay(50)` in coAnswers
- **File:** `aura-core/.../UserProfileStoreTest.kt` line 109
- **Pattern:** `delay(50)` inside `coAnswers` block.
- **Flake risk:** **LOW.** Same concern as above.

### 5.5 🟡 `ModelCatalogRepositoryTest` — Real `delay(500)` / `delay(5_000)` / `delay(30_000)`
- **File:** `aura-core/.../ModelCatalogRepositoryTest.kt` lines 229, 272, 304, 508, 634
- **Pattern:** Multiple real-time `delay` calls (up to 30 seconds).
- **Flake risk:** **HIGH.** Tests that actually wait 30 seconds for a timeout are a CI budget concern first, flake risk second. But on slow runners, the race between cancellation and real wall-clock can flake.
- **Recommendation:** Use `TestCoroutineScheduler.advanceTimeBy()` for timeout tests instead of real delays.

### 5.6 🟡 `MoaProviderTest` — Known Flaky (Commit History)
- **File:** `aura-core/.../MoaProviderTest.kt`
- **History:** Commits `b6acaadb` and `8e566af6` both adjusted `dispatchTimeoutMs` (30s → 10s → back to 30s) — explicit admission of flakiness.
- **Pattern:** `awaitCancellation()` with thread-timing-dependent assertions. The commit messages say: "Pre-existing flaky test — on cold CI runners the Dispatchers.IO thread timing differs."
- **Flake risk:** **HIGH.** Already known flaky. The timeout adjustments are a band-aid, not a fix.
- **Recommendation:** Use `TestCoroutineScheduler` or inject a `TestDispatcher` to eliminate real thread timing from the test.

---

## 6. Test Isolation Problems

### 6.1 🟡 `ChatViewModelTest` — Shared Mutable Mock State
- **File:** `app/.../ChatViewModelTest.kt`
- **Pattern:** All 11 mocks (lines 51–64) are class-level fields. `providerRegistry`, `toolRegistry`, etc., are created once in `setUp()` and mutated across tests. `providerRegistry.all()` returning `emptyList()` is shared; test `refreshModels` (line 118) overrides it with a different list.
- **Risk:** If tests run out of order or in parallel, mocks from one test leak into another. `coEvery` stubs persist between tests.
- **Recommendation:** Call `clearMocks()` in `@After` for all shared mocks, or recreate them in each test method.

### 6.2 🟡 `HistoryViewModelTest` — Shared `store` Mock
- **File:** `app/.../HistoryViewModelTest.kt`
- **Pattern:** `store` is a class-level `mockk(relaxed = true)` (line 30). `coEvery` stubs accumulate across tests.
- **Risk:** `coEvery { store.recentPinnedFirst(50) }` from one test bleeds into another that expects a different return.
- **Recommendation:** Add `clearMocks(store)` in `@After`.

### 6.3 🟡 `BackupManagerTest` — 20+ Class-Level Mocks
- **File:** `aura-core/.../BackupManagerTest.kt`
- **Pattern:** 21 mocks created as class-level fields, all `relaxed = true`. Every test uses every mock, but most aren't stubbed per-test. Tests set expectations on specific mocks but leave the others with default relaxed behavior.
- **Risk:** If a test calls `coEvery` on one mock but another mock's relaxed default returns an unexpected value, the test can pass on one run and fail on another.
- **Recommendation:** Group mocks by subsystem and clear unused ones per test.

### 6.4 🟡 `ProactiveBootstrapTest` — Shared `conversationStore` Not Stubbed
- **File:** `aura-core/.../ProactiveBootstrapTest.kt`
- **Pattern:** `conversationStore` is mocked `relaxed = true` but no test verifies or stubs its behavior. `start()` calls `conversationStore.purgeDeletedOlderThan()` which silently returns 0 via relaxed mock. If a future test needs a real return value, the mock gives no feedback.
- **Recommendation:** At minimum, `coEvery { conversationStore.purgeDeletedOlderThan() }` should be explicitly stubbed or verified in at least one test.

---

## 7. Coverage Holes in Critical Paths

### 7.1 ❌ Tool Risk Classification → PolicyEngine
- **Source:** `PolicyEngine.kt`, `ToolPolicyDefaults.kt`
- **Gaps:**
  - No test for `DESTRUCTIVE` tool classification → `EXPLICIT` confirmation
  - No test for `PRIVACY` tool classification (memory access tools)
  - No test for `ToolPolicyDefaults.forTool()` as a standalone function
  - No test confirming `WRITE_LOCAL` defaults to `ConfirmationLevel.NONE` (the regression fix)
- **Recommended test:** Add `ToolPolicyDefaultsTest` (see section 1.3) + add `DESTRUCTIVE` and `PRIVACY` tests to `PolicyEngineTest`.

### 7.2 ❌ AgentRun Approval Flow
- **Source:** `AgentRunsViewModel.kt` (lines 75–93), `AgentRunStore.kt`
- **Gaps:**
  - `AgentRunsViewModelTest` — no test file at all (see 1.4)
  - `AgentRunStoreTest.approve` (line 124–139) doesn't test the "look up before flipping" pattern that was the critical bug fix
- **Recommended test:** `AgentRunStoreTest` should verify that `getById` is called before `decide`, and that the approval is looked up from `pendingApprovals`.

### 7.3 ❌ Conversation Soft-Delete + Restore
- **Source:** `ConversationDao.kt`, `ConversationStore.kt`, `HistoryViewModel.kt`
- **Gaps:** See section 3 (items 3.1–3.7).

### 7.4 ❌ Backup Export with `deletedAt` Column
- **Source:** `BackupManagerTest.kt`
- **Gaps:**
  - `ConversationBackup` in the restore test (line 268–272) doesn't include `deletedAt` field — it's a new column that may not be serialized in the backup schema
  - If `deletedAt` is not part of the backup schema, soft-deleted tombstones won't survive export/import, meaning conversations that were soft-deleted would be restored as visible after import
- **Recommended test:** Verify `ConversationBackup` serializes/deserializes `deletedAt` correctly. Verify that export includes tombstoned rows (if that's the intent) or excludes them (if not).

### 7.5 ❌ Embedding Guard Columns (`modelId`, `dimension`)
- **Source:** Commit 8e3644fe
- **Gaps:**
  - `Embedder` interface gained `modelId()` and `dimension()` methods — are these tested?
  - `MemoryStore.store()` now stamps `embeddingModel`/`embeddingVersion` — verify in `MemoryStoreTest`?
- **Recommended test:** Verify `MemoryStore.store()` writes `modelId()` and `dimension()` to the entity. Verify `CloudEmbedder.dimension()` returns expected value.

---

## Summary / Risk Table

| Area | Gaps Found | Severity |
|------|-----------|----------|
| `ErrorMessageMapper` untested | 1 file, 8 branches | HIGH |
| `ChatSendController` untested | 1 file, 391 lines | HIGH |
| `ToolPolicyDefaults` untested | 1 file, 5 risk levels | HIGH |
| `AgentRunsViewModel` untested | 1 file, approval flow | HIGH |
| Soft-delete restore/purge untested | 4 functions | HIGH |
| Daily-use UX features untested | 8+ functions | MEDIUM |
| `TtsTest` is a placeholder | 3 trivial assertions | LOW |
| `AgentRunStoreTest` approval shallow | Missing lookup-first guard | MEDIUM |
| Flaky: ProactiveBootstrap polling | 6 tests, 2s deadline | HIGH |
| Flaky: MoaProviderTest (known) | 1 test, dispatchTimeout | HIGH |
| Flaky: ToolExecutorTimeout Thread.sleep | 1 test, 3s real delay | MEDIUM |
| Isolation: shared mocks across tests | 4+ test files | MEDIUM |
| Backup: deletedAt column not in schema test | ConversationsBackup | MEDIUM |
| Embedding guard columns untested | 2 new Embedder methods | LOW |
