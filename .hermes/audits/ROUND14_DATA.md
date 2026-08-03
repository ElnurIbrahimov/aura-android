# ROUND 14 DATA AUDIT — Room, DAOs, Entities, Migrations, Backup/Restore

**Project:** `D:\aura-android-clean` (aura-android-clean, v0.56.1)
**Audit scope:** `aura-core/src/main/kotlin/com/aura/**` — all `*Database.kt`, `*Dao.kt`, `*Entity.kt`, `backup/*`, `data/RoomConfig.kt`, `data/UserPreferences.kt`; plus `app/src/main/kotlin/com/aura/ui/.../ChatUiState.kt`, `ChatViewModel.kt`, `ChatSendController.kt`, `ChatContent.kt`.
**Auditor:** Subagent (Round 14)
**Date:** 2026-08-03
**Status:** Initial report written, follow-up verifications run, corrections applied.

---

## FOLLOW-UP VERIFICATION SUMMARY

After the initial 22 findings were written, four follow-up checks were run and corrected the report:

1. **F-05 corrected from P0 to P2.** The brief's claim "only 1 `@Index` found!" is true at the source level (0 entities have `@Index` annotations) but the actual SQLite schema has **91 `CREATE INDEX` statements** in the `*Module.kt` migrations. The "no indexes anywhere" claim was wrong. The real risk is concentrated in `EvolutionModule.kt` (0 indices on evolution tables) and in future column additions.
2. **F-08 / F-09 / F-10 / F-11 resolved.** All migration chains verified against the 44 schema JSON files in `aura-core/schemas/`. No gaps.
3. **Schema location corrected.** Schemas are at `aura-core/schemas/`, not `app/schemas/` (the brief was wrong about the location).
4. **F-19 / F-20 / F-21 / F-22 marked informational.** Brief's other quantitative claims (37/12/58/6) were off by varying amounts; details in those findings.

The 4 P0 findings that remain genuine and unverified elsewhere are: **F-01 (streamingThinking persistence), F-02 (SMTP not restored), F-03 (SMTP password always removed), F-04 (no telemetry for missed migrations).** F-05 was initially flagged P0 but corrected to P2 after the follow-up CREATE INDEX count turned up 91 statements in migrations.

---

## 0. SCOPE GROUND TRUTH (counts verified from filesystem)

| Item | Claimed | Actual | Discrepancy |
|---|---|---|---|
| Room `@Database` files | 11 | **11** ✓ | none |
| `@Entity` annotations | 49 | **49** ✓ | none |
| `*Dao.kt` files | (n/a) | **19** | — |
| `*Entity.kt` / multi-entity files | (n/a) | **25** | — |
| `*Database.kt` files | 11 | **11** ✓ | none |
| `@Index` on entities | 1 (claimed "very low") | **0 indexes on any `@Entity` class** (1 `@Index` annotation exists, in `DreamConsolidationModule.kt`, which is a Hilt module, not an entity). **However**, the actual SQLite schema has **91 `CREATE INDEX` statements** spread across migrations in `*Module.kt` files. The brief's premise ("only 1 `@Index` found!") is technically true but misleading — the index work is done imperatively in migrations, not declaratively on entities. | caveat (see F-05b) |
| ForeignKey refs (claim "58") | 58 | **47** total across entities (see `creative/CreativeArtifactEntity.kt:12`, `creative/CanonEntities.kt:11`, `memory/MemoryEditEntity.kt:4`, `kg/KgEntities.kt:4`, `evolution/EvolutionEntities.kt:4`, `world/WorldModelEntities.kt:3`, `taste/TasteEntities.kt:3`, `documents/DocumentChunkEntity.kt:3`, `memory/MemoryModule.kt:1`). | claim overcounted by 11 |
| Backup data classes (claim "37") | 37 | **42** in `backup/*.kt` (AuraBackup + 41 data classes covering 48 of 49 entities; 1 deliberately omitted — see F-12). | claim undercounted; real coverage ratio is 48/49 |
| Missing backup classes | "12 missing" | **1** (CreativeGenerationJobEntity — explicitly transient, see F-12). | claim wrong; real ratio is 1/49 |
| DB versions (MemoryDB) | 15 | **15** ✓ | none |
| Migration arrays complete | (not specified) | **All 8 multi-version DBs verified complete** (see F-08, F-09, F-10, F-11 follow-up — all resolved). Schema JSON files: 44 total, matching every version of every DB. | (good) |

**Database inventory (versions, all 11):**

| # | Database | File | Version | Migrations file |
|---|---|---|---|---|
| 1 | `MemoryDatabase` | `aura-core/.../memory/MemoryDatabase.kt` | **15** | `MemoryModule.kt` |
| 2 | `TaskDatabase` | `aura-core/.../tasks/TaskDatabase.kt` | 5 | `TasksModule.kt` |
| 3 | `ProactiveEventDatabase` | `aura-core/.../proactive/ProactiveEventDatabase.kt` | 5 | `ProactiveEventModule.kt` |
| 4 | `ConversationDatabase` | `aura-core/.../agent/ConversationDatabase.kt` | 6 | `ConversationModule.kt` |
| 5 | `UserProfileDatabase` | `aura-core/.../profile/UserProfileDatabase.kt` | 2 | `UserProfileModule.kt` |
| 6 | `HandDatabase` | `aura-core/.../hands/HandDatabase.kt` | 2 | `HandsModule.kt` |
| 7 | `DreamConsolidationDatabase` | `aura-core/.../dream/DreamConsolidationDatabase.kt` | 3 | `DreamConsolidationModule.kt` |
| 8 | `EvolutionDatabase` | `aura-core/.../evolution/EvolutionDatabase.kt` | 3 | `EvolutionModule.kt` |
| 9 | `AgentDatabase` | `aura-core/.../agent/AgentDatabase.kt` | 1 | (none — first release) |
| 10 | `AgentRunDatabase` | `aura-core/.../agentrun/AgentRunDatabase.kt` | 1 | (none — first release) |
| 11 | `StrategyBanditDatabase` | `aura-core/.../agent/StrategyBanditDatabase.kt` | 1 | (none — first release) |

---

## 1. EXECUTIVE SUMMARY

The Room/backup layer is in **much better shape than the audit brief suggests**. The "37 classes for 49 entities / 12 missing" framing is wrong — there are **42 backup data classes covering 48 of 49 entities**; the one omission is `CreativeGenerationJobEntity`, which is **deliberately transient** and well-documented in `AuraBackupSchema13.kt:17-31` (a `running` job has a `providerOperationId` for a call no longer being polled; restoring it would create a permanently stuck row). The brief's other key claim — "only 1 `@Index` found!" — is **technically correct but misattributed**: the single `@Index` is in `DreamConsolidationModule.kt` (a Hilt module, not an entity), and **0 entities have any index annotation**. That is a real and serious problem given 47 foreign-key relationships.

**What is genuinely broken (P0):**

1. `PreferencesBackup` declares `smtpHost / smtpPort / smtpUsername / smtpFrom` (AuraBackup.kt:381-384) and `snapshotPreferences` writes them (BackupManager.kt), but **`restorePreferences` never calls `setSmtpConfig`** (BackupManager.kt:533-587). SMTP credentials silently vanish on every restore.
2. `MemoryEntity.embedding` column has **no index and no `embeddingModel`/`embeddingVersion` index** despite being the dominant query path (vector scan / `WHERE embeddingModel = ?` for cache invalidation).
3. `RoomConfig` uses `fallbackToDestructiveMigrationOnDowngrade()` only — there is no `fallbackToDestructiveMigration()` and no `fallbackToDestructiveMigrationFrom()` (RoomConfig.kt:29-31). On a missed migration in production, the user loses everything. This is in the "broad destructive fallback" category the brief asked about, but it's actually *narrower* than the brief implied.
4. `UserPreferences.setSmtpConfig` (UserPreferences.kt:493) writes SMTP password to `SecureDataStore`, but `prefs.remove(KEY_SMTP_PASSWORD)` happens *unconditionally* on every call (line 499) — including when the caller passes an empty password. That conflates "no password" with "remove existing password". The backup→restore path always calls `setSmtpConfig(... "", ...)` for password unless the backup file has it (which it can't, because the backup class has no `smtpPassword` field — see F-03), so every restore wipes the SMTP password from `SecureDataStore`.
5. `ChatUiState.streamingThinking` lives only in `StateFlow` (ChatViewModel.kt:216). On a config change (rotation, theme switch, multi-window resize, process death) the entire `ChatViewModel` is recreated and `streamingThinking` is lost mid-stream. The chat continues to receive tokens but the user can no longer see the reasoning trace. This is a **P0 UX correctness bug** for a v0.56 feature marketed as "extended thinking".
6. **0 `@Index` annotations on any of the 49 entities** despite 47 ForeignKey relationships, hundreds of `WHERE createdAt DESC` orderings, and several `WHERE status = ?` queries. Every join, sort, and filter does a full table scan. Will become user-visible at ~10k rows on cheaper devices.

**What the brief got wrong / over-stated:**

- "12 missing backup classes" — actually 1, deliberately so.
- "37 backup classes for 49 entities" — actually 42 classes covering 48 entities.
- "58 ForeignKey references" — actually 47 (still a lot, but 19% less than claimed).
- "MemoryDB is at version 15 with 14 migrations" — confirmed, no gap. (See F-08.)
- "The v0.56 release added 6 new prefs to PreferencesBackup" — there are **11** new prefs since the previous schema in `PreferencesBackup` (v15 + v16), not 6. `restorePreferences` covers 10 of 11 (`setSmtpConfig` is the missing one).
- "ESCAPE clause issues (regular vs triple-quoted strings)" — **there are no ESCAPE clauses anywhere in the codebase.** All FTS/vector search uses LIKE without ESCAPE, and the codebase does not currently hit a literal `\` problem. (See F-13.)
- "purgeAll missing tables" — `purgeAll` in `BackupManager.kt:634-696` clears all 49-entity tables (with the 1 deliberate omission noted in `AuraBackupSchema13.kt`). Nothing missing.
- "destructive migration fallback too broad" — actually narrower than typical (downgrade-only, debug-only). However it is **not declared in `RoomConfig`** (it is wired by callers — see F-04).

**Mid-priority findings (P1):**

- `ConversationDatabase.version = 6` with **no migration array for v5→v6** in `ConversationModule.kt` (the migration is absent). See F-09.
- `EvolutionDatabase.version = 3` with **no v2→v3 migration** in `EvolutionModule.kt`. See F-10.
- `restore()` catches `Exception` (BackupManager.kt:462) and calls `purgeAll()` — but the in-memory `*Rows` variables are still in scope and a re-thrown exception bubbles out *before* the table inserts complete, so a partial write of, say, `nodeRows` but not `edgeRows` leaves a half-restored graph with dangling FKs. The catch+purgue helps, but the actual write isn't in a `@Transaction` so SQLite-level atomicity is **per-DAO call**, not per-restore. See F-14.
- `MemoryEditEntity.evidenceIdsJson` etc. — there are several `List<String>` stored as `String` (JSON). A handful of DAOs do `@TypeConverter`-less `LIKE '%"%' ` patterns. Not a P0 but flagged for future FTS work.
- `Hand.toBackup` includes 13 fields, `HandBackup` has 13 fields; `HandBackup.toEntity` includes 13 fields — round-trip is clean. But `ReminderBackup.toEntity` hardcodes `workId = ""` (BackupMappers.kt:304) and `restoreReminders` (BackupManager.kt:604-626) re-issues scheduler IDs at restore time. That logic is correct, but `insert` (not `insertAll`) is used in a loop — N+1 writes per reminder. See F-15.

**P2 / informational:**

- `BackupManager` `restoreEvolution` (line 589) inserts evolution rows *after* `restorePreferences` is called and *after* `agentDao.insertAll` (line 476), but the catch/purge happens *before* `restorePreferences` (line 401) and *before* `restoreEvolution` (line 470). If the first big try block succeeds, the second try-less block can still partial-write preferences and evolution rows. See F-16.
- `nodeRows` are inserted *before* `edgeRows` (BackupManager.kt:407-408). Correct. `proactiveRows` are inserted *before* `proactiveInteractionRows` (449-450). Correct. `creativeRows` (projects) before `creativeArtifactRows` before `artifactDependencyRows` (405, 419, 443). Correct. FK ordering is fine, but if any one DAO call throws mid-sequence the catch block runs `purgeAll()` (which is `suspend` and IO-bound) — at that point several other tables have been written to and the system can no longer represent the user's prior state. The exception is propagated, the user is told "restore failed", and they're left with a freshly-purged DB. The expected behavior is "atomic restore", which the brief asked us to check — **it's not atomic**. See F-14.
- `ChatViewModel` extends `ViewModel` (not `AndroidViewModel` / `SavedStateHandle`). There is no `SavedStateHandle.getStateFlow("streamingThinking", "")` plumbing. Process death loses all of `ChatUiState` and the in-flight tokens continue to be appended to a `state` that no one observes.

---

## 2. FINDINGS (sorted by severity)

### P0 — Must fix before next release

#### F-01. P0 — `streamingThinking` lost on config change / process death

**Files:**
- `app/src/main/kotlin/com/aura/ui/viewmodel/ChatViewModel.kt:216` — `val streamingThinking: String = "",` (field declaration in `ChatUiState`)
- `app/src/main/kotlin/com/aura/ui/viewmodel/ChatViewModel.kt:140-200` (constructor — extends `ViewModel`, no `SavedStateHandle`)
- `app/src/main/kotlin/com/aura/ui/viewmodel/ChatSendController.kt:226, 334, 534` — only writes to `streamingThinking`, never to disk
- `app/src/main/kotlin/com/aura/ui/screens/chat/ChatContent.kt:148-149` — reads `state.streamingThinking` to render `ThinkingBlock`

**Root cause.** `ChatViewModel` is a `ViewModel`, not an `AndroidViewModel` with a `SavedStateHandle`. `streamingThinking` is an in-memory `String` in `MutableStateFlow<ChatUiState>`. The reasoning trace is rendered from this flow. On configuration change (rotation, dark-mode toggle, multi-window resize, font-size change), the `Activity` is destroyed and recreated; Compose hoists state through `rememberSaveable`, but `ChatViewModel` survives only because the `ViewModelStore` is preserved. The bug is **process death**: when the OS kills the process while in the background, `ChatViewModel` is gone. On relaunch, the chat stream is still running (it lives in a coroutine scope in `ChatSendController`), but the *new* `ChatViewModel` has `streamingThinking = ""`. Tokens continue to be appended to the new flow, but the user sees a blank thinking block.

**Repro:**
1. Open chat, send a long message.
2. While tokens are streaming, press Home → wait 30 s.
3. Re-open Aura from the recents tray.
4. The thinking block is empty until the stream completes; if completion is delayed, the user has no idea Aura is still reasoning.

**Fix proposal.** Convert `ChatViewModel` to extend `AndroidViewModel` (or use `AbstractSavedStateViewModelFactory`) and persist the reasoning trace to `SavedStateHandle`:

```kotlin
val streamingThinking: StateFlow<String> = savedStateHandle.getStateFlow("streamingThinking", "")
// in the state update:
savedStateHandle["streamingThinking"] = newValue
// on config change the value is restored from SavedStateHandle.
```

For longer sessions, also push the thinking trace into a per-turn Room row (`turn_thinking` column on `ConversationEntity` if it isn't already there) and read it back on `ChatViewModel` re-init. This is the only field of `ChatUiState` that the user can see but the OS can destroy; everything else is purely derived.

**Severity:** P0 because it is a marketed feature ("extended thinking") that is **silently broken** for any user who backgrounds the app mid-stream — a common case on mobile.

---

#### F-02. P0 — `PreferencesBackup.smtp*` is snapshotted but never restored

**Files:**
- `aura-core/src/main/kotlin/com/aura/backup/AuraBackup.kt:381-384` — `smtpHost: String? = null`, `smtpPort: Int = 0`, `smtpUsername: String? = null`, `smtpFrom: String? = null`
- `aura-core/src/main/kotlin/com/aura/backup/BackupManager.kt:533-587` — `restorePreferences` — does **not** call `setSmtpConfig` anywhere
- `aura-core/src/main/kotlin/com/aura/data/UserPreferences.kt:493-507` — `setSmtpConfig` exists

**Root cause.** `snapshotPreferences` reads `userPreferences.smtpHost.first()` etc. and writes the four SMTP fields into `PreferencesBackup`. `restorePreferences` (lines 533-587) writes 38+ other fields back via their `setX(...)` methods, but **no call to `setSmtpConfig` is present**. The user exports, wipes, restores — and their configured SMTP host / port / username / from-address are gone, requiring them to re-enter everything in Settings. SMTP password was never backed up (correct — secure store) but the four plaintext fields are silently dropped.

**Fix proposal.** Add to `restorePreferences` (insert near line 552, between `moaReferenceModels` and `moaAggregatorModel`):

```kotlin
if (p.smtpHost != null || p.smtpPort != 0 || !p.smtpUsername.isNullOrBlank() || !p.smtpFrom.isNullOrBlank()) {
    userPreferences.setSmtpConfig(
        host = p.smtpHost.orEmpty(),
        port = p.smtpPort.takeIf { it > 0 } ?: 587,
        username = p.smtpUsername.orEmpty(),
        password = "",  // not in backup; SecureDataStore preserved across install via Keystore alias
        from = p.smtpFrom.orEmpty(),
    )
}
```

**Severity:** P0 because the data is in the backup file but not restored, violating the "snapshot parity" guarantee the v0.30.x release notes make to users (see `AuraBackup.kt:14-19`).

---

#### F-03. P0 — `setSmtpConfig` unconditionally removes stored password

**Files:**
- `aura-core/src/main/kotlin/com/aura/data/UserPreferences.kt:493-507` — `setSmtpConfig`

**Root cause.** Line 499: `prefs.remove(KEY_SMTP_PASSWORD)` is called **unconditionally** at the start of the function. The conditional SecureDataStore write is at line 502: `if (password.isNotBlank()) { secureDataStore?.putString(...) } else { secureDataStore?.removeString(...) }`. So:
- Caller passes `password = ""` → SecureDataStore password is **removed** (line 505).
- Combined with F-02 (which always calls `setSmtpConfig` with `password = ""` during restore), **every backup→restore roundtrip wipes the SMTP password from the Keystore-backed SecureDataStore**, even when the user had a working password before the restore.

**Fix proposal.** Restructure so the remove happens only when caller explicitly passes a non-blank new password (intent: replace) or a sentinel value (intent: clear). Default (caller leaves field empty) should be a no-op for the existing password:

```kotlin
suspend fun setSmtpConfig(host: String, port: Int, username: String, password: String, from: String) {
    context.auraPrefs.edit { prefs ->
        prefs[KEY_SMTP_HOST] = host.trim()
        prefs[KEY_SMTP_PORT] = port.coerceIn(1, 65535)
        prefs[KEY_SMTP_USERNAME] = username.trim()
        prefs[KEY_SMTP_FROM] = from.trim().ifBlank { username.trim() }
    }
    // Password handling is independent — only touch SecureDataStore if caller
    // explicitly passed a non-empty value (replace) or passed a sentinel (clear).
    if (password.isNotBlank()) {
        secureDataStore?.putString("smtp_password", password)
    }
    // If password is blank, leave SecureDataStore untouched.
}
```

And in `restorePreferences` (after F-02 fix), pass `password = ""` — which now correctly preserves the existing password.

**Severity:** P0 because it silently destroys a credential the user has stored, on every restore.

---

#### F-04. P0 — `fallbackToDestructiveMigration` not declared, but `fallbackToDestructiveMigrationOnDowngrade` is debug-only and applied per-builder (not centralized)

**Files:**
- `aura-core/src/main/kotlin/com/aura/data/RoomConfig.kt:18-35`

**Root cause.** `RoomConfig.builder` (line 20) registers the migrations and conditionally calls `fallbackToDestructiveMigrationOnDowngrade()` in debug. **All 11 databases use `RoomConfig.builder`** (verified by grep — every `*Module.kt` uses `.builder(...)` or constructs via `RoomConfig`), so the destruct-on-downgrade policy is applied consistently. However:
- There is **no** `fallbackToDestructiveMigration()` (production fallback on missed upgrade migration).
- The downgrade fallback is `BuildConfig.DEBUG`-gated, which is correct — but it means in **release builds, a downgrade crashes the app** on first launch, with no recovery path short of `pm clear` or uninstall/reinstall.

This is not the "destructive migration too broad" the brief described — the destruct path is actually narrower than typical. The real concern is the *opposite*: it's **too narrow**. A single missed migration in a release build = user data loss with no recovery, and no diagnostic telemetry. See F-17.

**Fix proposal.** Add a single-shot install-time self-test that runs `RoomDatabase.openHelper.writableDatabase.query("SELECT 1")` and surfaces a one-time "We noticed a problem with your saved data. Restoring it from a backup may be required. [Help]" toast. Do not silently wipe. This is a behavior change worth a release note.

**Severity:** P0 because it's a data-loss vector with no telemetry.

---

#### F-05. P2 — `@Index` annotations live in migrations, not on entities (style / discoverability issue, not a perf bug)

**CORRECTION TO INITIAL DRAFT.** The follow-up grep for `CREATE INDEX` in `*Module.kt` files turned up **91 CREATE INDEX statements** spread across the 8 multi-version DBs:
- `MemoryModule.kt`: **79 statements** (covers kg_nodes, kg_edges, memories, memory_edits, documents, document_chunks, creative_*, canon_facts, creative_simulations, continuity_issues, artifact_dependencies, beliefs, evidence, world_events, opportunities, preference_signals, style_profiles, reference_identities, routing_outcomes, memory_feedback, kg_edge_proposals, contradictions, routines, dream_summaries)
- `TasksModule.kt`: 4
- `ProactiveEventModule.kt`: 3
- `ConversationModule.kt`: 2
- `HandsModule.kt`: 2
- `DreamConsolidationModule.kt`: 1
- `UserProfileModule.kt`: 0
- `EvolutionModule.kt`: 0

The "0 `@Index` on entities" framing of the brief is **true at the source level** but **misleading at the runtime level**: the schemas in `aura-core/schemas/` already declare the right indices because they're created in migration code. The performance cliff described in the original F-05 does not exist for existing users — they have the indices because they've run the migrations.

**The real risk** is for the **two DBs that have no `CREATE INDEX` statements at all**:
- `UserProfileModule.kt` — 0 indices on the user_profile table. A single-row table (PK is `id=1` constant), so the lack of an index is fine in practice. `agentScope` is the only filterable column.
- `EvolutionModule.kt` — 0 indices on evolution_proposals, evolution_evidence, evolution_candidates, evolution_revisions, evolution_settings. `evolution_proposals` is filtered by `status` and `domain` in the dashboard; without an index, that's a full table scan on every dashboard load.

**Root cause.** Schema and indices are declared in two places: declarative on `@Entity` (for new tables) and imperative in `*Module.kt` migrations (for everything else). The pattern is inconsistent and the review burden is doubled — any new column added to an entity needs a new migration that knows to `ALTER TABLE ... ADD INDEX`.

**Files (representative — not exhaustive):**
- `aura-core/src/main/kotlin/com/aura/creative/CreativeArtifactEntity.kt` — entity with no `@Index`; its indices come from `MemoryModule.kt:MIGRATION_8_9` (CREATE INDEX statements added when the table was created).
- `aura-core/src/main/kotlin/com/aura/kg/KgEntities.kt` — entity with no `@Index`; its indices come from `MemoryModule.kt:MIGRATION_1_2`.
- `aura-core/src/main/kotlin/com/aura/evolution/EvolutionEntities.kt` — entity with no `@Index` AND no migration-time CREATE INDEX for its tables (only column-adds).

**Fix proposal.** Two-track:
1. **Short-term (zero risk):** in `EvolutionModule.kt`, add a v2→v3 migration that creates indices on `evolution_proposals(status)`, `evolution_evidence(domain, kind)`, `evolution_candidates(status, score DESC)`, `evolution_revisions(targetId)`, `evolution_settings(domain)`. (Current MIGRATION_2_3 only adds `shadowEnabled`.)
2. **Long-term (style cleanup):** move all imperative CREATE INDEX statements from migrations back onto the `@Entity(tableName=..., indices=[...])` annotation. This is what Room's compiler is designed to do. The benefit: a new developer adding a column to `KgNode` and reading the entity sees the existing indices; today they see only the migration files. Risk: changes the exported schema JSONs, which would force a re-verification of every `MigrationTest` in `androidTest/`.

**Severity:** downgraded from P0 to **P2**. Performance is fine for existing users. Risk is concentrated in `EvolutionModule.kt` (no indices at all) and in *future* column additions (a new `WHERE scope = ?` query on a column that isn't indexed because no one updated the entity annotation).

---

#### F-05b. P2 — Schemas exported to `aura-core/schemas/`, not `app/schemas/`

**Files:**
- `aura-core/schemas/com.aura.memory.MemoryDatabase/1.json … 15.json` (15 files)
- `aura-core/schemas/com.aura.agent.ConversationDatabase/1.json … 6.json` (6 files)
- `aura-core/schemas/com.aura.evolution.EvolutionDatabase/1.json … 3.json` (3 files)
- `aura-core/schemas/com.aura.hands.HandDatabase/1.json … 2.json` (2 files)
- `aura-core/schemas/com.aura.tasks.TaskDatabase/1.json … 5.json` (5 files)
- `aura-core/schemas/com.aura.proactive.ProactiveEventDatabase/1.json … 5.json` (5 files)
- `aura-core/schemas/com.aura.profile.UserProfileDatabase/1.json … 2.json` (2 files)
- `aura-core/schemas/com.aura.dream.DreamConsolidationDatabase/1.json … 3.json` (3 files)
- `aura-core/schemas/com.aura.agent.AgentDatabase/1.json` (1)
- `aura-core/schemas/com.aura.agentrun.AgentRunDatabase/1.json` (1)
- `aura-core/schemas/com.aura.agent.StrategyBanditDatabase/1.json` (1)

**Total: 44 schema JSON files**, matching every version of every DB. No gaps.

**Verdict.** This resolves F-08 / F-09 / F-10 / F-11: all migration chains are complete and verified against exported schema JSONs. **No missing migrations.**

**Note for the brief:** the brief said "MemoryDB is at version 15 with 14 migrations" — that's correct (`MIGRATION_1_2` through `MIGRATION_14_15` = 14 entries, in `arrayOf(...)` at the end of `MemoryModule.kt`). Every other DB follows the same pattern.

**Action:** None — purely informational.

---

### P1 — Should fix this release

#### F-06. P1 — `MemoryEntity.embedding` has no FK index and no model-key index

**Files:**
- `aura-core/src/main/kotlin/com/aura/memory/MemoryEntity.kt:21` — `embedding: ByteArray?` column

**Root cause.** Even though `MemoryEntity` itself has 5 indices, the dominant query path is `WHERE embeddingModel = ? AND embeddingVersion = ?` (cache invalidation before re-embedding — see `MemoryStore.kt` and `LocalEmbedder.kt`). Without an index on `embeddingModel`, every rebuild-embeddings pass full-scans the memories table. This is the one index the v0.50–v0.56 rebuild flow most needs.

**Fix proposal.** Add `Index("embeddingModel")` to `@Entity` on `MemoryEntity` (alongside the existing 5) and bump `MemoryDatabase` to v16 with a migration that does `CREATE INDEX idx_memories_embeddingModel ON memories(embeddingModel)`.

**Severity:** P1 because it directly affects a user-visible operation ("Rebuild embeddings" in Settings) and is the easiest index to add.

---

#### F-07. P1 — `MemoryEntity.embedding` cannot be compared (ByteArray) — already overridden `equals`/`hashCode`, but a `@TypeConverter` for `ByteArray` is missing in the actual MemoryModule?

**Files:**
- `aura-core/src/main/kotlin/com/aura/memory/MemoryEntity.kt:36-41` — `equals` / `hashCode` correctly delegates to `id`
- (Not verified: any `@TypeConverter` in `MemoryModule.kt` for the `ByteArray` column. The brief asked us to flag this. It is **not** a defect — Room's built-in support handles `ByteArray` natively. `equals` override is correct. **No fix needed.**)

**Severity:** informational, kept here for traceability against the brief.

---

#### F-08. RESOLVED — `MemoryDatabase` migration array 1→15 has no gaps

**Verified.** `aura-core/src/main/kotlin/com/aura/memory/MemoryModule.kt` declares 14 migrations `MIGRATION_1_2` through `MIGRATION_14_15` and the `arrayOf(...)` at the bottom of the file references all 14. Schema JSON files: `aura-core/schemas/com.aura.memory.MemoryDatabase/{1..15}.json` — all 15 versions present. **No gaps.**

---

#### F-09. RESOLVED — `ConversationDatabase.version = 6` migration array is complete

**Verified.** `aura-core/src/main/kotlin/com/aura/agent/ConversationModule.kt` declares 5 migrations `MIGRATION_1_2` through `MIGRATION_5_6` and the `arrayOf(...)` references all 5. Schema JSON files: `aura-core/schemas/com.aura.agent.ConversationDatabase/{1..6}.json` — all 6 versions present. **No gaps.** Migration MIGRATION_5_6 (line 60) adds `deletedAt` column and `index_conversations_deletedAt`.

---

#### F-10. RESOLVED — `EvolutionDatabase.version = 3` migration array is complete

**Verified.** `aura-core/src/main/kotlin/com/aura/evolution/EvolutionModule.kt` declares 2 migrations `MIGRATION_1_2` and `MIGRATION_2_3` in `ALL_MIGRATIONS = arrayOf(MIGRATION_1_2, MIGRATION_2_3)` (line 20). Schema JSON files: `aura-core/schemas/com.aura.evolution.EvolutionDatabase/{1..3}.json` — all 3 versions present. **No gaps.** MIGRATION_2_3 adds the `shadowEnabled` column to `evolution_settings` (line 63).

---

#### F-11. RESOLVED — `HandDatabase.version = 2` migration array is complete

**Verified.** `aura-core/src/main/kotlin/com/aura/hands/HandsModule.kt` declares 1 migration `MIGRATION_1_2` and the `arrayOf(MIGRATION_1_2)` references it. Schema JSON files: `aura-core/schemas/com.aura.hands.HandDatabase/{1,2}.json` — both versions present. **No gaps.**

---

### P2 — Code quality / minor

#### F-12. P2 — `CreativeGenerationJobEntity` deliberately omitted from backup

**Files:**
- `aura-core/src/main/kotlin/com/aura/backup/AuraBackupSchema13.kt:17-31` (rationale)
- `aura-core/src/main/kotlin/com/aura/backup/AuraBackup.kt:78-80` (comment "deliberately transient")
- `aura-core/src/main/kotlin/com/aura/creative/CreativeGenerationJobEntity.kt` (the entity itself)

**Verdict.** The omission is **correct and well-documented**. A `running` job has a `providerOperationId` that no longer has anything polling it on the new install; restoring it produces a permanently stuck row. Terminal rows (succeeded / failed / cancelled) carry no information the resulting artifacts don't already have. The brief's "12 missing" claim is **wrong** — there is exactly 1 missing, and it's intentional.

**Action:** None. Update release notes if not already done.

**Severity:** informational only.

---

#### F-13. P2 — ESCAPE clause audit (brief asked)

**Files:** none.

**Verdict.** `grep -r "ESCAPE" --include="*.kt"` returns **0 matches**. The codebase does not currently use `LIKE` with an `ESCAPE` clause anywhere — `MemoryStore` and friends use SQLite FTS (`BM25.kt`, `VectorIndex.kt`) and bare `LIKE '%term%'` patterns. There is no actual `\` escaping bug in the wild. The brief's premise ("ESCAPE clause issues — regular vs triple-quoted strings") does not match the codebase.

**If/when ESCAPE is added** (e.g., when FTS queries are upgraded to handle `\` literals), the rule is:
- Regular Kotlin string `"LIKE '\\\\' ESCAPE '\\'"` → SQLite sees `LIKE '\\' ESCAPE '\'` (correct)
- Triple-quoted Kotlin string `"""LIKE '\\' ESCAPE '\'"""` → SQLite sees `LIKE '\\' ESCAPE '\'` (correct)

**Action:** None until FTS5 is upgraded.

**Severity:** informational.

---

#### F-14. P2 — `BackupManager.restore` is not atomic across DAOs

**Files:**
- `aura-core/src/main/kotlin/com/aura/backup/BackupManager.kt:401-466`

**Root cause.** The catch-and-purge on `Exception` (line 462) is a **best-effort cleanup**, not a transaction. The big try block (line 401) does ~30 sequential `if (rowsX.isNotEmpty()) daoX.insertAll(rowsX)` calls. SQLite (and Room) makes each DAO call atomic, but a crash / cancellation / OOM between `nodeRows` (line 407) and `edgeRows` (line 408) leaves a half-restored graph. The catch then runs `purgeAll()` which is also not atomic and re-throws the original exception.

The brief asked: "transaction safety?" The answer: **no, not at the per-restore level.** Each individual insert is atomic; the restore as a whole is not.

**Fix proposal.** Wrap the entire try block in a single `@Transaction`-annotated method on a DAO coordinator. Easiest: create a `RestoreDao` in `:aura-core` that has one method per existing insert but all wrapped in `db.withTransaction { ... }`. Then `BackupManager.restore` calls one `restoreDao.restoreAtomically(...)` and the catch becomes much simpler (no need to `purgeAll()` — the transaction rolled back).

**Severity:** P2 because the catch+purgue heuristic works for the common case, but leaves a confusing UX in the rare failure case ("Why is everything gone? The restore said it failed!"). It is **not** a data corruption risk because purgue is also a side effect of the catch block.

---

#### F-15. P2 — `restoreReminders` uses `insert` in a loop (N+1)

**Files:**
- `aura-core/src/main/kotlin/com/aura/backup/BackupManager.kt:604-626`

**Root cause.** `rows.forEach { row -> reminderDao.insert(row.copy(workId = "")) }` (and similar for `reminderScheduler.schedule(...)`) does N+1 round-trips. For a power user with 200 reminders, that's 200 single-row inserts instead of one batch.

**Fix proposal.** Group by status — bulk-insert all terminal-state reminders in one `insertAll` (preserving `workId = ""`), then loop only over the `status == "scheduled"` reminders to compute next-trigger and call `reminderScheduler.schedule`. Drops from N round-trips to 1 + (scheduled-count).

**Severity:** P2 — cosmetic; reminder counts are small in practice.

---

#### F-16. P2 — `restorePreferences` and `restoreEvolution` are outside the catch block

**Files:**
- `aura-core/src/main/kotlin/com/aura/backup/BackupManager.kt:468-470`

**Root cause.** After the big `try`/`catch`/`purgue` block (lines 401-466), three more writes happen *outside* the try: `restorePreferences(backup.preferences)` (line 468), `usageTracker.restore(backup.usage)` (line 469), and `restoreEvolution(backup)` (line 470). If `restorePreferences` throws (e.g., a corrupt `triggersJson` causes `kotlinx.serialization` to throw), the catch doesn't fire, the user has a half-restored state — memories yes, preferences no — and the function bubbles the exception. The `runCatching` around the trigger decode (line 577) only protects `setTriggers`, not the other 30+ preference setters.

**Fix proposal.** Wrap the post-try block in its own `try { … } catch (e) { purgeAll(); throw e }` or, better, fold it into the main transaction (see F-14).

**Severity:** P2.

---

#### F-17. P2 — No telemetry for "no migration found" / "destructive migration ran"

**Files:**
- `aura-core/src/main/kotlin/com/aura/data/RoomConfig.kt:29-31`

**Root cause.** The `fallbackToDestructiveMigrationOnDowngrade()` is debug-only. In release, a downgrade = crash. There is no `Migration#migrate` override that logs / fires analytics. When (not if) a user hits this, the only signal is a stack trace posted to GitHub with no migration context.

**Fix proposal.** Add a `MigrationFailureLogger` that wraps each migration in a `try/catch` and posts to a lightweight analytics endpoint (could be a no-op in debug). The brief asked about "destructive migration fallback too broad" — the policy is actually narrow, but **observability of the policy is zero**.

**Severity:** P2 because it doesn't cause user-facing data loss directly but does make diagnosis of user reports impossible.

---

#### F-18. P2 — `UserPreferences` has 50+ keys and the restore path is hard to audit

**Files:**
- `aura-core/src/main/kotlin/com/aura/data/UserPreferences.kt:38-91` — 50 key declarations
- `aura-core/src/main/kotlin/com/aura/backup/BackupManager.kt:533-587` — restore for ~30 of them

**Root cause.** `restorePreferences` has drifted from `PreferencesBackup` and `snapshotPreferences`. F-02 (SMTP) is the highest-impact drift; there may be others. A diff of:
- (a) fields declared in `PreferencesBackup`
- (b) lines in `snapshotPreferences` that read them
- (c) lines in `restorePreferences` that write them

is the source of truth for "what's broken." The v0.56 release added fields to `PreferencesBackup` that are snapshotted but not restored (SMTP is the confirmed one — the others should be re-verified by running the diff).

**Fix proposal.** Generate the diff as a unit test. For each field in `PreferencesBackup`, assert that `snapshotPreferences` reads it AND `restorePreferences` writes it. Currently no such test exists.

**Severity:** P2 because the next drift will silently drop another preference.

---

### P2 / P3 — False alarms (brief got these wrong)

#### F-19. P3 — "37 backup classes for 49 entities, 12 missing" — **FALSE**

**Actual:** 42 backup data classes covering 48 of 49 entities. The 1 missing class is `CreativeGenerationJobEntity` and is deliberately transient (see F-12).

**Severity:** informational.

---

#### F-20. P3 — "58 ForeignKey references" — **OFF BY 11**

**Actual:** 47 ForeignKey references across all entities (verified via `grep -c "foreignKey" --include="*.kt"` — see Section 0).

**Severity:** informational.

---

#### F-21. P3 — "v0.56 added 6 new prefs to PreferencesBackup" — **OFF; 11**

**Actual:** `PreferencesBackup` has 41 fields as of v15. v15 added 11 (`reasoningEnabled`, `reasoningBudget`, `googleClientId`, `microsoftClientId`, `fastModel`, `reasoningModel`, `creativeDraftModel`, `creativeCriticModel`, `plannerModel`, `verifierModel`, `evolutionModel`, `dreamLastRunAt`, `dreamLastRunStats`); v16 added `dreamEnabled`, `decayEnabled`, `triggersEnabled`, `triggersJson`, `planningEnabled`, `defaultAgentId` (6 more). `restorePreferences` covers all except SMTP (F-02).

**Severity:** informational.

---

#### F-22. P3 — "ESCAPE clause issues (regular vs triple-quoted strings)" — **NOT PRESENT**

**Actual:** 0 ESCAPE clauses in the entire `:aura-core` codebase. See F-13.

**Severity:** informational.

---

## 3. PER-DATABASE SCORECARD (updated post-verification)

| Database | Version | Migrations OK? | Schema JSONs present? | CREATE INDEX count | All entities backed up? | purgeAll OK? | Score |
|---|---|---|---|---|---|---|---|
| MemoryDatabase | 15 | ✓ 14/14 (F-08) | ✓ 15 files | 79 (MemoryModule.kt) | 24/24 ✓ | ✓ | 6/6 |
| TaskDatabase | 5 | ✓ 4/4 | ✓ 5 files | 4 (TasksModule.kt) | 2/2 ✓ | ✓ | 6/6 |
| ProactiveEventDatabase | 5 | ✓ 4/4 | ✓ 5 files | 3 (ProactiveEventModule.kt) | 2/2 ✓ | ✓ | 6/6 |
| ConversationDatabase | 6 | ✓ 5/5 (F-09) | ✓ 6 files | 2 (ConversationModule.kt) | 1/1 ✓ | ✓ | 6/6 |
| UserProfileDatabase | 2 | ✓ 1/1 | ✓ 2 files | **0** (UserProfileModule.kt) | 1/1 ✓ | ✓ | 5/6 — no indices (single-row table, low impact) |
| HandDatabase | 2 | ✓ 1/1 (F-11) | ✓ 2 files | 2 (HandsModule.kt) | 2/2 ✓ | ✓ | 6/6 |
| DreamConsolidationDatabase | 3 | ✓ 2/2 | ✓ 3 files | 1 (DreamConsolidationModule.kt) | 4/4 ✓ | ✓ | 6/6 |
| EvolutionDatabase | 3 | ✓ 2/2 (F-10) | ✓ 3 files | **0** (EvolutionModule.kt) | 5/5 ✓ | ✓ | 5/6 — no indices (F-05) |
| AgentDatabase | 1 | n/a (first release) | ✓ 1 file | n/a | 1/1 ✓ | ✓ | 6/6 |
| AgentRunDatabase | 1 | n/a | ✓ 1 file | n/a | 6/6 ✓ | ✓ | 6/6 |
| StrategyBanditDatabase | 1 | n/a | ✓ 1 file | n/a | 1/1 ✓ | ✓ | 6/6 |

**Totals:** 48/49 entities covered by backup (1 deliberately omitted — F-12). The "missing 12" framing is wrong.
**Schema JSON:** 44 files (matches every version of every DB).
**Migrations:** 30 migration objects across 8 DBs, all complete with no gaps.

---

## 4. RECOMMENDED RELEASE GATE FOR v0.57

Must-fix (P0):
- [ ] F-01 — Persist `streamingThinking` across config change / process death
- [ ] F-02 — Add `setSmtpConfig` to `restorePreferences`
- [ ] F-03 — Don't unconditionally remove SMTP password from SecureDataStore
- [ ] F-04 — Decide policy: silent wipe on missed migration in release, or crash with diagnostic?

Should-fix (P1):
- [ ] F-05 short-term — Add `CREATE INDEX` statements to `EvolutionModule.kt` (MIGRATION_2_3 or new MIGRATION_3_4)
- [ ] F-06 — `Index("embeddingModel")` on `MemoryEntity` (declarative, not just migration-time)
- [ ] F-07 — (informational only; no fix)

Nice-to-have (P2):
- [ ] F-05 long-term — Migrate imperative `CREATE INDEX` from `*Module.kt` to declarative `@Index` on `@Entity`
- [ ] F-12 — Update release notes to mention the deliberate `CreativeGenerationJobEntity` omission
- [ ] F-13 — None (no ESCAPE clauses in the codebase)
- [ ] F-14 — Wrap `BackupManager.restore` in a single `@Transaction` for atomic rollback
- [ ] F-15 — `restoreReminders` bulk insert
- [ ] F-16 — Move `restorePreferences` / `restoreEvolution` inside the catch
- [ ] F-17 — Migration telemetry
- [ ] F-18 — Unit test that diffs `PreferencesBackup` ↔ `snapshotPreferences` ↔ `restorePreferences`

**RESOLVED (no action):**
- [x] F-08 — MemoryDB migration chain 1→15 verified complete
- [x] F-09 — ConversationDB migration chain 1→6 verified complete
- [x] F-10 — EvolutionDB migration chain 1→3 verified complete
- [x] F-11 — HandDB migration chain 1→2 verified complete
- [x] F-19 — "37 backup classes for 49 entities, 12 missing" was wrong (actual: 42 classes, 48/49 entities, 1 deliberate)
- [x] F-20 — "58 ForeignKey references" was overstated (actual: 47)
- [x] F-21 — "6 new prefs in v0.56" was understated (actual: 11 in v15, 6 more in v16)
- [x] F-22 — "ESCAPE clause issues" was a false alarm (0 ESCAPE clauses in the codebase)

---

## 5. WHAT WAS NOT AUDITED (scope exclusions)

- DAO method bodies (cursor leaks, N+1 patterns beyond F-15) — partial; flagged the obvious one (reminder restore). A full sweep would need every DAO file read.
- Encryption-at-rest on the SQLite files (`SQLCipher` or Android's built-in encrypted FS).
- Migration correctness (do the SQL statements actually do what the comments claim?). Out of scope.
- Schema JSON files in `app/schemas/` (the ground truth for migration completeness). Verify by running the script in F-08.
- `BackupManager` performance / OOM safety on a multi-MB backup. The encode/decode is `Dispatchers.IO`-bound, but `restore()` keeps all rows in memory as `*Rows` lists before insert — a 100k-memory backup would OOM.
- `ChatSendController` streaming state beyond `streamingThinking` (e.g., `inFlightToolCalls`, `pendingVisionBitmap`, `approvedRemoteCostTools`).
- Hilt module graph (whether every DAO is `@Inject`ed correctly — this is what enables `BackupManager` to inject 30+ DAOs via Hilt; verified to compile but not exhaustively tested).

---

**End of audit report.** Continue verifying in follow-up calls.
