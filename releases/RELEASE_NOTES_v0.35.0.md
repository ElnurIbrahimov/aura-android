# Aura Android v0.35.0

## What's New

### P1 / P2 hardening pass
- **MEMORY C2** — LocalEmbedder in-process LRU cache (1024 entries, thread-safe). Pre-fix, every `embed("text")` re-tokenized and re-hashed. Now cached.
- **AGENTIC B2** — TimerTool bounded by MAX_TIMERS=100 with FIFO eviction. Pre-fix, leaked an entry per unstopped timer.
- **AGENTIC A2-A5** — DelegateToAgentTool child context (memoryEnabled, approvedRemoteCostTools, userMessage, 30s timeout), MCP allowlist consistency, Conversation.addToolCall on empty now throws instead of creating malformed Turn.
- **AGENTIC C1-C2** — UseSkillTool risk corrected (WRITE→READ, docstring matched), SmsSendTool phone validation (7-15 digits).
- **AGENTIC D1** — RunHandTool: when enqueue returns null (hand disabled / conditions failed), return error instead of silently falling through to `repository.run()` direct execution.
- **MEMORY A4, A5** — MemoryStore.maybeStore wraps in exactInsertMutex, applyConsolidateMemories now preserves source memories' scopes (no cross-agent leak on consolidation).
- **MEMORY B1, B3, B4** — Vector-fallback path now fires evolutionHooks.onMemoryRecalled, CloudEmbedder logs all failures (was silent), MemoryReranker parser is now index-aware (handles out-of-order "Memory 4: 0.9" responses and missing lines with neutral default).

### Regression tests added
- LocalEmbedderCacheTest (3 tests) — cache hits return same instance, different text returns different entries, L2 normalization preserved
- MemoryRerankerTest 2 new tests — out-of-order explicit indices, mismatched line count
- ConversationAddToolCallTest (3 tests) — A5 malformed Turn contract
- TimerToolTest 1 new test — eviction past MAX_TIMERS
- ProviderKeysTest 1 new test — setEmbeddingModel blank removes

### Verified as false positives (no fix needed)
- PROVIDERS B2 (API key in error) — ProviderCatalogException already documents "raw response body never included"
- PROVIDERS B3 (billableChunkSeen) — already includes chunk.toolCall
- PROVIDERS C2 (MCP body size cap) — MAX_META_RESPONSE_BYTES=2_000_000 already enforced
- PROVIDERS F1 (removeString) — already correct, regression test pinned
- AGENTIC E1 (McpToolBridge stale) — already cleaned up in syncTools()
- AGENTIC C3 (HttpFileReadTool original URL) — pinnedClient with Dns override is the right pattern
- UI/UX A2 (IdentityEditor reachability) — composable("identity_editor") already registered
- UI/UX A3 (ProductionPipeline from Home) — Production card already in HomeSecondaryActions
- AGENTIC F1 (agent ID mismatch) — ChatViewModel.setSpecialist correctly prefixes "agent_"
- AGENTIC E3 (McpConnection _health @Volatile) — already fixed in 7cea0bf2

## Stats
- 7 atomic commits on `feat/tier-1-friction`
- 952 aura-core tests, 0 failures (was 944 at start, +8)
- APK: 37 MB
- versionCode 36 → 37, versionName 0.34.0 → 0.35.0

## Known limitations
- UI/UX C1 (draft persistence across process death) — requires SavedStateHandle, deferred
- UI/UX B1 (ChatViewModel 1059-line god class) — defer to controller-extraction session
- AGENTIC D3 (ProductionPipelineEngine parallelization) — defer to DAG refactor
- AGENTIC B3 (TtsSpeakTool MediaPlayer timeout) — defer to TTS session
