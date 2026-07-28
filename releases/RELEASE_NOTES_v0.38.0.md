# Aura Android v0.38.0 — Release Notes

## What's new since v0.36.1

### SOTA Agentic Loop Upgrades (v0.37.0)
- **Reflection Engine**: self-corrects after max_steps_exceeded with tool errors. Generates "what went wrong" note, injected into next run's system prompt. Cleared on success.
- **Strategy Bandit**: Thompson Sampling over 3 reasoning strategies × 7 problem categories. Beta-distributed reward, Room-backed. Selects optimal maxSteps per task type. Learns from outcomes.
- **LLM Profile Extraction**: regex first (name/location/job/prefs), cheap-model LLM fallback for patterns regex misses ("I use Vim", "allergic to peanuts").

### SOTA Subsystem Upgrades (v0.38.0)
- **Entity-aware compaction**: KG snapshot prepended to compaction summary so structured facts survive.
- **KG entity resolution**: Levenshtein dedup of nodes by label similarity before insertion.
- **Real evolution evaluators**: self-consistency + LLM-as-judge replace toy token-length scorer.
- **Taste prompt enhancer**: converts passive preferences ("prefers tone: concise") to active instructions ("Be concise.").
- **Hands word-boundary matching**: trigger phrases use Unicode word boundaries.

### Claude Code Review Fixes (v0.37.0)
- Composite PK on StrategyBanditEntity (was silently overwriting rows)
- Success recording in Done handler (bandit can now reinforce winners)
- Stale lastReflection cleared on success
- Robust JSON extraction with balanced-brace parser
- Word-boundary matching for short classification keywords

### Engineering Review Fixes (v0.38.0)
- Evolution logging: 16 silent runCatching sites now log root cause (10 in RollbackManager, 6 in ApplySaga)
- README synced with actual counts (v0.38.0, 62 tools, 1,423 tests, schema v14)
- Pipeline tests: circular dependency detection, topological ordering, image stage output type validation
- Navigation: agent_editor route uses proper default-null argument

## Download
See attached APK.
