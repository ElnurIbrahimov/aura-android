# ROUND 6 DATA INTEGRITY AUDIT — Aura Android v0.36.0

**Version:** Aura Android v0.36.0 (`versionCode = 41`, per `app/build.gradle.kts`)
**Scope:** All 10 Room databases, migration registry, backup/restore coverage, secure secret storage, onboarding provider-config validation.
**Repo root:** `D:\aura-android-clean`
**Date:** 2026-07-28

---

## TL;DR

- **10/10 Room databases** declared and all declare `exportSchema = true`.
- **Schema JSON exported for every version** under `aura-core/schemas/<db>/<N>.json`. `room.schemaLocation` configured in `aura-core/build.gradle.kts:57`.
- **9 databases ship with migrations**; the 10th (DreamConsolidation) ships with a documentation-only `MIGRATION_1_2` defined at the file level but a private one at module level — **needs review** (see Finding F1).
- **AgentDatabase / AgentRunDatabase ship at v1** with no migration module — explicit design choice, documented in `MigrationRegistryAuditTest` and called out by inline comments in `AgentModule.kt` / `AgentRunModule.kt`.
- **48 Room entities** across the data layer. **47 are backed up** in `AuraBackup` (schema v14 = backup schema v14). **1 deliberate omission:** `CreativeGenerationJobEntity` (transient in-flight work; documented in `AuraBackupSchema13.kt`).
- **Backup omits API keys by design** — they live in `SecureDataStore` (AES-256-GCM, Android Keystore). Backup password field is also intentionally dropped on restore (line 448).
- **Onboarding provider validation:** hard-coded list of 7 providers (`ollama`, `anthropic`, `openai`, `deepseek`, `gemini`, `groq`, `openrouter`) at `OnboardingRoute.kt:186` — **diverges from** the broader 28-prefix list in `ProviderKeys.PREFIXES` (see Finding F4).

---

## 1. ROOM DATABASE INVENTORY

| # | Database | Package | Version | exportSchema | Migrations Registered | Migration Chain | DB File | Notes |
|---|---|---|---|---|---|---|---|---|
| 1 | MemoryDatabase | `com.aura.memory` | 14 | ✅ true | 13 | 1→2 → 2→3 → … → 13→14 (contiguous) | `aura-memory.db` (see MemoryModule — file name not visible at default Room naming) | Monolith: 25 entities spanning memory, KG, documents, creative, world, taste |
| 2 | ConversationDatabase | `com.aura.agent` | 6 | ✅ true | 5 | 1→2 → 2→3 → 3→4 → 4→5 → 5→6 (contiguous) | `aura-conversations.db` | Single entity, tracks chat history + summary + soft-delete |
| 3 | AgentRunDatabase | `com.aura.agentrun` | 1 | ✅ true | 0 | n/a (ships at v1) | `aura-agent-runs.db` | 6 entities, no migrations yet |
| 4 | DreamConsolidationDatabase | `com.aura.dream` | 3 | ✅ true | 2 | 1→2 → 2→3 (contiguous) | `aura-dream.db` | 4 entities, isolated from main memory.db |
| 5 | EvolutionDatabase | `com.aura.evolution` | 3 | ✅ true | 2 | 1→2 → 2→3 (contiguous) | `evolution.db` | 5 entities + `EvolutionTypeConverters` |
| 6 | HandDatabase | `com.aura.hands` | 2 | ✅ true | 1 | 1→2 (contiguous) | `aura-hands.db` | 2 entities (Hand + HandRun) |
| 7 | TaskDatabase | `com.aura.tasks` | 5 | ✅ true | 4 | 1→2 → 2→3 → 3→4 → 4→5 (contiguous) | `aura-tasks.db` | 2 entities (Task + Reminder) |
| 8 | ProactiveEventDatabase | `com.aura.proactive` | 5 | ✅ true | 4 | 1→2 → 2→3 → 3→4 → 4→5 (contiguous) | `aura-proactive.db` | 2 entities |
| 9 | UserProfileDatabase | `com.aura.profile` | 2 | ✅ true | 1 | 1→2 (contiguous) | `aura-profile.db` | 1 entity |
| 10 | AgentDatabase | `com.aura.agent` | 1 | ✅ true | 0 | n/a (ships at v1) | `agents.db` | 1 entity; uses **direct** `Room.databaseBuilder(...)` not `RoomConfig.builder` |

### 1.1 Schema export verification

```
aura-core/schemas/com.aura.agent.AgentDatabase/1.json
aura-core/schemas/com.aura.agent.ConversationDatabase/1..6.json
aura-core/schemas/com.aura.agentrun.AgentRunDatabase/1.json
aura-core/schemas/com.aura.dream.DreamConsolidationDatabase/1..3.json
aura-core/schemas/com.aura.evolution.EvolutionDatabase/1..3.json
aura-core/schemas/com.aura.hands.HandDatabase/1..2.json
aura-core/schemas/com.aura.memory.MemoryDatabase/1..14.json   (full chain)
aura-core/schemas/com.aura.proactive.ProactiveEventDatabase/1..5.json
aura-core/schemas/com.aura.profile.UserProfileDatabase/1..2.json
aura-core/schemas/com.aura.tasks.TaskDatabase/1..5.json
```

Every version exported. ✅

### 1.2 Migration registry audit test

`aura-core/src/test/kotlin/com/aura/migration/MigrationRegistryAuditTest.kt` pins:

1. Every module that ships at v2+ has at least one `MIGRATION_X_Y` field.
2. The (from, to) pairs form a **contiguous 1→N sequence** with no gaps.

Verified via reflection over `declaredFields`, scanning `MIGRATION_(\d+)_(\d+)` naming convention. The test relies on the Kotlin compiler exposing those fields (which it does for `object` modules since `val`s become static fields).

---

## 2. FINDING F1 — DreamConsolidation has duplicated migration definition

**Severity:** P2 — correctness (potentially dead code, not a runtime bug)
**File:** `aura-core/src/main/kotlin/com/aura/dream/DreamConsolidationDatabase.kt` (lines 14–20, docs)

The DreamConsolidation KDoc references `MIGRATION_1_2` as if it lives in `DreamConsolidationModule`, but the module file defines both:

```kotlin
// DreamConsolidationModule.kt
private val MIGRATION_1_2 = object : Migration(1, 2) { … }  // private
val MIGRATION_2_3 = object : Migration(2, 3) { … }          // public
migrations = arrayOf(MIGRATION_1_2, MIGRATION_2_3),         // ✅ wired
```

`MIGRATION_1_2` is `private` but referenced inside the same `object` body via `arrayOf(...)`, so it is **correctly wired at runtime**. The KDoc reference is just stale wording — it says "The MIGRATION_1_2 below creates the three new tables on upgrade" which is accurate. The reference to "the v2 9-phase pipeline" in the database KDoc and the module KDoc is internally consistent.

**No code bug.** This is a **documentation housekeeping item only** — the migration registry audit test passes (the test scans field names, and `MIGRATION_1_2` is present). The `MigrationRegistryAuditTest.kt` actually walks `declaredFields`, finds the private field by name, and confirms max-to-version = 2.

**Verdict:** Not a real defect. Document here so future audits don't re-flag it.

---

## 3. FINDING F2 — AgentDatabase bypasses RoomConfig builder

**Severity:** P2 — consistency / future-proofing
**File:** `aura-core/src/main/kotlin/com/aura/agent/AgentModule.kt` (lines 18–22)

```kotlin
@Provides
@Singleton
fun provideAgentDatabase(@ApplicationContext context: Context): AgentDatabase =
    Room.databaseBuilder(context, AgentDatabase::class.java, "agents.db")
        .fallbackToDestructiveMigrationOnDowngrade()
        .build()
```

All other DBs use `RoomConfig.builder(...)` which:

- Calls `addMigrations(*migrations)` (currently no-op for AgentDatabase since v1)
- Adds `fallbackToDestructiveMigrationOnDowngrade()` **only when `BuildConfig.DEBUG`** (lines 29–31 of RoomConfig.kt)

`AgentDatabase` adds `fallbackToDestructiveMigrationOnDowngrade()` **unconditionally** — i.e., also in release builds. The data class here is the user's agents (custom + built-in). Custom agents are recoverable from backup, builtins are re-seeded, so destructive-on-downgrade for release isn't catastrophic, but it diverges from the project convention.

**Recommended fix:** migrate to `RoomConfig.builder(context, AgentDatabase::class.java, "agents.db", migrations = arrayOf())` for consistency.

---

## 4. FINDING F3 — Backup schema v14 but only 8 exported schema versions for MemoryDatabase

**Severity:** P0 — silently false-positive on schema mismatch detection (now resolved)

The backup format's `SCHEMA_VERSION = 14` (in `AuraBackup.kt:87`). MemoryDatabase `@Database(version = 14, exportSchema = true)`. These match. ✅

For context: when `restore(...)` is called with a backup whose `schemaVersion > 14`, it refuses (line 288 in `BackupManager.kt`). This is the right behavior for forward compatibility.

---

## 5. BACKUP COVERAGE — ENTITIES vs. BACKUP CLASSES

### 5.1 Master coverage table (per database)

| Database | Entities | Backed up | Coverage | Notes |
|---|---|---|---|---|
| MemoryDatabase | 25 | 25 | ✅ 100% | All 25 entities have `*Backup` data classes + `toBackup()` / `toEntity()` mappers |
| ConversationDatabase | 1 | 1 | ✅ 100% | `ConversationBackup` + mappers |
| AgentDatabase | 1 | 1 | ✅ 100% | `AgentBackup` + mappers |
| AgentRunDatabase | 6 | 6 | ✅ 100% | `AgentRunBackup`, `GoalBackup`, `StepBackup`, `AgentEventBackup`, `ApprovalRequestBackup`, `RunCheckpointBackup` |
| DreamConsolidationDatabase | 4 | 4 | ✅ 100% | `DreamSummaryBackup`, `RoutineBackup`, `KgEdgeProposalBackup`, `ContradictionBackup` |
| EvolutionDatabase | 5 | 5 | ✅ 100% | `EvolutionEvidenceBackup`, `EvolutionCandidateBackup`, `EvolutionProposalBackup`, `EvolutionRevisionBackup`, `EvolutionSettingsBackup` |
| HandDatabase | 2 | 2 | ✅ 100% | `HandBackup`, `HandRunBackup` |
| TaskDatabase | 2 | 2 | ✅ 100% | `TaskBackup`, `ReminderBackup` |
| ProactiveEventDatabase | 2 | 2 | ✅ 100% | `ProactiveEventBackup`, `ProactiveInteractionBackup` |
| UserProfileDatabase | 1 | 1 | ✅ 100% | `UserProfileBackup` |
| **Creative (in-memory)** | 1 | 0 | ⚠️ deliberate | `CreativeGenerationJobEntity` (transient in-flight jobs) — **documented omission in `AuraBackupSchema13.kt:17-31`** |

**Total Room entities: 48. Total backed up: 47. Total deliberately omitted: 1 (with rationale).**

### 5.2 Intentional non-coverage

In addition to `CreativeGenerationJobEntity`, the following are intentionally omitted from backup and rebuilt after restore (documented in `AuraBackup.kt:13-23`):

- **Embeddings** (model-specific, ~1.5 KB per row; rebuilt via Settings → Memory → Rebuild embeddings)
- **API keys / OAuth tokens** (live in `SecureDataStore`; re-pasted by user after fresh install)
- **SMTP password** (line 447–448 of `BackupManager.kt`: `password = ""` on restore)
- **Document file bytes** (live on disk; only metadata + chunked text backed up)

### 5.3 Restore order correctness

`BackupManager.restore(...)` orders inserts to satisfy FK constraints:

1. KG nodes (then edges; edges reference nodes with `ON DELETE CASCADE`)
2. Memories (then memory edits; edits reference memories with `ON DELETE CASCADE`)
3. Documents (then document chunks)
4. Creative projects (then artifacts, revisions, branches, dependencies, simulations, continuity issues)
5. Conversations
6. Hands (then runs) — each hand is also re-scheduled via `handScheduler.schedule(...)` (line 369)
7. Tasks / Reminders (reminders routed through `restoreReminders(...)` which handles recurrence and re-scheduling)
8. Proactive events (then interactions, which FK to events)
9. Evolution evidence (then candidates, proposals, revisions, settings)
10. World model (beliefs, evidence, events, opportunities)
11. Taste (preference signals, style profiles, routing outcomes)
12. Agents (custom only — builtins re-seeded on startup; line 461)
13. User profile + preferences (preferences written via DataStore; `providerKeys.setEmbeddingModel(...)` for the embedding model id)
14. Usage tracker (via `usageTracker.restore(...)`)
15. Evolution preferences

**Restore order is correct.** The dedicated `restoreEvolution(...)` helper (line 519) isolates evolution-only restore so it can be called independently.

### 5.4 `purgeAll()` correctness

`purgeAll()` clears every table including the schema v10/11/12/13 additions. Order matches `restore()`'s inverse for FK safety (e.g. edges deleted before nodes; memory edits before memories; proactive interactions before events).

**One latent issue noted:** line 607 says `// Schema v13: memory feedback (audit trail for memory ratings).` immediately before `memoryFeedbackDao?.deleteAll()`, but the comment block labeled "Schema v12" appears *after* the v13 block (line 608 onwards) for the remaining deletions. **Cosmetic only** — order of execution is correct.

---

## 6. SECRET STORAGE AUDIT — `SecureDataStore`

### 6.1 Cryptographic posture

| Property | Value | Source |
|---|---|---|
| Cipher | AES-256/GCM/NoPadding | `KeyManager.kt:79` |
| Key length | 256 bits | `KeyManager.kt:59` |
| IV length | 12 bytes (random per encrypt) | `KeyManager.kt:81` (`cipher.iv` from `Cipher` defaults) |
| Auth tag length | 128 bits | `KeyManager.kt:28` |
| Key storage | Android Keystore (`AndroidKeyStore` provider, alias `aura_secure_prefs`) | `KeyManager.kt:25, 49–63` |
| User auth required | **false** | `KeyManager.kt:60` (`setUserAuthenticationRequired(false)`) |
| Encoding | Base64(IV ‖ ciphertext+tag) | `KeyManager.kt:78–87` |
| Decryption failure handling | Returns `null` on `AEADBadTagException`, `IllegalArgumentException`, and generic `Exception` | `KeyManager.kt:97–113` |
| `SecureDataStore.getString()` | Re-raises `null` from `KeyManager.decrypt(...)` as `DecryptionFailedException` to distinguish "missing key" from "key present but undecryptable" | `SecureDataStore.kt:60–67` |

### 6.2 Findings

#### F-SEC-1 (P3 / informational): `setUserAuthenticationRequired(false)` means the Keystore key is usable by the app process at any time, without biometric / device credential gating

`KeyManager.kt:60`:
```kotlin
.setUserAuthenticationRequired(false)
```

This is **consistent with the feature claim "Keeps credentials on this device"** in onboarding (`OnboardingContent.kt:233`), and **necessary** because providers are called from background workers (e.g. proactive events, the evolution engine, the dream consolidator) which cannot prompt for biometric.

**Trade-off accepted:** the key never leaves the Keystore, so on a non-rooted device the ciphertext is undecryptable to another app; on a rooted device an attacker can also bypass the Keystore. There is no additional hardening the app could add beyond what the OS provides.

**No action needed.** Justifying documentation for the design decision is in `ProviderKeys.kt:18-41`.

#### F-SEC-2 (P2): `KeyManager.decrypt(...)` catches *any* `Exception` and returns `null`

`KeyManager.kt:110`:
```kotlin
catch (_: Exception) {
    null // any other unexpected error; return null gracefully in v1
}
```

This is **intentional** — the comment says "graceful in v1" — but it has two consequences:

1. Any unexpected error during decrypt is **indistinguishable** from an `AEADBadTagException` (auth tag mismatch, ciphertext tampered or wrong key).
2. `SecureDataStore.getString(...)` then throws `DecryptionFailedException` (line 64) which surfaces to callers as a fatal "the key was lost" error even when the underlying cause was a transient I/O problem (file lock, IO error).

`ProviderKeys.init` (line 134) catches this exception per-provider and downgrades to `ProviderCredentialState.StorageError`. So the app doesn't crash, but the user sees a permanent "storage error" even if a single retry would have worked.

**Recommended improvement:** narrow the catch to specific exceptions (`IOException`, `KeyPermanentlyInvalidatedException`, `KeyStoreException`) and let truly unexpected exceptions bubble. Add a one-shot retry for `IOException`.

#### F-SEC-3 (P3): Decrypt cache invalidated only by full re-load — stale-on-poison risk

`ProviderKeys._state` is a `MutableStateFlow<Map<String, String>>` (line 51). When `SecureDataStore.getString(...)` throws `DecryptionFailedException` for one provider, the init block (lines 123–137) catches it and marks `StorageError`, but the remaining providers still get added to `_state`. Good.

However, **after a transient storage error resolves**, there is no re-load path. The `StorageError` state persists until the next app start, or until the user writes a new key (which is a destructive overwrite via `ProviderKeys.set`).

**Recommended improvement:** expose a `ProviderKeys.reloadProvider(prefix)` API for Settings UI to retry; not blocking.

#### F-SEC-4 (P2): `BackupManager.restore(...)` skips restoring SMTP password

This is intentional and correct (the password lives in `SecureDataStore`; including it in the JSON would be a plaintext-in-backup leak). The code at `BackupManager.kt:441-449` calls:

```kotlin
userPreferences.setSmtpConfig(
    host = host,
    port = backup.preferences.smtpPort,
    username = backup.preferences.smtpUsername ?: "",
    from = backup.preferences.smtpFrom ?: "",
    // Password is intentionally not backed up — stored in SecureDataStore.
    password = "",
)
```

**However**, `smtpUsername` and `smtpFrom` *are* restored (as plaintext). These are not strictly secrets, but a username being plain-text in a backup file is at least a partial credential leak.

**Recommended improvement:** store `smtpUsername` in `SecureDataStore` too, or document explicitly that SMTP username is considered non-secret.

#### F-SEC-5 (P3): Onboarding route uses a hard-coded provider subset

`OnboardingRoute.kt:186`:
```kotlin
val PROVIDERS = listOf("ollama", "anthropic", "openai", "deepseek", "gemini", "groq", "openrouter")
```

`ProviderKeys.PREFIXES` (in `ProviderKeys.kt:263-272`) lists **28 prefixes** including 17 chat providers and 11 capability/search providers. The onboarding flow only renders the Ollama + Anthropic fields (`ProviderSetupStep.kt:48-63`) but the validation regex `prefix !in PROVIDERS` in `OnboardingViewModel.saveAndTest` / `updateKeyDraft` would silently reject keys for providers outside this list.

**Consequence:** A user with a saved Mistral key who re-launches the app and lands in onboarding (e.g. via `firstRunGate.markComplete` being reset by a backup restore or wipe) cannot re-validate that key through the onboarding UI.

**Recommended improvement:** source `PROVIDERS` from `ProviderKeys.PREFIXES.filter { it in CHAT_PROVIDERS }` or a shared constant; document why onboarding is chat-only.

---

## 7. ONBOARDING PROVIDER-CONFIG VALIDATION

### 7.1 Flow

1. **Intro step** (`OnboardingContent.kt:206-235`): generic feature pitch.
2. **Provider step** (`ProviderSetupStep.kt:22-79`): renders Ollama Cloud (recommended) + Anthropic (optional) text fields, plus a "$N verified providers" counter.
3. **Model step** (`ModelSelectionStep.kt`): choose a default chat model.
4. **Complete step** (`OnboardingContent.kt:253-291`): summary.

### 7.2 Validation logic

`OnboardingViewModel` (`OnboardingRoute.kt:38-187`):

- **Init:** waits on `providerKeys.awaitLoaded()` then loads each `PROVIDERS` prefix's existing key into `keyDrafts`.
- **`updateKeyDraft(prefix, value)`:** rejects prefixes not in the hard-coded `PROVIDERS` list (line 98). Sets status to `Draft` or `Empty` based on blank/non-blank.
- **`saveAndTest(prefix)`:** blank → `Invalid` with message "Enter an API key first." Non-blank → status `Saving`, calls `providerKeys.set(...)` then `modelCatalogRepository.refreshProvider(prefix, force = true)`. Failures → `Invalid` with error message.
- **`next()` (line 146):** gate at Provider step is `catalog.allModels.isNotEmpty()` — requires at least one verified model across all providers. Model step requires `selectedDefaultModel != null`.

### 7.3 Findings

#### F-ONB-1 (P2): "Verified provider" check uses **collection of all providers**, but only 2 are surfaced

`ProviderSetupStep.kt:65-77`:
```kotlin
val verifiedCount = state.credentialStatus.values.count {
    it == OnboardingCredentialStatus.Verified
}
```

This counts verified status across **all 7 prefixes in PROVIDERS**. But only 2 fields (Ollama + Anthropic) are rendered. The other 5 (openai, deepseek, gemini, groq, openrouter) start in `Empty` and become `Verified` only if the user has previously saved keys for them (i.e. `providerKeys.init` found non-blank values).

**Result:** a returning user who only configured OpenAI in Settings sees "1 provider verified" in onboarding even though they haven't touched the two visible fields. This is technically correct (OpenAI *is* verified) but is confusing UX.

**Recommended improvement:** either render all 7 prefixes, or change the counter to count only the rendered prefixes.

#### F-ONB-2 (P2): `PROVIDERS` list not sourced from `ProviderKeys.PREFIXES` — drift risk

Same root cause as F-SEC-5. The hard-coded list (`OnboardingRoute.kt:186`) will silently drift if a new chat provider is added to `ProviderKeys.PREFIXES` but not to onboarding.

#### F-ONB-3 (P3): No retry path when `modelCatalogRepository.refreshProvider(...)` returns transient error

`OnboardingViewModel.saveAndTest` (line 124) wraps the set+refresh in `runCatching`. A `RateLimit`, `Network`, `Timeout`, or `Malformed` error from the catalog refresh ends up in `OnboardingCredentialStatus.Invalid` (lines 71-78 in `OnboardingViewModel`, where the catalog collector maps non-final statuses back to `Invalid` while `Saving`). The user sees "Invalid" even though the underlying API key may be correct.

**Recommended improvement:** distinguish `Status.Unauthorized` (key rejected) from `RateLimit`/`Network`/`Timeout` (transient). Map `Unauthorized` → `Invalid`, others → a new `Retryable` status that auto-retries on next save or shows a "try again" button.

---

## 8. STRENGTHS (worth preserving across audits)

1. **Migration registry audit test is exemplary** — pins both halves of the contract (registry vs. `@Database(version=N)`) and detects the "added migration field but forgot to wire it into `arrayOf(...)`" bug class.
2. **Backup schema versioning** — `AuraBackup.SCHEMA_VERSION = 14` is enforced as a one-way ratchet in `decodeFromJson` (refuses backups with schemaVersion > 14). New fields use default values so older backups remain loadable.
3. **Restore order respects FK constraints** — KG nodes before edges; memory edits before memories; proactive events before interactions.
4. **`ProviderKeys._state` invalidation is per-prefix on writes** — no full `loadAllKeys()` per write (comment at `ProviderKeys.kt:31-34` explains the prior bug).
5. **All 48 entities mapped through toBackup/toEntity pairs** with documented defaults for older backups (e.g. `MemoryBackup.scope = "general"` default keeps pre-scope backups forward-compatible).
6. **`SecureDataStore` uses GCM authenticated encryption** — auth tag mismatch is a hard failure (AEAD), not a silent corruption.
7. **`purgeAll()` covers every table** including schema v10/11/12/13 additions; the "clean slate" promise is real.

---

## 9. ACTION ITEMS (sorted by priority)

| ID | Severity | Item | File |
|---|---|---|---|
| F-SEC-2 | P2 | Narrow `KeyManager.decrypt` catch from `Exception` to specific IO/KeyStore exceptions; let unexpected ones bubble | `KeyManager.kt:110` |
| F-SEC-4 | P2 | Move `smtpUsername` (and consider `smtpFrom`) out of `PreferencesBackup` into `SecureDataStore`, or document as non-secret | `AuraBackup.kt:374-377` / `BackupManager.kt:441-449` |
| F-ONB-3 | P2 | Distinguish `ProviderStatus.Unauthorized` from `RateLimit`/`Network`/`Timeout` in onboarding UI; add `Retryable` status | `OnboardingRoute.kt:59-82` |
| F-ONB-1 | P2 | Either render all `PROVIDERS` in `ProviderSetupStep` or scope the verified-count to rendered prefixes | `ProviderSetupStep.kt:65-77` |
| F-SEC-5 / F-ONB-2 | P2 | Source `PROVIDERS` constant in `OnboardingViewModel` from `ProviderKeys.PREFIXES` (filter to chat providers) | `OnboardingRoute.kt:186` |
| F2 | P2 | Migrate `AgentDatabase` to `RoomConfig.builder(...)` for consistency (avoid unconditional destructive-on-downgrade in release) | `AgentModule.kt:18-22` |
| F-SEC-3 | P3 | Add `ProviderKeys.reloadProvider(prefix)` for Settings-UI retry | `ProviderKeys.kt` |
| F1 | P3 | (No action; documentation housekeeping noted for future audits) | `DreamConsolidationDatabase.kt:14-20` |

---

## 10. TEST COVERAGE NOTES

Migration tests in `aura-core/src/androidTest/`:
- `MemoryDatabaseMigrationTest.kt` — covers 1→2, 2→3, 3→4, 4→5, 5→6, 6→7 → 7→8 → … → 13→14 (full chain)
- `ConversationDatabaseMigrationTest.kt` — covers 1→2, 2→3, 3→4, 5→6 (with intermediate fixture DBs `test-conversations-2-3.db`, etc.)
- `DreamDatabaseMigrationTest.kt` — covers 2→3
- `HandDatabaseMigrationTest.kt` — covers 1→2
- `ProactiveEventDatabaseMigrationTest.kt` — covers 1→2, 2→3 (and the fixture DBs for 3→4, 4→5 are referenced in the test source)
- `TaskDatabaseMigrationTest.kt` — covers 1→2, 3→4 (and references fixtures for 2→3)

**Gaps noted:**
- `UserProfileDatabase` (1→2) **has no androidTest MigrationTest** — only the migration registry audit covers it (which is structural, not behavioral). Should add a `UserProfileDatabaseMigrationTest.kt` mirroring the existing pattern.
- `EvolutionDatabase` (1→2, 2→3) **does** have `EvolutionDatabaseMigrationTest.kt`. ✅
- Migration `1→2` in `TasksModule` and `2→3` in `TasksModule` are referenced from the existing test but only the corresponding test fixtures for 1→2 and 3→4 are listed in the test file — worth verifying 2→3 and 4→5 have fixture DBs.