# Aura Android — Architecture

**Version:** 0.65.0 (versionCode 80)
**Branch:** main (the `fix/a-grade-sweep` sweep is merged)

## Overview

Aura Android is a native Kotlin/Compose AI assistant app — a port of the Python Aura personal AI platform. It runs on-device with cloud LLM providers, with no server-side backend. All data is stored locally in Room databases and DataStore.

## Module Structure

```
aura-android-clean/
├── app/                    # Android application module
│   ├── src/main/kotlin/com/aura/ui/       # Compose UI (screens, components, theme)
│   ├── src/main/kotlin/com/aura/documents/ # Document text extraction
│   └── src/test/                          # Unit tests
├── aura-core/              # Core library module (no Android UI deps)
│   ├── src/main/kotlin/com/aura/agent/   # Agentic loop, tools, tool executor, gates
│   ├── src/main/kotlin/com/aura/providers/ # LLM providers (17 providers)
│   ├── src/main/kotlin/com/aura/memory/  # Memory store, embeddings, BM25, reranker
│   ├── src/main/kotlin/com/aura/creative/ # Creative studio, world bible, pipelines
│   ├── src/main/kotlin/com/aura/evolution/ # Self-improvement system (patch authoring)
│   ├── src/main/kotlin/com/aura/proactive/ # Proactive workers, awareness, daemon
│   ├── src/main/kotlin/com/aura/consciousness/ # NarrativeSelf, IntrinsicMotivation, DriveSignals, TheoryOfMind, Affinity
│   ├── src/main/kotlin/com/aura/world/   # World model (events, opportunities)
│   ├── src/main/kotlin/com/aura/dream/   # Dream consolidation (memory clustering)
│   └── src/test/                          # Unit tests
```

## Key Subsystems

### Agentic Loop (`MemoryAugmentedAgenticLoop`)
- ReAct-style loop: plan → tool call → observe → repeat (max 10 steps by default; the strategy bandit can adjust)
- Optional pre-answer planning pass (opt-in, default off — costs an extra model call)
- Reflection after max_steps_exceeded
- Strategy Bandit (Thompson Sampling over 3 strategies × 7 categories)
- Tool execution: parallel via coroutineScope+async+awaitAll, withTimeout per tool
- Tool-call history is fully serialized back to every provider (assistant `tool_calls` echo + per-provider tool-result formats), so multi-turn tool conversations survive round-trips
- **Typed gate pause/resume**: permission, confirmation, and remote-cost approval all pause the loop as a `PendingGate`, emit a typed `AgentEvent.GateRequested`, and resume via `resumeAfterGate` (deny writes a synthetic result — no dangling tool calls)
- Consciousness layer injected on step 1: NarrativeSelf, IntrinsicMotivation, TheoryOfMind, AffinityTracker
- Entity-aware compaction: KG entity snapshot prepended to summary

### Memory Pipeline
- BM25 with IDF (floored at 0.1 for small corpora)
- RRF 6-signal fusion (text + vector + recency + access + decay + importance)
- Word prefilter pads unused LIKE slots with a no-match sentinel (never `%%`), filters shared `StopWords`, and sizes the candidate pool so the reranker actually gets its `RERANK_POOL_SIZE`
- Cross-encoder reranker (batched 4/call, 10s timeout, min-5 guard)
- Recall caching per (userMessage, agentId)
- Query rewriting for deictic references
- Dedup on store via `maybeStore` (bounded semantic-dedup scan)
- Scoped SQL LIKE for UI browsing (searchByTextInScopes)

### Providers (17)
- Ollama Cloud, Anthropic, OpenAI, DeepSeek, Gemini, Groq, OpenRouter, Mistral, xAI, Together, Cerebras, NVIDIA, Meta Llama, Agnes, ChatGPT subscription, Custom endpoint, MoA (virtual)
- All via single `Provider` interface with `chat()` (streaming) and `listModels()`
- Per-call stream handles with identity guards — two concurrent streams on one provider instance never clobber each other
- Explicit sampling respected: `ChatOptions.temperature/topP` are nullable; `EmotionEngine.applySampling` fills only nulls
- 400/401/403/404/422 non-retryable; 429 honors `Retry-After`; failover picks a same-class model via `CheapModelHeuristic`
- Provider failover with prefix + model ID dedup

### Multi-Agent System
- AgentEntity in standalone Room DB (agents.db v3)
- 7 builtin agents seeded from Specialist.ALL
- Per-agent memory scopes (General=shared, others=private)
- 6-dimension personality profiles injected into system prompt
- delegate_to_agent tool (mini agentic loop, 3 steps, 30s timeout)
- run_council + run_life_council tools
- User-creatable agents via AgentEditorScreen with template picker

### Consciousness Layer
- `DriveSignals` (@Singleton, 5-min TTL cache) feeds the drives with real DB counts: CURIOSITY ← KG gap nodes, COHERENCE ← unresolved dream contradictions, COMPETENCE ← low-confidence bandit arms
- CURIOSITY satisfies only on genuine research/search tools; SOCIAL satisfies per completed turn
- `NarrativeSelf` updates from dream-cycle summaries (growth) and unresolved contradictions (concerns) — no per-turn heuristics
- `AffinityTracker` uses min-threshold level semantics

### Proactive System
- DaemonWorker — configurable interval (default 60 min, 15-min WorkManager floor), network-connected + battery-not-low constraints; council debates default off
- MorningBriefWorker: daily morning brief
- CalendarCheckWorker (15-min): Calendar Instances API with 30-min lookahead, persisted per-instance dedup, delivery via `ProactiveEvents.record` (replaced the permanent CalendarMonitorService FGS)
- DecayWorker: memory decay with Settings toggle
- TriggerWorker (15-min): scheduled triggers, opportunity engine
- DreamWorker: daily memory consolidation (9 phases)
- ProactiveAwarenessEngine: staleness (30d), goal-blocker (7d), relationship gap (3d)
- AgentPresence: proactive outreach message generation

### Evolution System
- Pipeline: detectors → **EvolutionPatchAuthor** (one LLM call returns `{decision, reason, patch}`) → **EvolutionPatchValidator** (schema + safety checks; invalid → REJECTED) → ProposalStore → InboxViewModel → approve → ApplySaga → **EvolutionOutcomeScorer** (deterministic, evidence-based, ≥7d post-apply)
- 4 EvolutionAction types: PATCH_SKILL, RETIRE_SKILL, PROMOTE_TO_HAND, CONSOLIDATE_MEMORIES — each with typed, complete rollback snapshots
- Candidate dedup on (domain, action, targetId) with a 14-day cooldown
- Safety guard enforced twice: coordinator auto-apply requires `canAutoApply(domain)` (SKILL never auto-applies) and Settings persists auto-apply only for guard-passing domains

### Screen Capture
- `ScreenCaptureService`: MediaProjection foreground service (type `mediaProjection`), async first frame via ImageReader on a dedicated HandlerThread, row-stride-corrected bitmap, watchdog teardown
- `ScreenCaptureHolder`: per-capture `CompletableDeferred`s; consent requested fresh for every capture (single-use consent Intents on API 34+)

### Room Databases (11)
- MemoryDB v16, ConversationDB v6, ProactiveEventDB v5, TaskDB v5, EvolutionDB v4
- DreamConsolidationDB v3, AgentDB v3, HandDB v2, UserProfileDB v2
- AgentRunDB v1, StrategyBanditDB v1
- Backup SCHEMA_VERSION 16 (restore is snapshot-rollback + non-cancellable insert phase)

### Tools (76)
- Web search (7: DDG HTML, DDG instant answer, Brave, Tavily, SearXNG, Wikipedia search/read, plus capability-backed)
- Research: deep_research (parallel fetch, gap detection), parallel_research
- Vision, image gen (2), code interpreter (JS sandbox), transcribe, translate
- URL readers: fetch_url (Firecrawl), read_url (Jina), http_file_read/write
- Calendar read/write, reminders, tasks, schedule_task, timer, weather
- Knowledge graph (extract, query), memory (recall, remember, index, canon query), taste, world model
- Creative (engine, read project, add world item)
- Agents (delegate, run_council, run_life_council)
- Evolution (trigger, approve, rollback)
- Device (capture, location, biometric, clipboard, notifications, contacts, DND, volume, battery, network state, photos, share, launch, browser, TTS)
- Comms (email x2, SMS), integrations (Gmail, Google Calendar/Drive, Outlook Mail/Calendar, OneDrive)
- Hands (run), skills (use) — MCP tools register dynamically on top

### Security
- SsrfGuard on all network tools (MCP, HTTP file, deep research, web search); `read_url` pins to the proxy host it actually fetches
- OkHttpClient: redirects disabled (SSRF prevention)
- SecureDataStore: AES-256-GCM for credentials, SMTP passwords, MCP auth tokens
- BiometricPrompt: BIOMETRIC_STRONG for app lock and sensitive tools
- ToolExecutor: withTimeout per tool, bounded tool parallelism (8), typed confirmation/approval gates via PolicyEngine
- PolicyEngine: risk-based defaults (READ_ONLY, WRITE_LOCAL, WRITE_REMOTE, REMOTE_COST, PRIVACY) + per-tool user policy with confirmation grants
- Screen capture: per-capture consent, visible FGS notification during capture
- WebView: JS enabled, DOM storage enabled, file/content access disabled, mixed content blocked,
  `javaScriptCanOpenWindowsAutomatically` off, destroyed on dispose. Cookies are left at the
  platform default (first-party accepted, third-party rejected) — the in-app browser is a
  general browsing surface and login-gated pages need them. `CookieManager` is not configured;
  this line previously claimed "cookies disabled", which was never implemented.

## Build Configuration
- Kotlin 2.4.10 (K2), Gradle 9.7, AGP 9.3.1, Compose BOM 2026.06.01
- Hilt 2.60.1, Room 2.8.4, WorkManager 2.11.2
- minSdk 26, targetSdk 35, compileSdk 37
- Release: R8 minification + resource shrinking, upload-keystore signing via `local.properties`
- 2,467 unit tests, 0 failures (gated by `scripts/check-test-count.sh`)
- 76 registered tools, 17 provider configurations (6 provider classes — 11 of the 17 are
  `OllamaCloudProvider` with a different base URL), 7 builtin agents

## Prompt assembly

The system message is composed per step in `MemoryAugmentedAgenticLoop`. Order matters:

1. Agent identity, specialist prompt, personality directive, conversation system prompt,
   resolved identity, user profile — all trusted, all authored by Aura or the user.
2. `# Retrieved context` — recalled memories, world-model beliefs, taste profile, recent topics.
   Wrapped in `PromptFraming.UNTRUSTED_CONTEXT_PREAMBLE`, because this content is
   attacker-reachable in one hop: the model reads a page with `read_url`, judges a line
   memorable, calls `remember`, and the line returns inside the system message on a later turn.
3. Mood, triggered hands, prior-attempt reflection, consciousness blocks — computed by Aura from
   the user's own input, so deliberately *not* framed as untrusted.

## Memory retrieval

`MemoryStore.query` fuses six unweighted signals through RRF (`Retrieval.rankCandidates`,
`k = 60`): BM25 text score, vector cosine, recency, access frequency, FadeMem decay, importance.

- **Candidates** come from `memories_fts` (FTS4, MemoryDatabase v17), kept current by SQL
  triggers. Replaced six `content LIKE '%word%'` clauses, which capped the query at six terms
  and forced a full table scan.
- **BM25** takes its corpus size and per-term document frequency from the index rather than from
  the candidate list, so IDF discriminates instead of collapsing to its floor.
- **Stopword-only queries** fall back to a substring LIKE — `MATCH ''` is a SQLite syntax error,
  not an empty result.
- **No lexical overlap** falls back to a bounded vector scan (2,000 most-active scoped rows).
- **Reranking** (cross-encoder, cheap model) runs over the top 20 when ≥5 candidates survive.

## Consciousness layer

Five components, all persisted so they survive the process death Android imposes between
sessions: `NarrativeSelf` and `IntrinsicMotivation` and `TheoryOfMind` (JSON in `filesDir`),
`EmotionEngine` and `AffinityTracker` (DataStore). `DriveSignals` is derived, not stored.

None of them are in the backup schema — see ENGINEERING_HISTORY §3.
