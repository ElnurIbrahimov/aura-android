# Aura Android v0.36.1 — Integration cleanup + silent-failure audit

## Fixes
- Council result now wires back into the active chat conversation.
- `schedule_task` / WorkManager cancellation uses task-specific tags; `TaskScheduler.cancel()` actually cancels reminders.
- `@agent` mentions propagate the active agent ID through `ToolContext` for agent-scoped memory and delegated runs.
- Removed legacy `selectedSpecialist` / `suggestedSpecialist` dual state in `ChatUiState`; `activeAgent` is now the single source of truth. Deleted unused `SpecialistChips` component.
- Reviewed all 258 `runCatching` sites in main source; added `.onFailure { Log.w(...) }` to silent swallowers and documented 3 verified false positives in the audit report.

## Docs
- Updated `README.md` to v0.36.0 / versionCode 41, 239 test files, 1,596 tests, 59 tools.
- Updated `docs/architecture.md` tool/provider counts.
- Updated `ENGINEERING_HISTORY.md` timeline.
- Audit report: `.hermes/audits/runcatching-silent-sites-2026-07-27.md`.

## Tests
- Full suite green: `:app:testDebugUnitTest` + `:aura-core:testDebugUnitTest`.
- `:app:assembleDebug` green.
