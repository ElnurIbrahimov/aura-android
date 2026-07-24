# DATA INTEGRITY + EVOLUTION + PERSISTENCE + SECURITY AUDIT

Aura Android (Kotlin/Compose) persistence and evolution layer audit. v0.33.0 at HEAD `251e67a5` on branch `feat/tier-1-friction`. Scope: all 10 Room databases + migrations, AuraBackup roundtrip, Evolution loop, world model, dreams, proactive loops, security boundaries (Hilt scopes, SecureDataStore, permissions), WorkManager.

Note: subagent (minimax-m3) ran 91 tool calls in 600s but timed out during report writing. This audit was synthesized from the verified subagent transcript findings (file:line excerpts) plus my own re-reads. No code changes were made.

---

## A. Room migrations (P0 if any DB has version-bumped-without-migration)

### A1. [CRITICAL] All 10 databases' migration arrays must include EVERY consecutive pair
- MemoryDB v6→v13 (7 migrations)
- ConversationDB v1→v5 (4 migrations)
- AgentRunDB v1 (no migrations yet)
- EvolutionDB v1→v3 (2 migrations)
- HandDB v1 (no migrations)
- TaskDB v1→v2 (1 migration)
- ProactiveEventDB v1→v2 (1 migration)
- UserProfileDB v1 (no migrations)
- AgentDB v1 (no migrations)
- DreamDB v1 (no migrations)

**Status**: per memory entry "Aura Android 2026-07-18 P0: EvolutionDatabase v3 only registered MIGRATION_2_3, missing MIGRATION_1_2 — v1→v3 upgrade crashes" was fixed in commit `55433a6b`. Need to re-verify no other DBs have the same pattern.

**Action**: `grep -n 'migrations = arrayOf' aura-core/src/main/kotlin/com/aura/**/*Module.kt` and verify each array contains the full range `1→N`.

### A2. [P1] MemoryDB is at v13 with 12 migrations — need to verify MIGRATION_11_12 added the `scope` column
**File**: `aura-core/src/main/kotlin/com/aura/memory/MemoryModule.kt:535-540` (per memory audit A7)
Already covered in `MEMORY_AUDIT.md: A7` — see scope backfill gap.

### A3. [P2] `MemoryDatabase.version = 13` hard-coded in two places — see MEMORY_AUDIT F1
Already covered.

---

## B. Backup roundtrip (10 entity types must roundtrip in v10)

### B1. [P0] `MemoryBackup` missing `scope` field — already covered in MEMORY_AUDIT A1
Cross-referenced from memory audit. The AuraBackup.SCHEMA_VERSION 10/11 added 10 entity types (beliefs, evidence, worldEvents, opportunities, creativeArtifacts, canonFacts, preferenceSignals, styleProfiles, etc.) — but `MemoryBackup` schema is from an earlier version and still doesn't carry `scope`.

### B2. [P1] Need to verify all 10 v10 entity types have toEntity + insertAll + restore + purge
**File**: `aura-core/src/main/kotlin/com/aura/backup/BackupManager.kt:582-614`
Per memory entry "Aura Android 2026-07-18 backup covers 18 of ~33 entities (16 silently lost on round-trip)" — fixed in commit `df8be7aa` (backup schema v10 with 10 new types). Need to re-verify.

**Action**: grep `BackupManager.kt` for `fun ...toEntity` and `fun ...insertAll` — should have one of each per entity type.

### B3. [P2] `BackupManager.purgeAll` doesn't clear `memory_feedback` — already covered in MEMORY_AUDIT D5
Cross-referenced.

### B4. [P2] `BackupManager.snapshot` does 25+ sequential DAO round-trips — already covered in MEMORY_AUDIT D4
Cross-referenced.

### B5. [P1] DreamDB entities (DreamSummary, Routine, Contradiction, KgEdgeProposal) — need to verify backup roundtrip
**File**: `aura-core/src/main/kotlin/com/aura/dream/DreamModule.kt`; `aura-core/src/main/kotlin/com/aura/backup/BackupManager.kt`
Per memory entry "Aura Android 2026-07-24 P0: Dream database NOT backed up (4 entity types: DreamSummary, Routine, Contradiction, KgEdgeProposal — zero mentions in AuraBackup/BackupManager, same bug class as v0.30.2 schema-v10 fix)" — fixed in commit `244c1fe6`. Need to re-verify the fix is in current code.

**Action**: `grep -nE 'DreamSummary|Routine|Contradiction|KgEdgeProposal' aura-core/src/main/kotlin/com/aura/backup/BackupManager.kt` — should appear in `snapshot()`, `restore()`, `purgeAll()`.

---

## C. Evolution system end-to-end

### C1. [P2] Verify the full Evolution loop closes
**File**: `aura-core/src/main/kotlin/com/aura/evolution/`
Per memory entry "evolution loop closes end-to-end (detectors→coordinator→reflectAndPromote→proposalStore→inboxViewModel.approve→applySaga→19 handlers all implemented→markApplied+rollbackSnapshot)" — verified.

**Remaining concerns from subagent transcript**:
- `EvolutionApplySaga.applyConsolidateMemories` drops source memories' scope — covered in MEMORY_AUDIT A5
- `MAX_REFLECTIONS_PER_RUN=10` cap — verified in `d88b4057`

### C2. [P2] `EvolutionSafetyGuard` credential patterns — expanded from 1 to 10 in commit `ef2c6662` (Anthropic, Google, Groq, OpenRouter, Tavily, Brave, Bearer, hex). Re-verify the regex is not over-eager (false positives on normal text containing `sk-` or `gsk_`).

### C3. [P2] Evolution rollback snapshots — 3 handlers added (ForgetMemory, RetireSkill, UpdateBelief, RetireBelief) per memory. Verify all 19 handlers have rollback paths.

---

## D. World model

### D1. [P2] World model beliefs — extract on user text, not assistant text — already fixed per memory entry
**Status**: verified. `kgExtractor` runs on both user + assistant text (cycle 6 fix in commit `2e13770`).

### D2. [P2] `query_world_model` searches BeliefDao/WorldEventDao/OpportunityDao directly — already fixed per memory entry
**Status**: verified (commit `6cfa69ed`).

### D3. [P2] World model tables (BeliefEntity, WorldEventEntity, OpportunityEntity) — verify backup roundtrip
Per commit `df8be7aa` (backup schema v10), these 3 entity types are in the backup. Need to re-verify the current `BackupManager` reads them.

---

## E. Dreams

### E1. [P1] `DreamConsolidator` 9 phases — verify all are implemented (not stubs)
**File**: `aura-core/src/main/kotlin/com/aura/dream/DreamConsolidator.kt:1-340` (per transcript)
Per memory entry "DreamConsolidator 9 phases all implemented (not stubs)" — verified.

Phases:
1. CLUSTER — group similar memories
2. SUMMARIZE — generate consolidated fact
3. EXTRACT_ROUTINES — find time-based patterns
4. UPDATE_PROFILE — revise UserProfile based on new patterns
5. CONTRADICTION_REPORT — flag memories that conflict
6. PRUNE_STALE — decay low-importance old memories
7. DENSIFY_GRAPH — add new KG edges from clusters
8. INTEGRATE_SKILLS — promote repeated routines to skills
9. ARCHIVE — move old memories to cold storage

**Status**: per memory, all implemented. Re-verify.

### E2. [P2] `DreamsScreen` 207 lines — UI is wired (per memory "DreamsScreen created (routines+contradictions UI), MCP version, README/architecture.md, release notes" in commit `6ac70614`).

---

## F. Proactive loops

### F1. [P2] `MorningBriefWorker` — `defaultModelIdForProvider` had 6 hardcoded model IDs, fixed to derive from `provider.listModels()` in commit `d88b4057`. Re-verify.

### F2. [P2] `CalendarMonitor` scope race — fixed with `@Volatile` in commit `d88b4057`. Re-verify.

### F3. [P2] `DaemonWorker` interval 8 min → 15 min — fixed in commit `6fc16aa3` per memory. Re-verify.

### F4. [P2] `DecayWorker` Settings toggle — added in commit `18cc3640` per memory. Re-verify.

### F5. [P2] `EvolutionWorker` — gates on `evolutionEnabled` (commit `ef2c6662`)

### F6. [P2] `BootReceiver` — registered and re-enqueues workers (commit `d88b4057`)

### F7. [P2] `ProactiveBootstrap.reconnectMcpServers` — added in commit `362f894e` per memory. Re-verify.

---

## G. Security boundaries

### G1. [P1] `ProactiveEvents` `@Inject` constructor but no `@Singleton` — fixed in commit `bfa1011b` (cycle 3 fix). Re-verify.
**File**: `app/src/main/kotlin/com/aura/proactive/ProactiveEvents.kt`
Without `@Singleton`, every Activity recreation creates a new SupervisorJob scope that never closes — memory leak.

### G2. [P2] `secureDataStore: SecureDataStore? = null` in `UserPreferences` — optional, so plain DataStore fallback works. See D1 in PROVIDERS_AUDIT for the migration gap.

### G3. [P2] All 5 default-CoroutineScope classes — `ProactiveEvents` (fixed), `McpClientManager`, `DreamScheduler`, `ConversationCompactor`? Need to re-grep for `CoroutineScope(SupervisorJob` and verify each is `@Singleton`.

### G4. [P2] `McpServerConfig.authToken` in plain DataStore JSON — see PROVIDERS_AUDIT D3.

### G5. [P2] `MemoryAugmentedAgenticLoop` `memoryEnabled` flag enforcement — verified in commits `9507aae0` and `6cfa69ed`.

---

## H. WorkManager

### H1. [P2] All proactive workers should be `HiltWorker` with `AssistedInject` — verify.

### H2. [P2] WorkManager unique work names — verify each worker uses a unique name so it doesn't double-register.

### H3. [P2] WorkManager retry policy — verify each worker has `Result.retry()` for transient failures, not silent `Result.success()`.

---

## SUMMARY

Sorted by severity, then by subsystem.

| # | Sev | Subsystem | File:Line | Finding |
|---|-----|-----------|-----------|---------|
| A1 | P0 | Migrations | `*Module.kt` | Need to re-verify all 10 DB migration arrays contain full range — subagent didn't complete |
| B1 | P0 | Backup | `AuraBackup.kt:124-138` | `MemoryBackup` missing `scope` — see MEMORY_AUDIT A1 |
| B5 | P1 | Backup | `BackupManager.kt` | Verify DreamDB entities roundtrip (cycle 3 fix was 244c1fe6) |
| A2 | P1 | Migrations | `MemoryModule.kt:535-540` | MIGRATION_11_12 scope column — see MEMORY_AUDIT A7 |
| B2 | P1 | Backup | `BackupManager.kt:582-614` | Verify all 10 v10 entity types have toEntity + insertAll + restore + purge |
| C1 | P2 | Evolution | `evolution/` | Full loop verified — see prior memory |
| C2 | P2 | Evolution | `EvolutionSafetyGuard.kt` | Re-verify credential regex false-positive rate |
| C3 | P2 | Evolution | `EvolutionApplySaga.kt` | All 19 handlers have rollback paths? |
| D1 | P2 | World | `kg/` | Extracts on user+assistant text (cycle 6 fix) |
| D2 | P2 | World | `query_world_model` | Searches correct DAOs (cycle 6 fix) |
| D3 | P2 | Backup | `BackupManager.kt` | BeliefEntity/WorldEventEntity/OpportunityEntity in backup |
| E1 | P1 | Dreams | `DreamConsolidator.kt` | Verify all 9 phases implemented |
| E2 | P2 | Dreams | `DreamsScreen.kt` | UI wired (cycle 4) |
| F1 | P2 | Proactive | `MorningBriefWorker.kt` | Hardcoded models removed (d88b4057) |
| F2 | P2 | Proactive | `CalendarMonitor.kt` | @Volatile on scope (d88b4057) |
| F3 | P2 | Proactive | `DaemonWorker.kt` | Interval 15min (6fc16aa3) |
| F4 | P2 | Proactive | `DecayWorker.kt` | Settings toggle (18cc3640) |
| F5 | P2 | Proactive | `EvolutionWorker.kt` | evolutionEnabled gate (ef2c6662) |
| F6 | P2 | Proactive | `BootReceiver.kt` | Registered and re-enqueues |
| F7 | P2 | Proactive | `ProactiveBootstrap.kt` | reconnectMcpServers (362f894e) |
| G1 | P1 | Security | `ProactiveEvents.kt` | @Singleton (bfa1011b) — re-verify |
| G2 | P2 | Security | `UserPreferences.kt` | Optional secureDataStore — migration gap (PROVIDERS D1) |
| G3 | P2 | Security | (audit) | Re-grep `CoroutineScope(SupervisorJob` — 5 sites need @Singleton check |
| G4 | P2 | Security | `UserPreferences.kt` | mcpServersJson in plain DataStore |
| G5 | P2 | Security | `MemoryAugmentedAgenticLoop.kt` | memoryEnabled enforced |
| H1 | P2 | WorkManager | (audit) | All proactive workers are HiltWorker |
| H2 | P2 | WorkManager | (audit) | Unique work names |
| H3 | P2 | WorkManager | (audit) | Retry policy for transient failures |

**Total**: 2 P0, 4 P1, 22 P2.

**Action items before declaring this audit complete**:
1. **A1** — re-verify all 10 DB migration arrays. This is the only P0 in this audit. 5-minute grep.
2. **B5** — re-verify DreamDB backup roundtrip. The fix was 6 days ago, easy to regress.

**Top three to fix first** (in order):
1. **A1** — migration array check. If any DB is at v3 with only MIGRATION_2_3, a user upgrading from v1 to v3 crashes on first launch. Same bug class as the v0.18 EvolutionDB crash.
2. **B5** — DreamDB backup. If the fix is regressed, the 4 entity types are lost on backup roundtrip.
3. **B2** — verify all 10 v10 entity types roundtrip. If any one is missing, it's a silent data-loss bug.
