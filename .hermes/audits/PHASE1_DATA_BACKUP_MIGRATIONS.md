# Phase 1 Audit — Data Layer, Backup, Migrations

**Audited:** `D:\aura-android-clean` Android Kotlin/Compose personal AI assistant
**Modules:** `app` (UI), `aura-core` (libraries)
**Scope:** Room databases, entities, DAOs, BackupManager, Room migrations, conversation compaction
**Date:** 2026-07-25

---

## Executive Summary

| Category | Verdict |
|---|---|
| `@Database` definitions (10 DBs) | ✅ All have `exportSchema = true` |
| Version ↔ migration array parity | ✅ All 9 versioned DBs match |
| Entity coverage (DAO + backup class + mapper) | 🔴 **8 of 48 entities have NO backup class** — data is permanently lost on backup/restore |
| Room schema export completeness | 🟡 **MemoryDatabase schemas 7.json–10.json are missing from `aura-core/schemas/`** — schema drift risk |
| BackupManager FK ordering | ✅ KG nodes → edges, document → chunks, hand → handRuns, etc. are all correct |
| Agent scope preservation across backup | ✅ `agentScope` is in `MemoryBackup`, `BeliefBackup`, `EvidenceBackup`, etc. and preserved by all mappers |
| Conversation compaction | 🟡 Compactor selects "cheapest" model by shortest model name — a brittle heuristic that may pick wrong model |
| `MigrationRegistryAuditTest` | ✅ Present and pins both halves of the contract (max-to-version + contiguous sequence) |

**Bottom line:** The migration registry is solid and matches the `@Database` versions. The two real concerns are (1) **8 entity types are silently dropped on every backup/restore** because they have no `Backup` data class and no wiring in `BackupManager`, and (2) **4 schema JSON files (MemoryDatabase v7–v10) are missing from the schema export directory**, so a fresh schema-generation pass would change the hash and break the migration test's hash comparison.

---

## 1. @Database Definitions — Schema Export Audit

All 10 `@Database` declarations in the project set `exportSchema = true` and match the migration arrays in their respective modules. **PASS.**

| # | Database | File | Version | exportSchema | Migration array parity |
|---|---|---|---|---|---|
| 1 | `AgentDatabase` | `agent/AgentDatabase.kt:6-10` | 1 | ✅ | N/A (v1, no migrations) |
| 2 | `ConversationDatabase` | `agent/ConversationDatabase.kt:6` | 6 | ✅ | ✅ 5 migrations, arrayOf() at line 74 |
| 3 | `AgentRunDatabase` | `agentrun/AgentRunDatabase.kt:6-17` | 1 | ✅ | N/A (v1, no migrations) |
| 4 | `DreamConsolidationDatabase` | `dream/DreamConsolidationDatabase.kt:21-30` | 2 | ✅ | ✅ 1 migration, arrayOf() at line 96 |
| 5 | `EvolutionDatabase` | `evolution/EvolutionDatabase.kt:13-23` | 3 | ✅ | ✅ 2 migrations via `ALL_MIGRATIONS` (line 20) |
| 6 | `HandDatabase` | `hands/HandDatabase.kt:6` | 2 | ✅ | ✅ 1 migration, arrayOf() at line 55 |
| 7 | `MemoryDatabase` | `memory/MemoryDatabase.kt:47-76` | 14 | ✅ | ✅ 13 migrations, arrayOf() at line 589 |
| 8 | `ProactiveEventDatabase` | `proactive/ProactiveEventDatabase.kt:6` | 5 | ✅ | ✅ 4 migrations, arrayOf() at line 70 |
| 9 | `TaskDatabase` | `tasks/TaskDatabase.kt:6` | 4 | ✅ | ✅ 3 migrations, arrayOf() at line 76 |
| 10 | `UserProfileDatabase` | `profile/UserProfileDatabase.kt:6` | 2 | ✅ | ✅ 1 migration, arrayOf() at line 30 |

### 1.1 Critical — MemoryDatabase v7–v10 schema files missing (P0)

**Finding:** `D:\aura-android-clean\aura-core\schemas\com.aura.memory.MemoryDatabase\` contains `1.json, 2.json, 3.json, 4.json, 5.json, 6.json, 11.json, 12.json, 13.json, 14.json` — but is **missing `7.json, 8.json, 9.json, 10.json`**.

`git log --all -- 'aura-core/schemas/com.aura.memory.MemoryDatabase/7.json'` returns no history — they were never committed.

**Impact:**
- The migrations `MIGRATION_6_7`, `MIGRATION_7_8`, `MIGRATION_8_9`, `MIGRATION_9_10`, `MIGRATION_10_11` exist in `MemoryModule.kt` (lines 159, 193, 288, 382, 465) and are wired into `arrayOf()` at line 589.
- However, Room's `exportSchema = true` requires that the schema file exist on disk at build time. The KSP processor will regenerate them — but if a developer regenerates and the new file's `identityHash` differs from the migrated schema's expected hash, the `MigrationTestHelper` (if used) will fail.
- More importantly, **the missing files represent schema states the project team never reviewed**: v7 introduced `document_chunks`, v8 introduced `creative_artifacts` + 3 sibling tables, v9 added `canon_facts`/`continuity_issues`/`artifact_dependencies`, v10 added `beliefs`/`evidence`/`world_events`/`opportunities`/`preference_signals`/`style_profiles`/`reference_identities`/`routing_outcomes`. These are 13 of the 25 entities in the current schema.

**Evidence:** The 6.json schema has only 6 tables (`memories, memory_edits, documents, kg_nodes, kg_edges, creative_projects`); 11.json has 24 tables. The 4 missing JSON files are the schemas for the intermediate versions where 18 new tables were created.

**Fix:** Re-export the schemas by running `./gradlew :aura-core:exportSchema` (or equivalent KSP task) and committing `7.json, 8.json, 9.json, 10.json`. Verify the `identityHash` fields still match the migration `migrate()` SQL output for round-trip safety.

---

## 2. Entity Coverage Audit

48 entities exist across the codebase. The audit cross-checked each entity against four properties:
1. **DAO interface** with at least one `@Insert` method
2. **Backup data class** in `aura-core/src/main/kotlin/com/aura/backup/`
3. **`toBackup()` mapper** in `BackupManager.kt`
4. **`toEntity()` mapper** and `BackupManager.snapshot/restore` wiring

### 2.1 Critical — 8 entities have NO backup class and are silently dropped (P0)

These entities are registered in `@Database(entities = [...])` and exist in the on-device database, but **no `Backup` data class exists** and **no `toBackup`/`toEntity` mapper exists**. Their data is permanently lost on every `BackupManager.snapshot()` and never restored on `restore()`.

| Entity | Database | File | Tables dropped on backup |
|---|---|---|---|
| `CreativeSimulationEntity` | MemoryDatabase v9 | `creative/CanonEntities.kt:80` | `creative_simulations` |
| `ContinuityIssueEntity` | MemoryDatabase v9 | `creative/CanonEntities.kt:117` | `continuity_issues` |
| `ArtifactDependencyEntity` | MemoryDatabase v9 | `creative/CanonEntities.kt:164` | `artifact_dependencies` |
| `CreativeGenerationJobEntity` | MemoryDatabase v8 | `creative/CreativeArtifactEntity.kt:152` | `creative_generation_jobs` |
| `EvolutionEvidenceEntity` | EvolutionDatabase v1 | `evolution/EvolutionEntities.kt:25` | `evolution_evidence` |
| `EvolutionCandidateEntity` | EvolutionDatabase v1 | `evolution/EvolutionEntities.kt:67` | `evolution_candidates` |
| `ProactiveInteractionEntity` | ProactiveEventDatabase v2 | `proactive/ProactiveEventEntity.kt:27` | `proactive_interactions` |
| `RoutingOutcomeEntity` | MemoryDatabase v12 | `taste/TasteEntities.kt:120` | `routing_outcomes` |

**Note:** `ProactiveInteractionEntity` is the most severe because `ProactiveInteractionDao` (`proactive/ProactiveEventDao.kt:55-67`) has **no `insertAll()` method and no `allForBackup()` method at all** — so the entity was never designed to participate in backup even at the DAO level.

**For `EvolutionEvidenceEntity` and `EvolutionCandidateEntity`:** the `EvolutionDao.kt` has `allForBackup()` for these, and the DAOs are injected into `BackupManager` (lines 85-87), but the `BackupManager.snapshot()` block (lines 178-256) only iterates over `evolutionProposalDao`, `evolutionRevisionDao`, `evolutionSettingsDao` — `evolutionEvidence` and `evolutionCandidate` are missing. The `restoreEvolution()` method (lines 479-492) is also missing them. **Confirmed: data is lost on every backup/restore cycle.**

**Fix for each entity:**
1. Create a `XxxBackup` `@Serializable` data class in `AuraBackup.kt` (or a new `AuraBackupSchema13.kt`).
2. Add a `val xxxs: List<XxxBackup> = emptyList()` field to `AuraBackup`.
3. Bump `AuraBackup.SCHEMA_VERSION` from 12 to 13.
4. Add `private fun XxxEntity.toBackup()` and `private fun XxxBackup.toEntity()` mappers.
5. Wire `xxxDao?.allForBackup()?.map { it.toBackup() }` into `BackupManager.snapshot()` and `if (rows.isNotEmpty()) xxxDao?.insertAll(rows)` into `BackupManager.restore()`.
6. Add the row count to `RestoreCounts` and `total` derivation.
7. For `ProactiveInteractionEntity` specifically, also add `insertAll` and `allForBackup` to `ProactiveInteractionDao`.

### 2.2 Complete — 40 entities have full coverage

The other 40 entities (Agent, Conversation, AgentRun+5 agentrun entities, MemoryEntity+MemoryEdit+MemoryFeedback+Node+Edge+Document+DocumentChunk+8 creative+canon entities+4 world model+4 taste+Hand+HandRun+Task+Reminder+UserProfile+ProactiveEvent+EvolutionProposal+EvolutionRevision+EvolutionSettings+CanonFact+Belief+Evidence+WorldEvent+Opportunity+PreferenceSignal+StyleProfile+ReferenceIdentity+CreativeArtifact+CreativeRevision+CreativeBranch+5 dream database entities) have backup class + mappers + snapshot/restore wiring.

---

## 3. BackupManager.kt Audit

### 3.1 Schema version — `AuraBackup.SCHEMA_VERSION = 12`

`BackupManager.kt:77` (AuraBackup.kt:77): `const val SCHEMA_VERSION = 12`. The decoder at `BackupManager.kt:273-277` refuses to read any backup with `schemaVersion > 12`. This is enforced correctly with `require()`.

**Drift risk:** The most recent migration is `MIGRATION_13_14` (MemoryDatabase v13→v14 added `agentScope` to 8 tables). The backup schema version is 12. There is no comment explaining why backup schema version lags Room schema version — this appears intentional (a v12 backup predates the v14 schema additions), but it's worth documenting so a future developer doesn't think backup is "stale."

### 3.2 Foreign key ordering — correct ✅

`BackupManager.restore()` (lines 294-377) restores in dependency order:
- KG nodes (lines 343) → KG edges (line 344) ✅ (edges FK to nodes)
- Documents (line 340) → document_chunks (line 368) ✅ (chunks FK to documents)
- Creative projects (line 341) → creative artifacts (line 355) → creative revisions/branches (lines 356-357) ✅
- Beliefs (line 351) → evidence (line 352) ✅ (evidence FK to beliefs via beliefId)
- AgentRun (line 371) → goals (line 370) → steps (line 372) → events/approvals/checkpoints (lines 373-375) — note: AgentRun is restored *after* goals, which is a potential FK issue if Room enforces foreign keys here. Verify by running on a device with `PRAGMA foreign_keys = ON`.

The KG dependency order is correctly documented in the comment at `BackupManager.kt:280-285`.

### 3.3 Agent scope preservation — correct ✅

`agentScope` is preserved across all 8 world model + taste entities:
- `BeliefBackup` (AuraBackup.kt:423) has `agentScope`
- `BeliefEntity.toBackup()` → `toEntity()` mappers at BackupManager.kt:986, 1020 preserve it
- `EvidenceBackup`, `WorldEventBackup`, `OpportunityBackup`, `PreferenceSignalBackup`, `StyleProfileBackup`, `ReferenceIdentityBackup` all have `agentScope` field and preserved mappers

The most recent `MIGRATION_13_14` (MemoryModule.kt:558) added the `agentScope` column to all 8 tables. Since the backup already preserved `agentScope` for these entities, no schema migration was needed for the backup file format — the backup data class already carried the field.

### 3.4 Soft-delete preservation for `ConversationEntity` — correct ✅

`ConversationBackup.deletedAt` (AuraBackup.kt:221) has `Long? = null` and both mappers (`BackupManager.kt:768, 787`) preserve it. Soft-deleted conversations are correctly tombstoned across backup/restore.

### 3.5 MemoryEmbedding is intentionally NOT backed up ✅

`MemoryBackup.toEntity()` (BackupManager.kt:695) sets `embedding = null`. This is documented at BackupManager.kt:56-60 — embeddings are model-specific and rebuilt via "Settings → Memory → Rebuild embeddings" after a restore. **Correct design.**

### 3.6 API keys are NOT backed up ✅

`BackupManager.kt:61-64` and the `restore()` block (lines 386-424) — API keys live in `SecureDataStore` and are re-entered by the user. SMTP password is intentionally cleared to `""` at line 416. **Correct security design.**

### 3.7 Hands restoration is partially transactional 🟡

`BackupManager.kt:345-348` inserts hands and immediately calls `handScheduler.schedule()` for each. If `insertAll` succeeds but the scheduler throws partway through, some hands will be inserted but not scheduled. The `purgeAll` block (lines 535-537) has the inverse problem: it cancels hand schedules before deleting hands, but if the DAO delete throws, schedules are left cancelled. **Recommend: wrap in `runCatching` or move scheduling to a single `handScheduler.scheduleAll(handRows)` method.**

### 3.8 Reminders use `restoreReminders` helper ✅

`BackupManager.kt:377, 494-516` — reminders are restored via a dedicated helper that handles the `triggerAt` past-due case (computes next trigger via `ReminderRecurrence.nextTrigger` or marks as `fired` if recurrence is exhausted). The scheduler is called inside the helper. **Correct.**

### 3.9 No transactional boundary for the entire restore 🟡

`BackupManager.restore()` is a long sequence of `insertAll` calls with no `withTransaction { }` wrapper. A crash mid-restore leaves the database in a partially restored state. Given that `purgeAll` is the recommended pre-restore step, this is *probably* safe (the purgeAll wipes everything), but if the user calls restore *without* purgeAll, partial state is possible. **Recommend: wrap the entire restore in `RoomDatabase.withTransaction { ... }` from `androidx.room.withTransaction`.**

---

## 4. DAO Audit

### 4.1 All 48 entities have DAO interfaces ✅

Verified by searching for each entity name in `*Dao.kt`/`*Daos.kt` files. Every entity has at least one `@Dao` interface with `@Insert` methods.

### 4.2 All DAOs have `insertAll` or `upsertAll` ✅

| DAO | File | insertAll/upsertAll |
|---|---|---|
| `AgentDao` | `agent/AgentDao.kt` | ✅ 3 methods |
| `ConversationDao` | `agent/ConversationDao.kt` | ✅ `insertAll` |
| `MemoryDao` | `memory/MemoryDao.kt:101` | ✅ |
| `MemoryFeedbackDao` | `memory/MemoryDao.kt:164` | ✅ |
| `DocumentDao` | `documents/DocumentDao.kt` | ✅ |
| `DocumentChunkDao` | `documents/DocumentChunkDao.kt` | ✅ |
| `KnowledgeGraphDao` | `kg/KnowledgeGraphDao.kt` | ✅ `insertAllNodes/insertAllEdges` |
| `CreativeProjectDao` | `creative/CreativeProjectDao.kt` | ✅ |
| `CreativeArtifactDao` | `creative/CreativeArtifactDao.kt` | ✅ `insertAll` |
| `CreativeRevisionDao` | same file | ✅ `insertAll` |
| `CreativeBranchDao` | same file | ✅ `insertAll` |
| `CreativeGenerationJobDao` | same file | ✅ `insertAll` |
| `CanonFactDao` | `creative/CanonDaos.kt` | ✅ `upsertAll` |
| `BeliefDao` | `world/WorldModelDaos.kt` | ✅ |
| `EvidenceDao` | same file | ✅ |
| `WorldEventDao` | same file | ✅ |
| `OpportunityDao` | same file | ✅ |
| `PreferenceSignalDao` | `taste/TasteDaos.kt` | ✅ |
| `StyleProfileDao` | same file | ✅ |
| `ReferenceIdentityDao` | same file | ✅ |
| `RoutingOutcomeDao` | same file | ✅ |
| `HandDao` | `hands/HandDao.kt` | ✅ `insertAll` + `insertAllRuns` |
| `TaskDao` | `tasks/TaskDao.kt` | ✅ |
| `ReminderDao` | `tasks/ReminderDao.kt` | ✅ |
| `UserProfileDao` | `profile/UserProfileDao.kt` | ✅ (uses `upsert` for the singleton row) |
| `ProactiveEventDao` | `proactive/ProactiveEventDao.kt` | ✅ |
| **`ProactiveInteractionDao`** | `proactive/ProactiveEventDao.kt:55-67` | ❌ **No `insertAll`, no `allForBackup`** (see §2.1) |
| `DreamConsolidationDao` | `dream/DreamConsolidationDao.kt` | ✅ |
| `RoutineDao` | `dream/RoutineDao.kt` | ✅ |
| `ContradictionDao` | `dream/ContradictionDao.kt` | ✅ |
| `KgEdgeProposalDao` | `dream/KgEdgeProposalDao.kt` | ✅ |
| `EvolutionEvidenceDao` | `evolution/EvolutionDaos.kt` | ✅ |
| `EvolutionCandidateDao` | same file | ✅ |
| `EvolutionProposalDao` | same file | ✅ |
| `EvolutionRevisionDao` | same file | ✅ |
| `EvolutionSettingsDao` | same file | ✅ |
| `AgentRunDao` | `agentrun/AgentRunDaos.kt` | ✅ |
| `GoalDao` | same file | ✅ |
| `StepDao` | same file | ✅ `upsertAll` |
| `AgentEventDao` | same file | ✅ |
| `ApprovalRequestDao` | same file | ✅ |
| `RunCheckpointDao` | same file | ✅ `upsertAll` |
| `MemoryEditDao` | `memory/MemoryEditDao.kt` | ✅ |

**Exception:** `ProactiveInteractionDao` lacks both methods. This needs to be added to fully support §2.1's fix.

### 4.3 LIKE escaping — correct ✅

All `LIKE` queries use the `ESCAPE '\'` clause and the input is passed through `escapeLikeWildcards` helper:
- `MemoryDao.kt:62, 66-71, 81` — uses `ESCAPE '\\'` with the helper
- `ConversationDao.kt:54-55, 67-68` — uses `ESCAPE '\'` with the helper
- `KnowledgeGraphDao.kt:30-32` — uses `ESCAPE '\'` with the helper
- The helper is at `MemoryStore.kt:557-560` and escapes `\`, `%`, `_` in order (critical order — backslash first, then the wildcards)

**Verified consumers:** `MemoryStore.kt:191, 201, 331`, `ConversationStore.kt:85`, `KnowledgeGraphRepository.kt:57`, `GlobalSearchRepository.kt:58`, `ContactsSearchTool.kt:65`. All wire the escaped value.

### 4.4 Scope filtering — correct ✅

- `MemoryDao.byScope` (line 53) — exact match
- `MemoryDao.withinScope` (line 56) — `'general' OR scope LIKE 'agent:<id>%'` (for shared + agent-private scope leakage prevention)
- `MemoryDao.byScopes` (line 59), `searchByTextInScopes` (line 62), `searchByTextInScopesWithKeywords` (line 65) — `IN (:scopes)`
- `MemoryDao.allByScopes` (line 78) — `IN (:scopes)`

All scope queries use parameter binding (no string interpolation), and `IN (:scopes)` works correctly with Room's `@Query` for `List<String>` parameters. **PASS.**

### 4.5 Soft-delete handling — correct ✅

- `ConversationDao.searchVisible` (line 54) — `WHERE deletedAt IS NULL`
- `ConversationDao.all` (line 66) — `AND deletedAt IS NULL`
- `ConversationDao.mostRecent` (line 75) — `WHERE deletedAt IS NULL`
- `ConversationDao.softDelete` (line 91), `restore` (line 95), `purgeOld` (line 103) — direct update/delete
- `ConversationDao.allWithEmbeddings` (line 118) — `AND deletedAt IS NULL`
- `ConversationDao.findMissingEmbeddings` (line 126) — `AND deletedAt IS NULL`
- `ConversationEntity.kt:15` — has `Index(value = ["deletedAt"])`

**PASS.**

### 4.6 No N+1 query patterns detected 🟢

Searched for `forEach { ... query ... }` and `for (... in ...) { dao... }` patterns. The only loop patterns are:
- `MemoryAugmentedAgenticLoop.kt:691, 703, 737` — these are in-memory tool call dispatch, not DB queries.
- `BackupManager.restoreReminders` (line 496) — iterates over rows already loaded from `backup.reminders`, calls `reminderDao.insert` (single row) and `reminderScheduler.schedule`. This is correct — scheduling requires per-row work and the rows are already in memory.

**No N+1 anti-patterns in DAOs.** ✅

---

## 5. MemoryDatabase v14 Migration Chain (1→14)

`MemoryModule.kt` lines 41-583 define all 13 migrations; line 589 wires them in `arrayOf()`. The chain is **contiguous 1→2→3→...→14** with no gaps, matching the `MigrationRegistryAuditTest` pattern at `aura-core/src/test/kotlin/com/aura/migration/MigrationRegistryAuditTest.kt`.

| # | Migration | Lines | What it does |
|---|---|---|---|
| 1 | `MIGRATION_1_2` | 41-80 | Initial state — no actual schema change in this object (migrations predate the entity additions) |
| 2 | `MIGRATION_2_3` | 82-102 | (similar — see file) |
| 3 | `MIGRATION_3_4` | 104-116 | |
| 4 | `MIGRATION_4_5` | 118-134 | |
| 5 | `MIGRATION_5_6` | 136-157 | |
| 6 | `MIGRATION_6_7` | 159-191 | **Adds `document_chunks` table + `embeddingModel`/`embeddingVersion` columns to memories + `indexStatus`/`indexError` to documents** |
| 7 | `MIGRATION_7_8` | 193-286 | **Adds `creative_artifacts` + `creative_revisions` + `creative_branches` tables** |
| 8 | `MIGRATION_8_9` | 288-380 | **Adds `creative_generation_jobs` + `creative_simulations` + `continuity_issues` + `artifact_dependencies` tables** |
| 9 | `MIGRATION_9_10` | 382-463 | **Adds `canon_facts` + world model + taste tables (`beliefs`, `evidence`, `world_events`, `opportunities`, `preference_signals`, `style_profiles`, `reference_identities`)** |
| 10 | `MIGRATION_10_11` | 465-533 | (see file) |
| 11 | `MIGRATION_11_12` | 535-540 | **Adds `scope` column to memories + index** |
| 12 | `MIGRATION_12_13` | 542-556 | **Adds `memory_feedback` table + indices** |
| 13 | `MIGRATION_13_14` | 558-582 | **Adds `agentScope` column to 8 world model + taste tables + indices** |

**PASS** — all 13 migrations are registered, contiguous, and the `@Database(version = 14)` matches the chain endpoint.

### 5.1 Migration test (`MigrationRegistryAuditTest`)

This test (`aura-core/src/test/kotlin/com/aura/migration/MigrationRegistryAuditTest.kt:1-191`) pins:
- The max "to" version per module is ≥ 1 (line 91-96)
- The migration pairs form a contiguous 1→N sequence (line 113-130)

The test uses reflection (`Class.declaredFields`) to find `MIGRATION_X_Y` fields. **Limitation:** The test doesn't actually verify the migration array passed to `Room.databaseBuilder` matches the fields — it only checks that fields exist. A developer who creates a `MIGRATION_10_11` field but forgets to add it to `arrayOf()` would pass the test. **The original DATA_AUDIT P0 finding (per the test's own comment at line 8-10) was that this test exists to catch that bug class — but the test as written only catches the "did you write the field" case, not the "did you wire it into the array" case.**

**Recommendation:** Strengthen the test by introspecting the `provideDatabase` method (e.g., via the module's source, or by making the migrations array a `val ALL_MIGRATIONS = ...` field that the test can read directly). Several modules already use the `ALL_MIGRATIONS` pattern (e.g., `EvolutionModule.kt:20`); the others should adopt it.

---

## 6. Conversation Compaction Audit

`aura-core/src/main/kotlin/com/aura/agent/ConversationCompactor.kt` (198 lines).

### 6.1 Context window lookup per provider — mostly correct 🟡

- `lookupContextWindow` (lines 149-155) calls `providerRegistry.parse(model)` then `cachedModelsWithContext(provider).firstOrNull { it.name == modelName }?.contextWindow`.
- 5 of 7 providers override `listModelsWithContext` with real context window data: `AnthropicProvider:278`, `GeminiProvider:241`, `OllamaCloudProvider:42`, `OpenAiCompatProvider:184`, `OpenRouterProvider:60`.
- **2 providers do NOT override `listModelsWithContext`:** `ChatGptSubscriptionProvider` (line 173 only overrides `listModels()`) and `CustomOpenAiCompatProvider` (line 257 only overrides `listModels()`).

For these two providers, the default `Provider.listModelsWithContext` at `Provider.kt:43-44` is used, which returns `listModels().map { ModelInfo(name = it, contextWindow = null) }` — i.e., `null` context windows for all models.

**Impact:** When a user compacts a ChatGPT or custom OpenAI-compatible conversation, `lookupContextWindow` returns `null`, which makes `resolveThreshold` (`ConversationCompactor.kt:183-188`) fall back to `DEFAULT_UNCOMPACTED_TOKENS = 32_000`. This is a *generous* fallback (32K), so the compactor will fire later than it should for large-context models (e.g., GPT-4 Turbo 128K). For small-context models it's also too generous, so the compactor might never fire even when context is full. **Mild bug — model-specific context window knowledge would help.**

### 6.2 Compaction threshold logic — has a brittle heuristic 🟡

`ConversationCompactor.kt:55-69` — when choosing which model to use for the *compaction* call (which compresses the conversation), the algorithm picks:

```kotlin
candidates.minByOrNull { it.substringAfter(":").length } ?: model
```

**This picks the model with the shortest name as a proxy for "cheapest."** This is a heuristic, not a property of the model. Examples:
- `gpt-3.5-turbo` (12 chars) would be picked over `gpt-4-turbo-preview` (18 chars) — but `gpt-3.5-turbo-instruct` (21 chars) might be picked over `gpt-4` (5 chars, but with the same prefix issues). The heuristic is meaningless when models have similar-length names.
- There's no cost / pricing data on `ModelInfo`. Adding `val costPerInputToken: Double? = null` and `val costPerOutputToken: Double? = null` to `ModelInfo` would let the compactor make a real cost-based choice.

**Recommendation:** Either:
1. Add cost fields to `ModelInfo` and use `minByOrNull { it.costPerInputToken ?: Double.MAX_VALUE }`.
2. Add an explicit "use model X for compaction" preference in `UserPreferences`.
3. At minimum, fall back to the same model (no override) when the heuristic is unreliable.

### 6.3 Token estimation — chars/4 heuristic is documented ✅

`ConversationCompactor.kt:72-80` uses `chars / 4` as a tokens-per-char estimate. The comment correctly notes this is rough but works for English text. **Acceptable for a personal-use install.**

### 6.4 Recent turns kept — constant ✅

`RECENT_TURNS_TO_KEEP = 24` (line 190). 24 turns is reasonable for a personal assistant; the comment notes this is for "local coherence and tool-call continuity." **Acceptable.**

### 6.5 Failure handling — correctly non-blocking ✅

`compactIfNeeded` (lines 87-122) wraps the compaction call in try/catch. A failure logs to `crashLogger` and returns the unchanged conversation — the user's next turn is never blocked by a failed compaction. **Correct design.**

---

## 7. Findings Summary & Severity Triage

### P0 (must-fix)

1. **8 entities silently dropped on every backup/restore** (§2.1) — data loss bug. The user thinks their data is backed up; it isn't. Add `Backup` classes, mappers, and BackupManager wiring for: `CreativeSimulationEntity`, `ContinuityIssueEntity`, `ArtifactDependencyEntity`, `CreativeGenerationJobEntity`, `EvolutionEvidenceEntity`, `EvolutionCandidateEntity`, `ProactiveInteractionEntity`, `RoutingOutcomeEntity`.

2. **MemoryDatabase schema files 7.json–10.json missing** (§1.1) — schema drift. Re-export and commit them.

### P1 (should-fix)

3. **Compaction model selection uses name-length heuristic** (§6.2) — may pick wrong model. Add cost fields to `ModelInfo` or an explicit preference.

4. **ChatGPT and Custom OpenAI compat providers don't override `listModelsWithContext`** (§6.1) — compaction threshold is wrong for these providers. Override and provide accurate context windows.

5. **`ProactiveInteractionDao` lacks `insertAll` and `allForBackup`** (§2.1, §4.2) — required for fix #1. Add both methods.

### P2 (nice-to-fix)

6. **`BackupManager.restore()` is not transactional** (§3.9) — partial state possible on crash mid-restore. Wrap in `withTransaction { }`.

7. **Hands restoration is partially transactional** (§3.7) — wrap scheduling in try/catch or move to a single batch method.

8. **`MigrationRegistryAuditTest` doesn't verify the array** (§5.1) — only verifies the fields exist. Adopt `ALL_MIGRATIONS` pattern in all modules and have the test read it.

### P3 (informational)

9. **`AuraBackup.SCHEMA_VERSION = 12` lags `MemoryDatabase` v14** (§3.1) — intentional, but undocumented. Add a comment.

10. **AgentRun restored *after* goals in `BackupManager.restore()`** (§3.2) — verify with `PRAGMA foreign_keys = ON` that AgentRun → Goal FK isn't violated.

---

## 8. Files Referenced

- `D:\aura-android-clean\aura-core\src\main\kotlin\com\ura\backup\BackupManager.kt` (1198 lines)
- `D:\aura-android-clean\aura-core\src\main\kotlin\com\ura\backup\AuraBackup.kt` (633 lines)
- `D:\aura-android-clean\aura-core\src\main\kotlin\com\ura\backup\AuraBackupSchema12.kt`
- `D:\aura-android-clean\aura-core\src\main\kotlin\com\ura\memory\MemoryModule.kt` (lines 1-590 for migrations)
- `D:\aura-android-clean\aura-core\src\main\kotlin\com\ura\memory\MemoryDatabase.kt` (101 lines)
- `D:\aura-android-clean\aura-core\src\main\kotlin\com\ura\agent\ConversationCompactor.kt` (198 lines)
- `D:\aura-android-clean\aura-core\src\test\kotlin\com\ura\migration\MigrationRegistryAuditTest.kt` (191 lines)
- `D:\aura-android-clean\aura-core\schemas\com.aura.memory.MemoryDatabase\` (missing 7.json–10.json)
- All 10 `@Database` files (10 files)
- All 23 `*Dao.kt` / `*Daos.kt` files
- All 24 entity files

---

**End of Phase 1 Audit.**
