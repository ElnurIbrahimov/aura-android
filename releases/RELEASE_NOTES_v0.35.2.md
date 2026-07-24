# Aura Android v0.35.2

## What's New

### Compactor queries the actual context window from the provider catalog

**User correction:** "why 200K when models have 1M context window?"

The 32K default was still a guess. The compactor needs the **real** context window from the provider, not a fallback.

**What changed:**

- **Provider interface gets `listModelsWithContext(): List<ModelInfo>`.** `ModelInfo` is a name + nullable contextWindow pair. Default implementation returns `listModels()` with null context (providers that don't know still work, falling back to the 32K default).

- **`OllamaCloudProvider` queries `/api/show` per model.** Parses `model_info.<arch>.context_length` out of the Ollama response — the real source of truth for Ollama Cloud models. A 1M-context model gets a 1M context window back. A 200K-context model gets 200K.

- **`ConversationCompactor.lookupContextWindow(model)`** uses `providerRegistry.parse` to find the provider and calls `listModelsWithContext`, returning the matching model's contextWindow (or null if the model is unknown or the catalog fetch failed).

- **`compactIfNeeded` passes that context window to `resolveThreshold`**, which uses 80% of the real number — leaving 20% headroom for the response + system prompt.

**Result:** a 1M-context Ollama model now gets an 800K compaction trigger. A 200K-token chat doesn't compact mid-conversation. A 8K-context model still compacts at 6.4K tokens (80%). An unknown model still uses 32K.

**Other providers (Anthropic, OpenAI, Gemini, ChatGPT, CustomOpenAiCompat) still use the default `listModelsWithContext()` which returns null context windows — they fall back to 32K.** Wiring their real /v1/models or /models endpoints is a follow-up.

## Tests
- 960 aura-core tests, 0 failures (was 957, +3 from new ConversationCompactorContextLookupTest)
- 1M-context model: 200K tokens does NOT compact
- Unknown context window: falls back to 32K default
- 8K-context model: 15K tokens DOES compact (proves the 80% rule is actually applied per-model)

## Stats
- 1 atomic commit (1a827503)
- APK: 37 MB
- versionCode 38 → 39, versionName 0.35.1 → 0.35.2
