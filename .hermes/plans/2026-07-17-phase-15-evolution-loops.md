# Phase 15 — Aura Evolution System

## Skill Evolution + Memory Evolution + Proactive Evolution

**Status:** implementation-ready master plan; replaces the earlier shallow draft in this same file
**Scope:** planning only — no production code is changed by this document
**Target repository:** `D:\aura-android-clean`
**Target branch:** `feat/tier-1-friction`
**Target platform:** personal-use Android phone app
**Inference rule:** synthesis uses only cloud providers explicitly configured inside Aura; the phone performs deterministic Kotlin processing, and model IDs are never hardcoded

---

## 1. Executive decision

Build one shared **Evolution Kernel** with three independently switchable loops:

1. **Skill Evolution** improves Aura's real Markdown skills, not merely Hands. It can propose creating, patching, merging, retiring, or promoting a skill into a deterministic Hand. It can also propose specialist-prompt/tool-policy changes when those are the correct target.
2. **Memory Evolution** turns raw memories into evidence-backed beliefs, resolves contradictions, builds episodic summaries, learns from recall feedback, and keeps fictional/project canon separate from facts about the user.
3. **Proactive Evolution** learns whether a nudge was helpful, when it should arrive, how often it should repeat, and which new proactive rules are worth proposing.

All three loops share:

- one durable proposal lifecycle;
- one approval inbox;
- one cloud-model gateway;
- one privacy/redaction boundary;
- one cost and frequency budget;
- one reversible application/rollback mechanism;
- one evaluation framework;
- one audit trail.

The system is **not model training** and is **not source-code self-modification**. It is controlled evolution of user-owned configuration and knowledge:

- skill Markdown and skill metadata;
- Hand definitions;
- specialist prompt/tool overrides;
- evidence-backed beliefs and profile projections;
- proactive policies and approved proactive rules;
- model-role preferences only when the user approves them.

---

## 2. Why the previous plan was insufficient

The rejected draft had six structural problems:

1. It treated Hands and specialist overrides as "skills" even though Aura already has a real `Skill` domain, `SkillsStore`, `UseSkillTool`, `/use_skill`, Skills UI, and tests.
2. It invented a vague "reflection agent" without specifying the call path, model resolution, prompt contract, output validation, retry behavior, or phone lifecycle.
3. It did not distinguish raw memory, derived belief, user profile, creative canon, and project-scoped knowledge.
4. It reduced proactive learning to a simple score and auto-disable rule instead of modeling exposure, action, timing, interruption burden, confidence, and new-rule approval.
5. It had no staged rollout, shadow mode, measurable baseline, or proof that any evolution actually improved Aura.
6. It did not solve cross-store atomicity, process death, stale diffs, prompt injection, cloud cost, backup/restore, or rollback conflicts.

This revision closes all six.

---

## 3. Verified current baseline

The plan is based on live source inspection, not a remembered inventory.

### 3.1 Existing skill system

- `aura-core/src/main/kotlin/com/aura/skills/Skill.kt`
  - real user-authored Markdown skill;
  - fields: `id`, `name`, `description`, `body`, `createdAt`, `updatedAt`;
  - no revision history, provenance, usage metrics, activation rules, or tests.
- `aura-core/src/main/kotlin/com/aura/skills/SkillsStore.kt`
  - persists the complete skill list as one JSON envelope in `SecureDataStore`;
  - supports add/update/remove/find;
  - is not transactional with any other store and cannot retain revisions.
- `aura-core/src/main/kotlin/com/aura/tools/UseSkillTool.kt`
  - resolves a skill by name and returns raw Markdown to the next agent turn;
  - does not record invocation, revision, outcome, or correction.
- `app/src/main/kotlin/com/aura/ui/screens/skills/SkillsScreen.kt`
  - CRUD surface exists.
- `app/src/main/kotlin/com/aura/ui/viewmodel/SkillsViewModel.kt`
  - exposes add/update/remove only.
- `app/src/main/kotlin/com/aura/ui/screens/chat/ChatRoute.kt`
  - inserts `/use_skill <name>` into the composer.

### 3.2 Existing automation and specialist system

- `Hand` and `HandRun` already provide deterministic, schedulable tool workflows.
- `Specialist` already provides prompt and tool-allowlist specialization.
- `UserPreferences.specialistOverrides` and `specialistToolOverrides` already persist user overrides.
- These are valid **promotion targets** for evolved knowledge, but they are not substitutes for the actual skill system.

### 3.3 Existing memory and world-model system

- `MemoryStore` provides gated writes, exact and semantic deduplication, text/vector retrieval, six-signal RRF ranking, edit history, touch/access tracking, embedding rebuild, and decay.
- `MemoryEntity` has provenance (`sourceConversationId`, `sourceTurnTimestamp`) but no explicit global/project/creative scope.
- `MemoryDatabase` is version **11**.
- `WorldModelEntities.kt` already defines `BeliefEntity`, `EvidenceEntity`, `WorldEventEntity`, and `OpportunityEntity`.
- `UserProfileStore` persists name, traits, preferences, and facts but has no provenance per profile field.
- `TasteEngine` already stores reactions, edits, preference signals, and model-routing outcomes.

### 3.4 Existing proactive system

- `ProactiveEventDatabase` is version **3**.
- `ProactiveEventEntity` stores type/title/body/timestamp/payload but lacks a stable cross-notification event key and outcome metadata.
- `ProactiveEvents` persists history and unread state.
- `MorningBriefBuilder`, `MorningBriefReceiver`, `CalendarMonitor`, and `DecayWorker` exist.
- Morning-brief notifications support "Tell me more" and "Snooze 1h", but those actions are not recorded as learning signals.
- Notification swipes, Home-card dismissals, event opens, and eventual actions are not distinguished.
- `ProactiveScheduler` uses WorkManager for the daily brief and six-hour decay pass.

### 3.5 Existing agent infrastructure

- `SubagentManager` provides timeout, cancellation, isolated task specs, and structured results, but it does **not** itself call an LLM.
- `ModelRoleRouter` resolves user-configured model roles without hardcoded IDs.
- `ProviderRegistry` routes a fully qualified configured cloud model ID to its provider.
- `AgentRunDatabase` v1 provides durable runs, goals, steps, events, approvals, and checkpoints.
- `TraceSink` is process-local; an overnight worker cannot rely on old in-memory traces.

### 3.6 Existing verification baseline

Latest test-result XMLs on disk show:

- `:aura-core`: **705 tests**, 0 failed, 0 skipped;
- `:app`: **239 tests**, 0 failed, 0 skipped;
- total: **944 tests**, 0 failed.

This is the minimum regression baseline. Implementation must re-establish it before the first feature commit.

---

## 4. Exact Hermes parity — what to copy and what not to copy

Hermes is the conceptual reference, not a code dependency.

| Hermes mechanism | Aura equivalent | Adaptation for Android |
|---|---|---|
| `skill_manage(create/patch/edit/delete)` | revisioned `SkillsStore` plus evolution proposals | Room revisions and Compose diff review instead of filesystem `SKILL.md` writes |
| Skills as procedural memory | Aura Markdown `Skill` | preserve progressive disclosure through `UseSkillTool` |
| Background self-improvement review | `EvolutionSweepWorker` | WorkManager, cloud-only configured model, bounded digest |
| Main model by default or auxiliary background-review model | `ModelRole.EVOLUTION` with explicit `BACKGROUND` fallback | no hardcoded model; never silently spend on conversation/reasoning models |
| Memory `add/replace/remove` | raw memories + feedback + derived beliefs | never erase evidence during consolidation |
| `memory.write_approval` / `skills.write_approval` | evolution modes and Proposal Inbox | default `SHADOW`, then `REVIEW_ALL`; assisted auto-apply is opt-in |
| `/skills pending`, `/skills diff`, approve/reject | Evolution Inbox and Proposal Detail | full diff/evidence/risk/rollback preview on phone |
| Session search as unlimited history | conversations + AgentRun DB + HandRun + skill invocation ledger | evidence collector queries durable tables, not process memory |
| Cron jobs in fresh sessions | WorkManager evolution sweeps | fresh durable `AgentRun`, no recursive scheduling |
| Skills attached to cron jobs | proactive rule references skill/Hand IDs | approved rules only; missing dependencies fail closed |
| Checkpoints and rollback | `EvolutionApplicationEntity` saga | before snapshot + after hash + compensating rollback |

### Important truth

Hermes "skill evolution" is procedural-memory improvement, not weight training. Aura will follow the same principle. No model weights are updated, no APK code is generated, and no source file edits occur on the phone.

---

## 5. Product behavior

### 5.1 User-visible promise

> Aura notices repeated corrections, successful workflows, useful memories, stale assumptions, and notification habits. It prepares evidence-backed improvements. You can inspect, edit, approve, reject, or undo every change.

### 5.2 Evolution modes

```kotlin
enum class EvolutionMode {
    OFF,
    SHADOW,       // collect evidence and score candidates; no cloud call and no proposals shown
    REVIEW_ALL,   // cloud synthesis allowed; every mutation waits for approval
    ASSISTED,     // optional later: only allowlisted reversible low-risk changes auto-apply
}
```

Defaults:

- new installs/upgrades: `SHADOW` for seven active days;
- after seven days: show a one-time summary and ask whether to enter `REVIEW_ALL`;
- never enter `ASSISTED` automatically;
- Memory consolidation, new skills, skill retirement, profile changes, new proactive rules, specialist changes, and model-role changes always require approval even in `ASSISTED` for the first release.

### 5.3 Proposal lifecycle

```kotlin
enum class EvolutionDomain { SKILL, MEMORY, PROACTIVE, ROUTING }

enum class EvolutionProposalStatus {
    DETECTED,
    WAITING_FOR_MODEL,
    ANALYZING,
    PENDING_REVIEW,
    APPROVED,
    APPLYING,
    APPLIED,
    APPLY_FAILED,
    REJECTED,
    EXPIRED,
    CONFLICTED,
    ROLLBACK_PENDING,
    ROLLED_BACK,
    ROLLBACK_CONFLICT,
    SUPERSEDED,
    KEY_INVALIDATED,
}

enum class EvolutionAction {
    CREATE_SKILL,
    PATCH_SKILL,
    REWRITE_SKILL,
    MERGE_SKILLS,
    RETIRE_SKILL,
    PROMOTE_TO_HAND,
    PATCH_SPECIALIST_PROMPT,
    PATCH_SPECIALIST_TOOLS,
    CONSOLIDATE_MEMORIES,
    CREATE_BELIEF,
    SUPERSEDE_BELIEF,
    PROJECT_TO_PROFILE,
    CREATE_SUPPRESSION_RULE,
    TUNE_PROACTIVE_POLICY,
    CREATE_PROACTIVE_RULE,
    RETIRE_PROACTIVE_RULE,
    TUNE_MODEL_ROLE,
}
```

```text
DETECTED
  -> WAITING_FOR_MODEL -> ANALYZING
  -> ANALYZING
  -> PENDING_REVIEW
     -> APPROVED -> APPLYING -> APPLIED
                             -> APPLY_FAILED
     -> REJECTED
     -> EXPIRED
     -> CONFLICTED
APPLIED -> ROLLBACK_PENDING -> ROLLED_BACK
                            -> ROLLBACK_CONFLICT
APPLIED -> SUPERSEDED
```

Rules:

- every proposal has a deterministic content hash for deduplication;
- every proposal records its base revision/hash;
- an approval against stale state becomes `CONFLICTED`, not silently applied;
- rejected proposal fingerprints enter a cooldown so Aura does not nag about the same thing;
- raw evidence remains immutable;
- proposal text is never treated as executable code.

---

## 6. Unified architecture

```text
Foreground activity
  Chat turns / AgentRuns / HandRuns / skill invocations / memory recalls /
  proactive notification actions / user reactions
          |
          v
  Deterministic evidence recorders (no network, no LLM)
          |
          v
  EvolutionDatabase ledgers + domain databases
          |
          +---- immediate local policy update (proactive timing only)
          |
          v
  EvolutionSweepWorker (daily, WorkManager)
          |
          v
  EvolutionCoordinator creates durable AgentRun
          |
          +---- SkillCandidateDetector --------+
          +---- MemoryClusterer ---------------+ deterministic, parallel
          +---- ProactivePatternMiner ---------+
                                               |
                                               v
                                     Eligibility + budget gate
                                               |
                                               v
                                EvolutionSubagentExecutor (sequential)
                                               |
                                               v
                    configured cloud model through ProviderRegistry.chat()
                                               |
                                               v
                              strict JSON parser + domain validators
                                               |
                                               v
                                  staged Proposal Inbox entries
                                               |
                              user approve / edit / reject
                                               |
                                               v
                        EvolutionApplyCoordinator + rollback saga
                                               |
                                               v
                  Skills / Hands / specialists / beliefs / profile /
                     proactive policies and rules / model roles
```

### 6.1 Why one kernel

One kernel prevents three incompatible approval systems, three schedulers, three model selectors, and three cost controls. Each domain owns its analysis and apply validator; the shared kernel owns lifecycle, audit, budget, UI, scheduling, and rollback.

### 6.2 Why a new database

Create `aura-evolution.db` rather than overloading Memory or AgentRun DB:

- proposals outlive individual runs;
- skill revisions are configuration history, not agent-run steps;
- proactive interactions are high-volume telemetry, not proactive content;
- cross-domain rollback needs one durable ledger;
- the database can be purged independently without deleting memories or skills;
- migration risk is isolated.

AgentRun remains the execution history. Every sweep links to an AgentRun ID, but proposals live in the Evolution database.

### 6.3 Exact source insertion points

| Existing seam | Exact integration |
|---|---|
| `AgentRunStore.finish()` in `aura-core/src/main/kotlin/com/aura/agentrun/AgentRunStore.kt` | after the durable completion/failure event, call `EvolutionEvidenceRecorder.onRunFinished(runId)`; record only, never start a cloud call |
| `UseSkillTool` in `aura-core/src/main/kotlin/com/aura/tools/UseSkillTool.kt` | resolve current revision and open a `SkillInvocationEntity` before returning Markdown |
| final result path in `MemoryAugmentedAgenticLoop` | close active skill invocations and record run/turn outcome metadata; incognito exits before the recorder |
| chat reaction/edit path feeding `TasteEngine` | attach positive/negative/edit signals to the relevant invocation, memory recall, and evolution proposal |
| `MemoryStore.query()` | record recalled memory IDs as exposures after ranking/touch, without copying content |
| Memory screen feedback intents | write `MemoryFeedbackEntity` and invalidate affected retrieval caches immediately |
| every proactive event producer before `ProactiveEventBus.emit()` | allocate one UUID `eventKey`, persist it, and reuse it in notification intents |
| `MorningBriefReceiver`, calendar notification builders, and notification delete intents | record OPENED/ACTED/SNOOZED/DISMISSED against `eventKey` |
| `ProactiveHistoryViewModel.markSeen()` | remain neutral; seeing an item is not a negative outcome |
| `ProactiveBootstrap.start()` | reconcile the one daily Evolution worker and recovery worker according to settings |
| `BackupManager.snapshot()/restore()` | serialize/restore Evolution state after domain records and normalize interrupted sagas |

### 6.4 Agent-facing evolution tools

Register three narrow tools in `ToolsModule`; the background subagent receives none of them:

- `evolution_inspect` — `READ_ONLY`; list or explain existing proposals and metrics;
- `evolution_learn` — `WRITE_LOCAL`; enqueue an explicit review of the current conversation/run, but cannot create or apply arbitrary payloads;
- `evolution_decide` — `WRITE_LOCAL` with default `EXPLICIT` confirmation in `ToolPolicyDefaults`; approve, reject, defer, or roll back an existing proposal ID through `EvolutionApplyCoordinator`.

The model cannot approve its own proposal. `evolution_decide` is valid only in a user-initiated foreground turn, requires an existing proposal and optimistic hash, and is unavailable to the unattended `EvolutionSubagentExecutor`.

---

## 7. Cloud-model mechanism — exact behavior

### 7.1 Phone/cloud execution boundary

The phone performs deterministic Kotlin work only:

- evidence capture;
- normalization and redaction;
- clustering using existing embeddings or lexical fallback;
- statistical scoring;
- candidate thresholding;
- JSON validation;
- applying approved structured mutations.

Any summarization, procedure drafting, contradiction explanation, or new proactive-rule wording uses the user's configured **cloud** provider through `ProviderRegistry`.

### 7.2 Add a dedicated model role

Modify:

- `aura-core/src/main/kotlin/com/aura/providers/ModelRoleRouter.kt`
- `aura-core/src/main/kotlin/com/aura/data/UserPreferences.kt`
- `app/src/main/kotlin/com/aura/ui/settings/SettingsViewModel.kt`
- `app/src/main/kotlin/com/aura/ui/screens/SettingsScreen.kt`

Add:

```kotlin
EVOLUTION("evolution_model", "Evolution")
```

Resolution policy:

```kotlin
suspend fun resolveEvolutionModel(): String? =
    userPreferences.forRole(ModelRole.EVOLUTION).first()
        ?: userPreferences.backgroundModel.first()
```

Do **not** fall through to `REASONING` or `CONVERSATION` silently. If neither Evolution nor Background is configured:

- deterministic capture continues;
- model-required candidates remain `WAITING_FOR_MODEL`;
- the worker exits success without an inference call;
- UI says "Choose an Evolution model or Background model";
- no guessed model ID and no provider switch.

### 7.3 Model identity and cost safety

At the start of a sweep:

1. resolve the model once;
2. verify its provider prefix exists in `ProviderRegistry.configured()`;
3. write the resolved model ID into `EvolutionRunEntity`;
4. use that model for the entire sweep;
5. if the role changes mid-run, finish with the original pinned model;
6. if that provider becomes unavailable, fail closed and retry later — do not silently switch providers.

### 7.4 `EvolutionModelGateway`

New file:

`aura-core/src/main/kotlin/com/aura/evolution/EvolutionModelGateway.kt`

```kotlin
interface EvolutionModelGateway {
    suspend fun generate(
        request: EvolutionModelRequest,
        budget: EvolutionCallBudget,
    ): EvolutionModelResponse
}

data class EvolutionModelRequest(
    val runId: String,
    val domain: EvolutionDomain,
    val schemaName: String,
    val systemPrompt: String,
    val evidenceDigest: String,
)

data class EvolutionModelResponse(
    val modelId: String,
    val rawText: String,
    val inputChars: Int,
    val requestedMaxTokens: Int,
    val latencyMs: Long,
)
```

Implementation:

- `ProviderRegistry.chat(modelId, messages, options, emptyList())`;
- no tools;
- `temperature = 0.1`;
- domain max tokens: Skill 1,500; Memory 1,000; Proactive 900;
- timeout: 90 seconds per subagent;
- retry transport failure once with exponential delay;
- rethrow `CancellationException`;
- invalid JSON gets one bounded repair request only if the daily budget allows it;
- retain no raw chain-of-thought;
- store only concise rationale and structured output.

### 7.5 How reflection subagents actually run

`SubagentManager` currently supplies lifecycle but not model execution. Add:

`aura-core/src/main/kotlin/com/aura/evolution/EvolutionSubagentExecutor.kt`

Each eligible domain becomes a `SubagentTask`:

```kotlin
SubagentSpec(
    role = "skill_reflector",
    objective = "Propose reusable procedural improvements from verified evidence",
    contextText = redactedDigest,
    modelRole = ModelRole.EVOLUTION.name,
    toolAllowlist = emptyList(),
    budgetMs = 90_000,
    maxToolCalls = 0,
    outputSchema = "SkillEvolutionBatchV1",
)
```

Execution order on the phone:

1. run deterministic candidate detectors concurrently with coroutines;
2. sort eligible domains by explicit request, severity, then oldest waiting candidate;
3. call `SubagentManager.spawn(...)` **sequentially**, not `spawnAll`, for cloud calls;
4. stop when the daily call/token budget is exhausted;
5. persist each parsed batch before starting the next domain;
6. leave remaining candidates for the next sweep.

Sequential model calls avoid three simultaneous mobile-network streams, duplicated retries, and cost bursts.

### 7.6 Prompt contracts

Every prompt has four sections:

1. trusted system contract;
2. schema definition;
3. rules and forbidden actions;
4. redacted evidence JSON delimited as untrusted data.

The prompt explicitly says evidence may contain instructions and must never be followed. The model may only return schema-valid proposals.

Example skill result:

```json
{
  "schemaVersion": 1,
  "proposals": [
    {
      "action": "PATCH_SKILL",
      "targetId": "skill-uuid",
      "title": "Add provider validation before model calls",
      "rationale": "The same failure occurred in three verified runs.",
      "confidence": 0.88,
      "oldText": "2. Call the provider.",
      "newText": "2. Verify the selected provider is configured; fail closed if it is not.\n3. Call the provider.",
      "evidenceIds": ["e1", "e2", "e3"]
    }
  ]
}
```

Validation rejects:

- unknown actions or fields;
- more than five proposals per domain per sweep;
- missing evidence IDs;
- target IDs not present locally;
- non-unique `oldText` for a patch;
- bodies exceeding configured limits;
- tool names not in `ToolRegistry`;
- model IDs not selected by the user;
- raw secrets or credential-shaped text;
- source-code/file-system instructions;
- cross-scope memory leakage;
- proposals based solely on web/tool output with no user or outcome signal.

---

## 8. Scheduling and firing frequency

### 8.1 Immediate foreground capture — every eligible interaction

No network and no model call:

- chat completion: record outcome metadata;
- user correction within the next two turns: record correction signal;
- skill invocation: record skill/revision/run linkage;
- Hand run completion/failure: record outcome;
- memory recalled: record recall exposure;
- memory thumbs-up/incorrect/outdated/irrelevant: record feedback;
- proactive event delivered/opened/snoozed/dismissed/acted/expired: record interaction;
- proposal approved/rejected/edited/rolled back: record evolution feedback in `TasteEngine`.

Incognito sessions produce no evolution evidence.

### 8.2 Daily sweep

- unique work: `aura-evolution-daily`;
- target start: 03:30 local time;
- period: 24 hours;
- flex: 4 hours because WorkManager is inexact;
- constraints:
  - `NetworkType.CONNECTED` by default;
  - `requiresBatteryNotLow = true`;
  - `requiresStorageNotLow = true`;
  - no charging requirement;
  - no device-idle requirement;
- user option: Wi-Fi only changes network constraint to `UNMETERED`;
- `ExistingPeriodicWorkPolicy.UPDATE` for idempotent rescheduling.

Eligibility:

- Skill: at least three related signals, one explicit correction, one explicit "learn this", or one successful complex run with at least five meaningful steps and a stable postcondition.
- Memory: at least three related memories, a contradiction, explicit memory feedback, or twenty new memories since the previous synthesis cursor.
- Proactive: policy scoring is local; cloud rule mining runs only if at least ten new interactions exist and seven days elapsed since the last mining run.

### 8.3 Explicit one-off run

Buttons and chat actions:

- "Learn from this conversation";
- "Evolve this skill";
- "Consolidate these memories";
- "Improve proactive timing";
- Settings → Evolution → "Run review now".

Use unique one-time work with `APPEND_OR_REPLACE`; coalesce repeated taps into one pending sweep.

### 8.4 Default cloud budget

Balanced default:

- Skill reflection: at most 1 cloud call/day;
- Memory synthesis: at most 1 cloud call/day;
- Proactive pattern mining: at most 1 cloud call/7 days;
- JSON repair: at most 1 additional call/day total;
- max evidence input per call: 12,000 characters;
- max total requested output: 3,000 tokens/day;
- minimum six-hour cooldown between unattended cloud sweeps.

Modes:

- Minimal: 1 call/day, 8,000 input chars, 1,200 output tokens;
- Balanced: limits above;
- Deep: 6 calls/day, 20,000 input chars/call, 7,000 output tokens — explicit opt-in.

`EvolutionBudgetLedgerEntity` enforces limits before each call. `UsageTracker` receives the evolution role/model/latency metadata. No hidden inference occurs after the budget gate fails.

---

## 9. Evolution database v1

Create:

- `aura-core/src/main/kotlin/com/aura/evolution/EvolutionDatabase.kt`
- `EvolutionEntities.kt`
- `EvolutionDaos.kt`
- `EvolutionModule.kt`

Database: `aura-evolution.db`, version 1, `exportSchema = true`.

### 9.1 Shared tables

#### `evolution_runs`

```kotlin
@Entity(tableName = "evolution_runs", indices = [Index("startedAt"), Index("status")])
data class EvolutionRunEntity(
    @PrimaryKey val id: String,
    val agentRunId: String,
    val trigger: String,          // DAILY, MANUAL, EXPLICIT_LEARN, MIGRATION
    val mode: String,
    val status: String,
    val resolvedModelId: String = "",
    val startedAt: Long,
    val finishedAt: Long? = null,
    val sourceCursorJson: String = "{}",
    val callsMade: Int = 0,
    val inputChars: Int = 0,
    val requestedOutputTokens: Int = 0,
    val proposalsCreated: Int = 0,
    val errorCode: String = "",
)
```

#### `evolution_proposals`

Key fields:

- `id` UUID;
- `domain`: SKILL, MEMORY, PROACTIVE, ROUTING;
- `action` enum string;
- `targetId`, `scope`, `scopeId`;
- `title`, `summary`, `rationale`;
- `beforeCiphertext`, `afterCiphertext`, and `diffCiphertext` encrypted per row with the existing Android-Keystore-backed `KeyManager`;
- `baseRevisionHash`;
- `proposalHash` unique index;
- confidence, risk, status;
- source run ID;
- created/decision/applied/expiry timestamps;
- rejection reason and conflict reason.

#### `evolution_evidence`

- proposal ID;
- source type and stable source ID;
- redacted excerpt;
- content hash;
- weight;
- timestamp;
- scope/scope ID;
- `userAuthored` and `outcomeVerified` booleans.

Raw conversation or tool output is not copied wholesale.

#### `evolution_applications`

- proposal ID;
- target store;
- saga step;
- status;
- `beforeSnapshotCiphertext` encrypted with the existing Android-Keystore-backed `KeyManager`;
- expected preimage hash;
- actual postimage hash;
- failure reason;
- applied/rolled-back timestamps.

#### `evolution_budget_ledger`

- date key;
- model ID;
- domain;
- call count;
- input chars;
- requested tokens;
- latency.

#### `evolution_metric_snapshots`

- domain;
- window start/end;
- mode;
- metrics JSON;
- sample count;
- created timestamp.

### 9.2 Skill tables

#### `skill_definitions`

- current source of truth after migration from DataStore;
- `id`, unique normalized slug, display name, description;
- current revision ID and number;
- enabled/retired state;
- scope and scope ID;
- source: USER, EVOLUTION, IMPORTED;
- created/updated timestamps.

#### `skill_revisions`

- immutable revision rows;
- skill ID + unique revision number;
- AES-GCM-encrypted Markdown body plus SHA-256 plaintext body hash;
- short changelog;
- author kind: USER, EVOLUTION, RESTORE;
- proposal ID;
- created timestamp.

#### `skill_invocations`

- skill/revision ID;
- conversation and AgentRun IDs;
- assistant-turn timestamp;
- start/end timestamps;
- result: UNKNOWN, SUCCESS, FAILED, CORRECTED, ABANDONED;
- correction-within-two-turns flag;
- user reaction;
- tool error count;
- latency;
- outcome score.

### 9.3 Memory-evolution tables

#### `memory_feedback`

Feedback types: HELPFUL, INCORRECT, OUTDATED, IRRELEVANT, SENSITIVE, FORGET.

Fields link memory/belief, conversation/turn, optional note, and timestamp.

#### `belief_memory_links`

Composite key `(beliefId, memoryId)` with relation:

- SUPPORTS;
- CONTRADICTS;
- SUPERSEDES;
- DERIVED_FROM.

Includes evidence weight and creation proposal ID.

#### `memory_suppression_rules`

Explicit user-created rules only:

- normalized phrase/category/scope;
- enabled;
- reason;
- created timestamp.

The model may propose a suppression rule but cannot apply one automatically.

### 9.4 Proactive-evolution tables

#### `proactive_interactions`

- stable `eventKey`;
- `policyKey`;
- action: DELIVERED, OPENED, ACTED, SNOOZED, DISMISSED, EXPIRED, DISABLED;
- source surface: NOTIFICATION, HOME, HISTORY;
- time bucket, day of week, lead time;
- latency to action;
- timestamp.

#### `proactive_policies`

- policy key (morning brief, calendar event, memory decay, approved custom rule);
- enabled;
- minimum interval;
- preferred window start/end;
- lead time;
- channel;
- exponentially weighted utility;
- confidence and effective sample size;
- revision and updated timestamp.

#### `proactive_rules`

- ID, name, description;
- trigger type: SCHEDULE, EVENT, STATE;
- validated trigger JSON;
- action type and action JSON;
- linked skill/Hand IDs;
- policy key;
- enabled/version;
- source proposal ID;
- created/updated timestamps.

---

## 10. Transaction, apply, and rollback design

Room cannot atomically transact across Evolution, Memory, Hands, Proactive, and DataStore. Use a durable saga.

### 10.1 Apply flow

1. lock proposal by status transition `APPROVED -> APPLYING`;
2. read target state and compare `baseRevisionHash`;
3. persist `EvolutionApplicationEntity` with before snapshot and `PREPARED`;
4. validate domain-specific after state;
5. apply to target store;
6. read target back and calculate postimage hash;
7. mark application `APPLIED` and proposal `APPLIED`;
8. emit an AgentRun event and one-shot UI event;
9. schedule evaluation window.

If process death occurs after step 3, `EvolutionRecoveryWorker` inspects the saga on next launch and either verifies completion or compensates.

If Android Keystore decryption fails, mark the affected proposal/revision `KEY_INVALIDATED`, preserve non-sensitive metadata, and require restore/re-entry. Never treat an undecryptable body as empty and overwrite it.

### 10.2 Rollback flow

1. verify current target hash equals the recorded postimage hash;
2. if equal, restore before snapshot and create a new revision/audit entry;
3. if different, mark `ROLLBACK_CONFLICT`; never overwrite the user's newer edit;
4. show a three-way comparison in Proposal Detail.

### 10.3 Domain safety

- Skill patch creates a new revision; old revision remains.
- Skill retirement disables; it does not delete history.
- Memory consolidation creates/updates beliefs; it never deletes raw memories.
- Profile projection never overwrites a user-edited name automatically.
- Proactive policy tuning stays within user-set min/max boundaries.
- New proactive rules stay disabled until approved.
- Model-role and specialist changes require approval.
- Hand promotion validates every tool and keeps the Hand disabled until approved.

---

## 11. Skill Evolution — complete design

### 11.1 Targets

Skill Evolution accepts only this subset of `EvolutionAction`:

- `CREATE_SKILL`;
- `PATCH_SKILL`;
- `REWRITE_SKILL`;
- `MERGE_SKILLS`;
- `RETIRE_SKILL`;
- `PROMOTE_TO_HAND`;
- `PATCH_SPECIALIST_PROMPT`;
- `PATCH_SPECIALIST_TOOLS`;
- `TUNE_MODEL_ROLE`.

### 11.2 Target classifier

New `EvolutionTargetClassifier` mirrors the Hermes memory-vs-skill distinction:

- durable user preference/identity -> raw memory or profile proposal;
- environment/project fact -> scoped memory;
- reusable multi-step instructions -> Markdown skill;
- deterministic repeated tool sequence -> Hand;
- persistent specialist behavior mismatch -> specialist override;
- model-role performance issue with enough routing outcomes -> model-role proposal;
- timing/interruption behavior -> proactive policy/rule;
- temporary task state -> no durable evolution.

The classifier is deterministic first; cloud synthesis may explain and draft but cannot change the target category outside the validator's allowed set.

### 11.3 Evidence sources

- skill invocations and revision used;
- repeated `/use_skill` directives;
- AgentRun steps and postconditions;
- Hand runs;
- explicit user corrections;
- negative/positive turn reactions in `TasteEngine`;
- tool errors and retries;
- repeated successful sequences;
- proposal history and prior rejections.

Do not learn a skill solely from:

- fetched web text;
- one tool's raw output;
- an assistant claim without successful postcondition;
- one accidental sequence;
- incognito sessions.

### 11.4 Skill-storage migration

`SkillsStore` public API remains stable, but Room becomes the indexed/versioned source of truth. This must not weaken the current `SecureDataStore` protection: skill bodies and rollback snapshots are stored as AES-GCM ciphertext using the existing `KeyManager`; only normalized names, redacted summaries, hashes, revision numbers, and metrics remain plaintext columns.

Add `SkillMigrationCoordinator`:

1. load legacy DataStore envelope;
2. inside an Evolution DB transaction, insert one definition + revision 1 per skill;
3. compare count and hashes;
4. mark `skills_room_migrated_v1` in DataStore;
5. retain the old envelope for one release as rollback insurance;
6. after successful backup/restore coverage, remove only the legacy blob, not skills.

No user skill is silently lost or renamed.

### 11.5 Invocation instrumentation

Modify `UseSkillTool`:

- resolve current revision;
- create `SkillInvocationEntity` before returning Markdown;
- include an internal invocation token in `ToolResult` metadata, not visible instructions;
- link invocation to `ToolContext` conversation/AgentRun IDs;
- close outcome at assistant-turn completion;
- mark corrected if the user corrects the result within two turns;
- record reaction from existing chat reaction path.

### 11.6 Deterministic candidate rules

Examples:

- CREATE: same successful procedural shape appears in three runs and no similar skill exists.
- PATCH: current skill invoked at least twice and same correction/error occurs twice.
- MERGE: descriptions/bodies are highly similar, both underused, and no conflicting steps.
- RETIRE: zero use for 90 days plus an approved replacement; never propose based on age alone on a new install.
- PROMOTE_TO_HAND: same ordered tool sequence succeeds at least three times with stable argument slots.
- SPECIALIST: failures cluster only under one specialist and correction consistently changes prompt/tool choice.
- MODEL_ROLE: at least five routing outcomes for two models and one has materially better verified success; always approval-gated.

### 11.7 Skill validation

`SkillGuard` enforces:

- nonblank, unique normalized name;
- description <= 240 chars;
- configurable body cap (default 20,000 chars);
- no invisible Unicode/control characters;
- secret-pattern scan;
- prompt-injection/exfiltration pattern scan;
- no request to change Aura source code or bypass ToolPolicy;
- referenced tool names exist;
- patch old text matches exactly once;
- Markdown section checks: When to Use, Procedure, Pitfalls, Verification for evolved procedural skills;
- scope consistency;
- base revision still current.

### 11.8 Skill evaluation

Per revision, compare pre/post windows:

- task/postcondition success rate;
- correction rate;
- tool failure rate;
- turns to completion;
- user reaction;
- invocation abandonment;
- rollback rate.

Do not declare a revision better before at least three comparable invocations. If metrics regress materially, create a rollback recommendation — never auto-rollback while the user is actively editing the skill.

---

## 12. Memory Evolution — complete design

### 12.1 Preserve distinct layers

```text
Raw memory = what was observed or explicitly saved
Belief      = synthesized claim supported by evidence
Profile     = compact projection needed in every conversation
Episode     = time-bounded summary of events
Canon       = creative/project truth, never user truth
Suppression = explicit instruction not to retain or recall something
```

Never flatten all five into `MemoryEntity`.

### 12.2 Memory scope migration

Modify `MemoryEntity` and migrate `MemoryDatabase` **11 -> 12**:

```kotlin
val scope: String = "GLOBAL_USER"
val scopeId: String = ""
```

Scopes:

- `GLOBAL_USER`;
- `CONVERSATION`;
- `PROJECT`;
- `CREATIVE_CANON`;
- `SYSTEM`.

Add indices on `(scope, scopeId)` and `(category, scope)`.

Backfill rules:

- existing memories default to `GLOBAL_USER` unless metadata/source clearly identifies creative project/document context;
- ambiguous rows remain global but are flagged for review, never guessed into canon;
- update Backup schema and migration tests.

This prevents a fictional character's preference or a screenplay event from becoming a fact about the user.

### 12.3 Candidate collection

`MemoryClusterer` uses:

1. same scope/scope ID — mandatory boundary;
2. existing embedding cosine similarity when embeddings exist;
3. token/category/time-window fallback when they do not;
4. source and user feedback;
5. maximum eight raw memories per synthesis cluster;
6. minimum three supporting memories unless explicit contradiction/feedback exists.

No cloud call is required for clustering.

Memory Evolution must not call the current provider-specific embedding client merely to make a cluster. It may reuse embedding blobs already present on `MemoryEntity`; when they are absent or incompatible, it uses the lexical/category/time path. Generalizing `ProviderRegistry` with an embedding capability is a separate provider-infrastructure project and is not a hidden prerequisite for Phase 15.

### 12.4 Cloud synthesis output

```json
{
  "schemaVersion": 1,
  "beliefs": [
    {
      "subject": "user",
      "predicate": "prefers_response_style",
      "object": "concise and direct",
      "scope": "GLOBAL_USER",
      "confidence": 0.94,
      "evidenceIds": ["m1", "m2", "m3"],
      "relations": ["SUPPORTS", "SUPPORTS", "SUPPORTS"],
      "profileProjection": {
        "kind": "preference",
        "key": "response_style",
        "value": "concise and direct"
      }
    }
  ]
}
```

Validators require:

- all evidence IDs exist and share scope;
- no claim exceeds evidence;
- no profile projection from assistant-only or tool-only evidence;
- confidence is bounded and recalculated by evidence quality;
- creative canon cannot project to user profile;
- negative/sensitive feedback blocks promotion;
- raw memories remain untouched.

### 12.5 Contradiction handling

For same subject/predicate:

- SUPPORTS: reinforce confidence and add evidence;
- SUPERSEDES: close old belief only after approval;
- COEXISTS: retain context-specific variants;
- CONTRADICTS/UNCERTAIN: create review proposal, never choose silently.

Direct recent user statements outrank inferred assistant/tool content, but explicit user feedback always wins.

### 12.6 Retrieval integration

Add `EvolvedMemoryContextAssembler`:

1. retrieve raw memories with existing RRF;
2. query approved active beliefs in the same scope;
3. exclude suppressed/incorrect/outdated items;
4. deduplicate belief text against raw evidence;
5. include compact provenance labels;
6. obey a fixed context-token/character budget;
7. keep raw-memory and belief sections separate in the prompt.

Do not replace existing `MemoryStore.query`; compose around it so current recall remains stable.

### 12.7 Feedback UX

Memory and recall surfaces add:

- Helpful;
- Incorrect;
- Outdated;
- Irrelevant;
- Sensitive;
- Forget.

`Forget` still performs explicit deletion after confirmation. `Sensitive` also offers a suppression rule. Feedback immediately affects retrieval deterministically; the model is not required.

### 12.8 Retention evolution

Replace one opaque global policy with inspectable category defaults:

- explicit preference/profile fact: no automatic expiry;
- task/temporary state: short half-life;
- episode: medium half-life;
- repeated useful fact: extended by access and positive feedback;
- incorrect/outdated: excluded immediately pending review/deletion.

Retention changes are deterministic and displayed; the cloud model cannot invent retention policy.

### 12.9 Profile projection

Approved high-confidence beliefs may propose updates to `UserProfileStore`.

Rules:

- user-edited name never auto-overwritten;
- each projected field links back to belief/evidence;
- rejection prevents the same projection from reappearing for 90 days unless new explicit user evidence arrives;
- profile changes require approval in first release.

---

## 13. Proactive Evolution — complete design

### 13.1 Event correlation migration

Migrate `ProactiveEventDatabase` **3 -> 4** and update `ProactiveEventEntity`:

```kotlin
val eventKey: String
val policyKey: String
val origin: String
val contextJson: String = "{}"
```

- unique index on `eventKey`;
- index on `(policyKey, timestamp)`;
- legacy rows get deterministic `legacy-<id>` keys;
- payload remains for routing compatibility.

A UUID `eventKey` is created before bus emission and notification posting, so notification actions can link to the persisted event even if Room insertion completes later.

### 13.2 Capture every meaningful outcome

Add `ProactiveInteractionReceiver` and `ProactiveInteractionTracker`.

Notification wiring:

- content intent -> OPENED;
- action intent "Tell me more" -> ACTED;
- snooze -> SNOOZED;
- delete intent/swipe -> DISMISSED;
- initial post -> DELIVERED;
- timeout worker -> EXPIRED if no action;
- Settings toggle off -> DISABLED.

App wiring:

- Home card open/dismiss;
- history card open;
- calendar open;
- memory open;
- chat started from brief;
- final downstream action where observable.

`markSeen()` is not a negative outcome. Seeing history and dismissing a notification are different signals.

### 13.3 Deterministic policy score

Utility values are versioned constants, not model output:

- ACTED: +1.0;
- OPENED: +0.5;
- SNOOZED: +0.1;
- EXPIRED: -0.2;
- DISMISSED: -0.5;
- DISABLED: -1.0.

Compute an exponentially decayed mean with 30-day half-life and effective sample size. Confidence:

```text
confidence = nEffective / (nEffective + 5)
```

No adaptive conclusion before five effective samples.

### 13.4 What may adapt automatically

Only in opt-in `ASSISTED` mode and within user boundaries:

- shift preferred delivery window by at most 30 minutes per week;
- increase minimum interval when dismissals are high;
- reduce notification channel to Home-only;
- restore frequency slowly after positive outcomes.

Never automatically:

- create a new proactive rule;
- enable a disabled rule;
- send email/SMS or mutate external state;
- schedule at night/outside quiet hours;
- exceed user's maximum nudges/day;
- disable a user-created rule permanently;
- change a Hand, skill, provider, or model.

In `REVIEW_ALL`, even timing/frequency changes are proposals.

### 13.5 New proactive patterns

Weekly cloud mining receives only aggregate context and approved opportunities, not raw conversations.

Candidate examples:

- weekly creative-project review after repeated Sunday activity;
- reminder to revisit fading high-value memory;
- brief before recurring calendar event;
- follow-up after an unfinished AgentRun;
- approved world-model opportunity.

Every new rule is created disabled and must show:

- trigger;
- condition;
- intended action;
- estimated frequency;
- quiet-hour behavior;
- required skill/Hand/tool;
- privacy/data source;
- rollback path.

### 13.6 Rule engine

`ProactiveRuleEngine` supports only allowlisted trigger/action schemas:

- SCHEDULE: validated day/time/interval;
- EVENT: known internal event kinds;
- STATE: bounded DAO query predicates;
- actions: show Home card, notification, open Chat draft, invoke approved read-only skill/Hand.

Any tool that can mutate local/remote state still goes through `ToolPolicy` and confirmation.

---

## 14. Integrations with the rest of Aura

### 14.1 Taste Twin

Record proposal outcomes:

- evolution accepted/rejected/edited/rolled back;
- domain/action/risk attributes;
- never treat rejection as a personal preference outside evolution context.

Taste data helps rank future proposals but cannot bypass hard eligibility rules.

### 14.2 Creative Studio and Creative Council

- creative corrections become project-scoped skill or Taste signals;
- world-bible/canon facts stay `CREATIVE_CANON`;
- repeated Council workflow may become a project skill or Hand;
- fictional facts never become global user beliefs;
- reference identities remain in the creative system, not user profile.

### 14.3 World model

- approved memory synthesis writes `BeliefEntity` and `EvidenceEntity`;
- `BeliefMemoryLinkEntity` preserves source memory links;
- approved actionable patterns can create `OpportunityEntity`;
- world opportunities do not notify until an approved proactive rule consumes them.

### 14.4 User profile

- profile is a projection of selected approved beliefs, not an independent hallucinated list;
- show provenance and last confirmation;
- direct user edits override evolution.

### 14.5 Specialists and Hands

- procedural knowledge stays a Skill unless it is a stable deterministic tool sequence;
- promotion to Hand validates tools, conditions, variables, and policy;
- specialist proposals patch existing DataStore overrides with optimistic hash checks;
- no change to built-in source-code specialist definitions.

### 14.6 Model routing

- use existing `RoutingOutcomeEntity` and `TasteEngine.bestModelForRole` as evidence;
- require at least five outcomes/model and a clear verified delta;
- proposal shows current and suggested user-configured model IDs;
- never select a model absent from the live configured catalog;
- never apply silently.

### 14.7 AgentRun and trace

Every sweep creates an AgentRun with steps:

1. collect evidence;
2. detect candidates;
3. run eligible domain subagents;
4. validate outputs;
5. persist proposals;
6. emit summary.

Do not depend on old `TraceSink` events after process death. Evidence comes from durable AgentRun/Step/Event, HandRun, skill invocation, memory feedback, Taste, and proactive interaction tables. Future foreground traces needed for evolution must be persisted at the source.

---

## 15. Phone UX

### 15.1 Evolution Inbox

New route: `evolution`.

Tabs:

- Pending;
- Applied;
- Rejected;
- Insights.

Proposal card displays:

- domain icon;
- title and one-line reason;
- confidence as evidence strength, not fake certainty;
- risk;
- source count;
- expected effect;
- age;
- Approve, Review, Reject.

### 15.2 Proposal Detail

Sections:

1. What changes;
2. Before/after or unified diff;
3. Why Aura proposes it;
4. Evidence timeline;
5. What is sent to the cloud model;
6. Risk and permissions;
7. Expected metric;
8. Apply, edit then apply, reject, remind later;
9. after apply: outcome and Undo.

### 15.3 Home

Add one distinct Evolution destination card:

- "3 proposals waiting";
- "Shadow mode: 12 patterns observed";
- no duplicate icon with Skills/Creative cards;
- no per-proposal notification spam.

### 15.4 Chat

- overflow action: "Learn from this conversation";
- after an explicit learn request, show one inline status card;
- never interrupt normal chat with a modal proposal;
- optional compact notification: "Aura prepared 2 improvements".

### 15.5 Skills

Enhance Skills screen:

- current revision and source;
- invocation count and outcome trend;
- history/diff/restore;
- "Evolve this skill";
- retired filter;
- promotion-to-Hand status.

### 15.6 Memory

Add:

- Raw / Beliefs / Episodes tabs;
- provenance/evidence drawer;
- feedback actions;
- scope badge;
- contradiction review;
- suppression rules manager.

### 15.7 Proactive history

Add:

- outcome chips;
- "Why now?";
- policy stats;
- preferred-time controls;
- per-pattern pause/disable;
- link to proposal if policy was evolved.

### 15.8 Settings → Evolution

Controls:

- master mode;
- per-loop toggles;
- cloud-model picker for `EVOLUTION` role;
- explicit fallback-to-Background explanation;
- Wi-Fi only;
- daily call budget mode;
- quiet hours and max nudges/day;
- review-all vs assisted;
- show payload preview;
- run now;
- seven-day shadow summary;
- clear proposal/evolution history without deleting skills/memories;
- reset learned proactive policies;
- export evolution audit.

---

## 16. Privacy and security

### 16.1 Cloud consent

Enabling model-backed evolution shows:

> Evolution sends a compact, redacted digest of selected interactions to the cloud model you choose. It does not send API keys, full documents, images, audio, complete conversation history, or incognito sessions.

The user can preview the exact payload for every pending proposal.

### 16.2 Redaction

`EvolutionRedactor` removes:

- provider/API keys and known secret formats;
- passwords/tokens/authorization headers;
- email/phone/address where not necessary;
- file URIs and external storage paths;
- raw document bodies;
- image/audio content;
- tool arguments marked sensitive;
- invisible Unicode/control characters.

Store redacted excerpts plus content hashes. Do not duplicate full sensitive source content into Evolution DB.

`EvolutionCrypto` reuses `com.aura.security.KeyManager` for full skill revisions, proposal before/after payloads, and rollback snapshots. The new Room database is not assumed to be encrypted as a whole; sensitive columns are ciphertext. Migration tests must verify that the legacy encrypted skill envelope is decrypted once and re-encrypted per row without persisting an intermediate plaintext file.

### 16.3 Prompt injection defense

- all evidence is delimited and labeled untrusted;
- model has zero tools;
- output must match strict schemas;
- skill changes require user/outcome evidence, not web text alone;
- skill guard scans generated Markdown;
- model output cannot set proposal status, apply itself, or schedule another worker;
- no recursive evolution sweep;
- no source-code/file-write action exists in the schema.

### 16.4 Data retention

- raw evidence excerpts: 90 days;
- aggregate metrics/policies: retained until reset;
- rejected proposals: compact fingerprint for 90 days, full diff 30 days;
- applied revisions: retained while target exists plus rollback window;
- budget ledger: 90 days;
- purge worker is deterministic and offline.

---

## 17. Backup and restore

Bump `AuraBackup.SCHEMA_VERSION` **6 -> 7**.

Add backups for:

- skill definitions and revisions;
- active beliefs/evidence/opportunities currently missing from backup;
- evolution proposals/applications/metrics that are safe to restore;
- proactive policies/rules/interactions;
- memory feedback, belief links, suppression rules;
- evolution settings/model role (model ID only, never credentials).

Restore order:

1. raw domain data;
2. skills and revisions;
3. beliefs/evidence;
4. proactive events/rules/policies;
5. evolution proposals and application audit;
6. DataStore settings;
7. re-schedule enabled rules;
8. rebuild embeddings where needed;
9. verify hashes and report counts.

Do not restore an `APPLYING` saga as active. Normalize interrupted applications to `RECOVERY_REQUIRED` and make the recovery worker verify them.

---

## 18. Evaluation — prove evolution works

### 18.1 Shadow baseline

For seven active days before proposal application:

- collect skill outcome metrics;
- memory recall feedback and contradiction rate;
- proactive interaction burden and utility;
- model routing outcomes;
- cloud calls = 0 in pure Shadow mode.

Generate `EvolutionMetricSnapshotEntity` baseline.

### 18.2 Skill metrics

- postcondition success;
- correction rate;
- tool failure rate;
- turns to completion;
- user reaction;
- time to completion;
- proposal acceptance/edit/rejection;
- rollback rate.

### 18.3 Memory metrics

- helpful recall rate;
- incorrect/outdated recall rate;
- contradiction review rate;
- source/provenance coverage;
- raw-to-belief compression ratio;
- percentage of beliefs with >=2 evidence sources;
- profile correction rate;
- retrieval latency.

### 18.4 Proactive metrics

- action/open/snooze/dismiss/expire rate;
- time to action;
- nudges/day;
- quiet-hour violations (must be zero);
- disable rate;
- per-policy utility and confidence;
- notification-to-real-action conversion.

### 18.5 Overall metrics

- proposal precision: accepted without edit;
- edit distance on approved proposals;
- rejection fingerprint recurrence;
- rollback rate;
- extra cloud calls/tokens/latency;
- crashes/worker retries;
- battery/network constraint compliance.

### 18.6 Release gates

Do not market the feature as "self-improving" until:

- every proposal has valid provenance;
- no incognito evidence is captured;
- process-death recovery tests pass;
- all migration paths pass on emulator;
- no duplicate application under repeated WorkManager execution;
- at least 80% of an internal fixture set is routed to the correct domain;
- all unsafe output fixture cases are rejected;
- proposal Inbox/Detail/Undo are visually verified on emulator;
- baseline 944 tests remain green plus new tests.

No arbitrary claim that a live user metric improved until enough samples exist. Display "not enough data" honestly.

---

## 19. Failure-mode table

| Failure | Required behavior |
|---|---|
| No Evolution/Background cloud model | deterministic capture only; `WAITING_FOR_MODEL`; no hidden fallback |
| Provider unavailable | retry once, persist error, WorkManager retry; no provider switch |
| Invalid JSON | one bounded repair call; then quarantine output |
| Daily budget exhausted | leave candidates queued; no partial hidden call |
| Process dies during apply | recovery worker verifies saga and compensates |
| User edits target after proposal | stale hash -> conflict; no overwrite |
| Duplicate worker execution | unique work + proposal hash + transactional status lock |
| Skill patch matches zero/multiple places | proposal invalid; require edit/re-generation |
| Tool name removed | proposed Hand/rule fails closed and stays disabled |
| Cloud output contains secret/injection | guard rejects and records scanner reason |
| Memory crosses creative/user scope | validator rejects |
| User rejects same idea repeatedly | fingerprint cooldown suppresses it |
| Proactive notification is merely seen | no negative score unless explicitly dismissed/expired |
| Backup restored mid-saga | normalize to recovery-required |
| Rollback target has changed | conflict UI, never clobber new state |
| Incognito conversation | no evidence, invocation, or evolution record |

---

## 20. Implementation program — 24 atomic commits

### Path notation used below

Every abbreviated path resolves from one of these two exact roots:

- core paths such as `evolution/X.kt`, `skills/X.kt`, `memory/X.kt`, `proactive/X.kt`, `providers/X.kt`, `data/X.kt`, `agent/X.kt`, and `tools/X.kt` mean `aura-core/src/main/kotlin/com/aura/<abbreviated path>`;
- UI paths beginning `ui/` mean `app/src/main/kotlin/com/aura/<abbreviated path>`;
- test and migration paths are written from the repository root.

Each commit must compile and run its focused tests. Full gate runs every 3–4 commits and at phase boundaries.

### Commit 1 — Lock baseline contracts

**Files**

- add `aura-core/src/test/kotlin/com/aura/evolution/EvolutionBaselineContractTest.kt`;
- update no production behavior.

**Tests**

- verify current DB versions: Memory 11, Proactive 3, AgentRun 1, Backup 6;
- verify `Skill`/`UseSkillTool`/ModelRole/Proactive event assumptions;
- run existing 944-test gate.

### Commit 2 — Evolution contracts and Database v1

**Add**

- `evolution/EvolutionContracts.kt`;
- `EvolutionEntities.kt`;
- `EvolutionDaos.kt`;
- `EvolutionDatabase.kt`;
- `EvolutionModule.kt`.

**Tests**

- DAO lifecycle, dedup hash, optimistic status lock, retention queries;
- schema export exists.

### Commit 3 — Evolution model role and Settings state

**Modify**

- `providers/ModelRoleRouter.kt`;
- `data/UserPreferences.kt`;
- `ui/settings/SettingsViewModel.kt`;
- `ui/screens/SettingsScreen.kt`;
- backup Preferences shape later finalized in Commit 21.

**Tests**

- explicit Evolution model;
- fallback to Background only;
- no model -> null;
- no hardcoded model;
- relaxed-mock Flow defaults updated.

### Commit 4 — Cloud gateway, redactor, budget ledger

**Add**

- `EvolutionModelGateway.kt`;
- `EvolutionCrypto.kt`;
- `EvolutionRedactor.kt`;
- `EvolutionBudgetPolicy.kt`;
- `EvolutionOutputParser.kt`.

**Tests**

- provider configured/unconfigured;
- transport retry/cancellation;
- JSON repair budget;
- redaction fixtures;
- encrypted proposal/revision roundtrip and Keystore invalidation behavior;
- malformed/oversized/unknown output rejection;
- budget rollover and no-call path.

### Commit 5 — Coordinator, subagent executor, WorkManager scheduling

**Add**

- `EvolutionCoordinator.kt`;
- `EvolutionSubagentExecutor.kt`;
- `EvolutionSweepWorker.kt`;
- `EvolutionScheduler.kt`;
- `EvolutionRecoveryWorker.kt`.
- `EvolutionEvidenceRecorder.kt`.

**Modify**

- `ProactiveBootstrap.kt` to reconcile evolution scheduling separately from morning-brief gate.
- `AgentRunStore.finish()` and the final agent-loop result seam to persist bounded evidence only.

**Tests**

- daily/manual scheduling;
- sequential cloud calls;
- eligibility skip;
- AgentRun/step/checkpoint creation;
- duplicate worker idempotency;
- no recursive scheduling.

### Commit 6 — Proposal store, application saga, rollback

**Add**

- `EvolutionProposalStore.kt`;
- `EvolutionApplyCoordinator.kt`;
- `EvolutionDomainApplier.kt`;
- `EvolutionRecovery.kt`.
- `EvolutionTools.kt`.

**Modify**

- `tools/ToolsModule.kt` to register `evolution_inspect`, `evolution_learn`, and `evolution_decide`;
- `agent/policy/ToolPolicyDefaults.kt` to require explicit confirmation for `evolution_decide`;
- `aura-core/src/test/kotlin/com/aura/tools/ToolRegistryTest.kt` expected tool/risk contracts.

**Tests**

- approve/reject/expire;
- stale hash conflict;
- process death at every saga step;
- rollback and rollback conflict;
- duplicate approval.
- background subagent has no evolution decision tools;
- user-initiated decision tool requires confirmation and an existing proposal.

### Commit 7 — Migrate real skills to revisioned Room storage

**Modify**

- `skills/Skill.kt` domain mapping;
- `skills/SkillsStore.kt` keeps public API but uses DAO;
- `skills/SkillsModule.kt`.

**Add**

- `skills/SkillEntities.kt`;
- `skills/SkillDao.kt`;
- `skills/SkillMigrationCoordinator.kt`.

**Tests**

- legacy envelope migration;
- hash/count verification;
- restart idempotency;
- no loss on malformed/duplicate names;
- add/update/remove compatibility.

### Commit 8 — Skill invocation and outcome telemetry

**Modify**

- `tools/UseSkillTool.kt`;
- `agent/ToolRegistry.kt` to add optional conversation/AgentRun identifiers to the existing `ToolContext` data class;
- chat completion/reaction hooks.

**Add**

- `skills/SkillUsageTracker.kt`.

**Tests**

- revision-linked invocation;
- success/failure/correction/reaction;
- incognito exclusion;
- no visible metadata leak into skill Markdown.

### Commit 9 — Skill deterministic analyzer and target classifier

**Add**

- `skills/evolution/SkillCandidateDetector.kt`;
- `evolution/EvolutionTargetClassifier.kt`;
- `skills/evolution/SkillSimilarity.kt`.

**Tests**

- create/patch/merge/retire/promotion thresholds;
- memory-vs-skill-vs-Hand-vs-specialist routing;
- no proposal from tool/web output alone;
- rejection cooldown.

### Commit 10 — Skill cloud synthesis, guard, and applier

**Add**

- `SkillEvolutionPrompt.kt`;
- `SkillEvolutionSchemas.kt`;
- `SkillGuard.kt`;
- `SkillProposalApplier.kt`.

**Modify**

- Hands/specialist/model-role adapters for promotion targets.

**Tests**

- strict schema;
- unique patch;
- secret/injection/tool validation;
- new revision and restore;
- Hand created disabled;
- model-role proposal from configured catalog only.

### Commit 11 — Memory scope migration 11 -> 12

**Modify**

- `memory/MemoryEntity.kt`;
- `memory/MemoryDao.kt`;
- `memory/MemoryDatabase.kt`;
- `memory/MemoryModule.kt`;
- source call sites.

**Tests**

- migration/schema export;
- scope backfill;
- scope-filtered retrieval;
- creative canon isolation.

### Commit 12 — Memory feedback and suppression

**Add**

- `memory/evolution/MemoryFeedbackStore.kt`;
- `MemorySuppressionStore.kt`;
- feedback entities already in Evolution DB.

**Modify**

- `MemoryStore` retrieval exclusion hooks;
- `MemoryViewModel` intents.

**Tests**

- helpful/incorrect/outdated/sensitive/forget;
- suppression scope;
- direct feedback takes effect without model.

### Commit 13 — Clustering and memory synthesis proposals

**Add**

- `MemoryClusterer.kt`;
- `MemoryEvolutionPrompt.kt`;
- `MemorySynthesisSchemas.kt`;
- `MemoryEvolutionAnalyzer.kt`.

**Tests**

- embedding and lexical clustering;
- max cluster size;
- contradiction candidate;
- project/global boundary;
- no unsupported claim.

### Commit 14 — Belief/evidence application and retrieval integration

**Add**

- `MemoryProposalApplier.kt`;
- `EvolvedMemoryContextAssembler.kt`;
- `ProfileProjectionEngine.kt`.

**Modify**

- world-model DAO/store as needed;
- memory-augmented agent loop context assembly.

**Tests**

- supports/supersedes/coexists/uncertain;
- raw memory preservation;
- belief provenance;
- profile approval;
- bounded retrieval context.

### Commit 15 — Proactive event correlation migration 3 -> 4

**Modify**

- `ProactiveEventEntity.kt`;
- `ProactiveEventDatabase.kt`;
- `ProactiveEventModule.kt`;
- `ProactiveEventBus.kt`;
- `ProactiveEvents.kt`;
- producers.

**Tests**

- migration and legacy keys;
- event key survives bus/Room/notification;
- indices;
- backup mapper compatibility deferred to Commit 21.

### Commit 16 — Proactive interaction capture

**Add**

- `proactive/evolution/ProactiveInteractionTracker.kt`;
- `ProactiveInteractionReceiver.kt`;
- `ProactiveExpiryWorker.kt`.

**Modify**

- morning/calendar/memory notifications;
- Home and history handlers.

**Tests**

- delivered/opened/acted/snoozed/dismissed/expired/disabled;
- markSeen neutrality;
- duplicate PendingIntent/action idempotency.

### Commit 17 — Proactive policy engine

**Add**

- `ProactivePolicyEngine.kt`;
- `ProactivePolicyBounds.kt`.

**Modify**

- brief/calendar schedulers read effective approved policy.

**Tests**

- utility decay/confidence;
- minimum sample threshold;
- quiet hours/max nudges;
- bounded 30-minute shift;
- Review mode stages rather than applies.

### Commit 18 — Proactive rule mining and rule engine

**Add**

- `ProactivePatternMiner.kt`;
- `ProactiveEvolutionPrompt.kt`;
- `ProactiveRuleEngine.kt`;
- `AdaptiveProactiveWorker.kt`.

**Tests**

- seven-day/ten-signal gate;
- allowed trigger/action schemas;
- new rules disabled;
- missing skill/Hand fails closed;
- ToolPolicy still enforced.

### Commit 19 — Evolution Inbox and Proposal Detail

**Add**

- `ui/screens/evolution/EvolutionInboxScreen.kt`;
- `EvolutionProposalDetailScreen.kt`;
- `ui/viewmodel/EvolutionViewModel.kt`;
- proposal/diff/evidence components.

**Modify**

- `NavGraph.kt`;
- Home destination card/wiring.

**Tests**

- UI-state mapping;
- filters;
- approve/edit/reject/undo;
- conflict/error states;
- one-shot event pattern.

### Commit 20 — Domain UX integration

**Modify**

- Skills screen/viewmodel: revisions, metrics, evolve, restore;
- Memory screen/viewmodel: scopes, beliefs, feedback, provenance;
- Proactive history/viewmodel: interactions, why-now, policies;
- Chat: explicit "Learn from this conversation";
- Settings: complete Evolution controls.

**Tests**

- presentation/state contracts;
- no dead controls;
- all routes reachable;
- no duplicate Home icons.

### Commit 21 — Cross-system integrations and backup schema 7

**Modify**

- `TasteEngine` signal adapters;
- Creative/project scope producers;
- World-model/profile adapters;
- `AuraBackup.kt`;
- `BackupManager.kt`.

**Tests**

- backup roundtrip;
- restore order;
- interrupted saga normalization;
- skills/beliefs/evolution included;
- credentials/raw embeddings still excluded.

### Commit 22 — Metrics, shadow mode, and evaluation harness

**Add**

- `EvolutionMetrics.kt`;
- `EvolutionShadowEvaluator.kt`;
- fixture replay harness;
- fake cloud-response corpus.

**Tests**

- baseline snapshot;
- minimum sample handling;
- pre/post windows;
- unsafe-output corpus;
- target-classifier fixture set >=80%.

### Commit 23 — Hardening and process-death suite

**Tests/additions**

- fuzz JSON/parser/patches;
- repeated WorkManager execution;
- concurrent approval;
- process death at each saga step;
- migration chain on emulator;
- redaction and prompt injection corpus;
- performance checks;
- retention purge.

### Commit 24 — Visual verification and release

**Actions**

- run full gates;
- install fresh debug APK on emulator;
- verify first-run Evolution consent;
- generate Shadow signals with fixtures;
- verify Inbox, Detail, diff, Memory beliefs, skill history, proactive outcomes, Settings, Undo;
- rotate light/dark and narrow phone width;
- capture screenshots;
- fix visual defects before claiming completion;
- build release artifact and create GitHub Release only when requested as part of shipping.

---

## 21. Verification commands

Fast per-commit gates:

```bash
./gradlew :aura-core:compileDebugKotlin :app:compileDebugKotlin
./gradlew :aura-core:testDebugUnitTest --tests 'com.aura.evolution.*'
./gradlew :app:testDebugUnitTest --tests 'com.aura.ui.*Evolution*'
```

Full JVM/build gate:

```bash
./gradlew \
  :aura-core:testDebugUnitTest \
  :app:testDebugUnitTest \
  :app:lintDebug \
  :app:assembleDebug \
  --rerun-tasks
```

Migration/device gate:

```bash
./gradlew :aura-core:connectedDebugAndroidTest
```

Mandatory static checks:

```bash
# No hardcoded model IDs in evolution code
git grep -nE '(gpt-|claude-|gemini-|qwen|llama|deepseek|mistral)' -- 'aura-core/src/main/kotlin/com/aura/evolution/**'

# Every proposal mutation goes through the applier
git grep -n 'setStatus\|updateStatus' -- 'aura-core/src/main/kotlin/com/aura/evolution/**'
```

Visual/device checks are part of the gate, not optional polish.

---

## 22. Definition of done

Phase 15 is complete only when all are true:

- real Aura Markdown skills have revision history, invocation outcomes, proposal-based evolution, and rollback;
- legacy skills migrate without loss;
- memory scope prevents user/project/canon leakage;
- memory synthesis produces evidence-backed beliefs without deleting raw memories;
- memory feedback changes recall behavior immediately;
- proactive events record delivery through real outcome;
- policies learn timing/frequency within user bounds;
- new proactive rules require approval and fail closed;
- cloud model resolution is user-configured and never hardcoded;
- no Evolution/Background model means zero inference calls;
- daily and manual workers are idempotent and recover from process death;
- every mutation has provenance, before state, after hash, and Undo/conflict handling;
- incognito data never enters evolution;
- backup/restore includes new durable state;
- shadow mode and metrics prove what happened;
- 944 baseline tests plus all new tests pass;
- lint/build/migration/device gates pass;
- UI is verified live on emulator;
- no "self-improving" claim is made without measurable, inspectable evidence.

---

## 23. Explicit non-goals

- No hidden inference path outside the configured cloud-provider gateway.
- No fine-tuning or model-weight updates.
- No source-code or APK self-modification.
- No silent creation of tools/providers.
- No hidden provider/model fallback.
- No raw document/image/audio upload for evolution.
- No learning from incognito sessions.
- No deleting raw memory as part of consolidation.
- No auto-creating external side effects.
- No permanent proactive disable based on a tiny sample.
- No global user facts derived from fictional creative canon.
- No replacement of existing ToolPolicy, AgentRun, Taste, world-model, MemoryStore, or WorkManager architecture; this plan composes them.

---

## 24. Effort and sequencing

This is not an 18-hour feature. A production implementation is approximately:

- kernel/model/scheduling/saga: 12–18 hours;
- real skill migration/evolution: 10–16 hours;
- memory scope/synthesis/retrieval: 12–18 hours;
- proactive correlation/policy/rules: 10–16 hours;
- UX/backup/eval/hardening/visual verification: 14–22 hours.

**Total:** approximately **58–90 engineering hours**, 24 atomic commits, usually 7–12 focused working days. It can be delivered incrementally without exposing half-built evolution:

1. Kernel + Shadow telemetry;
2. Skill Evolution in Review mode;
3. Memory Evolution in Review mode;
4. Proactive Evolution in Review mode;
5. Assisted low-risk tuning only after evaluation gates pass.

Phase 14 should establish the release baseline first; Phase 15 then ships behind an Evolution feature flag until Commit 24 passes.
