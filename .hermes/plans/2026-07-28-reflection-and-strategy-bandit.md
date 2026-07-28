# Plan: Reflection + StrategyBandit + LLM Profile Extraction

**Date:** 2026-07-28
**Branch:** `feat/tier-1-friction`
**Base head:** `3d33aaa6` (post engineering review)
**Tests:** 1,384 (0 failures)

## Goal

Three SOTA upgrades to the agentic loop, shipped as atomic commits:

1. **Reflection** — after a failed tool call or max-steps-exceeded, the model writes a short reflection ("I tried X, it failed because Y, next time try Z"). The reflection is injected into the next attempt's system prompt. Turns blind retries into self-correcting retries.

2. **StrategyBandit** — Thompson Sampling over reasoning strategies. Classify the request (math/code/analysis/creative/planning/debug), pick the reasoning approach (single-pass CoT vs multi-step with reflection vs creative single-pass), and track Beta-distributed reward signals per (category, strategy) pair. The bandit learns which strategies work best for which tasks.

3. **LLM Profile Extraction** — replace the 4-regex `extractProfileFromText` with an LLM call that extracts structured facts ("I use Vim" → trait "Uses Vim", "I'm allergic to peanuts" → fact "Allergic to peanuts"). Uses the existing `resolveCheapModel` infrastructure.

## Architecture

### Reflection

```
AgenticLoop.run()
  ├── step 1..N: ReAct loop (unchanged)
  ├── on tool error: store error in turn metadata (already happens)
  ├── on max_steps_exceeded: NEW — call reflectOnFailure()
  │   └── cheap LLM call: "You tried to answer X. You used tools A, B, C.
  │       Tool B failed with error E. You ran out of steps. What should you
  │       do differently next time? Answer in 1-2 sentences."
  │   └── store reflection string in Conversation metadata
  └── on next run() for same conversation: inject reflection into system prompt
      ("## Previous attempt reflection: ...")
```

**Files touched:**
- `aura-core/src/main/kotlin/com/aura/agent/MemoryAugmentedAgenticLoop.kt` — add reflection call after max_steps_exceeded, inject prior reflection into system prompt
- `aura-core/src/main/kotlin/com/aura/agent/Conversation.kt` — add `lastReflection: String?` field to metadata
- `aura-core/src/main/kotlin/com/aura/agent/ReflectionEngine.kt` — NEW file, ~80 lines

**Key design decisions:**
- Reflection fires ONLY on max_steps_exceeded or when ≥2 tool calls in a single run returned Error. Not on every error — a single failed search is normal.
- Reflection is stored per-conversation, not globally. It's injected into the NEXT run() for the same conversation. If the user starts a new conversation, there's no reflection to inject.
- The reflection call uses `resolveCheapModel` — same as planning, 150 tokens max, 10s timeout.
- If the reflection LLM call fails, the loop continues without reflection. Non-blocking.

### StrategyBandit

```
User sends message
  ├── SpecialistRouter.pickSpecialist() → picks persona (unchanged)
  ├── StrategyBandit.selectStrategy(userMessage, specialist) → NEW
  │   ├── classifyCategory(userMessage) → ProblemCategory
  │   │   (math/code/analysis/creative/planning/debug/conversation)
  │   ├── ThompsonSample(category) → ReasoningStrategy
  │   │   (single_pass / multi_step_reflect / creative_pass)
  │   └── return strategy
  ├── AgenticLoop.run() with strategy
  │   ├── single_pass: maxSteps=5, no planning
  │   ├── multi_step_reflect: maxSteps=15, planning enabled, reflection on failure
  │   └── creative_pass: maxSteps=3, no planning, high temperature
  └── After run: StrategyBandit.recordOutcome(category, strategy, success)
      └── update Beta(α, β) for this (category, strategy) pair
```

**Files touched:**
- `aura-core/src/main/kotlin/com/aura/agent/StrategyBandit.kt` — NEW file, ~150 lines
- `aura-core/src/main/kotlin/com/aura/agent/StrategyBanditStore.kt` — NEW file, Room-backed, ~60 lines
- `aura-core/src/main/kotlin/com/aura/agent/StrategyBanditDao.kt` — NEW file, ~30 lines
- `aura-core/src/main/kotlin/com/aura/agent/StrategyBanditEntity.kt` — NEW file, ~25 lines
- `aura-core/src/main/kotlin/com/aura/agent/StrategyBanditModule.kt` — NEW file, Hilt module, ~20 lines
- `aura-core/src/main/kotlin/com/aura/agent/MemoryAugmentedAgenticLoop.kt` — accept strategy param, adjust maxSteps/planning based on strategy
- `app/src/main/kotlin/com/aura/ui/viewmodel/ChatSendController.kt` — call StrategyBandit before run(), record outcome after

**Key design decisions:**
- Classification is keyword-based (same style as SpecialistRouter), NOT an LLM call. Classification must be instant — it runs before every message.
- Beta distribution per (category, strategy) pair. α starts at 1, β starts at 1 (uniform prior). Success increments α, failure increments β.
- Thompson Sampling: sample from Beta(α, β) for each strategy, pick the highest sample. This naturally balances exploration vs exploitation.
- "Success" = the loop completed without max_steps_exceeded AND the user didn't immediately regenerate/send a follow-up correction. We approximate: success = loop finished normally (finished=true, not max_steps_exceeded).
- The bandit is persisted in Room so it survives restarts. 7 categories × 3 strategies = 21 rows max.
- No Settings UI for the bandit — it's invisible to the user. They just feel the assistant getting better at routing over time.
- StrategyBandit is optional (`? = null` in the loop constructor) so existing tests don't break.

### LLM Profile Extraction

```
AgenticLoop post-turn (after memory store, after KG extraction)
  ├── Current: extractProfileFromText(lastUserMessage) — 4 regex patterns
  └── NEW: LlmProfileExtractor.extract(lastUserMessage, completedAssistant)
      ├── cheap LLM call: "Extract structured facts about the user from
      this conversation. Return JSON: {name, traits[], facts[]}. Only
      extract facts stated by the USER, not the assistant."
      ├── parse JSON response
      └── userProfileStore.update(name, traits, facts)
```

**Files touched:**
- `aura-core/src/main/kotlin/com/aura/profile/LlmProfileExtractor.kt` — NEW file, ~80 lines
- `aura-core/src/main/kotlin/com/aura/agent/MemoryAugmentedAgenticLoop.kt` — replace regex call with LLM extractor (keep regex as fallback)
- `aura-core/src/main/kotlin/com/aura/profile/UserProfileStore.kt` — add `mergeTraits()` method

**Key design decisions:**
- The LLM extractor runs ONLY when the heuristic regex finds nothing. If the regex catches "my name is X", we don't need an LLM call. The LLM fires for "I use Vim" / "I'm allergic to peanuts" / "my wife's name is Sarah" — things the regex misses.
- The LLM call uses `resolveCheapModel`, 200 tokens max, 5s timeout.
- The response is JSON: `{"name": "Elnur", "traits": ["Uses Vim", "Allergic to peanuts"], "facts": ["Wife's name is Sarah"]}`.
- If the LLM call fails or returns unparseable JSON, the regex extraction result (if any) is kept. Non-blocking.
- The extractor runs on the USER's text only, never the assistant's. Same guard as the current regex path.

## Execution plan (9 commits)

### Commit 1: ReflectionEngine + Conversation.lastReflection
- New file: `ReflectionEngine.kt` (~80 lines)
  - `suspend fun reflect(userMessage, toolErrors, maxSteps, model): String?`
  - System prompt: "You are a reflection assistant. The user asked X. You tried tools A, B, C. Tool B failed with error E. You ran out of steps. What should you do differently? 1-2 sentences."
  - Uses `Brain.stream()` with `resolveCheapModel`, 150 tokens, 10s timeout
  - Returns null on any failure (non-blocking)
- Patch `Conversation.kt`: add `lastReflection: String? = null` to metadata
- Patch `MemoryAugmentedAgenticLoop.kt`:
  - After max_steps_exceeded: call `reflectOnFailure()`, store result in conversation metadata
  - At start of `run()`: read `currentConversation.metadata["lastReflection"]`, inject into system prompt as "## Previous attempt reflection: ..."
- Tests: `ReflectionEngineTest.kt` — mock Brain, verify reflection prompt construction, verify null on timeout
- Gate: `./gradlew :aura-core:testDebugUnitTest`

### Commit 2: StrategyBandit core (Room + Thompson Sampling)
- New files: `StrategyBanditEntity.kt`, `StrategyBanditDao.kt`, `StrategyBanditDatabase.kt`, `StrategyBanditModule.kt`
  - Entity: (category: String, strategy: String, alpha: Double, beta: Double, lastUpdated: Long)
  - DAO: upsert, get, getAll, incrementAlpha, incrementBeta
  - Database: version 1, standalone (like AgentDatabase)
  - Module: @Provides @Singleton, @Provides DAO
- New file: `StrategyBandit.kt` (~150 lines)
  - `enum class ProblemCategory { MATH, CODE, ANALYSIS, CREATIVE, PLANNING, DEBUG, CONVERSATION }`
  - `enum class ReasoningStrategy { SINGLE_PASS, MULTI_STEP_REFLECT, CREATIVE_PASS }`
  - `fun classify(userMessage: String, specialist: Specialist?): ProblemCategory` — keyword-based, same style as SpecialistRouter
  - `fun selectStrategy(category: ProblemCategory): ReasoningStrategy` — Thompson sample from Beta(α, β) for each strategy in the category, pick highest
  - `suspend fun recordOutcome(category: ProblemCategory, strategy: ReasoningStrategy, success: Boolean)` — increment α or β
  - All methods are pure Kotlin (no suspend except recordOutcome which hits Room)
- New file: `StrategyBanditStore.kt` (~60 lines) — wraps DAO, provides suspend API
- Tests: `StrategyBanditTest.kt` — verify classification, verify Thompson sampling picks highest sample, verify Beta update
- Gate: `./gradlew :aura-core:testDebugUnitTest`

### Commit 3: Wire StrategyBandit into agentic loop
- Patch `MemoryAugmentedAgenticLoop.kt`:
  - Add `strategyBandit: StrategyBandit? = null` to constructor
  - `run()` accepts optional `strategy: ReasoningStrategy?` param
  - When strategy is SINGLE_PASS: maxSteps=5, planningEnabled=false
  - When strategy is MULTI_STEP_REFLECT: maxSteps=15, planningEnabled=true, reflection on failure
  - When strategy is CREATIVE_PASS: maxSteps=3, planningEnabled=false, temperature=0.7
  - When strategy is null: current defaults (maxSteps=10, planning from Settings)
- Patch `ChatSendController.kt`:
  - Before `loop.run()`: call `strategyBandit?.classify()` + `selectStrategy()`
  - After `loop.run()` completes: call `strategyBandit?.recordOutcome()` with success = (finished && !max_steps_exceeded)
- Tests: update existing ChatSendController tests to mock StrategyBandit
- Gate: `./gradlew :aura-core:testDebugUnitTest :app:testDebugUnitTest`

### Commit 4: LlmProfileExtractor
- New file: `LlmProfileExtractor.kt` (~80 lines)
  - `suspend fun extract(userText: String, model: String): ProfileExtraction?`
  - System prompt: "Extract structured facts about the user from their message. Return JSON: {\"name\": \"\", \"traits\": [], \"facts\": []}. Only extract facts the USER stated. If no personal facts, return empty arrays."
  - Uses `providerRegistry.chat()` with cheap model, 200 tokens, 5s timeout
  - Parses JSON response, returns null on failure
  - `data class ProfileExtraction(name: String?, traits: List<String>, facts: List<String>)`
- Patch `UserProfileStore.kt`: add `mergeTraits(newTraits: List<String>)` — dedup by lowercase
- Tests: `LlmProfileExtractorTest.kt` — mock provider, verify JSON parsing, verify null on bad response
- Gate: `./gradlew :aura-core:testDebugUnitTest`

### Commit 5: Wire LlmProfileExtractor into agentic loop
- Patch `MemoryAugmentedAgenticLoop.kt`:
  - Add `llmProfileExtractor: LlmProfileExtractor? = null` to constructor
  - In the post-turn section (after regex extraction):
    - If regex found nothing AND llmProfileExtractor is not null: call `extract()`
    - If extraction returns traits/facts: call `userProfileStore.mergeTraits()` + `mergeFacts()`
  - Keep the regex path as the fast first pass. LLM is the fallback for things regex misses.
- Update existing tests that construct MemoryAugmentedAgenticLoop with new optional param
- Gate: `./gradlew :aura-core:testDebugUnitTest :app:testDebugUnitTest`

### Commit 6: Settings toggle for StrategyBandit
- Patch `UserPreferences.kt`: add `strategyBanditEnabled: Flow<Boolean>` (default true)
- Patch `ChatSendController.kt`: gate StrategyBandit on the preference
- Patch `SettingsScreen` → AI & Models section: add toggle "Adaptive reasoning strategy"
- Gate: `./gradlew :aura-core:testDebugUnitTest :app:testDebugUnitTest :app:assembleDebug`

### Commit 7: Backup coverage for StrategyBandit
- Patch `AuraBackup.kt`: add `StrategyBanditBackup` data class
- Patch `BackupManager.kt`: snapshot/restore/purge for strategy bandit table
- Tests: update BackupManagerTest
- Gate: `./gradlew :aura-core:testDebugUnitTest`

### Commit 8: Integration test — reflection + bandit + profile extraction end-to-end
- New file: `ReflectionAndBanditIntegrationTest.kt`
  - Mock Brain with scripted responses
  - Send a "debug this code" message → verify Coder specialist + MULTI_STEP_REFLECT strategy
  - Script a tool failure → verify reflection is generated and stored
  - Send a follow-up → verify reflection is injected into system prompt
  - Send "I use Vim and I'm allergic to peanuts" → verify LLM profile extraction fires
- Gate: `./gradlew :aura-core:testDebugUnitTest`

### Commit 9: README + release
- Update README: add "Adaptive reasoning (StrategyBandit + Reflection)" to feature list
- Bump version: v0.37.0, vCode 42
- Build APK, create GitHub Release
- Gate: full test suite + assembleDebug + lint

## Verification

After each commit:
```
./gradlew :aura-core:testDebugUnitTest :app:testDebugUnitTest
```

After commit 6 and 9:
```
./gradlew :aura-core:testDebugUnitTest :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
```

Test count target: 1,384 → ~1,400 (+16 from new test files)

## What this does NOT change

- No new Room migrations for existing databases (StrategyBandit is a new standalone DB)
- No public API changes (all new params are optional with `? = null` defaults)
- No UI changes except the Settings toggle (commit 6)
- No provider changes
- No tool changes
- The regex profile extraction stays as the fast first pass — LLM is the fallback
- The SpecialistRouter stays as-is — StrategyBandit is a layer on top, not a replacement