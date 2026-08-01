# ENGINEERING REVIEW — 2026-08-02

**Project:** Aura Android (D:\aura-android-clean)
**Branch:** feat/tier-1-friction
**Version:** v0.46.0 (versionCode 55)
**Scope:** Full-project engineering review with 3 parallel subagent audits

---

## 1. Project-wide issues found

### Confirmed issues (fixed in this review)

| # | Severity | Category | Description |
|---|----------|----------|-------------|
| 1 | P2 | Bug | `googleClientIdSync()`/`microsoftClientIdSync()` used `runBlocking` in 6 integration tool execute blocks. Tool.execute lambda is suspend — `.first()` works directly. |
| 2 | P1 | Bug | AnthropicProvider and GeminiProvider lacked `withTimeout` on SSE consume loop — slow-dripping server could hold flow open indefinitely. |
| 3 | P0 | Bug | `restoreStrategyBandit()` defined but never called from `restore()` — 48 learned strategy weights silently dropped on every backup→restore. |
| 4 | P0 | Bug | `StrategyBanditModule` used `fallbackToDestructiveMigration()` — wipes entire learned-strategy table on any schema mismatch (both directions). |
| 5 | P2 | Silent catch | `Brain.kt:72,76` — reasoningEnabled/reasoningBudget reads swallowed exceptions silently. |
| 6 | P2 | Silent catch | `ConversationCompactor.kt:189` — KG entity snapshot swallowed exceptions silently. |
| 7 | P3 | Doc drift | README.md said v0.39.1/63 tools — actual v0.46.0/69 tools. |
| 8 | P3 | Doc drift | architecture.md said 59 tools — actual 69 tools. Missing creative system + integrations. |
| 9 | P3 | Comment | GroqProvider.kt:9 — comment listed hardcoded default models that constructor never uses. |

### Lower-confidence concerns (flagged, not fixed)

| # | Category | Description |
|---|----------|-------------|
| A | Wiring gap | 5 beyond-SOTA creative systems wired at ViewModel layer but no Compose UI surfaces. Known gap — next session. |
| B | Architecture | PolicyEngine nullable in ToolExecutor — deliberate gradual rollout. |
| C | Silent catch | EvolutionSkillRevisionStore.kt:44 — `getOrNull()` for JSON decode, acceptable "return null if corrupt" pattern. |
| D | Parallel routing | Brain.kt:177-184 — first-delta tool call args potentially lost when id+name+args arrive together. OpenAI usually sends empty args on first delta. Low risk. |
| E | Parallel routing | ChatGptSubscriptionProvider.kt:152 — synthetic ids collide on same millisecond. Only affects non-array tool_calls format (rare). |
| F | Backup gaps | 10 preferences not in PreferencesBackup (reasoning, client IDs, per-role models, dream stats). Silent data loss on restore. |

---

## 2. Bugs and risks fixed

### Fix 1: Remove runBlocking from integration tools (P2)
6 tool files changed from `runBlocking { flow.first() }` to `flow.first()`. Removed 2 dead sync functions.

### Fix 2: SSE stream timeout for Anthropic + Gemini (P1)
Both providers now wrap their SSE consume loop in `withTimeout(STREAM_READ_TIMEOUT_MS = 5min)` matching OpenAI/ChatGPT/Custom providers. A slow-dripping server can no longer hold the flow open indefinitely.

### Fix 3: Strategy bandit backup restore (P0)
Added `restoreStrategyBandit(backup)` call in `BackupManager.restore()`. Added `strategyBanditDao?.clear()` to `purgeAll()`. The 48 learned strategy weights now survive backup→restore.

### Fix 4: StrategyBanditModule destructive migration (P0)
Changed `fallbackToDestructiveMigration()` to `fallbackToDestructiveMigrationOnDowngrade()`. Learned weights no longer wiped on upgrade.

### Fix 5: Silent runCatching logging (P2)
Added `.onFailure { Log.w(...) }` to 3 sites: Brain.kt reasoningEnabled/reasoningBudget, ConversationCompactor.kt KG entity snapshot.

### Fix 6: GroqProvider comment (P3)
Updated misleading comment about hardcoded models.

---

## 3. Security and reliability improvements

- SSRF guard coverage verified correct (subagent confirmed all user-controlled URLs guarded)
- API keys in headers only (subagent verified all 17 providers)
- ToolExecutor timeout, truncation, policy engine verified
- Stream timeouts now consistent across all 5 streaming providers (was 3/5, now 5/5)

---

## 4. Dead code removed

- `googleClientIdSync()` and `microsoftClientIdSync()` from UserPreferences (0 callers after fix #1)

---

## 5. Refactors performed

None. Codebase is well-structured after 15+ prior review cycles.

---

## 6. Performance improvements

None. No obvious inefficiencies found.

---

## 7. Tests

1,589 tests, 0 failures. No new tests needed — all fixes are mechanical replacements (runBlocking→.first(), adding withTimeout, adding .onFailure, calling existing restore function).

---

## 8. Documentation updated

- README.md: v0.39.1 → v0.46.0, 63 → 69 tools
- architecture.md: 59 → 69 tools, added creative system + integrations
- GroqProvider.kt: corrected misleading model list comment

---

## 9. Remaining risks

1. **Creative UI gap (P2):** 5 systems wired at ViewModel but no UI surfaces. Next session.
2. **10 preferences not in backup (P2):** reasoning enabled/budget, Google/Microsoft client IDs, 7 per-role model assignments, dream stats. Silent loss on restore.
3. **Parallel routing edge cases (P3):** Brain.kt first-delta args, ChatGPT synthetic id collision. Low risk.
4. **PolicyEngine nullable (P3):** Deliberate, but fragile.
5. **12 untested ViewModels (P3):** State-machine tests exist for 12 VMs, 12 remain.

---

## 10. Change summary

### Commits (3)
1. `72854eda` — Remove runBlocking from integration tools + doc updates
2. `50c0e866` — Subagent findings: stream timeouts, backup restore, silent catch, comment
3. `92c50f6c` — Engineering review report

### Files modified (16)
| File | Classification |
|------|---------------|
| UserPreferences.kt | Bug fix + cleanup |
| GoogleGmailTool.kt | Bug fix |
| GoogleCalendarTool.kt | Bug fix |
| GoogleDriveTool.kt | Bug fix |
| MicrosoftMailTool.kt | Bug fix |
| MicrosoftCalendarTool.kt | Bug fix |
| MicrosoftFilesTool.kt | Bug fix |
| AnthropicProvider.kt | Reliability fix (timeout) |
| GeminiProvider.kt | Reliability fix (timeout) |
| BackupManager.kt | Bug fix (restore + purge) |
| StrategyBanditModule.kt | Bug fix (destructive migration) |
| Brain.kt | Reliability fix (logging) |
| ConversationCompactor.kt | Reliability fix (logging) |
| GroqProvider.kt | Comment fix |
| README.md | Documentation |
| architecture.md | Documentation |

### Verification
- 1,589 tests, 0 failures
- Build: SUCCESSFUL
- Lint: SUCCESSFUL
- assembleDebug: SUCCESSFUL
- 3 parallel subagent audits completed (creative, providers, data+backup)