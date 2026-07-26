# Multi-Agent Chat + Scheduled Tasks — Comprehensive Implementation Plan

> For Hermes: plan+execute in the same session. Start with Phase 1 (chat agent picker) immediately after the plan is saved.

**Goal:** Make agents first-class chat personas, enable multi-agent threads and council UI, and turn the existing proactive workers into a user-facing scheduled-task system with conditional triggers.

**Architecture:** Reuse the existing `AgentStore`/`AgentEntity` substrate and WorkManager scheduler. The bulk of the work is wiring existing infrastructure to the UI, not inventing new subsystems.

**Tech stack:** Android Kotlin, Compose, Hilt, Room, WorkManager.

---

## Pre-Audit: What Exists vs What's Needed

| Component | Status | Evidence |
|-----------|--------|----------|
| `AgentStore` + `AgentEntity` + 7 builtins | EXISTS | `aura-core/.../agent/AgentStore.kt`, seeded from `Specialist.ALL` in `ProactiveBootstrap` |
| `AgentEntity` fields: name, identity, personality, memoryScope, toolSet, preferredModel | EXISTS | `aura-core/.../agent/AgentEntity.kt` |
| `delegate_to_agent` tool | EXISTS | `aura-core/.../tools/DelegateToAgentTool.kt` |
| `run_council` tool | EXISTS | `aura-core/.../tools/RunCouncilTool.kt` |
| `AgentCouncil` | EXISTS | `aura-core/.../agent/AgentCouncil.kt` |
| Per-agent memory scopes (`general` + `agent:<name>`) | EXISTS | `DelegateToAgentTool.kt:152`, `MemoryStore.query(scopeFilter=...)` |
| Chat UI `activeAgentId` field | EXISTS | `ChatUiState.activeAgentId` derived from `selectedSpecialist` |
| Specialist picker in chat | EXISTS | `ChatViewModel.setSpecialist` uses legacy `Specialist` enum, not `AgentStore` |
| `AgentEditorScreen` for creating custom agents | EXISTS | `app/.../screens/AgentEditorScreen.kt` |
| AgentRun / Hands executor | EXISTS | `AgentRunStore`, `AgentRunExecutorWorker`, `DagResolver` |
| Proactive WorkManager jobs: MorningBrief, Decay, Dream, Daemon, Evolution | EXISTS | `ProactiveScheduler.kt` |
| `UserPreferences` toggles for morning brief, calendar, evolution, dream, decay | EXISTS | `ProactiveBootstrap.kt` reconciliation |
| `ReminderEditorDialog` + `RemindersScreen` | EXISTS | `app/.../screens/ReminderEditorDialog.kt`, `RemindersScreen.kt` |
| `TaskDB` v2 with reminders table | EXISTS | `TaskDatabase.kt`, `TaskModule.kt` MIGRATION_1_2 |

| Missing piece | Evidence |
|-------------|----------|
| Chat agent picker uses `AgentStore`, not `Specialist` | `ChatViewModel.setSpecialist` references `Specialist?`; `AgentStore` not injected |
| No visual agent indicator in chat header | `ChatHeader` shows model pill + mode chips, no agent identity |
| No "@agent" mention routing in composer | `ChatComposer.kt` not inspected yet; no mention parsing expected |
| `run_council` is a tool call only — no UI panel | no `CouncilScreen` or council route found |
| No upcoming scheduled work UI | `ProactiveHistoryScreen` shows past events only |
| No user-created recurring tasks with custom recurrence | `ReminderEditorDialog` uses one-shot `WorkManager`; no recurrence picker |
| No conditional triggers for schedules | no trigger engine file found |
| Agents cannot schedule their own follow-ups | `DelegateToAgentTool` returns text; no scheduling API exposed to agents |

---

## Phase 1: Chat Agent Picker (single-session, high impact)

### Objective
Replace the legacy `Specialist` picker with a real `AgentStore` dropdown so custom agents work as chat personas.

### Files
- Modify: `app/src/main/kotlin/com/aura/ui/viewmodel/ChatViewModel.kt:306-324` (constructor), `:610-620` (`setSpecialist`)
- Modify: `app/src/main/kotlin/com/aura/ui/screens/chat/ChatHeader.kt:57-75` (signature), `:100-151` (model pill)
- Modify: `app/src/main/kotlin/com/aura/ui/screens/chat/ChatRoute.kt` (pass agent state + callbacks)
- Test: `app/src/test/kotlin/com/aura/ui/viewmodel/ChatViewModelTest.kt` or create `ChatAgentPickerTest.kt`

### Tasks

#### Task 1.1: Inject AgentStore into ChatViewModel

```kotlin
private val agentStore: com.aura.agent.AgentStore,
```

Add to constructor after `tasteEngine`. This is safe because `AgentStore` is a `@Singleton`.

#### Task 1.2: Add `availableAgents` Flow collection in init

```kotlin
viewModelScope.launch {
    agentStore.all.collect { agents ->
        _state.update { it.copy(availableAgents = agents) }
    }
}
```

Add `availableAgents: List<AgentEntity> = emptyList()` to `ChatUiState`.

#### Task 1.3: Rewrite `setSpecialist` as `setActiveAgent(agent: AgentEntity?)`

```kotlin
fun setActiveAgent(agent: com.aura.agent.AgentEntity?) {
    _state.update { old ->
        val newModel = agent?.preferredModel?.takeIf { it.isNotBlank() }
            ?: old.activeModel
        val agentId = agent?.let { "agent_${it.name}" }
        old.copy(
            selectedSpecialist = null,
            activeAgent = agent,
            activeAgentId = agentId,
            activeModel = newModel,
        )
    }
}
```

Deprecate `selectedSpecialist` (keep field for backward compatibility but stop writing it). Update `newConversation()` / `startIsolatedSession()` / other clear sites to also clear `activeAgent`.

#### Task 1.4: Update ChatHeader to show agent + model pill

Change signature to accept `activeAgent: AgentEntity?` and `availableAgents: List<AgentEntity>`. Replace the model pill text with:
- If `activeAgent != null`: show agent name in the pill, with a smaller model label below or inside.
- Else: show current model pill as before.

The pill remains clickable; the dropdown now shows agents at the top and "Model only" option at the bottom. Tapping an agent calls `onAgentSelected`. Tapping "Model only" clears the agent.

#### Task 1.5: Wire ChatRoute

Pass `activeAgent` and `availableAgents` from `_state` to `ChatHeader`, and wire `onAgentSelected = { vm.setActiveAgent(it) }`.

#### Task 1.6: Update system prompt routing

Find where `selectedSpecialist` is used to build the system prompt (likely in `ChatSendController` or `MemoryAugmentedAgenticLoop`). Replace with `activeAgentId` lookup against `AgentStore.byName(...)` to inject the agent's identity + personality.

If the send controller doesn't have `AgentStore`, pass the resolved agent identity from the ViewModel as a string.

#### Task 1.7: Tests

Write `ChatAgentPickerTest`:
- `setActiveAgent_updatesActiveAgentIdAndModel()`
- `availableAgents_flowUpdatesUiState()`
- `newConversation_clearsActiveAgent()`

Use `agentStore = mockk<AgentStore>(relaxed = true)` and stub `every { agentStore.all } returns flowOf(listOf(...))`.

#### Task 1.8: Commit

```bash
git add app/src/main/kotlin/com/aura/ui/viewmodel/ChatViewModel.kt \
        app/src/main/kotlin/com/aura/ui/screens/chat/ChatHeader.kt \
        app/src/main/kotlin/com/aura/ui/screens/chat/ChatRoute.kt \
        app/src/test/...
git commit -m "feat(chat): agent picker replaces Specialist dropdown

- ChatViewModel now reads AgentStore and exposes availableAgents
- ChatHeader shows active agent + model pill, dropdown lists agents
- setActiveAgent wires agent identity/personality/model into chat
- Tests: 3 new cases for agent selection/clear/flow"
```

---

## Phase 2: Multi-Agent Chat Threads

### Objective
Allow a conversation to contain messages from multiple agents, either via `@agent` mentions or explicit agent chips.

### Files
- Modify: `app/src/main/kotlin/com/aura/ui/screens/chat/ChatComposer.kt`
- Modify: `app/src/main/kotlin/com/aura/ui/viewmodel/ChatViewModel.kt`
- Modify: `aura-core/src/main/kotlin/com/aura/agent/Conversation.kt`
- Create: `app/src/main/kotlin/com/aura/ui/components/AgentMentionChip.kt`
- Test: `app/src/test/.../ChatMentionTest.kt`

### Tasks

#### Task 2.1: Parse @agent mentions in composer

Add a helper:

```kotlin
fun String.extractAgentMentions(availableAgents: List<String>): List<String>
```

Detects `@name` tokens that match an available agent. Show autocomplete chips when the user types `@`.

#### Task 2.2: Add `agentId` to `Turn`

```kotlin
data class Turn(
    val role: Role,
    val text: String = "",
    val agentId: String? = null, // non-null for agent-authored turns
    ...
)
```

Update `Conversation.addAssistant` to accept optional `agentId`. Update backup/JSON serialization.

#### Task 2.3: Send pipeline routes per mention

In `ChatSendController`, if the user message contains agent mentions:
- For each mention in order, call `delegateToAgentTool.delegate(...)` with the relevant sentence/paragraph.
- Insert each result as a `Turn(role=assistant, agentId=...)` into the conversation.
- If no mentions, use the normal loop.

#### Task 2.4: UI renders agent-authored turns

In `ChatTimeline`/`MessageBubble`, show a small agent avatar + name label for turns where `agentId != null`. User turns stay unchanged.

#### Task 2.5: Tests

- `extractAgentMentions_findsKnownAgents`
- `send_withMention_insertsAgentTurn`
- `send_withoutMention_usesNormalLoop`

#### Task 2.6: Commit

```bash
git commit -m "feat(chat): @agent mentions route to delegated agents

- Composer parses @name and shows autocomplete
- Turn.agentId stores agent-authored messages
- Send controller delegates per mention and inserts agent turns
- Timeline renders agent avatar + name on agent turns"
```

---

## Phase 3: Agent Council UI

### Objective
Surface `run_council` as a first-class UI panel, not just a tool call.

### Files
- Create: `app/src/main/kotlin/com/aura/ui/screens/council/CouncilScreen.kt`
- Create: `app/src/main/kotlin/com/aura/ui/viewmodel/CouncilViewModel.kt`
- Modify: `app/src/main/kotlin/com/aura/ui/nav/NavGraph.kt`
- Modify: `aura-core/src/main/kotlin/com/aura/agent/AgentCouncil.kt` (expose progress stream)
- Test: `app/src/test/.../CouncilViewModelTest.kt`

### Tasks

#### Task 3.1: Add streaming council state

`AgentCouncil.run()` currently returns a single `CouncilResult`. Add an optional `onProgress: (CouncilProgress) -> Unit` callback that emits:
- `ProposalsStarted`
- `ProducerDone(agentName, output)`
- `DirectorStarted`
- `DirectorDone(final)`

#### Task 3.2: Create CouncilViewModel

State:
- `task: String`
- `selectedAgentIds: List<String>`
- `availableAgents: List<AgentEntity>`
- `progress: List<CouncilProgress>`
- `result: CouncilResult?`
- `running: Boolean`

Methods: `addAgent`, `removeAgent`, `runCouncil()`.

#### Task 3.3: Create CouncilScreen

Two-pane on large screens, single pane on phones:
- Top: task input + agent chips (multi-select)
- Bottom: progress cards (agent responses stream in) + director synthesis card.

#### Task 3.4: Wire NavGraph route

Add `"council"` route with slide-up transition. Add a Council entry point from ChatHeader overflow menu.

#### Task 3.5: Tests

- `runCouncil_emitsProgressEvents`
- `councilResult_updatesState`

#### Task 3.6: Commit

```bash
git commit -m "feat(council): dedicated council UI with streaming progress

- AgentCouncil exposes progress callbacks
- CouncilViewModel + CouncilScreen
- NavGraph route + chat header entry point"
```

---

## Phase 4: Persistent Agent Relationships

### Objective
Agents remember prior interactions in their own scope and adjust tone via `TasteEngine`.

### Files
- Modify: `aura-core/src/main/kotlin/com/aura/agent/TasteEngine.kt`
- Modify: `aura-core/src/main/kotlin/com/aura/agent/MemoryAugmentedAgenticLoop.kt`
- Modify: `aura-core/src/main/kotlin/com/aura/tools/DelegateToAgentTool.kt`
- Test: `aura-core/src/test/.../AgentTasteTest.kt`

### Tasks

#### Task 4.1: Record Taste signals per agent

`TasteEngine.recordSignal` currently takes a generic signal. Ensure `agentId` is included in `RoutingOutcome` and `PreferenceSignal` entities. Update `TasteEngine.aggregate()` to bucket per agent.

#### Task 4.2: Inject agent profile into main loop system prompt

In `MemoryAugmentedAgenticLoop`, if `activeAgentId` is non-null, resolve the agent via `AgentStore.byName()` and prepend `identity + personality` to the system prompt (same as delegation does).

#### Task 4.3: Delegation stores interaction memory in agent scope

`DelegateToAgentTool` already recalls from agent scope. Ensure the final delegation result is also stored as a memory in the agent's scope with category "delegation" so future delegations have context.

#### Task 4.4: Tests

- `recordSignal_includesAgentId`
- `loop_withActiveAgentId_prependsAgentPersonality`

#### Task 4.5: Commit

```bash
git commit -m "feat(agents): persistent per-agent memory + taste signals

- TasteEngine buckets signals per agentId
- Main loop injects active agent identity/personality into prompt
- Delegation stores results in agent memory scope"
```

---

## Phase 5: User-Created Recurring Tasks

### Objective
Turn `ReminderEditorDialog` into a real recurring task scheduler with daily/weekly/custom recurrence.

### Files
- Create: `aura-core/src/main/kotlin/com/aura/tasks/Recurrence.kt`
- Modify: `aura-core/src/main/kotlin/com/aura/tasks/TaskEntity.kt`
- Modify: `aura-core/src/main/kotlin/com/aura/tasks/TaskDatabase.kt` (migration v2→v3)
- Modify: `aura-core/src/main/kotlin/com/aura/tasks/TaskModule.kt`
- Modify: `aura-core/src/main/kotlin/com/aura/tasks/TaskScheduler.kt`
- Modify: `app/src/main/kotlin/com/aura/ui/screens/ReminderEditorDialog.kt`
- Test: `aura-core/src/test/.../RecurrenceTest.kt`, `TaskSchedulerTest.kt`

### Tasks

#### Task 5.1: Add recurrence fields to TaskEntity

```kotlin
val recurrenceRule: String? = null, // ISO 8601 RRule or simple "daily", "weekly", "weekdays"
val recurrenceInterval: Int = 1,
val nextScheduledAt: Long? = null,
```

#### Task 5.2: Room migration v2→v3

Add `ALTER TABLE tasks ADD COLUMN recurrence_rule TEXT;` etc.

#### Task 5.3: Implement Recurrence calculator

Pure function:

```kotlin
fun nextOccurrence(after: Instant, rule: Recurrence): Instant?
```

Support: one-shot, daily, weekly, weekdays, custom interval.

#### Task 5.4: Update TaskScheduler

For recurring tasks, schedule the next occurrence after completion. For one-shot, keep existing behavior.

#### Task 5.5: Update ReminderEditorDialog UI

Add recurrence chips: Once, Daily, Weekdays, Weekly, Custom interval. Store rule string.

#### Task 5.6: Tests

- `nextOccurrence_daily`
- `nextOccurrence_weekdays_skips_weekend`
- `scheduler_recurring_enqueuesNextInstance`

#### Task 5.7: Commit

```bash
git commit -m "feat(tasks): recurring reminders with daily/weekly/weekdays rules

- TaskEntity gains recurrence fields
- Room migration 2→3
- Recurrence.nextOccurrence pure calculator
- TaskScheduler reschedules recurring tasks after run"
```

---

## Phase 6: Conditional Triggers

### Objective
Allow tasks/schedules to fire on events: web result changed, email received, location changed, etc.

### Files
- Create: `aura-core/src/main/kotlin/com/aura/triggers/TriggerCondition.kt`
- Create: `aura-core/src/main/kotlin/com/aura/triggers/TriggerEngine.kt`
- Create: `aura-core/src/main/kotlin/com/aura/triggers/WebChangeDetector.kt`
- Create: `aura-core/src/main/kotlin/com/aura/triggers/TriggerWorker.kt`
- Modify: `aura-core/src/main/kotlin/com/aura/tasks/TaskScheduler.kt`
- Modify: `aura-core/src/main/kotlin/com/aura/proactive/ProactiveBootstrap.kt` (register trigger worker)
- Test: `aura-core/src/test/.../TriggerEngineTest.kt`

### Tasks

#### Task 6.1: Data model for trigger conditions

```kotlin
sealed class TriggerCondition {
    data class Schedule(val cron: String, val timezone: String) : TriggerCondition()
    data class WebChanged(val url: String, val selector: String? = null) : TriggerCondition()
    data class LocationEntered(val lat: Double, val lon: Double, val radiusMeters: Double) : TriggerCondition()
    data class IntentReceived(val action: String) : TriggerCondition()
}
```

#### Task 6.2: TriggerEngine

Evaluates a list of registered triggers. For each:
- Schedule → compare to current time.
- WebChanged → fetch URL, hash content, compare to stored hash.
- Location → placeholder (requires FusedLocationProvider; mark as needing permissions).
- IntentReceived → handled by BroadcastReceiver.

#### Task 6.3: TriggerWorker

Periodic WorkManager job (15 min minimum) that calls `TriggerEngine.checkAll()`. For fired triggers, enqueue the associated action (run a hand, send a notification, start a chat).

#### Task 6.4: Action model

```kotlin
sealed class TriggerAction {
    data class RunHand(val handId: String) : TriggerAction()
    data class Notify(val title: String, val body: String) : TriggerAction()
    data class StartChat(val prompt: String) : TriggerAction()
}
```

#### Task 6.5: Tests

- `triggerEngine_schedule_firesAtExpectedTime`
- `triggerEngine_webChanged_firesWhenHashChanges`
- `triggerEngine_webChanged_quietWhenUnchanged`

#### Task 6.6: Commit

```bash
git commit -m "feat(triggers): conditional schedule engine

- TriggerCondition sealed class (schedule, web changed, location, intent)
- TriggerEngine evaluates registered triggers and fires actions
- TriggerWorker periodic 15m evaluation
- Action types: run hand, notify, start chat"
```

---

## Phase 7: Agents Can Schedule Follow-ups

### Objective
Let a delegated agent or the main agent schedule a future task for itself via a new tool.

### Files
- Create: `aura-core/src/main/kotlin/com/aura/tools/ScheduleTaskTool.kt`
- Modify: `aura-core/src/main/kotlin/com/aura/tools/ToolsModule.kt` (register)
- Modify: `aura-core/src/main/kotlin/com/aura/agent/ToolRegistry.kt` (no-op if auto-discovered)
- Test: `aura-core/src/test/.../ScheduleTaskToolTest.kt`

### Tasks

#### Task 7.1: ScheduleTaskTool

Parameters:
- `title`: String
- `due_at`: ISO 8601 timestamp
- `recurrence`: optional "daily" / "weekly" / "weekdays"
- `action`: "notify" or "start_chat"
- `prompt`: String (for chat action)

Creates a `TaskEntity` + schedules via `TaskScheduler`. Returns confirmation.

#### Task 7.2: Register in ToolsModule

Inject `TaskScheduler` and `TaskDao` into tool. Add to tool registry.

#### Task 7.3: Tests

- `scheduleTaskTool_createsTaskAndSchedules`
- `scheduleTaskTool_invalidDateReturnsError`

#### Task 7.4: Commit

```bash
git commit -m "feat(tools): agents can schedule follow-up tasks

- schedule_task tool creates TaskEntity + schedules WorkManager job
- Supports one-shot, daily, weekly, weekdays recurrence
- Available to main agent and delegated agents"
```

---

## Phase 8: Upcoming Scheduled Work UI

### Objective
Show the user what is scheduled and let them cancel/edit.

### Files
- Create: `app/src/main/kotlin/com/aura/ui/screens/schedule/ScheduleScreen.kt`
- Create: `app/src/main/kotlin/com/aura/ui/viewmodel/ScheduleViewModel.kt`
- Modify: `app/src/main/kotlin/com/aura/ui/nav/NavGraph.kt` (add route)
- Modify: `app/src/main/kotlin/com/aura/ui/screens/SettingsScreen.kt` or bottom nav (add entry)
- Test: `app/src/test/.../ScheduleViewModelTest.kt`

### Tasks

#### Task 8.1: ScheduleViewModel

Expose:
- `upcomingTasks: Flow<List<TaskEntity>>` from `TaskDao` sorted by `nextScheduledAt`
- `proactiveJobs: List<ScheduledJobInfo>` from `WorkManager.getWorkInfosByTag("*")` or a static list
- Methods: `cancelTask(id)`, `runNow(id)`

#### Task 8.2: ScheduleScreen

Two tabs:
- Tasks (reminders + agent-scheduled + recurring)
- Proactive jobs (morning brief, decay, dream, daemon, evolution) with on/off toggles

#### Task 8.3: NavGraph route + bottom nav

Add `"schedule"` route. Add to `AuraBottomBar` as a 5th icon or move under Settings. Given the user's complaint about bottom nav clutter (v0.9.0 fix dropped Graph to keep 4 items), place Schedule under Settings → "Scheduled tasks & proactive" section, not as a top-level tab.

#### Task 8.4: Tests

- `upcomingTasks_sortedByNextScheduledAt`
- `cancelTask_callsTaskSchedulerCancel`

#### Task 8.5: Commit

```bash
git commit -m "feat(schedule): upcoming work UI with cancel/run-now

- ScheduleViewModel exposes tasks + proactive jobs
- ScheduleScreen with Tasks + Proactive tabs
- Entry point in Settings, not bottom nav (keeps 4 tabs)"
```

---

## Phase 9: Integration + Final Verification

### Objective
Full test suite green, assembleDebug, release APK.

### Tasks

#### Task 9.1: Run full local gate

```bash
./gradlew :aura-core:testDebugUnitTest :app:testDebugUnitTest :app:assembleDebug --no-daemon
```

#### Task 9.2: Fix any broken tests

Likely impacted tests:
- `ChatViewModelTest` due to new `AgentStore` constructor param
- `MemoryAugmentedAgenticLoopTest` due to agent prompt injection
- Migration tests due to TaskDB v3

Add `mockk<AgentStore>(relaxed = true)` stubs and bump migration assertions.

#### Task 9.3: Build release APK + GitHub Release

```bash
cp app/build/outputs/apk/debug/app-debug.apk releases/aura-debug-v0.36.0.apk
gh release create v0.36.0 releases/aura-debug-v0.36.0.apk \
    --title "Aura Android v0.36.0" \
    --notes-file releases/RELEASE_NOTES_v0.36.0.md
```

---

## Dependency Graph

```
Phase 1 (chat agent picker)
    ↓
Phase 2 (multi-agent mentions)  ← depends on Phase 1 state fields
    ↓
Phase 4 (persistent relationships) ← depends on Phase 1 activeAgentId
    ↓
Phase 3 (council UI)              ← independent of Phase 1/2, but uses AgentStore
Phase 5 (recurring tasks)         ← independent
    ↓
Phase 6 (conditional triggers)    ← depends on Phase 5 TaskScheduler
Phase 7 (agents schedule tasks)   ← depends on Phase 5 + 6
    ↓
Phase 8 (scheduled work UI)        ← depends on Phase 5 + 6 + 7
    ↓
Phase 9 (verification + release)
```

Phases 1 and 5 can be done in parallel after the plan is saved. Phase 3 and 4 can also run in parallel once Phase 1 is done. For a single-session execution, the safest order is 1 → 2 → 4 → 3 → 5 → 6 → 7 → 8 → 9.

---

## Quantitative Scope Summary

| Phase | # Tasks | New Files | Modified Files | New Tests | Risk |
|-------|---------|-----------|----------------|-----------|------|
| 1 — Chat agent picker | 8 | 1 | 4 | 3 | Low |
| 2 — @agent mentions | 6 | 2 | 5 | 3 | Medium |
| 3 — Council UI | 6 | 3 | 3 | 2 | Medium |
| 4 — Persistent relationships | 5 | 0 | 4 | 2 | Low |
| 5 — Recurring tasks | 7 | 2 | 6 | 3 | Medium (migration) |
| 6 — Conditional triggers | 6 | 5 | 3 | 3 | Medium |
| 7 — Agent scheduling tool | 4 | 1 | 2 | 2 | Low |
| 8 — Schedule UI | 5 | 2 | 3 | 2 | Low |
| 9 — Verification + release | 3 | 0 | 2 | 0 | Low |
| **Total** | **50** | **16** | **32** | **20** | — |

---

## Triage: What to Ship First

Given the user's "do all of them" signal, execute all phases in order. If a phase turns out larger than expected:
- Phase 5 (recurring tasks) is the most likely to need splitting because of the Room migration. Ship the migration + calculator first, then the UI.
- Phase 6 (conditional triggers) can be split: schedule + web-change first; location/intent deferred.

The single-session minimum viable deliverable that satisfies "couple of agents in the chat" is **Phases 1 + 2 + 3 + 4**. The scheduled-task deliverable is **Phases 5 + 6 + 7 + 8**. Plan says do all, in order.

---

## Prior Plans Alignment

- No prior plan for multi-agent chat UI found in `.hermes/plans/` during this audit.
- Existing agent infrastructure was shipped across commits in July 2026 (AgentStore, DelegateToAgentTool, RunCouncilTool, AgentCouncil). This plan extends that work to the chat surface.
- Proactive/scheduler infrastructure shipped across multiple sessions; this plan adds user-facing recurrence + conditional triggers.
