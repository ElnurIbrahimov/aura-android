# v0.39.1 — Round 8 Deep Review Fixes

## P0 — Critical

- **AgentRunExecutorWorker**: step output now truncated via `truncateToolResult()` before persisting. Previously raw 20K+ outputs from deep_research could blow step dependencies on small context models.
- **HandRepository**: per-step output now truncated via `truncateToolResult()` before accumulating. Previously 5 steps × 4K = 20K+ returned untruncated to the agentic loop.
- **DelegateToAgentTool**: intermediate tool results now truncated via `truncateToolResult()` before appending to conversation. Previously only the final response was `.take(2000)`.

## P1 — High

- **BootReceiver**: re-enqueues TriggerWorker on cold boot. Previously user-defined triggers didn't fire after reboot until app was opened.
- **MCP client version**: updated from 0.38.0 to 0.39.0.
- **README**: updated to v0.39.0 (versionCode 47), 262 test files, 1425 tests.
- **EvolutionApplySaga**: 13 silent `runCatching` blocks now have `.onFailure` logging. Previously malformed args JSON caused actions to silently fail while UI showed "Applied successfully."
- **TriggerCondition.LocationEntered**: labeled "not yet implemented" in UI display. Users cannot create location triggers (UI only offers Schedule); existing display is honest about status.

## Verification

- 1,425 tests, 0 failures
- assembleDebug green
- 38MB debug APK