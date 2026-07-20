# Aura Android — SOTA Upgrade Plan: Memory, Deep Research, Multi-Agent System

> **For Hermes:** Use subagent-driven-development skill to implement this plan task-by-task.

**Goal:** Upgrade memory retrieval to cross-encoder reranking + HNSW + query rewriting, transform deep research from single-pass to multi-step agentic research loop, and build a persistent multi-agent system with individual memory, personality, delegation, and user-creatable agents.

**Architecture:** Three major workstreams that share the SubagentManager infrastructure. Memory and Deep Research are self-contained upgrades. The Multi-Agent system unifies Specialists + Creative Council + Subagents into a single AgentEntity-backed system with per-agent memory scopes, personality profiles, delegation, and a generalized council pattern.

**Tech Stack:** Kotlin 1.9.24, Room 2.6.1, Hilt 2.51, Coroutines 1.9.0, Compose, MockK, Turbine

---

## Pre-Audit: What Exists vs What's Needed

### Memory subsystem

| Component | Status | Evidence |
|-----------|--------|----------|
| MemoryDatabase | v13 (12 migrations) | `MemoryDatabase.kt` line 8 |
| MemoryEntity with scope field | EXISTS | `MemoryEntity.kt` — `scope: String = "general"` |
| MemoryDao.withinScope | EXISTS | `MemoryDao.kt` — `suspend fun withinScope(scopePrefix: String, limit: Int)` |
| RRF retrieval (6 signals) | EXISTS | `Retrieval.kt` — rankCandidates with text/vector/recency/access/decay/importance |
| VectorIndex (brute-force) | EXISTS, NOT SOTA | `VectorIndex.kt` — linear cosine scan over all candidates |
| FadeMem (14-day half-life) | EXISTS | `FadeMem.kt` |
| WriteGate (heuristic) | EXISTS | `WriteGate.kt` — keyword-based category + importance |
| LlmWriteGate (cloud LLM) | EXISTS | `LlmWriteGate.kt` — best-effort LLM gate wrapping heuristic |
| CloudEmbedder (Ollama Cloud, LRU cache) | EXISTS | `CloudEmbedder.kt` — SHA-256 keyed LRU, 1000 entries |
| LocalEmbedder (pseudo-embeddings) | EXISTS, NOT SOTA | `LocalEmbedder.kt` — hash-based 384-dim, not real embeddings |
| Semantic dedup (cosine > 0.92) | EXISTS | `MemoryStore.maybeStore()` — `existsByContent` + cosine check |
| Cross-encoder reranking | DOES NOT EXIST | No reranker in `aura-core/src/main/kotlin/com/aura/memory/` |
| HNSW / ANN index | DOES NOT EXIST | `VectorIndex.kt` is O(n) linear scan |
| Query rewriting | DOES NOT EXIST | `MemoryStore.query()` passes raw user text to embedder |
| Recall result caching per user message | DOES NOT EXIST | `MemoryAugmentedAgenticLoop` calls `memoryStore.query()` on every step |
| BM25 with IDF | DOES NOT EXIST | `ScoredMemory.textScore` is term-overlap, not real BM25 |

### Deep Research subsystem

| Component | Status | Evidence |
|-----------|--------|----------|
| DeepResearchTool (single-pass) | EXISTS, NOT SOTA | `DeepResearchTool.kt` — search → fetch → synthesize, 370 lines |
| Sequential source fetching | EXISTS, NOT SOTA | Line 125: `for (citation in citations) { val content = fetchUrlContent(...) }` |
| 6000 char context limit | EXISTS, LOW | `CONTEXT_LIMIT = 6000` — 1500 chars per source for 4 sources |
| 60s timeout | EXISTS | `RESEARCH_TIMEOUT_MS = 60_000L` |
| Provider priority: Tavily > Brave > DDG | EXISTS | `performSearch()` lines 151-157 |
| Parallel source fetching | DOES NOT EXIST | Sequential for-loop at line 125 |
| Multi-step research loop | DOES NOT EXIST | Single search → fetch → synthesize pass |
| Query decomposition | DOES NOT EXIST | Single query, no sub-query expansion |
| Source quality scoring | DOES NOT EXIST | All sources weighted equally |
| Gap detection (what's missing) | DOES NOT EXIST | No "I need more info on X" step |
| Increased context budget (20K+) | DOES NOT EXIST | Hardcoded 6000 |

### Multi-Agent subsystem

| Component | Status | Evidence |
|-----------|--------|----------|
| Specialist (7 personas) | EXISTS, STATELESS | `Specialist.kt` — data class with name/icon/systemPrompt/toolsAllowed |
| SpecialistRouter (keyword-based) | EXISTS | `SpecialistRouter.kt` — regex keyword matching |
| SubagentManager | EXISTS | `SubagentManager.kt` — spawn, spawnAll, budget, progress |
| SubagentSpec / SubagentTask / SubagentResult | EXISTS | `SubagentContracts.kt` — role, objective, modelRole, toolAllowlist, budget |
| CreativeCouncil (10 roles) | EXISTS, SELF-CONTAINED | `CreativeCouncil.kt` + `CouncilRoles.kt` — only used by Creative Studio |
| CouncilRole enum with model roles | EXISTS | `CouncilRoles.kt` — DIRECTOR/WRITER/STORY_EDITOR/etc. |
| AgentEntity (persistent agent) | DOES NOT EXIST | No Room entity for agents |
| Per-agent memory scope | PARTIALLY EXISTS | `MemoryEntity.scope` supports "agent:<id>" but nobody sets it |
| Per-agent conversation tagging | DOES NOT EXIST | `ConversationEntity` has no agentId field |
| Agent delegation tool | DOES NOT EXIST | No `delegate_to_agent` tool |
| Personality profiles | DOES NOT EXIST | `EmotionEngine` tracks user state, not agent personality |
| User-creatable agents UI | DOES NOT EXIST | Settings has specialist prompt override, no agent creation |
| Generalized council (non-creative) | DOES NOT EXIST | CreativeCouncil only works for creative projects |
| AgentRoom database | DOES NOT EXIST | No database for agent entities |
| Backup type for agents | DOES NOT EXIST | `AuraBackup` has no AgentBackup field |
| ModelRole for agents | EXISTS (partially) | `ModelRole` enum has 10 roles, can add AGENT role |
| ConversationCompactor | EXISTS | `ConversationCompactor.kt` — 48 turns, 24 recent, summary |
| AuraBackup SCHEMA_VERSION | 8 | `AuraBackup.kt` line ~46 |

### Room/backup state

| Database | Current version | Entities |
|----------|----------------|----------|
| MemoryDatabase | v13 | memories, memory_feedback, memory_edits, document_chunks, documents, creative_project, creative_artifact, canon_facts, canon_simulations, canon_continuity, canon_dependencies, beliefs, evidence, world_events, opportunities, preference_signals, style_profiles, reference_identities, routing_outcomes |
| ConversationDatabase | v4 | conversations |
| AgentRunDatabase | v1 | agent_run, agent_run_goal, agent_run_step, agent_run_event, agent_run_approval, agent_run_checkpoint |
| EvolutionDatabase | v3 | evolution_candidate, evolution_proposal, evolution_skill_revision, evolution_metrics |
| HandDatabase | v1 | hands |
| TaskDatabase | v2 | tasks, reminders |
| ProactiveEventDatabase | v2 | proactive_events |
| UserProfileDatabase | v1 | user_profile |

---

## Phase Dependency Graph

```
Workstream A: Memory SOTA                    Workstream B: Deep Research SOTA
  Phase 1 (BM25 + query rewrite)                Phase 7 (parallel fetch + context budget)
  Phase 2 (cross-encoder reranking)             Phase 8 (multi-step research loop)
  Phase 3 (HNSW vector index)                   Phase 9 (query decomposition + gap detection)
  Phase 4 (recall caching)
                                                Workstream C: Multi-Agent System
                                                  Phase 5 (AgentEntity + AgentDB)
                                                  Phase 6 (per-agent memory + conversations)
                                                  Phase 10 (personality profiles)
                                                  Phase 11 (agent delegation tool)
                                                  Phase 12 (user-creatable agents UI)
                                                  Phase 13 (generalized council)
                                                  Phase 14 (unify Specialist → Agent)
```

Phases 1-4 are independent (memory). Phases 7-9 are independent (research). Phases 5-6, 10-14 depend on each other (multi-agent). All three workstreams can proceed in parallel after Phase 5 ships (AgentEntity is the foundation; memory and research don't depend on it).

---

## Workstream A: Memory Retrieval SOTA

### Phase 1: BM25 + Query Rewriting

**Objective:** Replace term-overlap textScore with real BM25 (IDF-weighted), add LLM query rewriting for ambiguous queries.

**Files:**
- Create: `aura-core/src/main/kotlin/com/aura/memory/BM25.kt`
- Create: `aura-core/src/main/kotlin/com/aura/memory/QueryRewriter.kt`
- Modify: `aura-core/src/main/kotlin/com/aura/memory/Retrieval.kt` (replace textScore computation)
- Modify: `aura-core/src/main/kotlin/com/aura/memory/MemoryStore.kt` (use BM25 + query rewriter)
- Test: `aura-core/src/test/kotlin/com/aura/memory/BM25Test.kt`
- Test: `aura-core/src/test/kotlin/com/aura/memory/QueryRewriterTest.kt`

**Approach:**

BM25 scorer:
- Tokenize the memory corpus, build IDF map (log(N/df)).
- Score each document: sum over query terms of IDF(t) * (k1+1) * tf / (tf + k1 * (1 - b + b * |d|/avgdl)).
- k1=1.2, b=0.75 (standard BM25 params).
- BM25 runs on the memory corpus at query time. For 1000 memories this is <10ms.
- Score normalized to 0-1 by dividing by max possible score.

Query rewriter:
- A cheap LLM call (the gate model — small/fast) that rewrites the user's message into a retrieval query.
- "what about that thing we discussed" → "database migration strategy discussion".
- Only rewrites if the original query is <5 content words or contains deictic references ("that", "this", "it", "we discussed").
- Returns the original query unchanged if the LLM call fails or if the query is already specific.
- Cached per user message (same cache key as the recall cache in Phase 4).

**Tasks:**

#### Task 1.1: BM25 scorer

Create `BM25.kt` with:
- `class BM25(documents: List<String>)` — builds IDF map + doc length stats at construction.
- `fun score(query: String, docIndex: Int): Float` — BM25 score for a single doc.
- `fun rank(query: String, topK: Int): List<Pair<Int, Float>>` — top-K doc indices + scores.
- Tokenizer: lowercase, split on non-alphanumeric, filter empty, add bigrams.
- IDF: `log((N - df + 0.5) / (df + 0.5))`, floored at 0.
- k1=1.2, b=0.75.

Test: BM25Test with 10 docs, verify that a query "kotlin coroutines" ranks a doc about "Kotlin coroutine dispatchers" higher than a doc about "Python async".

#### Task 1.2: Query rewriter

Create `QueryRewriter.kt` with:
- `class QueryRewriter(registry: ProviderRegistry, modelId: String)`.
- `suspend fun rewrite(userMessage: String, recentContext: String): String`.
- Heuristic gate: only rewrite if userMessage has <5 content words OR contains deictic patterns (regex: `\b(that|this|it|we|you|they)\b.*\b(discussed|talked|mentioned|said|about)\b`).
- LLM prompt: "Rewrite this user message into a search query for retrieving relevant memories. Return only the query, no explanation. Message: '{userMessage}' Recent context: '{recentContext}'".
- Returns original on any failure.
- Temperature 0.0, maxTokens 50.

Test: QueryRewriterTest with mockk ProviderRegistry. Verify: specific queries pass through, deictic queries get rewritten, LLM failure falls back to original.

#### Task 1.3: Wire BM25 + query rewriter into MemoryStore

Modify `MemoryStore.kt`:
- Add `bm25: BM25?` as a lazily-computed field, rebuilt when memory count changes by >10%.
- In `query()`: use query rewriter first, then BM25 for textScore instead of term overlap.
- Pass BM25 scores into `ScoredMemory.textScore`.

Modify `Retrieval.kt`:
- No change to RRF logic — it already consumes `textScore` as-is. BM25 just produces better scores.

Test: extend existing `RetrievalTest.kt` with BM25-scored candidates.

#### Task 1.4: Commit

```bash
git add aura-core/src/main/kotlin/com/aura/memory/BM25.kt \
  aura-core/src/main/kotlin/com/aura/memory/QueryRewriter.kt \
  aura-core/src/main/kotlin/com/aura/memory/MemoryStore.kt \
  aura-core/src/test/kotlin/com/aura/memory/BM25Test.kt \
  aura-core/src/test/kotlin/com/aura/memory/QueryRewriterTest.kt
git commit -m "feat(memory): BM25 scorer + LLM query rewriting for retrieval

Replace term-overlap textScore with real BM25 (IDF-weighted, k1=1.2,
b=0.75). Add query rewriter that rewrites deictic queries ('that thing
we discussed') into retrieval queries via a cheap LLM call. Falls back
to original query on any failure."
```

---

### Phase 2: Cross-Encoder Reranking

**Objective:** After RRF picks top-20 candidates, run a cross-encoder reranker to score each (query, memory) pair for actual semantic relevance. This is the single biggest quality leap in RAG.

**Files:**
- Create: `aura-core/src/main/kotlin/com/aura/memory/CrossEncoderReranker.kt`
- Modify: `aura-core/src/main/kotlin/com/aura/memory/MemoryStore.kt` (add reranking step after RRF)
- Test: `aura-core/src/test/kotlin/com/aura/memory/CrossEncoderRerankerTest.kt`

**Approach:**

Cross-encoder reranker:
- `class CrossEncoderReranker(registry: ProviderRegistry, modelId: String)`.
- `suspend fun rerank(query: String, candidates: List<MemoryEntity>, topK: Int): List<MemoryEntity>`.
- For each candidate, send a prompt to the LLM: "Rate the relevance of this memory to the query on a scale of 0-10. Memory: '{content}' Query: '{query}' Return only a number."
- Parse the score, sort descending, return top-K.
- Batch up to 5 candidates per LLM call (to limit API calls): "Rate the relevance of each memory. Return JSON array of numbers."
- Total API cost: 1 LLM call per 5 candidates. For 20 RRF candidates, that's 4 calls.
- Falls back to RRF order on any failure (never blocks retrieval).
- Uses the FAST model role (small/cheap) by default.
- Only runs if there are >5 candidates (otherwise RRF is sufficient).

**Tasks:**

#### Task 2.1: Cross-encoder reranker

Create `CrossEncoderReranker.kt`:
- Constructor takes `ProviderRegistry` + `modelId`.
- `suspend fun rerank(query: String, candidates: List<MemoryEntity>, topK: Int = 5): List<MemoryEntity>`.
- Batch size: 5 candidates per LLM call.
- Prompt: "You are a relevance judge. For each memory below, rate how relevant it is to the query on a scale of 0-10. Return ONLY a JSON array of numbers, one per memory, in order. Query: '{query}' Memories:\n1. {content}\n2. {content}\n..."
- Parse JSON array of floats, sort candidates by score descending, return top-K.
- On parse failure: return candidates in original RRF order.
- On timeout/failure: return candidates in original RRF order.

Test: CrossEncoderRerankerTest with mockk. Verify: correct ordering, JSON parse failure falls back, empty candidates returns empty, <5 candidates passes through.

#### Task 2.2: Wire reranker into MemoryStore

Modify `MemoryStore.query()`:
- After RRF `Retrieval.rankCandidates()` returns top-20, pass to `crossEncoderReranker.rerank()` to get final top-5.
- If reranker is null or fails, RRF top-5 is the result (current behavior).
- Add `crossEncoderReranker: CrossEncoderReranker?` to MemoryStore constructor (nullable, injected by Hilt).

Test: extend MemoryStoreTest with mocked reranker.

#### Task 2.3: Commit

```bash
git add aura-core/src/main/kotlin/com/aura/memory/CrossEncoderReranker.kt \
  aura-core/src/main/kotlin/com/aura/memory/MemoryStore.kt \
  aura-core/src/test/kotlin/com/aura/memory/CrossEncoderRerankerTest.kt
git commit -m "feat(memory): cross-encoder reranking for retrieval quality

After RRF picks top-20 candidates, a cross-encoder reranker scores
each (query, memory) pair via a cheap LLM call. Batches 5 candidates
per call (4 calls for 20 candidates). Falls back to RRF order on any
failure. Uses FAST model role. Single biggest RAG quality leap."
```

---

### Phase 3: HNSW Vector Index

**Objective:** Replace O(n) brute-force cosine scan with O(log n) approximate nearest neighbor search.

**Files:**
- Create: `aura-core/src/main/kotlin/com/aura/memory/HnswIndex.kt`
- Modify: `aura-core/src/main/kotlin/com/aura/memory/MemoryStore.kt` (replace VectorIndex with HnswIndex)
- Test: `aura-core/src/test/kotlin/com/aura/memory/HnswIndexTest.kt`

**Approach:**

HNSW (Hierarchical Navigable Small World):
- Pure Kotlin implementation, no external dependency.
- Parameters: M=16 (max connections per node), efConstruction=200 (build quality), efSearch=50 (query quality).
- Insert: add to graph, connect to M nearest neighbors at each level.
- Search: start at top layer, greedily descend, beam search at layer 0.
- Thread-safe: synchronized insert, concurrent read.
- Rebuild on startup from all `allWithEmbeddings()` memories.
- Incremental: add new memories as they're stored.
- 384-dim, cosine similarity (normalized dot product).

This is a bigger lift (~400 lines of Kotlin). The alternative is a simpler IVF (inverted file index) which is ~150 lines. Start with IVF, upgrade to HNSW if needed.

**Simplified approach (IVF):**
- Partition the embedding space into K clusters (K = sqrt(N)).
- Assign each memory to nearest cluster centroid.
- At query time, find nearest cluster centroid, search within that cluster + adjacent clusters.
- O(sqrt(N)) instead of O(N).
- Simpler to implement, slightly lower recall than HNSW but good enough for personal-scale (<10K memories).

**Tasks:**

#### Task 3.1: IVF index

Create `HnswIndex.kt` (named for future HNSW upgrade, starts as IVF):
- `class VectorIndexIVF(dim: Int = 384, numClusters: Int = 0)`.
- `fun add(id: String, embedding: FloatArray)`.
- `fun build()` — run k-means to find centroids, assign points to clusters.
- `fun search(query: FloatArray, topK: Int, nprobe: Int = 2): List<Hit>` — search nprobe nearest clusters.
- Auto-rebuild when size doubles since last build.
- Thread-safe: synchronized add/build, concurrent search.

Test: HnswIndexTest with 100 random vectors, verify recall > 80% vs brute-force on same data.

#### Task 3.2: Wire IVF into MemoryStore

Modify `MemoryStore`:
- Replace `VectorIndex` with `VectorIndexIVF`.
- On startup (or first query), load `allWithEmbeddings()` into the index.
- On `store()`, add to index after embedding.
- On `query()`, use IVF search instead of brute-force.

#### Task 3.3: Commit

```bash
git add aura-core/src/main/kotlin/com/aura/memory/HnswIndex.kt \
  aura-core/src/main/kotlin/com/aura/memory/MemoryStore.kt \
  aura-core/src/test/kotlin/com/aura/memory/HnswIndexTest.kt
git commit -m "feat(memory): IVF vector index for sub-linear retrieval

Replace O(n) brute-force cosine scan with IVF (inverted file index).
K-means clustering, O(sqrt(N)) query. Auto-rebuilds when size doubles.
80%+ recall vs brute-force on 100-vector test."
```

---

### Phase 4: Recall Caching Per User Message

**Objective:** Cache recall results per user message so multi-step agentic loops don't re-rank on every step.

**Files:**
- Modify: `aura-core/src/main/kotlin/com/aura/agent/MemoryAugmentedAgenticLoop.kt`
- Test: no new test file — this is a caching optimization, existing tests verify correctness.

**Approach:**

In `MemoryAugmentedAgenticLoop.run()`:
- Add `var cachedRecall: Pair<String, List<MemoryEntity>>? = null` (message → hits).
- Before calling `memoryStore.query()`, check if `cachedRecall?.first == lastUserMessage`. If yes, reuse the hits.
- After the first recall, cache the results.
- Clear the cache when the user sends a new message (new turn).
- The embedder LRU cache already caches embeddings; this caches the full recall result (RRF + rerank).

This is a ~10-line change. The impact is significant: a 5-step agentic loop currently calls `memoryStore.query()` 5 times for the same message. Each call is an embedder hit (cached) + RRF ranking + DB query + (after Phase 2) cross-encoder reranking. With caching, it's 1 call.

#### Task 4.1: Add recall cache to agentic loop

#### Task 4.2: Commit

```bash
git add aura-core/src/main/kotlin/com/aura/agent/MemoryAugmentedAgenticLoop.kt
git commit -m "perf(memory): cache recall results per user message

Multi-step agentic loops were calling memoryStore.query() on every
step for the same user message. Now cached after step 1. Saves RRF
ranking + DB query + cross-encoder reranking on steps 2-N."
```

---

## Workstream B: Deep Research SOTA

### Phase 7: Parallel Source Fetching + Context Budget

**Objective:** Fetch sources in parallel (5x latency improvement) and increase context budget from 6000 to 20000 chars.

**Files:**
- Modify: `aura-core/src/main/kotlin/com/aura/tools/DeepResearchTool.kt`
- Modify: `aura-core/src/test/kotlin/com/aura/tools/DeepResearchToolTest.kt`

**Approach:**

In `runResearch()`:
- Replace the sequential `for (citation in citations) { fetchUrlContent(...) }` with `coroutineScope { citations.map { async { fetchUrlContent(it.url) } }.awaitAll() }`.
- Increase `CONTEXT_LIMIT` from 6000 to 20000.
- Increase per-source truncation from 1500 to 3000 chars (still fits in most model context windows).
- Increase `RESEARCH_TIMEOUT_MS` from 60s to 90s (parallel fetch is faster, but more sources + larger context = longer synthesis).

**Tasks:**

#### Task 7.1: Parallel fetch

Modify `DeepResearchTool.runResearch()`:
```kotlin
// BEFORE (sequential):
val contents = mutableMapOf<String, String>()
for (citation in citations) {
    val content = fetchUrlContent(citation.url)
    if (!content.isNullOrBlank()) { contents[citation.url] = content }
}

// AFTER (parallel):
val contents = coroutineScope {
    citations.map { citation ->
        async { citation.url to fetchUrlContent(citation.url) }
    }.awaitAll()
        .filter { (_, content) -> !content.isNullOrBlank() }
        .associate { (url, content) -> url to content!! }
}
```

#### Task 7.2: Increase context budget

```kotlin
companion object {
    const val RESEARCH_TIMEOUT_MS = 90_000L
    const val CONTEXT_LIMIT = 20_000
    private const val PER_SOURCE_LIMIT = 3_000  // was 1500
}
```

Update `buildContextBlock` to use `PER_SOURCE_LIMIT` instead of hardcoded 1500.

#### Task 7.3: Update tests + commit

```bash
git add aura-core/src/main/kotlin/com/aura/tools/DeepResearchTool.kt \
  aura-core/src/test/kotlin/com/aura/tools/DeepResearchToolTest.kt
git commit -m "feat(research): parallel source fetching + 3x context budget

Sources fetched in parallel via async+awaitAll (5x latency reduction).
Context budget 6000→20000 chars, per-source 1500→3000. Timeout
60s→90s. Synthesis now has enough context for genuinely useful answers."
```

---

### Phase 8: Multi-Step Research Loop

**Objective:** Transform deep research from single-pass (search → fetch → synthesize) to multi-step (search → read → identify gaps → search again → synthesize). 2-3 iterations.

**Files:**
- Modify: `aura-core/src/main/kotlin/com/aura/tools/DeepResearchTool.kt`
- Test: `aura-core/src/test/kotlin/com/aura/tools/DeepResearchToolTest.kt`

**Approach:**

New research loop:
1. **Search** — initial search, get top sources.
2. **Fetch + read** — fetch content, build context block.
3. **Gap detection** — ask the model: "Based on these sources, what key information is missing to fully answer the research question? List specific gaps as search queries."
4. **Targeted search** — run gap queries as additional searches, fetch new sources.
5. **Synthesize** — final synthesis with all sources.
6. Max 2 iterations (initial + 1 gap-fill). Each iteration has a 45s budget.

Gap detection prompt:
```
You are a research analyst. Based on the sources below, identify what
key information is MISSING to fully answer the research question. Return
up to 3 specific search queries that would fill the gaps. If the sources
are sufficient, return an empty array [].

Research question: {query}

Sources:
{context_block}

Return ONLY a JSON array of search query strings.
```

**Tasks:**

#### Task 8.1: Gap detection function

Add `suspend fun detectGaps(query: String, context: String, modelId: String): List<String>` to DeepResearchTool.
- LLM call with the gap detection prompt.
- Parse JSON array of strings.
- Return empty list on failure.
- Max 3 gap queries.

#### Task 8.2: Multi-step research loop

Refactor `runResearch()`:
```kotlin
private suspend fun runResearch(query: String, maxSources: Int, modelArg: String?): String = withTimeout(RESEARCH_TIMEOUT_MS) {
    val allCitations = mutableListOf<Citation>()
    val allContents = mutableMapOf<String, String>()
    val searchedQueries = mutableSetOf(query)

    // Iteration 1: initial search
    searchAndFetch(query, maxSources, allCitations, allContents)

    // Iteration 2: gap detection + targeted search
    val context = buildContextBlock(allCitations, allContents)
    val gaps = detectGaps(query, context, modelId)
    for (gapQuery in gaps.take(3)) {
        if (gapQuery !in searchedQueries) {
            searchedQueries.add(gapQuery)
            searchAndFetch(gapQuery, maxSources / 2, allCitations, allContents)
        }
    }

    // Final synthesis
    val fullContext = buildContextBlock(allCitations, allContents)
    val answer = synthesizeAnswer(query, fullContext, modelId)
    buildJsonOutput(answer, allCitations.distinctBy { it.url })
}
```

#### Task 8.3: Commit

```bash
git add aura-core/src/main/kotlin/com/aura/tools/DeepResearchTool.kt \
  aura-core/src/test/kotlin/com/aura/tools/DeepResearchToolTest.kt
git commit -m "feat(research): multi-step research loop with gap detection

Research now runs 2 iterations: initial search → fetch → gap detection
→ targeted search for missing info → final synthesis. The model
identifies what's missing and generates follow-up queries. Max 3
gap queries per iteration. Transforms research from single-pass
search+summarize to genuinely iterative investigation."
```

---

### Phase 9: Query Decomposition + Source Quality Scoring

**Objective:** Decompose complex queries into sub-queries, score sources by quality.

**Files:**
- Modify: `aura-core/src/main/kotlin/com/aura/tools/DeepResearchTool.kt`
- Test: `aura-core/src/test/kotlin/com/aura/tools/DeepResearchToolTest.kt`

**Approach:**

Query decomposition:
- Before searching, ask the model: "Decompose this research question into 1-3 sub-questions that can be searched independently. Return JSON array."
- "Compare iPhone vs Samsung cameras" → ["iPhone camera specs and reviews", "Samsung camera specs and reviews", "iPhone vs Samsung camera comparison"].
- Each sub-query searched in parallel, results merged.
- Only decompose if the query contains comparison/multi-entity patterns (regex: `\b(vs|versus|compare|comparison|difference between|better than)\b` or multiple proper nouns).

Source quality scoring:
- Score each fetched source: domain authority (hardcoded list: wikipedia.org=0.9, arxiv.org=0.95, github.com=0.8, *.gov=0.85, else=0.5), content length (>2000 chars = +0.1), recency (has a date in the content = +0.05).
- Weight content by quality score in the context block: high-quality sources get more chars, low-quality get fewer.
- Don't drop low-quality sources — just give them less context budget.

**Tasks:**

#### Task 9.1: Query decomposition

Add `suspend fun decomposeQuery(query: String, modelId: String): List<String>`.
- Only decompose if query matches comparison/multi-entity patterns.
- LLM prompt: "Decompose this research question into 1-3 sub-questions. Return JSON array of strings."
- Returns [query] (single-element) if not decomposable.

#### Task 9.2: Source quality scoring

Add `fun scoreSource(url: String, content: String): Float`.
- Domain authority lookup (hardcoded map of known domains).
- Content length bonus.
- Return 0-1 score.

Modify `buildContextBlock()`:
- Sort citations by quality score descending.
- Allocate per-source budget proportional to score: `budget = PER_SOURCE_LIMIT * (0.5 + 0.5 * score)`.
- High-quality sources get full 3000 chars, low-quality get ~1500.

#### Task 9.3: Wire decomposition into research loop

Modify `runResearch()`:
- Call `decomposeQuery()` first.
- If decomposed into multiple sub-queries, search each in parallel, merge results.
- Dedup by URL.

#### Task 9.4: Commit

```bash
git add aura-core/src/main/kotlin/com/aura/tools/DeepResearchTool.kt \
  aura-core/src/test/kotlin/com/aura/tools/DeepResearchToolTest.kt
git commit -m "feat(research): query decomposition + source quality scoring

Complex queries (comparisons, multi-entity) are decomposed into
sub-queries searched in parallel. Sources scored by domain authority,
content length, and recency. High-quality sources get more context
budget. Wikipedia/arxiv/gov score higher than random blogs."
```

---

## Workstream C: Multi-Agent System

### Phase 5: AgentEntity + AgentDatabase

**Objective:** Create the foundational AgentEntity — a persistent, named agent with identity, tools, model, and memory scope. This replaces Specialist as the canonical agent definition.

**Files:**
- Create: `aura-core/src/main/kotlin/com/aura/agent/AgentEntity.kt`
- Create: `aura-core/src/main/kotlin/com/aura/agent/AgentDao.kt`
- Create: `aura-core/src/main/kotlin/com/aura/agent/AgentDatabase.kt`
- Create: `aura-core/src/main/kotlin/com/aura/agent/AgentModule.kt`
- Create: `aura-core/src/main/kotlin/com/aura/agent/AgentStore.kt`
- Modify: `aura-core/src/main/kotlin/com/aura/backup/AuraBackup.kt` (add AgentBackup + SCHEMA_VERSION bump)
- Modify: `aura-core/src/main/kotlin/com/aura/backup/BackupManager.kt` (snapshot/restore/purge agents)
- Test: `aura-core/src/test/kotlin/com/aura/agent/AgentStoreTest.kt`

**AgentEntity schema:**
```kotlin
@Entity(tableName = "agents", indices = [Index("name", unique = true)])
data class AgentEntity(
    @PrimaryKey val id: String,
    val name: String,               // "Aria", "Codex", "Sage"
    val icon: String,               // emoji
    val description: String,        // one-line description for picker
    val identity: String,           // full system prompt
    val toolsAllowed: String,      // comma-separated tool names
    val preferredModel: String?,   // null = use conversation default
    val memoryScope: String,        // "shared" or "agent:<id>"
    val personalityJson: String,    // PersonalityProfile as JSON
    val isBuiltin: Boolean,        // true for 7 defaults
    val isDefault: Boolean,         // true for the default agent (General)
    val createdAt: Long,
    val updatedAt: Long,
    val color: Int = 0,            // Material color code for UI
)
```

**AgentDao:**
```kotlin
@Dao
interface AgentDao {
    @Query("SELECT * FROM agents ORDER BY isDefault DESC, name ASC")
    fun all(): Flow<List<AgentEntity>>

    @Query("SELECT * FROM agents WHERE id = :id")
    suspend fun getById(id: String): AgentEntity?

    @Query("SELECT * FROM agents WHERE name = :name LIMIT 1")
    suspend fun byName(name: String): AgentEntity?

    @Query("SELECT * FROM agents WHERE isBuiltin = 1")
    suspend fun builtins(): List<AgentEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(agent: AgentEntity)

    @Delete
    suspend fun delete(agent: AgentEntity)

    @Query("DELETE FROM agents WHERE id = :id AND isBuiltin = 0")
    suspend fun deleteCustom(id: String)

    @Query("SELECT COUNT(*) FROM agents")
    suspend fun count(): Int
}
```

**AgentStore:**
- `suspend fun all(): List<AgentEntity>`
- `suspend fun byId(id: String): AgentEntity?`
- `suspend fun byName(name: String): AgentEntity?`
- `suspend fun create(name, icon, identity, tools, model, personality): AgentEntity`
- `suspend fun update(agent: AgentEntity)`
- `suspend fun delete(id: String)` — only custom agents
- `fun seedBuiltins()` — inserts the 7 default agents on first run, mapping from current `Specialist.ALL`

**Tasks:**

#### Task 5.1: AgentEntity + AgentDao + AgentDatabase

Create the entity, DAO, database (v1), and module. Database is standalone (not in MemoryDatabase) — agents are orthogonal to memories.

#### Task 5.2: AgentStore

Create the store with CRUD + `seedBuiltins()`. The seed maps the 7 Specialists to AgentEntities:
- General → agent:general, memoryScope=shared, isDefault=true
- Coder → agent:coder, memoryScope=agent:coder
- Researcher → agent:researcher, memoryScope=agent:researcher
- Writer → agent:writer, memoryScope=agent:writer
- Creative → agent:creative, memoryScope=agent:creative
- Executive → agent:executive, memoryScope=agent:executive
- PhoneNative → agent:phone_native, memoryScope=agent:phone_native

General keeps `memoryScope=shared` (sees all memories). Others use `agent:<name>` (see only their own + shared).

#### Task 5.3: Backup support

Add to `AuraBackup.kt`:
```kotlin
val agents: List<AgentBackup> = emptyList(),
```
Bump `SCHEMA_VERSION` from 8 to 9.

Add `AgentBackup` data class mirroring `AgentEntity`.

Modify `BackupManager`:
- `snapshot()`: read all agents, add to backup.
- `restore()`: insert agents (skip builtins that already exist).
- `purgeAll()`: delete custom agents only.
- `RestoreCounts`: add `agents: Int = 0`.

#### Task 5.4: Tests

AgentStoreTest: CRUD, seedBuiltins, delete custom only, byName lookup.

#### Task 5.5: Commit

```bash
git commit -m "feat(agents): AgentEntity + AgentDatabase + AgentStore

Foundation for persistent multi-agent system. AgentEntity replaces
Specialist as the canonical agent definition. 7 builtin agents seeded
from existing Specialists. General=shared memory, others=private.
Backup support (SCHEMA_VERSION 8→9)."
```

---

### Phase 6: Per-Agent Memory + Conversation Tagging

**Objective:** Wire agent-scoped memory into the retrieval pipeline and tag conversations with agent IDs.

**Files:**
- Modify: `aura-core/src/main/kotlin/com/aura/memory/MemoryStore.kt` (scope filtering)
- Modify: `aura-core/src/main/kotlin/com/aura/memory/MemoryDao.kt` (add scoped query)
- Modify: `aura-core/src/main/kotlin/com/aura/agent/ConversationEntity.kt` (add agentId field)
- Modify: `aura-core/src/main/kotlin/com/aura/agent/ConversationDatabase.kt` (v4→v5 migration)
- Modify: `aura-core/src/main/kotlin/com/aura/agent/ConversationModule.kt` (add MIGRATION_4_5)
- Modify: `aura-core/src/main/kotlin/com/aura/agent/MemoryAugmentedAgenticLoop.kt` (pass agentId, filter recall by scope)
- Modify: `aura-core/src/main/kotlin/com/aura/backup/AuraBackup.kt` (ConversationBackup add agentId)
- Test: extend existing tests

**Approach:**

Memory scope filtering:
- `MemoryStore.query()` takes an optional `scopeFilter: Set<String>` parameter.
- Default: `setOf("general")` — sees only shared memories.
- When agent is active: `setOf("general", "agent:<agentId>")` — sees shared + agent's own.
- `MemoryDao` gets a new query: `@Query("SELECT * FROM memories WHERE scope IN (:scopes) ORDER BY createdAt DESC LIMIT :limit") suspend fun byScopes(scopes: Set<String>, limit: Int): List<MemoryEntity>`.
- RRF ranking runs only on scope-filtered candidates.

Conversation tagging:
- `ConversationEntity` gets `val agentId: String? = null` — null = General/default.
- ConversationDatabase v4→v5 migration: `ALTER TABLE conversations ADD COLUMN agentId TEXT`.
- `ConversationStore.create()` takes optional `agentId`.
- `MemoryAugmentedAgenticLoop.run()` takes `agentId: String?` parameter, passes scope to MemoryStore.

**Tasks:**

#### Task 6.1: Add agentId to ConversationEntity + migration v4→v5

#### Task 6.2: Add scope-filtered query to MemoryDao

#### Task 6.3: Wire agentId + scope filtering into agentic loop

Modify `MemoryAugmentedAgenticLoop.run()`:
- Add `agentId: String? = null` parameter.
- Resolve agent's memoryScope from AgentStore.
- Build scopeFilter: if agent has `memoryScope=shared`, use `setOf("general")`. If `agent:<id>`, use `setOf("general", "agent:<id>")`.
- Pass scopeFilter to `memoryStore.query()`.
- Store memories with `scope = agentScope` (agent's own scope, not "general").

#### Task 6.4: Update ConversationBackup + backup restore

#### Task 6.5: Commit

```bash
git commit -m "feat(agents): per-agent memory scopes + conversation tagging

MemoryStore.query() now filters by scope set. General agent sees shared
memories. Other agents see shared + their own private memories.
Conversations tagged with agentId. ConversationDB v4→v5 migration."
```

---

### Phase 10: Personality Profiles

**Objective:** Give each agent a behavioral profile (warmth, formality, verbosity, humor, proactivity, riskTolerance) that's injected into the system prompt as tone directives.

**Files:**
- Create: `aura-core/src/main/kotlin/com/aura/agent/PersonalityProfile.kt`
- Modify: `aura-core/src/main/kotlin/com/aura/agent/AgentEntity.kt` (personalityJson field already in schema)
- Modify: `aura-core/src/main/kotlin/com/aura/agent/MemoryAugmentedAgenticLoop.kt` (inject personality into system prompt)
- Test: `aura-core/src/test/kotlin/com/aura/agent/PersonalityProfileTest.kt`

**PersonalityProfile:**
```kotlin
@Serializable
data class PersonalityProfile(
    val warmth: Float = 0.5f,        // 0=terse, 1=warm
    val formality: Float = 0.5f,     // 0=casual, 1=formal
    val verbosity: Float = 0.5f,     // 0=concise, 1=verbose
    val humor: Float = 0.3f,         // 0=serious, 1=playful
    val proactivity: Float = 0.5f,   // 0=reactive, 1=proactive
    val riskTolerance: Float = 0.5f, // 0=conservative, 1=experimental
) {
    fun toPromptDirective(): String = buildString {
        append("Tone: ")
        if (warmth > 0.7f) append("Be warm and friendly. ")
        else if (warmth < 0.3f) append("Be direct and businesslike. ")
        if (formality > 0.7f) append("Use formal language. ")
        else if (formality < 0.3f) append("Be casual. ")
        if (verbosity > 0.7f) append("Be thorough and detailed. ")
        else if (verbosity < 0.3f) append("Be concise. ")
        if (humor > 0.7f) append("Use humor where appropriate. ")
        else if (humor < 0.3f) append("Stay serious. ")
        if (proactivity > 0.7f) append("Anticipate follow-up needs. ")
        if (riskTolerance > 0.7f) append("Suggest creative alternatives. ")
        else if (riskTolerance < 0.3f) append("Prefer proven approaches. ")
    }.trim()
}
```

**Builtin personalities:**
- General: warmth=0.6, formality=0.4, verbosity=0.5, humor=0.5, proactivity=0.5, risk=0.5
- Coder: warmth=0.3, formality=0.7, verbosity=0.3, humor=0.2, proactivity=0.7, risk=0.3
- Researcher: warmth=0.5, formality=0.7, verbosity=0.7, humor=0.2, proactivity=0.7, risk=0.5
- Writer: warmth=0.7, formality=0.3, verbosity=0.6, humor=0.6, proactivity=0.5, risk=0.7
- Creative: warmth=0.7, formality=0.2, verbosity=0.5, humor=0.7, proactivity=0.5, risk=0.8
- Executive: warmth=0.3, formality=0.7, verbosity=0.2, humor=0.2, proactivity=0.6, risk=0.3
- PhoneNative: warmth=0.4, formality=0.3, verbosity=0.2, humor=0.4, proactivity=0.6, risk=0.5

**Tasks:**

#### Task 10.1: PersonalityProfile data class + prompt directive

#### Task 10.2: Inject personality into agentic loop

In `MemoryAugmentedAgenticLoop.run()`, after specialist identity, add:
```kotlin
val personalityDirective = agent?.personality?.toPromptDirective() ?: ""
// Add to system prompt: ... + personalityDirective + memoryContext + ...
```

#### Task 10.3: Commit

```bash
git commit -m "feat(agents): personality profiles with tone directives

Each agent has a 6-dimension personality profile (warmth, formality,
verbosity, humor, proactivity, riskTolerance) injected into the system
prompt as tone directives. 7 builtin personalities defined. Custom
agents get user-configurable sliders in Settings (Phase 12)."
```

---

### Phase 11: Agent Delegation Tool

**Objective:** Add a `delegate_to_agent` tool that lets the main agent call other agents as subagents.

**Files:**
- Create: `aura-core/src/main/kotlin/com/aura/tools/DelegateToAgentTool.kt`
- Modify: `aura-core/src/main/kotlin/com/aura/tools/ToolsModule.kt` (register the tool)
- Test: `aura-core/src/test/kotlin/com/aura/tools/DelegateToAgentToolTest.kt`

**Approach:**

The tool:
```kotlin
val tool = Tool(
    name = "delegate_to_agent",
    description = "Delegate a subtask to a specialist agent. The agent runs with its own tools, memory, and personality. Returns a structured result.",
    risk = ToolRisk.REMOTE_COST,
    parameters = ToolParameters(
        properties = mapOf(
            "agent_name" to ToolProperty(type = "string", description = "Name of the agent to delegate to (e.g. 'researcher', 'coder')"),
            "task" to ToolProperty(type = "string", description = "The subtask to delegate"),
            "context" to ToolProperty(type = "string", description = "Additional context for the agent"),
        ),
        required = listOf("agent_name", "task"),
    ),
    execute = { call, ctx ->
        // 1. Look up agent by name from AgentStore
        // 2. Create SubagentSpec with agent's identity, tools, model
        // 3. Run a mini agentic loop (3-5 steps max) with the agent's config
        // 4. Return the agent's response as ToolResult.Ok
    },
)
```

The delegated agent runs a simplified agentic loop:
- Gets the agent's system prompt + personality directive.
- Gets the agent's tool allowlist.
- Has its own 5-step max loop.
- Can recall memories from the agent's scope.
- Cannot delegate further (no recursive delegation).
- Returns a structured result: the agent's final text response.

**Tasks:**

#### Task 11.1: DelegateToAgentTool

Create the tool with:
- Agent lookup from AgentStore.
- SubagentSpec construction with agent's config.
- Mini agentic loop (simplified: no tool badges, no streaming, just text + tool calls).
- 30s timeout per delegation.

#### Task 11.2: Register in ToolsModule

Add `registry.register(delegateToAgent.tool)`.

#### Task 11.3: Test + commit

```bash
git commit -m "feat(agents): delegate_to_agent tool for multi-agent collaboration

The main agent can delegate subtasks to specialist agents. Each
delegated agent runs with its own identity, tools, memory scope, and
personality. 5-step max, 30s timeout, no recursive delegation. The
agent's response is returned as a tool result."
```

---

### Phase 12: User-Creatable Agents UI

**Objective:** Settings UI for creating, editing, and deleting custom agents with personality sliders.

**Files:**
- Create: `app/src/main/kotlin/com/aura/ui/screens/AgentEditorScreen.kt`
- Create: `app/src/main/kotlin/com/aura/ui/viewmodel/AgentEditorViewModel.kt`
- Modify: `app/src/main/kotlin/com/aura/ui/nav/NavGraph.kt` (add agent editor route)
- Modify: `app/src/main/kotlin/com/aura/ui/settings/sections/AiAndModelsSection.kt` (add "Agents" link)
- Modify: `app/src/main/kotlin/com/aura/ui/screens/SettingsScreen.kt` (add navigation callback)

**Approach:**

AgentEditorScreen:
- Name field, icon picker (emoji), description field.
- Identity/system prompt text field (multi-line).
- Tool allowlist: chips for each available tool, toggle on/off.
- Model picker: dropdown of configured models (or "use default").
- Personality sliders: 6 sliders (warmth, formality, verbosity, humor, proactivity, riskTolerance).
- Memory scope toggle: "Shared (sees all memories)" vs "Private (sees only its own)".
- Color picker: Material color options for the agent's avatar.
- Save / Delete buttons.
- Delete is disabled for builtin agents.

**Tasks:**

#### Task 12.1: AgentEditorViewModel

#### Task 12.2: AgentEditorScreen with personality sliders

#### Task 12.3: Wire into NavGraph + Settings

#### Task 12.4: Commit

```bash
git commit -m "feat(agents): user-creatable agents UI in Settings

Agent editor screen with name, icon, identity, tool allowlist, model
picker, 6 personality sliders, memory scope toggle, and color. Users
can create custom agents that appear in the chat specialist picker.
Builtin agents can be edited but not deleted."
```

---

### Phase 13: Generalized Agent Council

**Objective:** Extend the Creative Council pattern to work for any task, not just creative projects.

**Files:**
- Create: `aura-core/src/main/kotlin/com/aura/agent/AgentCouncil.kt`
- Create: `aura-core/src/main/kotlin/com/aura/tools/RunCouncilTool.kt`
- Modify: `aura-core/src/main/kotlin/com/aura/tools/ToolsModule.kt` (register tool)
- Test: `aura-core/src/test/kotlin/com/aura/agent/AgentCouncilTest.kt`

**Approach:**

AgentCouncil generalizes CreativeCouncil:
- Takes a list of agent IDs + a task.
- Each agent runs as a subagent with its own identity, tools, and memory.
- Producers run in parallel, a director agent synthesizes.
- Can be triggered by the user ("ask the council: ...") or by the model via the `run_council` tool.

```kotlin
class AgentCouncil(
    private val subagentManager: SubagentManager,
    private val agentStore: AgentStore,
) {
    suspend fun run(
        agentIds: List<String>,
        task: String,
        context: String = "",
        budgetMs: Long = 120_000L,
    ): CouncilResult {
        val agents = agentIds.mapNotNull { agentStore.byId(it) }
        val producers = agents.filter { !it.isDefault }
        val director = agents.firstOrNull { it.isDefault } ?: agents.first()

        // Phase 1: producers run in parallel
        val proposals = subagentManager.spawnAll(
            producers.map { agent ->
                subagentManager.createTask(
                    SubagentSpec(
                        role = agent.name,
                        objective = task,
                        modelRole = "CONVERSATION",
                        toolAllowlist = agent.toolsAllowed.split(","),
                        budgetMs = budgetMs / agents.size,
                    ),
                    parentRunId = "council",
                )
            },
            executor = ::runAgentLoop,
        )

        // Phase 2: director synthesizes
        val directorResult = subagentManager.spawn(
            subagentManager.createTask(
                SubagentSpec(
                    role = director.name,
                    objective = "Synthesize the best elements from all proposals into a final answer. Task: $task",
                    contextText = proposals.joinToString("\n\n") { it.output },
                    budgetMs = budgetMs / 3,
                ),
                parentRunId = "council",
            ),
            executor = ::runAgentLoop,
        )

        return CouncilResult(directorResult.output, proposals)
    }
}
```

**Tasks:**

#### Task 13.1: AgentCouncil orchestrator

#### Task 13.2: RunCouncilTool

```kotlin
val tool = Tool(
    name = "run_council",
    description = "Run a multi-agent council on a complex question. Multiple specialist agents work in parallel and a director synthesizes their answers.",
    risk = ToolRisk.REMOTE_COST,
    parameters = ToolParameters(
        properties = mapOf(
            "task" to ToolProperty(type = "string", description = "The question or task for the council"),
            "agents" to ToolProperty(type = "string", description = "Comma-separated agent names to include (default: auto-select)"),
        ),
        required = listOf("task"),
    ),
    execute = { call, _ ->
        // 1. Parse agent names (or auto-select relevant agents)
        // 2. Run AgentCouncil
        // 3. Return director's output
    },
)
```

#### Task 13.3: Register tool + commit

```bash
git commit -m "feat(agents): generalized multi-agent council for any task

AgentCouncil extends CreativeCouncil to work for any question.
Producers run in parallel, director synthesizes. Triggered by the
run_council tool or by the user asking 'ask the council: ...'. Agents
use their own identity, tools, and memory scopes."
```

---

### Phase 14: Unify Specialist → Agent

**Objective:** Replace the Specialist system with AgentEntity. SpecialistRouter routes to AgentEntity instead of Specialist. ChatViewModel uses AgentEntity. All references to Specialist.ALL are replaced with AgentStore.all().

**Files:**
- Modify: `aura-core/src/main/kotlin/com/aura/agent/SpecialistRouter.kt` (route to AgentEntity)
- Modify: `app/src/main/kotlin/com/aura/ui/viewmodel/ChatViewModel.kt` (use AgentEntity instead of Specialist)
- Modify: `app/src/main/kotlin/com/aura/ui/screens/chat/ChatHeader.kt` (agent picker from AgentStore)
- Modify: `app/src/main/kotlin/com/aura/ui/screens/SettingsScreen.kt` (specialist overrides → agent overrides)
- Modify: `app/src/main/kotlin/com/aura/ui/settings/SettingsViewModel.kt` (agent CRUD instead of specialist overrides)
- Keep: `Specialist.kt` as a legacy adapter (maps Specialist → AgentEntity for backward compat)

**Approach:**

This is the migration phase — it wires everything together:
- `SpecialistRouter.pickSpecialist()` returns `AgentEntity?` instead of `Specialist?`.
- The router looks up agents from AgentStore by matching keywords to agent names/descriptions.
- ChatViewModel's `activeSpecialist: Specialist?` becomes `activeAgent: AgentEntity?`.
- The chat header's specialist chips show agents from AgentStore.
- Specialist prompt overrides in Settings become "edit agent" → opens AgentEditorScreen.
- `Specialist.ALL` is kept as a deprecated accessor that returns `AgentStore.builtins().map { it.toSpecialist() }`.

**Tasks:**

#### Task 14.1: SpecialistRouter routes to AgentEntity

#### Task 14.2: ChatViewModel uses AgentEntity

#### Task 14.3: Chat header agent picker from AgentStore

#### Task 14.4: Settings — replace specialist overrides with agent editor link

#### Task 14.5: Commit

```bash
git commit -m "refactor(agents): unify Specialist → AgentEntity

Specialist system is now backed by AgentEntity. SpecialistRouter
routes to AgentEntity. ChatViewModel uses AgentEntity. Specialist
prompt overrides in Settings replaced by AgentEditorScreen.
Specialist.kt kept as legacy adapter. All 7 builtins seeded from
existing Specialist definitions."
```

---

## Summary Table

| Phase | Workstream | Tasks | New Files | Modified Files | New Tests | Dependencies |
|-------|-----------|-------|-----------|----------------|-----------|--------------|
| 1 | Memory | 4 | 2 | 2 | 2 | None |
| 2 | Memory | 3 | 1 | 1 | 1 | None |
| 3 | Memory | 3 | 1 | 1 | 1 | None |
| 4 | Memory | 2 | 0 | 1 | 0 | Phase 2 |
| 5 | Multi-Agent | 5 | 5 | 2 | 1 | None |
| 6 | Multi-Agent | 5 | 0 | 6 | 0 | Phase 5 |
| 7 | Research | 3 | 0 | 1 | 1 | None |
| 8 | Research | 3 | 0 | 1 | 1 | Phase 7 |
| 9 | Research | 4 | 0 | 1 | 1 | Phase 8 |
| 10 | Multi-Agent | 3 | 1 | 2 | 1 | Phase 5 |
| 11 | Multi-Agent | 3 | 1 | 1 | 1 | Phase 5, 6 |
| 12 | Multi-Agent | 4 | 2 | 3 | 0 | Phase 5, 10 |
| 13 | Multi-Agent | 3 | 2 | 1 | 1 | Phase 5, 6, 11 |
| 14 | Multi-Agent | 5 | 0 | 5 | 0 | Phase 5, 6, 10, 11, 12, 13 |
| **Total** | | **52** | **16** | **28** | **12** | |

## Verification Gate (run after each phase)

```bash
./gradlew --no-daemon :aura-core:testDebugUnitTest :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
```

## Migration Safety

- All new Room databases start at v1 — no existing data at risk.
- ConversationDatabase v4→v5 migration adds nullable `agentId` column — existing conversations get null (General agent).
- AuraBackup SCHEMA_VERSION 8→9 — old backups restore without agents (empty list default).
- Specialist.kt kept as legacy adapter — no code referencing Specialist.ALL breaks.
- Memory scope filtering is additive — `setOf("general")` is the default, same as current behavior.

## Prior Plans Alignment

No prior plans exist in `.hermes/plans/` — all were deleted in the cleanup commit (7aa1c6f). This is the sole authoritative plan.

## What's Deliberately Deferred

- HNSW (true navigable small world graph) — IVF is sufficient for personal-scale (<10K memories). Upgrade to HNSW if memory count exceeds 10K.
- Agent-to-agent communication (agents messaging each other proactively) — delegation is synchronous. Async inter-agent messaging is a separate system.
- Agent marketplace/sharing — agents are local-only. No cloud sync, no sharing, no import/export of agent definitions (beyond backup/restore).
- Agent learning (personality adaptation based on user feedback) — personality is user-configured, not learned. Learning is a future evolution-system concern.
- Recursive delegation — delegated agents cannot delegate further. Prevents runaway agent chains.
- Agent-specific conversation compaction — compaction is shared. Per-agent compaction strategies are deferred.