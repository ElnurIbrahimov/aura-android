# Memory/Data Subsystem Audit — 2026-07-26

**Project:** Aura Android (Kotlin/Compose)
**HEAD:** 40f5ca68 (after pass 2 of 7-25 engineering review)
**Scope:** memory subsystem, backup/restore, Room DBs, DreamConsolidator, TasteEngine, world model, knowledge graph, proactive data
**Method:** Static audit of the 4 highest-risk subsystems with file:line evidence and severity. Skips issues already fixed in the 7-18 or 7-25 review passes.

---

## 1. Confirmed bugs (not yet fixed)

### [P1] `BackupManager.restore()` writes 4 entity groups but never reports counts for them

**File:** `aura-core/src/main/kotlin/com/aura/backup/BackupManager.kt`
**Lines:** 333–336 (writes), 393–422 (RestoreCounts construction)
**Evidence:**

```kotlin
// Line 333-336 — rows ARE written
if (dreamSummaryRows.isNotEmpty()) dreamSummaryDao?.insertAll(dreamSummaryRows)
if (routineRows.isNotEmpty()) routineDao?.insertAll(routineRows)
if (contradictionRows.isNotEmpty()) contradictionDao?.insertAll(contradictionRows)
if (kgEdgeProposalRows.isNotEmpty()) kgEdgeProposalDao?.insertAll(kgEdgeProposalRows)

// Line 393-422 — RestoreCounts returned to caller. MISSING:
//   dreamSummaries, routines, contradictions, kgEdgeProposals
RestoreCounts(
    memories = memRows.size, ...
    styleProfiles = styleProfileRows.size,
    // 4 fields from the data class are NEVER assigned.
)
```

The `RestoreCounts` data class declares all 4 fields (line 559–562), and the `total` getter sums them (line 578), so `total` is correct, but every caller that reads `restoreCounts.dreamSummaries` etc. gets `0` (the default). The UI toast and any audit log will silently mis-report the restore. This is a regression introduced when schema v11 DAOs were added — the constructor was extended but the call-site was forgotten.

**Fix:** add `dreamSummaries = dreamSummaryRows.size, routines = routineRows.size, contradictions = contradictionRows.size, kgEdgeProposals = kgEdgeProposalRows.size` to the RestoreCounts construction at line 422.

---

### [P1] `TasteEngine.recomputeProfile` normalizes by signed `totalWeight` — flips signal signs for all-negative categories

**File:** `aura-core/src/main/kotlin/com/aura/taste/TasteEngine.kt`
**Lines:** 156–158
**Evidence:**

```kotlin
val totalWeight = categorySignals.sumOf { it.weight.toDouble() }.toFloat().coerceAtLeast(1f)
attrs.forEach { (k, v) -> attrs[k] = v / totalWeight }
```

Signals can have negative weights (`recordEdit` uses `-0.5f`; negative reactions use `-1.0f`). If every signal in a category is negative (e.g., 3 edits of weight -0.5 each), `totalWeight = -1.5f`. The division flips signs: a "dislike" bucket becomes a positive weight, the profile shows preferences that the user explicitly rejected.

`coerceAtLeast(1f)` only masks the case where total is `> -1f`. Anything ≤ -1 (e.g., -1.5, -3.0) goes through unchanged. Reproduce by adding 3 records of the same negative signal, recompute, observe flipped sign.

**Fix:** use `abs(totalWeight).coerceAtLeast(1f)` for normalization, or normalize by signal count `categorySignals.size.coerceAtLeast(1)` (matches the comment "divide by total signal count").

---

### [P1] `DreamConsolidator.phase7.pruneStale` adds a "pruned:dream" tag but never sets `decayScore` to 0 — the archive is fictional

**File:** `aura-core/src/main/kotlin/com/aura/dream/DreamConsolidator.kt`
**Lines:** 570–596
**Evidence:**

```kotlin
internal suspend fun pruneStale(): Int {
    ...
    if (entity.decayScore <= 0f) continue
    runCatching {
        memoryStore.update(
            id = entity.id, ...
            tags = entity.tags + ... + "pruned:dream",
        )
        // Comment: "Setting decayScore to 0 via the update path is not
        // exposed by MemoryStore.update; instead we mark via tag. The
        // FadeMem pass will handle the rest on the next decay cycle."
        archived++
    }
}
```

The comment admits the truth: `decayScore` is NOT set to 0. The function adds a tag and increments `archived` in the cycle report, but `FadeMem` uses `decayScore`, not tags. Result: `DreamCycleReport.memoriesArchived` reports N archived per cycle, but in reality zero are actually pruned from retrieval. The UI / telemetry will show "100 memories archived" while nothing changed.

The fix requires either (a) extending `MemoryStore.update` to accept a `decayScore` parameter, or (b) a new DAO method to set `decayScore = 0` directly. Either way the report counter is currently lying.

---

### [P1] `BackupManager.restore()` does not reset `usage` totals — old usage from previous install leaks into restored usage

**File:** `aura-core/src/main/kotlin/com/aura/backup/BackupManager.kt`
**Line:** 386
**Evidence:**

```kotlin
usageTracker.restore(backup.usage)
```

`usageTracker.restore()` is called with the backup's usage. Need to verify whether it overwrites or merges. The bigger problem: `snapshot()` reads `usageTracker.snapshot.value` (line 215), and the `usage` in the backup is a snapshot of the in-memory state at the time of export. If `usage` is never reset on import and the in-memory tracker was already populated (e.g., the user made some API calls between install and restore), the `restore()` behavior needs to be checked.

**Status:** needs verification — see Verification section below. If `usageTracker.restore()` is additive, this is a data corruption risk (double-counting); if it overwrites, no bug. Marking as P1 until confirmed.

---

### [P2] `BackupManager.snapshot()` does not serialize `usage` to/from a non-volatile store — lost on every cold start

**File:** `aura-core/src/main/kotlin/com/aura/backup/BackupManager.kt`
**Line:** 215
**Evidence:**

```kotlin
usage = usageTracker.snapshot.value,
```

`usageTracker.snapshot.value` reads from an in-memory StateFlow. If the user has not yet opened Settings (which is the only place the tracker persists), `usage` will be empty. The backed-up usage may be a subset of what the user actually consumed. Lower-priority because usage is non-critical; flagging for completeness.

---

### [P2] `MemoryDatabase` exports schema versions 1–6, 11–13 — schemas 7, 8, 9, 10 are missing from `aura-core/schemas/`

**File:** `aura-core/src/main/kotlin/com/aura/memory/MemoryDatabase.kt`
**Lines:** 74 (version = 13), `aura-core/schemas/com.aura.memory.MemoryDatabase/`
**Evidence:**

```
$ ls aura-core/schemas/com.aura.memory.MemoryDatabase/
1.json  11.json  12.json  13.json  2.json  3.json  4.json  5.json  6.json
```

Schemas 7, 8, 9, 10 are missing. The migration test in `aura-core/src/androidTest/kotlin/com/aura/memory/MemoryDatabaseMigrationTest.kt` validates migrations by comparing the actual DB after running migrations against the recorded `*.json` files. If those JSONs are missing, the test either skips the comparison or fails. This was likely enabled in v0.30.x and never regenerated for the intermediate versions.

**Fix:** re-export the missing schema versions, or document the gap and rely on runtime smoke tests. Not data-loss per se but a regression-detection gap.

---

### [P2] `BackupManager.purgeAll()` does not reset `usage` tracker — usage accumulates across restore cycles

**File:** `aura-core/src/main/kotlin/com/aura/backup/BackupManager.kt`
**Lines:** 470–513
**Evidence:** The `purgeAll()` function wipes every Room table but never calls `usageTracker.reset()` or anything similar. After a restore cycle (purgeAll → restore), the in-memory `usageTracker` still has the values from before the purge, but the database state has been replaced with the backup's data. The next call to `snapshot()` will write a hybrid `usage` field: the Room-derived counts from the backup PLUS the in-memory deltas accumulated since restore. Over many restore cycles this can drift arbitrarily far from reality.

**Fix:** call `usageTracker.reset()` at the top of `purgeAll()` (runCatching-wrapped) so the next `restore()` populates from a clean slate.

---

### [P2] `ConversationBackup.toEntity()` keeps `deletedAt` on restore — but `restore()` does not call `purgeAll` first, so soft-deleted rows survive restore as visible

**File:** `aura-core/src/main/kotlin/com/aura/backup/BackupManager.kt`
**Lines:** 695–712 (mapper), 313 (insert)
**Evidence:**

The mapper preserves `deletedAt` (intentional per the comment). The restore path uses `OnConflictStrategy.REPLACE`. If the user has existing conversations (e.g., from a previous install) and restores a backup that has overlapping IDs but different `deletedAt` values, the REPLACE overwrites. But the bigger issue: if the user calls `restore()` WITHOUT first calling `purgeAll()` (per the KDoc on line 267–272, "the caller is expected to call purgeAll first if a clean-slate restore is intended"), conversations present locally but not in the backup survive — including any soft-deleted ones already purged from the local ConversationStore. The expectation is "restore brings me back to the state at backup time" but that's only true if `purgeAll` is called. The Settings UI flow is the only thing that can guarantee that. **Verify:** check SettingsScreen restore flow.

---

### [P3] `MemoryDao.allForExport()` and `ConversationDao.allForExport()` — naming suggests "for export" but they're general-purpose read-everything methods

**File:** `aura-core/src/main/kotlin/com/aura/memory/MemoryDao.kt`, `aura-core/src/main/kotlin/com/aura/agent/ConversationDao.kt`
**Evidence:** Method names use `allForExport` but the queries are not restricted to exportable rows (e.g., they include soft-deleted conversations if those are present in the table). The export is then dumped wholesale. Style/clarity issue more than a bug, but if any future code adds a "skip rows with privacyClass = 'sensitive'" filter, the export path will silently bypass it.

---

## 2. Items checked, no new bugs found (worth recording)

- **MemoryDatabase migrations 1→13** — all 12 are present in the migration array; SQL is well-formed.
- **DreamConsolidationDatabase exportSchema** — was disabled; now enabled (fixed in 7-25 pass).
- **TasteEngine bucketing** — fixed in 7-25 pass (now key:value, not value only).
- **memory_feedback purge** — fixed in 7-25 pass.
- **BackupManager schema v10/v11 DAOs** — present in constructor and wired into restore() + purgeAll().
- **DreamConsolidator phase 1–6** — non-destructive, idempotent, single-cycle via WorkManager (no concurrency hazard).
- **MemoryEntity scope preservation** — fixed (per the mapper comment, MEMORY_AUDIT A1).
- **TasteEngine mutex** — present on `recordSignal` and `recomputeProfile`; signal reads via `routingDao` are not protected but read-only DAO methods are fine.
- **KG edge insertion order** — nodes before edges (correct).
- **Hand run scheduling on restore** — `handRows.forEach(handScheduler::schedule)` is correct; runs are inserted without re-scheduling.

---

## 3. Recommendations (priority order)

1. **Add the 4 missing RestoreCounts fields** (BackupManager.kt:422). Trivial, prevents the data class / call-site drift from getting worse.
2. **Fix TasteEngine sign-flipping normalization** (TasteEngine.kt:158). Use `abs(totalWeight)` or `categorySignals.size`.
3. **Decide what to do with pruneStale's "fake archive"** (DreamConsolidator.kt:580). Either add a DAO method to set `decayScore=0`, or change the report counter to 0 until the fix lands.
4. **Re-export MemoryDatabase schemas 7–10** and run the migration test to verify.
5. **Add `usageTracker.reset()` to purgeAll** (BackupManager.kt:470).
6. **Verify `usageTracker.restore()` semantics** — overwrite or merge? If merge, document or change.

---

## 4. Verification plan (TODO)

- [ ] Read `UsageTracker.restore()` and confirm overwrite-vs-merge.
- [ ] Read SettingsScreen restore flow to confirm `purgeAll` is always called before `restore`.
- [ ] Confirm whether `MemoryDatabaseMigrationTest` actually validates against the missing schema files (might just skip).
- [ ] Read `TasteEngineTest` to confirm the new aggregation test exercises the negative-weight case.
- [ ] Confirm `pruneStale`'s intent vs. effect — is the comment actually wrong, or is the FadeMem pass planning to handle `pruned:dream` tag in a future change?
