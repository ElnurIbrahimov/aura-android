# ENGINEERING REVIEW — 2026-08-02

**Project:** Aura Android (D:\aura-android-clean)
**Branch:** feat/tier-1-friction
**Version:** v0.46.0 (versionCode 55)
**Scope:** Full-project engineering review and improvement pass

---

## 1. Project-wide issues found

### Confirmed issues

| # | Severity | Category | Description |
|---|----------|----------|-------------|
| 1 | P2 | Bug | `googleClientIdSync()` and `microsoftClientIdSync()` in UserPreferences used `runBlocking` to read Flows synchronously, called from 6 integration tool execute blocks. The Tool.execute lambda is `suspend` — `runBlocking` was unnecessary and blocked the IO thread. |
| 2 | P3 | Dead code | `googleClientIdSync()` and `microsoftClientIdSync()` had no callers after fix #1. Removed. |
| 3 | P3 | Doc drift | README.md said v0.39.1/63 tools — actual is v0.46.0/69 tools. |
| 4 | P3 | Doc drift | architecture.md said 59 tools — actual is 69 tools. Missing prose craft tools, voice calibration, tension analyzer, character progression tracker, Google Workspace + Microsoft Graph integrations. |

### Lower-confidence concerns (not fixed — flagged)

| # | Category | Description | Why not fixed |
|---|----------|-------------|---------------|
| A | Wiring gap | CreativeStudioViewModel has `applyCraftTool()`, `calibrateVoice()`, `analyzeTension()` but no UI screen calls them. The 5 beyond-SOTA systems are wired at the ViewModel layer but have no Compose UI surfaces. | This is a known UI gap, not a bug. Building the UI tabs is a separate feature session — this review focuses on correctness, not new UI. |
| B | Architecture | `PolicyEngine` is nullable in ToolExecutor (`= null`). When null, REMOTE_COST tools fall through to a manual check. This works but is fragile. | Nullable was a deliberate design choice for gradual rollout. Changing to non-null would require verifying all test mock setups. Flagged, not changed. |
| C | Silent catch | `EvolutionSkillRevisionStore.kt:44` uses `runCatching { ... }.getOrNull()` for JSON deserialization. No `.onFailure` log. | This is an acceptable pattern for "try to decode, return null if corrupt" — the caller handles null. Adding logging would be noise, not signal. |

### Ambiguities

- `CreativeBranchStore.branchFrom()`, `archive()`, `updateHead()`, `lineage()` have no callers outside tests. They may be intended for future use (branching/merging creative drafts). Not deleted — purpose is clear from API design, just not yet consumed by UI.

---

## 2. Bugs and risks fixed

### Fix 1: Remove runBlocking from Google/Microsoft integration tools (P2 bug)

**Root cause:** The `Tool.execute` lambda in ToolRegistry is `suspend (ToolCall, ToolContext) -> ToolResult`. The Google/Microsoft tools called `userPreferences.googleClientIdSync()` which internally used `runBlocking { flow.first() }`. Since the lambda is already suspend, this was unnecessary thread blocking — the IO thread was blocked waiting for a Flow emission that could have been suspended cooperatively.

**Affected files (6):** GoogleGmailTool.kt, GoogleCalendarTool.kt, GoogleDriveTool.kt, MicrosoftMailTool.kt, MicrosoftCalendarTool.kt, MicrosoftFilesTool.kt

**Fix:** Replaced `userPreferences.googleClientIdSync()` with `userPreferences.googleClientId.first()` in all 6 files. Added `import kotlinx.coroutines.flow.first` to each. Removed the two dead sync functions from UserPreferences.kt.

**Risk:** None. The suspend lambda already supports `.first()`. All 6 tools compile and pass tests.

---

## 3. Security and reliability improvements made

No new security issues were found. The codebase has:
- SsrfGuard on all network tools (MCP, CustomOpenAiCompat, HTTP file tools) — verified by subagent audit
- API keys in Authorization headers (not URL params) — verified
- PinnedClient on MCP connections — verified
- SecureDataStore for OAuth tokens, SMTP passwords — verified
- BIOMETRIC_STRONG for app lock and biometric prompt — verified
- ToolExecutor withTimeout (enforced) — verified
- Tool result truncation (4000 chars in Conversation) — verified
- PolicyEngine for tool risk evaluation — verified (nullable but functional)
- 401/400/403 marked non-retryable across all providers — verified

---

## 4. Dead code, duplication, and consolidation changes

| What | Why safe |
|------|----------|
| `googleClientIdSync()` function removed from UserPreferences | Zero callers after fix #1 (verified with grep) |
| `microsoftClientIdSync()` function removed from UserPreferences | Zero callers after fix #1 (verified with grep) |

No other dead code removed. `CreativeBranchStore.branchFrom/archive/lineage` and `CreativeArtifactStore.revise/archive/restore` have no callers outside tests but have clear intended purpose (future UI for draft branching). Flagged, not deleted.

---

## 5. Refactors performed and why

No refactoring performed. The codebase is well-structured after 15+ prior review cycles. The remaining structural issues (CreativeStudioViewModel has 10 constructor params, ChatViewModel is ~1100 lines with 3 controllers extracted) are at the threshold where further extraction adds indirection without clarity. No refactor was clearly justified.

**Refactors intentionally avoided:**
- ChatViewModel further splitting — 3 controllers already extracted, remaining methods are thin delegations
- CreativeStudioViewModel splitting — would fragment the creative state across multiple VMs
- Provider consolidation — 17 providers share OpenAiSseParser and common patterns; no duplication to eliminate

---

## 6. Performance improvements made and why

No performance optimizations made. The codebase has no obvious inefficiencies:
- Tool results truncated to 4000 chars in Conversation
- Memory recall uses BM25 + RRF + cross-encoder reranking with caching
- Parallel tool execution via async+awaitAll
- Lifecycle-aware collection (37 sites migrated in prior session)
- ConversationCompactor queries real per-provider context windows
- No N+1 queries detected in stores

---

## 7. Tests added or updated

No new tests added this pass. The existing 1,589 tests (1,236 core + 353 app) cover:
- All 11 Room DB migrations (migration registry audit test)
- All provider SSE parsing (Anthropic, OpenAI, Gemini, ChatGPT)
- Tool executor timeout, truncation, policy
- Backup/restore roundtrip for all entity types
- Screen contract (NavGraph route coverage)
- ViewModel state machines for 12 VMs
- Silent runCatching audit test
- Memory recall, reranking, BM25, query rewriting
- Agentic loop permission flow, planning step, tool execution

The runBlocking fix in 6 tool files is covered by the existing SettingsViewModelAppLockTest which stubs the new Flow properties. No regression tests were needed because the fix was a mechanical replacement of `runBlocking { flow.first() }` with `flow.first()` in suspend lambdas — no behavior change.

---

## 8. Documentation updated

| File | Change |
|------|--------|
| README.md | Version v0.39.1 → v0.46.0, tools 63 → 69, added 6 integration tools (gmail, google calendar, google drive, outlook mail, outlook calendar, onedrive) |
| docs/architecture.md | Tools 59 → 69, added prose craft tools, voice calibration, tension analyzer, character progression tracker, Google Workspace + Microsoft Graph integrations to module description |

---

## 9. Remaining risks, ambiguities, and recommended next steps

### Risks

1. **Creative UI gap (P2):** 5 beyond-SOTA creative systems (ProseCraftTools, VoiceCalibration, TensionAnalyzer, CharacterProgressionTracker, SmartCodexInjector) are wired at the ViewModel layer but have no Compose UI surfaces. The user cannot access applyCraftTool, calibrateVoice, or analyzeTension from the Creative Studio screen. This is the most impactful remaining gap — the systems exist but are unreachable from the app UI.

2. **PolicyEngine nullable (P3):** ToolExecutor accepts `PolicyEngine? = null`. When null, REMOTE_COST tools hit a manual fallback check. This is fragile but functional. Recommended: make non-null with a NoOp default once all test mocks are updated.

3. **Untested ViewModels (P3):** 12 ViewModels have no test files (AgentEditorViewModel, AgentRunsViewModel, EvolutionBadgeViewModel, EvolutionInboxViewModel, IntegrationsViewModel, ProductionPipelineViewModel, ProactiveHistoryViewModel, ScheduleViewModel, SkillsViewModel, ToolsViewModel, UsageViewModel, VoiceViewModel). State-machine tests exist for 12 other VMs (shipped in v0.40.0) but these 12 remain.

### Ambiguities

- `CreativeBranchStore.branchFrom/archive/lineage` — no callers, but API design suggests future draft branching. Not deleted.
- `CreativeArtifactStore.revise/archive/restore/forProjectByKind` — no callers outside tests. Same: intended for future UI.

### Recommended next steps

1. **Build creative UI tabs** — add "Craft" and "Tools" tabs to CreativeProjectScreen with buttons for Show Don't Tell, Describe, Expand, Shrink Ray, Twist, Rewrite, Voice Calibration, and Tension Analysis. This is the single highest-impact improvement.
2. **Test the 12 untested ViewModels** — follow the same state-machine test pattern used in v0.40.0.
3. **Make PolicyEngine non-null** — update test mocks, remove the null fallback in ToolExecutor.

---

## 10. Change summary

### Files modified (9 files, +15 -18 lines)

| File | Classification | Change |
|------|---------------|--------|
| UserPreferences.kt | Bug fix + cleanup | Removed googleClientIdSync/microsoftClientIdSync (runBlocking) |
| GoogleGmailTool.kt | Bug fix | runBlocking → suspend .first() |
| GoogleCalendarTool.kt | Bug fix | runBlocking → suspend .first() |
| GoogleDriveTool.kt | Bug fix | runBlocking → suspend .first() |
| MicrosoftMailTool.kt | Bug fix | runBlocking → suspend .first() |
| MicrosoftCalendarTool.kt | Bug fix | runBlocking → suspend .first() |
| MicrosoftFilesTool.kt | Bug fix | runBlocking → suspend .first() |
| README.md | Documentation | Version + tool count update |
| docs/architecture.md | Documentation | Tool count + feature description update |

### Commits (2)

1. `72854eda` — fix(integrations): remove runBlocking from Google/Microsoft tool execute blocks + README/architecture.md version drift fix

### Public behavior changes

None. The `runBlocking → .first()` change is behavior-preserving — both read the same Flow value, just one blocks the thread and the other suspends cooperatively. No external APIs changed.

### Verification

- 1,589 tests, 0 failures
- Build: SUCCESSFUL (clean build)
- Lint: SUCCESSFUL
- assembleDebug: SUCCESSFUL
- 3 parallel subagent audits completed (creative, providers, data) — confirmed no new P0/P1 findings