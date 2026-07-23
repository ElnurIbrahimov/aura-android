# DreamConsolidator Port — v1 (Phase 1 + Phase 2)

**Target:** Aura Android v0.31.0
**Source:** `D:\Aura\aura\dream.py` (DreamConsolidator class, ~700 lines)
**Goal:** port the 6-phase consolidation pipeline to Kotlin, wire to WorkManager, surface in UI.
**Scope:** Phase 1 (cluster) + Phase 2 (summarize) only. Phases 3-6 deferred.
**Estimated effort:** 1-2 days.

## Why this matters

Every chat session creates 5-30 memories. After 100 conversations, the
`memories` table has 500-3000 rows. **80% of those rows are paraphrases**
of the same handful of facts ("user is a developer", "user prefers dark
mode", "user lives in Baku"). The retrieval pipeline is SOTA (BM25 + RRF
+ cross-encoder reranker), but the **storage is unbounded** — retrieval
quality degrades over time because the candidate set is bloated with
duplicates.

Python Aura's `DreamConsolidator` is the fix. It runs during sleep/charging,
clusters memories by embedding similarity, asks an LLM to summarize each
cluster, and writes the summary back as a *new* memory that points to the
cluster members. Future retrieval hits the summary instead of 12 paraphrases
of the same fact.

## What's ported in v1

| Phase | Python behavior | Android v1 |
|---|---|---|
| 1. CLUSTER | Single-linkage on embedding cosine, threshold 0.65 (embeddings) / 0.35 (Jaccard) | Same — reuses `MemoryEntity.embedding` (ByteArray → FloatArray) |
| 2. SUMMARIZE | LLM compresses cluster into 2-3 sentences, 500-char max, dominant tag extraction | Same — calls `ProviderRegistry.chat()` with cheap model |
| 3. EXTRACT_ROUTINES | Detect repeated behavioral patterns across clusters | **Deferred** (needs stable cluster sizes over weeks) |
| 4. PRUNE | Mark strength < 0.05 as 'forgotten' | **Already implemented** as `MemoryStore.runDecayPass()` (cycle 1) — no work needed |
| 5. CONTRADICTION_REPORT | Surface unresolved KG contradictions | **Deferred** (needs KG extractor wiring) |
| 6. DENSIFY_GRAPH | Propose new KG edges | **Deferred** (experimental in Python, off by default) |

## Architecture

```
aura-core/main/.../dream/
├── DreamConsolidator.kt        # the pipeline (port of Python class)
├── DreamSummary.kt             # data class for a summarized cluster
├── DreamCycleReport.kt         # per-cycle stats (counts, durations)
├── DreamConsolidationDao.kt    # Room: tracks summaries + source links
├── DreamSummaryEntity.kt       # the table
├── DreamConsolidationDb.kt     # separate Room database
├── DreamConsolidationModule.kt # Hilt @Module
└── DreamWorker.kt              # WorkManager periodic + one-shot

aura-core/main/.../proactive/
└── ProactiveScheduler.kt       # +scheduleDream / cancelDream

aura-core/main/.../data/
└── UserPreferences.kt          # +dreamEnabled, +dreamLastRunAt, +dreamLastRunStats

aura-core/main/.../proactive/
└── ProactiveBootstrap.kt       # +reconcileDream (gated on UserPreferences.dreamEnabled)

app/main/.../ui/settings/sections/
└── DreamConsolidationSection.kt  # Settings → Memory → "Dream last ran: X ago" + toggle

app/main/.../ui/screens/
└── MemoryScreen.kt             # + "X summaries" stat row
```

## Detailed design

### `DreamSummaryEntity` (Room)

```kotlin
@Entity(
    tableName = "dream_summaries",
    indices = [Index("createdAt"), Index("clusterId", unique = true)]
)
data class DreamSummaryEntity(
    @PrimaryKey val id: String,                // "dream_<clusterId>"
    val clusterId: String,                     // MD5 of joined content
    val compressedText: String,                // 2-3 sentences
    val sourceMemoryIds: String,               // comma-separated memory IDs
    val dominantTags: String,                  // comma-separated
    val sourceCount: Int,                      // number of memories in cluster
    val modelUsed: String,                     // which LLM summarized
    val createdAt: Long = System.currentTimeMillis(),
)
```

Why a separate table: dream summaries are different lifecycle from regular
memories. They're never pruned by `runDecayPass` (they're "structural"
records, not user facts). They show up in the Memory screen with a
distinct badge so the user understands "this is a consolidation, not a
fact you said."

### `DreamConsolidator.runCycle()` algorithm

```
1. FETCH: load recent N memories (default N=60, capped by batchSize)
   - filter: decayScore > 0.05 (skip already-forgotten)
   - filter: NOT source like 'dream:%' (don't re-consolidate summaries)
   - order: createdAt DESC (most recent first)

2. CLUSTER: single-linkage on embedding cosine
   - threshold: 0.65 (use the actual embedding bytes from Room)
   - if no embedding bytes for a memory, skip it (or use BM25 fallback in v2)
   - greedy: visit each memory, find an existing cluster whose first
     member has cosine > threshold, else create new cluster
   - skip clusters with size < minClusterSize (default 3)
   - skip clusters where all members are already in a summary (use a
     Set<String> "seen cluster members" cache; clear on each runCycle)

3. SUMMARIZE: for each cluster, call cheap LLM
   - model resolution: prefer same model user has set, fall back to
     first non-MoA provider, fall back to "default"
   - prompt: "Compress the following N related memory entries into a
     single concise summary (2-3 sentences, max 500 chars). Preserve
     key facts and preferences. Entries: <joined content, cap 3000 chars>"
   - on LLM error: use first memory's first 300 chars as fallback
   - result: DreamSummary(clusterId, sourceMemoryIds, compressedText,
     dominantTags[5], sourceCount)

4. WRITE: persist DreamSummaryEntity
   - if clusterId already exists, UPDATE (idempotent — same cluster
     re-running shouldn't double-write)
   - the source memories are NOT deleted (consolidation is non-destructive
     in v1; we just write a higher-quality memory alongside)
   - tag the source memories with "consolidated:dream_<clusterId>" in
     their existing `tags` field so future cycles skip them

5. REPORT: return DreamCycleReport
   - memoriesProcessed, clustersFormed, summariesWritten, totalCharsSaved
   - durationMs
   - this is persisted to the dream_summaries table itself
     (DreamCycleReportEntity is just a JSON blob)
```

### `DreamWorker` (WorkManager)

Two entry points, both backed by the same `runCycle()`:

```kotlin
@HiltWorker
class DreamWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val consolidator: DreamConsolidator,
    private val userPreferences: UserPreferences,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = runNow()

    suspend fun runNow(): Result = try {
        if (!userPreferences.dreamEnabled.first()) {
            return Result.success()  // gated off
        }
        val report = consolidator.runCycle()
        userPreferences.recordDreamRun(report)
        Result.success(workDataOf("summariesWritten" to report.summariesWritten))
    } catch (e: CancellationException) { throw e }
    catch (e: Exception) {
        Log.w("DreamWorker", "dream cycle failed: ${e.message}")
        Result.retry()
    }

    companion object {
        const val UNIQUE_NAME = "dream-consolidation-periodic"
    }
}
```

### `ProactiveScheduler.scheduleDream()`

```kotlin
fun scheduleDream() {
    val constraints = Constraints.Builder()
        .setRequiresBatteryNotLow(true)     // don't kill battery
        .setRequiresCharging(true)          // "sleep/charging" semantic
        .build()
    val request = PeriodicWorkRequestBuilder<DreamWorker>(1, TimeUnit.DAYS)
        .setConstraints(constraints)
        .setInitialDelay(2, TimeUnit.HOURS)  // give app time to settle after install
        .addTag("dream-consolidation")
        .build()
    WorkManager.getInstance(context)
        .enqueueUniquePeriodicWork(
            DreamWorker.UNIQUE_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
}
```

Note: WorkManager's minimum periodic interval is 15 minutes; we use
**1 day** (matches Python's daily cron). Charging constraint is the
"sleep" semantic — phone is plugged in overnight, do the work then.

### `ProactiveBootstrap.reconcileDream()`

Add to the existing combine() flow (currently 5 sources; we'd make it 6):

```kotlin
combine(
    userPreferences.morningBriefEnabled,
    userPreferences.calendarMonitorEnabled,
    userPreferences.morningBriefHour,
    userPreferences.evolutionEnabled,
    userPreferences.evolutionIntervalHours,
    userPreferences.dreamEnabled,  // NEW
) { mb, cm, bh, ev, evInt, dm ->
    ProactiveGates(mb, cm, bh, ev, evInt, dm)
}.collect { reconcile(it) }
```

ProactiveGates gets a new `dreamOn: Boolean` field. `reconcile()` calls
`scheduler.scheduleDream()` or `scheduler.cancelDream()`.

### `UserPreferences` additions

```kotlin
/** Whether the dream consolidator is enabled. Default true (opt-out). */
val dreamEnabled: Flow<Boolean> = context.auraPrefs.data.map { prefs ->
    prefs[KEY_DREAM_ENABLED] ?: true
}

/** Timestamp of the last successful dream cycle, 0 = never. */
val dreamLastRunAt: Flow<Long> = context.auraPrefs.data.map { prefs ->
    prefs[KEY_DREAM_LAST_RUN_AT] ?: 0L
}

/** Stats from the last run: "12 clusters, 4 summaries, 1.2k chars saved". */
val dreamLastRunStats: Flow<String> = context.auraPrefs.data.map { prefs ->
    prefs[KEY_DREAM_LAST_RUN_STATS] ?: ""
}

suspend fun recordDreamRun(report: DreamCycleReport) {
    context.auraPrefs.edit { prefs ->
        prefs[KEY_DREAM_LAST_RUN_AT] = System.currentTimeMillis()
        prefs[KEY_DREAM_LAST_RUN_STATS] = "${report.summariesWritten} summaries, " +
            "${report.clustersFormed} clusters, ${report.totalCharsSaved} chars saved"
    }
}
```

### Settings UI: `DreamConsolidationSection.kt`

```kotlin
@Composable
fun DreamConsolidationSection(
    enabled: Boolean,
    lastRunAt: Long,
    lastRunStats: String,
    totalSummaries: Int,
    onSetEnabled: (Boolean) -> Unit,
    onRunNow: () -> Unit,
) {
    SettingsSection(title = "Memory consolidation (Dream)") {
        // Toggle (default on)
        // "Last ran: 2 days ago" or "Never" with relative time
        // "X total summaries" stat
        // "Run now" button (kicks off a one-shot WorkRequest)
        // Description: "While phone is charging, clusters and summarizes memories..."
    }
}
```

Wired into SettingsViewModel:
- expose `dreamEnabled`, `dreamLastRunAt`, `dreamLastRunStats` from
  `userPreferences.*` flows
- expose `dreamSummaryCount` from `dreamConsolidationDao.count()`
- add `setDreamEnabled(b: Boolean)` calling `userPreferences.setDreamEnabled(b)`
- add `runDreamNow()` enqueueing `OneTimeWorkRequestBuilder<DreamWorker>()`

### `MemoryScreen` stat row

Add a "X dream summaries" line at the top of the memory list, below the
"X memories" count. Tappable — shows the summaries in a dialog. This is
the user-visible signal that the consolidator is doing its job.

## File-by-file change list

### New files (10)

| File | LOC est | Purpose |
|---|---|---|
| `aura-core/main/.../dream/DreamSummaryEntity.kt` | 50 | Room entity |
| `aura-core/main/.../dream/DreamConsolidationDao.kt` | 50 | DAO |
| `aura-core/main/.../dream/DreamConsolidationDatabase.kt` | 35 | Room database |
| `aura-core/main/.../dream/DreamConsolidationModule.kt` | 25 | Hilt module |
| `aura-core/main/.../dream/DreamSummary.kt` | 30 | data class |
| `aura-core/main/.../dream/DreamCycleReport.kt` | 25 | data class |
| `aura-core/main/.../dream/DreamConsolidator.kt` | 350 | the pipeline |
| `aura-core/main/.../dream/DreamWorker.kt` | 60 | WorkManager wrapper |
| `app/main/.../ui/settings/sections/DreamConsolidationSection.kt` | 130 | Settings UI |
| `aura-core/src/test/.../dream/DreamConsolidatorTest.kt` | 200 | unit tests |

**Total new: ~955 LOC** (including 200 LOC tests)

### Modified files (5)

| File | Change | LOC est |
|---|---|---|
| `aura-core/main/.../data/UserPreferences.kt` | +3 Flow prefs, +1 setter, +1 recordDreamRun | +35 |
| `aura-core/main/.../proactive/ProactiveScheduler.kt` | +scheduleDream, +cancelDream | +30 |
| `aura-core/main/.../proactive/ProactiveBootstrap.kt` | +reconcileDream, ProactiveGates field | +20 |
| `app/main/.../ui/settings/SettingsViewModel.kt` | +dream state, +setter, +runNow | +40 |
| `app/main/.../ui/screens/MemoryScreen.kt` | +summaries count row, +summary dialog | +60 |

**Total modified: +185 LOC**

### Net total: ~1,140 LOC across 15 files

## Step-by-step execution

### Step 1: Database + Hilt module (45min)
- Create `DreamSummaryEntity`, `DreamConsolidationDao`, `DreamConsolidationDatabase`, `DreamConsolidationModule`
- Test: Hilt can inject `DreamConsolidationDao`

### Step 2: DreamSummary + DreamCycleReport data classes (15min)
- Pure data, no logic
- Test: compile-only

### Step 3: DreamConsolidator.runCycle() — Phase 1 only (cluster) (1h)
- FETCH: query MemoryDao.recent(batchSize * 3) — reuse existing query
- CLUSTER: implement greedy single-linkage on FloatArray embeddings
  - re-use `MemoryStore.cosineSimilarity()` (already exists)
  - threshold: 0.65 (matches Python)
- Test: 3-unit test on clustering (small input, threshold edge, no embeddings)

### Step 4: DreamConsolidator.runCycle() — Phase 2 (summarize) (1h)
- SUMMARIZE: call ProviderRegistry.chat() with cheap-model fallback
  - re-use the same fallback pattern as `ConversationCompactor` (MoA → first non-MoA)
- WRITE: upsert DreamSummaryEntity, tag source memories
- REPORT: build DreamCycleReport
- Test: 3-unit tests (LLM success, LLM error fallback, idempotent re-run)

### Step 5: UserPreferences + ProactiveScheduler + ProactiveBootstrap wiring (45min)
- Add 3 UserPreferences flows + setter
- Add scheduleDream/cancelDream
- Add reconcileDream + ProactiveGates.dreamOn

### Step 6: DreamWorker (30min)
- 2 entry points (periodic + one-shot)
- Pattern from DecayWorker.kt

### Step 7: Settings UI (45min)
- `DreamConsolidationSection.kt` Composable
- `SettingsViewModel` wiring
- Hook into SettingsScreen router

### Step 8: MemoryScreen stat row (30min)
- Add summaries count to MemoryViewModel
- Render row + dialog

### Step 9: Tests + verification (45min)
- `DreamConsolidatorTest` (Phase 1 + Phase 2)
- Run full test suite, build APK, lint
- Update README + add `RELEASE_NOTES_v0.31.0.md`

### Step 10: Commit + release (20min)
- 5-6 atomic commits (one per logical layer)
- Push, `gh release create v0.31.0`

**Total time: ~7 hours of focused work**

## Risks and mitigations

| Risk | Mitigation |
|---|---|
| `MemoryEntity.embedding` may be null for older memories | Filter out nulls; cluster only memories with embeddings. v2 can fall back to BM25. |
| Cheap-model fallback in `ConversationCompactor` uses `providerRegistry.configured()` | Same pattern works for dream. Already validated in cycle 1. |
| 60 memories * 384-dim embedding = ~92KB cosine matrix | OK on modern phones. If too slow, batch to 20 at a time. |
| LLM call could fail (network, rate limit) | Fall back to first memory's first 300 chars (matches Python). Log to CrashLogger. |
| Multiple dream workers run concurrently | Use `enqueueUniquePeriodicWork` with `UPDATE` policy (WorkManager's dedup). Already used by DecayWorker. |
| Tagging source memories with `consolidated:dream_<id>` could grow unbounded | Cap to last 5 consolidation tags per memory (strip oldest on insert). |
| Test failure due to `android.util.Log` in pure JVM tests | Wrap in `try { Log.w(...) } catch (_: RuntimeException) {}` (DecayWorker pattern). |

## What I am NOT doing in v1

- Phase 3 (routines) — needs weeks of data to be useful
- Phase 4 (prune) — already done as `runDecayPass()`
- Phase 5 (contradictions) — needs KG extractor wiring
- Phase 6 (graph densify) — experimental in Python, off by default
- Async lazy dreaming (after each conversation closes) — Python does this
  but Android should keep it daily+charging for v1 to bound API cost
- Per-conversation scope filtering — v2 if users want "dream only project X"
- Cloud-side dream (offload to API) — over-engineering for personal use

## Success criteria

- v0.31.0 release shipped with `DreamConsolidatorSection` in Settings
- "Run now" button on a fresh install triggers a cycle and writes summaries
- After 1 night of charging, `dreamLastRunAt > 0` and `totalSummaries >= 1`
  on a test install with 60+ memories
- All 1,157 existing tests still pass
- 3 new DreamConsolidatorTest tests pass
- Lint clean, assembleDebug green

## Verification approach

1. Run `./gradlew :aura-core:testDebugUnitTest` — all green
2. Run `./gradlew :app:assembleDebug :app:lintDebug` — green
3. Manual: install APK, navigate Settings → Memory, see new section
4. Manual: tap "Run now", verify `dreamLastRunAt` updates within 5 seconds
5. Manual: place 30+ memories via paste-into-chat, tap Run now, verify
   "1-3 dream summaries" appears in Memory screen
