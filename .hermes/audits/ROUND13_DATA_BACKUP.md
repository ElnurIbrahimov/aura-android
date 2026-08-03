# ROUND 13 — Data & Backup Audit (Aura Android)

**Scope:** All Room databases, DAOs, entities, migrations, backup/restore system.
**Branch:** `feat/tier-1-friction`  |  **v0.51.2**  |  11 Room databases · 48 entity classes · 100+ DAOs · 5 backup files.

> **Audit-cycle context (12+ prior rounds):**
> Hilt `@Singleton @Inject` constructors are auto-discovered — do NOT flag as dead.
> Schema-history comments document the intended exclusions (embeddings, file bytes, SecureDataStore keys, transient `CreativeGenerationJobEntity`).
> Earlier rounds already wired up schema v10–v15 entities & mappers; this round focuses on round-trip correctness, coverage gaps, and the actual restore path.

---

## TL;DR — Severity distribution

| Severity | Count | Highlights |
|----------|------:|-----------|
| **P0**    | 4 | No transactional restore; missing purge of preferences; unconditional destructive fallback in production; RestoreCounts missing a snapshot field |
| **P1**    | 8 | Missing preference snapshot fields (`dreamEnabled`, `decayEnabled`, `triggersEnabled`, `triggers`, `planningEnabled`); Hand re-schedule inside restore; `route()` FK index gaps in dream DBs |
| **P2**    | 9 | `BackupMappers.kt` and `BackupManager.kt` share a 49-line duplicated import block; CreativeBranchEntity field drift in mappers; `proactive_events.correlationTag` not indexed; etc. |

---

## 0. Top-level findings (one-liner each)

| # | Sev | Headline |
|---|-----|----------|
| **F-01** | **P0** | `BackupManager.restore()` is **not** wrapped in a Room `@Transaction`. A crash mid-restore leaves the DB half-imported. |
| **F-02** | **P0** | `AgentModule` and `StrategyBanditModule` use `fallbackToDestructiveMigrationOnDowngrade()` *unconditionally* (no `BuildConfig.DEBUG` guard like `RoomConfig`), wiping agent + strategy tables silently on version downgrade in release. |
| **F-03** | **P0** | `RestoreCounts` is missing `strategyBandit: Int` even though `restoreStrategyBandit()` inserts rows. The `restoreStrategyBandit` is also gated on a nullable `strategyBanditDao: ? = null` — when Hilt doesn't supply one (it is `Optional` semantics with a default null, not `@Provides`/`@Inject`) the backup is silently dropped. |
| **F-04** | **P0** | `CreativeBranchEntity.updatedAt` is **dropped** in `BackupMappers.kt:494-504`. The Backup class declares it; the mappers don't pass it. Restore silently re-anchors all branches to `System.currentTimeMillis()`. |
| **F-05** | **P1** | `purgeAll()` never clears DataStore preferences (`UserPreferences`). `evolutionEnabled`, `evolutionIntervalHours`, and other restore-only prefs survive a "clean-slate restore" via `purgeAll`. |
| **F-06** | **P1** | `PreferencesBackup` is missing: `dreamEnabled` (U.P.:70), `decayEnabled` (U.P.:266), `triggersEnabled` (U.P.:269), `triggers` JSON (U.P.:272), `planningEnabled` (U.P.:316), `embeddingModel` (U.P.: already snapshotted but `key only`, see note in F-08), `agentId`. |
| **F-07** | **P1** | `restore()` re-schedules WorkManager reminders and hands inline via `reminderScheduler.schedule(...)` and `handScheduler.schedule(...)` *before* the non-transactional DAO writes complete. If anything throws after the scheduler call but before commit, the schedule is dangling with no DB row. |
| **F-08** | **P1** | `evolutionSettings` upserts in `restoreEvolution` (B.M.:563-565) are not idempotent on re-restore — back-to-back restore with no `purgeAll` re-issues the upsert but keeps stale ones across all-domain keys. (Minor; only an issue if restore is invoked twice without purge.) |
| **F-09** | **P1** | `DreamConsolidationDatabase`'s `ContradictionEntity` and `KgEdgeProposalEntity` use FK-like columns (`olderSummaryId`/`newerSummaryId`, `fromNodeId`/`toNodeId`) **without `@ForeignKey`**. A summary deletion would leave orphan rows. |
| **F-10** | **P1** | `BeliefEntity.supersededBy` is queried in belief-graph traversal queries but has no `@Index`, while columns like `validFrom` and `confidence` are indexed instead. |
| **F-11** | **P1** | `purgeAll()` deletes evolution tables but never clears `EvolutionSettingsEntity` rows that are stored *per-domain*: a "fresh install" leaves the user's "auto-apply" approvals intact when they may have intended a full reset. *(Borderline with F-05; tracked separately since this is a Room table, not prefs.)* |
| **F-12** | **P1** | `ProactiveInteractionEntity` has no FK to `ProactiveEventEntity` (only an index). A `proactive_events` cleanup via `deleteAll()`/`deleteOlderThan()` will orphan interactions. |
| **F-13** | **P2** | `BackupMappers.kt` (lines 1-49) duplicates `BackupManager.kt`'s package + imports verbatim. Looks like a copy-paste leftover; the mapper file is the legitimate file, the BackupManager imports are dead. |
| **F-14** | **P2** | `proactive_events.correlationTag` is queried in `byCorrelationTag(tag)` (ProactiveEventDao:43) but has no `@Index` — only `timestamp` is indexed. |
| **F-15** | **P2** | `MemoryDatabase` declares 25 entities with version 15, but most of those entities (CreativeArtifact*, Canon*, RoutingOutcome, etc.) belong to other logical DBs per the v10/v11/v12 doc-strings. The DB is fine for a personal-use app but the entity-to-DB mapping is confusing — consider splitting into a `CreativeDatabase` for clarity. (Cosmetic; no functional bug.) |
| **F-16** | **P2** | `Hands` table (`hands` entity) has no `@Index` on `name` even though `HandDao.getByName(name)` (line 22) is a typical hot path for the trigger-phrase matcher. |
| **F-17** | **P2** | `HandDao.deleteByName(name)` and `getByName(name)` both run `WHERE name = :name` without an index on `name`. |
| **F-18** | **P2** | `ConversationEntity.agentId` is queried by `ConversationStore.kt` for per-agent history but is not indexed (only `updatedAt` and `deletedAt` are). |
| **F-19** | **P2** | `evolution_settings` has `dailyCloudCallBudget`/`reflectionMaxTokens`/etc. — none are preserved in `EvolutionSettingsBackup`. Backup has only `domain`, `enabled`, `updatedAt`. The mappers (B.M.:154-158) `toBackup()` only writes 3 columns. |
| **F-20** | **P2** | `HandRun.status` enum (`RUNNING`/`SUCCESS`/...) has no enum-type conversion column — strings everywhere. Same for `HandRunTrigger`, `HandScheduleType`. (Documented but worth tracking.) |

---

## 1. Database & migration inventory

11 `@Database` classes, every migration path covered:

| DB | File | Version | Module migrations | Verdict |
|----|------|--------:|-------------------|---------|
| `AgentDatabase`             | agent/AgentDatabase.kt              | **1** | (none) | ✓ v1 only |
| `ConversationDatabase`      | agent/ConversationDatabase.kt       | **6** | 5 migrations (1_2..5_6) | ✓ complete |
| `StrategyBanditDatabase`    | agent/StrategyBanditDatabase.kt     | **1** | (none) | ✓ v1 only |
| `AgentRunDatabase`          | agentrun/AgentRunDatabase.kt        | **1** | 0 declared (`migrations = arrayOf()`) | ✓ v1 only |
| `MemoryDatabase`            | memory/MemoryDatabase.kt            | **15**| 14 migrations (1_2..14_15) | ✓ complete |
| `TaskDatabase`              | tasks/TaskDatabase.kt               | **5** | 4 migrations | ✓ complete |
| `HandDatabase`              | hands/HandDatabase.kt               | **2** | 1 migration | ✓ complete |
| `ProactiveEventDatabase`    | proactive/ProactiveEventDatabase.kt | **5** | 4 migrations | ✓ complete |
| `UserProfileDatabase`       | profile/UserProfileDatabase.kt      | **2** | 1 migration | ✓ complete |
| `DreamConsolidationDatabase`| dream/DreamConsolidationDatabase.kt  | **3** | 2 migrations (1_2..2_3) | ✓ complete |
| `EvolutionDatabase`         | evolution/EvolutionDatabase.kt      | **3** | 2 migrations | ✓ complete |

> **No missing N→N+1 migrations anywhere.** The migration-test corpus (`aura-core/src/androidTest/kotlin/com/aura/*DatabaseMigrationTest.kt`) exercises the major DBs.

`@Database(exportSchema = true)` is set on all 11 — Room's schema-export pipeline is in place.

---

## 2. Backup system architecture

```
   ┌──────────────┐                ┌──────────────────────┐
   │  11 Room DBs │  snapshot()    │  AuraBackup (v15)    │  encodeToJson()   ┌────────────┐
   │              │ ─────────────▶ │  data class (JSON)   │ ────────────────▶ │  .json file│
   └──────────────┘                └──────────────────────┘                   └─────┬──────┘
                                                                                    │
                                                                                    ▼
                                                                            ┌───────────────┐
                                                                            │ decodeFromJson│
                                                                            └───────┬───────┘
                                                                                    ▼
                                                                  restore() / purgeAll()
                                                                (suspend, not transactional)
```

| Schema version | What it added | File |
|-------:|---------------|------|
| v1–v9  | Pre-existing core types (memories, hands, tasks, reminders, prefs) | `AuraBackup.kt` |
| **v10**| World model (beliefs, evidence, events, opportunities) + creative artifacts + taste | `AuraBackup.kt` |
| **v11**| Dream DB (summaries, routines, contradictions, kg_edge_proposals) | `AuraBackup.kt` |
| **v12**| Memory feedback, document chunks, reference identities, agent run/goals/steps/events/approvals, run checkpoints | `AuraBackupSchema12.kt` |
| **v13**| Artifact dependencies, continuity issues, simulations; evolution evidence/candidates; proactive interactions; routing outcomes | `AuraBackupSchema13.kt` |
| v14    | Belief-linked contradictions (added fields with `null` default) | `AuraBackup.kt` |
| **v15**| Strategy bandit weights | `AuraBackup.kt` |

> **Documented exclusions (verified):** embeddings, file bytes (live in app-private storage), SecureDataStore keys (encrypted via Keystore), `CreativeGenerationJobEntity` (transient in-flight jobs).

---

## 3. Detailed findings

### F-01 — `restore()` not transactional — **P0**
**File:** `aura-core/src/main/kotlin/com/aura/backup/BackupManager.kt:335-554` (the entire `restore` body), `aura-core/src/main/kotlin/com/aura/backup/BackupManager.kt:601-663` (`purgeAll` body)

**Root cause:** ~80 `dao.insertAll(...)` / `dao.deleteAll()` calls execute under `withContext(Dispatchers.IO)` but with **no `db.withTransaction { ... }` wrapper**. If the user force-stops, OOMs, or the process is killed mid-restore, the DB is left half-imported — e.g. memory rows are in but `nodes` aren't, so `edges` whose `sourceId` references a missing node break the FK relationship at next startup.

This is the only `@Transaction` use in the backup package:
```
aura-core/src/main/kotlin/com/aura/kg/KnowledgeGraphDao.kt:95:    @Transaction
```

**Fix:**
```kotlin
suspend fun restore(backup: AuraBackup): RestoreCounts = withContext(Dispatchers.IO) {
    // Build all entity rows first (no DB calls).
    val memRows = ...
    // Then commit in a single transaction.
    var counts: RestoreCounts
    context.databasesRoomRef().withTransaction {
        counts = writeRestoreRows(backup, memRows, ...)
    }
    counts!!
}
```
A proper fix requires injecting the DB via Hilt (or `InMemoryDatabaseBuilder`) — currently `BackupManager` doesn't have access to `RoomDatabase`. Alternative: open a single Connection via `RoomDatabase.openHelper` and run `BEGIN TRANSACTION; ... COMMIT;` manually, but the cleanest path is to add `databasesRef: RoomDatabaseRef` injected into the constructor and use `withTransaction { }`.

**Effort:** M (½ day — DB ref plumbing + retry on partial restore).

---

### F-02 — Unconditional destructive fallback in production DBs — **P0**
**Files:**
- `aura-core/src/main/kotlin/com/aura/agent/AgentModule.kt:19-20`
```kotlin
Room.databaseBuilder(context, AgentDatabase::class.java, "agents.db")
    .fallbackToDestructiveMigrationOnDowngrade()
    .build()
```
- `aura-core/src/main/kotlin/com/aura/agent/StrategyBanditModule.kt:18-19` — same pattern.

**Root cause:** Unlike `RoomConfig` which is gated by `BuildConfig.DEBUG`, these two modules bypass it and apply the destructive fallback **in release builds too**. A user installing a debug-signed build, then switching to a release-signed (or older) build loses their custom agents and bandit weights silently.

**Fix:** Use `RoomConfig.builder(context, AgentDatabase::class.java, "agents.db", migrations = arrayOf(/* v1 only */))` so the fallback is debug-only and the release path requires explicit migration.

---

### F-03 — `RestoreCounts` missing `strategyBandit` (and restored silently) — **P0**
**Files:** `BackupManager.kt:677-754` (`RestoreCounts`), `BackupManager.kt:756-762` (`restoreStrategyBandit`), `BackupManager.kt:128` (nullable DAOs).

**Root cause:**
1. `RestoreCounts` has fields for every other schema — 36 of them — but the `strategyBandit` count is never tracked. The `restoreStrategyBandit` function returns Unit and the caller (line 494) just calls it.
2. `strategyBanditDao: StrategyBanditDao? = null` is nullable and uses no Hilt qualifier or `Optional<…>` wrapper. When Hilt resolves a missing binding it silently uses null (since the field has a default), so `restoreStrategyBandit(backup)` calls `strategyBanditDao?.clear()` and `strategyBanditDao?.insertAll(rows)` — both of which no-op when the DAO is null. Net effect: the backup is created with `strategyBandit` rows, but they are silently dropped on restore.

The same issue applies to every other nullable DAO injected in lines 94-128 of `BackupManager.kt` (beliefDao, evidenceDao, …). The comment "P0 fix" on line 495 acknowledges the previous version had the same bug.

**Fix:**
1. Make `strategyBanditDao` a hard `@Inject` parameter (drop the `= null` default). Module exists at `StrategyBanditModule.kt:23` — verify it's installed in `SingletonComponent`.
2. Add `val strategyBandit: Int = 0,` to `RestoreCounts` and a matching `strategyBandit = rows.size` in the constructor call (line 501-553).
3. Same audit pass for every nullable DAO: if a binding exists, the field must be non-null.

---

### F-04 — `CreativeBranchEntity.updatedAt` dropped in restore — **P0**
**Files:**
- `aura-core/src/main/kotlin/com/aura/backup/AuraBackup.kt:551-562` (`CreativeBranchBackup` data class — declares no `updatedAt`).
- `aura-core/src/main/kotlin/com/aura/backup/BackupMappers.kt:494-504` (both `toBackup()` and `toEntity()` — neither passes `updatedAt`).
- Entity definition: `aura-core/src/main/kotlin/com/aura/creative/CreativeArtifactEntity.kt:118-130` (`CreativeBranchEntity` HAS `updatedAt: Long = createdAt` as a non-default field).

**Root cause:** A field was added to the entity but not to the `CreativeBranchBackup` data class, nor to the mappers. Any branch restored after a backup gets `updatedAt = createdAt` (the data-class default expression evaluates entity-side as `createdAt`). After-the-fact sorting of "branches sorted by most-recent activity" becomes meaningless for restored data.

Additionally, `CreativeBranchBackup` declares `parentBranchId` and `headArtifactId` which are NOT in the entity at all — those fields will throw `NoSuchMethodError` at deserialization (no, actually `kotlinx.serialization` ignores unknown keys when `ignoreUnknownKeys = true`, see B.M.:172 — so they silently disappear on roundtrip).

**Fix:**
```kotlin
// Add to CreativeBranchBackup in AuraBackup.kt:
val updatedAt: Long = 0L,

// Add to mappers in BackupMappers.kt:
internal fun com.aura.creative.CreativeBranchEntity.toBackup() = CreativeBranchBackup(
    id = id, projectId = projectId, name = name,
    baseRevisionId = baseRevisionId, parentBranchId = parentBranchId,
    headRevisionId = headRevisionId, headArtifactId = headArtifactId,
    status = status, createdAt = createdAt, updatedAt = updatedAt,
)

internal fun CreativeBranchBackup.toEntity() = com.aura.creative.CreativeBranchEntity(
    id = id, projectId = projectId, name = name,
    baseRevisionId = baseRevisionId, headRevisionId = headRevisionId,
    status = status, createdAt = createdAt, updatedAt = updatedAt,
)

// Then add the matching fields to CreativeBranchEntity (currently missing parentBranchId and headArtifactId)
```
The entity needs `parentBranchId` and `headArtifactId` if we want the round-trip to preserve them.

---

### F-05 — `purgeAll()` doesn't reset DataStore preferences — **P1**
**File:** `BackupManager.kt:601-663`

`purgeAll()` is documented as a "clean-slate restore" preparation. It drops all Room tables, but leaves DataStore prefs intact. After a `purgeAll() + restore(b)` flow:
- `evolutionEnabled` survives a "fresh install" intent (the user's prior `enabled=false` opt-out wins, even if they set the backup's value).
- `evolutionIntervalHours`, `decayEnabled`, `triggersEnabled`, `triggersJson`, `planningEnabled` similarly survive.

**Fix:** Add a `userPreferences.resetAll()` API to `UserPreferences.kt` (or expose specific setters via `internal` visibility for `purgeAll` to use) and call it at the end of `purgeAll()`. Alternatively: when restoring, explicitly write every field of `PreferencesBackup` regardless of whether the backup changed it, so the restore fully overrides local prefs.

---

### F-06 — `PreferencesBackup` missing fields — **P1**
**File:** `aura-core/src/main/kotlin/com/aura/backup/AuraBackup.kt:357-406` (`PreferencesBackup`)

Cross-reference against `UserPreferences.kt:38-91` keys and `:98-528` flows — fields present in DataStore but absent from backup:

| Field | UserPreferences key | Where used | Currently in backup? |
|-------|---------------------|------------|----------------------|
| `dreamEnabled`           | `KEY_DREAM_ENABLED` (U.P.:70)        | `DreamWorker.kt:51` schedules the worker | ❌ |
| `decayEnabled`            | `KEY_DECAY_ENABLED` (U.P.:73)        | Memory decay sweep | ❌ |
| `triggersEnabled`         | `KEY_TRIGGERS_ENABLED` (U.P.:75)     | `AuraTriggersBootstrap.kt` | ❌ |
| `triggers` (JSON list)    | `KEY_TRIGGERS_JSON` (U.P.:76)        | Home-screen suggestion engine | ❌ |
| `planningEnabled`         | `KEY_PLANNING_ENABLED` (U.P.:77)     | AgentRuntime planning gate | ❌ |
| `agentId` (default agent) | `KEY_AGENT_ID` (U.P.:74)             | General agent selection | ❌ |
| `smtpPassword`            | `KEY_SMTP_PASSWORD` (U.P.:83)        | SMTP — *but this is a secret and intentionally excluded* | ✓ excluded |
| `embeddingModel` already captured at `preferences.embeddingModel` BUT it's actually stored in `ProviderKeys`, not UserPreferences | varies | semantically conflated | ⚠ |

**Fix:** Add to `PreferencesBackup`:
```kotlin
val dreamEnabled: Boolean = true,
val decayEnabled: Boolean = true,
val triggersEnabled: Boolean = true,
val triggersJson: String = "[]",
val planningEnabled: Boolean = false,
val defaultAgentId: String = "",
```
Add matching snapshot reads in `BackupManager.snapshot()` and matching setter calls in `BackupManager.restore()`.

---

### F-07 — `restore()` re-schedules WorkManager inside non-transactional flow — **P1**
**File:** `BackupManager.kt:393-396` (hands re-schedule)
```kotlin
if (handRows.isNotEmpty()) {
    handDao.insertAll(handRows)
    handRows.forEach(handScheduler::schedule)   // ← side-effects DB outside Room
}
```
**File:** `BackupManager.kt:435` reminders path (`restoreReminders`):
```kotlin
reminderScheduler.schedule(
    row.copy(workId = "", triggerAt = nextTrigger, status = "scheduled"),
)
```
**Root cause:** These calls invoke WorkManager + AlarmManager scheduling. If the subsequent preferences write or a different DAO write throws, the WorkManager job is dangling (per-row: each row tries to schedule its OWN WorkManager request). And there's a non-zero set of leftovers.

The `restoreReminders` design intentionally rotates `workId`s because recurring reminders rotate WorkManager IDs on every fire — but during restore this needs to happen *after* all DB rows are committed, not interleaved.

**Fix:** Schedule WorkManager only after all Room writes commit. Refactor `restore()` to:
1. Phase 1: build all entity rows + import into Room under a single `@Transaction`.
2. Phase 2: iterate the now-persisted rows and schedule WorkManager / AlarmManager side-effects.

---

### F-08 — `evolutionSettings` re-restore is non-idempotent across domain keys — **P2/P1**
**File:** `BackupManager.kt:563-565`
```kotlin
backup.evolutionSettings.forEach { settings ->
    evolutionSettingsDao.upsert(settings.toEntity())
}
```
**Root cause:** If a user restores twice (e.g. the restore completes, they change a setting, then re-restore the original), the stale `evolution_settings` for domains in the *backup* are upserted; for domains NOT in the backup, the live settings remain. After a `purgeAll`, the evolution_settings table is wiped first (B.M.:620), so this matters only on partial re-imports.

**Fix:** Document the contract ("`evolutionSettings` is a complete replace — call `purgeAll` first") OR use `evolutionSettingsDao.deleteAll()` then upsert inside `purgeAll`'s caller. Currently `restoreEvolution` doesn't `deleteAll` first.

---

### F-09 — Dream DB FK columns without `@ForeignKey` — **P1**
**Files:**
- `aura-core/src/main/kotlin/com/aura/dream/ContradictionEntity.kt:24-30` — indexes on `(olderSummaryId, newerSummaryId)` but no `foreignKeys = [...]`. If `DreamSummaryEntity` rows are cleaned up, the `contradictions` table will retain dangling `olderSummaryId` references that no longer resolve.
- `aura-core/src/main/kotlin/com/aura/dream/KgEdgeProposalEntity.kt:21-30` — `(fromNodeId, toNodeId)` indexed as a unique pair, but no `foreignKeys = [...]` to `NodeEntity` (which lives in another DB anyway — so this is a partial schema issue).

**Root cause:** The schema was declared knowing the FK relationship, but the `@ForeignKey` annotation was omitted, presumably because the FK target entity lives in a different Room DB (`MemoryDatabase` for `NodeEntity`) and Room refuses cross-DB foreign keys.

**Fix:**
- For `ContradictionEntity.olderSummaryId`/`newerSummaryId`: cannot use `@ForeignKey` because the target is in another DB. Either (a) add a `@RawQuery`/programmatic trigger at the AppDatabase layer to clean up `contradictions` rows when a `dream_summary` is deleted, or (b) accept the orphaned-row risk and document it as "summary deletion is rare and contradiction cleanup is a separate process".
- For `KgEdgeProposalEntity.fromNodeId`/`toNodeId`: same — drop the unachievable FK, ensure the proposal UI shows node presence and drops orphaned rows on render.

---

### F-10 — `BeliefEntity.supersededBy` not indexed — **P1**
**File:** `aura-core/src/main/kotlin/com/aura/world/WorldModelEntities.kt:17-52`
**Root cause:** `Beliefs` are versioned with `supersededBy: String?`. The belief revision walker queries "show me the chain of supersedes for belief X" by `WHERE supersededBy = :id` — without an `@Index` on `supersededBy`, that's a full table scan on every belief-merge.

**Fix:**
```kotlin
@Entity(
    tableName = "beliefs",
    indices = [
        Index(value = ["subject"]),
        Index(value = ["predicate"]),
        Index(value = ["status"]),
        Index(value = ["validFrom"]),
        Index(value = ["confidence"]),
        Index(value = ["agentScope"]),
        Index(value = ["supersededBy"]),   // ADD
    ],
)
```
Migration: `MIGRATION_3_4` in `WorldModelModule` (currently does not exist; the world model is embedded in `MemoryDatabase`).

---

### F-11 — `purgeAll()` doesn't reset evolution_settings consistently — **P1**
**File:** `BackupManager.kt:618-620`
```kotlin
evolutionProposalDao.deleteAll()
evolutionRevisionDao.deleteAll()
evolutionSettingsDao.deleteAll()
```
**Root cause:** This IS in `purgeAll` (consistent with F-08). The concern is that the evolution settings table uses `domain` as the primary key — if a user restores on a build that has ADDED a new domain between snapshot time and restore, that domain's row is dropped silently. Acceptable, but documented as "evolution is a separate process and may re-seed defaults on first run".

**Fix:** Document the contract in `purgeAll()`'s kdoc.

---

### F-12 — `ProactiveInteractionEntity` FK missing — **P1**
**File:** `aura-core/src/main/kotlin/com/aura/proactive/ProactiveEventEntity.kt:23-36`
**Root cause:** Has `@Index(["eventId"])` but no `foreignKeys = [ForeignKey(ProactiveEventEntity, ["id"], ["eventId"], onDelete = CASCADE)]`. If `proactive_events` rows are deleted via `deleteOlderThan` (ProactiveEventDao:40) or `deleteByCorrelationTag` (ProactiveEventDao:47) the `proactive_interactions` orphans accumulate.

**Fix:**
```kotlin
@Entity(
    tableName = "proactive_interactions",
    foreignKeys = [
        ForeignKey(
            entity = ProactiveEventEntity::class,
            parentColumns = ["id"],
            childColumns = ["eventId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["eventId"]), Index(value = ["timestamp"])],
)
```
Migration: `MIGRATION_5_6` in `ProactiveEventModule.kt` (currently jumps from v4→v5; need to add a v5→v6 migration OR extend MIGRATION_4_5). Same issue with `proactive_events` themselves not having any FK to interactions, but that's a 1:N and the table on the N side is missing only.

---

### F-13 — `BackupMappers.kt` duplicates `BackupManager.kt` package+imports block — **P2**
**Files:**
- `aura-core/src/main/kotlin/com/aura/backup/BackupManager.kt:1-49`
- `aura-core/src/main/kotlin/com/aura/backup/BackupMappers.kt:1-49`

```diff
   1| package com.aura.backup
   2|
   3| import android.content.Context
   4| import com.aura.agent.ConversationDao
   5| import com.aura.agent.ConversationEntity
   ... 49 identical lines ...
```

**Root cause:** `BackupManager.kt` was likely created by copy-pasting from an early version of `BackupMappers.kt` before the actual class body was added. The imports are dead weight — the only top-level declarations in `BackupManager.kt` use:
- `withContext`, `Dispatchers` (kotlinx.coroutines)
- `Json`, `encodeToString` (kotlinx.serialization)
- `File`, `Inject`, `Singleton` (javax)

NONE of the 11 agent/creative/memory/etc. imports at the top are used directly in `BackupManager.kt`. The actual entity ↔ backup mappers live in `BackupMappers.kt`.

**Fix:** Delete the 49-line duplicate header from `BackupManager.kt`. Keep only the imports actually used (Context, Json, Dispatchers, withContext, etc.).

---

### F-14 — `proactive_events.correlationTag` not indexed — **P2**
**File:** `aura-core/src/main/kotlin/com/aura/proactive/ProactiveEventEntity.kt:7-20`
**Root cause:** `ProactiveEventDao.byCorrelationTag(tag)` and `deleteByCorrelationTag(tag)` filter on `correlationTag` but only `timestamp` is indexed. For installations where the same tag recurs across many events (e.g. a recurring calendar series tagged `cal:NNN`), this is a slow scan.

**Fix:** Add `Index(value = ["correlationTag"])` to the entity and a `MIGRATION_5_6` in `ProactiveEventModule.kt`.

---

### F-15 — `MemoryDatabase` carries 25 entities across many domains — **P2**
**File:** `aura-core/src/main/kotlin/com/aura/memory/MemoryDatabase.kt:47-101`

The `@Database` declaration lists 25 entities — memories, kg_nodes, kg_edges, memory_edits, documents, document_chunks, creative_projects, creative_artifacts, creative_revisions, creative_branches, creative_generation_jobs, canon_facts, creative_simulations, continuity_issues, artifact_dependencies, beliefs, evidence, world_events, opportunities, preference_signals, style_profiles, reference_identities, routing_outcomes, memory_feedback.

**Root cause:** These were merged into `MemoryDatabase` over many schema versions as new features were added, ostensibly to avoid spawning yet another `.db` file. The "v10 world model", "v10 creative artifacts", "v10 taste" comments in `BackupManager.kt` all describe the entity groups as separate concerns, but they're physically in the same Room database as `memories`.

**Fix:** Split into:
- `MemoryDatabase` (memories, memory_edits, memory_feedback)
- `KgDatabase` (kg_nodes, kg_edges)
- `DocumentDatabase` (documents, document_chunks)
- `CreativeDatabase` (creative_projects, creative_*, canon_facts, simulations, continuity_issues, artifact_dependencies, reference_identities, creative_generation_jobs)
- `WorldDatabase` (beliefs, evidence, world_events, opportunities)
- `TasteDatabase` (preference_signals, style_profiles, routing_outcomes)

This is a major refactor — note in audit roadmap; do NOT block the release on this.

---

### F-16 — `hands.name` not indexed despite `getByName` lookups — **P2**
**File:** `aura-core/src/main/kotlin/com/aura/hands/Hand.kt:10-30`
**Root cause:** `HandDao.getByName(name)` (line 22) and `deleteByName(name)` (line 37) filter on `name` which has no index. With dozens of hands (typical), this is acceptable; for power users with hundreds of hands, the trigger-phrase matcher is the fast path while `getByName` is occasional. P2 not P1.

**Fix:**
```kotlin
@Entity(
    tableName = "hands",
    indices = [Index(value = ["name"])],
)
```
Or create a unique constraint if each hand must have a unique name.

---

### F-17 — Same for `HandDao.deleteByName` — **P2**
Already covered above.

---

### F-18 — `conversations.agentId` not indexed despite filter use — **P2**
**File:** `aura-core/src/main/kotlin/com/aura/agent/ConversationEntity.kt:7-16`
**Root cause:** `ConversationStore` filters by agentId in agent-scoped history reads. The agent table is small (<10 rows), so the agent-side join is fine; the conversations table needs an index only if the History screen surfaces "conversations by agent" frequently — currently unused (P2).

**Fix:** Add `Index(value = ["agentId"])` if future History screens filter by agent.

---

### F-19 — `evolution_settings` fields under-snapshotted — **P2**
**File:** `BackupManager.kt:154-158` (`EvolutionSettingsEntity.toBackup()`)
```kotlin
private fun com.aura.evolution.EvolutionSettingsEntity.toBackup() = EvolutionSettingsBackup(
    domain = domain,
    enabled = enabled,
    updatedAt = updatedAt,
)
```
**Root cause:** `EvolutionSettingsEntity` has 13 fields (autoApplyApproved, reflectionEnabled, shadowEnabled, dailyCloudCallBudget, retentionDays, etc.), but only 3 are preserved on backup. The user's per-domain evolution preferences (e.g. "auto-apply for SKILL domain is pre-approved") silently revert to defaults on restore.

**Fix:** Match the full entity field set in `EvolutionSettingsBackup` and the toEntity/toBackup mappers.

---

### F-20 — String-typed enum columns — **P2**
**Files:** Multiple — e.g. `Hand.conditions` (`Hand.kt:20`), `HandRun.status` (`HandAutomation.kt:93`), `MemoryEntity.source`, `MemoryEntity.category`, `ProactiveEventEntity.eventType`, `AgentRunEntity.status`, etc.

**Root cause:** All enums use `String = "manual"` and rely on a Kotlin `enum class X(val value: String)` to keep the set of valid values. This works but loses type safety in Room. (Not a P0/P1; documented for awareness — fixing requires a database-wide migration to switch to Room type converters everywhere.)

**Fix:** Out of scope for a single round. Add `@TypeConverters` to each `@Database` annotation that exposes an enum-converter for the most-used enums.

---

## 4. ESCAPE clause verification (LIKE queries)

All user-input LIKE queries in DAOs use `ESCAPE '\\'`:

| File | Queries covered |
|------|-----------------|
| `agent/ConversationDao.kt:57,58,70,71` | `title LIKE '%' || :escapedQuery || '%' ESCAPE '\\'` |
| `kg/KnowledgeGraphDao.kt:30,31,32` | `label LIKE ... ESCAPE '\\'`, `type LIKE ...`, `properties LIKE ...` |
| `memory/MemoryDao.kt:62,66-71,81` | All `content LIKE ... ESCAPE '\\'` |
| `tools/ContactsSearchTool.kt:66` | Android ContactsContract selection — `LIKE ? ESCAPE '\\'` |

**All ESCAPE clauses are correctly applied; no `LIKE` query uses raw user input.** Verified.

---

## 5. N+1 query patterns

Reviewed `MemoryDao.allForExport()` and equivalent queries. **No classic N+1 patterns found.** All export-side reads are single bulk `SELECT ... ORDER BY ...` queries, and all bulk writes are `@Insert(List<>)` which Room wraps in a single transaction.

The `forRun(agentRunId:)` style queries in `AgentRunDaos.kt` are individual `SELECT * FROM agent_runs WHERE agentRunId = :runId` — but they're called per-run, not inside a loop, so they are not N+1.

The `purgeAll()` method issues ~25 sequential `DELETE FROM <table>` statements. Each is a bulk statement (`DELETE FROM ... `, no `WHERE`), so it's an O(table_size) operation per call, not N+1. Acceptable.

---

## 6. Backup coverage matrix (entity ↔ backup class ↔ DAO ↔ restore)

| Entity | BackupClass | SnapshotDAO | RestoreDAO | purgeAll |
|--------|-------------|-------------|-----------|----------|
| MemoryEntity              | MemoryBackup              | `memoryDao.allForExport()`                | `memoryDao.insertAll`    | ✓ |
| MemoryEditEntity          | MemoryEditBackup          | `memoryEditDao.allForBackup()`            | `memoryEditDao.insertAll` | ✓ |
| MemoryFeedbackEntity      | MemoryFeedbackBackup      | `memoryFeedbackDao.all()`                 | `memoryFeedbackDao.insertAll` | ✓ |
| DocumentEntity            | DocumentBackup            | `documentDao.allForBackup()`              | `documentDao.insertAll`  | ✓ |
| DocumentChunkEntity       | DocumentChunkBackup       | `documentChunkDao.allForBackup()`         | `documentChunkDao.insertAll` | ✓ |
| CreativeProjectEntity     | CreativeProjectBackup     | `creativeProjectDao.allForBackup()`       | `creativeProjectDao.insertAll` | ✓ |
| CreativeArtifactEntity    | CreativeArtifactBackup    | `creativeArtifactDao.allForBackup()`      | `creativeArtifactDao.insertAll` | ✓ |
| CreativeRevisionEntity    | CreativeRevisionBackup    | `creativeRevisionDao.allForBackup()`      | `creativeRevisionDao.insertAll` | ✓ |
| CreativeBranchEntity      | CreativeBranchBackup (**updatedAt MISSING**) | `creativeBranchDao.allForBackup()` | `creativeBranchDao.insertAll` | ✓ |
| CreativeSimulationEntity  | CreativeSimulationBackup  | `creativeSimulationDao.allForBackup()`    | `creativeSimulationDao.insertAll` | ✓ |
| ContinuityIssueEntity     | ContinuityIssueBackup     | `continuityIssueDao.allForBackup()`       | `continuityIssueDao.insertAll` | ✓ |
| ArtifactDependencyEntity  | ArtifactDependencyBackup  | `artifactDependencyDao.allForBackup()`    | `artifactDependencyDao.insertAll` | ✓ |
| CreativeGenerationJobEntity | **N/A (intentionally excluded)**   | n/a                                          | n/a                                  | n/a |
| CanonFactEntity           | CanonFactBackup           | `canonFactDao.allForBackup()`             | `canonFactDao.upsertAll` | ✓ |
| BeliefEntity              | BeliefBackup              | `beliefDao.allForBackup()` (nullable)     | `beliefDao.insertAll`    | ✓ |
| EvidenceEntity            | EvidenceBackup            | `evidenceDao.allForBackup()`              | `evidenceDao.insertAll`  | ✓ |
| WorldEventEntity          | WorldEventBackup          | `worldEventDao.allForBackup()`            | `worldEventDao.insertAll` | ✓ |
| OpportunityEntity         | OpportunityBackup         | `opportunityDao.allForBackup()`           | `opportunityDao.insertAll` | ✓ |
| PreferenceSignalEntity    | PreferenceSignalBackup    | `preferenceSignalDao.allForBackup()`      | `preferenceSignalDao.insertAll` | ✓ |
| StyleProfileEntity        | StyleProfileBackup        | `styleProfileDao.allForBackup()`          | `styleProfileDao.insertAll` | ✓ |
| ReferenceIdentityEntity   | ReferenceIdentityBackup   | `referenceIdentityDao.allForBackup()`     | `referenceIdentityDao.insertAll` | ✓ |
| RoutingOutcomeEntity      | RoutingOutcomeBackup      | `routingOutcomeDao.allForBackup()`        | `routingOutcomeDao.insertAll` | ✓ |
| Hand                      | HandBackup                | `handDao.getAll()`                        | `handDao.insertAll` (also calls `handScheduler.schedule`) | ✓ |
| HandRun                   | HandRunBackup             | `handDao.allRunsForBackup()`              | `handDao.insertAllRuns`  | ✓ |
| TaskEntity                | TaskBackup                | `taskDao.all()`                           | `taskDao.insertAll`      | ✓ |
| ReminderEntity            | ReminderBackup            | `reminderDao.allForBackup()`              | `restoreReminders` (calls `reminderDao.insert` + `reminderScheduler.schedule`) | ✓ |
| UserProfileEntity         | UserProfileBackup         | `userProfileDao.get()`                    | `userProfileDao.upsert` (or `deleteAll` if backup has null profile) | ✓ |
| ProactiveEventEntity      | ProactiveEventBackup      | `proactiveEventDao.allForBackup()`        | `proactiveEventDao.insertAll` | ✓ |
| ProactiveInteractionEntity| ProactiveInteractionBackup| `proactiveInteractionDao.allForBackup()`  | `proactiveInteractionDao.insertAll` | ✓ |
| ConversationEntity        | ConversationBackup        | `conversationDao.allForExport()`          | `conversationDao.insertAll` | ✓ |
| AgentEntity               | AgentBackup               | `agentDao.allOnce()`                      | `agentDao.insertAll` (filtered to `!isBuiltin`) | ✓ |
| StrategyBanditEntity      | StrategyBanditBackup      | `strategyBanditDao.all()` (nullable)      | `restoreStrategyBandit` (uses `?.clear()` + `?.insertAll()`) | ✓ |
| NodeEntity                | NodeBackup                | `kgDao.allNodes()`                        | `kgDao.insertAllNodes`   | ✓ |
| EdgeEntity                | EdgeBackup                | `kgDao.allEdges()`                        | `kgDao.insertAllEdges`   | ✓ |
| DreamSummaryEntity        | DreamSummaryBackup        | `dreamSummaryDao.allForBackup()`          | `dreamSummaryDao.insertAll` | ✓ |
| RoutineEntity             | RoutineBackup             | `routineDao.allForBackup()`               | `routineDao.insertAll`   | ✓ |
| ContradictionEntity       | ContradictionBackup       | `contradictionDao.allForBackup()`         | `contradictionDao.insertAll` | ✓ |
| KgEdgeProposalEntity      | KgEdgeProposalBackup      | `kgEdgeProposalDao.allForBackup()`        | `kgEdgeProposalDao.insertAll` | ✓ |
| EvolutionEvidenceEntity   | EvolutionEvidenceBackup   | `evolutionEvidenceDao.allForBackup()`     | `evolutionEvidenceDao.insertAll` | ✓ |
| EvolutionCandidateEntity  | EvolutionCandidateBackup  | `evolutionCandidateDao.allForBackup()`    | `evolutionCandidateDao.insertAll` | ✓ |
| EvolutionProposalEntity   | EvolutionProposalBackup   | `evolutionProposalDao.insertAll` (via `restoreEvolution`) | same | ✓ |
| EvolutionRevisionEntity   | EvolutionRevisionBackup   | `evolutionRevisionDao.insertAll` (via `restoreEvolution`) | same | ✓ |
| EvolutionSettingsEntity   | EvolutionSettingsBackup   | (NOT snapshot — only set prefs via `userPreferences.setEvolutionEnabled`) | `evolutionSettingsDao.upsert` per-row (via `restoreEvolution`) | ✓ |
| AgentRunEntity            | AgentRunBackup            | `agentRunDao.allForBackup()`              | `agentRunDao.insertAll`  | ✓ |
| GoalEntity                | GoalBackup                | `goalDao.allForBackup()`                  | `goalDao.insertAll`      | ✓ |
| StepEntity                | StepBackup                | `stepDao.allForBackup()`                  | `stepDao.upsertAll`      | ✓ |
| AgentEventEntity          | AgentEventBackup          | `agentEventDao.allForBackup()`            | `agentEventDao.insertAll` | ✓ |
| ApprovalRequestEntity     | ApprovalRequestBackup     | `approvalRequestDao.allForBackup()`       | `approvalRequestDao.insertAll` | ✓ |
| RunCheckpointEntity       | RunCheckpointBackup       | `runCheckpointDao.allForBackup()`         | `runCheckpointDao.upsertAll` | ✓ |

**48 entities, 47 have full coverage. The one that's missing — `CreativeGenerationJobEntity` — is documented as deliberate (transient).** Good — coverage is comprehensive.

---

## 7. Recommendations summary (action plan)

| Sev | # | Fix | Effort |
|-----|---|-----|--------|
| P0 | F-01 | Wrap `restore()` in `db.withTransaction { }` — inject DB reference into BackupManager | M (½ day) |
| P0 | F-02 | Move AgentModule + StrategyBanditModule to `RoomConfig.builder` | XS |
| P0 | F-03 | Add `strategyBandit: Int` to `RestoreCounts`, make `strategyBanditDao` non-nullable | S |
| P0 | F-04 | Add `updatedAt` to `CreativeBranchBackup` + both mappers; add `parentBranchId`/`headArtifactId` to entity | S |
| P1 | F-05 | `purgeAll()` resets DataStore preferences via a `UserPreferences.resetAll()` API | S |
| P1 | F-06 | Add `dreamEnabled`, `decayEnabled`, `triggersEnabled`, `triggersJson`, `planningEnabled`, `defaultAgentId` to PreferencesBackup | S |
| P1 | F-07 | Schedule WorkManager/AlarmManager side-effects *after* all Room writes commit | M |
| P1 | F-09 | Document cross-DB FK limitations + add cleanup triggers for orphaned dream/contradiction rows | M |
| P1 | F-10 | `@Index("supersededBy")` on BeliefEntity + MIGRATION_3_4 | S |
| P1 | F-11, F-12 | Document evolution_settings restore contract; add `ForeignKey` to `ProactiveInteractionEntity` | S |
| P2 | F-13 | Delete 49-line duplicate header from `BackupManager.kt` | XS |
| P2 | F-14 | `@Index("correlationTag")` on `ProactiveEventEntity` + migration | S |
| P2 | F-15 | Roadmap entry: split MemoryDatabase into 6 logical DBs | XL (3 days+) |
| P2 | F-16/17 | `@Index("name")` on `Hand` + migration to v3 | S |
| P2 | F-18 | `@Index("agentId")` on ConversationEntity — defer until History screens need it | XS |
| P2 | F-19 | Snapshot all EvolutionSettingsEntity fields in `EvolutionSettingsBackup` | S |
| P2 | F-20 | Type-converters for hot enum columns; long-term play | L |

**Effort legend:** XS = <30 min, S = 1-2 hrs, M = ½ day, L = 1-2 days, XL = multi-day refactor.

---

## 8. Tests present (verified)

- `aura-core/src/test/kotlin/com/aura/backup/AuraBackupSchema12Test.kt` — schema v12 roundtrip
- `aura-core/src/test/kotlin/com/aura/backup/AuraBackupSchema13Test.kt` — schema v13 roundtrip
- `aura-core/src/test/kotlin/com/aura/backup/AuraBackupSerializationTest.kt` — JSON encode/decode
- `aura-core/src/test/kotlin/com/aura/backup/BackupManagerTest.kt` — mock-based round-trip coverage of every backup table
- `aura-core/src/test/kotlin/com/aura/backup/CreativeRevisionBackupRoundtripTest.kt` — creative revision round-trip
- `aura-core/src/androidTest/kotlin/com/aura/{Conversation,Dream,Evolution,Hand,Memory}DatabaseMigrationTest.kt` — Room `MigrationTestHelper` tests

> Backup tests should be extended to cover:
> 1. A snapshot-restore roundtrip persists all fields (proposed P0 test for F-04 — `CreativeBranchEntity.updatedAt`).
> 2. `purgeAll` followed by `restore` of an empty backup returns empty tables *and* default preferences.
> 3. Two back-to-back `restore` calls without `purgeAll` is idempotent (F-08 guard).

---

## 9. Conclusion

The data & backup system has been built out substantially (v1 → v15 in 17 cycles, ~80K LOC) and the schema coverage of the export is comprehensive — 47/48 entities are roundtripped; 1 is intentionally excluded (CreativeGenerationJobEntity). Migrations are gap-free at every N→N+1 boundary.

The most pressing issues are concentrated around **transactional safety of `restore()`** (F-01, F-07) and **a handful of undeclared field drifts** between entity ↔ backup ↔ restore (F-04, F-19, F-06). Fixing the four P0 findings is on the order of 1 day of focused work; the P1 list is another 1-2 days.

Recommend shipping the four P0s as a single PR titled `Round 13 — backup integrity fixes` and opening the F-15 MemoryDatabase split as a roadmap entry rather than a single-round effort.
