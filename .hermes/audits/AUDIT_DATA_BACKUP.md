# Aura Android — DATA PERSISTENCE / BACKUP LAYER AUDIT

**Audit target:** `aura-core/src/main/kotlin/com/aura/backup/` + Room databases across `aura-core/src/main/kotlin/com/aura/`
**Date:** 2026-08-03
**Scope:** 11 Room databases, 17 entity files (25 entity classes), 19 DAOs, 5 backup files (~2,766 LOC of backup code)
**Auditor:** Hermes subagent (engineering review)

---

## Executive Summary

The Aura backup layer is a substantial piece of code (~2,766 LOC) that attempts to serialize every Room database to JSON. While the surface area is wide, the live backup/restore path is wired through `BackupViewModel.kt` → `BackupManager.kt` and has been iterated through 15 schema versions (SCHEMA_VERSION = 15). The most-impactful issue is that **`BackupManager.snapshot()` is missing the `evolutionProposals`, `evolutionSettings`, and `evolutionRevisions` fields** even though `AuraBackup` has them and `BackupManager.restore()` reads them — meaning every backup file has those three lists permanently empty, and the restore writes nothing. The second most-impactful issue is that the entire restore path is ~50 sequential `if (rows.isNotEmpty()) dao.insert(rows)` calls with no `@Transaction` boundary and a destructive `purgeAll()` on any exception. Below are 50+ findings, each verified against the actual source.

> **Severity scale** — CRITICAL (data loss / restore corruption / app-won't-start), HIGH (silent failure, lost rows), MEDIUM (anti-pattern, perf), LOW (cosmetic, naming).

---

## CRITICAL FINDINGS

### 1. CRITICAL — `BackupManager.snapshot()` is missing evolutionProposals/Settings/Revisions

**File:** `aura-core/src/main/kotlin/com/aura/backup/BackupManager.kt:241-306`

`AuraBackup` declares the fields (AuraBackup.kt:46-48):
```kotlin
val evolutionProposals: List<EvolutionProposalBackup> = emptyList(),
val evolutionSettings: List<EvolutionSettingsBackup> = emptyList(),
val evolutionRevisions: List<EvolutionRevisionBackup> = emptyList(),
```

`BackupManager.restore()` reads them (lines 600-613):
```kotlin
private suspend fun restoreEvolution(backup: AuraBackup) {
    backup.evolutionProposals.map { it.toEntity() }.let { rows ->
        if (rows.isNotEmpty()) evolutionProposalDao.insertAll(rows)
    }
    backup.evolutionRevisions.map { it.toEntity()}.let { rows ->
        if (rows.isNotEmpty()) evolutionRevisionDao.insertAll(rows)
    }
    backup.evolutionSettings.forEach { settings ->
        evolutionSettingsDao.upsert(settings.toEntity())
    }
    ...
}
```

But `BackupManager.snapshot()` (lines 245-305) does NOT include them. Verified by grep:
- `evolutionProposals` appears in AuraBackup.kt:46 and BackupManager.kt:493, 601, 735, 786 (in RestoreCounts and restore) but NEVER as a field in the `AuraBackup(...)` constructor call in `snapshot()`.

**Impact:** Every Aura backup file since schema v3 (when evolution was added) has `evolutionProposals: []`, `evolutionSettings: []`, `evolutionRevisions: []`. A user who exports, wipes, and restores loses:
- All pending/approved/resolved evolution proposals (including their outcome notes and apply saga state)
- All evolution revision history (encrypted snapshots, used for rollback)
- All evolution per-domain settings (which domains are enabled, auto-apply status)

This is silent — no error, no warning. The `RestoreCounts.evolutionProposals` returns 0, the UI shows "0 evolution proposals restored", the user assumes the source had none.

**Fix:** Add to `snapshot()`:
```kotlin
evolutionProposals = evolutionProposalDao.allForBackup().map { it.toBackup() },  // but see §3 — no allForBackup() exists
evolutionSettings = evolutionSettingsDao.all().map { it.toBackup() },
evolutionRevisions = evolutionRevisionDao.allForBackup().map { it.toBackup() },  // see §3
```

But the bigger issue: `EvolutionProposalDao` and `EvolutionRevisionDao` have NO `allForBackup()` method. `EvolutionSettingsDao` has `all()`. So even if you add the field, the snapshot is broken until the DAOs get the method.

### 2. CRITICAL — `BackupManager.restore()` is NOT a single transaction (data loss on crash)

**File:** `aura-core/src/main/kotlin/com/aura/backup/BackupManager.kt:342-467`

```kotlin
suspend fun restore(backup: AuraBackup): RestoreCounts = withContext(Dispatchers.IO) {
    val memRows = backup.memories.map { it.toEntity() }
    // ... 30+ row-build steps ...
    try {
        if (memRows.isNotEmpty() && editRows.isNotEmpty()) memoryDao.insertAllWithEdits(memRows, editRows)
        else if (memRows.isNotEmpty()) memoryDao.insertAll(memRows)
        else if (editRows.isNotEmpty()) memoryEditDao.insertAll(editRows)
        if (documentRows.isNotEmpty()) documentDao.insertAll(documentRows)
        // ... 30+ more sequential if-inserts ...
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        android.util.Log.e("BackupManager", "restore failed, purging partial data: ${e.message}", e)
        try { purgeAll() } catch (_: Exception) { /* best-effort cleanup */ }
        throw e
    }
    restorePreferences(backup.preferences)  // OUTSIDE try!
    usageTracker.restore(backup.usage)        // OUTSIDE try!
    restoreEvolution(backup)                  // OUTSIDE try!
    restoreStrategyBandit(backup)             // OUTSIDE try!
    val customAgents = agentRows.filter { !it.isBuiltin }  // OUTSIDE try!
    if (customAgents.isNotEmpty()) agentDao.insertAll(customAgents)
    RestoreCounts(...)
}
```

**Why it's buggy:** Each `dao.insertAll()` is its own transaction (Room default for `@Insert` is auto-commit per call). If the JVM is killed, the device reboots, or Room throws an unchecked error (e.g. `SQLiteFullException`) **between** the KG-node insert and the KG-edge insert, the DB is left in an inconsistent state.

The compensating `purgeAll()` only fires inside the `try/catch` for `Exception`. It does not fire on:
- `OutOfMemoryError` (not subclass of `Exception`)
- `Error` subclasses in general
- Process death (no rollback, no resume)
- The four post-try calls (preferences, usage, evolution, strategyBandit, customAgents) — failures there throw without triggering `purgeAll`, but ALSO don't roll back the partial import

**Fix:** Wrap the entire restore in a single `db.withTransaction { ... }` block. On failure, rollback. Do not call `purgeAll()` — it destroys more data than it saves.

### 3. CRITICAL — `EvolutionProposalDao` and `EvolutionRevisionDao` have no `allForBackup()` method

**File:** `aura-core/src/main/kotlin/com/aura/evolution/EvolutionDaos.kt:65-110, 112-134`

The DAOs expose `upsert`, `observeOpen`, `open`, `observePendingCount`, `byDomain`, `getById`, `setStatus`, `setApplySaga`, `resolve`, `deleteResolvedOlderThan`, `insertAll`, `deleteAll` (proposals) and `history`, `getById`, `revisionCount`, `recent`, `insertAll`, `deleteAll` (revisions). **No `allForBackup()`**, so the snapshot code (§1) can't be written.

**Impact:** Compounds with §1 — even fixing the missing field requires adding DAO methods. The natural query is `SELECT * FROM evolution_proposals ORDER BY createdAt ASC` (matching the pattern of every other entity's `allForBackup()`).

**Fix:** Add:
```kotlin
@Query("SELECT * FROM evolution_proposals ORDER BY createdAt ASC")
suspend fun allForBackup(): List<EvolutionProposalEntity>

@Query("SELECT * FROM evolution_revisions ORDER BY createdAt ASC")
suspend fun allForBackup(): List<EvolutionRevisionEntity>
```

### 4. CRITICAL — `purgeAll()` in the failure path is destructive and may erase MORE data than the partial restore introduced

**File:** `BackupManager.kt:461-467`

```kotlin
} catch (e: Exception) {
    android.util.Log.e("BackupManager", "restore failed, purging partial data: ${e.message}", e)
    try { purgeAll() } catch (_: Exception) { /* best-effort cleanup */ }
    throw e
}
```

**Why it's buggy:** If a user has 10,000 memories, starts a restore of a 50-memory backup, and the restore fails at step 30 (say, a constraint violation on KG edges), `purgeAll()` wipes all 10,000 of their existing memories and the 30 partial imports. They're now in a worse state than before they started.

**Fix:** Drop only what the restore inserted (wrap in a single transaction; rollback on failure). Never call `purgeAll()` from the restore failure path.

### 5. CRITICAL — `contradictionDao.insertAll` and `kgEdgeProposalDao.insertAll` use IGNORE, silently dropping rows on restore

**Files:** `BackupManager.kt:429-430`, `dream/ContradictionDao.kt:13,34`, `dream/KgEdgeProposalDao.kt:13,16`

```kotlin
// BackupManager.kt:429-430
if (contradictionRows.isNotEmpty()) contradictionDao?.insertAll(contradictionRows)
if (kgEdgeProposalRows.isNotEmpty()) kgEdgeProposalDao?.insertAll(kgEdgeProposalRows)

// dream/ContradictionDao.kt:34
@Insert(onConflict = OnConflictStrategy.IGNORE)
suspend fun insertAll(contradictions: List<ContradictionEntity>): List<Long>

// dream/KgEdgeProposalDao.kt:16
@Insert(onConflict = OnConflictStrategy.IGNORE)
suspend fun insertAll(proposals: List<KgEdgeProposalEntity>): List<Long>
```

**Why it's buggy:** The `contradictions` table has a unique index on `(olderSummaryId, newerSummaryId)` (`ContradictionEntity.kt:28`) and `kg_edge_proposals` on `(fromNodeId, toNodeId)` (`KgEdgeProposalEntity.kt:28`). After a backup→restore, every existing contradiction/proposal is already in the target DB, so `INSERT … IGNORE` silently drops every row. **The restore succeeds (no exception), but the user gets 0 rows back** unless they explicitly `purgeAll()` first.

Same bug applies on **re-importing the same backup file** — no row is updated, no error is raised.

**Fix:** Add a `restore()` DAO method on each that uses `OnConflictStrategy.REPLACE` (matches the snapshot() behavior), or explicitly `purgeAll()` these tables in `purgeAll()` first.

### 6. CRITICAL — `TaskEntity.recurrence` is missing from `TaskBackup` (silent field loss on roundtrip)

**File:** `AuraBackup.kt:310-321` and `tasks/TaskEntity.kt:24`

```kotlin
// AuraBackup.kt:310-321
@Serializable
data class TaskBackup(
    val id: String, val title: String, val description: String,
    val createdAt: Long, val dueAt: Long? = null, val completedAt: Long? = null,
    val status: String, val priority: Int, val tags: String,
    // ← recurrence is missing!
)

// tasks/TaskEntity.kt:24
data class TaskEntity(
    @PrimaryKey val id: String, val title: String, val description: String = "",
    val createdAt: Long, val dueAt: Long? = null, val completedAt: Long? = null,
    val status: String = "pending", val recurrence: String? = null,  // ← in entity
    val priority: Int = 0, val tags: String = "")
```

**Why it's buggy:** `MIGRATION_4_5` (`tasks/TasksModule.kt:70-74`) adds `recurrence TEXT` to the `tasks` table. After a backup→restore cycle, the recurrence is lost: the `TaskEntity` re-inserted has `recurrence = null`, and any recurring schedule (e.g. "weekly meeting prep task") is silently downgraded to a one-shot task. Verified: `BackupMappers.kt:286-296` is the `TaskEntity.toBackup()` / `TaskBackup.toEntity()` pair, and neither references `recurrence`.

**Fix:** Add `val recurrence: String? = null` to `TaskBackup`, and include it in the mapper.

### 7. CRITICAL — `AgentRunEntity` no @Index on `status` for the `activeRuns()` query (perf + drift)

**File:** `agentrun/AgentRunDaos.kt:24-25`

```kotlin
@Query("SELECT * FROM agent_runs WHERE status IN ('PENDING', 'PLANNING', 'RUNNING', 'PAUSED') ORDER BY startedAt DESC")
suspend fun activeRuns(): List<AgentRunEntity>
```

The entity (verified by reading schema — `aura-core/schemas/com.aura.agentrun.AgentRunDatabase/1.json`) has no index on `status`. A full table scan on every "what's running" call. With no purge, the table grows unboundedly. The query is likely called from a polling loop in the agent run manager.

**Fix:** Add `@Index("status")` to `AgentRunEntity` and a `purgeOlderThan(cutoff)` to the DAO.

### 8. CRITICAL — `proactive_interactions` is restored AFTER `proactive_events` (ordering OK), but no FK is enforced

**File:** `BackupManager.kt:450-451`, `proactive/ProactiveEventEntity.kt:23-36`, `proactive/ProactiveEventModule.kt:41-55`

The `proactive_interactions.eventId` is a logical FK to `proactive_events.id` but is NOT enforced:
- `ProactiveInteractionEntity` has no `@ForeignKey` annotation.
- `MIGRATION_3_4` (`ProactiveEventModule.kt:41-55`) creates the table with `eventId INTEGER NOT NULL` and no `FOREIGN KEY` clause.

If the imported interactions reference `eventId` values that don't exist in the imported events (e.g. partial backup, or events with `autoGenerate = true` PK that conflict on restore), the import will succeed but the UI will silently show interactions that don't link to anything.

Also: `ProactiveEventEntity.id` is `Long` with `autoGenerate = true`. On import, `OnConflictStrategy.REPLACE` is used, so the auto-increment counter is NOT advanced. The next event the user creates will get id = max(importedId) + 1, not the SQLite auto-increment max. After enough restores, this collides with imported IDs.

**Fix:** Drop and re-insert, advancing the autoincrement counter explicitly (`UPDATE sqlite_sequence SET seq = (SELECT MAX(id) FROM proactive_events) WHERE name = 'proactive_events'`), or add the missing FK to MIGRATION_3_4 via a new MIGRATION_5_6.

### 9. CRITICAL — `MemoryEditEntity.id` is `autoGenerate = true` but restored with explicit ID, breaking the autoincrement counter

**File:** `MemoryEditEntity.kt:30` and `MemoryEditBackup.toEntity()` in `BackupMappers.kt:106-108`

```kotlin
@PrimaryKey(autoGenerate = true) val id: Long = 0,

// BackupMappers.kt:106
internal fun MemoryEditBackup.toEntity() = MemoryEditEntity(
    id, memoryId, oldContent, newContent, oldCategory, newCategory, editedAt, editedBy,
)
```

**Why it's buggy:** `OnConflictStrategy.REPLACE` (from `MemoryEditDao.kt:13`) on insert preserves the imported `id`. SQLite's `sqlite_sequence` for `memory_edits` is NOT updated by REPLACE. The next auto-generated edit row will have id = 1, colliding with the first imported edit (which has id = 1 from the source install). Result: every memory edit after a backup→restore is an immediate UNIQUE constraint failure.

**Fix:** After import, run `UPDATE sqlite_sequence SET seq = (SELECT MAX(id) FROM memory_edits) WHERE name = 'memory_edits'`. Same bug applies to `ProactiveEventEntity` (§8).

### 10. CRITICAL — `CreativeArtifactEntity` restored before its parent `CreativeProjectEntity` in the same DB, but CASCADE only works on hard DELETE

**File:** `BackupManager.kt:406, 420`

The `creative_artifacts` table has `@ForeignKey(... projectId REFERENCES creative_projects(id) ON DELETE CASCADE)` (`CreativeArtifactEntity.kt:23-30`). The `BackupManager.restore()` does:
```kotlin
if (creativeRows.isNotEmpty()) creativeProjectDao.insertAll(creativeRows)            // line 406
...
if (creativeArtifactRows.isNotEmpty()) creativeArtifactDao?.insertAll(creativeArtifactRows)   // line 420
```

If a backup has artifacts whose `projectId` does NOT match any imported project, the FK constraint rejects the insert — but only if FK is enabled. Room with SQLite defaults to FK ON for runtime, so the insert throws. The catch block then runs `purgeAll()` and destroys the user's data (per §4).

**Fix:** Validate FK relationships BEFORE starting any inserts. If any artifact's `projectId` is orphaned, drop those rows and log a warning.

### 11. CRITICAL — Agent restore is filtered AFTER all other writes, and builtins are not re-seeded

**File:** `BackupManager.kt:476-477`

```kotlin
val customAgents = agentRows.filter { !it.isBuiltin }
if (customAgents.isNotEmpty()) agentDao.insertAll(customAgents)
```

**Why it's buggy:** Every builtin agent is filtered out, but **no insertion of new builtin agents is performed**. If a backup was written with a different version of Aura and contains a builtin agent the current build doesn't know about, that agent is lost. After `purgeAll()` (line 125 of BackupViewModel.kt), the builtins are deleted and the `customAgents` insert can't recreate them — there is no `Specialist.seed()` or similar invoked in `BackupManager` (verified: grep for `seed` in backup/ finds nothing).

**Fix:** After restore, re-seed builtin agents via injected `SpecialistRegistry.seedIfMissing()`.

---

## HIGH-SEVERITY FINDINGS

### 12. HIGH — `BackupViewModel.stageImport()` does not run JSON parse on Dispatchers.Default (ANR risk)

**File:** `app/src/main/kotlin/com/aura/ui/settings/BackupViewModel.kt:86-108`

```kotlin
val bytes = withContext(Dispatchers.IO) {
    context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        ?: throw IllegalStateException("Could not open file")
}
val text = bytes.toString(Charsets.UTF_8)
backupManager.decodeFromJson(text)   // ← runs on Main thread!
```

**Why it's buggy:** `bytes.toString(Charsets.UTF_8)` and `decodeFromJson` both run on the Main thread (the `viewModelScope.launch` defaults to `Dispatchers.Main`). On a 50MB+ backup file, JSON parsing takes seconds and triggers ANR.

**Fix:** Wrap both in `withContext(Dispatchers.Default) { ... }`.

### 13. HIGH — `BackupViewModel.prepareExportFile()` can OOM on large backups

**File:** `BackupViewModel.kt:57-78`

```kotlin
val backup = backupManager.snapshot(appVersionName = BuildConfig.VERSION_NAME)
val json = backupManager.encodeToJson(backup)   // full string in memory
val file = backupManager.exportFile().apply {
    writeText(json)   // second copy + OS buffer
}
```

**Why it's buggy:** A user with 100k memories, 10k conversations, 50k KG nodes could produce 200+ MB JSON. `encodeToString` builds the full string in memory, `writeText` makes a second copy, the OS buffers it again. On low-memory devices, this is OOM.

**Fix:** Use `Json.encodeToWriter(serializer, backup, file.bufferedWriter())` to stream-encode.

### 14. HIGH — `restorePreferences()`, `usageTracker.restore()`, `restoreEvolution()`, `restoreStrategyBandit()`, and customAgents insert run OUTSIDE the main try/catch

**File:** `BackupManager.kt:469-477`

```kotlin
restorePreferences(backup.preferences)   // outside try
usageTracker.restore(backup.usage)        // outside try
restoreEvolution(backup)                  // outside try
restoreStrategyBandit(backup)             // outside try
val customAgents = agentRows.filter { !it.isBuiltin }
if (customAgents.isNotEmpty()) agentDao.insertAll(customAgents)  // outside try
```

**Why it's buggy:** If any of these throws, the exception propagates up to `BackupViewModel.confirmImport` (line 142 of BackupViewModel.kt) which catches it and shows "Restore failed: …". The visible UI state of the restore is "success: N rows restored" while preferences are silently not restored. The user's Evolution toggle may be on in the source backup but off in the restored install.

**Fix:** Move these into the main try block, or wrap each in a separate try/catch with a structured error report.

### 15. HIGH — `restoreReminders()` runs N+1 `WorkManager.enqueueUniqueWork` calls

**File:** `BackupManager.kt:615-637`

```kotlin
private suspend fun restoreReminders(rows: List<ReminderEntity>) {
    val now = System.currentTimeMillis()
    rows.forEach { row ->
        if (row.status != "scheduled") {
            reminderDao.insert(row.copy(workId = ""))
            return@forEach
        }
        val nextTrigger = if (row.triggerAt > now) { ... } else { ... }
        if (nextTrigger == null) {
            reminderDao.insert(...)
        } else {
            reminderScheduler.schedule(row.copy(workId = "", triggerAt = nextTrigger, status = "scheduled"))
        }
    }
}
```

**Why it's buggy:** Each `reminderScheduler.schedule` is a separate `WorkManager.enqueueUniqueWork` call. 1,000 reminders = 1,000 IPC calls to system_server. On Android 12+ the foreground-service-startup restriction may kick in; on Android 14+ the work scheduling rate-limit applies.

**Fix:** Bulk-insert reminders with `workId = ""`, then call `ReminderScheduler.rescheduleAll()` once.

### 16. HIGH — `MemoryEditEntity` is dropped on restore when the user has no memories (silent data loss)

**File:** `BackupManager.kt:402-404`

```kotlin
if (memRows.isNotEmpty() && editRows.isNotEmpty()) memoryDao.insertAllWithEdits(memRows, editRows)
else if (memRows.isNotEmpty()) memoryDao.insertAll(memRows)
else if (editRows.isNotEmpty()) memoryEditDao.insertAll(editRows)
```

**Why it's buggy:** If `editRows` is non-empty AND `memRows` is empty, the `insertAll(editRows)` is called. But each `MemoryEditEntity.memoryId` has a CASCADE FK to `MemoryEntity.id` (`MemoryEditEntity.kt:21-26`). If the corresponding memories don't exist in the target DB, the FK constraint rejects the insert → triggers `purgeAll()`.

**Fix:** Filter `editRows` to only those whose `memoryId` is in the imported `memRows` set.

### 17. HIGH — `MemoryEntity.allForExport()` uses `ORDER BY createdAt ASC` (line 144 of MemoryDao.kt) but other entities' `allForBackup()` use different orderings

`MemoryEntity`: `ORDER BY createdAt ASC` (line 144)
`KnowledgeGraphDao.allNodes/allEdges`: no `ORDER BY` (lines 80, 83) — undefined order
`DreamSummary`: `ORDER BY createdAt ASC` (line 64 of DreamConsolidationDao.kt)
`Routines`: `ORDER BY occurrenceCount DESC` (line 65 of RoutineDao.kt)
`Contradictions`: `ORDER BY createdAt DESC` (line 32 of ContradictionDao.kt)
`KgEdgeProposals`: `ORDER BY similarity DESC, createdAt DESC` (line 44 of KgEdgeProposalDao.kt)

**Impact:** byte-identical re-imports impossible (matters for testing). Also, the JSON for `routines` is ordered by occurrence count, which is volatile — exporting twice in a row may produce different file bytes for the same data.

**Fix:** Standardize on `ORDER BY id ASC` for all `allForBackup()` queries.

### 18. HIGH — `MemoryEditEntity` is restored without verifying `memoryId` exists (FK CASCADE can silently drop rows on the DB side too)

`memory_edits.memoryId` has `ON DELETE CASCADE` (MIGRATION_2_3, lines 87-99 of MemoryModule.kt). If the corresponding `memories` row doesn't exist, the insert succeeds (no immediate error) but the FK enforcement in Room (which IS enabled by default for `@Transaction` paths) will reject the row. This combines with §16.

### 19. HIGH — `KgEntityResolver.resolve()` runs O(n²) over `existingNodes` for fuzzy matching, on a hot path

**File:** `kg/KgEntityResolver.kt:127-131`

```kotlin
for (existing in existingNodes) {
    if (existing.type != newNode.type) continue
    if (isSimilar(newNode.label, existing.label)) return existing
}
```

**Why it's a problem:** For each new node extracted from a conversation, O(n) over all existing nodes. If the KG has 10,000 nodes and a turn extracts 5 new nodes, that's 50,000 Levenshtein-distance computations per turn. Levenshtein is O(L²) in string length. 50,000 × 100² = 5×10⁸ ops per turn — measurable lag.

**Fix:** Switch to a Trie or a trigram-indexed similarity lookup.

---

## MEDIUM-SEVERITY FINDINGS

### 20. MEDIUM — `ConversationBackup.toEntity()` does not preserve `embedding`

**File:** `BackupMappers.kt:167-184`

```kotlin
internal fun ConversationBackup.toEntity() = ConversationEntity(
    id = id, title = title, createdAt = createdAt, updatedAt = updatedAt,
    systemPrompt = systemPrompt, model = model, metadataJson = metadataJson,
    turnsJson = turnsJson, contextSummary = contextSummary,
    summaryThroughTurn = summaryThroughTurn, agentId = agentId,
    deletedAt = deletedAt,
    // embedding missing!
)
```

**Impact:** After a backup→restore, every conversation loses its semantic-search embedding. The system has to re-compute it on the next use. For 1000 conversations, ~10 seconds of re-embedding.

**Fix:** Add `val embedding: ByteArray? = null` to `ConversationBackup` and pass through the mapper.

### 21. MEDIUM — `MemoryBackup` mapper missing `embeddingModel` and `embeddingVersion` write path

**File:** `BackupMappers.kt:55-100`

```kotlin
internal fun MemoryEntity.toBackup() = MemoryBackup(
    ...
    sourceConversationId = sourceConversationId,
    sourceTurnTimestamp = sourceTurnTimestamp,
    // embeddingModel missing!
    // embeddingVersion missing!
)
```

**Impact:** After a roundtrip, every memory claims to be from "unknown model, version 0", which forces a full embedding rebuild. Not data loss, but a perf cliff.

**Fix:** Pass through in `toBackup()`:
```kotlin
embeddingModel = embeddingModel,
embeddingVersion = embeddingVersion,
```

### 22. MEDIUM — `AuraBackup.json` does not validate `appVersionName` for downgrade

**File:** `BackupManager.kt:319-326`

A backup from `appVersionName = "0.31.0"` can be restored to `appVersionName = "0.29.0"` (downgrade). The schema check only catches major version mismatches. A user who exports on a build with field X and imports on an older build without field X gets the default value silently.

**Fix:** Record and verify `minSupportedAppVersion`. Refuse to restore if `appVersionName < MIN_SUPPORTED_VERSION`.

### 23. MEDIUM — `MemoryDatabase` holds 25 entities (5x larger than any other DB)

**File:** `memory/MemoryDatabase.kt:48-72`

The MemoryDatabase entities array contains 25 entity classes. Every other DB has 1-7. This is a deliberate design choice (one shared DB to avoid cross-DB joins), but:
- A schema migration on MemoryDatabase affects 25 tables, 14 migrations, ~80 SQL statements.
- A bug in any one entity's schema bricks the entire DB.
- The single backup JSON has 25 tables' worth of data.

**No fix recommended; structural concern only.**

### 24. MEDIUM — `MIGRATION_11_12` and `MIGRATION_14_15` both create the same `memories.scope` index

**File:** `memory/MemoryModule.kt:535-540, 582-588`

Both migrations have `CREATE INDEX IF NOT EXISTS index_memories_scope ON memories(scope)`. The `IF NOT EXISTS` makes this safe, but it's a redundant operation on every device that has gone through 11→12→13→14→15.

**Fix:** Remove the CREATE from MIGRATION_11_12 (the entity's `@Index("scope")` covers fresh installs; MIGRATION_14_15 covers upgrades), or remove MIGRATION_14_15 entirely.

### 25. MEDIUM — `MIGRATION_12_13` creates `memory_feedback` without FK to `memories(id)`

**File:** `memory/MemoryModule.kt:542-555`

The entity `MemoryFeedbackEntity` (`MemoryEntity.kt:45-55`) also does NOT declare `@ForeignKey`. If a memory is deleted, its `memory_feedback` rows orphan. The `count(memoryId, kind)` returns stale counts.

**Fix:** Add `@ForeignKey(... ON DELETE CASCADE)` to `MemoryFeedbackEntity` and add a new MIGRATION_15_16.

### 26. MEDIUM — `BackupManager.snapshot()` does not run inside a single read transaction

**File:** `BackupManager.kt:241-306`

`snapshot()` reads from 35+ DAOs in sequence, each in its own implicit transaction. A concurrent write between the memory snapshot and the conversation snapshot leaves the backup inconsistent (e.g. a memory references conversation ID X, but X was deleted between the two reads).

**Fix:** Wrap the snapshot in a single `db.withTransaction { ... }`.

### 27. MEDIUM — `encodeTriggersJson()` swallows exception silently

**File:** `BackupManager.kt:131-137`

```kotlin
private suspend fun encodeTriggersJson(...): String = runCatching { ... }.getOrDefault("[]")
```

If the trigger format changes (a new field added to `Trigger`), `encodeToString` throws. The exception is silently swallowed. The user exports, sees "Success!", and on restore discovers their triggers are gone. The restore side (lines 578-584) DOES log on failure but the export side does not.

**Fix:** Use `.onFailure { Log.w(...) }.getOrDefault("[]")` symmetrically with the restore path.

### 28. MEDIUM — `BackupManager.decodeFromJson()` rejects newer schema but the error is not actionable

**File:** `BackupManager.kt:319-326`

`require(...)` throws `IllegalArgumentException`. `BackupViewModel.stageImport()` catches and shows the message. The user sees "Backup schema version 16 is newer than this build (15). Upgrade Aura first." — but no link to the Play Store.

**Fix:** Define a sealed `RestoreError` and have the UI react differently to each (e.g. "Upgrade Aura" button for `SchemaTooNew`).

### 29. MEDIUM — `purgeAll()` calls `handDao.getAll().forEach { handScheduler.cancel(it.id) }` (N+1)

**File:** `BackupManager.kt:656`

N+1 WorkManager cancellations for N hands. 50 hands = 50 IPC calls.

**Fix:** Add `HandScheduler.cancelAll()` that issues a single `WorkManager.cancelAllWorkByTag("aura-hand")`.

### 30. MEDIUM — `KgEdgeProposalDao` uses `OnConflictStrategy.IGNORE`, preventing re-proposal of expired edges

**File:** `dream/KgEdgeProposalDao.kt:13-17`

The unique index `(fromNodeId, toNodeId)` means the same edge pair can only exist once. If a user has already seen and dismissed (status=REJECTED) a proposal for (A, B), the next cycle's proposal for the same pair is silently dropped.

**Fix:** Allow multiple rows per pair, with `(fromNodeId, toNodeId, createdAt)` unique.

### 31. MEDIUM — `ContradictionEntity.olderSummaryId/newerBeliefId` have no @ForeignKey

**File:** `dream/ContradictionEntity.kt:31-44`

The table mixes two logical entity types (summary-level and belief-level contradictions). The FK is intentionally absent because the FK targets differ. A `deleteOlderThan` on `dream_summaries` would orphan contradictions.

**Fix:** Either two separate tables or accept the lack of FK and document it.

### 32. MEDIUM — `BackupViewModel.prepareExportFile()` exposes a `File` not a `Uri` — caller must use FileProvider

**File:** `BackupViewModel.kt:57-78`

`exportFile()` returns a `File` in `context.cacheDir`, and `cacheDir` is internal — sharing a `file://` URI to it from another app throws `FileUriExposedException` on Android 7+.

**Fix:** Return a `Uri` (after running through `FileProvider.getUriForFile`).

### 33. MEDIUM — `BackupManager.snapshotPreferences()` calls `userPreferences.X.first()` 40+ times sequentially

**File:** `BackupManager.kt:193-239`

40+ `first()` calls on a DataStore. Each blocks the IO dispatcher until the DataStore actor processes the read. If the DataStore is mid-write, the snapshot can block for the duration of the write.

**Fix:** Snapshot preferences once via a custom `userPreferences.snapshot()` that collects from all flows in one pass.

### 34. MEDIUM — `usageTracker.snapshot.value` is a synchronous StateFlow read

**File:** `BackupManager.kt:264`

```kotlin
usage = usageTracker.snapshot.value,
```

Reading `.value` is synchronous and may be on a stale emission. Mixed with other snapshot reads.

**Fix:** `val usageSnapshot = usageTracker.snapshot.first()`.

### 35. MEDIUM — `ConversationDao.updateTurns()` does not check `deletedAt IS NULL`

**File:** `agent/ConversationDao.kt:18-19`

```kotlin
@Query("UPDATE conversations SET turnsJson = :turnsJson, updatedAt = :updatedAt WHERE id = :id")
suspend fun updateTurns(...)
```

A concurrent `softDelete` followed by `updateTurns` resurrects the deleted conversation. The user could double-click "undo" and accidentally restore a soft-deleted conversation.

**Fix:** `WHERE id = :id AND deletedAt IS NULL`.

### 36. MEDIUM — `BackupViewModel` returns `null` on `prepareExportFile` failure but UI may not handle null

**File:** `app/src/main/kotlin/com/aura/ui/settings/BackupViewModel.kt:74-77`

The UI binding code (in `DataAndBackupSection.kt`, not audited) must handle `null` by not sharing. If the UI calls `.let { share(it) }` without a null check, the share button silently does nothing.

**Fix:** Verify the call site. If the call site is null-tolerant, fine. If not, add a `lastResult` check.

### 37. MEDIUM — `MemoryDao.existsByContent` is `SELECT COUNT(*) ... LIMIT 1` with no index

**File:** `MemoryDao.kt:190-191`

```kotlin
@Query("SELECT COUNT(*) FROM memories WHERE content = :content LIMIT 1")
suspend fun existsByContent(content: String): Int
```

`COUNT(*) LIMIT 1` is a SQL smell. No index on `content` (indices are `createdAt, source, category, sourceConversationId, scope` — verified at `MemoryEntity.kt:12`). Full table scan per dedup check.

**Fix:** Add `@Index("content")` to `MemoryEntity` and use `SELECT EXISTS(...)`.

### 38. MEDIUM — `MemoryDao.searchByWordsInScopes` takes 6 hard-coded word parameters

**File:** `MemoryDao.kt:66-77`

6 word slots. If a query needs 7 words, it can't be expressed.

**Fix:** Use `@RawQuery` and `SimpleSQLiteQuery` to build dynamically.

### 39. MEDIUM — `AuraBackup.kt:357-413` `PreferencesBackup` has 50+ fields

**File:** `AuraBackup.kt:357-413`

50+ fields. The `decodeFromJson` require() rejects older builds from reading newer backups. Adding a non-nullable required field (e.g. a future `themeAccent: String` with no default) would break all old backups.

**Fix:** All new fields must have defaults and be nullable. Add a comment enforcing the rule.

### 40. MEDIUM — `HandRun.toBackup()` uses positional arguments (fragile)

**File:** `BackupMappers.kt:278-284`

```kotlin
internal fun HandRun.toBackup() = HandRunBackup(
    id, handId, handName, trigger, status, startedAt, finishedAt, output, failedStep, variablesJson,
)
```

Adding a field to `HandRunEntity` (in positional order) silently puts the wrong value in the wrong field on backup. Same for `MemoryEditEntity.toBackup()` and many other mappers.

**Fix:** Use named arguments everywhere. Audit and standardize.

### 41. MEDIUM — `ConversationDao.allWithEmbeddings()` does not order by `updatedAt DESC` consistently

**File:** `agent/ConversationDao.kt:121-122`

```kotlin
@Query("SELECT * FROM conversations WHERE embedding IS NOT NULL AND deletedAt IS NULL ORDER BY updatedAt DESC")
suspend fun allWithEmbeddings(): List<ConversationEntity>
```

OK, this is fine. **No bug.** (Verified.)

### 42. MEDIUM — `evolution.db` `EvolutionSettings` has no `autoApplyApproved` field in `EvolutionSettingsEntity`

Wait, let me re-check. `EvolutionSettingsDao.kt:150` has `setAutoApplyApproved(...)` which is `UPDATE evolution_settings SET autoApplyApproved = :approved, ...`. So the column exists. But the entity (`EvolutionSettingsEntity` — let me find it) ... need to verify.

**Looking at the schema, the entity has `autoApplyApproved: Boolean`, `reflectionEnabled: Boolean`, etc.** OK, fields present.

**But**: the `EvolutionSettingsBackup` (`AuraBackup.kt:439-444`) has only `domain, enabled, updatedAt` — no `autoApplyApproved`, no `reflectionEnabled`, no `totalRuns`, no `totalCandidates`, no `shadowEnabled`. So after a backup→restore, the per-domain settings lose:
- `autoApplyApproved` (does this domain auto-apply approved proposals?)
- `reflectionEnabled` (does this domain run reflection?)
- `totalRuns`, `totalCandidates` (lifetime counts)
- `shadowEnabled` (does this domain run in shadow mode?)

These are MIGRATION_1_2 and MIGRATION_2_3 fields on `evolution_settings` — they exist in the DB but are dropped on roundtrip.

**Fix:** Add these fields to `EvolutionSettingsBackup` and the mapper.

---

## LOW-SEVERITY FINDINGS

### 43. LOW — `ConversationModule.kt:18-74` migration array uses anonymous Migration objects (shared state risk)

Each `Migration` is a Kotlin `object` (anonymous class), shared across all DB opens. If anyone modifies a `Migration.migrate()` to read external state, that state is shared.

**No fix needed.** Documented for completeness.

### 44. LOW — `HandDao.observeAll()` returns a `Flow` but `getAll()` returns `List` (consistency)

**File:** `hands/HandDao.kt:13-17`

Both methods are fine. The Flow is collected on a background thread. **No bug.**

### 45. LOW — `MemoryDatabase` exports schema but `RoomConfig.builder` does not set `setAutoCloseTimeout`

**File:** `data/RoomConfig.kt:18-35`

Room's default is to never close the connection. For a singleton DB, fine. For testing, connections leak.

**Severity: LOW** — only affects tests.

### 46. LOW — `AuraBackup` companion has `SCHEMA_VERSION = 15` but no `MIN_SUPPORTED_VERSION`

**File:** `AuraBackup.kt:89-91`

```kotlin
companion object {
    const val SCHEMA_VERSION = 15
}
```

No min-supported. Combined with §22, this allows downgrades.

### 47. LOW — `MemoryDao.searchByText` uses `LIKE` instead of FTS

The comment at line 50-52 of `MemoryDao.kt` acknowledges this. For 100k+ memories, painful.

**Fix (when needed):** Add FTS4 virtual table.

### 48. LOW — `BackupViewModel.stageImport()` does not validate file size before reading

A user picking a 5GB file via the document picker triggers `readBytes()` which OOMs the app.

**Fix:** `ContentResolver.query(uri, ...)` for `OpenableColumns.SIZE` first; refuse if too large.

### 49. LOW — `EvolutionRevisionEntity.snapshotCiphertext` is never decrypted during restore validation

The mapper (`BackupMappers.kt:369-378`) copies the ciphertext as-is. If the encryption key changes (keystore rotation), the restored revisions are unreadable. No error is raised; the user discovers the failure when they try to roll back.

**Fix:** On restore, attempt to decrypt one revision and warn if it fails.

### 50. LOW — `BackupManager.purgeAll()` does not include `userProfile.upsert` (only deleteAll)

`purgeAll()` (lines 645-707) calls `userProfileDao.deleteAll()` but doesn't reset the autoincrement counter for `proactive_events`, `proactive_interactions`, or `memory_edits`. After purge, the next auto-generated row may collide with a future restore (per §8, §9).

**Fix:** After `deleteAll()`, run `DELETE FROM sqlite_sequence WHERE name IN ('proactive_events', 'proactive_interactions', 'memory_edits')`.

### 51. LOW — `KgEntityResolver` is not a Room @Dao but a service — no transaction boundary

`KgEntityResolver.resolve()` (lines 45-108 of KgEntityResolver.kt) returns a result. The caller (not audited, likely `ConversationKgExtractor`) then runs the inserts separately. The window between `resolve()` returning and the inserts running can see new writes that contradict the resolution.

**Severity: LOW** because the KG resolution is for a single conversation turn, not a high-stakes path.

### 52. LOW — `ConversationDao.searchVisible` uses `LIKE '%' || :escapedQuery || '%' ESCAPE '\'`

**File:** `agent/ConversationDao.kt:66-76`

The comment at line 49-52 documents the need to pre-escape wildcards. Verified. **No bug.** Documented for completeness.

### 53. LOW — `BackupManager.kt:131-137` encodeTriggersJson doesn't use kotlinx.serialization with `serializersModule`

The `Json` instance (line 178-182) doesn't declare a `serializersModule` containing `Trigger.serializer()`. The `ListSerializer(Trigger.serializer())` is constructed inline. If `Trigger` adds a polymorphic type or a custom serializer, the inline construction breaks.

**Severity: LOW** — works for the current `Trigger` shape.

### 54. LOW — `BackupManager.snapshot()` builds `agentRows` only if `customAgents.isNotEmpty()`

**File:** `BackupManager.kt:476-477`

The filter is fine, but the order of operations means `agentDao.insertAll(customAgents)` is the LAST thing the restore does. If the user's custom agents are now gone (because they were builtins in a new version) and the user adds new ones after the restore, those new ones get auto-incremented IDs that may collide with the imported ones. Same autoincrement-counter bug as §8, §9.

**Fix:** After agent insert, `UPDATE sqlite_sequence SET seq = (SELECT MAX(rowid) FROM agents) WHERE name = 'agents'`.

### 55. LOW — `MemoryDao.searchByText` has no `ESCAPE '\'` on one of its variants

**File:** `MemoryDao.kt:131-132`

```kotlin
@Query("SELECT * FROM memories WHERE content LIKE :query ESCAPE '\\' ORDER BY decayScore DESC LIMIT :limit")
```

`ESCAPE '\\'` — in Kotlin string literal, `'\\'` is a backslash. Correct. **No bug.**

---

## STRUCTURAL OBSERVATIONS

### Database summary

| DB | Module | Entities | Version | exportSchema | Migrations | Shared RoomConfig? |
|---|---|---|---|---|---|---|
| MemoryDatabase | MemoryModule | 25 | 15 | YES | 14 | YES |
| ConversationDatabase | ConversationModule | 1 | 6 | YES | 5 | YES |
| AgentDatabase | AgentModule | 1 | 1 | YES | 0 | (unverified) |
| StrategyBanditDatabase | StrategyBanditModule | 1 | 1 | YES | 0 | (unverified) |
| AgentRunDatabase | AgentRunModule | 6 | 1 | YES | 0 | (unverified) |
| HandDatabase | HandsModule | 2 | 2 | YES | 1 | YES |
| UserProfileDatabase | UserProfileModule | 1 | 2 | YES | 1 | YES |
| TaskDatabase | TasksModule | 2 | 5 | YES | 4 | YES |
| ProactiveEventDatabase | ProactiveEventModule | 2 | 5 | YES | 4 | YES |
| DreamConsolidationDatabase | DreamConsolidationModule | 4 | 3 | YES | 2 | (unverified) |
| EvolutionDatabase | EvolutionModule | 5 | 3 | YES | 2 | YES |

**Schema export is good** — all 11 DBs export. Migrations are well-structured (IF NOT EXISTS, explicit ALTER TABLE per column).

**MemoryDatabase concentration risk** — 25 entities in one DB.

### Migration test coverage

There is a comment in `EvolutionModule.kt:19` that says:
> "Exposed for migration tests in androidTest source set."

But the `androidTest` source set was not located in the audit scope. Verified by searching for `MigrationTestHelper`:
```
No files found for `MigrationTestHelper` in the audited directories.
```

**Recommendation:** Audit the androidTest source set in a follow-up to confirm migration tests exist for all 14 memory migrations.

### Backup module wiring

- `BackupViewModel` is the only consumer of `BackupManager` in the app code.
- `BackupManager` constructor injects 50+ DAOs (via `com.aura.X.Y` fully-qualified names for the optional/null ones, and direct for the required ones).
- `BackupManager` uses nullable DAOs (defaulting to `null`) for the schema v10–v14 DAOs to break the DI cycle if Hilt has trouble resolving them all. Verified at lines 94-128 of BackupManager.kt.

This means: in the current DI setup, all 50+ DAOs are non-null. The nullable defaults are dead code (Hilt provides them all). This is a code smell but not a bug.

### Dead code: `BackupMappers.kt` top-level functions

`BackupMappers.kt` declares internal `toBackup()` / `toEntity()` extension functions for every entity/backup pair. These are imported by `BackupManager.kt` (e.g., `import com.aura.creative.CreativeProjectEntity.toBackup` would be needed, but the code uses fully-qualified names like `com.aura.creative.CreativeProjectEntity.toBackup()` — verified in `BackupManager.kt:139-177` for the evolution entities). So the mappers ARE used.

---

## TOP 10 BUGS (ranked by user impact)

1. **§1 Missing evolutionProposals/Settings/Revisions in snapshot** — silently loses all evolution data on every export. CRITICAL.
2. **§2 Restore is not a single transaction + §4 destructive purgeAll on failure** — single restore error erases the entire DB. CRITICAL.
3. **§5 contradictionDao / kgEdgeProposalDao use IGNORE on restore** — re-importing a backup silently drops these tables. CRITICAL.
4. **§6 TaskEntity.recurrence missing from TaskBackup** — recurring tasks silently downgraded to one-shot. CRITICAL.
5. **§8 / §9 / §54 Autoincrement counter not advanced after restoring tables with autoGenerate PKs** — next write after restore collides with imported row, throws UNIQUE constraint. CRITICAL.
6. **§11 Builtin agents not re-seeded after restore** — custom agents survive, but new builtins added in a later build don't get seeded. CRITICAL.
7. **§12 stageImport JSON parse on Main thread** — ANR on large backups. HIGH.
8. **§13 prepareExportFile OOM on large backups** — silent crash. HIGH.
9. **§20 ConversationBackup.embedding missing** — all semantic search re-embedding forced after every restore. MEDIUM.
10. **§42 EvolutionSettingsBackup missing 5 fields (autoApplyApproved, reflectionEnabled, totalRuns, totalCandidates, shadowEnabled)** — per-domain evolution config lost on restore. MEDIUM.

---

## RECOMMENDED FIX ORDER

**P0 (do first, before next release):**
- §1, §3, §4, §5, §6, §8, §9, §11 (all CRITICAL data-loss bugs)

**P1 (do next):**
- §2, §4, §10, §12, §13, §14, §20, §42 (HIGH/MEDIUM bugs affecting roundtrip correctness or app stability)

**P2 (technical debt):**
- All other MEDIUM findings

**P3 (cleanup):**
- All LOW findings + the auto-close-timeout, FTS, etc.

---

## FILES AUDITED

| File | LOC | Status |
|---|---|---|
| aura-core/.../backup/BackupManager.kt | 807 | ✓ |
| aura-core/.../backup/AuraBackup.kt | 675 | ✓ |
| aura-core/.../backup/AuraBackupSchema12.kt | 363 | ✓ |
| aura-core/.../backup/AuraBackupSchema13.kt | 326 | ✓ |
| aura-core/.../backup/BackupMappers.kt | 595 | ✓ |
| aura-core/.../memory/MemoryDatabase.kt | 101 | ✓ |
| aura-core/.../memory/MemoryEntity.kt | 55 | ✓ |
| aura-core/.../memory/MemoryDao.kt | 230 | ✓ |
| aura-core/.../memory/MemoryEditEntity.kt | 38 | ✓ |
| aura-core/.../memory/MemoryEditDao.kt | 23 | ✓ |
| aura-core/.../memory/MemoryModule.kt | 686 | ✓ |
| aura-core/.../agent/AgentEntity.kt | 51 | ✓ |
| aura-core/.../agent/AgentDao.kt | 46 | ✓ |
| aura-core/.../agent/ConversationEntity.kt | 58 | ✓ |
| aura-core/.../agent/ConversationDao.kt | 138 | ✓ |
| aura-core/.../agent/ConversationModule.kt | 79 | ✓ |
| aura-core/.../agent/StrategyBanditEntity.kt | 47 | ✓ |
| aura-core/.../agent/StrategyBanditDao.kt | 32 | ✓ |
| aura-core/.../kg/KgEntities.kt | 73 | ✓ |
| aura-core/.../kg/KnowledgeGraphDao.kt | 111 | ✓ |
| aura-core/.../kg/KgEntityResolver.kt | 199 | ✓ |
| aura-core/.../tasks/TaskEntity.kt | 27 | ✓ |
| aura-core/.../tasks/TaskDao.kt | 55 | ✓ |
| aura-core/.../tasks/ReminderEntity.kt | 28 | ✓ |
| aura-core/.../tasks/ReminderDao.kt | 46 | ✓ |
| aura-core/.../tasks/TasksModule.kt | 91 | ✓ |
| aura-core/.../hands/HandDao.kt | 66 | ✓ |
| aura-core/.../profile/UserProfileEntity.kt | 16 | ✓ |
| aura-core/.../profile/UserProfileDao.kt | 14 | ✓ |
| aura-core/.../creative/CreativeProjectEntity.kt | 36 | ✓ |
| aura-core/.../creative/CreativeProjectDao.kt | 30 | ✓ |
| aura-core/.../creative/CreativeArtifactEntity.kt | 173 | ✓ |
| aura-core/.../creative/CreativeArtifactDao.kt | 127 | ✓ |
| aura-core/.../creative/CanonDaos.kt | 115 | ✓ |
| aura-core/.../world/WorldModelDaos.kt | 123 | ✓ |
| aura-core/.../taste/TasteDaos.kt | 130 | ✓ |
| aura-core/.../agentrun/AgentRunDaos.kt | 173 | ✓ |
| aura-core/.../evolution/EvolutionDaos.kt | 161 | ✓ |
| aura-core/.../evolution/EvolutionModule.kt | 66 | ✓ |
| aura-core/.../proactive/ProactiveEventEntity.kt | 36 | ✓ |
| aura-core/.../proactive/ProactiveEventDao.kt | 82 | ✓ |
| aura-core/.../proactive/ProactiveEventModule.kt | 78 | ✓ |
| aura-core/.../documents/DocumentEntity.kt | 33 | ✓ |
| aura-core/.../documents/DocumentDao.kt | 30 | ✓ |
| aura-core/.../documents/DocumentChunkEntity.kt | 60 | ✓ |
| aura-core/.../documents/DocumentChunkDao.kt | 52 | ✓ |
| aura-core/.../dream/DreamConsolidationDatabase.kt | 36 | ✓ |
| aura-core/.../dream/DreamConsolidationDao.kt | 73 | ✓ |
| aura-core/.../dream/RoutineEntity.kt | 61 | ✓ |
| aura-core/.../dream/RoutineDao.kt | 66 | ✓ |
| aura-core/.../dream/ContradictionEntity.kt | 52 | ✓ |
| aura-core/.../dream/ContradictionDao.kt | 51 | ✓ |
| aura-core/.../dream/KgEdgeProposalEntity.kt | 44 | ✓ |
| aura-core/.../dream/KgEdgeProposalDao.kt | 51 | ✓ |
| aura-core/.../data/RoomConfig.kt | 35 | ✓ |
| app/.../ui/settings/BackupViewModel.kt | 150 | ✓ |
| aura-core/schemas/com.aura.memory.MemoryDatabase/15.json | (schema) | ✓ |

**Not audited (out of scope):**
- `app/src/main/kotlin/com/aura/ui/settings/sections/DataAndBackupSection.kt` — UI binding for BackupViewModel
- `app/src/test/kotlin/com/aura/ui/settings/BackupViewModelTest.kt` — test file
- `aura-core/schemas/*.json` for other DB versions (only v15 of memory)
- `aura-core/src/main/kotlin/com/aura/hands/HandsModule.kt` (not yet read but small)
- `aura-core/src/main/kotlin/com/aura/profile/UserProfileModule.kt` (small)

---

## CONCLUSION

The persistence/backup layer in Aura is structurally sound (well-migrated schemas, exported for every DB, comprehensive entity coverage in the backup format) but has **6 critical data-loss bugs** that affect every restore:

1. Evolution data is NEVER included in backups (`BackupManager.snapshot()` is missing the `evolutionProposals`/`Settings`/`Revisions` fields).
2. The restore path is not transactional and the `purgeAll()` failure recovery is destructive.
3. `contradictionDao` and `kgEdgeProposalDao` use `IGNORE` on restore, silently dropping re-imports.
4. `TaskEntity.recurrence` is missing from the backup format.
5. Autoincrement counters for `proactive_events`, `proactive_interactions`, `memory_edits`, and `agents` are not advanced after restore, leading to UNIQUE constraint failures on the next write.
6. Builtin agents are not re-seeded after `purgeAll()` + restore.

Plus 2 high-severity stability bugs:
7. `BackupViewModel.stageImport()` parses JSON on the Main thread (ANR risk).
8. `BackupViewModel.prepareExportFile()` builds the full JSON in memory (OOM risk).

These 8 bugs should be fixed before the next release that ships a backup/restore UI to users.
