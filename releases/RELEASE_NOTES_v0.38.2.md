# Aura Android v0.38.2 — Deep Review Fixes

## Fixes
- **Version sync**: bumped versionName to 0.38.2 / versionCode 44 (previous APK reported 0.38.0 while release metadata said 0.38.1).
- **AgentRun ToolContext threading**: background hand/pipeline steps now inherit approved remote-cost tools, incognito/memoryEnabled flag, active agent, and originating user message from the chat session that triggered them. Prevents hands silently pausing on paid tools.
- **Task recurrence**: `schedule_task` now actually writes the `recurrence` field to `TaskEntity`; recurring tasks now repeat.

## Tests
- 1432 tests, 0 failures (was 1430).
- New: `AgentRunStoreTest` metadata persistence, `HandRunEnqueuerTest` context serialization, `ScheduleTaskToolTest` recurrence assertion.
