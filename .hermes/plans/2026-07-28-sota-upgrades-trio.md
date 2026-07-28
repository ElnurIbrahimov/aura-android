# Plan: 5 SOTA Upgrades — Entity Compaction, KG Resolution, Evolution Evaluators, Taste Wiring, Hands Matching

**Date:** 2026-07-28
**Branch:** `feat/tier-1-friction`
**Base head:** `5446822d` (post Claude review fixes)
**Tests:** 1,408 (0 failures)

## Goal

Five SOTA upgrades shipped as atomic commits. Each fixes a subsystem that has infrastructure but doesn't deliver real value.

1. **Entity-aware compaction** — extract entities from old turns before summarizing, inject compact entity table instead of prose-only summary
2. **KG entity resolution** — dedup nodes before insertion, merge paraphrase edges, track confidence
3. **Real evolution evaluators** — replace toy token-length scorer with self-consistency + LLM-as-judge
4. **Taste engine wiring** — route models via taste, enhance prompts with explicit style instructions
5. **Hands word-boundary matching** — fix false triggers, add multi-phrase support

## Execution plan (10 commits)

### Commit 1: Entity-aware compaction
- Patch `ConversationCompactor.kt`:
  - Before sending old turns to the summarizer, extract entities (reuse `KnowledgeGraphTool.extract()`)
  - Build a compact entity table: "Known entities: user→likes→Kotlin, user→lives_in→Baku, restaurant→Düsseldorf Sushi Bar"
  - Prepend the entity table to the prose summary
  - The entity table survives compaction — it's injected as `contextSummary` prefix
- The KG extraction is best-effort: if it fails, fall back to prose-only summary (current behavior)
- **~80 lines** in ConversationCompactor
- Tests: verify entity table is prepended, verify fallback on KG failure
- Gate: `./gradlew :aura-core:testDebugUnitTest`

### Commit 2: KG entity resolution — fuzzy node matching
- New file: `KgEntityResolver.kt` (~120 lines)
  - `suspend fun resolve(nodes: List<KgNode>, edges: List<KgEdge>): Pair<List<KgNode>, List<KgEdge>>`
  - For each new node, check if an existing node has the same label (case-insensitive) or high label similarity (Levenshtein distance ≤ 2 for short labels, ≤ 30% for longer)
  - If match found: reuse existing node ID, don't create duplicate
  - If no match: keep new node
  - For edges: if source+target+label already exists, increment confidence instead of creating duplicate
- Patch `KnowledgeGraphRepository.saveGraph()`:
  - Call `resolver.resolve()` before inserting
  - Log resolved vs new counts for diagnostics
- Patch `KnowledgeGraphModule.kt`: provide `KgEntityResolver`
- Tests: `KgEntityResolverTest.kt` — verify dedup, verify edge merge, verify no false merges
- Gate: `./gradlew :aura-core:testDebugUnitTest`

### Commit 3: Real evolution evaluators — self-consistency + LLM-as-judge
- New file: `EvolutionEvaluators.kt` (~150 lines)
  - `SelfConsistencyEvaluator` — asks the model the same question twice, compares answers for semantic similarity
  - `LlmJudgeEvaluator` — asks a cheap model to score the response on a 0-1 scale
  - Both return `EvalResult(metricName, score, confidence)`
  - Both use `resolveCheapModel`, 200 tokens, 5s timeout, non-blocking
- Patch `EvolutionShadowEvaluator.kt`:
  - Replace `score()` (token-length Gaussian) with a composite: 0.4 * self-consistency + 0.4 * judge + 0.2 * length
  - Keep the old `score()` as a fallback when evaluators are unavailable
- Patch `EvolutionModule.kt`: provide evaluators
- Tests: `EvolutionEvaluatorsTest.kt` — mock provider, verify scoring, verify fallback
- Gate: `./gradlew :aura-core:testDebugUnitTest`

### Commit 4: Wire evaluators into EvolutionCoordinator
- Patch `EvolutionCoordinator.kt`:
  - Inject `EvolutionEvaluators` (optional, `? = null`)
  - Before `reflectAndPromote`, run evaluators on candidates
  - Candidate score = composite of evaluator scores
  - If evaluators unavailable, fall back to existing `EvolutionShadowEvaluator.score()`
- Update existing tests to mock the new evaluator
- Gate: `./gradlew :aura-core:testDebugUnitTest`

### Commit 5: Taste engine — wire bestModelForRole into ModelRoleRouter
- Patch `ModelRoleRouter.kt`:
  - When resolving a model for a role, check `tasteEngine.bestModelForRole(role)` first
  - If taste has a recommendation with ≥2 data points and >60% success rate, use it
  - Otherwise fall back to user preference → default model (current behavior)
- This is already partially wired — `ModelRoleRouter` already takes `tasteEngine?` but doesn't call `bestModelForRole`
- **~30 lines** in ModelRoleRouter
- Tests: verify taste-recommended model is preferred, verify fallback when no data
- Gate: `./gradlew :aura-core:testDebugUnitTest`

### Commit 6: Taste engine — prompt enhancement
- New file: `TastePromptEnhancer.kt` (~60 lines)
  - `fun enhance(systemPrompt: String, tasteContext: String): String`
  - Parses the taste context ("prefers tone: concise, style: direct")
  - Converts to explicit instructions: "Be concise. Use direct style. Avoid unnecessary preamble."
  - Appends to system prompt
- Patch `MemoryAugmentedAgenticLoop.kt`:
  - After `tasteContext` is built, run it through `TastePromptEnhancer.enhance()`
  - The enhanced version replaces the raw taste context in the system prompt
- Tests: `TastePromptEnhancerTest.kt` — verify conversion of taste signals to instructions
- Gate: `./gradlew :aura-core:testDebugUnitTest`

### Commit 7: Hands word-boundary matching + multi-phrase
- Patch `MemoryAugmentedAgenticLoop.kt`:
  - Replace `lower.contains(hand.triggerPhrase.lowercase())` with word-boundary regex
  - Split trigger phrase on `|` for multi-phrase: "git status|git log" matches either
  - Escape each phrase with `Regex.escape()` before word-boundary matching
- **~20 lines** change in the loop
- Tests: verify "git" doesn't match "widget", verify multi-phrase works
- Gate: `./gradlew :aura-core:testDebugUnitTest`

### Commit 8: Integration tests
- New file: `SotaUpgradesIntegrationTest.kt`
  - Test entity compaction: old turns → entity table extracted → injected into context
  - Test KG resolution: two turns with same entity → one node, not two
  - Test evolution evaluators: candidate scored by judge, not token length
  - Test taste routing: model selected based on routing outcomes
  - Test hands: "git" doesn't trigger on "widget"
- Gate: `./gradlew :aura-core:testDebugUnitTest`

### Commit 9: README + version bump
- Update README with new feature descriptions
- Bump to v0.38.0, vCode 43
- Gate: full test suite + assembleDebug + lint

### Commit 10: Build APK + GitHub Release
- `./gradlew :app:assembleDebug`
- Copy APK to releases/
- `gh release create v0.38.0`
- Push

## Verification

After each commit:
```
./gradlew :aura-core:testDebugUnitTest :app:testDebugUnitTest
```

After commits 9-10:
```
./gradlew :aura-core:testDebugUnitTest :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
```

Test count target: 1,408 → ~1,430 (+22 from new test files)

## What this does NOT change

- No new Room migrations (KG resolution uses existing tables, evaluators are in-memory)
- No public API changes (all new params are optional with `? = null` defaults)
- No UI changes
- No provider changes
- No tool changes
- The compaction entity extraction reuses existing `KnowledgeGraphTool.extract()` — no new LLM call shape
- The evolution evaluators are optional — if they fail, the existing shadow evaluator runs
- The taste routing is additive — it sits on top of the existing ModelRoleRouter, doesn't replace it