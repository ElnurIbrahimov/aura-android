# Aura Android v0.35.1

## What's New

### AGENTIC D4 — Remove artificial compactor threshold
- Killed `thresholdForModel` string-matching on model names ("4k" / "8k" / "4096" / "8192"). Modern models rarely embed context size in their name (Claude Sonnet 4 doesn't say "200k" anywhere).
- Replaced with `resolveThreshold(model, contextWindow)`: 80% of actual context window when known, 32K token default when unknown. 4K floor.
- Default raised 12K → 32K. Old 12K threshold fired compaction way too early on modern models — every ~3K chars meant a network round-trip to summarize, wasting tokens on contexts the model could have handled natively.
- 957 aura-core tests, 0 failures (was 952, +5 from new ConversationCompactorThresholdTest).

## Why
The old code path:
```kotlin
when {
    lower.contains("4k") || lower.contains("4096") -> 2_400
    lower.contains("8k") || lower.contains("8192") -> 4_800
    else -> MAX_UNCOMPACTED_TOKENS  // 12_000
}
```

The else branch is taken by every modern model. So:
- Claude Sonnet 4 (200K context) → compacts at 12K. Massive waste.
- Gemini 2.5 (1M context) → compacts at 12K. Massive waste.
- Llama 3.1 70B (128K context) → compacts at 12K. Massive waste.

New code path: ask the model catalog for the actual context window. When unknown, use 32K (covers most modern models without false positives). 80% of context leaves 20% headroom for response + system prompt.

## Stats
- 1 atomic commit (963d535a)
- 957 aura-core tests, 0 failures
- APK: 37 MB
- versionCode 37 → 38, versionName 0.35.0 → 0.35.1
