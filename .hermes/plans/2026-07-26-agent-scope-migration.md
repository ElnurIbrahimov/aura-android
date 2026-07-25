# Agent Scope for World Model / Taste / Profile Tables — Implementation Plan

> **For Hermes:** Use subagent-driven-development skill to implement this plan task-by-task.

**Goal:** Add `agentScope` to 9 entity types across MemoryDatabase (v13→v14) and UserProfileDatabase (v1→v2), with scope-filtered DAO queries and agentic loop wiring, so per-agent data is isolated.

**Architecture:** Follow the existing MemoryEntity scope pattern: `"general"` for shared data, `"agent:agent_<id>"` for agent-private. Add the column via ALTER TABLE migrations (same as MIGRATION_11_12). Update DAO queries to accept optional scope filters. Wire the agentic loop to pass the active agent's scope through.

**Tech Stack:** Room 2.6.1, Kotlin 1.9.24, Hilt, mockk, kotlinx.serialization

---

## Pre-Audit: What Exists vs What's Needed

| Component | Status | Evidence |
|-----------|--------|----------|
| `MemoryEntity.scope` field | EXISTS | `MemoryEntity.kt:19` — `val scope: String = "general"` |
| `MemoryDao` scope-filtered queries | EXISTS | `MemoryDao.kt:53-79` — `byScope`, `withinScope`, `byScopes`, `searchByTextInScopes` |
| Agentic loop scope resolution | EXISTS | `MemoryAugmentedAgenticLoop.kt:406` — resolves scopes from agentId |
| `BeliefEntity.agentScope` | DOES NOT EXIST | `WorldModelEntities.kt:27-49` — no scope field |
| `EvidenceEntity.agentScope` | DOES NOT EXIST | `WorldModelEntities.kt:70-81` — no scope field |
| `WorldEventEntity.agentScope` | DOES NOT EXIST | `WorldModelEntities.kt:95-105` — no scope field |
| `OpportunityEntity.agentScope` | DOES NOT EXIST | `WorldModelEntities.kt:119-143` — no scope field |
| `PreferenceSignalEntity.agentScope` | DOES NOT EXIST | `TasteEntities.kt:23-38` — no scope field (has `projectId` but that's creative-project scope, not agent scope) |
| `StyleProfileEntity.agentScope` | DOES NOT EXIST | `TasteEntities.kt:51-60` — no scope field |
| `ReferenceIdentityEntity.agentScope` | DOES NOT EXIST | `TasteEntities.kt:82-96` — no scope field |
| `RoutingOutcomeEntity.agentScope` | DOES NOT EXIST | `TasteEntities.kt:110-120` — no scope field |
| `UserProfileEntity.agentScope` | DOES NOT EXIST | `UserProfileEntity.kt:7-14` — no scope field |
| MemoryDatabase version | 13 | `MemoryDatabase.kt:74` |
| UserProfileDatabase version | 1 | `UserProfileDatabase.kt:7` |
| `AuraBackup.SCHEMA_VERSION` | 12 | `AuraBackup.kt:77` |
| Migration pattern for ALTER TABLE add column | EXISTS | `MemoryModule.kt:535-539` — MIGRATION_11_12 adds scope to memories |
| Existing scope-filtered query pattern | EXISTS | `MemoryDao.kt:59-63` — `WHERE scope IN (:scopes)` |
| Existing test pattern | mockk + runTest | `MigrationRegistryAuditTest.kt`, `MemoryStoreTest.kt` |

---

## Task 1: Add `agentScope` column to MemoryDatabase entities (migration v13→v14)

**Objective:** Add `agentScope: String = "general"` to 8 entities in MemoryDatabase (BeliefEntity, EvidenceEntity, WorldEventEntity, OpportunityEntity, PreferenceSignalEntity, StyleProfileEntity, ReferenceIdentityEntity, RoutingOutcomeEntity) and create MIGRATION_13_14.

**Files:**
- Modify: `aura-core/src/main/kotlin/com/aura/world/WorldModelEntities.kt`
- Modify: `aura-core/src/main/kotlin/com/aura/taste/TasteEntities.kt`
- Modify: `aura-core/src/main/kotlin/com/aura/memory/MemoryDatabase.kt` (version 13→14)
- Modify: `aura-core/src/main/kotlin/com/aura/memory/MemoryModule.kt` (add MIGRATION_13_14)

**Step 1: Add `agentScope` to each entity**

Add `val agentScope: kotlin.String = "general"` as the last field before the timestamp fields on each of the 8 entities. Add `Index(value = ["agentScope"])` to each entity's indices array.

**Step 2: Create MIGRATION_13_14 in MemoryModule.kt**

```kotlin
val MIGRATION_13_14 = object : Migration(13, 14) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // Add agentScope column to all 8 tables. Default "general" so
        // existing rows are visible to all agents (backward compatible).
        db.execSQL("ALTER TABLE beliefs ADD COLUMN agentScope TEXT NOT NULL DEFAULT 'general'")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_beliefs_agentScope ON beliefs(agentScope)")
        db.execSQL("ALTER TABLE evidence ADD COLUMN agentScope TEXT NOT NULL DEFAULT 'general'")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_evidence_agentScope ON evidence(agentScope)")
        db.execSQL("ALTER TABLE world_events ADD COLUMN agentScope TEXT NOT NULL DEFAULT 'general'")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_world_events_agentScope ON world_events(agentScope)")
        db.execSQL("ALTER TABLE opportunities ADD COLUMN agentScope TEXT NOT NULL DEFAULT 'general'")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_opportunities_agentScope ON opportunities(agentScope)")
        db.execSQL("ALTER TABLE preference_signals ADD COLUMN agentScope TEXT NOT NULL DEFAULT 'general'")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_preference_signals_agentScope ON preference_signals(agentScope)")
        db.execSQL("ALTER TABLE style_profiles ADD COLUMN agentScope TEXT NOT NULL DEFAULT 'general'")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_style_profiles_agentScope ON style_profiles(agentScope)")
        db.execSQL("ALTER TABLE reference_identities ADD COLUMN agentScope TEXT NOT NULL DEFAULT 'general'")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_reference_identities_agentScope ON reference_identities(agentScope)")
        db.execSQL("ALTER TABLE routing_outcomes ADD COLUMN agentScope TEXT NOT NULL DEFAULT 'general'")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_routing_outcomes_agentScope ON routing_outcomes(agentScope)")
    }
}
```

Add MIGRATION_13_14 to the migrations array in `provideDatabase`.

**Step 3: Bump MemoryDatabase version to 14**

Change `version = 13` to `version = 14` in `MemoryDatabase.kt`.

**Step 4: Verify compile**

Run: `./gradlew :aura-core:compileDebugKotlin --no-daemon`
Expected: BUILD SUCCESSFUL

**Step 5: Commit**

```bash
git add -A
git commit -m "feat(scope): add agentScope column to 8 world model + taste entities (MemoryDB v13→v14)"
```

---

## Task 2: Add `agentScope` to UserProfileEntity (migration v1→v2)

**Objective:** Add `agentScope` to UserProfileEntity and create the first migration for UserProfileDatabase.

**Files:**
- Modify: `aura-core/src/main/kotlin/com/aura/profile/UserProfileEntity.kt`
- Modify: `aura-core/src/main/kotlin/com/aura/profile/UserProfileDatabase.kt` (version 1→2)
- Modify: `aura-core/src/main/kotlin/com/aura/profile/UserProfileModule.kt` (add MIGRATION_1_2)

**Step 1: Add `agentScope` to UserProfileEntity**

```kotlin
data class UserProfileEntity(
    @PrimaryKey val id: Int = 1,
    val agentScope: String = "general",
    val name: String? = null,
    val traitsJson: String = "[]",
    val preferencesJson: String = "{}",
    val factsJson: String = "[]",
    val lastUpdated: Long = 0L,
)
```

**Step 2: Bump version and add migration**

```kotlin
@Database(entities = [UserProfileEntity::class], version = 2, exportSchema = true)
```

```kotlin
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE user_profile ADD COLUMN agentScope TEXT NOT NULL DEFAULT 'general'")
    }
}
```

Wire into `RoomConfig.builder` migrations array.

**Step 3: Verify compile + commit**

```bash
git commit -m "feat(scope): add agentScope to UserProfileEntity (UserProfileDB v1→v2)"
```

---

## Task 3: Add scope-filtered DAO queries

**Objective:** Add scope-filtered query methods to all 6 DAO interfaces (BeliefDao, EvidenceDao, WorldEventDao, OpportunityDao, PreferenceSignalDao, StyleProfileDao) following the MemoryDao pattern (`WHERE agentScope IN (:scopes)`).

**Files:**
- Modify: `aura-core/src/main/kotlin/com/aura/world/WorldModelDaos.kt`
- Modify: `aura-core/src/main/kotlin/com/aura/taste/TasteDaos.kt`

**Step 1: Add scope-filtered queries to each DAO**

For each DAO, add a scope-filtered variant of the main read query. Pattern:

```kotlin
// BeliefDao
@Query("SELECT * FROM beliefs WHERE agentScope IN (:scopes) AND status = 'active' ORDER BY updatedAt DESC LIMIT :limit")
suspend fun allActiveInScopes(scopes: List<String>, limit: Int = 200): List<BeliefEntity>

// EvidenceDao
@Query("SELECT * FROM evidence WHERE agentScope IN (:scopes) ORDER BY timestamp ASC")
suspend fun allForBackupInScopes(scopes: List<String>): List<EvidenceEntity>

// WorldEventDao
@Query("SELECT * FROM world_events WHERE agentScope IN (:scopes) AND consumed = 0 ORDER BY timestamp DESC LIMIT :limit")
suspend fun unconsumedInScopes(scopes: List<String>, limit: Int = 100): List<WorldEventEntity>

// OpportunityDao
@Query("SELECT * FROM opportunities WHERE agentScope IN (:scopes) AND status = 'proposed' ORDER BY urgency DESC, benefit DESC LIMIT :limit")
fun observeProposedInScopes(scopes: List<String>, limit: Int = 50): Flow<List<OpportunityEntity>>

// PreferenceSignalDao
@Query("SELECT * FROM preference_signals WHERE agentScope IN (:scopes) ORDER BY createdAt DESC LIMIT :limit")
suspend fun forScopes(scopes: List<String>, limit: Int = 500): List<PreferenceSignalEntity>

// StyleProfileDao
@Query("SELECT * FROM style_profiles WHERE agentScope IN (:scopes) ORDER BY updatedAt DESC LIMIT 1")
suspend fun forScopes(scopes: List<String>): StyleProfileEntity?
```

**Step 2: Verify compile + commit**

```bash
git commit -m "feat(scope): add scope-filtered DAO queries to world model + taste DAOs"
```

---

## Task 4: Wire agentic loop to pass agentScope to world model + taste stores

**Objective:** Update the agentic loop and the stores that read/write world model and taste data to pass the active agent's scope through, following the same pattern already used for MemoryStore.

**Files:**
- Modify: `aura-core/src/main/kotlin/com/aura/agent/MemoryAugmentedAgenticLoop.kt` (pass agentScope to belief/taste queries)
- Modify: `aura-core/src/main/kotlin/com/aura/taste/TasteEngine.kt` (accept scope parameter in recomputeProfile + getTasteContext)
- Read and update any store that queries beliefs/evidence/worldEvents/opportunities/preferences/styleProfiles to accept a scope parameter

**Step 1: Update TasteEngine to accept scopes**

Add `scopes: List<String> = listOf("general")` parameter to `recomputeProfile()` and `getTasteContext()`. Use `signalDao.forScopes(scopes)` instead of `signalDao.global(500)`.

**Step 2: Update agentic loop to resolve and pass scopes**

The loop already resolves `scopes` at line 406 for memory. Extend it to pass the same scopes to `TasteEngine.getTasteContext()` when building the system prompt, and to any belief/world-event queries in the loop.

**Step 3: Verify compile + commit**

```bash
git commit -m "feat(scope): wire agentic loop to pass agentScope to taste + world model queries"
```

---

## Task 5: Update backup to preserve agentScope

**Objective:** Ensure `agentScope` survives backup/restore roundtrip.

**Files:**
- Modify: `aura-core/src/main/kotlin/com/aura/backup/AuraBackup.kt` (add agentScope to backup data classes if not auto-included)
- Modify: `aura-core/src/main/kotlin/com/aura/backup/BackupManager.kt` (verify toEntity mappers preserve agentScope)
- Verify: AuraBackup.SCHEMA_VERSION may need bump to 13 if backup data classes are explicitly fielded

**Step 1: Verify backup data classes include agentScope**

Since the backup data classes use `@Serializable` and the entities have `agentScope` as a field, the backup data classes need the field too. Check each `*Backup` data class in `AuraBackup.kt` and add `val agentScope: String = "general"` if missing.

**Step 2: Bump SCHEMA_VERSION if needed**

If the backup data class shape changes (new field), bump `SCHEMA_VERSION` from 12 to 13.

**Step 3: Verify roundtrip test + commit**

```bash
git commit -m "feat(scope): preserve agentScope across backup/restore (schema v12→v13)"
```

---

## Task 6: Migration tests

**Objective:** Add migration tests for MIGRATION_13_14 (MemoryDB) and MIGRATION_1_2 (UserProfileDB).

**Files:**
- Create: `aura-core/src/androidTest/kotlin/com/aura/memory/MemoryDatabaseAgentScopeMigrationTest.kt`
- Create: `aura-core/src/androidTest/kotlin/com/aura/profile/UserProfileAgentScopeMigrationTest.kt`

**Step 1: Write migration tests following the existing pattern in `MemoryDatabaseMigrationTest.kt`**

Each test:
1. Creates the DB at the old version
2. Inserts a row without agentScope
3. Runs the migration
4. Verifies the row now has `agentScope = "general"`
5. Verifies a new row with a custom agentScope can be inserted and queried

**Step 2: Run tests + commit**

```bash
git commit -m "test(scope): migration tests for agentScope (MemoryDB v13→v14, UserProfileDB v1→v2)"
```

---

## Task 7: Full gate verification + final commit

**Objective:** Run the full test suite and assembleDebug to confirm zero regressions.

**Step 1: Run full gate**

```bash
./gradlew :aura-core:testDebugUnitTest :app:testDebugUnitTest :app:assembleDebug --no-daemon --console=plain
```

Expected: BUILD SUCCESSFUL, 0 failures.

**Step 2: Verify migration registry test passes**

The `MigrationRegistryAuditTest` scans migration arrays. Adding MIGRATION_13_14 requires updating the test's expected version count.

**Step 3: Final commit if any test fixes needed**

```bash
git commit -m "test(scope): fix migration registry test for v14"
```

---

## Notes

### Design decisions

1. **`agentScope` not `agentId`** — follows the existing `MemoryEntity.scope` pattern. The value is `"general"` for shared, `"agent:agent_<id>"` for agent-private. This allows future scope types without schema changes.

2. **Default `"general"`** — all existing rows get `"general"` on migration, so they remain visible to all agents. This is backward compatible — no data is hidden after the upgrade.

3. **`IN (:scopes)` query pattern** — queries accept a list of scopes (typically `["general", "agent:agent_researcher"]`) so agents see both shared and private data. Same pattern as `MemoryDao.byScopes()`.

4. **UserProfileEntity is special** — it's a single-row table (`@PrimaryKey val id: Int = 1`). Adding `agentScope` to it means the profile can be per-agent. The migration adds the column with default `"general"`, and the existing row stays at id=1 with `"general"` scope. New agents would get rows with `id = agentId.hashCode()` and their own scope.

5. **RoutingOutcomeEntity** — routing outcomes are global performance data, not agent-specific. Adding `agentScope` is technically correct (future feature: per-agent model routing), but the immediate value is low. The column is added for schema completeness but queries may not filter by scope initially.

### What's NOT in this plan

- **UI changes** — no Settings UI or agent editor changes. The scope filtering happens transparently in the agentic loop and store layer.
- **ReferenceIdentityEntity scope filtering** — reference identities are tied to creative projects via `projectId` with a FK. Adding `agentScope` is for schema completeness; queries continue to filter by `projectId`.
- **EvidenceEntity scope filtering** — evidence is tied to beliefs via FK. The scope filter is on the belief, not the evidence. The column is added for future use.