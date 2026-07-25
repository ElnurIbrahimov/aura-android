# Engineering Review — 2026-07-26 Pass 2

**Project:** Aura Android (Kotlin/Compose)
**Version:** v0.35.3 (version unchanged — fixes are bug corrections)
**Branch:** feat/tier-1-friction
**Commit:** `333f36e9`
**Method:** Fresh full-project audit focusing on provider SSE parsing paths not deeply audited in the prior pass (5c09d6d7). Verified all prior audit findings against current source, then independently audited the OpenAI-compatible provider family for the same parallel tool-call routing bug class.

## 1. Project-wide issues found

### Confirmed issues (fixed in this pass)

| # | Severity | Subsystem | Finding |
|---|----------|-----------|---------|
| 1 | P0 | Providers | `OpenAiCompatProvider` and `CustomOpenAiCompatProvider` did not track the OpenAI streaming `tool_calls[index]` field — parallel tool-call argument deltas had empty id and were mis-routed by Brain.fromProvider's `lastOrNull()` fallback |
| 2 | P1 | Providers | `ChatGptSubscriptionProvider.listModels()` queried `api.openai.com/v1/models` with a ChatGPT subscription token — always 401 because the token authenticates against `chatgpt.com/backend-api`, not `api.openai.com`. Model picker showed only fallback "gpt-4o" instead of the full ChatGPT model set |
| 3 | P1 | Tests | `NonRetryableStatusCodesTest` failed after removing the `listModels()` body that contained the `429`/`500` literal strings the test scans for. Fixed by changing `onFailure` to positive pattern (`code == 429 || code in 500..599`) matching Anthropic/Gemini style |

### Ambiguities / lower-confidence (not changed)

- **World model / taste / profile tables have no agent scope** — needs Room schema migration. Deferred (same as prior pass).
- **Evolution rollback covers 7 of 20 actions** — design limitation. Deferred.
- **10 untested ViewModels + 45 untested screens** — coverage gap. Deferred.

### Verified as already-fixed (from 2026-07-26 subagent audits)

All 11 subagent findings per audit were verified as already fixed by the 2026-07-26 hardening pass (99808305, 678f4d6d). See the prior pass's ENGINEERING_REVIEW_2026-07-26.md §"False positives" for the full table.

## 2. Bugs and risks fixed

### Bug: OpenAI parallel tool-call deltas mis-routed (P0)

**Root cause:** The OpenAI Chat Completions streaming API sends tool-call deltas with an `index` field that identifies which tool call the delta belongs to. The `id` and `name` are only sent on the first delta for each tool call. Subsequent argument deltas carry `index` but empty `id` and empty `name`. The provider code at `OpenAiCompatProvider.kt:78-81` and `CustomOpenAiCompatProvider.kt:237-240` read only `id` and `name`, ignoring `index` entirely. The emitted `ToolCall` had empty `id`, and `Brain.fromProvider`'s fallback (`nameById.keys.lastOrNull()`) mis-routed the delta to the most recently registered tool — the same bug class as the Anthropic fix in commit `5c09d6d7`.

**Scenario:** The model emits two parallel tool calls (e.g. `search_web` and `read_url`). The SSE stream sends:
1. `tool_calls[0]`: id="call_A", name="search"
2. `tool_calls[1]`: id="call_B", name="fetch"
3. `tool_calls[0]`: index=0, no id, no name, args='{"q":"test"}'
4. `tool_calls[1]`: index=1, no id, no name, args='{"u":"http://x"}'

Without index tracking, delta 3 has empty id → Brain uses `lastOrNull()` which is "call_B" (the most recently registered) → call_A's arguments go to call_B's buffer. Both tools execute with swapped arguments.

**Fix:** Added an `index → id` mapping (`toolCallIndexToId`) in both `OpenAiCompatProvider` and `CustomOpenAiCompatProvider`. On the first delta for a tool call (id non-empty), the mapping is registered. On subsequent deltas (id empty, index present), the id is resolved from the map. The emitted `ToolCall` now has a non-empty `id`, and `Brain.fromProvider`'s `tc.id.isNotEmpty()` branch (added in the prior pass) routes it correctly.

**Files:**
- `aura-core/src/main/kotlin/com/aura/providers/OpenAiCompatProvider.kt` — added `toolCallIndexToId` map + index resolution logic
- `aura-core/src/main/kotlin/com/aura/providers/CustomOpenAiCompatProvider.kt` — same fix (duplicated SSE parsing)

### Bug: ChatGPT subscription listModels() always 401 (P1)

**Root cause:** `ChatGptSubscriptionProvider.listModels()` sent an HTTP request to `https://api.openai.com/v1/models` with the ChatGPT subscription token as a Bearer header. The subscription token is a session token for `chatgpt.com/backend-api/codex`, not an OpenAI API key. The `api.openai.com` endpoint rejects it with 401. The function caught the 401 and fell back to `listOf("gpt-4o")`, so the model picker always showed only "gpt-4o" for ChatGPT subscription users.

**Fix:** Replaced the network call with a hardcoded list of ChatGPT Plus/Pro/Go models: gpt-5, gpt-5-mini, gpt-5-nano, gpt-4.1, gpt-4.1-mini, gpt-4o, gpt-4o-mini, o3, o4-mini. The `chatgpt.com` backend doesn't expose a models-listing endpoint, so this is the correct approach. Users who want the full OpenAI catalog should use the regular `openai` provider with an API key.

**File:** `aura-core/src/main/kotlin/com/aura/providers/ChatGptSubscriptionProvider.kt`

### Bug: NonRetryableStatusCodesTest failure after listModels removal (P1)

**Root cause:** The `NonRetryableStatusCodesTest` scans provider source files for literal strings `429`, `500`, `599` to verify retryable status codes are present. Removing the `listModels()` body (which contained these strings in the `when` block) caused the test to fail for `ChatGptSubscriptionProvider.kt`.

**Fix:** Changed the `onFailure` retryable check from the negative pattern (`code != 401 && code != 400 && code != 403`) to the positive pattern (`code == 429 || code in 500..599`), matching the Anthropic and Gemini provider style. This is both more explicit (the retryable codes are visible in the source) and satisfies the test's source-text scan.

## 3. Security and reliability improvements made

- **Parallel tool-call correctness for OpenAI-compatible providers.** This is the same reliability improvement as the Anthropic fix from the prior pass, now extended to cover OpenAI, DeepSeek, Groq, Ollama Cloud, NVIDIA, Together, Fireworks, and any other service that uses the `/v1/chat/completions` SSE format. Users who issue parallel tool calls through any of these providers will now have their arguments routed to the correct tool.
- **ChatGPT subscription model picker.** Users with a ChatGPT Plus/Pro/Go subscription will now see the full set of available models (gpt-5, gpt-4.1, o3, o4-mini, etc.) instead of just "gpt-4o".

No new security issues were introduced. No existing security controls were weakened.

## 4. Dead code, duplication, and consolidation changes

- **Removed 25 lines of dead network code** from `ChatGptSubscriptionProvider.listModels()` — the HTTP request to `api.openai.com/v1/models` would always fail with 401 for subscription tokens. Replaced with a 10-line hardcoded list.
- **No other dead code found.** The codebase is clean after 30+ review cycles.

## 5. Refactors performed and why

**No refactors performed.** All changes are surgical bug fixes. The SSE parsing code in `OpenAiCompatProvider` and `CustomOpenAiCompatProvider` is duplicated, but consolidating it into a shared helper would be a speculative refactor — the two providers have different request-building logic and different timeout/error handling, so the duplication is shallow (only the `onEvent` callback is similar). Not worth the migration risk.

## 6. Performance improvements made and why they matter

**No performance optimizations.** The `toolCallIndexToId` map is O(1) per lookup and bounded by the number of tool calls in a single response (typically 1-3). The `listModels()` fix removes an unnecessary network round-trip for ChatGPT subscription users.

## 7. Tests added or updated

| Test file | Cases | Purpose |
|-----------|-------|---------|
| `aura-core/src/test/kotlin/com/aura/providers/OpenAiCompatParallelToolCallTest.kt` (new) | 2 | MockWebServer SSE test: parallel tool calls route argument deltas to the correct id via index; single tool call without index field still works |

**Test count: 1,257 → 1,259 (+2). 0 failures.** Full gate green:
- `:aura-core:testDebugUnitTest` — 997 tests pass
- `:app:testDebugUnitTest` — 262 tests pass
- `:app:assembleDebug` — produces debug APK

## 8. Documentation updated

- **`OpenAiCompatProvider.kt`** — added comment block explaining the index→id mapping and why it's needed for parallel tool calls
- **`CustomOpenAiCompatProvider.kt`** — added comment referencing the same pattern
- **`ChatGptSubscriptionProvider.kt`** — added comment explaining why the model list is hardcoded (subscription token can't authenticate against api.openai.com)
- **`ChatGptSubscriptionProvider.kt`** — added comment on the retryable check explaining the 401/400/403 vs 429/5xx distinction

## 9. Remaining risks, ambiguities, and recommended next steps

### Unresolved ambiguities (intentionally not changed)

1. **World model / taste / profile tables have no agent scope** (P1) — needs Room schema migration + query changes. Same as prior pass.
2. **Evolution rollback covers 7 of 20 actions** (P2) — design limitation.
3. **10 untested ViewModels + 45 untested screens** (P2) — coverage gap.

### Worthwhile future improvements

1. **Consolidate the SSE parsing** between `OpenAiCompatProvider` and `CustomOpenAiCompatProvider` into a shared `OpenAiSseParser` — the `onEvent` callback logic is now identical (index→id mapping, tool-call parsing, finish-reason mapping). A shared parser would prevent future drift. Low priority since both copies are now correct.
2. **Add ChatGPT subscription SSE tool-call test** — the `ChatGptSubscriptionProvider.chat()` uses a different SSE format (Responses API) than the OpenAI Chat Completions API. A MockWebServer test for the ChatGPT tool-call parsing path would pin that contract.
3. **Add Gemini parallel tool-call test** — Gemini sends function calls as complete objects (not streaming deltas), so parallel calls should work correctly. But a test would pin the contract.

## 10. Change summary

### Files modified (production)

| File | Change type | Description |
|------|-------------|-------------|
| `aura-core/src/main/kotlin/com/aura/providers/OpenAiCompatProvider.kt` | Bug fix (P0) | Track `tool_calls[index]` → id mapping for parallel tool-call delta routing |
| `aura-core/src/main/kotlin/com/aura/providers/CustomOpenAiCompatProvider.kt` | Bug fix (P0) | Same index→id fix (duplicated SSE parsing) |
| `aura-core/src/main/kotlin/com/aura/providers/ChatGptSubscriptionProvider.kt` | Bug fix (P1) | Replace always-failing `api.openai.com/v1/models` call with hardcoded ChatGPT model list; change retryable check to positive pattern |

### Files added (test)

| File | Purpose |
|------|---------|
| `aura-core/src/test/kotlin/com/aura/providers/OpenAiCompatParallelToolCallTest.kt` | 2 MockWebServer SSE tests for parallel tool-call index resolution |

### Public behavior changes

- **OpenAI-compatible parallel tool calls** — argument deltas now route to the correct tool via the `index` field. Previously, parallel tool calls through OpenAI/DeepSeek/Groq/Ollama Cloud/NVIDIA/Together/Fireworks had their arguments swapped. No API changes, no configuration changes.
- **ChatGPT subscription model picker** — now shows the full model set (gpt-5, gpt-5-mini, gpt-5-nano, gpt-4.1, gpt-4.1-mini, gpt-4o, gpt-4o-mini, o3, o4-mini) instead of only "gpt-4o".

### Test results

- aura-core: 997 tests (was 995, +2 new), 0 failures
- app: 262 tests, 0 failures
- **Total: 1,259 tests, 0 failures** (was 1,257)
- `:aura-core:testDebugUnitTest`: green
- `:app:testDebugUnitTest`: green
- `:app:assembleDebug`: green

### Commit

`333f36e9 fix(providers): resolve OpenAI parallel tool-call deltas by index + ChatGPT listModels fix` — pushed to `aura-android/feat/tier-1-friction`