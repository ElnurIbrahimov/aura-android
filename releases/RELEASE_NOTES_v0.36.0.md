# Aura Android v0.36.0 — Multi-agent chat + scheduled tasks

## New features
- Chat agent picker: switch between agents in the input bar.
- @agent mentions in chat: delegate a question to any agent inline.
- Agent council UI: pick multiple agents and run them in parallel.
- Per-agent identity injection in system prompt.
- Agent-scoped taste signals and delegated-agent memory storage.
- Weekdays recurrence for reminders.
- `schedule_task` tool: agents can schedule future notifications or chat starts.
- Upcoming Schedule screen with Tasks/Reminders tabs.
- Conditional trigger engine: scheduled + web-change triggers with SSRF-safe fetch.
- Trigger Worker runs every 15 minutes, gated by Settings.

## Tests
- 4 new test files: AgentTasteTest, MemoryAugmentedAgenticLoopAgentPersonalityTest, ScheduleTaskToolTest, TaskSchedulerTest, TriggerEngineTest, ReminderRecurrenceTest.
- Full suite green.

## Notes
- TaskDatabase migrated to v5 (recurrence column).
- `aura-android` remote branch `feat/tier-1-friction`.
