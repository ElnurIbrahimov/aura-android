# Aura Android — Architecture

**Version:** 0.51.0 (versionCode 61)
**Branch:** feat/tier-1-friction

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
│   ├── src/main/kotlin/com/aura/agent/   # Agentic loop, tools, tool executor
│   ├── src/main/kotlin/com/aura/providers/ # LLM providers (17 providers)
│   ├── src/main/kotlin/com/aura/memory/  # Memory store, embeddings, BM25, reranker
│   ├── src/main/kotlin/com/aura/creative/ # Creative studio, world bible, pipelines
│   ├── src/main/kotlin/com/aura/evolution/ # Self-improvement system
│   ├── src/main/kotlin/com/aura/proactive/ # Proactive workers, awareness, daemon
│   ├── src/main/kotlin/com/aura/consciousness/ # NarrativeSelf, IntrinsicMotivation, TheoryOfMind, Affinity
│   ├── src/main/kotlin/com/aura/world/   # World model (events, opportunities)
│   ├── src/main/kotlin/com/aura/dream/   # Dream consolidation (memory clustering)
│   └── src/test/                          # Unit tests
```

## Key Subsystems

### Agentic Loop (`MemoryAugmentedAgenticLoop`)
- ReAct-style loop: plan → tool call → observe → repeat (max 15 steps)
- Planning step on long messages (>20 chars AND >3 words)
- Reflection after max_steps_exceeded
- Strategy Bandit (Thompson Sampling over 3 strategies × 7 categories)
- Tool execution: parallel via coroutineScope+async+awaitAll, withTimeout per tool
- Consciousness layer injected on step 1: NarrativeSelf, IntrinsicMotivation, TheoryOfMind, AffinityTracker
- Entity-aware compaction: KG entity snapshot prepended to summary

### Memory Pipeline
- BM25 with IDF (floored at 0.1 for small corpora)
- RRF 6-signal fusion (text + vector + recency + access + decay + importance)
- Cross-encoder reranker (batched 4/call, 10s timeout, min-5 guard)
- Recall caching per (userMessage, agentId)
- Query rewriting for deictic references
- Scoped SQL LIKE for UI browsing (searchByTextInScopes)

### Providers (17)
- Ollama Cloud, Anthropic, OpenAI, DeepSeek, Gemini, Groq, OpenRouter, NVIDIA, ChatGPT subscription, MoA (virtual)
- All via single `Provider` interface with `chat()` (streaming) and `listModels()`
- Provider failover with prefix + model ID dedup
- 401/400/403 non-retryable, 429 retryable with Retry-After

### Multi-Agent System
- AgentEntity in standalone Room DB (agents.db v1)
- 7 builtin agents seeded from Specialist.ALL
- Per-agent memory scopes (General=shared, others=private)
- 6-dimension personality profiles injected into system prompt
- delegate_to_agent tool (mini agentic loop, 3 steps, 30s timeout)
- run_council tool (generalized from CreativeCouncil)
- User-creatable agents via AgentEditorScreen with template picker

### Proactive System
- DaemonWorker (15-min interval): awareness checks, proactive outreach
- MorningBriefWorker: daily morning brief
- CalendarMonitor: upcoming calendar events
- DecayWorker: memory decay with Settings toggle
- TriggerWorker (15-min): scheduled triggers, opportunity engine
- DreamWorker: daily memory consolidation (9 phases)
- ProactiveAwarenessEngine: staleness (30d), goal-blocker (7d), relationship gap (3d)
- AgentPresence: emotional continuity, idle thoughts, proactive outreach

### Evolution System
- Detectors → Coordinator → reflectAndPromote → ProposalStore → InboxViewModel → approve → ApplySaga
- 20 EvolutionAction types, all with rollback
- Self-consistency + LLM-as-judge evaluators
- autoApplyApproved per domain (Settings toggle)

### Room Databases (11)
- MemoryDB v14, ConversationDB v5, AgentRunDB v1, EvolutionDB v3
- HandDB v1, TaskDB v2, ProactiveEventDB v2, UserProfileDB v2
- AgentDB v1, DreamConsolidationDB v1, CreativeProjectDB v1
- Backup SCHEMA_VERSION 15

### Tools (63)
- Web search (4: DDG, Brave, Tavily, capability-backed)
- Deep research (parallel fetch, multi-step gap detection, 20K context budget)
- Vision, image gen (2: DALL-E, capability-backed), code interpreter (JS sandbox)
- Calendar read/write, reminders, tasks, timer, weather, translate
- Knowledge graph, memory (recall, remember, index, canon query)
- Creative (engine, read project, add world item)
- Agent (delegate, run council)
- Evolution (trigger, approve, rollback)
- World model (query, beliefs, events, opportunities)
- MCP client (external tool servers, SSRF guard, auth)
- Device (camera, location, biometric, clipboard, notifications, contacts, DND, volume, battery, network state)
- Hands (run, schedule), skills (use), file I/O (HTTP read/write)

### Security
- SsrfGuard on all network tools (MCP, HTTP file, deep research, web search)
- OkHttpClient: redirects disabled (SSRF prevention)
- SecureDataStore: AES-256-GCM for credentials, SMTP passwords, MCP auth tokens
- BiometricPrompt: BIOMETRIC_STRONG for app lock and sensitive tools
- ToolExecutor: withTimeout per tool, REMOTE_COST approval gate
- PolicyEngine: risk-based defaults (READ_ONLY, WRITE_LOCAL, WRITE_REMOTE, REMOTE_COST, PRIVACY)
- WebView: JS enabled, DOM storage enabled, cookies disabled, file/content access disabled, mixed content blocked

## Build Configuration
- Kotlin 1.9.24, AGP 8.2.2, Compose BOM 2024.10.01
- Hilt 2.51, Room 2.6.1, minSdk 26, target/compileSdk 35
- 1,559 unit tests, 0 failures
- 63 registered tools, 17 LLM providers, 7 builtin agents