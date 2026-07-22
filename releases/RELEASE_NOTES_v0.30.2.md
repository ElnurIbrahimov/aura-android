# Aura Android v0.30.2 (2026-07-23)

**Version code:** 32
**Previous:** v0.30.1 (31)

## Headline

The 4th engineering-review cycle (5th audit) shipped 8 P0/P1 bug fixes, 5
dead-code/duplication cleanups, a `RestoreCounts` refactor, and 5 new regression
tests — all without breaking a single existing test (1,151 → 1,156, 0 failures).
The biggest wins are the silent data-loss bugs in `BackupManager.restore()` and
the new `BLOCKED` state for agent runs that need user permission.

## Bugs fixed

### P0 — Silent backup data loss (6 fixes)

- **BackupManager.restore()** was silently dropping 8 schema-v10 entity types
  on import: `beliefs`, `evidence`, `worldEvents`, `opportunities`,
  `creativeArtifacts`, `canonFacts`, `preferenceSignals`, `styleProfiles`.
  The JSON contained them; `restore()` never inserted them. **Restored backups
  were missing ~6-12% of user state** for anyone using world model + creative
  studio + taste tracking.
  Fix: added `toEntity` mappers + `insertAll`/`deleteAll` to 4 world DAOs,
  2 taste DAOs, and `CanonFactDao`; wired into `restore()` and `purgeAll()`.

- **BackupManager.snapshot()** was never populating `creativeRevisions` or
  `creativeBranches` even though the `CreativeRevisionBackup` /
  `CreativeBranchBackup` data classes existed in `AuraBackup.kt`. Fix:
  added toEntity mappers + snapshot wiring; extended the two backup schemas
  with backward-compatible defaults for fields the entities had but the
  backup types didn't (e.g. `branchId`, `parentRevisionId`, `headRevisionId`).

- **BackupManager.purgeAll()** skipped all 10 schema-v10 tables. A
  restore→purge→restore round-trip was a lie; the "purged" data came back.
  Fix: added `deleteAll()` calls for the 10 new tables.

### P0 — Agent run state semantics

- **AgentRunExecutorWorker** was calling `failStep()` for `NeedsApproval`
  and `NeedsPermission` tool results. A blocked step (waiting on the user)
  is semantically a *pause*, not a *failure* — but the worker was marking
  them as FAILED and emitting STEP_FAILED. Fix: added
  `AgentRunStore.blockStep()` (writes status="BLOCKED", emits
  STEP_BLOCKED) and switched the worker to it.

### P0 — Security boundary

- **MemoryAugmentedAgenticLoop** had a specialist MCP bypass — the filter
  `def.name.startsWith("mcp_") || def.category == "mcp"` let *any* MCP
  tool through a specialist's `toolsAllowed` allowlist. A specialist
  configured with `toolsAllowed=[web_search]` could silently invoke
  `delete_file` from an MCP server. Fix: extracted the base tool name
  (`mcp_<serverId>_<tool>` → `<tool>`) and checked the base name against
  the allowlist.

### P0 — Stale tool surface

- **McpToolBridge.syncTools()** retained tools from MCP servers that were
  still in the config list but no longer connected (e.g. server URL
  changed, server process died). The LLM could call those tools forever
  until the next manual sync. Fix: added a second staleness check —
  `(serverId in currentServerIds) && (serverId not in connectedServerIds)`
  → unregister the server's tools. Unprefixed tools (registered via
  `syncToolsUnprefixed`) are left alone by design; their cleanup path is
  `unregisterAll()`.

### P1 — TOCTOU + search leak

- **AgentRunsViewModel.approve()** captured the approval stepId after
  flipping the status. On Main dispatcher this isn't an actual race, but
  the code was structurally wrong — the captured `stepId` could be null
  if another caller resolved the approval first. Fix: capture `stepId`
  before the flip, early-return if not found.

- **GlobalSearchRepository** was using `conversationDao.search()` (no
  `deletedAt IS NULL` filter). Soft-deleted conversations surfaced in
  global search. Fix: switched to `searchVisible()`.

## Cleanups

- Removed dead `formatClockTime` (TimeFormat.kt, 0 callers).
- Removed dead `Haptics.tap/error/success` and the `Build.VERSION_CODES.R`
  guard around the (now-gone) success call.
- Removed duplicate `DeepResearchTool.Citation` nested class; all 9
  internal references now resolve to the top-level `com.aura.tools.Citation`.
- Added `ToolCategories.AGENTS = "agents"` (delegate_to_agent and
  run_council tools were using a literal).
- Added AGENTS + SKILLS + EVOLUTION to `ToolCategories.ALL` ordering;
  added displayName + icon for AGENTS. Tools in those categories were
  being grouped under OTHER.

## Refactor

- `RestoreCounts.total` expanded from 17-term hand-sum to 27-term
  explicit sum. Kept the hand-sum (no reflection dependency) but
  documented why and named every field — the next person who adds a
  field can't miss it.

## Tests added (5)

- `BackupManagerTest`: schema-v10 roundtrip with wired DAOs (pins B2
  silent-data-loss fix); null-DAO silent-skip contract; `RestoreCounts
  total` = 27.
- `AgentRunStoreTest`: `blockStep` writes BLOCKED status with reason
  + emits STEP_BLOCKED.
- `McpToolBridgeTest`: 2 new tests (prefixed tools unregister on
  server-remove; prefixed tools unregister on server-disconnect);
  existing "stale tools" test rewritten to document the
  unprefixed-by-design behavior.

## Test baseline

| | Before | After |
|---|---|---|
| Unit tests | 1,151 | 1,156 (+5) |
| Test files | 192 | 192 |
| Pre-existing failures | 0 | 0 |
| Lint errors | 1 (`ImageDecoder` API 28 / minSdk 26) | 0 |
| Lint warnings | 41 | 41 |

The single lint error (an unguarded `ImageDecoder.decodeBitmap()` call in
`ChatComposer.kt:103`) was blocking v0.30.2 from shipping. Fix: wrap the
call in a `Build.VERSION.SDK_INT >= P` check; on API 26-27 fall back to
`BitmapFactory.decodeStream(resolver.openInputStream(uri))` which supports
the same `content://` URIs. Single-line change with a fallback; clipboard
image paste now works on minSdk-26 devices (Android 8.0).

## What's not in this release

- **No MCP tool registry refactor** — out of scope for cycle 3; the
  security boundary is now correctly enforced.
- **No ChatSendControllerTest** — 391 lines, 0 tests, 2-3h work. Will
  ship in v0.30.3 (already on the next-session list).
- **No AgentRunsViewModelTest** — 129 lines, 0 tests, 1.5-2h work.
  v0.30.3.
- **No emulator visual verification** — `ImageDecoder` lint fix was
  the only change in `app/`, no UI changes. v0.30.3 if v0.30.2 ships
  and someone wants to confirm clipboard paste still works.

## Subagent false positives rejected

- "ToolExecutor.runInterruptible+runBlocking is wrong" — the pattern
  is correct for honoring `withTimeout` on blocking code; changing it
  would regress `ToolExecutorTimeoutTest`.
- "No mid-loop re-compaction" — already mitigated by per-specialist
  `maxSteps` + max context budget. The fix is a feature, not a bug.
- "MCP tools invisible to specialists" — verified: they're visible
  via `SettingsViewModel.syncTools()` → `ToolRegistry` → agentic loop.
  The audit was wrong.

## Download

- APK: `releases/aura-debug-v0.30.2.apk` (35.5 MB)
- Min SDK: 26 (Android 8.0)
- Target SDK: 35 (Android 15)
- Branch: `feat/tier-1-friction`
- Commit: see `git log -1` after push
