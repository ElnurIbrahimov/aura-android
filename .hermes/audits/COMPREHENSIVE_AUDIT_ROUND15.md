# COMPREHENSIVE AUDIT — Round 15 (FINAL)

**Project:** Aura Android (D:\aura-android-clean)
**Branch:** feat/tier-1-friction
**Head:** bf641683 (v0.59.0, versionCode 72)
**Date:** 2026-08-05
**Model:** glm-5.2 via Ollama Cloud
**Method:** 3 parallel subagent deep audits (agent loop/providers/MCP, memory/data/backup, UI/proactive/evolution) + manual cross-cutting review

---

## PROJECT STATE

| Metric | Value |
|---|---|
| Kotlin files (main) | 572 |
| Kotlin files (test) | 327 |
| Lines of code (main) | ~84K |
| Lines of code (test) | ~36K |
| Unit tests | 1,821 (0 failures) |
| Test files | 303 |
| Tools | 70 |
| LLM providers | 17 |
| Room databases | 11 |
| Room entities | 54 |
| Room migrations | 33 (across 11 databases) |

---

## PHASE 17 — FINAL SCORECARD

| Category | Score | Notes |
|---|---|---|
| Architecture | **9/10** | Clean 2-module separation, Hilt DI, no circular deps |
| Code Quality | **8.5/10** | Excellent KDoc, consistent naming, 173 silent runCatching sites |
| Maintainability | **8/10** | BackupManager (862L), ChatViewModel (1077L), agentic loop (1218L) are oversized |
| Performance | **8/10** | Parallel tools, recall caching, bounded parallelism. Channel.BUFFERED overflow risk |
| Scalability | **8/10** | 11 Room DBs, 33 migrations. 10K-row decay pass loads ~55MB |
| Security | **9/10** | AES-256-GCM, SsrfGuard, DNS-pinned, no redirects, BIOMETRIC_STRONG, PKCE OAuth |
| Reliability | **8/10** | 1821 tests, 0 failures. Council backup drops relationships/observations silently |
| Developer Experience | **8/10** | CI with lint gate, version drift check. Low Turbine usage |
| User Experience | **8.5/10** | Teal theme, thinking blocks, streaming cursor. 778 hardcoded dp values |
| Documentation | **7.5/10** | README stale (v0.58 vs v0.59). Otherwise excellent KDoc |
| Testing | **8/10** | 1821 tests, migration tests, contract tests. 4 untested VMs |
| Accessibility | **7/10** | contentDescription on IconButtons. No TalkBack audit |
| Infrastructure | **8.5/10** | CI on every push, 45min timeout, cache strategy, release build |
| Deployment | **8/10** | GitHub Releases with APKs. Tags stale (latest v0.52.0) |
| **Overall Engineering** | **8.3/10** | Production-grade for a personal-use app |
| **Overall Product** | **9/10** | 70 tools, 17 providers, creative studio, council, evolution, consciousness |

---

## CONSOLIDATED FINDINGS (across all 3 subagent reports + manual review)

### P0 — CRITICAL (6 findings)

**P0-1. ChatGPT subscription `system` role not mapped to `developer`**
- File: `ChatGptSubscriptionProvider.kt:75-86`
- The Responses API expects `developer` for system messages. The provider passes `msg.role.name` directly, sending `system` — which causes a 400 on every ChatGPT Plus/Pro call.
- Fix: Map `system → developer` before building the `input` array.

**P0-2. `council/{convId}` route referenced but not declared — crash on tap**
- File: `ChatRoute.kt:417` navigates to `council/$convId`, but `NavGraph.kt:371` only declares `composable("council")` with no argument
- The "Open Council" button in chat crashes with `IllegalArgumentException` on every tap.
- Fix: Declare `composable("council?convId={convId}")` with nullable string arg, thread it to CouncilViewModel.

**P0-3. Council backup silently drops ALL relationships and observations**
- File: `BackupManager.kt:313-314`
- Calls `forAgent("__all__")` on `AgentRelationshipDao` and `AgentObservationDao`, but these DAOs have no `all()` method — the query `WHERE agentAId = '__all__'` returns 0 rows.
- Every backup since Council shipped has `agentRelationships: []` and `agentObservations: []`.
- Fix: Add `allOnce()` methods to both DAOs, use them in snapshot.

**P0-4. `BackupManager.restore()` is NOT in a Room transaction**
- File: `BackupManager.kt:357-548`
- ~50 sequential `dao.insertAll` calls with no `@Transaction` wrapping. Process death or `Error` subclasses bypass the try/catch + purgeAll fallback.
- Fix: Wrap the entire restore in `db.withTransaction { ... }` or `beginTransaction/setTransactionSuccessful/endTransaction`.

**P0-5. Council preferences missing from backup**
- Files: `AuraBackup.kt:362-419`, `BackupManager.kt:199-245, 550-614`
- `councilEnabled`, `councilAutoApply`, `councilActivityLevel` exist in UserPreferences but are NOT in `PreferencesBackup`, `snapshotPreferences()`, or `restorePreferences()`.
- Fix: Add 3 fields to PreferencesBackup + 3 lines to snapshot + 3 lines to restore.

**P0-6. README + architecture.md version drift**
- README says v0.58.0/69 tools/295 tests/1759 tests. Actual: v0.59.0/70 tools/303 files/1821 tests.
- Fix: Update both files.

### P1 — HIGH (25 findings from subagent reports)

**Agent Loop (11 findings):**
1. Failover step counter not in trace events — can't tell how many provider attempts a step took
2. `ensureActive()` only after step+=1 — wasted recall on cancelled flows
3. `pendingPermissions` holds entire Conversation snapshot indefinitely — memory leak
4. `denyPendingPermission` doesn't surface denial to suspended run() collector
5. `filterSearchTools` is a degenerate filter — only excludes by name, never checks `providerKeys.isConfigured()`
6. `lastRecall` stale after incognito flip — chip shows recall from before incognito
7. MoA-only config silently skips write gate — memories never stored
8. LLM profile extraction 200-token cap too small for multi-fact sentences
9. `cachedCheapModel` failure re-triggers network every step
10. `cachedRecall` key ignores `recallLimit` (latent)
11. Planning step timeout confirmed present and correct (verified-OK)

**Providers (8 findings):**
1. Brain: Anthropic thinking budget > maxTokens — API rejects when caller sets explicit maxTokens=200
2. Brain: `fromProvider` last-resort id fallback can mis-route parallel deltas
3. Brain: Unknown model prefix throws with no fallback to first configured provider
4. OpenAI-compat: `Channel.BUFFERED` (64) overflow drops chunks for fast-streaming models
5. OpenAI-compat: `sourceHolder.cancel()` race during brief window before first `onEvent`
6. ChatGPT: `toolCallCounter` shared across concurrent calls on singleton
7. OllamaCloud: `/api/show` called serially for every model (10-50s blocking on startup)
8. OllamaCloud: `think` param sent to OpenAI-compat endpoint, silently ignored
9. MoA: Reference phase has no overall timeout — one hung reference model hangs the run
10. MoA: `runReference` exception swallowed, `isError` not set — failed reference silently treated as empty
11. ProviderRegistry: MoA usage under-counted (3 API calls recorded as 1)

**ToolExecutor (3 findings):**
1. `runInterruptible` can't preempt truly blocking I/O (Thread.sleep, synchronous OkHttp)
2. `RemoteCostApprovalGate` is dead code in production (policy engine handles REMOTE_COST differently)
3. Approval string matching too restrictive & locale-unaware ("yeah", "ok", "si" don't match)

**MCP (5 findings):**
1. `McpConnection._health` writes not synchronized — corrupts UI health dashboard
2. `authToken` in data class risks Room persistence if any caller serializes it
3. Response size check by char count, not byte count — OOM-able from large UTF-8 response
4. `callTool` uses hardcoded 30s timeout, ignores per-tool `ctx.timeout`
5. SSE response body parsed as single JSON object — multi-event SSE responses corrupted

**NavGraph (3 findings):**
1. `popBackStack()` is a no-op when back stack is empty (22 composables) — deep-link trap
2. 11+ raw string route literals — typo-prone, should be sealed class
3. 4 secondary screens (`hands`, `tools`, `proactive`, `agent_runs`) have no `onBack` wiring

**Proactive (5 findings):**
1. `EmotionEngine` updated on every message but snapshot never reaches system prompt — "adaptive tone" is documented but not implemented
2. EmotionEngine/AgentPresence/CouncilOrchestrator have NO user-facing toggles — ride daemon gate
3. `setDreamEnabled`/`setDecayEnabled`/`setDaemonEnabled`/`setTriggersEnabled` setters exist but no Settings UI invokes them
4. `CalendarMonitorService` doesn't check `READ_CALENDAR` permission before starting
5. `DecayWorker` runs on every cold start with no throttle

**Evolution (4 findings):**
1. Non-auto-apply users have no "Apply now" button — approved proposals sit forever
2. `EvolutionRollbackManager` has empty bodies for 5 actions (ENABLE_RULE, DISABLE_RULE, etc.)
3. SafetyGuard runs at proposal time, not at apply time — no re-validation
4. EvolutionWorker has no battery constraint

**Creative (4 findings):**
1. `WorldBibleEditor.kt` exists but is not composed in any screen — dead UI
2. `CreativeEngineTool` creates projects the chat agent has no reference to
3. `VoiceCalibration`/`TensionAnalyzer`/`CharacterProgressionTracker` VM state never read by UI
4. `CreativeBranchStore` injected but has no UI consumer

**Data/Memory (8 findings):**
1. `MemoryStore.maybeStore` holds mutex during embedding API call — serial bottleneck
2. `MemoryStore.touch()` called in sequential loop — 5 UPDATEs per recall
3. `MemoryStore.rebuildEmbeddings()` is N×API on cold start, no auto-trigger after restore
4. `MemoryStore.runDecayPass` loads 10,000 rows into memory at once (~55MB)
5. `MemoryStore.update()` writes phantom audit row on no-op saves
6. `MemoryStore.searchByText` is NOT scope-aware — agent-scoped memories invisible in Memory screen
7. `MemoryFeedbackEntity` written but never read in production (only backup + tests)
8. `BackupManager.restoreReminders` corrupts `firedAt` for pre-schema reminders

### P2 — MEDIUM (30+ findings)

- `dall-e-3` hardcoded as default image model
- `latencyMs = 0L` — taste routing never records actual latency
- `GlobalScope.launch` in AskAuraWidget
- 4 untested ViewModels (SettingsViewModel, HomeViewModel, ProactiveHistoryViewModel, ProductionPipelineViewModel)
- 162 hardcoded `Color(0x...)` values
- 778 hardcoded `dp` values
- 15 orphaned Compose components (0-2 callers)
- No `key()` in LazyColumn items, only 2 `derivedStateOf`
- Release tags stale (latest v0.52.0)
- Chart files hardcode 6 hex colors
- `Shapes.kt` abstraction is dead
- `ToolRegistry.definitions()` races `register()` (ConcurrentHashMap view iteration)
- MCP bridge re-register on reconnect doesn't clean up old names
- `syncTools` and `syncToolsUnprefixed` can double-register the same tool
- MoA `activeJob` never cleared on completion
- Various P2 hygiene items from all 3 reports

---

## PHASE 18 — MASTER IMPROVEMENT PLAN

### Immediate fixes (this session, <1h each)

| # | Finding | Effort |
|---|---|---|
| 1 | P0-1: Map `system → developer` in ChatGptSubscriptionProvider | 5min |
| 2 | P0-2: Add `council?convId={convId}` route to NavGraph | 15min |
| 3 | P0-3: Add `allOnce()` to AgentRelationshipDao + AgentObservationDao | 15min |
| 4 | P0-5: Add 3 council prefs to PreferencesBackup + snapshot + restore | 20min |
| 5 | P0-6: README + architecture.md version sync | 5min |
| 6 | P1: Anthropic thinking budget > maxTokens clamp | 10min |
| 7 | P1: Wire `EmotionEngine.snapshot()` into system prompt | 15min |
| 8 | P1: Add `onBack` to 4 secondary screens in NavGraph | 5min |
| 9 | P1: EvolutionInbox "Apply now" button | 20min |
| 10 | P1: Remove `dall-e-3` default, use null | 5min |

### Short-term improvements (next session, 1-4h each)

| # | Finding | Effort |
|---|---|---|
| 11 | P0-4: Wrap BackupManager.restore() in Room transaction | 2h |
| 12 | P1: Fix `filterSearchTools` to actually check `providerKeys.isConfigured()` | 30min |
| 13 | P1: Fix MoA `runReference` error swallowing | 30min |
| 14 | P1: Fix `Channel.BUFFERED` overflow — use `Channel.UNLIMITED` or larger buffer | 30min |
| 15 | P1: Add `emotionEnabled`/`presenceEnabled` user toggles | 1h |
| 16 | P1: Add Settings UI for dream/decay/daemon/triggers toggles | 1h |
| 17 | P1: CalendarMonitorService permission check | 15min |
| 18 | P1: Wire VoiceCalibration/TensionAnalyzer/CharacterProgressionTracker to UI | 2h |
| 19 | P1: Wire WorldBibleEditor into CreativeProjectScreen | 1h |
| 20 | P1: Tests for 4 untested ViewModels | 2h |
| 21 | P1: Audit 173 silent runCatching sites | 2h |
| 22 | P1: MCP `callTool` should use `ctx.timeout` not hardcoded 30s | 15min |
| 23 | P1: MCP response size check by bytes, not chars | 15min |

### Medium-term refactors (future sessions, 4h+ each)

| # | Area | Effort |
|---|---|---|
| 24 | Split ChatViewModel (1077 lines) — move 23 @Inject params to Dependencies data class | 4h |
| 25 | Split BackupManager (862 lines) into per-database snapshot/restore | 4h |
| 26 | Split MemoryAugmentedAgenticLoop (1218 lines) | 6h |
| 27 | Promote all route strings to sealed class Route | 2h |
| 28 | Color/dp token ratchet (162 colors + 778 dp → tokens) | 3h |
| 29 | Add `key()` to LazyColumn items + `@Immutable` to UiState | 2h |
| 30 | MemoryStore: batch `touch()` into single IN-clause UPDATE | 1h |
| 31 | MemoryStore: page `runDecayPass` instead of loading 10K rows | 1h |
| 32 | OllamaCloud: parallelize `/api/show` model catalog calls | 1h |
| 33 | Consolidate 11 Room databases into fewer (risky) | 8h+ |

---

## VERIFIED WORKING (35 prior fixes confirmed in place)

All major fixes from Rounds 1-14 are still present and correct: SQLite ESCAPE clauses, migration chains, StrategyBandit backup restore, embedding fields in backup, council backup (schema v16, but relationships/observations broken — see P0-3), SsrfGuard + DNS-pinned clients, base OkHttp no redirects, keyForAwaiting on all 7 chat providers, SSE stream timeouts, empty response error, parallel tool execution, tool result truncation, tool timeout, Brain maxTokens inflation guard, ConversationCompactor real context window, BackupManager try-catch with purgeAll (but not transaction — see P0-4), RoomConfig builder, evolution loop closure (19 handlers + rollback), proactive event infinite loop guard, REMOTE_COST approval loop, MCP tool bridge + persistence, specialist allowlist security, extended thinking on all 17 providers, OAuth PKCE, SMTP in SecureDataStore, search tool filtering.

---

*Subagent reports: ROUND15_AGENT.md (651 lines, 37 findings), ROUND15_DATA.md (726 lines, 24 findings), ROUND15_UI_PROACTIVE.md (582 lines, 21 findings). Total: 82 unique findings across 1959 lines of subagent audit reports.*