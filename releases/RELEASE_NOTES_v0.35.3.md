# Aura Android v0.35.3

## What's New

### Wire real context window lookup for all 5 providers

**User correction:** "every provider has their context so remove the limits!!!"

The v0.35.2 release wired OllamaCloud (queries `/api/show`) but left the other 4 providers on the 32K default. This commit wires the rest.

**Live API (no hardcoding):**
- **OpenRouter**: `/api/v1/models` returns `context_length` per model. Queries live, parses the field. Real context for every model the user has access to.
- **Gemini**: `/v1beta/models` returns `inputTokenLimit` per model. Queries live. Real context from Google's published limits.
- **OllamaCloud**: was already wired in v0.35.2 (queries `/api/show` for `model_info.<arch>.context_length`).

**Hardcoded table (API doesn't expose context):**
- **Anthropic**: `/v1/models` returns `id` only, no context.
- **OpenAI**: `/v1/models` returns `id + owned_by`, no context.
- **Groq**: `/openai/v1/models` returns `id` only, no context.
- **ChatGPT** subscription: same model IDs as OpenAI.

For these 4, added `ProviderContextWindows.kt` — a snapshot of publicly documented context windows. When the API doesn't return it, the table is the only way to know. The table is intentionally a SNAPSHOT: new models return null and the compactor uses the 32K default. A wrong entry would cause the compactor to never fire on a 4K-context model — worse failure than the default.

**Tests:** 15 new tests in `ProviderContextWindowsTest` covering Anthropic (modern + legacy), OpenAI (gpt-4o, gpt-4 original 8K, gpt-4-32k, reasoning, gpt-3.5), ChatGPT (uses OpenAI table), Groq (llama 3.1/3.3 131K, llama 3 8K, mixtral 32K), and safety (unknown models return null, context values are bounded 4K-1M).

**Infrastructure:**
- `OpenAiCompatProvider`: `baseUrl`, `httpClient`, `providerKeys` changed from `private` to `protected` so subclasses (OpenRouter) can override `listModelsWithContext`.
- `OllamaCloudProvider`: removed shadowing private fields (uses base class's now-protected fields).

**For `CustomOpenAiCompatProvider` (user's own endpoint):** left as the base class default which uses the OpenAI table. If the user's endpoint has different context windows, they can override `listModelsWithContext` in their custom provider.

The 32K default is now a true last resort: only when the lookup returns null (unknown model on all 4 hardcoded providers, or API call failed for OllamaCloud/OpenRouter/Gemini).

## Stats
- 975 aura-core tests, 0 failures (was 960, +15)
- 1 atomic commit (7d1ab7dd)
- APK: 37 MB
- versionCode 39 → 40, versionName 0.35.2 → 0.35.3
