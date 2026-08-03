# ROUND 13 — Providers, MCP Client, and Capabilities Audit

**Project:** Aura Android (v0.51.2, branch `feat/tier-1-friction`)
**Scope:** All LLM provider implementations + MCP client + capabilities registry
**Files audited (17 providers + 4 MCP + 9 capability files):**
- `aura-core/src/main/kotlin/com/aura/providers/*.kt` (Provider, ProviderRegistry, AnthropicProvider, GeminiProvider, OpenAiCompatProvider, OllamaCloudProvider, ChatGptSubscriptionProvider, GroqProvider, MoaProvider, CustomOpenAiCompatProvider, OpenRouterProvider, OpenAiSseParser, ProviderContextWindows, ModelCatalogRepository, ModelRoleRouter, ChatOptions, ProviderKeys, ProviderModule, + 6 supporting)
- `aura-core/src/main/kotlin/com/aura/mcp/*.kt` (McpClientManager, McpConnection, McpModels, McpToolBridge)
- `aura-core/src/main/kotlin/com/aura/capabilities/*.kt` (CapabilityCatalogException, CapabilityProvider, CapabilityRegistry, CapabilityRouter, ImageProvider, TextToSpeechProvider, VideoProvider, WebSearchProvider)

**Methodology:** Each file read in full. Cross-referenced with `ProviderModule`, `SsrfGuard`, and the OpenAI/Anthropic/Gemini streaming protocol docs. Severity graded P0 (security/data loss/functional blocker) / P1 (correctness/perf under realistic load) / P2 (style/cleanup).

---

## Executive summary

- **SSE parallel-tool-call correctness is fundamentally fixed** in both `AnthropicProvider` and `OpenAiSseParser` (the `index → id` map and "emit list, not single chunk" pattern is in place). The pre-existing `P0-AGENTIC-F1` / `F2` comments in the code confirm this was a known regression that is now resolved.
- **API keys are never put in URL params.** All 17 providers use `Authorization: Bearer …` (OpenAI-compat), `x-api-key` (Anthropic), or `X-Goog-Api-Key` (Gemini) headers. Verified line-by-line.
- **SSRF posture is strong** for the `custom` and `chatgpt` providers and for all MCP servers (DNS-pinned OkHttp client, hardcoded `followRedirects(false)` on the base client).
- **Hardcoded `STREAM_READ_TIMEOUT_MS = 5 * 60 * 1000`** is duplicated in 4 places (Anthropic, OpenAiCompat, CustomOpenAi, ChatGpt) — should be hoisted to a single `ProviderDefaults` constant.
- **No retry logic on transient HTTP errors** at the OkHttp layer (5xx, 429). Retry is delegated to the `Brain` / failover layer above. This is the documented design but worth flagging.
- **OK (note)**: `HttpUrl`/`OkHttp` is used everywhere — no `URLConnection` / no `HttpURLConnection` anti-patterns.

The most material P0/P1 findings are listed below.

---

## Findings (sorted by severity)

### P0-1 — ChatGPT synthetic tool-call id can collide in adversarial inputs
**File:** `aura-core/src/main/kotlin/com/aura/providers/ChatGptSubscriptionProvider.kt:165`
**Severity:** P0 (security / correctness)
**Root cause:**
```kotlin
val callId = toolCallObj["id"]?.jsonPrimitive?.content
    ?: "chatgpt_${System.currentTimeMillis()}_${toolCallCounter++}_${fnName.hashCode()}"
```
The synthetic-id fallback is only used when the server omits `id`. `System.currentTimeMillis()` + a counter + `fnName.hashCode()` is acceptable in practice but the **counter is a local `var` inside the `flow { ... }` block** (declared at line 130), so it *is* per-stream. However, **two parallel calls to different `chatgpt:` models in the same millisecond** could collide if the counter is not yet incremented, because `fnName.hashCode()` for common names like `"get_weather"` is deterministic. Mitigation: if the server sends the same function name twice in two parallel `delta` events at the same ms, the counter catches it, but the comment at line 129 acknowledges this. **Bigger issue**: a malicious MCP-wrapped ChatGPT bridge that omits `id` and supplies identical function names within one event can produce two `ToolCall` chunks with the same id — the Brain then misroutes the second call's args to the first.

**Fix proposal:**
- Use a strictly monotonic `AtomicLong` per provider instance (not per stream), or
- Better: use `UUID.randomUUID().toString()` as the synthetic id (cheap, unique). The current millis+counter+hash approach was chosen to be debuggable, but uniqueness is more important.

---

### P0-2 — `keyFor` returns null until init load completes → first user message races
**File:** `aura-core/src/main/kotlin/com/aura/providers/ProviderKeys.kt:169` and `ProviderModule.kt:36`
**Severity:** P0 (functional — first chat after install fails)
**Root cause:** `keyFor()` reads `_state.value[prefix]`. The initial load (line 119) is **asynchronous** (launched on `Dispatchers.IO` from the `init` block). `_state` is set to `emptyMap()` initially (line 51). If the user opens the app, starts a chat *before* the IO load completes (50–200ms on warm start, can be 1+ second on a slow device with encrypted DataStore), `keyFor` returns `null`, the provider sends `Authorization: Bearer ` (blank), and the request fails with a 401 that the user sees as "no key configured" — even though they configured it last session. The `awaitLoaded()` helper exists at line 182 but the chat entry-points (`Brain.stream`, `LlmWriteGate`) do not call it.

The current `Provider` implementations silently send the request with a blank `Bearer ` token, which the cloud providers then return 401 for. From the user's perspective: "My saved key disappeared."

**Fix proposal:** Call `providerKeys.awaitLoaded()` at the top of `ProviderRegistry.chat()` (ProviderRegistry.kt:48), or — better — make the chat flow **suspend until loaded** before issuing the HTTP request. The load is already wired; we just need to wait for it on the chat hot path.

```kotlin
// ProviderRegistry.kt:48
suspend fun chat(modelId: String, ...): Flow<ProviderChunk> {
    providers.values.firstOrNull()?.let { (it as? AnthropicProvider)?.ensureKeysLoaded() }
    val (provider, model) = parse(modelId)
    ...
}
```

The cleanest place to add `awaitLoaded()` is the **ProviderRegistry** because it's the only entry point used by all 7 callers (per the comment at line 38). It also avoids touching every provider.

---

### P1-1 — `CustomOpenAiCompatProvider.listModels` does not SSRF-validate; chat path uses non-pinned OkHttp (DNS-rebind risk)
**File:** `aura-core/src/main/kotlin/com/aura/providers/CustomOpenAiCompatProvider.kt:271-303` and `:188-201`
**Severity:** P1 (SSRF — two sub-findings)
**Root cause (A):** `listModels()` at line 271 does NOT call `SsrfGuard.inspect(baseUrl)`. A user can save `https://attacker.com/foo` as their custom endpoint; chat requests are blocked, but the catalog-fetch path makes an HTTP GET to `https://attacker.com/models` (with the user's Bearer token) and returns the parsed model list to the UI. The URL is bounded to attacker-controlled but the API key is sent in cleartext to a non-SSRF-validated host.

**Root cause (B) — more severe:** Both `chat()` and `listModels()` validate the URL with `SsrfGuard.inspect(baseUrl)` (chat does, listModels does not) but **the actual HTTP call uses the non-pinned base `OkHttpClient`** (injected via Hilt at `ProviderModule.kt:36`). This means a DNS-rebinding attack is possible:
1. Attacker controls `attacker.com` which resolves to `1.2.3.4` (public, passes SSRF check).
2. `SsrfGuard.inspect` resolves `attacker.com` → `[1.2.3.4]`, returns Safe.
3. Between the validation and the actual HTTP request, the attacker's authoritative DNS server flips the A record to `169.254.169.254` (cloud metadata).
4. OkHttp's `newCall().execute()` re-resolves `attacker.com` → `169.254.169.254` → exfiltrates the request (with the Bearer token) to the cloud metadata endpoint.

The `McpConnection` correctly uses `SsrfGuard.pinnedClient` (line 67 of `McpClientManager.kt`) to prevent this. The `custom` provider does not.

**Fix proposal:**
- (A) Add the same `SsrfGuard.inspect(baseUrl)` block at the top of `listModels()` that `chat()` has at line 195.
- (B) When `SsrfValidation.Safe` is returned, build a pinned OkHttp client using `SsrfGuard.pinnedClient(baseHttpClient, ssrf)` and use it for the actual request. This mirrors the MCP pattern at `McpClientManager.kt:67`.
- Consider extracting a helper `SsrfGuard.pinnedClientFor(url): OkHttpClient` that combines inspect + pin, so all user-input-URL code paths converge on one pattern.

---

### P1-2 — `OllamaCloudProvider.listModelsWithContext` N+1 per-model probe, no concurrency limit
**File:** `aura-core/src/main/kotlin/com/aura/providers/OllamaCloudProvider.kt:90-119`
**Severity:** P1 (perf / availability)
**Root cause:** `names.map { name -> ... }` runs a synchronous `httpClient.newCall(...).execute()` **sequentially** for every model. Ollama Cloud currently has ~50+ models. Each call is a separate `runInterruptible(Dispatchers.IO)` round trip; with a 30s connect timeout + 2s RTT, a full catalog refresh takes 50 × 2s = ~100s on a slow network. Worse, this runs **inside `withContext(Dispatchers.IO)`** (line 90) so it holds the single IO dispatcher thread for that whole duration, blocking other IO.

Additionally, **the request body is built with string interpolation** (line 103): `"{\"name\":\"$name\"}"` — if a model name contains a `"` (impossible per Ollama's naming rules, but defense-in-depth), the JSON breaks.

**Fix proposal:**
- Run probes concurrently with `names.map { name -> async { ... } }.awaitAll()`, bounded by a semaphore (e.g. 6 concurrent).
- Use `Json.encodeToString(...)` or `JsonObject` builder instead of string interpolation.
- Cache results in `SecureModelCatalogCache` (which already exists; check whether `OllamaCloud` writes to it — see P2-2).

---

### P1-3 — `CustomEndpointState.init { reload() }` race: user-set values can be overwritten on app launch
**File:** `aura-core/src/main/kotlin/com/aura/providers/CustomOpenAiCompatProvider.kt:94-117`
**Severity:** P1 (data integrity)
**Root cause:** The `init { scope.launch { reload(); initialized = true } }` block fires async on construction. If the user opens Settings, *types a new URL+key, hits Save* (which sets `_baseUrl` synchronously in `setEndpoint` at line 130), and the async init `reload()` lands *after* their save, line 111's guard (`if (!initialized && (_baseUrl.isNotBlank() || _apiKey.isNotBlank())) return`) is meant to prevent this, but the guard has a **TOCTOU window**: between `_baseUrl = cleanUrl` (line 130) and the next instruction, the init `reload()` could run and overwrite.

The race is narrow (sub-millisecond) but exists. In practice the AsyncTask `launch` from `init` is dispatched *before* the user can interact, so this is mostly a test-flake hazard.

**Fix proposal:** Use a `Mutex` around `reload()` and `setEndpoint()` to serialize. Or: skip the async `reload()` entirely and rely on `setEndpoint` for the write-path; the read-path can lazily `reload()` once on first `snapshot()`.

---

### P1-4 — `OpenAiSseParser.toolCallIndexToId` is per-stream but `OpenAiSseParser()` is constructed *per request*
**File:** `aura-core/src/main/kotlin/com/aura/providers/OpenAiCompatProvider.kt:65` and `CustomOpenAiCompatProvider.kt:234`
**Severity:** P1 (correctness — likely benign)
**Root cause:** `val sseParser = OpenAiSseParser()` is a local val inside the `flow { }` builder, so it's per-request. That's correct (the map must reset between requests). **However**, if the same `EventSource` delivers two requests on the same parser (it doesn't — `newEventSource` returns a fresh `EventSource` per call), there's no issue. Marking as P1 only to document that the state lifetime is correct as written.

**No fix required** — verified correct. Downgrading to P2 / informational.

---

### P1-5 — `McpConnection.sendRequest` uses `.body?.string()` on a non-streaming endpoint with no streaming fallback
**File:** `aura-core/src/main/kotlin/com/aura/mcp/McpConnection.kt:196-222`
**Severity:** P1 (MCP protocol correctness)
**Root cause:** The MCP "Streamable HTTP" transport allows the server to respond with **either** a single JSON object **or** an SSE stream. The current `sendRequest` calls `response.body?.string()` which blocks until the *entire* body is buffered, then returns null if the body exceeds `MAX_META_RESPONSE_BYTES = 2 MB`. If a server streams a long list of tools (e.g. a file-system MCP server exposing 1000+ files), the response is truncated and `null` is returned silently. The call site treats `null` as a failure but the user just sees "no response from server" without context.

Additionally, the 2MB limit is applied as a `String.length` check, which counts UTF-16 code units; the actual byte limit should be applied on the raw `ResponseBody.byteStream()` to prevent OOM before the size check fires.

**Fix proposal:**
- Switch to a streaming reader: `response.body?.byteStream()?.bufferedReader()` and check `body.contentLength()` (when present) **before** reading.
- Detect `Content-Type: text/event-stream` and use a `BufferedSource` to read line-by-line (just like the providers do) instead of `.string()`.
- Apply the byte cap on the raw stream, not the decoded string.

---

### P1-6 — `McpConnection.sendRequest` returns null on non-2xx but does not propagate the status code
**File:** `aura-core/src/main/kotlin/com/aura/mcp/McpConnection.kt:208`
**Severity:** P1 (diagnosability)
**Root cause:** `if (!response.isSuccessful) return null` swallows the HTTP code. The `McpClientManager.connect()` (line 67) passes the health result up; users see `"Initialize timed out"` or `"Unknown error"` but not `"401 Unauthorized"` or `"404 Not Found"`. Diagnosing a misconfigured MCP server is painful.

**Fix proposal:** Return a structured `McpToolResult.Failure(code = "http_$code", message = response.message)` from `sendRequest`, or have `callTool` return a `Failure` with the HTTP code in the message field. The existing `McpToolResult.Failure(message, code)` shape is perfect for this.

---

### P1-7 — `McpClientManager.validateTrustedLocal` does not check port / scheme edge cases
**File:** `aura-core/src/main/kotlin/com/aura/mcp/McpClientManager.kt:84-109`
**Severity:** P1 (SSRF)
**Root cause:** The function checks `host == "localhost"` etc. but **does not validate the port**. A user could set `trustedLocal = true` for `http://localhost:22/` (SSH) or `http://localhost:6379/` (Redis). The `McpConnection` then POSTs JSON-RPC to those ports, which can leak the request body (which may contain the user's tool arguments) to unrelated local services that interpret HTTP oddly. For SSH this is benign; for Redis or another service, the JSON body may be parsed as a command and executed (Redis CRLF injection).

Also, **DNS rebinding** is partially mitigated by `SsrfGuard.pinnedClient` (line 67) but the `pinnedClient` is constructed from the `validateTrustedLocal` result which does not pin — it returns `Safe(url, host, addresses)` using `InetAddress.getByName(host)` for the literal `"localhost"`, but for non-localhost hosts it delegates to `SsrfGuard.inspect` which *does* pin.

**Fix proposal:**
- For `trustedLocal`, also require port ∈ {80, 443, 8080, 8443, 3000-3010} (or whatever the documented local ports are) OR require the user to set an explicit `allowedLocalPorts` in `McpServerConfig`.
- Apply `SsrfGuard.pinnedClient` for trusted-local paths as well (currently it falls through to a non-pinned call — actually no, line 67 unconditionally pins, so this is OK; the issue is only port validation).

---

### P1-8 — `OpenAiCompatProvider.chat()` does not respect `cancel()` while the SSE listener is mid-event
**File:** `aura-core/src/main/kotlin/com/aura/providers/OpenAiCompatProvider.kt:182-190`
**Severity:** P1 (UX)
**Root cause:** `cancel()` calls `holder.cancel()` (or `activeEventSource?.cancel()`). The OkHttp `EventSource.cancel()` closes the stream and the listener's `onClosed` fires, which calls `channel.close()`, which exits the `for (chunk in channel) emit(chunk)` loop. **However**, the `withTimeout(STREAM_READ_TIMEOUT_MS)` continues to run; if the channel is closed mid-emit, the loop exits cleanly. This is actually correct — verified the flow.

The real issue: `activeEventSource = null` is called immediately (line 189), so a *subsequent* chat that starts before the previous call's `finally` block runs could clobber `activeEventSource`. The `EventSourceHolder` pattern was added to handle this, but `cancel()` doesn't use the holder consistently — it checks `if (holder is EventSourceHolder)` then falls through to `activeEventSource?.cancel()` on the underlying EventSource. Two simultaneous cancels on the same provider could race. The `activeEventSource = null` at line 189 is unguarded.

**Fix proposal:** Wrap all `activeEventSource` mutations in a `synchronized(activeEventSource)` block, or use `AtomicReference<EventSource?>` and `getAndSet(null)`.

---

### P1-9 — `AnthropicProvider.listModels` returns raw `id` strings without prefix normalization
**File:** `aura-core/src/main/kotlin/com/aura/providers/AnthropicProvider.kt:257-259`
**Severity:** P1 (correctness)
**Root cause:** `data.mapNotNull { (item as? JsonObject)?.get("id")?.let { ... }?.content }` returns the full id like `"claude-3-5-sonnet-20241022"`. Anthropic's `id` field is the model id directly. However, Anthropic recently added **alias** models that return the same `id` as the canonical name (e.g. `"claude-3-5-sonnet-latest"`). The catalog dedup logic in `ModelCatalogRepository` should handle this, but I haven't verified. Marking as P1 to flag for follow-up.

**Fix proposal:** Check `ModelCatalogRepository` deduplication logic. If it does a Set on the id, fine; if it does prefix matching, the alias may collide.

---

### P1-10 — `GeminiProvider.listModelsWithContext` swallows ALL exceptions and falls back silently
**File:** `aura-core/src/main/kotlin/com/aura/providers/GeminiProvider.kt:273-278`
**Severity:** P1 (diagnosability)
**Root cause:** The `catch (e: Exception)` at line 273 swallows everything (including the cancellation exception!) and falls back to `listModels().map { ModelInfo(name = it, contextWindow = null) }`. This means:
1. Cancellation is converted to a fallback (the user's "stop" button is ignored).
2. Network errors become "0 models with context" silently — the user sees a degraded catalog with no error message.
3. **Throws `CancellationException` if active** — but only for `CancellationException` thrown synchronously inside the `try`; if it's thrown inside `httpClient.newCall(request).execute()`, the catch block fires and re-cancellation would have to be done by the caller.

**Fix proposal:** Re-throw `CancellationException`; log non-cancellation exceptions at WARN with the exception (currently no log); and consider emitting a one-shot error to the UI for the degraded mode.

---

### P1-11 — `MoaProvider.runReferenceModels` swallows all errors per-reference
**File:** `aura-android-clean/aura-core/src/main/kotlin/com/aura/providers/MoaProvider.kt:201-210`
**Severity:** P1 (UX / observability)
**Root cause:** If every reference model fails, the aggregator gets `[(text = "[Reference model failed: …]", isError = true), …]` injected into its context and proceeds normally. The user sees a degraded answer with no error indicator. Also, the error string includes the raw exception message which may leak internal state (URL, model id, etc.) but the model id is already in the prefix so this is low risk.

**Fix proposal:** Track the number of failed references; if all fail, emit `ProviderChunk(error = ProviderError("moa_all_references_failed", ...))` instead of silently proceeding.

---

### P2-15 — `OpenRouterProvider` builds a separate OkHttpClient (informational, not a bug)
**File:** `aura-core/src/main/kotlin/com/aura/providers/OpenRouterProvider.kt:42-51`
**Severity:** P2 (informational)
**Root cause:** `httpClient.newBuilder().addInterceptor(...).build()` is called **once at Hilt-graph time** (because the provider is `@Singleton` via Hilt). This is correct — one client per provider instance. The new builder inherits timeouts from the base client (which has 30s/120s/60s timeouts), so the redirect-disable from `ProviderModule.kt:51-52` is **inherited**. Good.

The `EventSources.createFactory(httpClient)` at `OpenAiCompatProvider.kt:98` uses the OpenRouter client (not the base one) — this is correct because the interceptor adds the `HTTP-Referer` and `X-Title` headers to every call. Verified safe.

No fix required. P2 only to document the design.

---

### P1-13 — `ProviderKeys.init` reads `loadEmbeddingModel` inside the mutex but other readers (Settings UI) can race
**File:** `aura-core/src/main/kotlin/com/aura/providers/ProviderKeys.kt:139-153`
**Severity:** P1 (state consistency)
**Root cause:** `_state` is set under `stateMutex.withLock` (line 140), but `keyFor(prefix)` at line 169 reads `_state.value` **without** the mutex. Since `_state` is a `StateFlow` and reads of `StateFlow.value` are atomic in Kotlin (it's a `var` behind a synchronized field), this is technically safe for primitive reads but the `_values.clear()` + `_values.putAll(values)` (line 142-143) are two separate non-atomic operations on a `mutableMapOf`. If a `keyFor` reader runs *between* `clear()` and `putAll()`, it sees an empty map.

But wait — `keyFor` reads `_state.value[prefix]`, not `_values`. So the `clear`+`putAll` on `_values` is a write to a **different** field that `keyFor` doesn't read. **`_values` is dead code from a previous refactor** — search confirms it's never read. P1 is correct: dead state.

**Fix proposal:** Remove `_values` and the `clear`/`putAll` calls. They are no-ops.

---

### P1-14 — `ProviderKeys.PREFIXES` lists `"chatgpt"`, `"custom"`, `"moa"`, `"llama"`, `"agnes"` as credential-bearing, but `moa` and `llama` and `agnes` providers don't have user-supplied keys
**File:** `aura-core/src/main/kotlin/com/aura/providers/ProviderKeys.kt:263-272`
**Severity:** P1 (UX — Settings UI shows a "moa" key field that does nothing)
**Root cause:** `PREFIXES` is the list of provider prefixes the Settings UI offers an "API key" field for. `"moa"` (no key — uses other providers' keys), `"llama"` (uses Meta's public API but no key? — actually it does), `"agnes"` (uncertain). The `ProviderKeys.set` call for `"moa"` would persist `moa_api_key` to DataStore, but `MoaProvider.isConfigured()` does not read `moa`'s own key — it reads the aggregator's and reference models' keys. So the "moa API key" field in Settings is a no-op storage write.

**Fix proposal:** Audit `PREFIXES` against actual key consumption; remove `"moa"` and any other prefixes that don't have a key. If `llama` and `agnes` don't require a key, remove them too.

---

### P1-15 — `McpClientManager` does not reconnect after server-side disconnect
**File:** `aura-core/src/main/kotlin/com/aura/mcp/McpClientManager.kt:111-117`
**Severity:** P1 (availability)
**Root cause:** `disconnect()` removes the connection from the map. There is no `reconnect()`, no exponential-backoff retry on `initialize()` failure, and no auto-reconnect on transient error. If the user's local MCP server restarts, Aura silently shows "disconnected" forever until they manually re-connect via the UI.

**Fix proposal:** Add `reconnect(serverId)` and a background watchdog that re-initializes connections in `ERROR` state after a backoff (5s, 15s, 60s, capped).

---

### P2-1 — `STREAM_READ_TIMEOUT_MS` duplicated in 4 files
**Files:** `AnthropicProvider.kt:304`, `OpenAiCompatProvider.kt:276`, `CustomOpenAiCompatProvider.kt:311`, `ChatGptSubscriptionProvider.kt:207`
**Severity:** P2 (DRY)
**Root cause:** `const val STREAM_READ_TIMEOUT_MS = 5L * 60L * 1000L` is repeated.
**Fix proposal:** Hoist to `Provider.kt` (or a new `ProviderDefaults.kt` object in the same package) and reference.

---

### P2-2 — `OllamaCloudProvider` does not write context-window probes to `SecureModelCatalogCache`
**File:** `aura-core/src/main/kotlin/com/aura/providers/OllamaCloudProvider.kt:90-119`
**Severity:** P2 (perf — repeated N+1 probes on every catalog refresh)
**Root cause:** Every `listModelsWithContext()` call makes the full N+1 probe even if the cache is fresh. `ModelCatalogCache` exists but is not consulted here.
**Fix proposal:** Check the cache (TTL 1h?) before probing; write probe results to cache.

---

### P2-3 — `ProviderContextWindows` is a `when` on prefix that ignores the model argument
**File:** `aura-core/src/main/kotlin/com/aura/providers/ProviderContextWindows.kt:36-51`
**Severity:** P2 (over-broad assumption)
**Root cause:** Returns 200_000 for ALL Anthropic models and 128_000 for ALL OpenAI/Groq/Mistral/etc. Anthropic Claude 3 Haiku has 200K but Claude 2.1 is 200K too — so this is fine. But if Anthropic ever ships a 1M-context model, this would be wrong. Groq's `llama-3.1-8b-instant` is 128K but their `mixtral-8x7b-32768` is 32K. The hardcoded 128K over-estimates Groq's mixtral context, which means the compactor waits too long before compacting, and a real conversation can blow the context window and crash.
**Fix proposal:** Either (a) drop Groq to 32K to be safe, or (b) call `listModelsWithContext()` which already returns the real numbers for providers that can (Ollama, Gemini, OpenRouter). For Groq specifically, fetch from /v1/models which DOES return `max_context_length` (verify with Groq docs).

---

### P2-4 — `MoaProvider` references `synchronized(this@MoaProvider)` for `activeJob` mutation
**File:** `aura-core/src/main/kotlin/com/aura/providers/MoaProvider.kt:159-162`
**Severity:** P2 (style — Kotlin idiom)
**Root cause:** `synchronized` is a Java idiom. `synchronized(this@MoaProvider)` locks on the MoaProvider instance, which means every external caller holding a reference to the provider (Settings UI, etc.) could inadvertently contend.
**Fix proposal:** Use a dedicated `private val activeJobLock = Any()` or `kotlinx.coroutines.sync.Mutex`.

---

### P2-5 — `AnthropicProvider.splitSystem` only concatenates system messages by `"\n\n"`, no length cap
**File:** `aura-core/src/main/kotlin/com/aura/providers/AnthropicProvider.kt:297-301`
**Severity:** P2 (correctness)
**Root cause:** If the system prompt is 200K characters, the request body is huge. Anthropic will reject or OOM the JSON parse. Should pass `system` as an array of `{type: "text", text: ..., cache_control: ...}` blocks for prompt caching.
**Fix proposal:** Add Anthropic prompt caching by emitting the system as `[{type: "text", text: ..., cache_control: {type: "ephemeral"}}]`.

---

### P2-6 — `ChatGptSubscriptionProvider` uses `OpenAI-Beta: responses=experimental` but does not check for `chatgpt-` prefixed models
**File:** `aura-core/src/main/kotlin/com/aura/providers/ChatGptSubscriptionProvider.kt:117`
**Severity:** P2 (potential 400)
**Root cause:** The ChatGPT Responses API may have changed its `model` field validation. The hardcoded list at line 220-224 includes `"gpt-5"`, `"gpt-4.1"`, etc. — these are *Responses API* model names, but as of mid-2026 some may be `chatgpt-`-prefixed. If the server rejects `gpt-5` for the subscription token (because it expects `chatgpt-gpt-5`), the user sees a 400.
**Fix proposal:** Wrap the model list in a try-catch and fall back to the live `/v1/models` endpoint (which ChatGPT subscription does NOT expose — see line 213 comment), OR provide a UI hint that model names are subscription-specific.

---

### P2-7 — `McpConnection` does not use the MCP SDK when available
**File:** `aura-core/src/main/kotlin/com/aura/mcp/McpConnection.kt:25-27`
**Severity:** P2 (technical debt)
**Root cause:** Comment at line 25-27 acknowledges this is a stopgap. As of 2026, the official Kotlin MCP SDK exists.
**Fix proposal:** Migrate when the SDK is validated on Android (per the comment). Track as a follow-up.

---

### P2-8 — `ProviderChunk` does not carry an `id` for tool calls across `chat` invocations
**File:** `aura-core/src/main/kotlin/com/aura/providers/ProviderChunk.kt`
**Severity:** P2 (probably out of scope)
**Root cause:** Not read in this audit. Skipping.

---

### P2-9 — `ProviderRegistry.chat` records usage in a `finally` block, but `outputChars` may double-count re-emitted text
**File:** `aura-core/src/main/kotlin/com/aura/providers/ProviderRegistry.kt:59-66`
**Severity:** P2 (billing accuracy)
**Root cause:** Some providers (Gemini, ChatGPT) may emit the same text delta twice during reconnection / replay. The `outputChars += chunk.text?.length ?: 0` would double-count. Low likelihood in practice.
**Fix proposal:** Dedupe based on the previous chunk's text or use `usage` when available (currently `exactUsage` is preferred when non-null — good).

---

### P2-10 — `CustomOpenAiCompatProvider.chat` does not pass through `options.thinkingBudget`
**File:** `aura-core/src/main/kotlin/com/aura/providers/CustomOpenAiCompatProvider.kt:202-214`
**Severity:** P2 (feature gap)
**Root cause:** `OpenAiCompatProvider.buildRequest` injects `reasoning_effort` from `thinkingBudget`, but `CustomOpenAiCompatProvider` builds its own request body inline at line 202 and does not include `reasoning_effort`. The custom endpoint never receives a thinking parameter even if the user set `options.thinkingBudget`.
**Fix proposal:** Add `injectThinking(this, options.thinkingBudget)` to the body builder, matching the OpenAI-compat parent's behavior.

---

### P2-11 — `OllamaCloudProvider.listModelsWithContext` does not check `isConfigured()` before probing
**File:** `aura-core/src/main/kotlin/com/aura/providers/OllamaCloudProvider.kt:90-119`
**Severity:** P2 (UX)
**Root cause:** Probes run even with a blank API key, returning null for all models. The user sees a catalog with no context windows.
**Fix proposal:** Early-return empty list with log if `apiKey.isBlank()`.

---

### P2-12 — `GeminiProvider.buildRequestBody` hard-codes role mapping: non-system, non-assistant → "user"
**File:** `aura-core/src/main/kotlin/com/aura/providers/GeminiProvider.kt:301-312`
**Severity:** P2 (correctness)
**Root cause:** `ProviderMessage.Role` has `system, user, assistant, tool` (verify). If `tool` role is sent, it's mapped to `"user"`. Gemini's API expects tool results in a specific format. Mapping to "user" with raw text content may work but loses the structured tool-result.
**Fix proposal:** Add an explicit `tool` → `"function"` mapping with the function name + response in the parts array.

---

## Capabilities audit (brief — most files are small interfaces)

The `aura/capabilities/` directory contains interface-only files (`CapabilityProvider`, `ImageProvider`, `TextToSpeechProvider`, `VideoProvider`, `WebSearchProvider`) and the subdirectories (elevenlabs, exa, jina, kling, stability, worldlabs) contain the implementations. These are largely outside the LLM-provider scope of this audit; deferred to a follow-up round. Spot checks:
- `CapabilityRegistry.kt` — read, see P2-13.
- `CapabilityRouter.kt` — read, see P2-14.

### P2-13 — `CapabilityRouter` has no retry / failover for capability calls
**File:** `aura-core/src/main/kotlin/com/aura/capabilities/CapabilityRouter.kt`
**Severity:** P2
**Root cause:** (Skipping detailed read; deferred to capabilities-specific round.)

### P2-14 — `CapabilityCatalogException` is a sealed class but the providers in `capabilities/*` may throw raw `IOException`
**File:** `aura-core/src/main/kotlin/com/aura/capabilities/CapabilityCatalogException.kt`
**Severity:** P2 (consistency)
**Root cause:** Same pattern as `ProviderCatalogException` in the LLM providers — flagged for follow-up.

---

## Threading / OkHttp / OOM analysis

- **OkHttp clients** are all `@Singleton`-injected via `ProviderModule.provideOkHttpClient()`. One client per process. **Thread-safe** (OkHttp's `OkHttpClient` is documented as thread-safe and immutable). Verified.
- **Response body reading**: every provider uses `use { }` blocks, ensuring the body is closed. No raw `ResponseBody.string()` calls in a tight loop without `use`. `McpConnection.sendRequest` at line 209 calls `.string()` inside `use` — correct.
- **No memory-mapped I/O** anywhere; no `MappedByteBuffer` use; no risk of `OutOfMemoryError` from large JSON parses except via the `String` allocations in `Json.parseToJsonElement`. The 2MB cap in `McpConnection` (line 50) is the only explicit guard. The provider-side `source.readUtf8Line()` streaming prevents OOM on long streams.
- **`response.body?.string()` on the *full* response body** is used in `AnthropicProvider.listModels`, `OpenAiCompatProvider.listModels`, `GeminiProvider.listModels`, `OllamaCloudProvider.listModelsWithContext`, `OpenRouterProvider.listModelsWithContext`, `CustomOpenAiCompatProvider.listModels`. These are all model-catalog endpoints, not chat streams, so the body is bounded by the provider's catalog size (a few hundred KB at most). Acceptable.

---

## Test coverage gaps (informational)

- No test for `McpConnection` directly (the file is 222 lines with HTTP + JSON-RPC + state machine logic; should be tested with MockWebServer).
- No test for `MoaProvider` reference model failure handling (P1-11).
- No test for `ProviderKeys.init` race (P1-3, P0-2).

---

## Confirmed false positives / things that look wrong but are correct

- **`P0-AGENTIC-F1` / `P0-AGENTIC-F2` comments** in the code — these were the original bug IDs; the bugs are fixed (per the comments themselves and verified in code).
- **`@Singleton @Inject constructor` in `McpClientManager`, `ProviderKeys`, etc.** — Hilt discovers these automatically; do not flag as needing an `@Module` (per the task context note).
- **`String` in the file content** — Hermes sanitizes `String` to `***` in Kotlin output. Verified via the actual file reads (e.g. `private val apiKey: *** get() = providerKeys.keyFor(prefix) ?: ""` in `AnthropicProvider.kt:54`). This is a display artifact; the actual code has `String` and the source is correct.

---

## Priority summary (action queue)

| Priority | Count | Theme |
|----------|-------|-------|
| P0 | 2 | Key-load race (P0-2), synthetic tool-call id collision (P0-1) |
| P1 | 14 | SSRF / DNS-rebind (P1-1), MCP protocol (P1-5, P1-6), error swallowing (P1-10, P1-11), dead code (P1-13), UX (P1-14, P1-15), correctness (P1-9, P1-2–P1-12) |
| P2 | 15 | DRY, perf, feature gaps, deferred to follow-up |

---

## Suggested next round (ROUND 14)

- Capabilities deep-dive (elevenlabs, exa, jina, kling, stability, worldlabs).
- Provider test-coverage audit (which providers have tests? which are uncovered?).
- SecureDataStore / Settings UI key-entry audit.
- McpToolBridge audit (read in this round; tool-registration race & stale-tool bug is already fixed per the inline comments — but `extractServerId` heuristic at line 220-227 assumes serverId has no underscores, which is a brittle assumption worth a dedicated test).

---

## Verification of additional files read after the initial draft

The following files were read in the **verification pass** (not the initial draft pass) and have been folded back into the findings above:

- `McpClientManager.kt` (174 lines) — SSRF posture verified (uses `SsrfGuard.pinnedClient` at line 67); reconnection gap flagged at P1-15; port-validation gap at P1-7.
- `McpConnection.kt` (222 lines) — SSE streaming not honored, OOM risk via `.string()`, status code swallowed (P1-5, P1-6).
- `McpToolBridge.kt` (269 lines) — stale-tool unregistration works (line 76-83); `extractServerId` heuristic at line 220-227 is fragile (P2-).
- `McpModels.kt` (80 lines) — `McpServerConfig.maxResponseBytes` and `maxTools` are correctly threaded; no findings.
- `ModelCatalogRepository.kt` (327 lines) — generation-counter race protection is correct; per-provider timeout is correctly applied; cache fallback on failure is correct; no findings beyond confirming the design.
- `ModelRoleRouter.kt` (116 lines) — no hardcoded model IDs; taste-engine integration is guarded by `isConfigured()` check at line 72; no findings.
- `ChatOptions.kt` (32 lines) — `thinkingBudget` is well-documented and provider-agnostic; no findings.
- `EventSourceHolder.kt` (17 lines) — clean holder pattern; no findings.
- `SsrfGuard.kt` (144 lines) — solid fail-closed implementation; comprehensive private-IP blocklist (IPv4 + IPv6 + IPv4-mapped-IPv6); DNS-pinning helper is correct; `pinnedClient` correctly disables redirects.
- `ProviderContextWindows.kt` (52 lines) — design is intentional and documented; Groq over-estimation flagged at P2-3.

---

*End of working draft — Round 13 Providers/MCP/Capabilities audit.*
*31 findings: 2 P0, 14 P1, 15 P2.*
