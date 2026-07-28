# Deep Review Fix Plan — 2026-07-29

Comprehensive fix plan for findings from the deep structural review of
Aura Android v0.38.0 (branch `feat/tier-1-friction`, 1,423 tests).

## Phase 0: Baseline (pre-fix verification)

- [x] Test suite green: 1,148 aura-core + 275 app = 1,423 tests, 0 failures
- [x] assembleDebug green
- [x] Working tree clean

## Phase 1: Release hygiene (P0)

### 1.1 Create v0.38.0 GitHub Release
- Build fresh APK: `./gradlew :app:assembleDebug`
- Copy to `releases/aura-debug-v0.38.0.apk`
- Write release notes: `releases/RELEASE_NOTES_v0.38.0.md`
- Create GitHub Release via `gh release create v0.38.0`
- This covers v0.37.0 changes too (reflection + strategy bandit + LLM profile extraction)
- Also create v0.37.0 release tag pointing at the v0.37.0 commit

### 1.2 Fix README stale numbers
- Update version: v0.36.0 → v0.38.0
- Update versionCode: 41 → 43
- Update test count: 1,596 → 1,423
- Update test files: 239 → 253
- Update tool count: 59 → 62 (delegate_to_agent + run_council + schedule_task added)
- Update backup schema: v12 → v14
- Verify every number against actual source

## Phase 2: Backup completeness (P1)

### 2.1 Add CreativeGenerationJobEntity to backup
- Create `CreativeGenerationJobBackup` data class in AuraBackup.kt
- Add `toBackup()` mapper on CreativeGenerationJobEntity
- Add `toEntity()` mapper
- Add DAO methods: `allForBackup()`, `insertAll()`, `purgeAll()`
- Wire into BackupManager.snapshot() and BackupManager.restore()
- Bump SCHEMA_VERSION 14 → 15
- Add migration test for schema v15

### 2.2 Add StrategyBanditEntity to backup
- Create `StrategyBanditBackup` data class in AuraBackup.kt
- Add `toBackup()` mapper on StrategyBanditEntity
- Add `toEntity()` mapper
- Add DAO methods: `allForBackup()`, `insertAll()`, `purgeAll()`
- Wire into BackupManager.snapshot() and BackupManager.restore()
- Include in schema v15 bump from 2.1
- Add StrategyBanditDao backup methods

## Phase 3: Silent error logging (P1)

### 3.1 Add logging to EvolutionRollbackManager silent runCatching
- 10 silent sites need `.onFailure { Log.w(...) }` 
- File: `aura-core/.../evolution/EvolutionRollbackManager.kt`
- Lines: 60, 70, 78, 86, 95, 112, 151, 187, 218, 238

### 3.2 Add logging to EvolutionApplySaga silent runCatching
- 6 silent sites need `.onFailure { Log.w(...) }`
- File: `aura-core/.../evolution/EvolutionApplySaga.kt`
- Lines: 43, 73, 86, 104, 187, 224

## Phase 4: Documentation honesty (P2)

### 4.1 Note DreamConsolidator Phase 6 stub status in README
- Add parenthetical: "Phase 6 (profile extraction from dreams) is a timestamp-only stub; full LLM-driven extraction is future work"

## Phase 5: Production pipeline tests (P2)

### 5.1 Add ProductionPipelineEngine tests
- Test the 6 pipeline types resolve correct tools
- Test dependency chaining between stages
- Test stage-to-tool mapping
- Test invalid pipeline type handling
- File: `aura-core/src/test/.../pipeline/ProductionPipelineEngineTest.kt`

## Phase 6: Navigation cleanup (P2)

### 6.1 Fix agent_editor empty agentId navigation
- Settings navigates to `"agent_editor?agentId="` (empty)
- Change to `"agent_editor"` (no query param, lets defaultValue=null take effect)
- Or change to handle the empty string properly in AgentEditorScreen

## Phase 7: Final verification

- Full test suite: `./gradlew :aura-core:testDebugUnitTest :app:testDebugUnitTest`
- assembleDebug + lintDebug
- GitHub Release v0.38.0 with APK
- Push all commits
