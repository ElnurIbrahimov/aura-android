# Phase 15 — Evolution Loops: Skill, Memory, Proactive

**Goal:** give Aura Android its own inner Hermes-style learning loop. After every meaningful agent run, sleep cycle, and proactive event, the system reflects on what happened, proposes durable improvements, and asks the user before applying them. No autonomous self-modification.

**Scope:** three reinforcing subsystems.

1. **Skill Evolution** — detect missing hands, specialist prompt gaps, capability mis-routing, and tool failure patterns; propose declarative fixes; apply only on user approval.
2. **Memory Evolution** — nightly sleep-cycle consolidation of fading/related memories into higher-level beliefs, episodic timelines, and negative-memory guards.
3. **Proactive Evolution** — record how the user reacts to every proactive nudge; learn which proactive patterns help and which annoy; suggest new patterns from memory/calendar/creative usage signals.

**Principles**
- Propose, don't mutate. All changes start as pending proposals with evidence.
- Human approval is the write gate. The assistant can prepare diffs; the user taps approve, reject, or edit.
- No code generation. We generate JSON configuration (hand steps, specialist override map, capability prefs) only.
- Local-first. Reflection can use cheap local/edge models; no cloud dependency for the learning loop.
- Reversible. Every applied change is logged with a rollback path.

---

## Depends On

- Phase 1.3 `PolicyEngine`, Phase 1.5 `TraceSink`, Phase 6 `AgentRunStore`, Phase 11 `SubagentManager`.
- Phase 12.4 `TasteEngine` (preference signals + routing outcomes).
- Existing `UserPreferences.specialistOverrides`, `HandRepository`, `CapabilityRouter`, `MemoryStore`, `ProactiveEventDatabase`, `MemoryDatabase` v11.

---

## Phase 0 — Baseline, Contracts, and Skeleton

**What:** lock the structural invariants before adding the new DB and domain layers. Write the architecture note and a contract test.

**Files**
- `aura-core/src/main/kotlin/com/aura/evolution/EvolutionManifest.kt` — public constants: max proposals kept, confidence threshold, consolidation decay threshold.
- `aura-core/src/test/kotlin/com/aura/evolution/EvolutionContractTest.kt` — asserts:
  - `SkillProposalKind.values()` is non-empty.
  - `EvolutionDatabase` schema version is 1.
  - `EvolutionStore` exposes pending/approved/rejected counts.

**Verification**
- `:aura-core:testDebugUnitTest --tests 'com.aura.evolution.EvolutionContractTest'` passes.

---

## Phase 1 — Evolution Database + Entities + DAOs

**What:** create a new Room database `EvolutionDatabase` (v1) so the learning loop tables live beside, not inside, the already-crowded `MemoryDatabase`.

**Entities**
- `SkillProposalEntity`
  - `id: String @PrimaryKey`
  - `kind: String` — `NEW_HAND`, `SPECIALIST_OVERRIDE`, `CAPABILITY_PREF`, `TOOL_PARAM`
  - `title: String`, `description: String`
  - `evidenceJson: String` — serialized list of `{runId, eventType, reason}`
  - `proposedJson: String` — the actual payload (hand JSON, override prompt, capability preference, tool param)
  - `confidence: Float` 0..1
  - `status: String` — `pending`, `approved`, `rejected`, `applied`
  - `createdAt: Long`, `decidedAt: Long?`, `appliedPayloadId: String?`
- `MemoryConsolidationEntity`
  - `id: String @PrimaryKey`
  - `status: String` — `pending`, `approved`, `archived`
  - `sourceMemoryIdsJson: String`
  - `proposedBeliefSubject/Predicate/Object: String`
  - `proposedBeliefConfidence: Float`
  - `rationale: String`
  - `createdAt: Long`, `decidedAt: Long?`
- `ProactiveOutcomeEntity`
  - `id: String @PrimaryKey`
  - `eventType: String`, `eventId: String?`
  - `outcome: String` — `dismissed`, `tapped`, `snoozed`, `acted`, `disabled`
  - `contextJson: String` — hour-of-day, day-of-week, recent memory count, project id
  - `timestamp: Long`
- `ProactivePolicyEntity`
  - `eventType: String @PrimaryKey`
  - `enabled: Boolean`
  - `score: Float` — weighted success score
  - `sampleCount: Int`
  - `lastUpdated: Long`
- `SleepCycleLogEntity` — one row per run for debugging.

**DAOs**
- `SkillProposalDao`, `MemoryConsolidationDao`, `ProactiveOutcomeDao`, `ProactivePolicyDao`, `SleepCycleLogDao`.

**Database + Module**
- `aura-core/src/main/kotlin/com/aura/evolution/EvolutionDatabase.kt`
- `aura-core/src/main/kotlin/com/aura/evolution/EvolutionModule.kt` — Hilt module providing DAOs and `EvolutionDatabase`.

**Migration**
- v1 is a fresh DB, no migration needed.

**Verification**
- `:aura-core:compileDebugKotlin` green.
- `:aura-core:testDebugUnitTest --tests 'com.aura.evolution.EvolutionContractTest'` updated to assert DAO counts after insert.

---

## Phase 2 — Skill Evolution Engine

**What:** after agent runs complete, run a reflection pass that proposes skill-level improvements.

**Domain Store**
- `aura-core/src/main/kotlin/com/aura/evolution/skill/SkillEvolutionEngine.kt`
  - `reflectOn(runId: String, traces: List<AgentTraceEvent>): List<SkillProposal>`
  - Detection rules (heuristic first; later a cheap LLM call):
    1. **Missing hand** — run failed with `tool_not_found` or `hand_disabled`; user query contained a known trigger phrase; propose a hand that wraps the same tool sequence.
    2. **Specialist override** — a specialist's suggested tool list was ignored by the user N times; propose an override to its description/tools.
    3. **Capability mis-route** — a capability call failed N times on provider X but succeeded on fallback Y; propose a capability preference change.
    4. **Repeated user edit** — same artifact category edited repeatedly; propose a `StyleProfile` tweak or a new creative hand.
- `SkillEvolutionStore.kt` — persists proposals, loads pending, applies approved ones by writing to the correct downstream store.

**Tools**
- `propose_skill` — returns pending proposals for a run.
- `apply_skill_proposal` — user-facing tool; marks proposal applied and writes the config.
- `reject_skill_proposal` — marks rejected.

**Integration**
- Hook into `AgentRunStore.finalizeRun()` (or add a `RunCompleted` trace event consumer):
  - If run failed or user edited output, queue a `SkillReflectionWorker` via WorkManager (deferred 5 minutes so runs complete first).
- `SkillReflectionWorker` uses `SubagentManager.spawn()` for the heavy reflection step; it runs a small model call only when network is available, otherwise uses heuristic rules.

**Safety**
- Proposals never write to `HandRepository` or `UserPreferences` until `SkillEvolutionStore.apply()` is called from UI approval.
- `proposedJson` is validated against JSON schemas before persistence.

**Tests**
- `SkillEvolutionEngineTest`: missing-hand detection, repeated-edit detection.
- `SkillEvolutionStoreTest`: apply proposal writes a real hand, reject proposal updates status.

**Verification**
- Unit tests pass; `:app:compileDebugKotlin` green (tools registered in `ToolsModule`).

---

## Phase 3 — Memory Evolution Engine

**What:** a nightly `SleepCycleWorker` that clusters, consolidates, and extracts beliefs/episodes from memories.

**Domain Store**
- `aura-core/src/main/kotlin/com/aura/evolution/memory/MemoryEvolutionEngine.kt`
  - `runSleepCycle(limit: Int = 50): SleepCycleResult`
  - Steps:
    1. Load candidate memories: `decayedBelow(0.5f, limit)` + `recentSince(24h ago, limit)`.
    2. Cluster by `(category, tags, semantic similarity > 0.85)`.
    3. For each cluster with ≥3 members, ask a cheap local model: "summarize these observations into one belief" → `ConsolidationProposal`.
    4. For each conversation with ≥3 stored memories in the same session, create an `EpisodeSummary` (write to `MemoryEntity` with category `episode_summary`).
  - `MemoryEvolutionStore.kt` — persist proposals; on approval, write the belief to `BeliefDao`/`EvidenceDao`, archive raw memories (set category to `archive`), record `MemoryEditEntity` trail.

**Belief integration**
- Reuse existing `BeliefDao` and `EvidenceDao` from the world model.
- If a generated belief contradicts an existing active belief (same subject+predicate), mark old as `supersededBy` and insert new.

**Negative memory**
- Add `MemoryEntity.category = "rejection"` for explicit user rejections (e.g. user says "don't suggest that again"). The recall layer filters `rejection` from normal `query()` but `MemoryEvolutionEngine` uses it to suppress similar future proposals.

**Worker**
- `SleepCycleWorker` — `CoroutineWorker`, runs when charging + idle (use `Constraints.Builder().setRequiresCharging(true).setRequiresDeviceIdle(true)`). Schedules every 24h.
- `ProactiveScheduler.scheduleSleepCycle()` / `cancelSleepCycle()`.

**UI hook**
- `MemoryConsolidationScreen` lists pending proposals with source memory previews.

**Tests**
- `MemoryEvolutionEngineTest`: clustering deterministic, belief generation mocked.
- `SleepCycleWorkerTest`: worker returns success, creates proposal rows.

**Verification**
- `:aura-core:testDebugUnitTest` green; worker scheduling verified in `ProactiveBootstrapTest`.

---

## Phase 4 — Proactive Evolution Engine

**What:** learn which proactive nudges the user actually values.

**Tracking**
- Modify `ProactiveEventEntity` to add `outcome: String?` (nullable, default null).
- Update `ProactiveHistoryScreen` / notification action handlers to record outcome:
  - `dismissed` — user swipes away notification or taps Dismiss.
  - `tapped` — user taps notification body.
  - `snoozed` — user taps Snooze action.
  - `acted` — user taps a deep-link action (open calendar/memory/chat).
  - `disabled` — user turns off the originating toggle in Settings.
- Each outcome writes a `ProactiveOutcomeEntity`.

**Policy engine**
- `aura-core/src/main/kotlin/com/aura/evolution/proactive/ProactiveEvolutionEngine.kt`
  - `recomputePolicies()` groups outcomes by `eventType`, computes a weighted score:
    - `acted` +1.0, `tapped` +0.5, `snoozed` 0.0, `dismissed` -0.3, `disabled` -2.0.
    - Score = weighted sum / sample count, clamped to [-1, 1].
  - If score < -0.5 and samples ≥ 5, set `ProactivePolicyEntity.enabled = false` and surface a Settings warning.
  - If score > 0.3 and samples ≥ 5, promote the event type to "high confidence" and allow more frequent scheduling.
- `ProactivePolicyStore.kt` — read/write policies; expose `shouldRun(eventType): Boolean`.

**New proactive pattern suggestions**
- `ProactiveEvolutionEngine.suggestNewPatterns()` scans recent memory + creative project usage for correlations:
  - User opens creative project after 22:00 and stays >5 min → suggest `late_night_focus_brief`.
  - User asks about deadlines on Mondays → suggest `monday_deadline_scan`.
- Suggestions become `SkillProposalEntity` of kind `NEW_PROACTIVE_PATTERN` and go through the same approval UI.

**Worker**
- `ProactivePolicyWorker` — runs every 12h; calls `recomputePolicies()` and `suggestNewPatterns()`.

**Tests**
- `ProactiveEvolutionEngineTest`: score math, disable threshold, promote threshold.
- `ProactivePolicyStoreTest`: policy persistence gates scheduling.

**Verification**
- `:aura-core:testDebugUnitTest` green.

---

## Phase 5 — UI Wiring

**What:** make the learning loop visible and controllable.

**New screens**
- `app/src/main/kotlin/com/aura/ui/screens/evolution/EvolutionHubScreen.kt` — three cards: Skill Proposals, Memory Consolidations, Proactive Insights; counts from stores.
- `SkillProposalsScreen.kt` — LazyColumn of pending proposals with Approve/Reject/Edit buttons; shows evidence count and confidence.
- `MemoryConsolidationsScreen.kt` — shows source memory chips + proposed belief; Approve/Archive/Reject.
- `ProactiveInsightsScreen.kt` — bar chart or list of event types with scores, samples, and a toggle to re-enable disabled patterns.

**ViewModels**
- `EvolutionHubViewModel.kt`, `SkillProposalsViewModel.kt`, `MemoryConsolidationsViewModel.kt`, `ProactiveInsightsViewModel.kt`.

**Navigation**
- Add routes: `evolution_hub`, `skill_proposals`, `memory_consolidations`, `proactive_insights`.
- Add a "Evolution" icon to the home secondary actions and/or Settings section.
- Add a badge on the Evolution icon when pending proposals > 0.

**Tests**
- ViewModel tests with mocked stores; approve/reject state updates.

**Verification**
- `:app:compileDebugKotlin` green; lint green.

---

## Phase 6 — Integration, Safety, and Rollback

**What:** wire the engines into the rest of the app without breaking existing flows.

**Integration points**
- `AgentRunStore.finalizeRun()` emits a one-shot event consumed by `SkillReflectionWorker` enqueue.
- `MorningBriefWorker` records its own outcome via `ProactiveOutcomeRecorder` when the user taps/dismisses.
- `AuraApp.onCreate()` calls `ProactiveScheduler.scheduleSleepCycle()` and `ProactiveScheduler.schedulePolicyUpdate()` alongside existing scheduling.
- `SettingsScreen` gets new toggles:
  - "Learn from agent runs" (Skill Evolution)
  - "Nightly memory consolidation" (Memory Evolution)
  - "Learn from proactive feedback" (Proactive Evolution)
- Default all three to `true` for fresh installs.

**Safety / rollback**
- `EvolutionRollbackManager` — for each applied proposal, stores the previous state JSON. User can tap "Undo" on a proposal card; manager restores old specialist override / disables the new hand / re-enables old proactive policy.
- No autonomous changes to `ToolsModule`, Hilt modules, provider code, or model IDs. All changes are data-only.

**Tests**
- `EvolutionRollbackManagerTest`: undo a specialist override, undo a new hand, undo a proactive disable.
- End-to-end: run an agent run, see a skill proposal appear, approve it, verify hand exists, undo, verify hand disabled.

**Verification**
- `:aura-core:testDebugUnitTest :app:testDebugUnitTest` green.

---

## Phase 7 — Verification, Polishing, Docs

**What:** run the full gate and write release notes.

**Checklist**
- [ ] `:aura-core:testDebugUnitTest` — all tests pass.
- [ ] `:app:testDebugUnitTest` — all tests pass.
- [ ] `:app:lintDebug` — no new warnings.
- [ ] `:app:assembleDebug` — APK builds.
- [ ] Room schema exported for `EvolutionDatabase` v1 under `aura-core/schemas/`.
- [ ] No hardcoded model IDs in new reflection/consolidation code.
- [ ] No media blobs stored in Room.
- [ ] DataStore keys for toggles documented in `UserPreferences.kt` KDoc.

**Files**
- `.hermes/plans/2026-07-17-phase-15-evolution-loops.md` (this plan) committed.
- `docs/EVOLUTION_LOOPS.md` — user-facing explanation of how Aura learns.
- Update `README.md` if it has a features list.

---

## Phase 8 — Commit Table and Push

Plan to ship in **10–12 atomic commits** on `feat/tier-1-friction`:

| # | Commit | Scope | Verification |
|---|--------|-------|--------------|
| 1 | `feat(evolution): phase 15 baseline + contract tests` | Manifest + contract test | unit tests |
| 2 | `feat(evolution): EvolutionDatabase v1 + entities + DAOs` | DB + module | compile, contract tests |
| 3 | `feat(evolution): SkillEvolutionEngine detection rules` | Engine + store | unit tests |
| 4 | `feat(evolution): propose_skill / apply_skill_proposal tools` | Tools + registration | compile, ToolsModuleSanityTest |
| 5 | `feat(evolution): SkillReflectionWorker + AgentRun hook` | Worker + integration | worker test |
| 6 | `feat(evolution): MemoryEvolutionEngine + SleepCycleWorker` | Consolidation + worker | unit tests |
| 7 | `feat(evolution): ProactiveEvolutionEngine + policy worker` | Outcome tracking + policy | unit tests |
| 8 | `feat(evolution): UI screens and ViewModels` | Compose screens | compile, lint |
| 9 | `feat(evolution): navigation + home/settings wiring` | NavGraph + Settings | assembleDebug |
| 10 | `feat(evolution): rollback manager + safety guards` | Undo + safety | unit tests |
| 11 | `docs(evolution): user docs and plan` | Docs | n/a |
| 12 | `chore(release): phase 15 release notes + apk` | APK + tag | full gate |

**Estimated effort:** 18–26 hours of implementation + verification.

**Risk / mitigation**
- **Risk:** reflection/consolidation LLM calls cost money. **Mitigation:** heuristic rules run offline; cheap local/edge models only used when configured; can be disabled by toggle.
- **Risk:** bad proposal quality annoys user. **Mitigation:** high confidence threshold (0.7) before showing; user can disable each loop independently.
- **Risk:** DB migration mistakes. **Mitigation:** new `EvolutionDatabase` v1, no migration of existing tables.

---

## Anti-Features (deliberate non-goals)

- No automatic code changes to Aura's own source.
- No autonomous tool registration without user approval.
- No cloud training of a personal model; all learning is local/statistical.
- No retroactive deletion of existing memories without explicit user approval.
