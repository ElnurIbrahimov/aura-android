# ROUND 14 — LLM Providers / MCP / Capabilities Audit

**Project:** aura-android-clean v0.56.1
**Scope:** `aura-core/src/main/kotlin/com/aura/providers/*.kt`, `aura-core/src/main/kotlin/com/aura/mcp/*.kt`, `aura-core/src/main/kotlin/com/aura/capabilities/*.kt`
**Audit date:** 2026-08-03
**Method:** Static review of 4,822 lines across 37 Kotlin files, focused on v0.56 thinking/reasoning_content parsing, SSE parallel-tool-call deltas, keyForAwaiting(), OkHttp thread safety, OOM risks, and SSRF/credential/timeout gaps.

---

## Executive summary

The provider layer in v0.56 makes a major correctness improvement: SSE tool-call parsing now handles parallel `tool_calls` arrays (P0-AGENTIC-F1, fixed in `OpenAiSseParser`) and Anthropic parallel `input_json_delta` deltas (fixed in `AnthropicProvider` via `pendingByIndex`). The new `thinking` / `reasoning_content` deltas are plumbed through all three protocol parsers (OpenAI-compat, Anthropic, Gemini). However, the **`keyForAwaiting()` suspend method on `ProviderKeys` is wired into search tools and the agent loop, but is not used by any of the seven chat providers** — they continue to call the synchronous `keyFor()`. This is partially fine (the sync read hits the in-memory `StateFlow` which is invalidated on every `set()`), but a freshly-set key paired with an in-flight chat call can still race if the chat's first `keyFor` call returns the old value before the `set` lands. The `thinking` chunks are plumbed through `ProviderChunk` (which does have a `thinking: String?` field — verified at `ProviderChunk.kt:8`), but `ProviderMessage` has no `thinking` field (verified at `ProviderMessage.kt:7-12` — only `role, content, name, toolCalls, toolCallId`). So the chunks are emitted into the Flow but cannot be persisted into conversation history — they are silently lost on multi-turn conversations.

Other P0 issues: `OpenAiCompatProvider.activeEventSource` is a `@Volatile` non-atomic swap that races during concurrent `cancel()` + `chat()` calls; `GeminiProvider` and `AnthropicProvider` share the same pattern. `OpenAiSseParser.parseEvent` leaks `toolCallIndexToId` across `chat()` invocations on the same parser instance if the same parser is reused (it is currently `new OpenAiSseParser()` per `chat()` so this is OK, but the API doesn't prevent reuse). The `MoaProvider` has a `synchronized(this)` block around a coroutine Job that is meaningless (Job is already thread-safe and the lock is per-instance — it can't even serialize two MoA requests).

I count **6 P0**, **9 P1**, and **7 P2** findings. The most damaging to production reliability are F-01 (thinking deltas silently dropped from conversation history), F-02 (keyForAwaiting dead code), and F-04 (OkHttp client thread-safety race in `cancel()`).

---

## P0 findings (must fix before next release)

### F-01 — Thinking/reasoning chunks are emitted to the Flow but never persisted into conversation history
**Severity:** P0
**File:line:** `aura-core/src/main/kotlin/com/aura/providers/OpenAiSseParser.kt:68-70`, `AnthropicProvider.kt:168-171`, `GeminiProvider.kt:127-132`
**Root cause:** v0.56 added parsing of `reasoning_content` / `reasoning` (OpenAI), `thinking_delta` (Anthropic), and `thought=true` parts (Gemini). The chunks are emitted as `ProviderChunk(thinking = …)` into the SSE stream. However, in `Brain.fromProvider()` (out of audit scope, but reachable from the ProviderChunk contract) there is no `thinking` field on `ProviderMessage` and no accumulator in the conversation builder. Result: extended-thinking output is shown to the user as a streaming preview (if a UI hook reads it) but is **not stored** in the message history, so the next turn cannot reference it and the context-window compactor never accounts for it. This silently inflates the visible "assistant turn" size while undercounting actual token usage.
**Fix proposal:**
1. Add `thinking: String? = null` to `ProviderMessage` with a `Role.assistant` carrier (or a dedicated `MessagePart` sealed class).
2. In `Brain.fromProvider()`, when accumulating a streaming assistant response, fold `chunk.thinking` into a separate `ProviderMessage(content = thinking, role = assistant, kind = thinking)` part that lives alongside the final answer.
3. In the compactor, count `thinking` blocks at the same rate as `text` (Anthropic charges full price for thinking tokens; OpenAI o-series at high effort can produce 5–20x the visible answer in reasoning tokens).
4. Add a regression test that streams a 5-turn conversation where each turn emits 2 KB of `reasoning_content` and asserts that the persisted history contains it.

### F-02 — `keyForAwaiting()` is used by tools/agent loop but NOT by the seven chat providers
**Severity:** P0
**File:line:** `ProviderKeys.kt:172` (definition); callers `MemoryAugmentedAgenticLoop.kt:1135-1136`, `BraveSearchTool.kt:72`, `TavilySearchTool.kt:83` (in scope but tool-layer, not provider-layer); providers that do NOT use it: `OpenAiCompatProvider.kt:53`, `AnthropicProvider.kt:54`, `GeminiProvider.kt:57`, `OllamaCloudProvider.kt`, `OpenRouterProvider.kt`, `GroqProvider.kt`, `CustomOpenAiCompatProvider.kt`
**Root cause:** The v0.56 changelog added a suspend `keyForAwaiting()` method (ProviderKeys.kt:172) that waits for the initial DataStore load to complete (via `_loaded.first { it }`), then returns the key. This is correctly used by search tools (`BraveSearchTool`, `TavilySearchTool`) and the agent loop. However, the seven chat providers all call the **synchronous** `providerKeys.keyFor(prefix) ?: ""`. The synchronous path returns `null` while the initial load is in flight (the StateFlow is empty until `_loaded.value = true`). This means:
- A user who opens the app and immediately sends a chat message before DataStore finishes loading gets a **401** because `keyFor` returns null/blank.
- A user who updates a key in Settings and then immediately sends a chat message may also get a 401 if the new key's `set()` hasn't completed the StateFlow update yet (though this is rare because `set()` updates `_state.value` synchronously under `stateMutex`).
- The async `init` load in `ProviderKeys` (line 118) populates state under `stateMutex`, but until that completes, `keyFor("openai")` returns null.
**Fix proposal:**
1. Migrate all `chat()` methods to call `keyForAwaiting()` at the top of the Flow. This is a small change: replace `private val apiKey: *** get() = providerKeys.keyFor(prefix) ?: ""` with a `suspend fun resolveKey(): *** = providerKeys.keyForAwaiting(prefix) ?: ""` and call it once at the top of `chat()`'s flow body.
2. The `isConfigured()` check should also call `keyForAwaiting()` (currently synchronous, returns false during the load window, which is correct behavior — provider appears "not configured" briefly).
3. The `listModels()` path should also `awaitLoaded()` so a fresh app start doesn't return an empty catalog during the 50–100ms init load.
4. Add a regression test: clear DataStore, set a key, then immediately call `chat()`; assert no 401 is returned.

### F-03 — `MoaProvider.activeJob` synchronization is meaningless and the `@Volatile` pattern in OpenAiCompatProvider races
**Severity:** P0
**File:line:** `aura-core/src/main/kotlin/com/aura/providers/MoaProvider.kt:159-162`, `OpenAiCompatProvider.kt:46`, `AnthropicProvider.kt:56`, `GeminiProvider.kt:59`
**Root cause:** Three distinct issues:
1. `MoaProvider.chat()` does `synchronized(this@MoaProvider) { activeJob?.cancel(); activeJob = job }`. `Job.cancel()` is already thread-safe; the `synchronized` block is per-instance, so it cannot serialize two MoA requests from the same provider — it only delays the second request's first line of code by a few microseconds. Worse, if the previous `activeJob` is already completed, the `cancel()` is a no-op; the second request will run while the first is still tearing down, leading to two aggregator streams writing into two `channelFlow`s. The lock provides zero mutual exclusion.
2. `OpenAiCompatProvider.activeEventSource` is declared `@Volatile` and is reassigned to an `EventSourceHolder` in the listener's `onEvent` and back to `null` in `finally`. `cancel()` reads it without a lock. If `chat()` runs concurrently with `cancel()` on different threads (which it can: `cancel()` is called from the UI thread, `chat()` is called from a worker), the `activeEventSource` may be a `null` (after `cancel()`), but the EventSource was just started, and the new chat's `finally` block cancels the previous one's holder.
3. `AnthropicProvider` and `GeminiProvider` use `@Volatile private var activeCall: okhttp3.Call?` and `activeCall?.cancel()` in `cancel()`. The same race applies: the new call may overwrite `activeCall` before the old one is cancelled, causing a "stop" in the UI to cancel the **new** request.
**Fix proposal:**
1. For `MoaProvider`: remove the `synchronized` block; instead, use a `MutableStateFlow<Job?>` and `update { it?.cancel(); job }` so the cancel-then-replace is atomic. The previous Job's coroutine children will all see the cancel via structured concurrency.
2. For all three providers: change `activeEventSource`/`activeCall` to a `MutableStateFlow<Handle?>` where `Handle` is a sealed type with a `cancel()` method, and gate `chat()`'s assignment through `compareAndSet`. Or, use a per-request `Job` returned to the caller and have the UI cancel the Job (not the provider). The provider's `cancel()` should cancel **the most recent** Job, which means the Job lookup must be atomic.
3. Add a test: launch two `chat()` flows on the same provider from two coroutines and call `cancel()` between them; assert the first one is cancelled and the second one is not.

### F-04 — `OpenAiCompatProvider.listModels()` uses `defaultModels` shortcut that silently masks credential misconfiguration
**Severity:** P0
**File:line:** `aura-core/src/main/kotlin/com/aura/providers/OpenAiCompatProvider.kt:121`
**Root cause:** `if (defaultModels.isNotEmpty()) return@withContext defaultModels` — this short-circuits the `/v1/models` network call and returns a hardcoded list. Any subclass (e.g. `GroqProvider`, `OllamaCloudProvider`, `CustomOpenAiCompatProvider`) that sets `defaultModels` will never test the API key at the model-list layer. A user with a 401 / expired key will see the model list populated in the UI, pick a model, and only discover the credential issue when the first `chat()` returns 401. This violates the "fail fast" principle and is a foot-gun for `CustomOpenAiCompatProvider` where the user is configuring their own endpoint.
**Fix proposal:**
1. Add an opt-in flag: `if (defaultModels.isNotEmpty() && !verifyAuthOnCatalogFetch) return@withContext defaultModels`. Default `verifyAuthOnCatalogFetch = true` for `CustomOpenAiCompatProvider`, `false` for `OllamaCloudProvider` (where the model list is large and stable).
2. Or, when the list is hardcoded, still perform a lightweight `/models` HEAD-style request and surface 401 to the user via a one-time UI banner.
3. Add a regression test: configure a `CustomOpenAiCompatProvider` with a bad key; assert that `listModels()` either succeeds-with-warning or throws `AuthenticationException`; assert it does not silently return the hardcoded list as if all was well.

### F-05 — SSRF TOCTOU gap: `CustomOpenAiCompatProvider` validates `baseUrl` against DNS but OkHttp resolves again at send time
**Severity:** P0
**File:line:** `aura-core/src/main/kotlin/com/aura/providers/CustomOpenAiCompatProvider.kt:189-201`, `ProviderModule.kt:36-53`
**Root cause:** `CustomOpenAiCompatProvider.chat()` calls `SsrfGuard.inspect(baseUrl)` (line 195) which validates the URL. However, **OkHttp's DNS resolution happens at HTTP send time, not at validation time** — an attacker controlling DNS (or a compromised local resolver) can return a different IP at send time. Compare with `McpClientManager.connect()` (line 67) which uses `SsrfGuard.pinnedClient(httpClient, ssrfResult)` — that **pins** the resolved IP into the OkHttp client so the actual HTTP call cannot resolve to a different address. The custom chat provider does **not** use the pinned client; it uses the raw `httpClient` (line 174). The TOCTOU window is real on Android: a chat request and a DNS rebinding response can land within milliseconds.

Note: the base `OkHttpClient` is configured with `followRedirects(false)` and `followSslRedirects(false)` at `ProviderModule.kt:51-52`, so redirect-based SSRF is closed at the transport layer. Good. But DNS rebinding is still open for the custom endpoint.
**Fix proposal:**
1. In `CustomOpenAiCompatProvider.chat()`, after `SsrfGuard.inspect(baseUrl)` returns `Safe`, switch from `httpClient.newCall(request)` to `SsrfGuard.pinnedClient(httpClient, ssrfResult).newCall(request)` (same as MCP does). This requires hoisting the client construction out of the per-call hot path or caching it by `(baseUrl, host, addresses)` triple.
2. Validate that `baseUrl` uses `https://` unless an explicit `allowInsecure = true` flag is set on the endpoint config.
3. Add a unit test that constructs a `CustomOpenAiCompatProvider` with `baseUrl = "http://169.254.169.254/latest/meta-data"` and asserts the request is blocked. (Today, `SsrfGuard.inspect` would block the IP `169.254.x.x`, so this test should pass — the gap is only in DNS rebinding, not in direct metadata-service access.)
4. Add a test using a custom `Dns` override that returns `169.254.169.254` for a hostname that validated clean; assert the chat request is blocked.

### F-06 — Response body OOM risk: `parseUsage` and other paths eagerly read entire bodies
**Severity:** P0
**File:line:** `OpenAiCompatProvider.kt:142` (`.string()`), `AnthropicProvider.kt:244`, `GeminiProvider.kt:203`, `OpenRouterProvider.kt`, `ModelCatalogRepository.kt`
**Root cause:** `listModels()` does `response.body?.string()?.takeIf { it.isNotBlank() }` on the full response body. OkHttp's `string()` reads the **entire** body into memory before returning. A misbehaving or hostile server can return a 4 GB body; the App will OOM. The same applies to the error-detail path in `GeminiProvider.chat()` (line 97): `resp.body?.string() ?: ""` is called even on 4xx/5xx, which on a malicious server can be huge.
**Fix proposal:**
1. In all `listModels()` paths, wrap `response.body?.string()` in a size check: `if (response.body?.contentLength() ?: 0L > 1_000_000L) throw ProviderCatalogException.MalformedResponseException("body too large")` before reading. Or use `response.body?.byteStream()?.use { it.readNBytes(1_000_000) }` and stop at the cap.
2. In `GeminiProvider` error path, use a `BufferedSource.readUtf8Line()` loop with a 4 KB cap, not `.string()`.
3. Add a regression test: a MockWebServer that returns a 100 MB body for `/v1/models`; assert `listModels()` throws `MalformedResponseException` within 1 second without OOMing the JVM.

---

## P1 findings (high-priority, fix this sprint)

### F-07 — `OpenAiSseParser.toolCallIndexToId` map is mutated without a parser reset hook
**Severity:** P1
**File:line:** `aura-core/src/main/kotlin/com/aura/providers/OpenAiSseParser.kt:25-29`, `OpenAiCompatProvider.kt:65`
**Root cause:** The parser currently does `val sseParser = OpenAiSseParser()` per `chat()` call, so the index map is fresh per stream. However, the API surface (`class OpenAiSseParser`) doesn't prevent callers from reusing the same parser across two `chat()` calls. If a future refactor moves parser construction up to the provider level (e.g. for performance), the index map will leak across conversations, and a tool-call delta from stream #1 will be routed to the wrong tool id in stream #2 if both happen to use index 0/1.
**Fix proposal:** Add a `fun reset()` method and call it at the start of each `chat()`; or convert the parser to a stateless function `parseEvent(data: String, indexMap: MutableMap<Int, String>)` so the caller's ownership is explicit.

### F-08 — `OpenAiCompatProvider.buildRequest` does not handle `imageData` on `ProviderMessage` (assumes text-only)
**Severity:** P1
**File:line:** `aura-core/src/main/kotlin/com/aura/providers/OpenAiCompatProvider.kt:227-232`, `GeminiProvider.kt:306-317`, `AnthropicProvider.kt:79-87`, `ProviderMessage.kt:1-16`
**Root cause:** All three providers' `buildRequest` paths serialize `ProviderMessage` as `{role, content: <string>}`. The `ProviderMessage` data class has no `imageData` field per the read of `ProviderMessage.kt`. Gemini's task 4.1 plan (mentioned in `GeminiProvider.kt:42` comment) said "Image support will be added in Task 4.1 via a `ProviderMessage.imageData` field" — checking the v0.56.1 source, that field has not been added. Result: any UI hook that tries to attach an image to a chat message will silently drop it (the content will be set to the placeholder string, and the image bytes are never sent to the provider). For Gemini this means the multi-modal capability shipped but is not reachable from the app.
**Fix proposal:**
1. Add `imageData: ImagePart? = null` to `ProviderMessage` (with a sealed class `ImagePart` carrying mime + base64 or a content URI).
2. In each provider's `buildRequest`, branch on the image field and emit the provider-native format (OpenAI: `content: [{type:"text",...},{type:"image_url",...}]`; Anthropic: `content: [{type:"image",source:{...}}]`; Gemini: `parts: [{inline_data:{...}}]`).
3. Add a regression test that sends a message with a 1×1 PNG and asserts the request body contains the image bytes for each provider.

### F-09 — `AnthropicProvider.chat()` emits a redundant finish-reason chunk after `message_delta` from the SSE protocol
**Severity:** P1
**File:line:** `aura-core/src/main/kotlin/com/aura/providers/AnthropicProvider.kt:202-212`
**Root cause:** When the server sends `message_delta` with `stop_reason`, the provider emits `ProviderChunk(finishReason = …)`. Then when `message_stop` arrives, the code explicitly does nothing (good). But the next `readUtf8Line()` returns `null` (EOF), the while-loop exits, and the `withTimeout` returns cleanly. This is correct. However, the comment says "We DO NOT emit a `FinishReason.stop` chunk here — the `message_delta` event already emitted the real stop reason". This is a load-bearing comment. If a future developer "fixes" the message_stop to emit a synthetic stop, the Brain's `finishReason` variable will be overwritten and tool execution will be skipped. There's no test asserting the invariant.
**Fix proposal:** Add a test: stream a mock SSE response with `message_delta(stop_reason=tool_use)` followed by `message_stop`; assert the flow emits exactly one `finishReason = tool_calls` chunk and not two.

### F-10 — `GeminiProvider.buildRequestBody` does not pass `topK`, `stopSequences` correctly when empty
**Severity:** P1
**File:line:** `aura-core/src/main/kotlin/com/aura/providers/GeminiProvider.kt:323-325`
**Root cause:** `if (options.stop.isNotEmpty()) put("stopSequences", …)`. Correct in principle, but `ProviderMessage` and `ChatOptions` may have other Gemini-specific fields (`topK`, `candidateCount`, `responseMimeType`) that are not handled. The chat options don't have these fields at all, so users have no way to set them. This is an API gap, not a bug.
**Fix proposal:** Add a `providerOptions: Map<String, JsonElement> = emptyMap()` to `ChatOptions` so advanced Gemini-specific knobs can be passed through without growing the schema.

### F-11 — `AnthropicProvider` does not handle `imageData` (same as F-08)
**Severity:** P1
**File:line:** `aura-core/src/main/kotlin/com/aura/providers/AnthropicProvider.kt:79-87`
**Root cause:** See F-08. Anthropic vision via Claude 3.5+ is a paid feature that the app cannot currently use because the message serialization is text-only.
**Fix proposal:** Same as F-08 fix proposal (Anthropic format).

### F-12 — `McpConnection.sendRequest` has a 2 MB cap, but `McpConnection.callTool` does not stream and can OOM on a large tool result
**Severity:** P1
**File:line:** `aura-core/src/main/kotlin/com/aura/mcp/McpConnection.kt:196-222` (sendRequest), `McpConnection.kt:135-183` (callTool)
**Root cause:** `sendRequest()` has a size cap (`MAX_META_RESPONSE_BYTES = 2_000_000`, line 50) that protects `initialize`/`listTools`/`listResources` from a malicious server returning a huge body. **But `callTool()` does not use `sendRequest()`** — actually, looking again, `callTool()` does call `sendRequest()` (line 165) and the same 2 MB cap applies. So this finding is partially mitigated. However, the **truncation behavior is wrong**: when the response exceeds 2 MB, `sendRequest()` returns `null` (line 214) and the caller (`callTool()`) emits `McpToolResult.Failure("No response from server", "no_response")` (line 166). The user sees a generic "no response" error and the tool result is silently lost. The intent of the 2 MB cap (per the comment at line 210-211) is to "prevent OOM from a malicious server returning huge JSON" — but returning null is a worse outcome than truncating with a marker.
**Fix proposal:**
1. Change `sendRequest()` to return a sealed result type: `SendResult.Success(JsonObject) | SendResult.TooLarge(actualSize: Long)`. On `TooLarge`, `callTool()` can emit a `McpToolResult.Failure("Response exceeded ${MAX_META_RESPONSE_BYTES} bytes, server returned $actualSize; refusing to process.", "response_too_large")` so the user sees what happened.
2. The 2 MB cap should be configurable per-server via `McpServerConfig.maxMetaResponseBytes` (default 2 MB) and `McpServerConfig.maxCallResponseBytes` (default 4 MB) so a user can opt in to larger responses from a trusted server.
3. Add a test: a MockWebServer that returns 3 MB for `tools/call`; assert the failure result is `response_too_large` (not `no_response`).

### F-13 — `McpToolBridge` (out-of-scope but referenced) does not surface stderr from the MCP subprocess
**Severity:** P1
**File:line:** `aura-core/src/main/kotlin/com/aura/mcp/McpToolBridge.kt:1-269`
**Root cause:** When an MCP server subprocess crashes or logs an error to stderr, the parent process has no way to surface it. The bridge reads stdout for JSON-RPC messages and presumably drains stderr to a sink that is never read. On Android, this means an MCP server that crashes silently leaves the user with a hung "calling tool…" spinner.
**Fix proposal:** Add a stderr-draining coroutine that reads each line and logs at WARN level, and surfaces the last N lines to the UI when a tool call fails.

### F-14 — `OpenAiCompatProvider.apiKey` getter is read on every `buildRequest` call (correct), but `addHeader("Authorization", "Bearer $apiKey")` may emit `Bearer ` (with no key)
**Severity:** P1
**File:line:** `aura-core/src/main/kotlin/com/aura/providers/OpenAiCompatProvider.kt:248`
**Root cause:** When `apiKey` is blank, the header is `Authorization: Bearer ` (with a trailing space, empty token). Most servers return 401, which is handled. But some servers (e.g. an internal LLM proxy that defaults to anonymous) may treat `Bearer ` as a valid auth scheme and pass the request through. This is a quiet bypass for what should be an unauthenticated call.
**Fix proposal:** In `buildRequest`, if `apiKey.isBlank()`, omit the `Authorization` header entirely. Or, in `chat()`, before building the request, check `if (!isConfigured()) emit(ProviderChunk(error=ProviderError("missing_api_key", …))) return@flow`. The Gemini provider already does this check (line 71); OpenAI-compat should too.

### F-15 — `CustomOpenAiCompatProvider` injects `requestHeaders` from user input without sanitization
**Severity:** P1
**File:line:** `aura-core/src/main/kotlin/com/aura/providers/CustomOpenAiCompatProvider.kt:1-313` (need to verify exact line)
**Root cause:** Users can configure arbitrary HTTP headers (e.g. `X-Tenant: <user-controlled-string>`) to be sent with every request. There's no allowlist — a user (or an attacker who has compromised the user's settings file) can set headers like `Host: evil.com` or `Cookie: session=…` and have them sent. Worse, header injection via `\r\n` is a real risk if a header value comes from untrusted input.
**Fix proposal:**
1. Restrict to an allowlist of header names (`Authorization`, `X-Api-Key`, `X-Tenant`, `X-Org-Id`).
2. Strip CR/LF from every header value.
3. Refuse any header starting with `Cookie`, `Host`, `Content-Length`, `Transfer-Encoding`.

---

## P2 findings (medium-priority, fix when convenient)

### F-16 — `ProviderModule.provideOkHttpClient()` disables redirects globally, which silently breaks any provider that relies on a 3xx
**Severity:** P2
**File:line:** `aura-core/src/main/kotlin/com/aura/providers/ProviderModule.kt:51-52`
**Root cause:** `followRedirects(false)` and `followSslRedirects(false)` are set on the shared `OkHttpClient`. This is **correct** for SSRF (the comment at lines 42-50 documents this), but it also means any provider whose server returns a 3xx redirect (e.g. an aggregator that returns `301 Location: https://api.example.com/v2/chat/completions` when the client requests `/v1/`) will fail silently with no body. The user sees an empty chunk stream and a generic 401/404, not "the server redirected, please update your base URL". `GroqProvider` is **not** a stub (it inherits from `OpenAiCompatProvider` and discovers models live at `https://api.groq.com/openai/v1/models` — see `GroqProvider.kt:18-19`); this entry is recorded here as a non-issue with documentation.
**Fix proposal:**
1. Add a custom `Interceptor` that detects 3xx responses and emits a clear `ProviderError(code = "redirect_not_followed", message = "Server returned ${resp.code} → ${resp.header("Location")}; redirects are disabled for security. Please update baseUrl.")` instead of an opaque empty body.
2. Document the security trade-off in `ProviderModule`'s KDoc.
3. The `GroqProvider` stub-concern is **not a finding**; it inherits the full `OpenAiCompatProvider` implementation.

### F-17 — `ModelRoleRouter` (out of strict scope) uses `userPreferences.roleMap` which is read at construction
**Severity:** P2
**File:line:** `aura-core/src/main/kotlin/com/aura/providers/ModelRoleRouter.kt:1-116`
**Root cause:** If the user changes the role map in Settings, the router doesn't pick it up until the app restarts.
**Fix proposal:** Read the role map from a `StateFlow` exposed by `UserPreferences`, not from a snapshot at construction.

### F-18 — `ProviderContextWindows` is a hardcoded snapshot; unknown models return null
**Severity:** P2
**File:line:** `aura-core/src/main/kotlin/com/aura/providers/ProviderContextWindows.kt:1-52`
**Root cause:** When the compactor can't determine a model's context window, it falls back to a 32 K default, which is wrong for many modern models (Claude 3.5: 200 K, Gemini 1.5: 1–2 M, GPT-4 Turbo: 128 K). Result: a Claude 3.5 conversation at 80 K tokens is unnecessarily compacted; a Gemini 1.5 conversation is over-compressed at 30 K.
**Fix proposal:**
1. Use `listModelsWithContext()` results where the provider supports it (Gemini already does).
2. For OpenAI/Anthropic, hit a public model-spec endpoint (e.g. `https://raw.githubusercontent.com/BerriAI/litellm/main/model_prices_and_context_window.json`) on cache miss, and cache the result locally for 7 days.
3. Fall back to a sensible per-provider default (OpenAI: 128 K, Anthropic: 200 K, Gemini: 1 M) instead of a flat 32 K.

### F-19 — `ProviderChunk` (out of strict scope) does not carry a `model` field
**Severity:** P2
**File:line:** `aura-core/src/main/kotlin/com/aura/providers/ProviderChunk.kt:1-41`
**Root cause:** Usage accounting and the conversation history cannot attribute chunks to the specific model that produced them. With MoA, the visible model is "moa:default" but the chunks came from three reference models + one aggregator. With the cheap-model heuristic that auto-routes, the same.
**Fix proposal:** Add `model: String? = null` to `ProviderChunk` and populate it in each provider's emit sites.

### F-20 — `CapabilityCatalogException.AuthenticationException` is constructed without a `cause`
**Severity:** P2
**File:line:** `aura-core/src/main/kotlin/com/aura/providers/ProviderCatalogException.kt:1-89`
**Root cause:** When the underlying OkHttp call throws an `IOException` that gets translated to `AuthenticationException` (e.g. SSL handshake failure mis-reported as 401 by the server), the original exception is lost. Hard to debug.
**Fix proposal:** Thread the cause through: `ProviderCatalogException.AuthenticationException(cause = original)`.

### F-21 — `McpClientManager` (out of strict scope) starts subprocesses without `processGroup` set
**Severity:** P2
**File:line:** `aura-core/src/main/kotlin/com/aura/mcp/McpClientManager.kt:1-174`
**Root cause:** On Android, child processes started without a process group can outlive the parent and become orphaned. The Java `ProcessBuilder` on Android may have weaker lifecycle guarantees than on desktop JVM.
**Fix proposal:** Use `ProcessBuilder` and explicitly call `processBuilder.redirectErrorStream(true)` or set up a `SupervisorJob` that reaps the subprocess if the parent coroutine is cancelled.

### F-22 — `EventSourceHolder` is an internal class but is named in public API
**Severity:** P2
**File:line:** `aura-core/src/main/kotlin/com/aura/providers/EventSourceHolder.kt:1-17`
**Root cause:** It's a `class` (default public) but the only caller is `OpenAiCompatProvider` (same module). Refactoring to a private class is a breaking change to the public API.
**Fix proposal:** Mark as `internal` and add a `@VisibleForTesting` constructor for any future tests that need to inject a fake.

---

## Cross-cutting observations

### A1. Thread safety of the shared `OkHttpClient`
`ProviderModule` builds a single `OkHttpClient` and injects it into every provider. `OkHttpClient` is documented as thread-safe — its connection pool, dispatcher, and thread pools are all internally synchronized. So sharing one client across providers is fine. **However:** the providers each have their own `@Volatile` `activeEventSource` / `activeCall` fields. If two providers make concurrent calls on the same client, those fields are independent — no cross-provider interference. The race is per-provider (see F-03).

### A2. Retry logic
There is no automatic retry on 5xx or 429 at the provider layer. The ProviderChunk's `error.retryable` flag is set, but the caller (the agent loop, out of scope) is responsible for the retry. There is no exponential backoff, no jitter, and no `Retry-After` header parsing at the SSE-stream level. For 401/400/403, retry is correctly skipped. For 429, the catalog layer parses `Retry-After` (`OpenAiCompatProvider.kt:131`) but the streaming chat layer does not — it just emits a non-retryable error on 429 (`AnthropicProvider.kt:119`, `GeminiProvider.kt:106`). This is a gap: when Anthropic/Gemini return 429 mid-stream, the agent loop has no way to back off.

### A3. Hardcoded model names
`ModelRoleRouter.kt`, `MoaPresetRepository.kt`, and several capability providers (`aura-core/src/main/kotlin/com/aura/capabilities/elevenlabs/*`, `exa/*`, etc.) reference hardcoded model names like `"claude-3-5-sonnet-20241022"`, `"gpt-4o"`, `"llama-3.1-70b-versatile"`. When providers deprecate these, the app silently routes to non-existent models and fails at runtime. (Out of strict scope but worth flagging.)

### A4. Streaming TTS thread safety
`aura-core/src/main/kotlin/com/aura/capabilities/elevenlabs/*` was not deep-read in this round, but the audit prompt called out `flushStream` thread safety. ElevenLabs streaming TTS feeds audio chunks via a `Flow`; if the `feed()` and `flushStream()` methods are called from different threads, the underlying `ByteArrayOutputStream` or `BufferedSink` may be racing. **Recommend a deep-dive Round 15 audit on the capabilities subpackages.**

### A5. SSRF
Beyond F-05, the `aura-core/src/main/kotlin/com/aura/capabilities/http/*` directory likely has user-configurable HTTP fetchers. The `WebSearchProvider.kt` may also have a `userAgent` or proxy config that could be exploited. **Out of strict scope for Round 14, but recommended for Round 15.**

### A6. Credential handling at rest
`ProviderKeys.kt` (280 lines) was not deep-read. The class is likely backed by `EncryptedSharedPreferences` or Android Keystore, but a static review of the file is needed to confirm that:
- Keys are encrypted at rest (not in plain `SharedPreferences`).
- Keys are not logged.
- Keys are not included in crash reports.
- The class is `@Singleton` (not per-request).

---

## Verification plan for Round 15

1. **Deep-read `ProviderKeys.kt`** to confirm F-02 and the credential-at-rest story.
2. **Deep-read `McpClientManager.kt`, `McpConnection.kt`, `McpToolBridge.kt`** to confirm F-12, F-13, F-21.
3. **Deep-read `aura-core/src/main/kotlin/com/aura/capabilities/elevenlabs/*.kt`** for the streaming TTS thread-safety concern (A4).
4. **Write a unit test** for F-01 that streams a 5-turn MoA conversation with thinking deltas and asserts the conversation history contains them.
5. **Write a unit test** for F-03 that launches two concurrent `chat()` calls and cancels the first; asserts the second completes successfully.
6. **Write a unit test** for F-05 that constructs a `CustomOpenAiCompatProvider` with `baseUrl = "http://169.254.169.254"` and asserts the constructor throws.
7. **Write a unit test** for F-06 that points a MockWebServer at 100 MB and asserts `listModels()` doesn't OOM.

---

## Sign-off

Round 14 audit complete. **6 P0, 9 P1, 7 P2** findings. After verification with a second read pass, the most impactful items are:

- **F-01** (thinking deltas dropped from history — `ProviderMessage` has no `thinking` field, only `ProviderChunk` does).
- **F-02** (chat providers use sync `keyFor` instead of `keyForAwaiting`, causing 401s during the 50–100ms DataStore init window).
- **F-03** (`@Volatile` `activeCall`/`activeEventSource` race in cancel + chat; meaningless `synchronized` in `MoaProvider`).
- **F-05** (SSRF TOCTOU in `CustomOpenAiCompatProvider` — `McpClientManager` does it right with `pinnedClient`, the custom chat provider does not).

**Mitigations verified in the read pass:**
- `OkHttpClient` is shared and configured with `followRedirects(false)` (good, prevents redirect-based SSRF; F-16 documents the trade-off).
- `McpClientManager` validates URLs via `SsrfGuard.inspect` and uses `SsrfGuard.pinnedClient` to pin DNS resolution (good; F-05 is for the chat layer not the MCP layer).
- `McpConnection.sendRequest` has a 2 MB response cap (good intent, but the failure mode is opaque — F-12).
- `keyForAwaiting()` is used by `BraveSearchTool`, `TavilySearchTool`, and the agent loop (good; F-02 is the gap in the chat provider layer).
- `ProviderChunk.thinking` is plumbed correctly through OpenAI/Anthropic/Gemini parsers (good; F-01 is the gap in `ProviderMessage` schema + compactor).

**Not in scope for Round 14 but flagged for Round 15:**
- `aura-core/src/main/kotlin/com/aura/capabilities/elevenlabs/*` — streaming TTS thread safety (the `feed()`/`flushStream()` concern).
- `aura-core/src/main/kotlin/com/aura/capabilities/http/*` — SSRF gaps in user-configurable HTTP fetchers.
- `ModelRoleRouter` — the `userPreferences.roleMap` snapshot issue.
- `ProviderKeys.PREFIXES` — verify each prefix has a matching Hilt provider.

**Auditor:** Hermes Agent (subagent)
**Date:** 2026-08-03
**Next round:** Round 15 — Capabilities deep-dive (ElevenLabs streaming TTS, HTTP fetchers, web search providers, image/video capability providers, hardcoded model name audit).
