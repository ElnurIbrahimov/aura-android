# Aura Android — Deep Review Fixes Phase 2

Source: independent deep review of HEAD `828b3032` (v0.38.2).
Goal: fix the three real P1/P2 findings surfaced in round-2 digging.

---

## 1. TriggerWorker: wire RunHand and StartChat actions

### Problem
`TriggerWorker.kt:37-46` has TODO stubs for `TriggerAction.RunHand` and `TriggerAction.StartChat`. Only `Notify` actually works. User-defined triggers that fire hands or chat are silently dropped.

### Fix
- `RunHand`: call `HandRunEnqueuer.enqueue(handIdOrName, variablesJson="{}", trigger="trigger:${trigger.id}", conversationId="", modelId="")` and pass a default empty `ToolContext` snapshot. This reuses the same background-run infrastructure as `RunHandTool`.
- `StartChat`: post a notification via `NotificationsTool` with a tap action that opens `MainActivity`/`ChatScreen` with the prompt. For v1, serialize the prompt into the notification extras and let the launcher consume it; if no notification helper supports extras, fall back to a "tap to open Aura" notification that opens the app (user can then paste/see prompt in history later).
- Add tests: `TriggerWorkerTest` verifying that each action type invokes the right collaborator.

### Files
- `aura-core/src/main/kotlin/com/aura/triggers/TriggerWorker.kt`
- `aura-core/src/main/kotlin/com/aura/triggers/TriggerEngine.kt` (check if handId maps to name)
- `aura-core/src/main/kotlin/com/aura/tools/HandRunEnqueuer.kt` (already supports context snapshot)
- `aura-core/src/test/kotlin/com/aura/triggers/TriggerWorkerTest.kt` (new)

---

## 2. DreamConsolidator.pruneStale: implement the documented decay reset

### Problem
`DreamConsolidator.kt:180` comment says it "sets decayScore to 0"; code only appends `pruned:dream` tag. The tag is unread and `decayScore` unchanged, so memories keep surfacing.

### Fix
Add a `MemoryStore.updateDecayScore(id, decayScore)` method that updates only the decay score (no audit trail needed). Call it in `pruneStale()` for each candidate, replacing the no-op tag update. Keep the `pruned:dream` tag as metadata, but make the functional change the decay score.

### Files
- `aura-core/src/main/kotlin/com/aura/memory/MemoryStore.kt` (new `updateDecayScore` method)
- `aura-core/src/main/kotlin/com/aura/memory/MemoryDao.kt` (new `@Query` UPDATE for decayScore)
- `aura-core/src/main/kotlin/com/aura/dream/DreamConsolidator.kt` (call `updateDecayScore(0f)`)
- `aura-core/src/test/kotlin/com/aura/dream/DreamConsolidatorTest.kt` (new or extend existing)

---

## 3. ProviderContextWindows: remove training-data model-name heuristics

### Problem
`ProviderContextWindows.kt` hardcodes `model.contains("gpt-4o")`, `claude-3-opus`, `llama-3.1-70b`, etc. User explicitly rejected this pattern. Names rot and may not match actual configured models.

### Fix
Replace the substring tables with safe defaults and abstract pattern matching only where unavoidable:
- Anthropic: if the provider is queried, return `200_000` as a documented platform-wide context (Claude models are uniformly 200K). This is a provider-level default, not a model-name guess.
- OpenAI/Groq/ChatGPT: return `null` (use the 32K compactor default) instead of maintaining a stale model list. The live-query providers (OllamaCloud, Gemini, OpenRouter) already override this.
- Add KDoc explaining that unknown providers fall back to the safe default, and that per-model context should come from `Provider.listModelsWithContext()` overrides.
- Update `ProviderContextWindowsTest` if it asserts specific names; replace with prefix-level tests.

### Files
- `aura-core/src/main/kotlin/com/aura/providers/ProviderContextWindows.kt`
- `aura-core/src/test/kotlin/com/aura/providers/ProviderContextWindowsTest.kt` (if exists)

---

## Verification

1. Run targeted tests for each change.
2. Run full suite: `./gradlew :aura-core:testDebugUnitTest :app:testDebugUnitTest :app:assembleDebug --no-daemon`.
3. Expect test count delta: +5 to +8 tests, 0 failures.
4. Bump version to v0.38.3 and create GitHub Release with APK.

## Deferred / out of scope
- Parallel step execution in `AgentRunExecutorWorker` — larger architectural change, separate session.
- `activeEventSource` cancellation race — minor, needs careful testing, separate session.
