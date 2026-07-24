# Aura Android v0.35.1

## What's New

### AGENTIC D4 — Remove artificial compactor threshold
- Killed `thresholdForModel` string-matching on model names ("4k" / "8k" / "4096" / "8192"). Modern models rarely embed context size in their name.
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

The `else` branch (12K) was taken by every model that didn't have "4k" or "8k" in its name. That means the compactor fired on modern models way too early — burning an LLM round-trip to summarize context the model could have handled natively. 32K default stops that.

## Stats
- 2 atomic commits (963d535a + 345010b9 docs cleanup)
- 957 aura-core tests, 0 failures
- APK: 37 MB
- versionCode 37 → 38, versionName 0.35.0 → 0.35.1
