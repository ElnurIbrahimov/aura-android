# Test Gap Audit Round 3 — Highest-Leverage Gaps (Post v0.30.1)

**Branch:** feat/tier-1-friction
**Date:** 2026-07-23
**Current Test Baseline:** 1,151 tests, 0 failures
**Scope:** Gaps missed by 5 prior audit cycles + 2 prior fix rounds

---

## Preamble

This is NOT a restatement of the 281-line test-gap-report.md. Every item here
was verified by reading the actual source and test files. Items already fixed
(ErrorMessageMapperTest, ToolPolicyDefaultsTest, restore/purge tests,
MIGRATION_5_6 test, TtsTest rewrite, 3 HistoryViewModelTest additions from
v0.30.1) are NOT repeated.

Items are ordered by leverage — the ones that lock in recent fixes and provide
the most regression protection per test written.

---

## 1. `ChatSendControllerTest.kt` — Core Send Pipeline (HIGH)

**What to add:** New test file covering 5–7 critical public methods.

**File path:** `app/src/test/kotlin/com/aura/ui/viewmodel/ChatSendControllerTest.kt`

**Why it matters (SCOPE: L):** The 391-line controller owns the entire send
pipeline — `runSend` (streaming orchestration, MoA escalation, specialist
overrides, TTS on completion, tool dispatch, error mapping, conversation
persistence) and `cancel`. ChatViewModelTest only tests the VM wrappers, not
the controller's internal logic: duration tracking, `correctionPatterns`,
`shouldEscalate`, `consecutiveFailures` counters. A regression here silently
corrupts every user send.

| Test | What it covers |
|------|---------------|
| `runSend streams TextDelta events and updates conversation` | Basic streaming path → conversation turns updated |
| `runSend sets lastRunDurationMs via onRunComplete on Done` | `lastRunDurationMs` tracking (UX footer) |
| `runSend triggers TTS speak when ttsEnabled` | `textToSpeech.speak()` called on Done |
| `runSend increments consecutiveFailures on Error` | Failure counter → MoA escalation trigger |
| `correctionPatterns detects user corrections` | `shouldEscalate` logic for auto Deep Mode |
| `cancel saves partial response and clears streaming` | Cancellation side-effects |
| `applyProviderWarning updates state fields` | Pure function, independently testable (top-level) |

**Approximate effort:** 2–3h

---

## 2. `AgentRunsViewModelTest.kt` — Approval Flow (HIGH)

**What to add:** New test file covering approval flow, enqueue, cancel paths.

**File path:** `app/src/test/kotlin/com/aura/ui/viewmodel/AgentRunsViewModelTest.kt`

**Why it matters (SCOPE: M):** The 129-line ViewModel had a critical bug
(commit 109649c4) where `approve()` flipped status before looking it up from
`pendingApprovals`. No test file exists. The VM orchestrates the entire durable
agent-run lifecycle — `approve`, `deny`, `resume`, `cancel`, `loadRuns`,
`selectRun` — and calls through to `AgentRunStore` + `AgentRunExecutorService`.
Without tests, the next similar bug goes undetected.

| Test | What it covers |
|------|---------------|
| `approve looks up approval before calling store.approve` | Regression guard for the critical fix |
| `approve calls resetStep and enqueue after approval` | `resetStep` + `AgentRunExecutorService.enqueue` |
| `deny calls store.deny and refreshes detail` | Deny path |
| `cancel calls store.finish with CANCELLED` | Cancel path |
| `resume re-enqueues the executor worker` | Resume path |
| `loadRuns populates state from store.listRecent` | Initial load |
| `selectRun loads steps, events, and approvals` | Detail view |

**Approximate effort:** 1.5–2h

---

## 3. Shared Mock Isolation: Add `clearMocks()` in `@After` (MEDIUM)

**What to do:** Add `clearMocks()` for all class-level mockk mocks in 3 files.

**Files to patch:**
- `app/src/test/kotlin/.../ChatViewModelTest.kt`
- `app/src/test/kotlin/.../HistoryViewModelTest.kt`
- `aura-core/src/test/.../BackupManagerTest.kt`

**Why it matters (SCOPE: S each, M total):** All three files use class-level
`mockk(relaxed = true)` mocks created once per test class, with `coEvery`
stubs that accumulate across tests. ChatViewModelTest has 11 shared mocks,
HistoryViewModelTest has 1 shared `store` mock, BackupManagerTest has 20+
shared mocks. If tests run out of order, stale stubs bleed between tests —
producing false passes or puzzling failures that are hard to debug.

| File | Fix |
|------|-----|
| `ChatViewModelTest.kt` | Add `clearMocks(loop, providerKeys, providerRegistry, toolRegistry, toolExecutor, textToSpeech, userPreferences, memoryStore, conversationStore, knowledgeGraphRepository, crashLogger)` in `@After` |
| `HistoryViewModelTest.kt` | Add `clearMocks(store)` in `@After` |
| `BackupManagerTest.kt` | Add `@After` with `clearMocks(...)` for all 20+ mocks |

**Approximate effort:** 30min each

---

## 4. `ConversationStoreTest.mostRecent filters deletedAt` — v0.30.0 Fix (HIGH)

**What to add:** One test verifying that `mostRecent()` uses the deletedAt filter.

**File path:** `aura-core/src/test/kotlin/.../ConversationStoreTest.kt`

**Why it matters (SCOPE: S):** The F5 fix added `WHERE deletedAt IS NULL` to
`mostRecent()`. The existing test (`mostRecent returns null when DAO is empty`)
only tests the null-return case. There is NO test that the filter actually
works. If a future refactor drops the WHERE clause, the app silently resumes
into deleted conversations — users see a ghost chat they already deleted.

| Test | What it covers |
|------|---------------|
| `mostRecent returns null when the only row is soft-deleted` | Mock `dao.mostRecent()` returning a deleted entity, verify store returns null — OR integration with in-memory Room to test the SQL |

**Approximate effort:** 15–30min

---

## 5. `ChatGptSubscriptionProviderTest` — Stream Timeout (v0.30.1 Fix) (HIGH)

**What to add:** Test file (or tests) for the stream read timeout.

**File path:** `aura-core/src/test/kotlin/.../ChatGptSubscriptionProviderTest.kt`

**Why it matters (SCOPE: M):** No test file exists for this provider at all.
The F6 fix added `withTimeout(STREAM_READ_TIMEOUT_MS)` around the SSE channel
read — a critical safety net. Without a timeout test, a future refactor could
drop the `withTimeout` block, and the agent loop would hang forever on a silent
API response. This is the same pattern the other 3 providers have and test.

| Test | What it covers |
|------|---------------|
| `chat completes normally for a valid SSE stream` | Happy path |
| `chat handles mid-stream silence with withTimeout` | Timeout → synthesized stop |
| `chat propagates HTTP errors as ProviderError chunks` | Error path (401, 403, 500) |

**Approximate effort:** 1–1.5h

---

## 6. `ChatViewModelTest.exportConversation` — Daily-Use UX (MEDIUM)

**What to add:** Test for the markdown export function.

**File path:** `app/src/test/kotlin/.../ChatViewModelTest.kt`

**Why it matters (SCOPE: S):** `exportConversation()` (line 693) is the
user-facing "Share as text" feature. It generates `## User` / `## Aura`
sections from conversation turns. No test exists. A regression produces
malformed markdown.

| Test | What it covers |
|------|---------------|
| `exportConversation produces ##User/##Aura sections` | Both user and assistant turns |
| `exportConversation handles empty conversation` | Edge case |

**Approximate effort:** 15–30min

---

## 7. `ChatViewModelTest.clearConversation` — Daily-Use UX (MEDIUM)

**What to add:** Test for the "Clear" action.

**File path:** `app/src/test/kotlin/.../ChatViewModelTest.kt`

**Why it matters (SCOPE: S):** `clearConversation()` (line 674) cancels the
active stream, empties turns, resets state, and saves. If it breaks, the user
sees stale data after clearing.

| Test | What it covers |
|------|---------------|
| `clearConversation empties turns and resets state` | Cancels streaming, clears error, clears in-flight |
| `clearConversation persists the cleared conversation` | Verifies `conversationStore.save()` is called |

**Approximate effort:** 15–30min

---

## 8. `ChatViewModelTest.editAndResend` — Daily-Use UX (MEDIUM)

**What to add:** Test for the edit-and-resend workflow.

**File path:** `app/src/test/kotlin/.../ChatViewModelTest.kt`

**Why it matters (SCOPE: S):** `editAndResend()` (line 715) truncates
conversation turns, replaces user text, and re-runs the send pipeline via
`runSend(retryUserText = ...)`. No test exists. An index-out-of-bounds or
streaming guard regression silently loses the edit.

| Test | What it covers |
|------|---------------|
| `editAndResend truncates and resends` | Truncates to turnIndex, calls runSend with retryUserText |
| `editAndResend is a no-op when streaming` | Guard clause |
| `editAndResend is a no-op for out-of-bounds index` | Edge case |

**Approximate effort:** 30min

---

## 9. `ChatViewModelTest.isOnline transitions` — Daily-Use UX (MEDIUM)

**What to add:** Test for connectivity state changes.

**File path:** `app/src/test/kotlin/.../ChatViewModelTest.kt`

**Why it matters (SCOPE: S):** `isOnline` in ChatUiState (line 219) controls
the "You're offline" banner. The VM observes `ConnectivityManager` updates at
lines 324–341. No test verifies the state transitions. A stuck offline banner
(or missing one) is a visible UX defect.

| Test | What it covers |
|------|---------------|
| `isOnline is true by default` | Initial state |
| *Note: Full connectivity testing requires mocking ConnectivityManager, best done via integration or Robolectric test similar to existing pattern* | |

**Approximate effort:** 30–45min

---

## 10. `ChatViewModelTest.deleteCurrentConversation` — Daily-Use UX (MEDIUM)

**What to add:** Test for the "Delete" action on the chat screen.

**File path:** `app/src/test/kotlin/.../ChatViewModelTest.kt`

**Why it matters (SCOPE: S):** `deleteCurrentConversation()` (line 739) has two
code paths (incognito vs normal) with `runCatching`. No test exists. The
incognito path skips the store entirely; the normal path soft-deletes. A
regression could accidentally delete through both paths or silently throw.

| Test | What it covers |
|------|---------------|
| `deleteCurrentConversation deletes via store when not incognito` | Normal path |
| `deleteCurrentConversation skips store when incognito` | Incognito path |
| `deleteCurrentConversation resets to new conversation` | State cleanup |

**Approximate effort:** 30min

---

## 11. `AnthropicProviderTest.chat() safe cast` — v0.30.1 Fix (MEDIUM)

**What to add:** Test(s) for the `chat()` method covering the safe cast.

**File path:** `aura-core/src/test/kotlin/.../AnthropicProviderTest.kt`

**Why it matters (SCOPE: S):** The F7 fix was a one-character change (`as` →
`as?` on line 125). The existing test file only tests `listModels`,
`isConfigured`, and `cancel`. The `chat()` method (which is the one that had
the bug) has zero coverage. A future refactor could re-introduce the unsafe
cast.

| Test | What it covers |
|------|---------------|
| `chat parses SSE stream and emits text deltas` | Happy path via MockWebServer SSE |
| *Note: Adding a full SSE MockWebServer test for chat() is the ideal, but even a safe-cast contract test via direct argument call would provide regression coverage* | |

**Approximate effort:** 30min–1h

---

## 12. `BackupManagerTest.deletedAt round-trip` — v0.30.0 Fix (MEDIUM)

**What to add:** Strengthen the existing round-trip test to include `deletedAt`.

**File path:** `aura-core/src/test/kotlin/.../BackupManagerTest.kt`

**Why it matters (SCOPE: S):** The round-trip test at line 138 creates a
`ConversationBackup` without `deletedAt` (defaults to null). The F2 fix added
`deletedAt` to `ConversationBackup` and both conversion helpers. Without a test
that includes a non-null `deletedAt`, a future regression silently drops the
tombstone on export/import — resurrecting deleted conversations.

| Test | What it covers |
|------|---------------|
| Add `deletedAt = 99999L` to the `ConversationBackup` in `round-trip encode decode preserves payload` | Verify the field survives encode → decode |

**Approximate effort:** 5min

---

## 13. `ConversationStoreTest.deletedAt carry-forward on save()` with null previous — v0.30.0 (LOW-MEDIUM)

**What to add:** Edge case for the save() carry-forward logic.

**File path:** `aura-core/src/test/kotlin/.../ConversationStoreTest.kt`

**Why it matters (SCOPE: S):** The existing `save preserves soft-delete
tombstone` test (line 173) verifies carry-forward when a previous row exists.
But `save()` also has a code path where `dao.getById()` returns null (new
conversation, first save). The code catches the failure and `getOrNull()` = null.
A regression where `getById failure → previous = null → deletedAt = null` is
already correct (new rows have no tombstone), but there's no guard test for the
null-previous edge case.

| Test | What it covers |
|------|---------------|
| `save with null previous leaves deletedAt as null` | Edge case: new conversation, no previous row |

**Approximate effort:** 5min

---

## Summary Table

| # | Gap | Risk | Effort | File |
|---|-----|------|--------|------|
| 1 | `ChatSendControllerTest` — Core send pipeline untested (5–7 tests) | **HIGH** | L (2–3h) | New: `.../ChatSendControllerTest.kt` |
| 2 | `AgentRunsViewModelTest` — Approval flow untested (6–7 tests) | **HIGH** | M (1.5–2h) | New: `.../AgentRunsViewModelTest.kt` |
| 3 | Shared mock isolation — `clearMocks()` missing from 3 files | MEDIUM | S (30min each) | Patch 3 existing test files |
| 4 | `mostRecent` deletedAt filter — no test for the WHERE clause | **HIGH** | S (15–30min) | Add to `ConversationStoreTest.kt` |
| 5 | `ChatGptSubscriptionProvider` timeout — no test file at all | **HIGH** | M (1–1.5h) | New: `.../ChatGptSubscriptionProviderTest.kt` |
| 6 | `exportConversation()` — daily-use UX, no test | MEDIUM | S (15–30min) | Add to `ChatViewModelTest.kt` |
| 7 | `clearConversation()` — daily-use UX, no test | MEDIUM | S (15–30min) | Add to `ChatViewModelTest.kt` |
| 8 | `editAndResend()` — complex operation, no test | MEDIUM | S (30min) | Add to `ChatViewModelTest.kt` |
| 9 | `isOnline` transitions — offline banner, no test | MEDIUM | S (30–45min) | Add to `ChatViewModelTest.kt` |
| 10 | `deleteCurrentConversation()` — two code paths, no test | MEDIUM | S (30min) | Add to `ChatViewModelTest.kt` |
| 11 | `AnthropicProvider.chat()` safe cast — v0.30.1 fix untested | MEDIUM | S–M (30min–1h) | Add to `AnthropicProviderTest.kt` |
| 12 | Backup round-trip missing `deletedAt` in test fixture | MEDIUM | S (5min) | Patch `BackupManagerTest.kt` |
| 13 | `save()` null-previous edge case — no guard test | LOW | S (5min) | Add to `ConversationStoreTest.kt` |

**Total new tests:** ~20–25
**Total new test files:** 3 (ChatSendControllerTest, AgentRunsViewModelTest, ChatGptSubscriptionProviderTest)
**Files to patch:** 5 (ChatViewModelTest, HistoryViewModelTest, BackupManagerTest, ConversationStoreTest, AnthropicProviderTest)
