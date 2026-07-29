# Round 8 Deep Review Fixes

## Context
Deep review of Aura Android v0.39.0 (504 .kt files, 72K LOC, 1,425 tests, 0 failures) found 3 P0 + 5 P1 issues. All prior-session fixes verified in place (22-item verification pass, 0 false claims).

## Items (8 total)

### P0-1. AgentRunExecutorWorker — truncate step output before persisting
- File: `aura-core/src/main/kotlin/com/aura/agentrun/AgentRunExecutorWorker.kt:129`
- Bug: `completeStep(step.id, result.output)` stores raw output. A deep_research returning 20K flows untruncated into step dependencies.
- Fix: Apply `truncateToolResult(result.output)` before `completeStep`.
- Test: existing AgentRunExecutorWorker tests should still pass (mocked tools return short strings).

### P0-2. HandRepository — truncate per-step output before accumulating
- File: `aura-core/src/main/kotlin/com/aura/hands/HandRepository.kt:179`
- Bug: `outputs += "Step N (tool): ${result.output}"` — full output appended. 5 steps × 4K = 20K returned to caller.
- Fix: Apply `truncateToolResult(result.output)` per step.
- Note: `MAX_HISTORY_OUTPUT_CHARS = 8_000` only applies to persisted record, not in-flight string.

### P0-3. DelegateToAgentTool — truncate intermediate tool results
- File: `aura-core/src/main/kotlin/com/aura/tools/DelegateToAgentTool.kt:241`
- Bug: `resultText = result.output` — intermediate results in mini-loop not truncated. Only final response is `.take(2000)`.
- Fix: Apply `truncateToolResult(resultText)` before adding to conversation.

### P1-1. BootReceiver — re-enqueue TriggerWorker on cold boot
- File: `app/src/main/kotlin/com/aura/proactive/BootReceiver.kt`
- Bug: Enqueues Decay/Daemon/MorningBrief/Dream/Evolution workers but NOT TriggerWorker. User-defined triggers don't fire after reboot until app is opened.
- Fix: Add `TriggerWorker.schedule(appContext)` after the evolution worker block.

### P1-4. MCP client version string drifted (0.38.0 vs 0.39.0)
- File: `aura-core/src/main/kotlin/com/aura/mcp/McpConnection.kt:58`
- Bug: `put("version", "0.38.0")` — app is 0.39.0.
- Fix: Update to "0.39.0".

### P1-2. README stale (version, test counts, test file count)
- File: `README.md`
- Bug: Says v0.38.0/versionCode 43 (actual v0.39.0/47), 253 test files/1,423 tests (actual 262/1,425).
- Fix: Update version, versionCode, test file count, test count.

### P1-5. EvolutionApplySaga — 17 silent runCatching need .onFailure logging
- File: `aura-core/src/main/kotlin/com/aura/evolution/EvolutionApplySaga.kt`
- Bug: 17 `runCatching { Json.decodeFromString(...) }` with no failure handler. Malformed args → action silently fails, user sees "Applied successfully."
- Fix: Add `.onFailure { Log.w(TAG, "args parse failed for ${proposal.action}: ${it.message}") }` to each silent site.

### P1-7. TriggerCondition.LocationEntered — permanently unreachable
- File: `aura-core/src/main/kotlin/com/aura/triggers/TriggerEngine.kt:22`
- Bug: Engine returns null for LocationEntered. UI lets users create location triggers that never fire.
- Fix: Remove LocationEntered from the trigger creation UI (TriggersSection.kt). Keep the sealed class type for future implementation but don't show it as an option.

## Verification
After all fixes: `./gradlew :aura-core:testDebugUnitTest :app:testDebugUnitTest` — expect 1,425+ tests, 0 failures. Then `./gradlew :app:assembleDebug` — expect BUILD SUCCESSFUL.