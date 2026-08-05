# Round 16 Remaining Weaknesses — 7-Item Remediation Plan

**Branch:** feat/tier-1-friction · **Base:** 08e88beb (v0.62.0)

| # | Item | Effort | Approach |
|---|------|--------|----------|
| 7 | OllamaCloud N+1 sequential /api/show | S | coroutineScope + async + awaitAll, capped concurrency (8) |
| 6 | MCP callTool drops List/Array/null args | S | JsonElement-based serialization in McpConnection.callTool |
| 2 | Index starvation (1 @Index / 54 entities) | M | MemoryDB v15→v16: CREATE INDEX decayScore + accessCount; check other hot DAO queries |
| 1 | 143 silent runCatching across 67 files | M | Block-aware script adds .onFailure { Log.w } — verify no double-wraps, run tests |
| 3 | 356 hardcoded dp + 162 hardcoded colors | M | Scripted token mapping (AuraSpacing + AuraTokens) — same ratchet as prior sessions |
| 5 | 0 @ForeignKey annotations | M | Annotate core entities to match migration-declared FKs; Room validates; version bump if needed |
| 4 | 24 untested screens + 15 untested VMs | L | VM state-machine tests for top untested VMs + screen contract source-scan tests |

**Execution order:** quick wins first (7, 6), then data (2), then mechanical (1, 3), then risky (5), then tests (4).
**Validation:** tsc-equivalent = compile + full unit suite after each phase. Commit per phase.
