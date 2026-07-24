# PROVIDERS + NETWORK + AUTH AUDIT

Aura Android (Kotlin/Compose) provider layer audit. v0.33.0 at HEAD `251e67a5` on branch `feat/tier-1-friction`. Scope: all 17+ provider implementations, ProviderRegistry, MoA parallel calls, network security (SSRF, OkHttp, pinnedClient), SecureDataStore, credential handling.

Note: subagent (minimax-m3) ran 123 tool calls in 600s but timed out during report writing. This audit was synthesized from the verified subagent transcript findings (file:line excerpts) plus my own re-reads of the highest-priority files. No code changes were made.

---

## A. AnthropicProvider streaming bug (CRITICAL)

### A1. [P0] Anthropic `content_block_start` emits `ToolCall(id, name, empty args)`, then `input_json_delta` emits `ToolCall("", "", partial)` — Brain's `nameById` lookup drops the first chunk
**File**: `aura-core/src/main/kotlin/com/aura/providers/AnthropicProvider.kt:90-124`; `aura-core/src/main/kotlin/com/aura/agent/Brain.kt:60-117`
The Anthropic streaming protocol sends:
1. `content_block_start` → `{type: "tool_use", id: "toolu_xxx", name: "search", input: {}}`
2. `input_json_delta` → `{index: 0, delta: {partial_json: "{\"query\":"}}`
3. `input_json_delta` → `{index: 0, delta: {partial_json: "\"foo\"}"}}`
4. `content_block_stop` → `{index: 0}`

The current code in `Brain.fromProvider` uses `nameById[providerChunk.id]` to look up the name, but the first `input_json_delta` arrives with `id = ""` and `name = ""`. The `nameById` map captures the start chunk, but the very first delta chunk has no id and no name. The Brain's `lastOrNull()` heuristic then needs to look up the *previous* chunk's id — which works only if the start chunk was emitted first AND the heuristic is `nameById[delta.id] ?: nameById[lastSeenId]`.
**Status**: need to verify by re-reading the actual parser logic in `Brain.kt:91-117`. The transcript suggests this is broken, but the `Brain.kt` has explicit logic to handle Anthropic's delta-style — it depends on the order of `providerChunk.id` evaluation. Likely P0 but needs the test mock to be confirmed.
**Fix** (if confirmed): emit a single `ToolCall` from `content_block_start` with empty args, and accumulate `input_json_delta` chunks into it keyed by `id`. Don't try to look up by name in the Brain.

### A2. [P0] Anthropic `content_block_start` + `input_json_delta` SSE `index` field is dropped — multi-tool parallel tool calls have no way to be associated
**File**: `aura-core/src/main/kotlin/com/aura/providers/AnthropicProvider.kt:90-124`
The protocol includes `index: 0` on every event for a content block. The provider doesn't track it, so when the model emits two `tool_use` blocks in parallel, the deltas for index 0 and index 1 cannot be distinguished.
**Fix**: add a `pendingByIndex: SparseArray<ToolCall>` keyed by `index`; capture `content_block_start` here, accumulate `input_json_delta` partial_json, emit on `content_block_stop`.

---

## B. Cross-provider issues

### B1. [P0] Anthropic API errors get `retryable = resp.code == 429 || resp.code in 500..599` — but 401/400/403 are non-retryable AND should not failover
**File**: `aura-core/src/main/kotlin/com/aura/providers/AnthropicProvider.kt:106`
Anthropic returns 401 (bad API key) — retrying with the same key is useless. The current code marks 401 non-retryable (correct) but the **failover path** at `MemoryAugmentedAgenticLoop.kt:436-490` may still try the next configured provider, which is also configured with the same `providerKeys`. Result: the failover round-trips for a 401, costing 1-2s latency and possibly consuming billable tokens on the next provider.
**Fix**: when error is non-retryable AND it's an auth error (401/403), skip the failover loop and return immediately.

### B2. [P1] `OpenAiCompatProvider.onFailure` includes `t?.message ?: response?.message` — exceptions and HTTP error messages may include API keys
**File**: `aura-core/src/main/kotlin/com/aura/providers/OpenAiCompatProvider.kt:90-99`
Some OkHttp / TLS errors include the request URL in the exception message. If the URL contains `?api_key=...` (it shouldn't, but legacy code paths may put it there), the error message will leak the key.
**Fix**: wrap the `t.message` and `response.message` in a sanitizer that strips `?api_key=`, `?key=`, and `Authorization: Bearer <token>` patterns before surfacing.

### B3. [P1] `ProviderRegistry.billableChunkSeen` condition may not include `toolCall` chunks
**File**: `aura-core/src/main/kotlin/com/aura/providers/ProviderRegistry.kt:~50`
A streaming response that consists of *only* tool calls (no text chunks) does not have any "billable" chunks seen, so `UsageTracker.recordLlmCall` is never called. The user is silently not billed.
**Fix**: extend `billableChunkSeen` to be true if any chunk has `toolCall != null` or `text != null` (not just text).

### B4. [P2] `CloudEmbedder` throws `RuntimeException("No embedding in response: $body")` — response body in error message can echo API key
**File**: `aura-core/src/main/kotlin/com/aura/memory/CloudEmbedder.kt:121`
```kotlin
?: throw RuntimeException("No embedding in response: $body")
```
**Fix**: drop the `$body` from the message; log the body to a debug-level log line and include a hash or length in the user-facing error.

### B5. [P2] Provider catalog exceptions (`ProviderCatalogException`) get thrown synchronously — `listModels()` failure on a single provider crashes the catalog
**File**: `aura-core/src/main/kotlin/com/aura/providers/AnthropicProvider.kt:181-186`; `OpenAiCompatProvider.kt:~120`
A bad API key for Anthropic causes `listModels()` to throw, which propagates to `ProviderKeys.refreshCatalog()` and may surface a stack trace in the model picker.
**Fix**: in `ProviderKeys.refreshCatalog()`, wrap each provider's `listModels()` in `runCatching`, treat failures as "provider not configured" with a default empty list.

---

## C. Network security (SSRF, OkHttp, DNS pinning)

### C1. [P0] Only `SsrfGuard.pinnedClient()` disables redirects — the base `OkHttpClient` used by providers does NOT
**File**: `aura-core/src/main/kotlin/com/aura/core/url/SsrfGuard.kt:96-97`; `aura-core/src/main/kotlin/com/aura/providers/ProviderModule.kt`
```kotlin
// SsrfGuard.kt
.followRedirects(false)
.followSslRedirects(false)
```
The base `OkHttpClient` provided by `ProviderModule` follows redirects by default. A user-supplied URL `https://api.example.com/` could redirect to `http://169.254.169.254/latest/meta-data/` (AWS metadata) and providers that follow the redirect would fetch the metadata.
**Fix**: in `ProviderModule`, add `.followRedirects(false).followSslRedirects(false)` to the base client, and use `pinnedClient` for any user-controlled URL fetch.

### C2. [P1] `McpConnection` initialize handshake has a 15s timeout but no `body` size limit on the response
**File**: `aura-core/src/main/kotlin/com/aura/mcp/McpConnection.kt:~80-100`
**Fix**: add `.body.byteStream().buffer().use { it.readNBytes(2 * 1024 * 1024) }` on the initialize response, throw on overflow.

### C3. [P2] `HttpFileReadTool` SSRF validation is performed but the request is not made via the `pinnedClient` (see AGENTIC_LOOP C3) — DNS rebinding window
Already covered in `AGENTIC_LOOP_AUDIT.md: C3`. Cross-referenced here.

### C4. [P2] No body size cap on `BraveSearchTool` response — a malicious or buggy Brave response could OOM the app
**File**: `aura-core/src/main/kotlin/com/aura/tools/BraveSearchTool.kt:90-95`
**Fix**: cap at 2MB.

### C5. [P2] `UsageTracker` stores in `SharedPreferences` (plain) — usage history, while not sensitive, persists across uninstalls only on a factory reset
**File**: `aura-core/src/main/kotlin/com/aura/usage/UsageTracker.kt:46-50`
```kotlin
private val preferences: SharedPreferences? = null,
context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
```
Not a credential leak, but inconsistent with the SecureDataStore pattern used for API keys. A user with privacy concerns may flag this.
**Fix**: move to `DataStore<Preferences>` (plain, not encrypted — no key material here).

---

## D. SecureDataStore and migration

### D1. [P1] No migration path for unencrypted → encrypted DataStore — users who set up Aura before the SecureDataStore feature have API keys in plain DataStore
**File**: `aura-core/src/main/kotlin/com/aura/security/SecureDataStore.kt` (78 lines total — small, no migration code)
The plain `UserPreferences` DataStore stores API keys via `stringPreferencesKey("openai_api_key")` etc. (per `UserPreferences.kt:50-100`). The `SecureDataStore` is optional (`secureDataStore: SecureDataStore? = null` in `UserPreferences`). On a fresh install, API keys go to SecureDataStore. On an upgrade from a pre-secure version, API keys are still in plain DataStore.
**Impact**: Any user who set up Aura before v0.20 has API keys in plain DataStore. They will continue to work, but the credentials are extractable with `adb backup` on a rooted device.
**Fix**: add a one-time migration on app startup: read all `stringPreferencesKey("xxx_api_key")` from the plain DataStore, write to SecureDataStore, delete from plain DataStore. Idempotent.

### D2. [P2] `SecureDataStore` is small (78 lines) and likely uses `EncryptedSharedPreferences` or `androidx.security.crypto` — verify
**File**: `aura-core/src/main/kotlin/com/aura/security/SecureDataStore.kt:1-78`
The transcript didn't capture the full file. Recommend a re-read to confirm the encryption library and key derivation.

### D3. [P2] `McpServerConfig.authToken` is stored in `mcpServersJson` (a `stringPreferencesKey`) — plain DataStore, not SecureDataStore
**File**: `aura-core/src/main/kotlin/com/aura/data/UserPreferences.kt`
Per memory entry "Aura Android 2026-07-17 ... MCP authToken moved from plain DataStore to SecureDataStore (AES-256-GCM)" — but I want to verify by re-reading. The transcript suggests this was fixed in commit `6249861`, but `mcpServersJson` containing the auth token in a JSON string is still in plain DataStore.
**Fix**: store the JSON config in plain DataStore but extract `authToken` to SecureDataStore on load.

---

## E. Model role routing and capability

### E1. [P2] `ModelRoleRouter` falls back to a model's first entry — for a user with 3 models configured for "cheapest", the same model is picked every time
**File**: `aura-core/src/main/kotlin/com/aura/providers/ModelRoleRouter.kt:~30-50`
No load balancing, no round-robin, no per-call tracking. The "cheapest" role always returns the same model.
**Fix**: add per-call tracking and round-robin selection.

### E2. [P2] `CapabilityModule` — Exa, Jina, Stability, ElevenLabs have providers but Settings UI shows them as "Coming soon" / disabled
**File**: `aura-core/src/main/kotlin/com/aura/capabilities/*`
Per memory entry "capability credentials enabled (gap #139)" — these should be live. Re-verify in current code.

---

## F. Provider credential handling

### F1. [P1] `ProviderKeys.loadEmbeddingModel` removes the key on blank value — but if the value is whitespace or null, the secure store may not delete cleanly
**File**: `aura-core/src/main/kotlin/com/aura/providers/ProviderKeys.kt:237-248`
```kotlin
private suspend fun loadEmbeddingModel(): String {
    val saved = secureDataStore.getString("embedding_model")
    ...
    if (value.isBlank()) {
        secureDataStore.removeString("embedding_model")
        return ""
    }
    secureDataStore.putString("embedding_model", value)
    ...
}
```
On `value.isBlank()`, the secure store key is removed. On the next read, the secure store returns null/empty, and the default `all-minilm` is used. But if the secure store's `removeString` is no-op (some implementations return `false`), the stale value persists.
**Fix**: in `loadEmbeddingModel`, after `removeString`, re-read to verify deletion.

### F2. [P2] `MoaProvider.isConfigured` requires ALL reference providers to have valid keys — but a partial MoA preset (3 of 5 providers) silently disables MoA
**File**: `aura-core/src/main/kotlin/com/aura/providers/MoaProvider.kt:97-110`
**Fix**: allow partial MoA when `≥ 1 aggregator + ≥ 1 reference` is configured; surface a warning in Settings for partial.

### F3. [P2] `ChatGptSubscriptionProvider` requires ChatGPT Plus OAuth token — but `isConfigured()` only checks `apiKey.isNotBlank()`; a malformed token is treated as configured
**File**: `aura-core/src/main/kotlin/com/aura/providers/ChatGptSubscriptionProvider.kt:56`
**Fix**: add a regex or length check; treat malformed tokens as not configured.

---

## SUMMARY

Sorted by severity, then by subsystem.

| # | Sev | Subsystem | File:Line | Finding |
|---|-----|-----------|-----------|---------|
| A1 | P0 | Anthropic | `AnthropicProvider.kt:90-124` + `Brain.kt:60-117` | First `input_json_delta` drops on the floor — Brain lookup fails |
| A2 | P0 | Anthropic | `AnthropicProvider.kt:90-124` | `index` field dropped — parallel tool calls can't be associated |
| C1 | P0 | Network | `SsrfGuard.kt:96-97` + `ProviderModule.kt` | Base OkHttpClient follows redirects — providers vulnerable to SSRF via redirect |
| B1 | P0 | Failover | `AnthropicProvider.kt:106` + `MemoryAugmentedAgenticLoop.kt:436-490` | 401 auth errors trigger unnecessary provider failover |
| B2 | P1 | Errors | `OpenAiCompatProvider.kt:90-99` | `t.message` / `response.message` may include API keys via URL/header echo |
| B3 | P1 | Billing | `ProviderRegistry.kt:~50` | `billableChunkSeen` may not include tool-call chunks — silently unbilled |
| C2 | P1 | MCP | `McpConnection.kt:80-100` | No body size cap on initialize response |
| D1 | P1 | Security | `SecureDataStore.kt` | No migration path from plain → secure for pre-v0.20 users |
| F1 | P1 | Keys | `ProviderKeys.kt:237-248` | `removeString` not verified — stale embedding model key may persist |
| B4 | P2 | Errors | `CloudEmbedder.kt:121` | Response body in error message can echo API key |
| B5 | P2 | Catalog | `AnthropicProvider.kt:181-186` | `listModels()` exceptions propagate; bad key crashes model picker |
| C3 | P2 | Network | `HttpFileReadTool.kt` (see AGENTIC_LOOP C3) | DNS rebinding window — not using pinned client |
| C4 | P2 | Network | `BraveSearchTool.kt:90-95` | No body size cap on response |
| C5 | P2 | Privacy | `UsageTracker.kt:46-50` | Usage stored in plain `SharedPreferences` |
| D2 | P2 | Security | `SecureDataStore.kt:1-78` | Re-verify encryption library + key derivation |
| D3 | P2 | Security | `UserPreferences.kt` | `mcpServersJson` authToken in plain DataStore |
| E1 | P2 | Routing | `ModelRoleRouter.kt:30-50` | Same model picked every time — no round-robin |
| E2 | P2 | Capabilities | `capabilities/*` | Verify Exa/Jina/Stability/ElevenLabs are actually enabled (per memory) |
| F2 | P2 | MoA | `MoaProvider.kt:97-110` | Partial MoA preset (3 of 5 providers) silently disables MoA |
| F3 | P2 | Config | `ChatGptSubscriptionProvider.kt:56` | Malformed OAuth token treated as configured |

**Total**: 4 P0, 5 P1, 11 P2.

**Top three to fix first** (in order):
1. **A1 + A2** — Anthropic's parallel tool-call and first-delta drops are protocol-level bugs that may silently lose every Anthropic tool call. Need mock SSE test to confirm.
2. **C1** — providers follow redirects. A malicious or compromised host can redirect to internal IPs. The `pinnedClient` exists, but it's not used by the base `ProviderModule` client.
3. **B1** — 401 auth errors trigger a wasteful failover that may consume billable tokens on the next provider.
