# Deep-Dive Fix Plan — 8 Items (wired-but-unused / upgrades)

**Branch:** feat/tier-1-friction · **Base:** cfe928a0 (v0.63.0) · **Tests:** 1,829

---

## Item 1 — Idle-time preparation: COMPUTED BUT NEVER DELIVERED (HIGH)

**Problem:** `IdleTimePreparationEngine.prepare()` runs every daemon cycle → `_prepared` StateFlow written. `consume()` exists. NOTHING reads `prepared` or calls `consume()`. The pre-researched answer is thrown away.

**Files:**
- `aura-core/.../proactive/IdleTimePreparationEngine.kt` — add `fun latest(): PreparedAnswer?` (peek, no consume) 
- `app/src/main/kotlin/com/aura/ui/viewmodel/ChatViewModel.kt` — inject engine (nullable), expose `preparedAnswer: StateFlow<PreparedAnswer?>`, `fun consumePreparedAnswer()`
- `app/src/main/kotlin/com/aura/ui/screens/chat/ChatRoute.kt` — when preparedAnswer != null, show a suggestion chip above the input bar ("Aura predicted: <question>") → tap sends the question as a normal message (fast-path: the pre-researched answer goes as context into the message)

**Wire-in:** ChatViewModel already has most singletons; add `IdleTimePreparationEngine?` via constructor (nullable default, Hilt provides it). ChatRoute collects `preparedAnswer` via ChatUiState.

**Validation:** unit test — engine.prepare() writes, consume() clears; ChatViewModel exposes and clears. ChatRoute chip renders when non-null (compose contract test).

---

## Item 2 — LocationEntered trigger: UI lie (MEDIUM-HIGH)

**Problem:** `TriggerEngine.checkAll` returns `null` for LocationEntered. UI shows it as configurable. `LocationNowTool` already proves plain `LocationManager` works (no Play Services).

**Files:**
- `aura-core/.../triggers/TriggerEngine.kt`:
  - Add `@ApplicationContext private val appContext: Context` to constructor (Hilt @Singleton — needs qualifier)
  - `private suspend fun checkLocation(condition: TriggerCondition.LocationEntered): Unit?` — check `ACCESS_FINE_LOCATION`/`ACCESS_COARSE_LOCATION` permission; `LocationManager.getLastKnownLocation` over all providers (best accuracy); haversine distance vs condition.lat/lon; return Unit if within radiusMeters
  - Replace the `-> null` branch with `-> checkLocation(condition)`
- `aura-core/.../triggers/TriggerWorker.kt` — no change needed (already checks all triggers); note: worker runs every 15 min, location check is passive (last known), no battery cost

**Validation:** unit test — mock LocationManager? LocationManager is final; instead extract `haversineMeters(lat1, lon1, lat2, lon2)` as internal fun + test it directly (0m → true, 5000m vs 100m radius → false). Test checkLocation with mocked Context is hard — test the pure distance math + a fake location provider path via interface extraction if cheap; otherwise document STRONGLY INDICATED for the permission path.

---

## Item 3 — Neuromodulation doesn't reach the LLM (MEDIUM-HIGH)

**Problem:** Python Aura: dopamine→temperature, serotonin→num_predict, norepinephrine→top_p. Android: EmotionEngine computes mood → prompt text only. ChatOptions.temperature/topP never adjusted.

**Files:**
- `aura-core/.../emotion/EmotionEngine.kt` — add `fun samplingAdjustments(): SamplingAdjustments` (data class: temperatureDelta, topPDelta, maxTokensDelta) mapping:
  - energy (0..1) → temperature: base 0.7, energy 0.0 → 0.45 (focused), energy 1.0 → 0.95 (exploratory): `temp = 0.45 + energy * 0.5`
  - focus → topP: focus 0.0 → 0.95, focus 1.0 → 0.80 (tight sampling): `topP = 0.95 - focus * 0.15`
  - tension (0..1) → maxTokens: high tension → shorter responses: `maxTokens = (1.0 - tension*0.4) * base` (only when caller didn't set maxTokens)
  - connection → seed stability (skip — no seed change; keep simple)
- `aura-core/.../agent/MemoryAugmentedAgenticLoop.kt` — where `options` is constructed for the main stream call: if emotionEngine != null, apply `samplingAdjustments()` to temperature/topP (maxTokens only if null)
- Keep prompt-text mood context as-is (both feed)

**Validation:** unit test — EmotionEngine.samplingAdjustments: neutral state (0.5/0.5/0.5/0.5) → temp≈0.7, topP≈0.875; high energy → temp > 0.8; high focus → topP < 0.85. Loop applies adjustments when engine present.

---

## Item 4 — Search tools shotgun → search planner (MEDIUM)

**Problem:** 7 overlapping search tools exposed; LLM picks randomly. Python has search_planner + search_fallback.

**Files:**
- NEW `aura-core/.../tools/SearchPlannerTool.kt` — one tool `search_plan` with `query` + `maxResults`:
  1. Try free sources first: DDG Instant Answer → Searxng → Wikipedia search
  2. Then configured paid: Brave (if key) → Tavily (if key) → web_search capability (if key)
  3. Fallback: jina reader free
  4. Merge + dedupe by URL, return top N
- `aura-core/.../tools/ToolsModule.kt` — register `searchPlanner.tool`
- `aura-core/.../agent/MemoryAugmentedAgenticLoop.kt` — `filterSearchTools()`: hide the individual search tools from the LLM when planner exists (keep planner visible); keep individual tools registered (hands/MCP compat)
- `aura-core/.../tools/SearchPlannerTool.kt` — inject the existing search tools (WebSearchTool, BraveSearchTool, TavilySearchTool, DdgInstantAnswerTool, SearxngSearchTool, WikipediaSearchTool, JinaReaderFreeTool) — all already @Singleton

**Validation:** unit test — planner routes: all-free-unconfigured → wikipedia fallback; bravo configured → brave used; dedupe by URL; result cap.

---

## Item 5 — Subagent system under-leveraged (MEDIUM)

**Problem:** SubagentManager used ONLY by councils. Main loop never delegates.

**Files:**
- NEW `aura-core/.../tools/ParallelResearchTool.kt` — tool `parallel_research`:
  - Input: `question`, `angles: List<String> = 3 default`
  - Decompose: use cheap model to split question into 2-3 sub-questions (or keyword heuristic fallback)
  - Spawn SubagentManager tasks (role="researcher", toolAllowlist=["web_search","brave_search","tavily_search","wikipedia_search","ddg_instant_answer","searxng_search","jina_reader_free"], budgetMs=60_000)
  - Collect results, synthesize with cheap model → single answer
- `aura-core/.../tools/ToolsModule.kt` — register
- `aura-core/.../agents/SubagentManager.kt` — verify `spawn()` handles tool allowlist + budget correctly (it does per contracts); no change needed unless validation shows otherwise

**Validation:** unit test — tool decomposes + spawns (mock SubagentManager), synthesizes; timeouts handled (subagent budget); empty results → error message.

---

## Item 6 — MCTS-lite reasoning (MEDIUM — biggest brain upgrade)

**Problem:** No tree search over reasoning paths. StrategyBandit picks strategy; no branch expansion.

**Files:**
- NEW `aura-core/.../agent/ReasoningTree.kt` — `class ReasoningTree`:
  - `suspend fun explore(userMessage, model, brain, maxBranches=3): String?` — prompt cheap model for 2-3 distinct approach summaries ("Approach 1: ... Approach 2: ..."), value each with a scoring call (cheap model: "score 0-1"), pick best, return best approach as plan prefix
  - Guard: only for messages > 60 chars AND step==1 (not for trivial)
  - Timeout 15s, fallback null (no plan)
- `aura-core/.../agent/MemoryAugmentedAgenticLoop.kt` — in the planning step (existing `needsPlan` block), after the linear plan, if StrategyBandit strategy == MULTI_STEP_REFLECT OR message is long, run ReasoningTree and prepend "## Approach: <best>" 
- Wire through the existing plan prefix mechanism (plan + sys)

**Validation:** unit test — ReasoningTree with mocked brain (returnsMany: branches → scores), picks highest-scoring branch, timeout → null, short message → skipped.

---

## Item 7 — Response cache (LOW-MEDIUM)

**Problem:** Python has `_get_response_cache()`. Android re-answers identical questions.

**Files:**
- NEW `aura-core/.../agent/ResponseCache.kt` — `@Singleton class ResponseCache`:
  - LRU LinkedHashMap<String, CachedAnswer>(maxSize=50, accessOrder=true)
  - Key: normalized user message (lowercase, trim, collapse whitespace) + model
  - Value: (answer, timestamp); TTL 24h
  - `suspend fun get(key): String?`, `fun put(key, answer)`
- `aura-core/.../agent/MemoryAugmentedAgenticLoop.kt` — at start of run(): if lastUserMessage is short (<80 chars) and cache hit → emit a synthetic TextDelta + Result immediately, skip loop (no tools)
- `aura-core/.../agent/MemoryAugmentedAgenticLoop.kt` — at end of run(): if no tool calls and text length > 40, cache it
- Cache only when: no tools used, conversation has ≤2 turns (fresh context), incognito off

**Validation:** unit test — put/get roundtrip, LRU eviction at 50, TTL expiry, normalized key (case/space), skip when tool calls present.

---

## Item 8 — Stale comment (LOW)

**Files:**
- `aura-core/.../agent/MemoryAugmentedAgenticLoop.kt:333-335` — comment says "serverId is sanitized to no underscores" — WRONG since MCP-002 fix. Update to: "serverId may contain underscores; the first segment after the prefix is the server id, the remainder is the tool name — but registration uses the ownership map (registeredNameToServerId) in McpToolBridge for stale cleanup."

---

## Execution order
1. Item 8 (trivial)
2. Item 2 (LocationEntered — small, independent)
3. Item 3 (Neuromodulation — small, independent)
4. Item 1 (Idle-prep delivery — medium, UI wiring)
5. Item 7 (Response cache — medium, independent)
6. Item 4 (Search planner — medium)
7. Item 5 (Parallel subagent — larger)
8. Item 6 (MCTS-lite — largest, last)

**Verification per commit:** compile + targeted test + full suite at logical boundaries (every 2-3 commits).
**Final:** full test suite + assembleDebug + push + release.
