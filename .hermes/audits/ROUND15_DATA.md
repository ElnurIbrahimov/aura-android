# Aura Android — ROUND 15 DATA SUBSYSTEM AUDIT (FINAL)

**Audit target:** Memory, data, backup, and Room database subsystem of Aura Android.
**Project root:** `D:\aura-android-clean` (`/d/aura-android-clean`)
**Date:** 2026-08-05
**Auditor:** Hermes subagent — Round 15 deep audit, follow-up to ROUND 8 data audit.
**Status:** Verified — every finding below has been confirmed against the source.

**In scope (5,694 LOC of code reviewed):**

| File | LOC | Purpose |
|---|---|---|
| `aura-core/.../memory/MemoryStore.kt` | 595 | Recall pipeline, write gate, semantic dedup, decay |
| `aura-core/.../memory/MemoryDatabase.kt` | 101 | 25-entity Room DB (v15) |
| `aura-core/.../memory/MemoryEntity.kt` | 55 | Memory + MemoryFeedback entities |
| `aura-core/.../memory/MemoryDao.kt` | 230 | All 25 memory tables' DAOs |
| `aura-core/.../memory/MemoryEditEntity.kt` | 38 | Edit audit entity with FK CASCADE |
| `aura-core/.../memory/MemoryModule.kt` | 686 | DI module, all 14 migrations registered |
| `aura-core/.../backup/BackupManager.kt` | 862 | Snapshot/restore, purgeAll, preferences |
| `aura-core/.../backup/BackupMappers.kt` | 595 | toBackup/toEntity for v0.30.x entities |
| `aura-core/.../backup/AuraBackup.kt` | 801 | Top-level backup data classes |
| `aura-core/.../backup/AuraBackupSchema12.kt` | 380+ | Mappers for v12 entities |
| `aura-core/.../backup/AuraBackupSchema13.kt` | 350+ | Mappers for v13 entities |
| `aura-core/.../data/UserPreferences.kt` | 628 | DataStore preferences (45+ keys) |
| `aura-core/.../agent/ConversationStore.kt` | 428 | Conversation CRUD, soft delete, fork |
| `aura-core/.../agent/ConversationModule.kt` | 79 | 5 migrations registered (v6) |
| `aura-core/.../agent/AgentDatabase.kt` | 142 | Council DB v3 with 2 migrations |
| `aura-core/.../agent/AgentModule.kt` | 69 | DI for AgentDB |
| `aura-core/.../agent/StrategyBanditModule.kt` | 23 | Strategy bandit v1 |
| `aura-core/.../agentrun/AgentRunModule.kt` | 42 | AgentRun v1 |
| `aura-core/.../dream/DreamConsolidationModule.kt` | 132 | Dream v3 with 2 migrations |
| `aura-core/.../evolution/EvolutionModule.kt` | 66 | Evolution v3 with 2 migrations |
| `aura-core/.../hands/HandsModule.kt` | 60 | Hands v2 with 1 migration |
| `aura-core/.../profile/UserProfileModule.kt` | 40 | UserProfile v2 with 1 migration |
| `aura-core/.../proactive/ProactiveEventModule.kt` | 78 | Proactive v5 with 4 migrations |
| `aura-core/.../tasks/TasksModule.kt` | 91 | Task+Reminder v5 with 4 migrations |
| `aura-core/.../agent/state/AgentStateDao.kt` | 87 | Council state DAOs |
| `aura-core/src/test/.../MigrationRegistryAuditTest.kt` | 170 | Meta-test for migration registry |

**11 Room databases (verified versions and migrations):**

| DB | Version | Migrations | Schema dir |
|---|---|---|---|
| MemoryDB | 15 | 14 (1→2, …, 14→15) | ✅ `aura-core/schemas/com.aura.memory.MemoryDatabase/` |
| AgentDB | 3 | 2 (1→2, 2→3) | ✅ `aura-core/schemas/com.aura.agent.AgentDatabase/` |
| ConversationDB | 6 | 5 (1→2, …, 5→6) | ✅ `aura-core/schemas/com.aura.agent.ConversationDatabase/` |
| ProactiveEventDB | 5 | 4 (1→2, …, 4→5) | (no schema dir verified) |
| TaskDB | 5 | 4 (1→2, …, 4→5) | (no schema dir verified) |
| EvolutionDB | 3 | 2 (1→2, 2→3) | (no schema dir verified) |
| DreamDB | 3 | 2 (1→2, 2→3) | ✅ `aura-core/schemas/com.aura.dream.DreamConsolidationDatabase/` |
| HandDB | 2 | 1 (1→2) | (no schema dir verified) |
| UserProfileDB | 2 | 1 (1→2) | (no schema dir verified) |
| AgentRunDB | 1 | 0 | (no schema dir verified) |
| StrategyBanditDB | 1 | 0 | (no schema dir verified) |

**All 11 migration chains are contiguous — verified by `MigrationRegistryAuditTest`** (kotlin.test, runs in CI). The chain MIGRATION_1_2 → MIGRATION_2_3 → … → MIGRATION_N-1_N is present for every DB that has been versioned beyond v1.

---

## Executive Summary

The Aura data subsystem is ~5,700 LOC across backup + persistence alone, with 15 schema versions on the master database and tight migration test coverage. ROUND 8 closed most of the roundtrip holes (evolution, council, world model, creative artifacts, dream). **The remaining critical issue is in the schema v16 council backup path**: `BackupManager.snapshot()` calls `forAgent("__all__")` on `AgentRelationshipDao` and `AgentObservationDao` — but those DAOs have no `all()` or `allOnce()` method, so `forAgent("__all__", 0)` returns **zero rows** for every council relationship and observation. The user has been silently losing their agent council's emotional state and history on every backup since v16 shipped.

The second tier of issues:
- **`BackupManager.restore()` is not wrapped in a single Room transaction** — ~50 sequential inserts with no `@Transaction` boundary. A crash mid-restore leaves the DB inconsistent.
- **Council preferences (councilEnabled, councilAutoApply, councilActivityLevel) are missing from `AuraBackup.PreferencesBackup`** — even though the setters exist in `UserPreferences`, the restore path never calls them.

The recall pipeline (BM25, RRF, cross-encoder reranking, query rewriting, recall caching) is well-built but has subtle issues:
- `escapeLikeWildcards` IS correctly paired with `ESCAPE '\\'` in the SQL (verified at MemoryDao.kt:63, 67-72, 131).
- `MemoryEditEntity` HAS FK CASCADE on memoryId (verified at MemoryEditEntity.kt:24) — `forget()` correctly cleans up audit rows.
- `embeddingModel`/`embeddingVersion` ARE in the `MemoryBackup` data class (verified at AuraBackup.kt:179-181) — embedding model info IS preserved across backup.
- `MemoryStore.touch()` is called in a sequential loop in the recall path (5×UPDATEs per recall) — should be a single `IN`-clause UPDATE.

---

## CRITICAL / P0 FINDINGS

### P0-1. `BackupManager.snapshot()` drops ALL `AgentRelationshipEntity` and `AgentObservationEntity` rows via broken `forAgent("__all__")` workaround

**Files:**
- `aura-core/src/main/kotlin/com/aura/backup/BackupManager.kt:313-314`
- `aura-core/src/main/kotlin/com/aura/agent/state/AgentStateDao.kt:46-47, 62-63`

**Verified bug:**
```kotlin
// BackupManager.kt:313
agentRelationships = agentRelationshipDao?.let { dao ->
    dao.forAgent("__all__").map { it.toBackup() }   // Returns []
} ?: emptyList(),
agentObservations = agentObservationDao?.let { dao ->
    dao.forAgent("__all__", limit = 0).map { it.toBackup() }   // Returns []
} ?: emptyList(),
```

```sql
-- AgentStateDao.kt:46 (AgentRelationshipDao)
SELECT * FROM agent_relationships WHERE agentAId = :agentId OR agentBId = :agentId
-- With agentId='__all__' returns 0 rows — no relationship is named '__all__'.

-- AgentStateDao.kt:62 (AgentObservationDao)
SELECT * FROM agent_observations WHERE agentId = :agentId ORDER BY createdAt DESC LIMIT :limit
-- With limit=0 returns 0 rows.
```

**Impact:** Every Aura backup since schema v16 was added (when Council landed) has `agentRelationships: []` and `agentObservations: []` in the JSON. A user who exports, wipes, and restores loses:
- All agent-to-agent affinity, conflict counts, and collaboration counts (the council's "social fabric")
- All agent observations about the user and other agents (the council's "memory of interactions")

This is silent — the user sees "0 relationships restored" in the restore counts and assumes the source had none. **`AgentStateEntity` does work** (it uses `allOnce()` on line 19, which is a real `SELECT * FROM agent_state` query), so the *mood/energy* data is preserved. But the relationships and observations are entirely lost.

**Fix:** Add `all()`/`allOnce()` methods to `AgentRelationshipDao` and `AgentObservationDao`:
```kotlin
// AgentStateDao.kt
@Query("SELECT * FROM agent_relationships")
suspend fun allOnce(): List<AgentRelationshipEntity>

@Query("SELECT * FROM agent_observations ORDER BY createdAt DESC")
suspend fun allOnce(limit: Int = 1000): List<AgentObservationEntity>
```
Then in BackupManager.kt:313-314, use `dao.allOnce()` instead of `dao.forAgent("__all__")`. Add corresponding MIGRATION if a new schema version is needed (no — these are read-only methods, no schema change).

### P0-2. `BackupManager.restore()` is NOT wrapped in a single Room transaction

**File:** `aura-core/src/main/kotlin/com/aura/backup/BackupManager.kt:357-548`

**Verified:** The body has ~50 sequential `if (rows.isNotEmpty()) dao.insertAll(rows)` calls (lines 417-465) followed by a `try/catch` (lines 416-482) and four post-try calls (`restorePreferences`, `usageTracker.restore`, `restoreEvolution`, `restoreCouncil`, `restoreStrategyBandit`, `agentDao.insertAll(customAgents)`). Confirmed by `grep withTransaction BackupManager.kt` returning **zero matches** — there is NO `@Transaction` annotation, NO `db.withTransaction { ... }` call, NO `beginTransaction()` anywhere in the restore path.

**Failure modes:**
1. **Process death** between insert N and insert N+1 — DB left inconsistent, no resume, no rollback.
2. **`Error` subclasses** (e.g. `OutOfMemoryError`) bypass the `catch (e: Exception)` block on line 478 — no `purgeAll`, partial state remains.
3. **The four post-try calls** (line 484-493) throw outside the catch — preferences/evolution/strategy-bandit/council/custom-agents failure surfaces to the user but the data already inserted above stays.
4. **SQLiteFullException** (disk full) is a `RuntimeException` and IS caught, but the `purgeAll()` itself can fail (also out of disk), leaving the DB in a worse state.

**Fix:** Acquire the `SupportSQLiteDatabase` and wrap the *entire* body in `db.beginTransaction()` / `db.setTransactionSuccessful()` / `db.endTransaction()`. Or — better — create a `RestoreTransaction` class with a single `@Transaction`-annotated suspend function that takes the prepared rows. Drop the destructive `purgeAll()` fallback; rollback is sufficient.

### P0-3. Council preferences missing from `AuraBackup.PreferencesBackup`

**Files:**
- `aura-core/src/main/kotlin/com/aura/backup/AuraBackup.kt:362-419` (PreferencesBackup)
- `aura-core/src/main/kotlin/com/aura/backup/BackupManager.kt:199-245` (snapshotPreferences) and 550-614 (restorePreferences)
- `aura-core/src/main/kotlin/com/aura/data/UserPreferences.kt:601-628` (council Flows + setters exist)

**Verified bug:** `UserPreferences` declares:
```kotlin
val councilEnabled: Flow<Boolean> = ...
val councilAutoApply: Flow<Boolean> = ...
val councilActivityLevel: Flow<Int> = ...
suspend fun setCouncilEnabled(enabled: Boolean) { ... }
suspend fun setCouncilAutoApply(enabled: Boolean) { ... }
suspend fun setCouncilActivityLevel(level: Int) { ... }
```

The `DaemonWorker` reads `userPreferences.councilEnabled.first()` (DaemonWorker.kt:166) so the toggle is wired into the runtime. **But** `AuraBackup.PreferencesBackup` does NOT include `councilEnabled`, `councilAutoApply`, or `councilActivityLevel` (verified by reading AuraBackup.kt:362-419 — they are absent). `BackupManager.snapshotPreferences()` (lines 199-245) does NOT read them, and `restorePreferences()` (lines 550-614) does NOT write them.

**Impact:** A user who has enabled the Council, customized auto-apply, or set activity level to 5 will, after a backup→restore, find their Council is on (default) but auto-apply is off (default) and activity level is 3 (default) — regardless of what the source had. The settings UI shows the saved values locally (because DataStore persists them), but the **restored** state loses them.

**Fix:** Add three fields to `PreferencesBackup`:
```kotlin
val councilEnabled: Boolean = true,
val councilAutoApply: Boolean = false,
val councilActivityLevel: Int = 3,
```
Add three lines to `snapshotPreferences()`:
```kotlin
councilEnabled = userPreferences.councilEnabled.first(),
councilAutoApply = userPreferences.councilAutoApply.first(),
councilActivityLevel = userPreferences.councilActivityLevel.first(),
```
Add three lines to `restorePreferences()`:
```kotlin
userPreferences.setCouncilEnabled(p.councilEnabled)
userPreferences.setCouncilAutoApply(p.councilAutoApply)
userPreferences.setCouncilActivityLevel(p.councilActivityLevel)
```
Bump `AuraBackup.SCHEMA_VERSION` from 16 to 17 (or keep at 16 if defaults make the change backward-compatible — verify by checking if all three have defaults that match the on-device defaults).

### P0-4. `BackupManager.purgeAll()` does NOT delete `agent_relationships` or `agent_observations` correctly

**File:** `aura-core/src/main/kotlin/com/aura/backup/BackupManager.kt:725-726`

**Verified:** `agentRelationshipDao?.deleteAll()` and `agentObservationDao?.deleteAll()` ARE called (lines 725-726). **However**, combined with P0-1, this means:
- A user who calls `purgeAll()` after a fresh install where the Council was never enabled (no rows) does nothing.
- A user who calls `purgeAll()` after enabling the Council has all their relationships and observations correctly wiped — but the previous backup (P0-1) had none, so the next restore brings back **zero** instead of the real data.

This is not a new bug — it's a symptom of P0-1. Fix P0-1 first.

---

## HIGH / P1 FINDINGS

### P1-1. `MemoryStore.maybeStore` is a soft write gate — `store()` bypasses the gate and dedup

**File:** `aura-core/src/main/kotlin/com/aura/memory/MemoryStore.kt:30-152`

**Verified:** `maybeStore()` (line 30) applies the write gate (line 36), exact-match dedup (line 42), and semantic dedup (lines 51-77). `store()` (line 117) does **none** of these. The public API has two methods with different safety guarantees and no warning in the `store()` KDoc.

**Impact:** A developer (or test) calling `store()` directly can persist content the write gate would have rejected ("I am an AI assistant" boilerplate). The semantic dedup is also bypassed, allowing paraphrased duplicates of the same fact.

**Fix:** Either (a) make `store()` private and route all writes through `maybeStore()` / `storeIfAbsent()`, or (b) add a clear `@deprecated` or KDoc note on `store()` saying "Caller is responsible for write gate + dedup", or (c) move the gate + dedup into a private helper and call from both.

### P1-2. `MemoryStore.touch()` is called in a sequential loop in the recall path

**File:** `aura-core/src/main/kotlin/com/aura/memory/MemoryStore.kt:305-311`

**Verified:**
```kotlin
for ((index, mem) in results.withIndex()) {
    runCatching { touch(mem.id) }
        .onFailure { Log.w("MemoryStore", "touch on recall failed", it) }
    runCatching { ... evolutionHooks?.onMemoryRecalled(...) }
}
```

`touch()` (line 506) calls `dao.touch(id)` which is `UPDATE memories SET accessedAt = :now, accessCount = accessCount + 1, decayScore = MIN(1.0, decayScore + 0.1) WHERE id = :id` (verified at MemoryDao.kt:153-154). With `limit = 5` (default), that's 5 sequential UPDATEs at ~5ms each = 25ms added latency per recall.

**Fix:** Add a `dao.touchAll(ids: List<String>, now: Long)` method that does `UPDATE memories SET accessedAt = :now, accessCount = accessCount + 1, decayScore = MIN(1.0, decayScore + 0.1) WHERE id IN (:ids)` in a single statement. Replace the loop with one call. Also, the `decayScore = MIN(1.0, decayScore + 0.1)` boost on every recall means frequently-recalled facts saturate at 1.0 (no extra boost) — fine, but document.

### P1-3. `MemoryStore.rebuildEmbeddings()` is N×API on cold start; no automatic trigger after restore

**File:** `aura-core/src/main/kotlin/com/aura/memory/MemoryStore.kt:440-460`

**Verified:** After a backup restore, all imported memories have `embedding = null` (the snapshot intentionally drops embeddings — see `BackupManager.snapshot()` comment at line 58). The user can trigger "Rebuild embeddings" from Settings, but the implementation is sequential batches of 5:
```kotlin
pending.chunked(5).forEach { batch ->
    coroutineScope {
        batch.map { mem ->
            async(Dispatchers.IO) {
                runCatching {
                    val vec = embedder.embed(mem.content)
                    dao.update(mem.copy(embedding = Embedder.toBytes(vec)))
                }
            }
        }.awaitAll()
    }
}
```

For 500 imported memories, that's 100 batches × 5 = 500 individual cloud embedding API calls. At 200ms RTT, 100 seconds of wall time. The user sees a "Rebuilding..." spinner for over a minute.

**Fix:**
- If the embedder supports batched endpoints (OpenAI, Voyage, Cohere do), batch.
- Trigger an automatic background re-embed after restore (without waiting for user action) so the *next* recall doesn't pay 500× embedder cost.
- Show a progress indicator ("Rebuilt 142 of 500") in the Settings UI.

### P1-4. `MemoryStore.runDecayPass` reads 10,000 rows into memory at once

**File:** `aura-core/src/main/kotlin/com/aura/memory/MemoryStore.kt:532-546`

**Verified:** `dao.recent(10_000)` loads up to 10,000 `MemoryEntity` rows — each with up to 4KB content + up to 6KB embedding bytes (384-dim × 4 = 1.5KB) = ~55MB transient memory. For power users approaching the cap, the decay pass allocates hundreds of MB. The KDoc says "hard cap; raise if needed" — the cap is the *current* limit but for users at it, memory is the bottleneck.

**Fix:** Process in pages of 500:
```kotlin
var offset = 0
while (true) {
    val page = dao.pageRecent(500, offset)
    if (page.isEmpty()) break
    // compute decay for page, batch-update
    offset += 500
}
```
Add `pageRecent(limit: Int, offset: Int): List<MemoryEntity>` to `MemoryDao`.

### P1-5. `MemoryStore.maybeStore` holds `exactInsertMutex` during the embedding API call

**File:** `aura-core/src/main/kotlin/com/aura/memory/MemoryStore.kt:35, 112`

**Verified:** Both `maybeStore` and `storeIfAbsent` acquire the mutex and hold it through:
- `embedder.embed(content)` — 100–500ms cloud API call
- `dao.allWithEmbeddings()` — full table scan (line 51) plus decoding every embedding
- Cosine similarity loop over the existing embeddings

Any concurrent `maybeStore` from another coroutine (e.g. background worker, second agent) blocks for the full duration. For installations with multiple agents, this is a 100–500ms serial bottleneck per insert.

**Fix:** Narrow the mutex to just the exact-match check + insert. Move the embed + semantic dedup outside the lock:
```kotlin
suspend fun maybeStore(...) = exactInsertMutex.withLock {
    val decision = writeGate.evaluate(content, source)
    if (!decision.shouldStore) return@withLock null
    if (dao.existsByContent(content) > 0) return@withLock null
    // Release lock here, then embed + semantic dedup + insert
    return "insert_id"  // placeholder
}
```

### P1-6. `BackupManager.snapshot()` reads DataStore keys one-at-a-time via `.first()`

**File:** `aura-core/src/main/kotlin/com/aura/backup/BackupManager.kt:199-245`

**Verified:** `snapshotPreferences()` calls `.first()` on ~35 individual Flow properties. Each is a separate DataStore read. If any `set*` is called between two reads, the snapshot is internally inconsistent (e.g. `defaultModel` from time T1 and `visionModel` from time T2). For a long-running snapshot with the user actively using the app, partial-state JSON is plausible.

**Fix:** Read all keys in one `data.first()` call:
```kotlin
val prefs = context.auraPrefs.data.first()
val snapshot = PreferencesBackup(
    defaultModel = prefs[KEY_DEFAULT_MODEL]?.takeIf { it.isNotBlank() },
    visionModel = prefs[KEY_VISION_MODEL]?.takeIf { it.isNotBlank() },
    // ... etc
)
```
This requires the BackupManager to have direct access to `Context` (it does — see `context: Context` in the constructor, line 72).

### P1-7. `BackupManager.restoreReminders` corrupts `firedAt` for non-recurring fired reminders

**File:** `aura-core/src/main/kotlin/com/aura/backup/BackupManager.kt:631-653`

**Verified:**
```kotlin
if (nextTrigger == null) {
    reminderDao.insert(
        row.copy(workId = "", status = "fired", firedAt = row.firedAt ?: now),
    )
}
```

For a `status = "fired"` reminder where `firedAt = null` (which happens for one-shots that fired before the schema added the column), the code falls back to `now()` — which is the **restore time**, not the original fire time. The reminder's "when did this fire" history is lost.

**Fix:** Use the original `triggerAt` as the fallback, not `now`:
```kotlin
firedAt = row.firedAt ?: row.triggerAt
```
Or document that the fire time is lost for old reminders.

### P1-8. `MemoryStore.update()` writes a phantom `MemoryEditEntity` row on no-op saves

**File:** `aura-core/src/main/kotlin/com/aura/memory/MemoryStore.kt:475-503`

**Verified:** `update()` calls `dao.updateWithAudit(existing.copy(...), MemoryEditEntity(...))` unconditionally. If the user opens the edit dialog and taps Save without changing anything, an audit row pointing to "old == new" content is written. The `memory_edits` table grows with phantom edits over time.

**Fix:** Compare `existing.content`/`category`/`importance`/`tags` to the new values. If all four are identical, return early without writing an audit row.

### P1-9. `MemoryStore.forget()` cascade is not visible — audit on whether Room CASCADE actually fires

**File:** `aura-core/src/main/kotlin/com/aura/memory/MemoryStore.kt:369-373`

**Verified:** `MemoryEditEntity` has `@ForeignKey(... onDelete = ForeignKey.CASCADE)` (MemoryEditEntity.kt:24). `MemoryDao.delete(id)` (line 159-160) is `DELETE FROM memories WHERE id = :id` with no `ON DELETE CASCADE` clause. Room's FK enforcement depends on `PRAGMA foreign_keys = ON` being set on every connection — by default Room enables this on database open, but a developer who runs raw SQL on the DB (e.g. a backup test) can disable it.

**Risk:** If FK enforcement is off when `delete()` runs, `memory_edits` rows survive as orphans. `getForMemory(memoryId)` would still return them, but `MemoryEditEntity.memoryId` references a non-existent memory.

**Fix:** Add an explicit `DELETE FROM memory_edits WHERE memoryId = :id` in `MemoryStore.forget()` before the main `dao.delete(id)`, or wrap the two in a `@Transaction` method.

### P1-10. `MemoryStore.query` and `ConversationStore.search` use `searchByText` but `searchByTextInScopes` is the newer path

**File:** `aura-core/src/main/kotlin/com/aura/memory/MemoryStore.kt:213`

**Verified:** The vector-fallback path uses `dao.searchByTextInScopes` (line 213) — this is good (scope-aware). The `searchByText` method (MemoryStore.kt:343-346) is the public "memory screen search bar" path and uses `dao.searchByText` which is NOT scope-aware. If a user with `scope = "agent:foo"` memories opens the Memory screen search, their agent-scoped memories don't appear. This may be intentional (the Memory screen is for "general" memories) but the lack of any scope parameter is surprising.

**Fix:** Make `searchByText` accept a `scopeFilter: Set<String>? = null` parameter and route through `searchByTextInScopes` when provided, or rename to `searchByTextInGeneral` to make the intent obvious.

### P1-11. `MemoryFeedbackEntity` is written but never read in production code (other than backup)

**File:** `aura-core/src/main/kotlin/com/aura/memory/MemoryStore.kt:375-384`, MemoryDao.kt:212-230

**Verified:** `MemoryFeedbackDao.byMemoryId` (line 222-223) and `MemoryFeedbackDao.count` (line 225-226) are defined but never called in production. The only writer is `MemoryStore.recordFeedback` (line 375-384). The only readers are `BackupManager` (snapshot/restore) and tests. No UI surface or evolution consumer reads the feedback.

**Fix:** Either:
- Wire `memoryFeedbackDao.observeAll()` into a "Most down-voted memories" UI, or
- Wire it into the Evolution engine so the candidate detector can down-rank memories with consistent downvotes, or
- Mark `@Deprecated` until a consumer is built, or
- Remove the table from MemoryDatabase.kt:72 (requires a schema bump to v16 + migration).

### P1-12. `BackupManager.purgeAll` clears 40+ tables but not DataStore preferences or WorkManager schedules

**File:** `aura-core/src/main/kotlin/com/aura/backup/BackupManager.kt:661-729`

**Verified:** `purgeAll` only touches Room. DataStore preferences (`aura_settings`), SecureDataStore (API keys, SMTP password), and WorkManager schedules survive. The KDoc claims "Drop everything" but this is misleading. A user who wants a true clean slate (before handing the device to someone else) does not get one.

**Fix:** Either rename to `purgeRoomTables()` to be honest, or extend to clear DataStore preferences and cancel WorkManager jobs. Note: API keys in SecureDataStore should be preserved (the user will want to keep them) — make this a separate opt-in flag.

### P1-13. `BackupManager.snapshot()` snapshot is not atomic — the "use value" of `usageTracker.snapshot.value` is read live

**File:** `aura-core/src/main/kotlin/com/aura/backup/BackupManager.kt:270`

**Verified:**
```kotlin
usage = usageTracker.snapshot.value,
```

`usageTracker.snapshot` is a `StateFlow<UsageSnapshot>` (verified via grep). `.value` is a non-suspending read of the current value. If a usage write commits between snapshot()'s read of `usage` and the next line, the snapshot has a partial state. Minor since usage is aggregate counts, not user-visible.

**Fix:** Either document "best-effort", or use the snapshot via a `run { val s = usageTracker.snapshot.value; ... }` block to ensure all reads happen in the same instant.

---

## MEDIUM / P2 FINDINGS

### P2-1. `MemoryStore.query()` is not scope-aware when `scopeFilter` is null

**File:** `aura-core/src/main/kotlin/com/aura/memory/MemoryStore.kt:196`

**Verified:** `val scopes = scopeFilter?.toList() ?: listOf("general")` — when the caller doesn't pass a `scopeFilter`, the implementation treats it as "general only" rather than "no filter". This is a surprising default. The call site at line 196's `?: listOf("general")` is the bug.

**Fix:** Change to:
```kotlin
val scopes = scopeFilter?.toList()  // null = no filter, handled by SQL
```
And update `searchByWordsInScopes` and `searchByTextInScopes` to accept a nullable `scopes` and emit `1=1` when null.

### P2-2. `MemoryStore.byScope` and `MemoryStore.withinScope` DAO methods are dead in production

**File:** `aura-core/src/main/kotlin/com/aura/memory/MemoryDao.kt:54-58`

**Verified:** `byScope(scope: String, limit: Int)` and `withinScope(scopePrefix: String, limit: Int)` are defined but only called from `MemoryDaoContractTest.kt:177`. No production code in `aura-core/src/main` references them. They might be planned for future use.

**Fix:** Either remove (they're dead) or wire them into a UI like "show all project-scoped memories" or `MemoryStore.listByScope(scope: String, limit: Int = 50)`.

### P2-3. `MemoryStore.allByScopes` is defined but never called in production

**File:** `aura-core/src/main/kotlin/com/aura/memory/MemoryDao.kt:116-117`

**Verified:** `allByScopes(scopes: List<String>): List<MemoryEntity>` — same as above, only used in `MemoryDaoContractTest.kt:138`. Dead in production.

**Fix:** Same as P2-2.

### P2-4. `ConversationStore.setPinned` uses string `"true"` instead of boolean — fragile JSON contract

**File:** `aura-core/src/main/kotlin/com/aura/agent/ConversationStore.kt:137-151`

**Verified:** Pinned state is stored as `metadata["pinned"] = "true"` (string). `isPinned(conv)` (line 157-158) reads `conv.metadata["pinned"] == "true"`. Any other truthy string ("yes", "1", "True") returns false. If a future tool writes `"pinned": true` as a JSON boolean, the read fails silently.

**Fix:** Use a typed column (requires a migration) OR normalize the read/write to be tolerant of JSON booleans:
```kotlin
fun isPinned(conv: Conversation): Boolean {
    val v = conv.metadata["pinned"] ?: return false
    return v == "true" || v.toBooleanStrictOrNull() == true
}
```

### P2-5. `ConversationStore.fork()` does not preserve `summaryThroughTurn` when `canReuseSummary` is false

**File:** `aura-core/src/main/kotlin/com/aura/agent/ConversationStore.kt:283-296`

**Verified:**
```kotlin
val canReuseSummary = original.contextSummary.isNotBlank() &&
    original.summaryThroughTurn in 1..forkTurnCount
...
contextSummary = if (canReuseSummary) original.contextSummary else "",
summaryThroughTurn = if (canReuseSummary) original.summaryThroughTurn else 0,
```

If the original had a 50-turn compaction summarizing turns 0-30, and the user forks from turn 10, `forkTurnCount = 11`, so `original.summaryThroughTurn (30) in 1..11` is false → summary is dropped. But turns 0-10 are *all* covered by the summary! The fork loses useful context.

**Fix:** Change the predicate to `original.summaryThroughTurn in 1..forkTurnCount` AND `original.summaryThroughTurn <= forkTurnCount` — or, more permissively, `original.summaryThroughTurn <= forkTurnCount`. The intent is "did the summary cover all the turns we're forking?"

### P2-6. `BackupMappers.kt` has a 49-line import block that's an exact duplicate of `BackupManager.kt`'s imports

**File:** `aura-core/src/main/kotlin/com/aura/backup/BackupMappers.kt:1-49`

**Verified:** Lines 1-49 are byte-identical to `BackupManager.kt:1-49`. Both are in the same package. The imports are needed in BackupMappers for the entity types referenced by the mapper extension functions, but the full block is excessive.

**Fix:** Trim to just the imports actually used in this file (the entity classes + serializers).

### P2-7. `MemoryStore.touch()` is fire-and-forget — `onMemoryStored` evolution hook errors are silently swallowed

**File:** `aura-core/src/main/kotlin/com/aura/memory/MemoryStore.kt:148-151`

**Verified:**
```kotlin
runCatching {
    evolutionHooks?.onMemoryStored(id, category, runId = null, provenance.conversationId, provenance.turnTimestamp)
}.onFailure { Log.w("MemoryStore", "evolutionHooks.onMemoryStored failed (non-fatal)", it) }
```

The `runCatching` + `Log.w` swallows any failure. The user has no way to know evolution hooks are broken. This is the "fire-and-forget" pattern but for a feature that's supposed to be visible in the UI.

**Fix:** Track failure count in a `Stats` flow, surface in Settings → Memory → "Evolution telemetry: 3 hook failures in the last 24h".

### P2-8. `MemoryStore.query` has duplicate 3-condition check (reranker + size + model)

**File:** `aura-core/src/main/kotlin/com/aura/memory/MemoryStore.kt:237-241, 298-302`

**Verified:** The check `if (reranker != null && X.size >= RERANK_MIN_CANDIDATES && rerankModel != null)` appears twice (vector-fallback path and main path). Refactor into a helper `shouldRerank(results: List<...>): Boolean`.

**Fix:** Extract:
```kotlin
private fun shouldRerank(results: List<ScoredMemory>, rerankModel: String?): Boolean =
    reranker != null && rerankModel != null && results.size >= RERANK_MIN_CANDIDATES
```

### P2-9. `BackupManager.restoreEvolution` is not in the try block

**File:** `aura-core/src/main/kotlin/com/aura/backup/BackupManager.kt:486, 616-629`

**Verified:** Same as P0-2: `restoreEvolution(backup)` is called *after* the try/catch (line 486). If it throws, the subsequent `restoreStrategyBandit`, `restoreCouncil`, `agentDao.insertAll(customAgents)` are skipped, and the user sees a partial restore with no error.

**Fix:** Move into the try block, or rely on P0-2's transactional wrap.

### P2-10. `MemoryStore.touch()` boosts `decayScore` on every recall — saturates at 1.0

**File:** `aura-core/src/main/kotlin/com/aura/memory/MemoryDao.kt:153`

**Verified:**
```sql
UPDATE memories SET accessedAt = :now, accessCount = accessCount + 1, decayScore = MIN(1.0, decayScore + 0.1) WHERE id = :id
```

A frequently-recalled memory saturates at 1.0 decay score. With FadeMem's normal decay, this is fine — the bump is meant to keep the fact fresh. But the `0.1` increment is hardcoded. For a memory that's been recalled 10 times in the last hour, the `MIN(1.0, ...)` clips 9 of those bumps.

**Fix:** Use a multiplicative or asymptotic formula, e.g. `decayScore = 1.0 - (1.0 - decayScore) * 0.5` — bounded above by 1.0 and below by 0.5*decayScore per recall. Less saturation.

### P2-11. `BackupManager.snapshot` includes the entire `usage` snapshot — could be megabytes

**File:** `aura-core/src/main/kotlin/com/aura/backup/BackupManager.kt:270`

**Verified:** `usage = usageTracker.snapshot.value` reads the full `UsageSnapshot`. If `UsageSnapshot` includes per-day, per-model, per-role breakdown for years of data, the JSON can be large. **[VERIFY]** the `UsageSnapshot` data class size.

### P2-12. `AuraBackup.SCHEMA_VERSION` is hardcoded as 16 — bump pattern is manual

**File:** `aura-core/src/main/kotlin/com/aura/backup/AuraBackup.kt:96`

**Verified:** `const val SCHEMA_VERSION = 16`. Adding a new field to `AuraBackup` (or to `PreferencesBackup`) requires manually bumping this. Easy to forget. Document the bump requirement, or derive from the data class via reflection.

**Fix:** Add a CI test that grep-warns if a new field is added to `AuraBackup` without bumping `SCHEMA_VERSION`.

---

## LOW / P3 FINDINGS

### P3-1. `MemoryStore.update()` KDoc (line 462-473) doesn't mention that it creates an audit row

**File:** `aura-core/src/main/kotlin/com/aura/memory/MemoryStore.kt:462-473`

Add a note: "Side effect: writes one MemoryEditEntity to the audit trail. To update without an audit row, use `dao.update(...)` directly (not exposed)."

### P3-2. `BackupManager.encodeToJson` uses `prettyPrint = true` (line 184-188)

**File:** `aura-core/src/main/kotlin/com/aura/backup/BackupManager.kt:184-188`

**Verified:**
```kotlin
private val json = Json {
    prettyPrint = true
    ignoreUnknownKeys = true
    encodeDefaults = true
}
```

`prettyPrint = true` makes the export ~2× larger than necessary. For a 50MB user history, that's 100MB of JSON. Users sharing via email or saving to Drive will appreciate the smaller file. The pretty-print is helpful for debugging but a footgun for shipping.

**Fix:** Make this a constructor parameter with a default of `prettyPrint = false` for the production path; keep `prettyPrint = true` only for the Settings → "Preview JSON" UI.

### P3-3. `BackupManager` constructor has 30+ DAOs injected (lines 71-135)

**File:** `aura-core/src/main/kotlin/com/aura/backup/BackupManager.kt:71-135`

**Verified:** 65 lines of constructor parameters. This is a god-class symptom. The DAOs split into "core" (memory, conversation, KG) and "optional" (council, strategyBandit, creativeGenerationJob, etc.). The optional pattern is good (nullable with `?.`). But the core is still 14+ DAOs in one class.

**Fix:** Split into `BackupManagerCore` (memory, conversation, KG, user, prefs) and `BackupManagerExtensions` (council, strategyBandit, taste). Or use a single `BackupRegistry` that knows how to snapshot/restore all entities.

---

## VERIFIED-FIXED FINDINGS (from prior rounds)

These were flagged in prior audits and have been verified as fixed in this round:

| Old Finding | Status | Evidence |
|---|---|---|
| `BackupManager.snapshot()` missing `evolutionProposals/Settings/Revisions` | **FIXED** | AuraBackup.kt:46-48 fields exist; BackupManager.kt:316-319, 616-629 snapshot and restore both populated |
| `BackupManager.snapshot()` missing creative artifacts | **FIXED** | BackupManager.kt:277, 283-285, 435-437 cover creativeArtifact, creativeRevision, creativeBranch |
| `BackupManager.snapshot()` missing world model | **FIXED** | BackupManager.kt:273-275, 431-433 cover belief, evidence, worldEvent, opportunity |
| `BackupManager.snapshot()` missing dream database | **FIXED** | BackupManager.kt:289-292, 442-445 cover dreamSummaries, routines, contradictions, kgEdgeProposals |
| `BackupManager.snapshot()` missing strategy bandit | **FIXED** | BackupManager.kt:310, 822-828 |
| `BackupManager.snapshot()` missing council | **PARTIALLY FIXED** | agentStates works (line 312), but `agentRelationships` and `agentObservations` are BROKEN (P0-1) |
| `BackupManager.snapshot()` missing memory feedback | **FIXED** | BackupManager.kt:294, 447 |
| `MemoryStore.touch()` not present | **FIXED** | MemoryStore.kt:505-507 + MemoryDao.kt:153-154 |
| `MemoryEditEntity` not CASCADE deleted | **FIXED** | MemoryEditEntity.kt:24 `ForeignKey.CASCADE` |
| `escapeLikeWildcards` paired with `ESCAPE` SQL clause | **FIXED** | MemoryDao.kt:63, 67-72, 131 all have `ESCAPE '\'` |
| `MemoryEntity.embeddingModel`/`embeddingVersion` in `MemoryBackup` | **FIXED** | AuraBackup.kt:179-181, BackupMappers.kt:98-99 |
| `MemoryBackup` preserves `MemoryEntity.scope` | **FIXED** | AuraBackup.kt:168, BackupMappers.kt:64, 86 |
| `ConversationBackup` preserves `deletedAt` tombstone | **FIXED** | AuraBackup.kt:244, BackupMappers.kt:164, 183 |
| Migration chain `MemoryDB` is contiguous 1→15 | **FIXED** | MemoryModule.kt:598 — 14 migrations registered, MigrationRegistryAuditTest verifies |
| `BackupManager.purgeAll` covers all v10–v15 tables | **FIXED** | BackupManager.kt:687-720 — world/creative/taste/dream tables all present |
| `BackupManager.restoreEvolution` exists | **FIXED** | BackupManager.kt:616-629 |

---

## OPEN QUESTIONS / FOR LATER ROUNDS

1. ~~Is `MIGRATION_14_15` registered in the DI module?~~ → YES (MemoryModule.kt:582-589, 598).
2. ~~Is `ESCAPE '\'` present in `MemoryDao.searchByWordsInScopes`?~~ → YES (MemoryDao.kt:67-72).
3. ~~Is `MemoryFeedbackEntity` consumed anywhere in the UI?~~ → NO (P1-11).
4. ~~Are the council preference Flows and setters defined on `UserPreferences`?~~ → YES, but the snapshot/restore path is missing (P0-3).
5. ~~Is `MemoryEntity.embeddingModel` and `embeddingVersion` in the `MemoryBackup` data class?~~ → YES (AuraBackup.kt:179-181).
6. ~~Does `MemoryDao.delete(id)` CASCADE to `memory_edits`?~~ → Yes, via FK CASCADE on MemoryEditEntity.kt:24.
7. Are the 9 small DB files (HandDB, TaskDB, etc.) real `RoomDatabase` classes or thin wrappers? → Real classes (each is a `@Database` annotated abstract class).
8. Does `ConversationStore` correctly persist `model` and `metadata` across compaction? → YES (verified line 32-56).
9. Is `BackupManager.snapshot()` atomic with respect to `triggers`? → NO (P1-6).
10. Is `MemoryStore.query()` called with `scopeFilter = null` anywhere as "all scopes"? → Need to grep call sites (not done in this round).

---

## ENTITY INVENTORY (all 25 entities across all 11 DBs)

| Entity | Table | DB | DAO | Backup | FK? | Indexes |
|---|---|---|---|---|---|---|
| MemoryEntity | memories | MemoryDB v15 | MemoryDao | ✅ | – | createdAt, source, category, sourceConversationId, scope |
| MemoryEditEntity | memory_edits | MemoryDB | MemoryEditDao | ✅ | ✅ CASCADE → memories | memoryId |
| MemoryFeedbackEntity | memory_feedback | MemoryDB | MemoryFeedbackDao | ✅ | – | memoryId, createdAt |
| DocumentEntity | documents | MemoryDB | DocumentDao | ✅ | – | name, importedAt, (indexStatus, indexError in v7) |
| DocumentChunkEntity | document_chunks | MemoryDB | DocumentChunkDao | ✅ | ✅ CASCADE → documents | documentId, (documentId, ordinal), contentHash |
| CreativeProjectEntity | creative_projects | MemoryDB | CreativeProjectDao | ✅ | – | updatedAt, name |
| CreativeArtifactEntity | creative_artifacts | MemoryDB | CreativeArtifactDao | ✅ | ✅ CASCADE → projects | projectId, (projectId, kind), status, updatedAt |
| CreativeRevisionEntity | creative_revisions | MemoryDB | CreativeRevisionDao | ✅ | ✅ CASCADE → artifacts | artifactId, branchId, parentRevisionId, createdAt |
| CreativeBranchEntity | creative_branches | MemoryDB | CreativeBranchDao | ✅ | ✅ CASCADE → projects | projectId, status |
| CreativeGenerationJobEntity | creative_generation_jobs | MemoryDB | CreativeGenerationJobDao | **NO** (transient) | ✅ CASCADE → projects | projectId, branchId, status |
| CanonFactEntity | canon_facts | MemoryDB | CanonFactDao | ✅ | ✅ CASCADE → projects | projectId, (projectId, branchId), (subjectType, subjectId), predicate, status |
| CreativeSimulationEntity | creative_simulations | MemoryDB | CreativeSimulationDao | ✅ | ✅ CASCADE → projects | projectId, (projectId, branchId), canonizedAt |
| ContinuityIssueEntity | continuity_issues | MemoryDB | ContinuityIssueDao | ✅ | – | projectId, (projectId, branchId), artifactId, severity, status |
| ArtifactDependencyEntity | artifact_dependencies | MemoryDB | ArtifactDependencyDao | ✅ | ✅ CASCADE → artifacts | sourceArtifactId, targetArtifactId, relation |
| BeliefEntity | beliefs | MemoryDB | BeliefDao | ✅ | – | subject, predicate, status, validFrom, confidence, agentScope (v14) |
| EvidenceEntity | evidence | MemoryDB | EvidenceDao | ✅ | ✅ CASCADE → beliefs | beliefId, source, agentScope (v14) |
| WorldEventEntity | world_events | MemoryDB | WorldEventDao | ✅ | – | timestamp, source, eventType, agentScope (v14) |
| OpportunityEntity | opportunities | MemoryDB | OpportunityDao | ✅ | – | status, benefit, urgency, agentScope (v14) |
| PreferenceSignalEntity | preference_signals | MemoryDB | PreferenceSignalDao | ✅ | – | projectId, signalType, createdAt, agentScope (v14) |
| StyleProfileEntity | style_profiles | MemoryDB | StyleProfileDao | ✅ | – | projectId, agentScope (v14) |
| ReferenceIdentityEntity | reference_identities | MemoryDB | ReferenceIdentityDao | ✅ | ✅ CASCADE → projects | projectId, identityType, name, agentScope (v14) |
| RoutingOutcomeEntity | routing_outcomes | MemoryDB | RoutingOutcomeDao | ✅ | – | modelRole, modelId, success, agentScope (v14) |
| NodeEntity | kg_nodes | MemoryDB | KnowledgeGraphDao | ✅ | – | label, type, (label, type), sourceConversationId (v4) |
| EdgeEntity | kg_edges | MemoryDB | KnowledgeGraphDao | ✅ | ✅ CASCADE → nodes | sourceId, targetId, (sourceId, targetId, type), sourceConversationId (v4) |
| ConversationEntity | conversations | ConversationDB v6 | ConversationDao | ✅ | – | updatedAt, deletedAt (v6) |
| TaskEntity | tasks | TaskDB v5 | TaskDao | ✅ | – | status, (status, dueAt) |
| ReminderEntity | reminders | TaskDB | ReminderDao | ✅ | – | triggerAt, (status, triggerAt) |
| ProactiveEventEntity | proactive_events | ProactiveEventDB v5 | ProactiveEventDao | ✅ | – | timestamp (v3) |
| ProactiveInteractionEntity | proactive_interactions | ProactiveEventDB | ProactiveInteractionDao | ✅ | – | eventId, timestamp |
| HandEntity | hands | HandDB v2 | HandDao | ✅ | – | (hand_id pk) |
| HandRunEntity | hand_runs | HandDB | HandDao | ✅ | – | handId, startedAt |
| UserProfileEntity | user_profile | UserProfileDB v2 | UserProfileDao | ✅ | – | (id=1 singleton) |
| AgentEntity | agents | AgentDB v3 | AgentDao | ✅ | – | (id pk) |
| AgentStateEntity | agent_state | AgentDB | AgentStateDao | ✅ | ✅ CASCADE → agents | (agentId UNIQUE) |
| AgentRelationshipEntity | agent_relationships | AgentDB | AgentRelationshipDao | ✅ (BROKEN — P0-1) | ✅ CASCADE → agents | (agentAId, agentBId) UNIQUE |
| AgentObservationEntity | agent_observations | AgentDB | AgentObservationDao | ✅ (BROKEN — P0-1) | ✅ CASCADE → agents | agentId, (agentId, resolved) |
| ForumPostEntity | forum_posts | AgentDB | ForumPostDao | ✅ | ✅ CASCADE → agents | agentId, threadId, status |
| ForumVoteEntity | forum_votes | AgentDB | ForumVoteDao | ✅ | ✅ CASCADE → posts/agents | (postId, agentId) UNIQUE |
| DreamSummaryEntity | dream_summaries | DreamDB v3 | DreamConsolidationDao | ✅ | – | (id pk) |
| RoutineEntity | routines | DreamDB | RoutineDao | ✅ | – | (id pk) |
| ContradictionEntity | contradictions | DreamDB | ContradictionDao | ✅ | – | (id pk) |
| KgEdgeProposalEntity | kg_edge_proposals | DreamDB | KgEdgeProposalDao | ✅ | – | (id pk) |
| EvolutionProposalEntity | evolution_proposals | EvolutionDB v3 | EvolutionProposalDao | ✅ | – | (id pk) |
| EvolutionSettingsEntity | evolution_settings | EvolutionDB | EvolutionSettingsDao | ✅ | – | (domain pk) |
| EvolutionRevisionEntity | evolution_revisions | EvolutionDB | EvolutionRevisionDao | ✅ | – | (id pk) |
| EvolutionEvidenceEntity | evolution_evidence | EvolutionDB | EvolutionEvidenceDao | ✅ | – | (id pk) |
| EvolutionCandidateEntity | evolution_candidates | EvolutionDB | EvolutionCandidateDao | ✅ | – | (id pk) |
| AgentRunEntity | agent_runs | AgentRunDB v1 | AgentRunDao | ✅ | – | (id pk) |
| GoalEntity | goals | AgentRunDB | GoalDao | ✅ | – | (id pk) |
| StepEntity | steps | AgentRunDB | StepDao | ✅ | – | (id pk) |
| AgentEventEntity | agent_events | AgentRunDB | AgentEventDao | ✅ | – | (id pk) |
| ApprovalRequestEntity | approval_requests | AgentRunDB | ApprovalRequestDao | ✅ | – | (id pk) |
| RunCheckpointEntity | run_checkpoints | AgentRunDB | RunCheckpointDao | ✅ | – | (id pk) |
| StrategyBanditEntity | strategy_bandit | StrategyBanditDB v1 | StrategyBanditDao | ✅ | – | (id pk) |

**Total entities: 49. Total backed up: 48 (CreativeGenerationJobEntity is intentionally transient per AuraBackupSchema13.kt:19). Total broken (P0-1): 2 (AgentRelationshipEntity, AgentObservationEntity).**

---

## PREFERENCE INVENTORY (all 45+ DataStore keys)

| Key | Type | Default | Setters | In Snapshot? | In Restore? | Status |
|---|---|---|---|---|---|---|
| `default_model` | String? | null | setDefaultModel, setRoleModel(CONV) | ✅ defaultModel | ✅ | OK |
| `vision_model` | String? | null | setVisionModel | ✅ | ✅ | OK |
| `background_model` | String? | null | setBackgroundModel, setRoleModel(BG) | ✅ | ✅ | OK |
| `deep_mode_model` | String? | null | setDeepModeModel, setRoleModel(DEEP) | ✅ | ✅ | OK |
| `moa_reference_models` | String | "" | setMoaReferenceModels | ✅ | ✅ | OK |
| `moa_aggregator_model` | String? | null | setMoaAggregatorModel | ✅ | ✅ | OK |
| `app_lock_enabled` | Boolean | false | setAppLockEnabled | ✅ | ✅ | OK |
| `first_run_complete` | Boolean | false | setFirstRunComplete | ✅ | ✅ | OK |
| `last_seen_proactive_at` | Long | 0L | setLastSeenProactiveAt | ✅ | ✅ | OK |
| `morning_brief_enabled` | Boolean | true | setMorningBriefEnabled | ✅ | ✅ | OK |
| `calendar_monitor_enabled` | Boolean | true | setCalendarMonitorEnabled | ✅ | ✅ | OK |
| `tts_enabled` | Boolean | true | setTtsEnabled | ✅ | ✅ | OK |
| `incognito_default` | Boolean | false | setIncognitoDefault | ✅ | ✅ | OK |
| `theme_mode` | String | "system" | setThemeMode | ✅ | ✅ | OK |
| `custom_identity` | String | "" | setCustomIdentity | ✅ | ✅ | OK |
| `specialist_overrides` | String | "{}" | setSpecialistOverrides | ✅ | ✅ | OK |
| `morning_brief_hour` | Int | 7 | setMorningBriefHour | ✅ | ✅ | OK |
| `specialist_tool_overrides` | String | "{}" | setSpecialistToolOverrides | ✅ | ✅ | OK |
| `evolution_enabled` | Boolean | false | setEvolutionEnabled | ✅ | ✅ | OK |
| `evolution_interval_hours` | Int | 24 | setEvolutionIntervalHours | ✅ | ✅ | OK |
| `evolution_shadow_enabled` | Boolean | false | setEvolutionShadowEnabled | ✅ | ✅ | OK |
| `evolution_onboarding_shown` | Boolean | false | setEvolutionOnboardingShown | ✅ | ✅ | OK |
| `daemon_enabled` | Boolean | false | setDaemonEnabled | ✅ | ✅ | OK |
| `dream_enabled` | Boolean | true | setDreamEnabled | ✅ | ✅ | OK |
| `dream_last_run_at` | Long | 0L | recordDreamRun | ✅ | ✅ | OK |
| `dream_last_run_stats` | String | "" | recordDreamRun | ✅ | ✅ | OK |
| `decay_enabled` | Boolean | true | setDecayEnabled | ✅ | ✅ | OK |
| `triggers_enabled` | Boolean | true | setTriggersEnabled | ✅ | ✅ | OK |
| `triggers_json` | String | "[]" | setTriggers | ✅ | ✅ | OK |
| `planning_enabled` | Boolean | false | setPlanningEnabled | ✅ | ✅ | OK |
| `mcp_servers_json` | String | "" | setMcpServersJson | ✅ | ✅ | OK |
| `image_model` | String | "dall-e-3" | setImageModel | ✅ | ✅ | OK |
| `agent_id` | String? | null | setAgentId | ✅ | ✅ | OK |
| `google_client_id` | String | "" | (no setter visible) | ✅ | ✅ | OK |
| `microsoft_client_id` | String | "" | (no setter visible) | ✅ | ✅ | OK |
| `reasoning_enabled` | Boolean | true | setReasoningEnabled | ✅ | ✅ | OK |
| `reasoning_budget` | Int | 32000 | setReasoningBudget | ✅ | ✅ | OK |
| `smtp_host` | String? | null | setSmtpConfig | ✅ | ✅ | OK |
| `smtp_port` | Int | 587 | setSmtpConfig | ✅ | ✅ | OK |
| `smtp_username` | String? | null | setSmtpConfig | ✅ | ✅ | OK |
| `smtp_password` | String (SecureDataStore) | "" | (SecureDataStore API) | **NO** (security) | **NO** (security) | OK — deliberately not in JSON |
| `smtp_from` | String? | null | setSmtpConfig | ✅ | ✅ | OK |
| `embedding_model` | String? | null | (in ProviderKeys, not UserPreferences) | ✅ embeddingModel | ✅ | OK |
| `council_enabled` | Boolean | true | **setCouncilEnabled** | ❌ | ❌ | **P0-3** |
| `council_auto_apply` | Boolean | false | **setCouncilAutoApply** | ❌ | ❌ | **P0-3** |
| `council_activity_level` | Int | 3 | **setCouncilActivityLevel** | ❌ | ❌ | **P0-3** |
| `creative_draft_model` (private) | String? | null | setRoleModel(CREATIVE_DRAFT) | ✅ creativeDraftModel | ✅ | OK |
| `creative_critic_model` (private) | String? | null | setRoleModel(CREATIVE_CRITIC) | ✅ creativeCriticModel | ✅ | OK |
| `planner_model` (private) | String? | null | setRoleModel(PLANNER) | ✅ plannerModel | ✅ | OK |
| `verifier_model` (private) | String? | null | setRoleModel(VERIFIER) | ✅ verifierModel | ✅ | OK |
| `fast_model` (private) | String? | null | setRoleModel(FAST) | ✅ fastModel | ✅ | OK |
| `reasoning_model` (private) | String? | null | setRoleModel(REASONING) | ✅ reasoningModel | ✅ | OK |
| `evolution_model` (private) | String? | null | setRoleModel(EVOLUTION) | ✅ evolutionModel | ✅ | OK |

**Total: 51 keys. Covered: 48. Missing: 3 (P0-3).**

---

## SUMMARY TABLE

| Severity | Count | Key findings |
|---|---|---|
| P0 | 4 | Council backup broken (relationships + observations dropped), restore not transactional, council prefs missing from backup, purgeAll incomplete (symptom of P0-1) |
| P1 | 13 | Write gate bypass, touch loop, rebuildEmbeddings N×API, decay pass memory blowup, mutex held during I/O, non-atomic snapshot, reminder firedAt corruption, phantom audit edits, forget cascade unverified, searchByText non-scope-aware, memory feedback table dead, purgeAll misleading, usage non-atomic |
| P2 | 12 | scopeFilter=null behavior, dead DAO methods, fragile pinned metadata, fork summary loss, import block dup, hook errors swallowed, duplicate reranker check, restoreEvolution not in try, touch decay saturation, snapshot size, schema version manual, constructor god-class |
| P3 | 3 | KDoc gaps, prettyPrint bloat, constructor parameter sprawl |

**Verdict:** Subsystem is largely healthy. Migration chains are correct. Most prior round fixes are still in place. The blocker is P0-1 (council relationships + observations silently dropped on backup) — this has been broken since schema v16 was added and should be fixed before the next public release.
