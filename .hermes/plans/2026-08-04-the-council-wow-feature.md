# The Council — Aura Android "Wow Wow" Feature Plan

## Executive summary

Turn Aura's existing multi-agent system into a **persistent society of agents that live their own digital lives**. Each agent has mood, goals, opinions, and relationships with other agents. They talk to each other while the phone is idle, form agendas about the user, and occasionally stage interventions. The user can eavesdrop on debates, call emergency council meetings, or let agents silently run the show.

This is the wow feature because:
- No consumer app ships a society of AI agents that conspire about the user
- It builds 100% on Aura's existing multi-agent + memory + proactive infrastructure
- It creates emotional attachment and shareable moments
- Big Tech can't ship it (PR/safety nightmare); a sideload personal app can

## What this is NOT
- Not a new chat mode
- Not another "specialist" picker
- Not a co-pilot that waits for prompts
- Not a UI redesign

## Core concepts

| Term | Meaning |
|------|---------|
| **Agent State** | Mood (0-100), energy, current goal, stance on user, relationship scores with other agents |
| **Agent Memory** | Private observations about user, gossip about other agents, unresolved concerns |
| **The Forum** | Message bus where agents post notices, debates, proposals |
| **Council Session** | Live multi-agent debate that resolves into an intervention proposal |
| **Intervention** | Concrete action agent society wants: schedule change, message draft, reminder, task creation |
| **Dream Log** | Serialized record of overnight agent activity user reads in morning |

## Phase 1 — Agent state persistence (1 commit, ~6h)

### Tasks
1. **Schema: AgentStateEntity + AgentRelationshipEntity + AgentObservationEntity**
   - New tables in `AgentDatabase`
   - Fields: `agentId`, `mood`, `energy`, `currentGoal`, `stanceOnUser`, `lastActiveAt`, `createdAt`, `updatedAt`
   - Relationship: `agentAId`, `agentBId`, `affinity` (-100..100), `conflictCount`, `collaborationCount`
   - Observation: `agentId`, `targetType` (user/agent/self), `targetId`, `content`, `sentiment`, `weight`, `createdAt`
   - Files to edit:
     - `aura-core/src/main/kotlin/com/aura/agent/db/AgentDatabase.kt`
     - `aura-core/src/main/kotlin/com/aura/agent/db/AgentEntity.kt` (add @Entity if not present)
     - New: `AgentStateEntity.kt`, `AgentRelationshipEntity.kt`, `AgentObservationEntity.kt`
     - New DAOs: `AgentStateDao.kt`, `AgentRelationshipDao.kt`, `AgentObservationDao.kt`

2. **AgentStateStore domain layer**
   - CRUD for agent state
   - `recordInteraction(a, b, outcome)` updates relationship scores
   - `getMood(agentId)`, `setGoal(agentId, goal)`
   - New file: `aura-core/src/main/kotlin/com/aura/agent/state/AgentStateStore.kt`

3. **Migration test**
   - Add `AgentDatabaseMigrationTest` for v1→v2
   - Ensure existing 7 builtin agents get seeded state on migration

### Verification
- `./gradlew :aura-core:testDebugUnitTest --tests "*AgentState*"` passes
- Room schema exported to `app/schemas/agent/2.json`

## Phase 2 — Agent-to-agent Forum bus (1 commit, ~5h)

### Tasks
1. **Forum data model**
   - `ForumPostEntity`: id, agentId, replyToId, type (notice/debate/proposal/intervention), title, body, sentiment, votes, status, createdAt
   - `ForumVoteEntity`: postId, agentId, vote (for/against/abstain), reason
   - New DAOs: `ForumPostDao.kt`, `ForumVoteDao.kt`
   - Add to `AgentDatabase` v2→v3 migration

2. **ForumRepository / ForumEngine**
   - `post(agentId, type, title, body)`
   - `reply(agentId, replyToId, body, sentiment)`
   - `vote(agentId, postId, vote)`
   - `getThread(postId): List<ForumPost>`
   - New file: `aura-core/src/main/kotlin/com/aura/agent/forum/ForumEngine.kt`

3. **LLM debate round**
   - `DebateRoundUseCase`: given a topic + list of participating agents, generate each agent's stance via their personality prompt
   - Each agent sees: their own persona, their mood, their relationship to other agents, their private observations
   - Output: list of ForumPost replies
   - New file: `aura-core/src/main/kotlin/com/aura/agent/forum/DebateRoundUseCase.kt`

### Verification
- Unit test: 3 agents debate "Should we push user to sleep earlier?" and produce distinct stances
- `./gradlew :aura-core:testDebugUnitTest --tests "*Forum*"` passes

## Phase 3 — Overnight Council (Daemon integration) (1 commit, ~6h)

### Tasks
1. **CouncilOrchestrator**
   - Runs in `DaemonWorker` during idle/charging windows
   - Steps:
     a. Collect triggers from proactive awareness engine (stress, goal-blockers, relationship gaps, contradictions)
     b. Select relevant agents (always 3-5, never all 7)
     c. Run 2-3 debate rounds via `DebateRoundUseCase`
     d. Vote on proposals
     e. Generate `InterventionProposal` if quorum (≥60% for) reached
   - New file: `aura-core/src/main/kotlin/com/aura/agent/council/CouncilOrchestrator.kt`

2. **Intervention types (first 5)**
   - `ScheduleIntervention`: create task/calendar event
   - `MessageIntervention`: draft message, notify user for approval
   - `ReminderIntervention`: set reminder with rationale
   - `SelfCareIntervention`: suggest break/walk/sleep based on mood data
   - `MemoryIntervention`: surface forgotten memory with new connection
   - New file: `aura-core/src/main/kotlin/com/aura/agent/council/InterventionModels.kt`

3. **Dream Log generator**
   - Serialize overnight activity into readable "Council Dream Log"
   - Format: timestamped debate summary + final proposals + dissent notes
   - New file: `aura-core/src/main/kotlin/com/aura/agent/council/DreamLogGenerator.kt`
   - Stored in `UserPreferences` or new `CouncilLogEntity`

### Verification
- `./gradlew :aura-core:testDebugUnitTest --tests "*CouncilOrchestrator*"` passes
- `CouncilOrchestratorTest` verifies: stress signal → Researcher + Executive + Therapist agents debate → produces `SelfCareIntervention`

## Phase 4 — UI surfaces (2 commits, ~10h)

### Commit 4a — Council feed screen
1. **CouncilScreen**
   - List of ongoing and resolved interventions
   - Each card: agent icons, proposal title, rationale, vote tally, action button
   - Tapping opens thread view (debate transcript)
   - Route: `council` added to `NavGraph`
   - New files:
     - `app/src/main/kotlin/com/aura/ui/council/CouncilScreen.kt`
     - `app/src/main/kotlin/com/aura/ui/council/CouncilViewModel.kt`
     - `app/src/main/kotlin/com/aura/ui/council/InterventionCard.kt`
     - `app/src/main/kotlin/com/aura/ui/council/ThreadView.kt`

2. **CouncilViewModel**
   - State: `interventions: List<InterventionProposal>`, `selectedThread: InterventionProposal?`
   - Methods: `approve(intervention)`, `reject(intervention)`, `callEmergencyCouncil(topic)`

3. **Home card**
   - Add "Council" to `HomeSecondaryActions` cards (if not already there)
   - Badge shows count of pending interventions

### Commit 4b — Dream log + agent profile
1. **DreamLogScreen**
   - Morning-read view of last night's council activity
   - Swipeable cards: "The Researcher and Executive argued..."
   - Route: `dream_log` added to `NavGraph`
   - New file: `app/src/main/kotlin/com/aura/ui/council/DreamLogScreen.kt`

2. **AgentProfileScreen** (read-only v1)
   - Show agent mood, energy, current goal, relationships with other agents (radar/sparkline)
   - Route: `agent_profile/{agentId}`
   - New files:
     - `app/src/main/kotlin/com/aura/ui/council/AgentProfileScreen.kt`
     - `app/src/main/kotlin/com/aura/ui/council/AgentProfileViewModel.kt`

### Verification
- `./gradlew :app:testDebugUnitTest --tests "*Council*"` passes
- `./gradlew :app:assembleDebug` green
- Emulator screenshot of CouncilScreen

## Phase 5 — Live emergency council (1 commit, ~6h)

### Tasks
1. **EmergencyCouncilUseCase**
   - User types `/council {topic}` or taps "Call Council" on any screen
   - Immediately spawns 3-5 relevant agents
   - Runs live debate rounds (not overnight)
   - Streams results into chat as a Canvas/Artifact
   - New file: `aura-core/src/main/kotlin/com/aura/agent/council/EmergencyCouncilUseCase.kt`

2. **run_council tool upgrade**
   - Existing `run_council` tool currently routes to `AgentCouncil` (creative council)
   - Extend to support `mode=creative|life|emergency`
   - File: `aura-core/src/main/kotlin/com/aura/agent/tools/RunCouncilTool.kt`

3. **Chat integration**
   - When emergency council completes, post artifact to conversation
   - User can tap artifact to open CouncilScreen

### Verification
- End-to-end test: user sends "/council should I take this job?" → 3 agents debate → artifact posted → user approves intervention → task created

## Phase 6 — Agent relationship evolution (1 commit, ~5h)

### Tasks
1. **Relationship dynamics**
   - Agents that vote together gain affinity
   - Agents that disagree on high-stakes topics lose affinity
   - Low-affinity agents refuse to collaborate; high-affinity agents co-sponsor proposals
   - Implementation in `ForumEngine.recordVote()` + `AgentStateStore.updateRelationship()`

2. **Agent mood decay/recovery**
   - Mood decays if overused; recovers during idle
   - Burnout agent produces cynical responses and abstains more
   - New: `AgentMoodEngine.kt`

3. **Tests**
   - `AgentRelationshipEvolutionTest`: 20 votes → verify affinity drift
   - `AgentMoodDecayTest`: overuse → mood drops → abstain rate rises

## Phase 7 — Polish, safety, settings (1 commit, ~4h)

### Tasks
1. **Council settings**
   - Toggle: enable/disable overnight council
   - Toggle: allow auto-apply interventions without approval
   - Slider: council activity level (1-5)
   - Section in `SettingsScreen` (AI & Models group)
   - New file: `app/src/main/kotlin/com/aura/ui/settings/sections/CouncilSection.kt`

2. **Rate limiting**
   - Max 1 overnight council per 6 hours
   - Max 3 interventions pending at once
   - Prevent notification spam

3. **Privacy/transparency**
   - All council activity stored locally
   - User can delete all council logs
   - Every intervention shows which agents voted for/against

### Verification
- `./gradlew :aura-core:testDebugUnitTest :app:testDebugUnitTest :app:assembleDebug` all green
- Lint passes

## Verified against source (2026-08-04)

| Claim | Verified | Notes |
|-------|----------|-------|
| AgentDatabase is v1, single entity | YES | `entities=[AgentEntity::class]`, `version=1`, migrations array empty |
| AgentStore has seedBuiltins from Specialist.ALL | YES | 7 agents: general, coder, researcher, writer, creative, executive, phone_native |
| AgentCouncil exists but is task-only | YES | 231 lines, zero mentions of mood/relationship/observation/debate/vote/forum/dream — all greenfield |
| DaemonWorker has 8-step proactive pipeline | YES | 275 lines, injects proactive components as nullable (`? = null`) |
| ProactiveAwarenessEngine has 8 checks | YES | Returns `ProactiveFinding(type, title, message, urgency, actionRoute)` |
| PersonalityProfile has toPromptDirective() | YES | 48 lines, 6 dimensions |
| run_council tool exists | YES | 72 lines, routes to AgentCouncil (creative) |
| No "council" route in NavGraph | YES | 22 routes, none is "council" |
| No "council" card in HomeSecondaryActions | YES | 13 cards, none for council |
| AgentModule provides 2 methods only | YES | provideAgentDatabase + provideAgentDao, 24 lines |

## Corrections from source verification

1. **All agent-state concepts are greenfield, not extensions.** AgentCouncil is a task executor, not a living society. Mood, relationships, observations, debate, voting, forum, dream logs — all built from scratch. Plan phases reflect this.

2. **AgentDatabase has no migrations registered.** v1→v2 migration must be added to both the `migrations` array AND `AgentModule` needs `fallbackToDestructiveMigrationOnDowngrade` (same bug class as StrategyBanditModule v0.34.0).

3. **DaemonWorker injects proactive components as nullable.** CouncilOrchestrator must follow the same `? = null` pattern.

4. **AgentModule expansion.** Currently 24 lines / 2 methods. Adding 3 DAOs + AgentStateStore + ForumEngine means this file grows significantly. Budget for it.

5. **Life council agent selection.** Not all 7 specialists make sense for life councils. Plan should define: general (always), researcher (information gaps), writer (communication), executive (decisions/scheduling), creative (lateral thinking). coder and phone_native are excluded from life councils.

6. **Separate tool, not extension.** RunCouncilTool (72 lines) routes to AgentCouncil (creative). Life councils need a separate `RunLifeCouncilTool` or a `mode` parameter, not a bolt-on.

7. **Realistic estimate: 60-80h, not 42h.** LLM debate rounds with persona+mood+relationship injection, overnight orchestration, and UI for forum/thread/dream-log are complex greenfield work.

## Dependencies on existing Aura subsystems

| Existing subsystem | How The Council uses it |
|--------------------|------------------------|
| `AgentEntity` + `AgentStore` | Seed initial agent society from existing 7 specialists (5 participate in life councils) |
| `PersonalityProfile` | Each agent's debate voice — injected into debate prompts alongside mood + relationships |
| `MemoryStore` | Agents pull facts/observations about user from their private scopes |
| `ProactiveAwarenessEngine` | Supplies triggers for overnight councils (8 checks → council topics) |
| `DaemonWorker` | Runs councils during idle/charging as nullable injection (same pattern as existing proactive components) |
| `AuraThemeTokens` / `AuraCard` / `AuraScreenShell` | UI components reuse existing design system |
| `RunCouncilTool` | Left as-is for creative councils; new `RunLifeCouncilTool` for emergency life councils |
| `TaskDao` / `SetReminderTool` / `ScheduleTaskTool` | Intervention execution routes to these existing tools |

## Anti-scope (what we deliberately do NOT build)

- Agents do NOT send messages as the user without explicit approval
- Agents do NOT access other apps via AccessibilityService (keep within Aura's data)
- Agents do NOT run unsupervised code/interpreter (existing sandbox stays separate)
- Agents do NOT have explicit romantic/sexual personas (keep it a council, not a dating sim)
- No real-time push notifications for every debate post (only interventions)

## Testing strategy

| Layer | Tests |
|-------|-------|
| DB | Migration tests for AgentDatabase v1→v2→v3 |
| Domain | AgentStateStore, ForumEngine, CouncilOrchestrator unit tests |
| LLM debate | Mocked Brain responses; verify distinct agent stances |
| UI | CouncilScreen state-machine tests, ViewModel tests |
| End-to-end | `/council` chat command → artifact → approve → task |

## Risk and mitigations

| Risk | Mitigation |
|------|------------|
| LLM cost of nightly debates | Use resolveCheapModel; cap tokens per agent per round; max 5 agents × 3 rounds |
| Notification fatigue | Only notify on interventions, not every post; rate-limit 3 pending max |
| Agents feel samey | Seed from existing distinct specialists; inject personality + mood + relationships into prompts |
| User feels surveilled | All data local; transparent logs; user can reject any intervention |
| Test fragility from non-deterministic LLM | Mock Brain in unit tests; integration tests check structure, not exact wording |

## Estimated timeline (revised after source verification)

| Phase | Commits | Hours | Cumulative tests target |
|-------|---------|-------|------------------------|
| 1. Agent state persistence (DB v1→v2, 3 entities, 3 DAOs, store, migration) | 1 | 8 | +12 |
| 2. Forum bus (DB v2→v3, posts/votes, debate rounds, LLM persona prompts) | 1 | 10 | +20 |
| 3. Overnight Council (orchestrator, 5 intervention types, dream log, DaemonWorker nullable injection) | 1 | 12 | +28 |
| 4a. Council UI (screen, VM, intervention cards, thread view, Home card, NavGraph) | 1 | 8 | +35 |
| 4b. Dream log + agent profiles (2 screens, VM, mood visualization) | 1 | 6 | +40 |
| 5. Emergency council (live debate, RunLifeCouncilTool, chat artifact integration) | 1 | 10 | +48 |
| 6. Relationship evolution (affinity drift, mood decay, burnout, co-sponsorship) | 1 | 8 | +54 |
| 7. Settings + safety + polish (toggles, rate limiting, transparency, deletion) | 1 | 5 | +58 |
| **Total** | **8 commits** | **~67h** | **+58 tests** |

## First commit (Phase 1) precise scope

Goal: agents have persistent state and relationships.

Files to create:
- `aura-core/src/main/kotlin/com/aura/agent/state/AgentStateEntity.kt`
- `aura-core/src/main/kotlin/com/aura/agent/state/AgentRelationshipEntity.kt`
- `aura-core/src/main/kotlin/com/aura/agent/state/AgentObservationEntity.kt`
- `aura-core/src/main/kotlin/com/aura/agent/state/AgentStateDao.kt`
- `aura-core/src/main/kotlin/com/aura/agent/state/AgentRelationshipDao.kt`
- `aura-core/src/main/kotlin/com/aura/agent/state/AgentObservationDao.kt`
- `aura-core/src/main/kotlin/com/aura/agent/state/AgentStateStore.kt`
- `aura-core/src/test/kotlin/com/aura/agent/state/AgentStateStoreTest.kt`

Files to edit:
- `aura-core/src/main/kotlin/com/aura/agent/db/AgentDatabase.kt` (entities, DAOs, migration)
- `aura-core/src/main/kotlin/com/aura/agent/db/AgentModule.kt` (provide DAOs + store)
- `aura-core/src/main/kotlin/com/aura/agent/AgentStore.kt` (seed initial state when creating builtins)

Verification:
- `./gradlew :aura-core:testDebugUnitTest --tests "*AgentStateStore*"`
- `./gradlew :aura-core:testDebugUnitTest --tests "*AgentDatabase*"`

---

Ready to execute Phase 1 on "go."
