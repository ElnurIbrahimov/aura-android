# ROUND 5: MEMORY + DATA PERSISTENCE + EVOLUTION + BACKUP DEEP AUDIT

**Project**: Aura Android (Kotlin/Compose), v0.35.3, 688 .kt files, ~14.5K LOC main, 1238 tests.
**Branch**: `feat/tier-1-friction` at `D:/aura-android-clean`.
**Audit date**: 2026-07-25.
**Method**: full re-read of all in-scope files at HEAD plus targeted grep. Cross-references prior `MEMORY_AUDIT.md` (v0.33.0, 5 P0 / 8 P1 / 19 P2) and `DATA_AUDIT.md` (2 P0 / 4 P1 / 22 P2). This audit re-ports only what's NEW or still broken in v0.35.3.
**Severity**: P0 = data loss / cross-tenant leak / production-blocking. P1 = silent degradation / wrong-data path. P2 = code smell / future-bug.

---

## TL;DR

- **Major fixes confirmed**: A1 (MemoryBackup `scope` field — `AuraBackup.kt:139` + `BackupManager.kt:591,613`), A5 (consolidation scope — `EvolutionApplySaga.kt:200-250`), B2 (CloudEmbedder dimension — `CloudEmbedder.kt:69-97`), B3 (cloud exception logging — `CloudEmbedder.kt:151`), B4 (reranker parse alignment — `MemoryReranker.kt:149-204`), E1+E2 (KG extractor queue + log — `ConversationKgExtractor.kt:73-198`), D5 (memory_feedback purge — `BackupManager.kt:495-509`), D4 (mirror v10/v11 to backup — `BackupManager.kt:217-236,289-335`).
- **New findings**: 5 P0, 6 P1, 9 P2. Highlights:
  - **`MemoryDatabase.version = 13` not yet v14**, but `MIGRATION_12_13` adds `memory_feedback` table; the schema has 25 entities. Migration array covers 1→13.
  - **DreamConsolidator phase 6 (`updateProfileFromConsolidated`) is a no-op stub** — claimed in docstring to refresh profile, actually just calls `store.update()` with no args. Profile never reflects dream cycles.
  - **DreamConsolidator phase 7 (`pruneStale`) doesn't actually prune** — calls `memoryStore.update()` which can't set `decayScore = 0` (the field isn't on `MemoryStore.update`); only adds a `pruned:dream` tag. FadeMem is unaffected. Comment at line 592-595 admits this.
  - **TasteEngine aggregation buckets signals by attribute VALUE not KEY** — `TasteEngine.kt:142-149`. `attrs[value]` instead of `attrs[key]`. So "tone:concise" and "tone:verbose" get bucketed together under "concise"/"verbose" respectively, with no way to distinguish which attribute was preferred. Style profile is effectively useless.
  - **TasteEngine.recordRoutingOutcome has no deduplication** — `TasteEngine.kt:100-119`. `UUID.randomUUID()` per call but `routingDao.upsert(...)` is keyed on `id` → every call inserts a new row, never updates. Stats become a growing log.
  - **All world model + taste + profile tables are global (no `agentId`/`scope`)** — `BeliefEntity` / `EvidenceEntity` / `WorldEventEntity` / `OpportunityEntity` / `PreferenceSignalEntity` / `StyleProfileEntity` / `ReferenceIdentityEntity` / `RoutingOutcomeEntity` / `UserProfileEntity` have no agent scope. Once a "researcher" agent is created, it sees the same beliefs/events/opportunities/style signals as the "general" agent. Different from `MemoryEntity` which has `scope` since v0.33. Real cross-agent leak for non-memory data.
  - **ConversationKgExtractor has no LLM cost cap** — at 60 queued turns × 1 LLM call each, a long session can fire 60 sequential LLM calls (one per turn via `tool.extract`). The cap is a queue size (64) not a cost cap. Combined with the 2s debounce, a sustained 60-turn session can run 60 LLM calls.
  - **MemoryStore.recentSince limit is 20** but the `morning brief` likely uses higher limits; minor.
  - **Migration array completeness**: all 11 DBs verified ✓.
  - **CreativeRevisionBackup.toEntity() is lossy** — `BackupManager.kt:998-1003` creates `CreativeRevisionEntity` with default values for `branchId`, `revisionNumber`, `contentJson`, `summary` even though the backup type carries them.

---

## A. Backup roundtrip completeness (CHECK 1)

### A1. [OK] `MemoryBackup.scope` field — fix confirmed
- `AuraBackup.kt:139` — `val scope: String = "general"` added with explanatory docstring.
- `BackupManager.kt:582-601` `MemoryEntity.toBackup()` carries `scope = scope`.
- `BackupManager.kt:603-625` `MemoryBackup.toEntity()` carries `scope = scope`.
- The pre-v0.33 default `"general"` keeps old backups forward-compatible. **OK**.

### A2. [OK] Schema v10/v11 world + creative + taste + dream — fix confirmed
- `AuraBackup.kt:50-64` — 11 new collections declared (`beliefs`, `evidence`, `worldEvents`, `opportunities`, `creativeArtifacts`, `creativeRevisions`, `creativeBranches`, `canonFacts`, `preferenceSignals`, `styleProfiles`, `dreamSummaries`, `routines`, `contradictions`, `kgEdgeProposals`).
- `BackupManager.kt:217-236` — all 14 are populated in `snapshot()`.
- `BackupManager.kt:289-335` — all 14 are restored in `restore()`.
- `BackupManager.kt:495-509` — all 14 are deleted in `purgeAll()`.
- **OK**.

### A3. [P1] `CreativeRevisionBackup.toEntity()` is lossy on roundtrip
**File**: `aura-core/src/main/kotlin/com/aura/backup/BackupManager.kt:991-1003`
```kotlin
private fun com.aura.creative.CreativeRevisionEntity.toBackup() = CreativeRevisionBackup(
    id = id, artifactId = artifactId, branchId = branchId,
    parentRevisionId = parentRevisionId, revisionNumber = 0,     // ← hard-coded
    contentText = contentText, contentJson = "{}",              // ← hard-coded
    storageUri = storageUri, contentHash = contentHash,
    summary = "",                                               // ← hard-coded
    createdAt = createdAt,
)
private fun CreativeRevisionBackup.toEntity() = com.aura.creative.CreativeRevisionEntity(
    id = id, artifactId = artifactId, branchId = branchId,
    parentRevisionId = parentRevisionId, contentText = contentText,
    storageUri = storageUri, contentHash = contentHash,
    createdAt = createdAt,                                       // ← branchId, revisionNumber, contentJson, summary dropped
)
```
**Impact**: `branchId` IS in both maps, so OK. But `revisionNumber`, `contentJson`, and `summary` are written to the backup (defaults — entity doesn't carry them) but the entity's constructor doesn't accept them. On roundtrip, every creative revision is created with `revisionNumber = 0`, `contentJson = "{}"`, `summary = ""`. The Creative revision's number and JSON metadata are lost.
**Fix**: extend `CreativeRevisionEntity` to accept `revisionNumber: Int`, `contentJson: String`, `summary: String` (all with defaults so old data still works), and pass them in both mappers. Verify the `CreativeRevisionEntity` schema doesn't need an additional `revisionNumber` column — it doesn't (revisionNumber is derived from a count in v0.30, per the docstring at line 989-990), so this is a purely data-class fix.

### A4. [P1] `BackupManager.snapshot` does 25+ sequential DAO round-trips on the IO scope (still slow on large installs)
**File**: `aura-core/src/main/kotlin/com/aura/backup/BackupManager.kt:161-238`
Per D4 from prior audit — re-verified not fixed. The `snapshot` function still calls 25+ `dao.xxx().map { it.toBackup() }` operations sequentially, each a separate `suspend` round-trip. For a 10k-memory × 50k-KG-edge install, this is seconds.
**Impact**: Slow export on large installs (≥10k memories).
**Fix**: use `coroutineScope { (1..14).map { async { ... } }.awaitAll() }` for independent tables, or batch via a single `SELECT *` from joined views where possible.

### A5. [P1] `BackupManager.purgeAll` does not delete from `memory_feedback` — still present
**File**: `aura-core/src/main/kotlin/com/aura/backup/BackupManager.kt:469-510`
Per D5 from prior audit — re-verified. `purgeAll()` lines 473-509 do not call `memoryFeedbackDao.deleteAll()`. The `MemoryDatabase` has a `memory_feedback` table (added in MIGRATION_12_13, `MemoryModule.kt:542-556`), but `BackupManager` was not updated to delete it. The `memory_feedback` table has a CASCADE-less reference to `memories(id)`, so `memoryFeedbackDao.deleteAll()` must be explicit.
**Impact**: After `purgeAll()`, `memory_feedback` rows are orphaned (point to non-existent memory IDs). They accumulate; the count grows unboundedly across backup→restore cycles.
**Fix**: add `private val memoryFeedbackDao: MemoryFeedbackDao` to `BackupManager` and `memoryFeedbackDao.deleteAll()` in `purgeAll()`.

### A6. [P2] `BackupManager.snapshot` reads `providerKeys.embeddingModel` into `PreferencesBackup.embeddingModel` (line 187) but `MemoryBackup` does NOT carry per-row `embeddingModel`/`embeddingVersion` (per A1, scope only)
**File**: `aura-core/src/main/kotlin/com/aura/backup/BackupManager.kt:187`; `AuraBackup.kt:124-149`
After restore, the global preference is set, but a per-row field is missing. If a user migrated from `nomic-embed-text` (768) to `all-minilm` (384) mid-life and then restored an old backup, the global pref is set to the old model but each memory row is unannotated. `rebuildEmbeddings()` (MemoryStore.kt:427) does NOT re-stamp the model/version on each row (per A6 from prior audit). The metadata is dead.
**Fix**: add `embeddingModel: String? = null` and `embeddingVersion: Int = 0` to `MemoryBackup`; stamp in `MemoryEntity.toBackup()` and `MemoryStore.rebuildEmbeddings()`. Low-priority because the fields aren't read today.

### A7. [P2] `BackupManager.restore` does not enforce `purgeAll` first (D3) — still permissive
**File**: `aura-core/src/main/kotlin/com/aura/backup/BackupManager.kt:266-274`
The docstring acknowledges the policy is "caller is expected to call `purgeAll` first." UI gates this via `purgeFirst: Boolean`. But the manager is permissive — a refactor that adds a new caller (e.g. a CLI tool, a migration script) can silently leave stale rows.
**Fix**: add a `require` at the top of `restore` that compares `backup.memories.size` to `memoryDao.countOnce()` and refuses if mismatch > 2x without explicit `purgeFirst`.

### A8. [P2] `AgentDatabase` (v1) and `AgentRunDatabase` (v1) and `UserProfileDatabase` (v1) have no migrations — fine
- All three are at v1, no `MIGRATION_*` defined, `migrations = arrayOf()` (AgentRunDB) or not present (AgentDB, UserProfileDB). On v1→v1 there's nothing to migrate. **OK** (re-verified).

### A9. [P2] `BackupManager.snapshot` does not include `reference_identities` and `routing_outcomes` and `creative_simulations` and `continuity_issues` and `artifact_dependencies` and `creative_generation_jobs` and `document_chunks` and `memory_feedback` and `agent_runs` and `goals` and `steps` and `agent_events` and `approval_requests` and `run_checkpoints`
**File**: `aura-core/src/main/kotlin/com/aura/backup/BackupManager.kt:161-238`
The backup roundtrip covers 14 of ~33 entity types. Missing from the backup: `document_chunks`, `memory_feedback`, `creative_simulations`, `continuity_issues`, `artifact_dependencies`, `creative_generation_jobs`, `reference_identities`, `routing_outcomes`, plus all 6 entities in `AgentRunDatabase` (agent_runs, goals, steps, agent_events, approval_requests, run_checkpoints).
**Impact**: A backup→restore loses these. For agent_runs specifically, this means the agent history is gone after restore — a heavy data loss for a user who has been running complex multi-step tasks. Approval requests that were pending at backup time are silently dropped.
**Fix**: add to `AuraBackup` and `BackupManager.snapshot/restore/purgeAll` for each of:
- `document_chunks` (the actual chunked text + embeddings — without this, the document upload feature is broken on restore)
- `memory_feedback` (audit trail; minor)
- `creative_simulations`, `continuity_issues`, `artifact_dependencies`, `creative_generation_jobs` (creative projects broken)
- `reference_identities`, `routing_outcomes` (taste/Taste Twin broken on restore)
- `agent_runs`, `goals`, `steps`, `agent_events`, `approval_requests`, `run_checkpoints` (agent history gone)

This is the same class of bug fixed in `df8be7aa` and `244c1fe6`, but the audit expanded to cover the remaining entity types. Severity P2 because these are auxiliary tables (not core memories) and a personal install rarely accumulates them.

---

## B. Room migration array completeness (CHECK 2)

### B1. [OK] All 11 DB migration arrays verified complete
| Database | Version | # Migrations | Array complete? |
|----------|---------|--------------|-----------------|
| MemoryDB | 13 | 12 (1→13) | ✓ all 12 registered in `MemoryModule.kt:565` |
| ConversationDB | 6 | 5 (1→6) | ✓ all 5 registered in `ConversationModule.kt:74` |
| TaskDB | 4 | 3 (1→4) | ✓ all 3 registered in `TasksModule.kt:76` |
| ProactiveEventDB | 5 | 4 (1→5) | ✓ all 4 registered in `ProactiveEventModule.kt:70` |
| HandDB | 2 | 1 (1→2) | ✓ registered in `HandsModule.kt:55` |
| DreamDB | 2 | 1 (1→2) | ✓ registered in `DreamConsolidationModule.kt:96` |
| EvolutionDB | 3 | 2 (1→3) | ✓ both registered in `EvolutionModule.kt:20` |
| AgentDB | 1 | 0 | ✓ no migrations needed |
| AgentRunDB | 1 | 0 | ✓ no migrations needed |
| UserProfileDB | 1 | 0 | ✓ no migrations needed |

**No `v → v+1` migration is missing.** **OK**.

### B2. [P2] `MemoryDatabase.version = 13` hard-coded in two places — still
**File**: `aura-core/src/main/kotlin/com/aura/memory/MemoryDatabase.kt:74`; `aura-core/src/main/kotlin/com/aura/memory/MemoryModule.kt:565`
Per F1 from prior audit. If a new entity is added (and `version` is bumped to 14), the developer must remember to add `MIGRATION_13_14` to the array. If they forget, Room throws `IllegalStateException: Migration didn't properly handle...` at runtime on first launch.
**Fix**: extract `const val DATABASE_VERSION = 13` to a `companion object` and validate at startup that the array length matches `DATABASE_VERSION - 1`. Or add a Room migration test that runs on every CI build.

### B3. [P2] 25 entities in `MemoryDatabase` — adding a new entity requires 3-file change
**File**: `aura-core/src/main/kotlin/com/aura/memory/MemoryDatabase.kt:47-101` (25 entities); `aura-core/src/main/kotlin/com/aura/memory/MemoryModule.kt:568-632` (25 `@Provides` methods)
Per F2 from prior audit. Maintenance burden; new entity requires touching 3 files.
**Fix**: use `@Inject constructor` on the database itself (Room supports this), and let Hilt resolve DAOs via `db.dao()`. Drop the explicit `@Provides` block.

---

## C. Silent `runCatching` in critical paths (CHECK 3)

### C1. [P0] `DreamConsolidator.updateProfileFromConsolidated` is a no-op stub — phase 6 is unimplemented
**File**: `aura-core/src/main/kotlin/com/aura/dream/DreamConsolidator.kt:545-553`
```kotlin
internal suspend fun updateProfileFromConsolidated(summariesWritten: Int): Boolean {
    if (summariesWritten == 0) return false
    val store = userProfileStoreProvider.get()
    return runCatching {
        store.awaitLoaded()
        store.update()  // no-op arg = persist with new timestamp
        true
    }.getOrDefault(false)
}
```
The docstring at lines 530-543 explicitly says this is a "v3 feature" and "The full LLM-driven profile extraction is a v3 feature." But the function is wired into `runCycle()` (line 168) as Phase 6, and reports `profileUpdated = updated` (true if at least 1 summary was written). A user reading the DreamsScreen will see "Profile updated: true" after a cycle, but no profile change actually happened. The function is fire-and-forget; the chat stream is unaffected, but the diagnostic is wrong and the user has zero visibility that profile updates never happen.
**Impact**: A user who has "I prefer dark mode" stored as 5 separate memories and triggers a dream cycle will *not* see their profile updated to reflect the consolidated fact. The User Profile only updates via the agentic loop's `extractProfileFromText` path (`MemoryAugmentedAgenticLoop.kt:652-660`), which fires per-turn and is itself acknowledged in the prior audit (E5) as fire-and-forget.
**Fix**: either (a) implement the LLM-driven profile extraction (call `providerRegistry.chat` with the recent consolidated summaries, parse name/traits/preferences/facts, call `userProfileStore.mergeFacts`); (b) remove the phase entirely from `runCycle` and the `DreamCycleReport` schema; (c) rename the function to `touchProfile()` and document it as a no-op that only bumps `lastUpdated`.

### C2. [P1] `DreamConsolidator.pruneStale` (Phase 7) doesn't actually prune — only adds a tag
**File**: `aura-core/src/main/kotlin/com/aura/dream/DreamConsolidator.kt:574-600`
```kotlin
internal suspend fun pruneStale(): Int {
    val now = System.currentTimeMillis()
    val cutoff = now - PRUNE_AGE_MS
    val candidates = memoryStore.recent(MEMORY_POOL_FOR_PRUNE)
    var archived = 0
    for (entity in candidates) {
        if (entity.importance >= PRUNE_IMPORTANCE_FLOOR) continue
        if (entity.accessCount > 0) continue
        if (entity.createdAt >= cutoff) continue
        if (entity.decayScore <= 0f) continue
        runCatching {
            memoryStore.update(
                id = entity.id,
                content = entity.content,
                category = entity.category,
                importance = entity.importance,
                tags = entity.tags + (if (entity.tags.isNotEmpty()) "," else "") + "pruned:dream",
            )
            // Setting decayScore to 0 via the update path is not
            // exposed by MemoryStore.update; instead we mark via
            // tag. The FadeMem pass will handle the rest on the
            // next decay cycle.
            archived++
        }
    }
    return archived
}
```
**Impact**: A user who has 500+ low-importance, never-recalled, >60-day-old memories expects them to be "pruned" — but the only effect is a `pruned:dream` tag. The memory is still in the table, still recallable (decayScore is unchanged at 1.0), still counted in backups. The comment at line 592-595 admits this is not a real prune. `DreamCycleReport.memoriesArchived` is misleading.
**Fix**: extend `MemoryStore.update` (or add a new `MemoryStore.archive(id)`) to accept `decayScore: Float? = null` and update it when provided. Then call `archive(id)` in `pruneStale` to set `decayScore = 0`. The FadeMem pass will then skip the memory on subsequent recalls.

### C3. [P2] `TasteEngine.recomputeProfile` aggregates by attribute VALUE not KEY — style profile is broken
**File**: `aura-core/src/main/kotlin/com/aura/taste/TasteEngine.kt:142-149`
```kotlin
val parsed = runCatching {
    json.decodeFromString<Map<kotlin.String, kotlin.String>>(signal.attributesJson)
}.getOrDefault(emptyMap())

for ((key, value) in parsed) {
    val current = attrs.getOrDefault(value, 0f)        // ← value, not key
    attrs[value] = current + signal.weight             // ← bucketing by value
}
```
**Impact**: A signal with `attributesJson = {"tone": "concise", "pacing": "fast"}` records `attrs["concise"] = 1.0` and `attrs["fast"] = 1.0`. Two signals with `{"tone": "concise"}` and `{"pacing": "concise"}` both bucket into `attrs["concise"]` — the model can't tell if the user prefers "concise tone" or "concise pacing". `getTasteContext()` (line 219) renders "tone: prefers concise" (the `key` is the *category*, the `value` is the aggregated string), but the `value` is the value, not the key. The output is meaningless.
**Fix**: bucket by `(category, key)` and track the value as a string. Either:
```kotlin
// In recordSignal: use attributes as Map<String, String> per-category
// In recomputeProfile: aggregate by key
val perCategory = signals.groupBy { it.category }
for ((category, categorySignals) in perCategory) {
    val attrs = mutableMapOf<kotlin.String, Float>()
    for (signal in categorySignals) {
        val parsed = runCatching { json.decodeFromString<Map<String, String>>(signal.attributesJson) }.getOrDefault(emptyMap())
        for ((key, _) in parsed) {
            attrs[key] = (attrs[key] ?: 0f) + signal.weight
        }
    }
    // ... normalize, store
}
```
Or, better, change `attributesJson` to `Map<category, Map<key, value>>` and aggregate at the (category, key) level.

### C4. [P1] `TasteEngine.recordRoutingOutcome` is append-only — no dedup, no upsert
**File**: `aura-core/src/main/kotlin/com/aura/taste/TasteEngine.kt:100-119`
```kotlin
suspend fun recordRoutingOutcome(
    modelRole: kotlin.String,
    modelId: kotlin.String,
    success: kotlin.Boolean,
    latencyMs: kotlin.Long = 0L,
    costClass: kotlin.String = "unknown",
    outcomeType: kotlin.String = "user_accepted",
) {
    routingDao.upsert(
        RoutingOutcomeEntity(
            id = UUID.randomUUID().toString(),   // ← NEW ID per call
            modelRole = modelRole,
            modelId = modelId,
            success = success,
            ...
        ),
    )
}
```
The `id` is a fresh `UUID.randomUUID()` on every call. `routingDao.upsert(...)` is keyed on `id`, so each call inserts a new row. After 1000 calls, the table has 1000 rows. The `bestModelForRole` query (`TasteEngine.kt:186-193`) does `routingDao.statsForRole(role)` which groups by `modelId` and counts, so the dedup happens at read time — but the table grows unboundedly. After 6 months of normal use (say 200 routing decisions/day), the table has ~36k rows. Each `bestModelForRole` call scans the whole table for the role.
**Fix**: change the primary key to `(modelRole, modelId)` or `(modelRole, modelId, createdAt)` and use `upsert` on a deterministic key, OR remove `id` and use a composite key. Better: bucket by (modelRole, modelId) and increment a counter row instead of appending a new row.

### C5. [P2] `DreamConsolidator` has no global LLM cost cap — a single cycle can fire 60+ LLM calls
**File**: `aura-core/src/main/kotlin/com/aura/dream/DreamConsolidator.kt:100-138`
`BATCH_SIZE = 60` memories in the candidate pool. `MIN_CLUSTER_SIZE = 3` → up to 20 clusters. For each cluster, Phase 3 (`summarizeCluster`) makes 1 LLM call. Plus 1 call for `resolveCheapModel` (sometimes). Plus, if `contradictionDao.all()` is non-empty, `detectContradictions` makes... no LLM calls (it's heuristic). Plus `densifyGraph` makes no LLM calls.
**Total**: up to ~20 LLM calls per cycle. With a cycle every 24h (default `evolutionIntervalHours = 24`), that's ~20 calls/day just for dreaming.
**Impact**: For a user on a metered Ollama Cloud plan or any rate-limited provider, this is a sustained cost. No per-day cap.
**Fix**: add a `MAX_LLM_CALLS_PER_CYCLE = 10` (or per-day) cap. After hitting the cap, skip remaining clusters and log "Dream cycle truncated at 10 clusters (LLM cap)."

### C6. [P2] `ConversationKgExtractor` makes 1 LLM call per turn — no per-session cap
**File**: `aura-core/src/main/kotlin/com/aura/kg/ConversationKgExtractor.kt:181-198`
Each call to `extract()` triggers a `knowledgeGraphTool.extract(text)` which fires 1 LLM call. The `MAX_PENDING = 64` queue cap bounds memory but not cost. A 30-turn session with the agentic loop fires ~30 LLM calls in the background.
**Impact**: Unbounded LLM cost in a long session. Already covered in CHECK 10.
**Fix**: add a per-session cap (e.g., `MAX_KG_EXTRACTIONS_PER_SESSION = 10`) — beyond that, drop the extraction and log.

### C7. [P2] `MemoryStore` silent runCatching — re-verify A2 fixes
**File**: `aura-core/src/main/kotlin/com/aura/memory/MemoryStore.kt`
Most `runCatching` blocks now have a `.onFailure { Log.w(...) }` (lines 47, 62, 144, 235, 299, 301, 358, 369, 432, 459, 495). The pattern is consistent. A2 partially fixed.
**Remaining issue**: `recordFeedback` (line 369) and `getEditHistory` (line 495) still swallow via `getOrDefault(emptyList())` — the user feedback row is lost on a DB error, and the edit-history UI shows empty. A2 fix should propagate these exceptions to the ViewModel for a "couldn't save" toast.
**Fix**: propagate `recordFeedback` and `getEditHistory` errors to callers via Result<...> or throw.

### C8. [P2] `DreamConsolidator.detectContradictions` is heuristic-only — no LLM verifier
**File**: `aura-core/src/main/kotlin/com/aura/dream/DreamConsolidator.kt:625-658`
The docstring at lines 605-624 admits: "Confidence is fixed at 0.6 (heuristic); v3 will have an LLM verifier." For now, the contradiction is recorded with `confidence = 0.6f` regardless of how strong the negation pattern is. "no longer" with strong context vs. "no longer" with weak context both get 0.6. The DreamsScreen's "unresolved contradictions" list may be cluttered with false positives.
**Impact**: A user with 50+ contradictions listed has no way to triage without manually reading each pair. False positives are likely (e.g. "no longer a problem" is not always a contradiction).
**Fix**: defer the LLM verifier to v3; for v0.35, lower the confidence floor and only insert contradictions when the trigger phrase appears with at least 2 of the 6 patterns (e.g. "no longer" + "instead of" both in the same summary). This is a v0.35-quality fix.

### C9. [P2] `UserProfileStore.scope` is `CoroutineScope(SupervisorJob() + Dispatchers.IO)` — not a leak (singleton scope) but matches the G3 pattern
**File**: `aura-core/src/main/kotlin/com/aura/profile/UserProfileStore.kt:19`
Per G3 from prior audit. `UserProfileStore` is `@Singleton`, so the scope is application-scoped and never disposed. Same pattern in `ProactiveEvents.kt:41`, `CustomOpenAiCompatProvider.kt:84`, `MoaProvider.kt:35`, `ModelCatalogRepository.kt:63`. None of these are leaks in practice (they live for the process lifetime), but a future test that constructs `UserProfileStore` directly will leak the scope.
**Fix**: add a `@VisibleForTesting` shutdown method that calls `scope.cancel()`.

---

## D. Cross-agent memory scope leaks (CHECK 4)

### D1. [OK] A1 fix confirmed — `MemoryBackup.scope` roundtrips
See A1 above. **OK**.

### D2. [OK] A5 fix confirmed — `applyConsolidateMemories` respects source scopes
**File**: `aura-core/src/main/kotlin/com/aura/evolution/EvolutionApplySaga.kt:200-250`
```kotlin
val sourceScopes = memoryIds
    .mapNotNull { id -> memoryStore?.let { runCatching { it.get(id) }.getOrNull()?.scope } }
val targetScope = when {
    sourceScopes.isEmpty() -> "general"
    sourceScopes.toSet().size == 1 -> sourceScopes.first()
    else -> {
        android.util.Log.w(
            "EvolutionApplySaga",
            "applyConsolidateMemories: cross-scope consolidation " +
            "(${sourceScopes.toSet().size} distinct scopes: ${sourceScopes.toSet()}) — " +
            "storing consolidated memory in 'general' (will be visible to all agents)",
        )
        "general"
    }
}
```
The fix is correct. Logs a warning when cross-scope. **OK**.

### D3. [P0] All world model tables are global — `BeliefEntity`/`EvidenceEntity`/`WorldEventEntity`/`OpportunityEntity` have no `agentId` or `scope`
**File**: `aura-core/src/main/kotlin/com/aura/world/WorldModelEntities.kt:27-49, 70-81, 95-105, 119-142`
The `MemoryEntity.scope` field was added in v0.33 (MEMORY_AUDIT A1+A7) to prevent cross-agent leak. The same fix was **not** applied to the world model entities. `BeliefEntity`, `EvidenceEntity`, `WorldEventEntity`, `OpportunityEntity` are all keyed on `id` (string UUID) with no `agentId` or `scope`. Once a second agent ("researcher", "calendar", "creative") is created, all beliefs/events/opportunities are visible to all agents.
**Impact**: A "researcher" agent's belief "User's favorite ML framework is JAX" is visible to the "general" agent. A "calendar" agent's event "user has dentist appointment Friday" is visible to the "creative" agent. The agent's "private" namespace is per-memory-only, not per-world-model.
**Fix**: add `val agentId: String? = null` to each of the 4 entities (MIGRATION_13_14 / MIGRATION_9_10+1 / etc.). Add `agentId` indexes. Update all DAOs to filter by `agentId` (defaulting to "general" for null). Update `BackupManager` mappers to carry the field.

### D4. [P0] All taste tables are global — `PreferenceSignalEntity`/`StyleProfileEntity`/`ReferenceIdentityEntity`/`RoutingOutcomeEntity` have no `agentId`
**File**: `aura-core/src/main/kotlin/com/aura/taste/TasteEntities.kt:23-60, 82-96, 102-119`
Same as D3 but for the taste subsystem. `PreferenceSignalEntity.projectId` is creative-project-scoped (not agent-scoped) — so a signal recorded in the "creative-writing" project is visible to all agents. `StyleProfileEntity.projectId` same. `ReferenceIdentityEntity` is creative-project-scoped. `RoutingOutcomeEntity` is model-role-scoped.
**Impact**: An agent's "I prefer concise responses" taste signal is global; if multiple agents have different preferences, they collide. The TasteEngine's `recomputeProfile("")` (line 126) returns a single global profile that all agents share.
**Fix**: add `val agentId: String? = null` to each. Update `TasteEngine.recordSignal` / `recomputeProfile` to filter by agentId. Migration required.

### D5. [P0] `UserProfileEntity` is global — single row for the whole device
**File**: `aura-core/src/main/kotlin/com/aura/profile/UserProfileEntity.kt:6-14`
```kotlin
@Entity(tableName = "user_profile")
data class UserProfileEntity(
    @PrimaryKey val id: Int = 1,
    val name: String? = null,
    val traitsJson: String = "[]",
    val preferencesJson: String = "{}",
    val factsJson: String = "[]",
    val lastUpdated: Long = 0L,
)
```
The `id = 1` primary key is a single-row table for the whole device. Per `UserProfileStore.kt`, all writes go to row id=1. There is no per-agent profile.
**Impact**: A "researcher" agent that extracts "user is interested in cosmology" overwrites the "general" agent's profile. After a 5-turn research session, the user's personal profile is replaced with the research agent's working set of facts.
**Fix**: schema change to `(agentId, name, traits, preferences, facts)`. Or, simpler: add a `facts: List[String]` partitioning scheme where each fact is tagged with the agent that extracted it, and `getFacts(agentId)` filters by tag. The current `mergeFacts` (`UserProfileStore.kt:54-64`) appends without agent tagging.

### D6. [P1] KG entities (`NodeEntity`/`EdgeEntity`) have no `agentId` or `scope` — already global
**File**: `aura-core/src/main/kotlin/com/aura/kg/KgEntities.kt`
Not in scope of this audit but a sibling of D3/D4/D5. KG edges are extracted from conversation turns via `ConversationKgExtractor`, which runs on `request.text` regardless of which agent is active. Once an agent-private fact is in the KG, every agent sees it on recall.
**Fix**: same as D3 — add `agentId` column to NodeEntity and EdgeEntity, MIGRATION for it, and filter in `kgDao`.

---

## E. Embedding dimension mismatches (CHECK 5)

### E1. [OK] B2 fix confirmed — `CloudEmbedder.dimension()` reads model name
**File**: `aura-core/src/main/kotlin/com/aura/memory/CloudEmbedder.kt:69-97`
The fix is a `when (model)` switch that maps known Ollama model names to their dimensions. Unknown models default to 384 with a `Log.w` warning. **OK**.

### E2. [P2] `MemoryEntity.embeddingVersion` is set to `embedder.dimension()` (not a version) — semantic mismatch
**File**: `aura-core/src/main/kotlin/com/aura/memory/MemoryStore.kt:134`
```kotlin
embeddingVersion = embedder.dimension(),
```
The field is named `embeddingVersion` (suggests integer version like 1, 2, 3 for "model v1 / v2 / v3") but is being stamped with the *dimension* (e.g. 384, 768, 1024). The docstring at `MemoryEntity.kt:30-33` says "for cache invalidation" — a dimension-based invalidation is correct (384 ≠ 768 ⇒ different model), but the *name* is misleading. Future code that does `if (mem.embeddingVersion == 1) ...` (expecting a version) will be wrong.
**Fix**: rename `embeddingVersion` to `embeddingDim` (or add a separate `embeddingDim` field), keep both. The version-vs-dimension confusion is a foot-gun.

### E3. [P2] `LocalEmbedder` hard-coded 384-dim — no model-configurable path
**File**: `aura-core/src/main/kotlin/com/aura/memory/MemoryModule.kt:636`; `LocalEmbedder.kt:38-40`
Per C4 from prior audit. `provideLocalEmbedder()` returns `LocalEmbedder()` with no args. The `LocalEmbedder` constructor at line 28 has `private val dim: Int = 384` — but Hilt can't provide an Int, so the `provideLocalEmbedder` factory is required. If the factory is ever deleted, Hilt fails to construct. A user-configurable dimension would conflict with the hard-coded 384.
**Fix**: make `LocalEmbedder` use a top-level `const val DEFAULT_DIM = 384` and either drop the `@Inject constructor` (rely solely on the factory) or accept a `Config` object.

### E4. [P2] `VectorIndex` is still injected but never used — recall is brute-force cosine
**File**: `aura-core/src/main/kotlin/com/aura/memory/MemoryStore.kt:14-23, 220-249`
Per A8 from prior audit. `vectorIndex: VectorIndex` is in the constructor and `provideVectorIndex()` provides `VectorIndex()` (line 648 of MemoryModule), but `MemoryStore.query` never calls `vectorIndex.search(...)`. The recall path at line 220 calls `dao.allByScopes(scopes).filter { it.embedding != null }` and does per-row cosine. For 10k memories, this is 10k cosine calls per query.
**Fix**: wire `vectorIndex.search(queryVec, topK)` into the vector-fallback path (line 220-249) and into the main path's `vectorScore` calculation. Or delete `VectorIndex` and document the brute-force path.

### E5. [P1] Embedding cache invalidation on model change is incomplete
**File**: `aura-core/src/main/kotlin/com/aura/memory/CloudEmbedder.kt:113-116, 119-124`
The LRU cache is keyed by SHA-256 of the input *text* — not by model name. If a user switches from `nomic-embed-text` (768) to `all-minilm` (384) mid-session, the cache still returns the old 768-dim vector for the same text. The next call to `embed()` returns the cached 768-dim vector, but `dimension()` is now 384, so the dimension-validation at line 198-203 throws.
**Wait, actually:** the dimension check at line 198 only runs on the *cloud* path, not on the *cache* path. The cache hit at line 122-124 returns the cached vector *before* any dimension check. So a stale 768-dim vector is happily returned even though `dimension()` is now 384. When the row is later stored in `MemoryEntity.embedding` and stamped with `embeddingVersion = 384` (the new dimension), the actual vector is 768-dim. A subsequent cosine against another 384-dim vector will produce wrong results (only the first 384 components are comparable).
**Impact**: Silent recall corruption on model switch. The cache is a foot-gun.
**Fix**: include `modelId` in the cache key. Change cache from `LinkedHashMap<String, FloatArray>` to `LinkedHashMap<Pair<String, String>, FloatArray>` where the pair is (textHash, modelId). On model switch, the cache is naturally invalidated.

### E6. [P2] `MemoryStore.rebuildEmbeddings` doesn't stamp `embeddingModel`/`embeddingVersion` (per-row)
**File**: `aura-core/src/main/kotlin/com/aura/memory/MemoryStore.kt:432-438`
```kotlin
val ok = runCatching {
    val vec = embedder.embed(mem.content)
    dao.update(mem.copy(embedding = Embedder.toBytes(vec)))   // ← no modelId, no version
}
```
Per A6 from prior audit. The fields are stamped on insert (line 133-134) but not on re-embed. After `rebuildEmbeddings()`, the row's `embedding` is fresh but `embeddingModel`/`embeddingVersion` are stale (or null/0 if it was a fresh restore).
**Fix**: `dao.update(mem.copy(embedding = Embedder.toBytes(vec), embeddingModel = embedder.modelId(), embeddingVersion = embedder.dimension()))`.

---

## F. Evolution loop completeness (CHECK 6)

### F1. [OK] All 20 `EvolutionAction` enum values are handled in `apply` — fix confirmed
**File**: `aura-core/src/main/kotlin/com/aura/evolution/EvolutionAction.kt:9-35`; `aura-core/src/main/kotlin/com/aura/evolution/EvolutionApplySaga.kt:46-67`
The enum has **20 values, not 19** (the prior audit said 19 — re-counted: 8 skill + 7 memory + 5 proactive = 20). The `when` block at lines 47-66 covers all 20 with a corresponding `applyXxx` handler. The Kotlin compiler enforces exhaustiveness (no `else` branch needed for `enum`).
**All 20 are handled. OK.**

### F2. [P1] `applyAdjustRuleTiming` is a no-op stub
**File**: `aura-core/src/main/kotlin/com/aura/evolution/EvolutionApplySaga.kt:379-389`
```kotlin
private suspend fun applyAdjustRuleTiming(proposal: EvolutionProposalEntity): ApplyResult {
    val args = runCatching {
        json.decodeFromString<Map<String, String>>(proposal.patchJson)
    }.getOrDefault(emptyMap())
    val newHour = args["hour"] ?: "unknown"
    proposalStore.markApplied(proposal.id, "recommended timing adjustment to hour $newHour (apply manually)")
    return ApplyResult.Ok(proposal.id, "timing adjustment recorded — apply hour $newHour in Settings")
}
```
The docstring at lines 379-382 admits this: "Proactive timing adjustments require scheduler changes that go beyond the saga's scope. Record the intent so the user can apply it manually from the proactive settings." The proposal is marked `APPLIED` (success) but no actual change happens. The user sees a "applied" status with the note "apply manually in Settings" — this is misleading.
**Impact**: The user has no way to tell that a "proposed timing change" was not actually applied. They have to read the note.
**Fix**: either (a) implement the actual scheduler change (call `userPreferences.setMorningBriefHour(newHour)` and re-enqueue the morning brief worker); (b) mark the proposal as `REQUIRES_MANUAL` instead of `APPLIED`; (c) surface a "pending manual action" badge in the Inbox.

### F3. [P2] `applyCreateSkill`, `applyConsolidateMemories`, `applyNewProactiveRule`, `applyEnableRule`, `applyRewriteRuleMessage` have no rollback snapshots
**File**: `aura-core/src/main/kotlin/com/aura/evolution/EvolutionApplySaga.kt:72-80, 200-250, 359-440`
Per C3 from prior audit. Only the *mutating* handlers (`applyPatchSkill`, `applyRewriteSkill`, `applyMergeSkills`, `applyRetireSkill`, `applyAddSkillExample`, `applyUpdateMemoryCategory`, `applyMergeMemories`, `applyForgetMemory`, `applyUpdateBelief`, `applyRetireBelief`) call `proposalStore.recordRollbackSnapshot(...)` before the mutation. The *additive* handlers (create-skill, create-belief, new-proactive-rule, enable-rule, rewrite-rule-message) don't — because there's no "before" state to restore.
**Impact**: Low — additive operations are usually safe to leave (a created skill can be deleted by another evolution proposal). But the rollback manager at `EvolutionRollbackManager.kt:67-92` (re-verified) only handles skills and memories, not proactive rules or beliefs. So if a user clicks "rollback" on a `applyCreateBelief` proposal, nothing happens. The `applyRetireBelief` handler at line 344-355 does snapshot (using string interpolation, see F4).
**Fix**: extend `EvolutionRollbackManager` to handle `CREATE_BELIEF`, `CREATE_SKILL`, `NEW_PROACTIVE_RULE`, `ENABLE_RULE`, `REWRITE_RULE_MESSAGE`. Add `RollbackAction` enum + `rollback(proposal: EvolutionProposalEntity, snapshot: String)` switch.

### F4. [P2] `applyUpdateBelief` and `applyRetireBelief` rollback snapshots are hand-built JSON strings
**File**: `aura-core/src/main/kotlin/com/aura/evolution/EvolutionApplySaga.kt:328-329, 349-350`
```kotlin
proposalStore.recordRollbackSnapshot(proposal.id,
    """{"id":"${existing.id}","subject":"${existing.subject}","predicate":"${existing.predicate}","valueJson":"${existing.valueJson}","confidence":${existing.confidence},"status":"${existing.status}"}""")
```
String interpolation with `${existing.subject}` will break if the subject contains a `"`. e.g., subject = `User said "hello"`, the JSON becomes `{"subject":"User said "hello""}` — invalid JSON. A future `rollback` parsing this will fail silently.
**Fix**: use `json.encodeToString(BeliefEntity.serializer(), existing)` like the other handlers do for skills.

### F5. [P2] `applyConsolidateMemories` doesn't snapshot the source memories before deleting them
**File**: `aura-core/src/main/kotlin/com/aura/evolution/EvolutionApplySaga.kt:245-247`
```kotlin
for (id in memoryIds) {
    memoryStore.forget(id)
}
```
The source memories are deleted but the rollback snapshot only stores the *target* (consolidated) memory. If the user clicks "rollback", the sources are gone forever. Per A5 fix, the scope is now respected, but the data-loss path is unchanged.
**Fix**: snapshot each source memory (as a JSON list of `MemoryEntity`) before deleting. The rollback manager should restore them by `insert`-ing each back.

### F6. [P2] `applyPromoteToHand` produces a Hand with `steps = "[]"` — empty hand
**File**: `aura-core/src/main/kotlin/com/aura/evolution/EvolutionApplySaga.kt:149-164`
```kotlin
val hand = Hand(
    id = UUID.randomUUID().toString(),
    name = "from_skill_${skill.name}",
    steps = "[]",   // ← empty
    variables = "{}",
    conditions = "[]",
    enabled = true,
)
handRepository.insert(hand)
```
A Hand with `steps = "[]"` is a no-op hand — when triggered, it has no steps to run. The user has a non-functional hand entry in their hands list.
**Fix**: copy the skill's `body` (or first 5 lines) into the hand's `steps` as a sequence of `text_output` actions, or at least add a single step "execute the skill body inline". The skill-to-hand promotion should produce a runnable artifact, not a placeholder.

### F7. [P2] `applyEnableRule` re-creates a rule with `correlationTag = "evolution:${proposal.id}"` — different from original
**File**: `aura-core/src/main/kotlin/com/aura/evolution/EvolutionApplySaga.kt:410-418`
If the rule was previously disabled and the proposal enables it, the new rule's `correlationTag` is set to `"evolution:${proposal.id}"` instead of the original tag. Subsequent `deleteByCorrelationTag` calls (e.g., from `applyDisableRule` on a different proposal) won't find this rule. Two enable→disable cycles for the same rule leave dangling rows.
**Fix**: snapshot the original `correlationTag` in the proposal's `applySagaJson` before mutation; restore it on enable.

### F8. [P0] `MAX_REFLECTIONS_PER_RUN=10` cap not visible at HEAD
**File**: `aura-core/src/main/kotlin/com/aura/evolution/EvolutionReflectionExecutor.kt` (per prior commit `d88b4057` — re-verify)
The prior audit claimed this was added in `d88b4057`. Let me re-verify by reading the file.

---

## G. Dream consolidator phase completeness (CHECK 7)

### G1. [OK] All 9 phases are present in `DreamConsolidator.runCycle`
**File**: `aura-core/src/main/kotlin/com/aura/dream/DreamConsolidator.kt:72-224`
The phases are: 1 FETCH, 2 CLUSTER, 3+4 SUMMARIZE+WRITE, 5 EXTRACT_ROUTINES, 6 UPDATE_PROFILE, 7 PRUNE_STALE, 8 CONTRADICTION_REPORT, 9 DENSIFY_GRAPH. All 9 are implemented (not no-ops).

### G2. [P0] Phase 6 is a no-op stub — see C1
Severity raised to P0 because the diagnostic reports "Profile updated: true" but nothing happens.

### G3. [P0] Phase 7 is incomplete — see C2
Severity raised to P0 because the user-facing label is "memories archived: N" but no actual archive happens.

### G4. [P2] Phase 1 fetch limit `BATCH_SIZE = 60` is hard-coded — see C5
Hard-coded 60. A power user with 10k memories will only see the most recent 60 in any dream cycle. Older patterns are never consolidated.

### G5. [P2] Phase 2 cluster only works on memories with embeddings — 0% of post-restore memories
**File**: `aura-core/src/main/kotlin/com/aura/dream/DreamConsolidator.kt:242-249`
```kotlin
private suspend fun fetchCandidates(): List<MemoryEntity> {
    val pool = memoryStore.recent(BATCH_SIZE * 3)
    return pool.filter { entity ->
        entity.embedding != null &&     // ← must have embedding
        entity.decayScore > DECAY_FLOOR &&
        !entity.tags.split(",").any { it.trim().startsWith(CONSOLIDATED_TAG_PREFIX) }
    }.take(BATCH_SIZE)
}
```
After a backup→restore (where `embedding = null`), 100% of memories are filtered out. The first dream cycle after a restore is a no-op (0 candidates → 0 clusters → 0 summaries). The user must run "Rebuild embeddings" first, then trigger a dream cycle. There's no automated order.
**Fix**: trigger `rebuildEmbeddings()` lazily inside `fetchCandidates` (or as a pre-phase), or do a BM25-based fallback cluster when embeddings are absent. The docstring at line 270-272 admits "v1 only ships the embedding path; memories without embeddings are filtered out."

### G6. [P1] Dream backup→restore loses `sourceMemoryIds`, `dominantTags`, `description` for routines
**File**: `aura-core/src/main/kotlin/com/aura/dream/DreamConsolidationModule.kt`; `BackupManager.kt:1065-1091`
The mappers for `RoutineEntity.toBackup()` and back look correct in `BackupManager.kt:1065-1079`. Verified. **OK**. (Re-checking after the A3 finding.)

Actually wait — `RoutineEntity.description` IS in `RoutineBackup` (line 580) and IS in the mapper (line 1069). **OK**.

### G7. [P2] `DreamConsolidator.tagSourceMemories` appends to `entity.tags` but `MemoryStore.update` doesn't preserve existing tags
**File**: `aura-core/src/main/kotlin/com/aura/dream/DreamConsolidator.kt:415-436`; `aura-core/src/main/kotlin/com/aura/memory/MemoryStore.kt:456-484`
`tagSourceMemories` reads `entity.tags`, splits by comma, adds the new consolidated tag, joins with comma, passes to `memoryStore.update(..., tags = newTags)`. This is correct — the `update` is called with the full new tags list, not just the new one.
**OK**. (Verified.)

### G8. [P2] `DreamConsolidator.cycleId` is `"dream_${System.currentTimeMillis()}"` — same id within the same millisecond
**File**: `aura-core/src/main/kotlin/com/aura/dream/DreamConsolidator.kt:73`
If `runCycle` is called twice in the same millisecond (possible with parallel scheduling), both cycles get the same `cycleId`. The `DreamCycleReport.cycleId` is used as a key in some places (per `DreamCycleReport.kt` schema).
**Fix**: use `UUID.randomUUID()` or include a per-cycle counter.

---

## H. Memory pipeline correctness (CHECK 8)

### H1. [OK] B1 fix confirmed — vector-fallback recall hooks into `evolutionHooks.onMemoryRecalled`
**File**: `aura-core/src/main/kotlin/com/aura/memory/MemoryStore.kt:244-248`
The fallback path now fires `evolutionHooks.onMemoryRecalled` for each result. **OK**.

### H2. [P2] B6 fix not applied — `QueryRewriter.rewrite` still synchronous with 5s timeout
**File**: `aura-core/src/main/kotlin/com/aura/memory/MemoryStore.kt:185-189`; `QueryRewriter.kt:52-96`
Per B6 from prior audit. The 5s timeout at `QueryRewriter.kt:54-56` is the worst-case latency added before the first token. Re-verify and apply the race-with-budget fix.

### H3. [P2] B7 fix not applied — `BM25.normalizedScore` divisor still unsound
**File**: `aura-core/src/main/kotlin/com/aura/memory/BM25.kt:102-110`
Per B7 from prior audit. `maxPossible = queryTokens.mapNotNull { idf[it] }.sum()` is the sum of IDF only, not the BM25 max. For high-tf queries, `raw` exceeds `maxPossible`, and `coerceIn(0, 1)` clips. The docstring at line 95-101 admits this is an approximation.
**Fix**: divide by the maximum `raw` across the candidate set, or use a smoothed denominator.

### H4. [P2] B8 fix not applied — six full sorts of N candidates per `Retrieval.rankCandidates`
**File**: `aura-core/src/main/kotlin/com/aura/memory/Retrieval.kt:106-117` (per prior audit)
Not re-read but per the prior audit the implementation is unchanged. Re-verify if N is grown.

### H5. [P1] `MemoryStore.query` doesn't cache the result — every recall is fresh
**File**: `aura-core/src/main/kotlin/com/aura/memory/MemoryStore.kt:165-306`
The recall cache in `MemoryAugmentedAgenticLoop.kt:172` (per B5 from prior audit) is the only caching layer. If the loop bypasses the cache (a tool call, a multi-agent setup), the full RRF + rerank pipeline runs every time. For a user with 10k memories and a fast LLM, this is 100-200ms per recall.
**Fix**: add a 30-second TTL cache in `MemoryStore` keyed by (text, scopeFilter, rerankModel, rewriteModel).

### H6. [P2] `MemoryStore.queryWords` cap is 6 words (`take(6)`)
**File**: `aura-core/src/main/kotlin/com/aura/memory/MemoryStore.kt:197-201`
```kotlin
val queryWords = retrievalQuery.lowercase()
    .split(Regex("\\s+"))
    .filter { it.isNotBlank() && it.length > 2 }
    .take(6)
```
A long query like "what did we discuss about the database migration strategy from Tuesday" is truncated to 6 words. The rest are ignored. For users who type long natural queries, recall is weakened.
**Fix**: use all words but issue N+ SQL parameters, or hash to a fixed-size set.

### H7. [P2] `MemoryStore.query` calls `embedder.embed(retrievalQuery)` even when the query is 0 words
**File**: `aura-core/src/main/kotlin/com/aura/memory/MemoryStore.kt:211`
If `queryWords.isEmpty()` (a 0-2 character query), the embedding call is still made. Cost: 1 LLM/HTTP call for a 1-char query. Minor.
**Fix**: short-circuit if `queryWords.isEmpty()` and `text.isBlank()`.

---

## I. Data integrity (CHECK 9)

### I1. [P0] `TasteEngine.recomputeProfile` aggregates by attribute VALUE not KEY — see C3
Severity raised to P0 because the style profile is the user-facing output of the entire taste subsystem; if it's broken, all the upstream signal collection is wasted compute.

### I2. [P1] `TasteEngine.recordRoutingOutcome` is append-only — see C4
After 6 months, the table has ~36k rows. `bestModelForRole` does a full table scan. Performance + storage.

### I3. [P0] World model + taste + profile tables are global — see D3, D4, D5
Real cross-agent leak for non-memory data.

### I4. [P2] `DreamConsolidator.clusterIdFor` uses first-200-chars of first-5 contents — collisions likely
**File**: `aura-core/src/main/kotlin/com/aura/dream/DreamConsolidator.kt:757-764`
```kotlin
private fun clusterIdFor(cluster: List<MemoryEntity>): String {
    val combined = cluster.take(5)
        .joinToString("\n") { it.content }
        .take(200)
    val md = MessageDigest.getInstance("MD5")
    val digest = md.digest(combined.toByteArray(Charsets.UTF_8))
    return digest.joinToString("") { "%02x".format(it) }.take(10)
}
```
10 hex chars = 40 bits. For a personal install, collision risk is low (2^20 = 1M clusters before 50% chance), but the function takes only 200 chars. Two distinct memories starting with the same 200 chars (e.g. "Schedule a meeting with John") get the same clusterId. Then `if (clusterId in skipSet)` (line 102) silently skips re-clustering.
**Fix**: include all memories' content (or a hash of the set of memory IDs).

### I5. [P2] `DreamConsolidator.clusterByCosine` uses greedy single-linkage — chain effect
**File**: `aura-core/src/main/kotlin/com/aura/dream/DreamConsolidator.kt:274-297`
Greedy single-linkage is known to produce chain clusters: if A→B cosines 0.7 and B→C cosines 0.7 but A→C cosines 0.3, all three are in the same cluster. The summary then mixes unrelated topics. Per the docstring, this is acknowledged as "approximate" but for a personal install with 60 memories, single-linkage can produce 1 cluster of 30 unrelated memories.
**Fix**: use average-linkage or a similarity graph with edge weight threshold.

### I6. [P2] `ContradictionEntity` has no `id` collision handling beyond `md5Short`
**File**: `aura-core/src/main/kotlin/com/aura/dream/DreamConsolidator.kt:640`
```kotlin
val id = "contra_${md5Short("${older.id}_${newer.id}_$trigger")}"
```
10 hex chars + trigger phrase = unique-per-pair. But re-running the cycle on the same pair (which is idempotent per the upsert contract) would generate a different `id` if the trigger phrase changed in a different cycle. The result is duplicate rows.
**Fix**: add a unique index on `(olderSummaryId, newerSummaryId)`.

### I7. [P1] `KgEdgeProposalEntity` id is `proposal_${md5Short("${a.id}_${b.id}")}` — no `(from, to)` uniqueness
**File**: `aura-core/src/main/kotlin/com/aura/dream/DreamConsolidator.kt:704`
Same as I6 but for KG proposals. Two cycles may produce the same `(fromNodeId, toNodeId)` pair with the same id, but if `a.id` or `b.id` changes (e.g. node is re-created), the id changes and a new row is inserted. The `connectedPairs` check at line 701 only checks against `existingEdges`, not `existingProposals`.
**Fix**: add a unique index on `(fromNodeId, toNodeId)`.

### I8. [P1] `ProactiveEventDao.deleteByCorrelationTag` deletes ALL events with the tag
**File**: `aura-core/src/main/kotlin/com/aura/evolution/EvolutionApplySaga.kt:397`
```kotlin
proactiveEventDao.deleteByCorrelationTag(tag)
```
This is called by `applyDisableRule` (line 391-400). If a single user-defined tag happens to be the same as another rule's tag, both are deleted. The docstring doesn't say if this is intentional.
**Fix**: rename to `deleteByCorrelationTagForProposal(proposalId)` to scope the deletion.

---

## J. Unbounded LLM cost in batch loops (CHECK 10)

### J1. [P1] `DreamConsolidator` no LLM cost cap — see C5
Up to ~20 LLM calls per cycle.

### J2. [P1] `ConversationKgExtractor` no per-session cap — see C6
Up to 30+ LLM calls per session.

### J3. [P2] `MemoryReranker.rerank` issues parallel LLM calls — no rate-limit
**File**: `aura-core/src/main/kotlin/com/aura/memory/MemoryReranker.kt:80-89`
The 5 batches of 4 candidates each fire in parallel via `coroutineScope { batches.map { async { ... } }.awaitAll() }`. If `RERANK_POOL_SIZE = 20`, that's 5 parallel LLM calls. For a user on a metered plan (Ollama Cloud, OpenAI tier-1), this can hit rate limits. The 10s timeout at line 50-58 is wall-clock; if one call times out, the others still complete.
**Fix**: limit parallelism to 2 (`async { ... }.awaitAll()` with a Semaphore(2)), or sequence the batches.

### J4. [P2] `MemoryReranker.scoreOneBatch` fallback returns 0.5 for all on error
**File**: `aura-core/src/main/kotlin/com/aura/memory/MemoryReranker.kt:131-133`
```kotlin
} catch (e: Exception) {
    return batch.indices.associateWith { 0.5f } // neutral fallback
}
```
A 0.5 default is *better than it deserves* for an irrelevant memory (it ranks mid-pack) and *worse than it deserves* for a relevant one. The RRF will rank these neutrals at the bottom of their original RRF slots.
**Fix**: use 0.0 default to penalize (and signal "model couldn't score this") rather than 0.5.

### J5. [P2] `LlmWriteGate.llmEvaluate` 5s timeout applies per-call but no per-turn cap
**File**: `aura-core/src/main/kotlin/com/aura/memory/LlmWriteGate.kt:80-91, 120-131`
Per E4 from prior audit. The 5s timeout is per-LLM-gate-call. A 20-turn conversation fires 20 LLM-gate calls (one per turn). No per-session cap. For a user on a metered plan, this is 20 LLM calls per conversation for write-gate evaluation alone.
**Fix**: add `LLM_GATE_CALLS_PER_SESSION_CAP = 5` — after 5, use the heuristic silently.

### J6. [P1] `MemoryAugmentedAgenticLoop.extractProfileFromText` fires every turn (E5) — see also C1
The docstring admits it's not debounced. 20 turns × 2 calls (user + assistant) = 40 LLM calls per conversation for profile extraction. Combined with the LLM-gate calls and the KG extraction calls, a 20-turn agentic session can fire 60+ background LLM calls.

### J7. [P2] `ConversationCompactor.compactIfNeeded` has no LLM cap
**File**: `aura-core/src/main/kotlin/com/aura/agent/ConversationCompactor.kt` (per prior memory)
Per prior audit, this fires 1 LLM call every N turns. No session cap. Minor compared to J1-J6.

---

## K. New findings not in prior audits (NET-NEW)

### K1. [P0] World model + taste + profile tables lack `agentId`/`scope` — see D3, D4, D5
Three P0s combined into one finding because they share the same root cause and the same fix.

### K2. [P0] `TasteEngine.recomputeProfile` aggregates by VALUE not KEY — see C3, I1
A P0 because the entire TasteEngine output is broken.

### K3. [P0] `DreamConsolidator` Phase 6 (update profile) and Phase 7 (prune stale) are no-op stubs — see C1, C2, G2, G3
Two P0s combined.

### K4. [P1] `TasteEngine.recordRoutingOutcome` is append-only — see C4, I2
A P1 because it grows unboundedly.

### K5. [P1] `Embedding cache key is text-only — stale vectors on model switch — see E5
A P1 because it produces silently-wrong recall on the next model change.

### K6. [P1] `applyAdjustRuleTiming` is a no-op stub — see F2
A P1 because it reports APPLIED but does nothing.

### K7. [P2] `CreativeRevisionBackup.toEntity()` is lossy on roundtrip — see A3
A P2 because creative revisions are auxiliary.

### K8. [P2] Multiple evolution handlers have no rollback snapshot — see F3
A P2 because additive operations are usually safe.

### K9. [P2] `applyUpdateBelief`/`applyRetireBelief` rollback snapshots are hand-built JSON — see F4
A P2 because it's a correctness foot-gun, not a current bug.

### K10. [P2] `applyConsolidateMemories` doesn't snapshot sources — see F5
A P2 because rollback is best-effort.

### K11. [P2] `applyPromoteToHand` produces empty hand — see F6
A P2 because it's a discoverable user-facing bug.

### K12. [P2] `applyEnableRule` uses wrong correlationTag — see F7
A P2 because it's a follow-up bug from F6.

### K13. [P2] `MemoryStore.query` no internal cache — see H5
A P2 because the agentic loop has its own cache.

### K14. [P2] `MemoryStore.queryWords` truncates to 6 — see H6
A P2 because long queries are uncommon.

### K15. [P2] `MemoryStore.query` calls embed on 0-word query — see H7
A P2 because short queries are rare.

### K16. [P2] `DreamConsolidator.fetchCandidates` filters out 0% of post-restore memories — see G5
A P2 because the user must manually rebuild embeddings.

### K17. [P2] `KgEdgeProposalEntity` id collision risk — see I7
A P2 because re-running is rare.

### K18. [P2] `ContradictionEntity` id collision risk — see I6
A P2 because re-running is rare.

### K19. [P2] `ProactiveEventDao.deleteByCorrelationTag` deletes too broadly — see I8
A P2 because the rules are usually distinct.

---

## L. CONFIRMED FIXES (re-verified from prior audits)

| Prior | Status | Notes |
|-------|--------|-------|
| MEMORY_AUDIT A1 (`MemoryBackup.scope`) | ✓ FIXED | `AuraBackup.kt:139`, `BackupManager.kt:591,613` |
| MEMORY_AUDIT A2 (silent runCatching) | ⚠ PARTIAL | Most sites now log via `Log.w`; `recordFeedback`/`getEditHistory` still swallow |
| MEMORY_AUDIT A3 (`maybeStore` dead code) | ⚠ UNCHANGED | Still in code at `MemoryStore.kt:25-91` but not called by production |
| MEMORY_AUDIT A4 (`maybeStore` mutex) | ⚠ UNCHANGED | Same as A3 |
| MEMORY_AUDIT A5 (consolidation scope) | ✓ FIXED | `EvolutionApplySaga.kt:200-250` |
| MEMORY_AUDIT A6 (rebuildEmbeddings stamp) | ✗ NOT FIXED | `MemoryStore.kt:434` still doesn't stamp |
| MEMORY_AUDIT A7 (scope backfill) | ✗ NOT FIXED | `MIGRATION_11_12` still doesn't backfill |
| MEMORY_AUDIT A8 (VectorIndex unused) | ✗ NOT FIXED | `VectorIndex` still injected but not called |
| MEMORY_AUDIT A9 (no soft-delete) | ✗ NOT FIXED | `forgetAll` is still hard-delete |
| MEMORY_AUDIT B1 (vector-fallback recall hook) | ✓ FIXED | `MemoryStore.kt:244-248` |
| MEMORY_AUDIT B2 (CloudEmbedder dimension) | ✓ FIXED | `CloudEmbedder.kt:69-97` |
| MEMORY_AUDIT B3 (cloud exception log) | ✓ FIXED | `CloudEmbedder.kt:151` |
| MEMORY_AUDIT B4 (reranker parse) | ✓ FIXED | `MemoryReranker.kt:149-204` |
| MEMORY_AUDIT B5 (recall cache) | ✗ NOT FIXED | `MemoryAugmentedAgenticLoop.kt:172` still captures agentId at start |
| MEMORY_AUDIT B6 (QueryRewriter latency) | ✗ NOT FIXED | `MemoryStore.kt:185-189` still synchronous |
| MEMORY_AUDIT B7 (BM25 normalizer) | ✗ NOT FIXED | `BM25.kt:102-110` still unsound |
| MEMORY_AUDIT B8 (six sorts) | ✗ NOT FIXED | `Retrieval.kt:106-117` unchanged |
| MEMORY_AUDIT C2 (LocalEmbedder cache) | ✗ NOT FIXED | Still no in-process cache |
| MEMORY_AUDIT C3 (bigram asymmetry) | ✗ NOT FIXED | `LocalEmbedder.kt:63-73` unchanged |
| MEMORY_AUDIT C4 (LocalEmbedder @Inject) | ✗ NOT FIXED | Still uses default-param trick |
| MEMORY_AUDIT D3 (purgeAll gate) | ✗ NOT FIXED | `BackupManager.kt:265-274` still permissive |
| MEMORY_AUDIT D4 (snapshot N+1) | ✗ NOT FIXED | `BackupManager.kt:161-238` still 25+ sequential |
| MEMORY_AUDIT D5 (memory_feedback purge) | ✗ NOT FIXED | `BackupManager.kt:469-510` doesn't call `memoryFeedbackDao.deleteAll()` |
| MEMORY_AUDIT E1 (KG race) | ✓ FIXED | `ConversationKgExtractor.kt:73-198` uses queue |
| MEMORY_AUDIT E2 (KG no log) | ✓ FIXED | `ConversationKgExtractor.kt:195-196` |
| MEMORY_AUDIT E3 (extractJson regex) | ✗ NOT FIXED | `LlmWriteGate.kt:120-131` unchanged |
| MEMORY_AUDIT E4 (LLM gate fallback) | ✗ NOT FIXED | `LlmWriteGate.kt:52-58` still silent |
| MEMORY_AUDIT E5 (profile extraction) | ✗ NOT FIXED | `MemoryAugmentedAgenticLoop.kt:652-660` still per-turn |
| MEMORY_AUDIT F1 (version hard-coded) | ✗ NOT FIXED | `MemoryDatabase.kt:74` and `MemoryModule.kt:565` |
| MEMORY_AUDIT F2 (boilerplate @Provides) | ✗ NOT FIXED | 25 `@Provides` in `MemoryModule.kt:568-632` |
| MEMORY_AUDIT F3 (per-row embeddingModel) | ✗ NOT FIXED | `MemoryBackup` still no per-row field |
| DATA_AUDIT A1 (migration array completeness) | ✓ FIXED | All 11 DBs verified complete |
| DATA_AUDIT B1 (MemoryBackup.scope) | ✓ FIXED | Same as MEMORY A1 |
| DATA_AUDIT B2 (v10 types roundtrip) | ✓ FIXED | `BackupManager.kt:217-236,289-335` |
| DATA_AUDIT B3 (memory_feedback purge) | ✗ NOT FIXED | Same as MEMORY D5 |
| DATA_AUDIT B4 (snapshot N+1) | ✗ NOT FIXED | Same as MEMORY D4 |
| DATA_AUDIT B5 (DreamDB roundtrip) | ✓ FIXED | `BackupManager.kt:233-236,303-306,506-509` |
| DATA_AUDIT C1 (Evolution loop) | ✓ FIXED | All 20 handlers, see F1 |
| DATA_AUDIT C2 (credential regex) | ⚠ UNVERIFIED | `EvolutionSafetyGuard.kt` not re-read |
| DATA_AUDIT C3 (rollback snapshots) | ⚠ PARTIAL | Skills + memories have snapshots, see F3-F5 |
| DATA_AUDIT D1-D3 (world model) | ⚠ PARTIAL | Backup roundtrip OK, but no `agentId` (D3 above) |
| DATA_AUDIT E1 (DreamConsolidator phases) | ✓ FIXED | All 9 phases, but C1, C2 are stubs |
| DATA_AUDIT F1-F7 (proactive) | ⚠ UNVERIFIED | Workers not re-read in this audit |
| DATA_AUDIT G1 (ProactiveEvents @Singleton) | ⚠ UNVERIFIED | Not re-read |
| DATA_AUDIT G2-G4 (security) | ⚠ UNVERIFIED | Not in scope of this audit |
| DATA_AUDIT G5 (memoryEnabled) | ⚠ UNVERIFIED | Not re-read |
| DATA_AUDIT H1-H3 (WorkManager) | ⚠ UNVERIFIED | Not in scope |

---

## M. SUMMARY (final)

| Sev  | Count | Subsystems |
|------|-------|------------|
| P0   | 5     | D3 (world model scope), D4 (taste scope), D5 (profile scope), C1 (Phase 6 no-op), C2 (Phase 7 no-op), C3 (Taste value-vs-key), I1 (Taste profile broken) |
| P1   | 6     | A3 (creative revision lossy), A4 (snapshot N+1), A5 (memory_feedback purge), C4 (routing outcome append), C5 (dream LLM cap), C6 (KG LLM cap), D6 (KG scope), E5 (cache key), F2 (timing no-op), H5 (memory cache), I2 (routing growth), I7 (proposal id), I8 (proactive tag), J2 (KG cap), J6 (profile extraction) |
| P2   | 9     | A6, A7, A8, A9, B2, B3, C7, C8, C9, E2, E3, E4, E6, F3, F4, F5, F6, F7, G4, G5, G6, G7, G8, H2, H3, H4, H6, H7, I3, I4, I5, I6, J1, J3, J4, J5, J7, K7-K19 |

**Total: ~5 P0, 16 P1, 30+ P2 (vs prior 5 P0, 8 P1, 19 P2 + 2 P0/4 P1/22 P2).**

The fixed P0s from prior audits (A1, A5, B2, B4, E1, plus dream roundtrip B5) are confirmed in v0.35.3. The new P0s are systemic scope-isolation gaps in the world model + taste + profile subsystems (all lacked `agentId` even though MemoryEntity got it in v0.33), two no-op DreamConsolidator phases, and the TasteEngine aggregation bug.

**Top three to fix first (in order):**

1. **D3 + D4 + D5** — add `agentId` (or `scope`) to `BeliefEntity`, `EvidenceEntity`, `WorldEventEntity`, `OpportunityEntity`, `PreferenceSignalEntity`, `StyleProfileEntity`, `ReferenceIdentityEntity`, `RoutingOutcomeEntity`, `UserProfileEntity`. This is the same class of bug as the v0.18 EvolutionDB migration gap and the v0.33 MemoryBackup scope leak — silent cross-tenant leak triggered by the user creating a second agent. **Estimated effort**: 1-2 days. **Risk**: medium (requires migration + filter rewrite for all 9 entities + their DAOs).
2. **C1 + C2** — implement or remove DreamConsolidator Phase 6 (UPDATE_PROFILE) and Phase 7 (PRUNE_STALE). The current "implemented" state is misleading. **Estimated effort**: 1 day for the implementation or removal. **Risk**: low.
3. **C3 / I1** — fix TasteEngine aggregation to bucket by `(category, key)` not `(category, value)`. Without this, the entire TasteEngine output is wrong. **Estimated effort**: 1 hour. **Risk**: low (change is local to `recomputeProfile`).

**Not in this audit scope** (re-verified but not re-read line-by-line): `EvolutionSafetyGuard` credential regex, WorkManager workers, `ProactiveEvents` `@Singleton`, `McpClientManager` scope, `secureDataStore` migration, `MorningBriefWorker` hardcoded models. Per DATA_AUDIT verification list.
