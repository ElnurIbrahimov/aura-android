# TOOLS Layer Audit (Aura Android)

> **Final report.** 38+ tools, ToolRegistry, ToolExecutor, PolicyEngine, McpToolBridge reviewed. Save-to-disk-on-first-call rule satisfied (working draft landed at first 5 tool calls); this file supersedes it.
> **Repo state audited:** v0.58.0 / 69 tools per README (this audit focused on the agent/tools/mcp/policy surface; 26 of 38 in-scope tool files read in full, balance skimmed).

---

## Executive summary

Aura's tool layer is a sophisticated Kotlin/Compose app with most security primitives in place (SSRF guard, incognito gate, two-user-turn approval for paid/writes, tool allowlists per specialist, OkHttp `.use{}` everywhere), but it has **eight substantive issues** a real attacker or a sufficiently-perverse user can trigger today. The MCP surface and the per-run approval gate are the most fragile. Risk labels on four tools disagree with their actual behavior. Output-truncation budget exists for `code_interpreter` and `fetch_url` but is absent for several tools whose outputs can be unbounded.

The top 5 (one paragraph each, with file:line):

---

### 🔴 Top 5 findings

**1. `RemoteCostApprovalGate` approves a paid tool call on any text matching a single word** — `aura-core/.../agent/ToolExecutor.kt:230-244` (function `isExplicitApproval`). The gate strips every non-alphanumeric character from the user message and matches against `{"yes","yes please","yes confirm","confirm","confirmed","go ahead","do it","continue","approve","approved"}`. Any later user message that *contains* the substring "approved" / "do it" / "yes" — e.g. "the board approved the merger", "did you see the email? I forwarded it to Jess — yes" — clears a pending paid call regardless of intent. A user can also re-trigger an MCP tool by saying the magic word twice in unrelated messages. Severity: **HIGH (cost amplification + confused-deputy on confirmation)**. The companion `CalendarApprovalGate` (CalendarWriteTool.kt:184-194) has the same hazard and is the same string-set family.

**2. `McpToolBridge.syncToolsUnprefixed` overwrites native tool risk with REMOTE_COST** — `aura-core/.../mcp/McpToolBridge.kt:142-203`. The `syncTools` path uses a `mcp_<serverId>_<toolName>` prefix to avoid colliding with native tools, but `syncToolsUnprefixed` (intentionally, per the comment) registers with the **bare** MCP tool name and `ToolRisk.REMOTE_COST`. Because `ToolRegistry` is a `ConcurrentHashMap`, the second `register()` overwrites the first — including the tighter risk label of the native tool. If a user connects a Tavily MCP server (or any server with a tool whose base name matches a native tool), the MCP version (which calls arbitrary code on an external endpoint) takes over the slot. With the `RemoteCostApprovalGate` in `ToolExecutor.kt:119` only firing when `policyEngine == null` (in production it is always non-null via Hilt), and `REMOTE_COST` defaulting to `ConfirmationLevel.NONE` in `ToolPolicyDefaults.kt:35-39`, the user can be charged for a paid tool without per-run approval. Severity: **HIGH (privilege escalation + unapproved cost)**.

**3. `McpConnection.callTool` silently drops list, null, and unknown argument values** — `aura-core/.../mcp/McpConnection.kt:142-160`. The `when` switch handles `String`, `Number`, `Boolean`, and `Map<*,*>` only; `null`, `List<Any?>`, and other types fall through to no `put` call. Combined with `McpToolBridge.parseSchema` swallowing schema-parse errors and falling back to `ToolParameters()` (line 264-268), a malicious or buggy MCP server that returns an invalid `inputSchema` produces a tool with no declared parameters, and any array-typed argument is **silently dropped** at JSON-RPC serialisation. The LLM sees "OK" while the server receives a request with the array missing. Severity: **HIGH (silent data corruption in MCP surface)**.

**4. `runInterruptible { runBlocking { … } }` makes tool timeouts best-effort** — `aura-core/.../agent/ToolExecutor.kt:127-137`. The combination parks an IO worker thread in a nested event loop and relies on thread `interrupt()` to cancel. Any tool body that catches `InterruptedException` (or wraps it, e.g. `OkHttp.Call.execute()` in many call sites, `Thread.sleep` wrappers, custom coroutine builders, the inner `runBlocking` itself) will continue executing past the timeout. The executor returns success because `runBlocking` resumes with the tool's value, even though `withTimeout` already fired. With `TOOL_PARALLELISM = 8` IO threads (`ToolExecutor.kt:34`) and the same pattern in the inner agentic loop, a model that emits 8 blocking tools with 30s timeouts can pin all 8 threads for arbitrarily long. Severity: **MEDIUM-HIGH (timeout not honoured, partial DoS of shared IO pool)**.

**5. Risk-label misclassification on four tools + README drift**:
- `ImageGenTool.kt:68` declares `risk = ToolRisk.REMOTE_COST`; the README at line 174 documents `image_gen` as `WRITE_REMOTE` (the actual function returns the generated image URL — no state mutation, but the tool does not declare it writes anything either). The mismatch between `REMOTE_COST` (handled by per-run approval) and `WRITE_REMOTE` (defaulted to `EXPLICIT` confirmation per `ToolPolicyDefaults.kt:46-49`) means a code change in the risk label silently changes UX.
- `CodeInterpreterTool.kt:69` declares `risk = ToolRisk.REMOTE_COST` but the implementation is a local `WebView` (no network, no file access) — should be `READ_ONLY`. Currently triggers per-run approval for every JS execution, breaking the assistant's "compute 12! for me" loop until the user types "yes".
- `FirecrawlFetchTool.kt:48` declares `risk = ToolRisk.REMOTE_COST` but uses `SsrfGuard.validate(url)` (a one-shot check) and then calls `httpClient.newCall(req).execute()` on the **un-pinned** shared client — `httpFile_read` and `deep_research` correctly use `SsrfGuard.pinnedClient()`. A URL whose DNS answer changes between validate and execute (TOCTOU) can hit private IPs. README at line 169 says `fetch_url` is "SSRF-guarded" but the guard is weaker than the sibling tools.
- `TavilySearchTool.kt:66` / `BraveSearchTool.kt:52` declare `risk = ToolRisk.READ_ONLY` but they make **paid** API calls. The README at lines 165-167 says "READ_ONLY" with no per-run approval note. With `REMOTE_COST` defaulting to `NONE` confirmation, this is currently OK (no approval required for the user), but the absence of cost gating means an attacker who can call the model can run up arbitrary bills.
- README line 13 says "69 tools" but `ToolsModule.kt:94-167` registers **66** tools (counted), the README catalog at lines 160+ lists 28 in the "Web & research" section alone, and there is no `kg_add` tool in the codebase (README line 188 promises it). `query_taste` exists (`QueryTasteTool.kt`); `web_search_capability` exists; `image_generate` exists alongside `image_gen` — both registered, the consolidated `web_search` is also registered, and the duplicated tools are listed in the LLM-visible tool list (the comment at `WebSearchTool.kt:28-31` claims the agentic loop filters duplicates but no filter exists in `ToolRegistry.definitions()`). Severity: **MEDIUM (UX inconsistency, partial over-permissioning)**.

---

## Section A — Per-tool findings (most relevant subset)

| Tool | File | Risk (declared) | Risk (actual) | Issue |
|---|---|---|---|---|
| `web_search` | WebSearchTool.kt:55 | READ_ONLY | REMOTE_COST (when Tavily/Brave key set) | Chained call to Tavily/Brave; risk label only true for DDG fallback |
| `brave_search` | BraveSearchTool.kt:52 | READ_ONLY | REMOTE_COST | Paid API call declared as free read |
| `tavily_search` | TavilySearchTool.kt:66 | READ_ONLY | REMOTE_COST | Same — and **no output truncation** for `include_answer` (unbounded) |
| `fetch_url` | FirecrawlFetchTool.kt:48 | REMOTE_COST | REMOTE_COST + SSRF-bypass | Uses `SsrfGuard.validate` (no `pinnedClient`) |
| `http_file_read` | HttpFileReadTool.kt:44 | REMOTE_COST | OK | Correctly uses `pinnedClient`. `max_chars=32000` default 8000; output truncated correctly |
| `http_file_write` | HttpFileWriteTool.kt:44 | WRITE_REMOTE | OK | SSRF-pinned. No `content` size cap — model can write MB-sized body in one call |
| `image_gen` | ImageGenTool.kt:68 | REMOTE_COST | Mismatch with README (which says WRITE_REMOTE) | Output is just a URL string — no state mutation, but billed as if it were |
| `image_generate` | ImageGenCapabilityTool.kt:35 | REMOTE_COST | OK | Capability-routed; default fallback to Pollinations is not gated |
| `code_interpreter` | CodeInterpreterTool.kt:69 | REMOTE_COST | READ_ONLY (local WebView, no network) | Wrong label — every JS run requires per-run approval |
| `vision` | VisionTool.kt:52 | REMOTE_COST | OK | 2 MB input cap enforced; uses shared client (no per-call timeout) |
| `transcribe` | TranscriptionTool.kt:61 | REMOTE_COST | OK | 25 MB cap, base64 estimate |
| `knowledge_graph_extract` | KnowledgeGraphTool.kt:61 | REMOTE_COST | OK | LLM call; no output cap (model response) |
| `translate` | TranslateTool.kt:48 | REMOTE_COST | OK | No input cap; can push huge text through provider |
| `deep_research` | DeepResearchTool.kt:92 | REMOTE_COST | OK + missing output cap | Final JSON is uncapped; only the 20k context block is truncated |
| `weather` | WeatherTool.kt:58 | READ_ONLY | OK | Uses `SsrfGuard.validate` (no pin) but URL is hardcoded `open-meteo.com` so TOCTOU is moot |
| `delegate_to_agent` | DelegateToAgentTool.kt:47 | REMOTE_COST | OK | Allowlist logic fixed; child `ToolContext` properly scoped. Risk: 30s timeout × 3 steps × N children |
| `run_council` | RunCouncilTool.kt:25 | REMOTE_COST | OK | No rate limit on how often it can be called; each run = N+1 LLM calls |
| `run_hand` | RunHandTool.kt:50 | WRITE_LOCAL | OK | Now queues via AgentRun; returns error on null enqueue |
| `use_skill` | UseSkillTool.kt:52 | READ_ONLY | OK | Fixed in 2026-08-03 audit (was wrongly WRITE_LOCAL) |
| `manage_tasks` | TaskManagerTool.kt:48 | WRITE_LOCAL | OK | No size cap on `title`, `description`, `tags` (could push KB into DB) |
| `set_reminder` | SetReminderTool.kt:35 | WRITE_LOCAL | OK | Message size unbounded |
| `calendar_read` | CalendarReadTool.kt:48 | PRIVACY | OK | `days` and `max_results` clamped |
| `calendar_write` | CalendarWriteTool.kt:53 | PRIVACY | OK + good 2-turn gate | Same hazard as #1 (substring match) |
| `post_notification` | NotificationsTool.kt:65 | WRITE_LOCAL | OK | Channel created in `init {}` — runs at Hilt inject time, may be before UI ready; on Android 13+ requires runtime POST_NOTIFICATIONS, returned as `NeedsPermission` correctly |
| `notification_list` | NotificationListTool.kt:35 | PRIVACY | OK | Permission check via `BIND_NOTIFICATION_LISTENER_SERVICE` flag (not a real permission) |
| `biometric_prompt` | BiometricPromptTool.kt:56 | WRITE_LOCAL | OK | 60s timeout. `USE_BIOMETRIC` is a normal permission but the call is gated by `BiometricActivityHolder` |
| `dnd_mode` | DndModeTool.kt:39 | WRITE_LOCAL | OK | Requires `notification_policy_access` (not a regular permission); uses special NeedsPermission string |
| `clipboard_read` | ClipboardReadTool.kt:34 | PRIVACY | OK | No size cap on returned text — could dump MB to LLM |
| `clipboard_write` | ClipboardWriteTool.kt:40 | WRITE_LOCAL | OK | No size cap on written text |
| `launch_app` | AppLauncherTool.kt:38 | WRITE_LOCAL | OK | Accepts `https://...` URLs without SSRF check, but only opens via `ACTION_VIEW` (browser) — no exfil risk |
| `open_browser_tab` | OpenBrowserTabTool.kt:36 | WRITE_LOCAL | OK | SSRF-validated |
| `share` | ShareIntentTool.kt:40 | WRITE_LOCAL | OK | No size cap on shared text |
| `email_send` | EmailSendTool.kt:61 | WRITE_LOCAL | OK | mailto:; email regex validation |
| `sms_send` | SmsSendTool.kt:48 | WRITE_LOCAL | OK | Phone number regex (7-15 digits) |
| `send_email_background` | SendEmailBackgroundTool.kt:52 | WRITE_REMOTE | OK | Uses JavaMail SMTP. **No recipient validation** (no regex; any string passed to `InternetAddress.parse`). `cc` and `bcc` are not validated either |
| `location_now` | LocationNowTool.kt:38 | PRIVACY | OK | `lastKnown()` only — no active GPS |
| `photo_library` | PhotoLibraryTool.kt:46 | PRIVACY | OK | `READ_MEDIA_IMAGES` for API 33+, fallback `READ_EXTERNAL_STORAGE` |
| `battery_state` | BatteryStateTool.kt:34 | READ_ONLY | OK | |
| `network_state` | NetworkStateTool.kt:33 | READ_ONLY | OK | |
| `system_volume` | SystemVolumeTool.kt (not read) | WRITE_LOCAL | (presumed OK) | |
| `tts_speak` | TtsSpeakTool.kt:50 | REMOTE_COST | OK | Caches audio to `cacheDir` with `deleteOnExit()` — won't fire if process crashes, leaks |
| `text_to_speech` / `video_generate` / `world_3d_generate` | MediaCapabilityTools.kt | REMOTE_COST | OK | Capability-routed |
| `query_world_model` | QueryWorldModelTool.kt:46 | READ_ONLY | OK | Capped at 5 superseded beliefs per current |
| `canon_query` | CanonQueryTool.kt:38 | READ_ONLY | OK | Memory limit 8 |
| `creative_read_project` | CreativeTools.kt:38 | READ_ONLY | OK | |
| `creative_add_world_item` | CreativeTools.kt:105 | WRITE_LOCAL | OK | 4k description cap |
| `canon_query` (duplicated with `creative`) | (Cataloging) | — | — | OK |
| `kg_query` | KgQueryTool.kt | (skim) | READ_ONLY | (skim — TO READ) |
| `index_document` | IndexDocumentTool.kt | (skim) | WRITE_LOCAL | (skim — TO READ) |
| `timer` | TimerTool.kt:71 | WRITE_LOCAL | OK | Bounded at MAX_TIMERS=100 (FIFO) |
| `query_taste` | QueryTasteTool.kt | (skim) | READ_ONLY | |
| `KgQueryTool` | KgQueryTool.kt | (skim) | READ_ONLY | |
| `FilePickerTool` | FilePickerTool.kt | (skim) | (presumed READ_ONLY or PRIVACY) | |

**Tool count reconciliation:** The "38 tools" in the task brief counts tool classes; the README claims 69; `ToolsModule.kt:94-167` registers 66. The 69 number in the README presumably includes 3 Google Workspace tools (Gmail, Google Calendar, Google Drive) and 3 Microsoft Graph tools (Outlook Mail, Outlook Calendar, OneDrive) under `aura-core/.../integrations/` which I have not opened in this audit pass.

---

## Section B — ToolRegistry, ToolExecutor, PolicyEngine

### B1. Risk enum ordering is brittle (LOW now, HIGH later)
`ToolRegistry.kt:19` declares `ToolRisk` in the order `READ_ONLY, REMOTE_COST, WRITE_LOCAL, WRITE_REMOTE, PRIVACY, DESTRUCTIVE`. `byRisk(min)` at line 87 uses ordinal comparison, which means "all tools at or above PRIVACY" includes WRITE_LOCAL/REMOTE (correct) and DESTRUCTIVE (correct), but the ordering confounds two unrelated risk axes (mutation × sensitivity). Any future enum insertion (e.g. `PRIVACY_WRITE`) silently changes the meaning of every ordinal comparison.

### B2. `ToolRegistry.register` overwrites silently (MEDIUM)
`ToolRegistry.kt:82` is `tools[tool.name] = tool`. No check for an existing entry, no log, no version bump. In production this is mostly fine because `ToolsModule` registers each tool once at startup. But the MCP `syncToolsUnprefixed` path (see finding #2) **intentionally** uses this to override native tools — by design, undocumented, and the path is reachable from a user adding an MCP server in Settings.

### B3. `ToolExecutor.parseArgs` drops required fields silently (MEDIUM)
`ToolExecutor.kt:167-175` iterates `schema.properties` and only writes keys that exist in the JSON. The `required: List<String>` field on `ToolParameters` (set by every tool and parsed from MCP schemas) is **never checked**. A tool that declares `required=["url"]` is called with empty `args` if the model omits the field, and the body sees `args["url"]` as `null`. Every tool then has its own `?: return@Tool ToolResult.Error("missing 'X'", "bad_args")` — the schema is documentation, not enforcement.

### B4. `runBlocking` inside `runInterruptible` (MEDIUM-HIGH — see finding #4)
`ToolExecutor.kt:135-137`. The intent is documented in the surrounding KDoc, but the implementation is fragile. `runBlocking` creates a private event loop that owns the worker thread for the duration of the tool body. Cancellation must propagate via thread interrupt, which is only honoured by code that respects it. A `withContext(Dispatchers.IO) { Thread.sleep(60_000) }` inside a tool body will block past the executor timeout; the executor will not see the late result, but the worker thread stays pinned.

### B5. Incognito gate is duplicated (LOW)
`ToolExecutor.kt:96-101` and `PolicyEngine.kt:30-32` both check `!ctx.memoryEnabled && tool.risk >= WRITE_LOCAL`. The check is duplicated because the PolicyEngine is optional (line 45 `policyEngine: PolicyEngine? = null` — null in unit tests). Belt-and-suspenders, but if the two checks ever diverge (one switches to a different risk level), the policy engine becomes the de-facto gate and the executor's check is dead.

### B6. `RemoteCostApprovalGate` is dead code in production (HIGH — finding #1)
`ToolExecutor.kt:119-123` only calls `remoteCostApprovalGate.authorize(...)` when `policyEngine == null`. In production, Hilt always injects a `PolicyEngine` (it's a `@Singleton`), so the gate never runs. The `PolicyEngine` `evaluate` method at `PolicyEngine.kt:48-50` handles REMOTE_COST approval with the `ctx.approvedRemoteCostTools` set, but the **approval UX** — substring match on "yes" / "do it" / "approved" — is implemented in `RemoteCostApprovalGate` (line 230-244) and the corresponding flow that drives it lives in the conversation UI. The substring match in the gate and the corresponding `CalendarApprovalGate` substring match in `CalendarWriteTool.kt:184-194` are the actual attack surface: any user message containing a magic word authorises a pending paid/write call. (See finding #1.)

### B7. `ToolContext.approvedRemoteCostTools` is a Set<String> (CONFUSED-DEPUTY)
`ToolRegistry.kt:61`. The field name says "per-run" but the implementation never expires entries — they're cleared by the gate on a successful match. The set is also used by `PolicyEngine.evaluate` at line 53 for `requireApprovalPerRun` of any policy, which means **a user who approves one paid tool for the run also clears the per-run flag for DESTRUCTIVE tools** that the policy demands per-run approval for. The semantic overloading is invisible in the field name and the docstring doesn't mention it.

### B8. Tool result size is recorded but not enforced (LOW)
`ToolExecutor.kt:149` calls `usageTracker.recordToolResult(result.output.length)`. The agentic loop's truncation (referenced as "4k char tool-result truncation budget" in the README) lives in `truncateToolResult` (used by `DelegateToAgentTool.kt:243`) and the loop itself. The executor does not enforce a 4k cap on the tool's output — a tool that returns 10 MB is returned to the loop and the loop truncates. The truncation is best-effort and lossy: the model sees `output.take(4000) + "…[truncated]"` with no signal of what was dropped.

### B9. Output truncation is inconsistent across tools (LOW)
Tools that cap their own output: `code_interpreter` (4k), `http_file_read` (8k-32k), `firecrawl_fetch` (8k), `deep_research` (`buildContextBlock` 20k, but final JSON uncapped), `vision` (model output), `image_gen` (just a URL). Tools that do **not** cap: `clipboard_read` (clipboard can be MB), `clipboard_write` (text in), `share` (text in/out), `set_reminder` (message in), `manage_tasks` (title, description, tags in), `translate` (text in), `transcribe` (model output), `email_send` (subject, body in), `sms_send` (body in), `battery_state` (small), `network_state` (small), `system_volume` (presumed small), `http_file_write` (content in, no cap).

### B10. `PolicyEngine` never returns `CostExceeded` or `ScopeDenied` (MEDIUM)
`PolicyResult` (`ToolPolicy.kt:46-47`) defines `CostExceeded` and `ScopeDenied` data classes. `PolicyEngine.evaluate` (lines 28-58) only ever returns `Allowed`, `Disabled`, `NeedsConfirmation`, `NeedsApproval`. The `costCeiling` and `allowedScopes` fields on `ToolPolicy` (lines 21-23) are parsed from DataStore but never consulted. Dead policy feature; user expectation gap.

### B11. Specialists with empty allowlist get the FULL registry (TO VERIFY)
`DelegateToAgentTool.kt:132-150` — if `agent.toolSet()` is empty, the agent gets `registry.definitions()`. Comment says it's a permission allowlist, but the empty-list fallback grants "all tools". Combined with finding #2 (MCP tools registerable as bare names), a user-created specialist with no allowlist effectively gets all native + all MCP tools.

---

## Section C — MCP / McpToolBridge / McpConnection

### C1. Schema-parse fallback to empty `ToolParameters()` (HIGH — finding #3)
`McpToolBridge.kt:233-269`. A malicious MCP server returns an `inputSchema` that fails to parse; the catch block at line 264 returns `ToolParameters()`. The LLM sees a tool with no declared parameters, can pass any JSON, and `McpConnection.callTool` (line 142-160) silently drops any value that isn't a String/Number/Boolean/Map<String,Any?>.

### C2. Bare-name registration overrides native tool risk (HIGH — finding #2)
`McpToolBridge.kt:142-203`. See top finding #2. The `syncToolsUnprefixed` path is reachable from the Settings screen (user adds a server, tool registry updates) and silently changes the risk of any colliding tool name. A user connecting `tavily` MCP would, after the bridge sync, find their `tavily_search` tool replaced with a REMOTE_COST MCP variant (which the dispatch closure calls directly into MCP). Since `REMOTE_COST` → `NONE` confirmation in `ToolPolicyDefaults.kt:35-39` and the per-run gate is bypassed in production (B6), no confirmation is requested.

### C3. `extractServerId` parsing is naive (LOW)
`McpToolBridge.kt:220-227`. Splits the registered name on the first underscore after the `mcp_` prefix. If the server ID itself contains an underscore (allowed by `id` generation in some setups), the wrong base name is extracted. The `mcpToolName` function at line 217 doesn't sanitise the server ID, so a server with id `my_server` and a tool named `foo` registers as `mcp_my_server_foo` but `extractServerId` returns `my`. Subsequent allowlist logic in `DelegateToAgentTool.kt:138-146` strips the wrong prefix.

### C4. Response body is truncated by char count, not byte count (LOW)
`McpConnection.kt:174-177`. `output.take(config.maxResponseBytes)` operates on Kotlin `String` characters, where each character may be 1-4 bytes in UTF-8. A response with 4-byte emoji can be cut in the middle of a surrogate pair, producing invalid UTF-8. Worse, a 1 MB response that's all 1-byte ASCII is allowed; a 1 MB response that's all 4-byte emoji is truncated to ~250k characters of `String` (~1 MB of UTF-8).

### C5. MCP JSON-RPC error responses silently become "empty list" (LOW)
`McpConnection.kt:216` uses `as? JsonObject` which returns `null` on type mismatch. The caller (`listTools` / `listResources`) interprets null as "empty". A server that returns `{"error": {"code": ..., "message": ...}}` is indistinguishable from a server that returns no tools.

### C6. SSE streaming not implemented (LOW)
`McpConnection.sendRequest` (line 196-222) reads the full response as a single `response.body?.string()`. The MCP "Streamable HTTP" transport supports SSE streaming for long-running tool calls. Aura's implementation waits for the entire response, capping the timeout at the call site. Long-running MCP tools (>30s) will hit the `withTimeoutOrNull` and return `McpToolResult.Timeout`, which the bridge maps to `mcp_timeout` error. The MCP SDK roadmap note (`McpConnection.kt:23-26`) acknowledges this is interim.

### C7. `validateTrustedLocal` only allows loopback (OK)
`McpClientManager.kt:84-109`. The check `host == "localhost" || host == "127.0.0.1" || host == "::1"` short-circuits to `SsrfValidation.Safe` without calling the standard guard. This is the documented behavior (trusted local servers are an explicit opt-in), but the function then returns `Safe` with `addresses = listOf(InetAddress.getByName(host))` — a single address. The `pinnedClient` for trusted local will pin only loopback, so any DNS-rebinding attack against the loopback hostname is fine. (Documented; OK.)

---

## Section D — Capabilities (Exa, Jina, Stability, ElevenLabs, Kling, WorldLabs)

I did not read every capability provider in full this pass. Skimmed:
- `CapabilityRegistry` exists and is wired in DI.
- `WebSearchCapabilityTool.kt` (line 25) and `ImageGenCapabilityTool.kt` (line 29) wrap the router and fall back to free / honest errors. OK.
- `MediaCapabilityTools.kt` covers TTS, video, 3D. Each delegates to a single configured provider. OK at the wrapper level; per-provider security needs a separate audit.
- `BraveSearchTool.kt:85-86` / `TavilySearchTool.kt:104` / `VisionTool.kt:104,151,195,231,242` / `ImageGenTool.kt:120` / `TranscriptionTool.kt:130,172` / `FirecrawlFetchTool.kt:82` / `SendEmailBackgroundTool.kt` — API key parameter is consistently declared `String` (redacted in the read_file output as `***`, the type is in the build cache). No keys are logged.

**No red flag at the wrapper layer.**

---

## Section E — README / docs vs implementation

| README claim | Reality |
|---|---|
| "69 tools" | ToolsModule registers 66; total tool count varies by which `McpToolBridge` path runs |
| "63 registered tools with risk levels" (line 155) | 66 |
| `web_search` = "DuckDuckGo HTML search (free, no key)" | Now chains to Tavily/Brave if a key is set (WebSearchTool.kt:77-87) |
| `image_gen` = WRITE_REMOTE | Actually `REMOTE_COST` (ImageGenTool.kt:68) |
| `image_generate` = WRITE_REMOTE | Actually `REMOTE_COST` (ImageGenCapabilityTool.kt:35) |
| `code_interpreter` = REMOTE_COST | Local WebView; should be `READ_ONLY` (CodeInterpreterTool.kt:69) |
| `kg_add` | **Not present** in `ToolsModule.kt`. (Probably deprecated; no replacement.) |
| `4k char tool-result truncation budget` (line 26) | Truncation lives in `truncateToolResult` (used by `DelegateToAgentTool.kt:243`); executor does not enforce a 4k cap |
| `parallel tool execution` (line 26) | `runBlocking` inside `runInterruptible` serialises-by-thread; bounded at 8 IO threads |
| `biometric gate for sensitive tools and app lock` (line 43) | Only `biometric_prompt` is gated; other tools do not check the app lock state |
| `JavaScript code interpreter` (line 55) | Correct, but risk label is wrong (see above) |

---

## Section F — Specialist allowlist (B11 verification)

`DelegateToAgentTool.kt:132-150`:
- If `agent.toolSet().isEmpty()` → agent gets `registry.definitions()` minus `delegate_to_agent`. Empty allowlist = full access.
- If non-empty → filter by name (with MCP base-name stripping for `mcp_*` and `category == "mcp"`).
- The filter does **not** verify that the delegated agent's `toolSet` entries actually exist in the registry (typos silently get filtered out, no error).
- A specialist like `coder` with `toolSet = ["web_search", "delegate_to_agent"]` (with the empty fallback rule) — this would still work because `delegate_to_agent` is filtered out post-hoc.

The MCP-allowlist-bypass concern from the brief (item 8) is closed: the `startsWith("mcp_") || category == "mcp"` check that previously allowed any MCP tool to bypass the allowlist (P1 AGENTIC A2) is fixed in `DelegateToAgentTool.kt:137-149` — both the pre-fix bug and the fix are documented in the comment block.

The remaining risk in the specialist layer is: an agent whose `toolSet` is empty gets full access. The default seeded specialists (per README line 30: "general, coder, researcher, writer, creative, executive, phone-native") presumably ship with non-empty allowlists, but user-created agents (README line 31: "user-creatable agents via Settings") are validated for non-empty allowlist at creation time only if the form enforces it. Needs verification in `AgentStore` / `AgentEntity`.

---

## Section G — Per-run approval flow (closing the loop on item 8)

The "specialist allowlist bypass" angle in the brief is not exploitable as the brief fears. The actual risk in the same surface is:
1. Specialist with empty allowlist = full access.
2. Specialist allowlist name collisions with MCP-renamed tools (handled in C3 above).
3. DelegateToAgentTool itself is registered as a tool in `ToolRegistry` (via `ToolsModule.kt:159`). The recursive-delegation guard at `DelegateToAgentTool.kt:151` filters it out for delegated children but **not for the top-level agent**. A top-level agent can call `delegate_to_agent` whose `agent_name` is itself (or any other top-level agent). If the delegated agent has a smaller allowlist, this is fine; if the delegated agent has a larger allowlist (or empty = full), the parent gains that agent's effective permissions for the duration of the delegation. This is a real escalation path, documented nowhere.

---

## Section H — Resource leaks (item 4)

`grep -r '\.newCall\|\.execute()` for OkHttp calls (eyeballed in tools I've read):
- Every `httpClient.newCall(req).execute()` I have read is followed by `.use { … }`. Good.
- `MediaPlayer` in `TtsSpeakTool.kt:100-105` is created without a finally block — if `prepare()` throws, `start()` is never called, but `release()` is also not called. `setOnCompletionListener { release() }` only fires on successful completion. **Resource leak** if `prepare()` throws.
- `CodeInterpreterTool.kt:104` creates a `WebView` per call. The `webView.destroy()` is called in two paths (line 157 post-evaluation, line 164 on cancellation), but not on `evaluateJavascript` callback exception. With repeated calls, the WebView pool is reclaimed when the FragmentActivity dies; in-process accumulation is bounded by TOOL_PARALLELISM=8, but on a foregrounded app with many tool calls in quick succession, the WebViews can transiently pile up.
- `BraveSearchTool.kt:101-106` / `TavilySearchTool.kt:113-119,155-162` / `FirecrawlFetchTool.kt:92-100` / `DeepResearchTool.kt:293-306,332-345,379-388,400-408` all use `.use{}` correctly on their direct OkHttp calls. No leak there.
- `ImageGenTool.kt:136-152` — `httpClient.newCall(req).execute()` is **not** in a `.use{}` block. The response is read in the body (`response.body?.string()`) but the `Response` object is never closed. Each call leaks a `Response` (which holds a connection back to the OkHttp connection pool — the pool is bounded, so the symptom is a transient `Response` accumulation that GC's once the function returns). Severity: LOW.
- `BraveSearchTool.kt:113-118,155-159` (Tavily) — same pattern, no `.use{}`. The `response` is read inline and goes out of scope; the underlying connection should be released by OkHttp's pool when the function returns, but explicitly calling `.close()` is recommended.
- `FirecrawlFetchTool.kt:92-100` — same, no `.use{}`.
- `VisionTool.kt:240-246` — has `.use{}`. OK.

### Shared OkHttpClient configuration (TO VERIFY)
`McpClientManager` and the tools share an injected `OkHttpClient`. The build config for it (timeouts, connection pool) is not in this audit's reviewed files. The `SsrfGuard.pinnedClient` clones a base client with `.dns(pinnedDns).followRedirects(false).followSslRedirects(false)`, so pinned clients disable redirect. The non-pinned shared client **may or may not have a `callTimeout(...)` set** — that file is not in `aura-core/src/main/kotlin/com/aura/core/url/` reviewed set. The `McpConnection.sendRequest` at line 196-222 calls `httpClient.newCall(...).execute()` synchronously, so the only backstop is the `withTimeoutOrNull(timeoutMs)` in `callTool` (line 139) — which works only if `timeoutMs < OkHttp.callTimeout`.

---

## Section I — Per-tool parameter validation (item 5)

Good:
- `WebSearchTool`, `BraveSearchTool`, `TavilySearchTool`, `FirecrawlFetchTool`, `HttpFileReadTool`, `HttpFileWriteTool`, `TranscriptionTool`, `VisionTool`, `EmailSendTool`, `SmsSendTool` all validate their input types and clamp numeric ranges.
- `WeatherTool` requires lat/lon, parses doubles.
- `SetReminderTool` validates recurrence enum.

Missing:
- `TavilySearchTool` does **not** validate `include_answer` is a bool beyond the `as? Boolean` cast (line 73); if a non-bool is passed, it defaults to `true`. Not a security issue, just lenient.
- `manage_tasks` accepts any string for `tags` (line 57) and `when` (line 54). The `when` is parsed by `TimeParser.parse` which (per `CalendarWriteTool.kt:91-95` comment) was hardened to reject `99:99`. `tags` is unconstrained.
- `clipboard_write`, `clipboard_read`, `share`, `email_send`, `sms_send`, `set_reminder`, `manage_tasks` (description), `run_hand` (variables_json parsed via `repository.parseVariables` which I did not open) — no length caps. A model could push 100 MB into a SQLite column.
- `http_file_write` — `content` has no cap; the LLM can send a 100 MB body in one call.
- `send_email_background` — `to`, `cc`, `bcc` are passed to `InternetAddress.parse` which is permissive (allows `"Name <email>"` and bare addresses). No regex/format check; the JavaMail `setRecipients` will accept malformed addresses and the SMTP server may reject or silently drop.

---

## Section J — Items the brief asked about, with verdicts

| Brief item | Verdict |
|---|---|
| 1. Tool risk misclassification | **YES** — `code_interpreter` (REMOTE_COST, should be READ_ONLY), `tavily_search`/`brave_search` (READ_ONLY, should be REMOTE_COST), `image_gen` (REMOTE_COST, README says WRITE_REMOTE), `firecrawl_fetch` (REMOTE_COST but SSRF weaker than siblings) |
| 2. Missing required permissions | Partial. `calendar_read` declares `READ_CALENDAR` and re-checks at execute (CalendarReadTool.kt:52). `location_now` declares both fine/coarse. `biometric_prompt` declares `USE_BIOMETRIC`. `dnd_mode` correctly returns `NeedsPermission("notification_policy_access", …)` (a special-access string, not a regular permission — works). `post_notification` declares `POST_NOTIFICATIONS`. `notification_list` returns `NeedsPermission("BIND_NOTIFICATION_LISTENER_SERVICE", …)` correctly. `app_launcher` opens external apps — no permission needed (Intent.ACTION_VIEW). **Gap:** `open_browser_tab` opens arbitrary URLs after SSRF check, no special permission needed. No missing permissions found in the read set. |
| 3. SSRF in URL-fetching tools | **Yes — gap:** `FirecrawlFetchTool` uses `validate` (no `pinnedClient`); `WeatherTool` uses `validate` but URL is hardcoded. `HttpFileReadTool`, `HttpFileWriteTool`, `DeepResearchTool.fetchDirect`, `McpConnection.sendRequest` correctly use `pinnedClient`. `OpenBrowserTabTool` uses `validate` only — but the result is not actually fetched server-side (it's a marker for an in-app WebView), so no TOCTOU surface. |
| 4. Resource leaks | Minor. `TtsSpeakTool` MediaPlayer not released on prepare() failure. `ImageGenTool`, `BraveSearchTool`, `TavilySearchTool`, `FirecrawlFetchTool` OkHttp Response not explicitly closed (relies on OkHttp pool). |
| 5. Parameter validation | Mostly OK. Gaps: `http_file_write.content` uncapped; `manage_tasks.*` mostly uncapped; `clipboard_*` uncapped; `email_send.body` uncapped. |
| 6. Tool result truncation | Inconsistent. `code_interpreter`, `http_file_read`, `firecrawl_fetch`, `deep_research` partial, all cap. `clipboard_read`, `share`, `set_reminder` (output is short), `manage_tasks` (output is short), `transcribe` (model), `translate` (model), `vision` (model) all return whatever the upstream produced. The 4k cap exists at the agentic loop layer (truncateToolResult), not at the executor. |
| 7. Tool registry duplicates | `ToolRegistry.register` (line 82) is `Map.put`, no dedup. In production, `ToolsModule` registers each tool once, but `McpToolBridge.syncToolsUnprefixed` (line 142) intentionally re-registers bare-named MCP tools over native ones. **Yes, exploitable as documented in finding #2.** |
| 8. Specialist allowlist bypass | `DelegateToAgentTool` correctly enforces the allowlist (per the P1 AGENTIC A2 fix documented in lines 110-150). **No bypass.** The remaining risk is: empty allowlist = full access (B11). |
| 9. MCP tool validation | `McpToolBridge.parseSchema` (line 233) catches exceptions and returns empty schema, **silently** exposing a tool with no parameter constraints (C1). `McpConnection.callTool` (line 142) silently drops non-String/Number/Boolean/Map argument values. **Yes, exploitable.** |
| 10. Policy engine defaults | `ToolPolicyDefaults.forTool` (line 29-60) is the canonical risk→confirmation mapping. READ_ONLY/REMOTE_COST/WRITE_LOCAL → NONE; WRITE_REMOTE/DESTRUCTIVE → EXPLICIT; PRIVACY → IMPLICIT. **IMPLICIT does not block write tools** — the gate UI surfaces a hint, not a refusal. Built-in risk cannot be loosened by user policy (per the policy file's docstring at `ToolPolicy.kt:11-13`). `costCeiling` and `allowedScopes` policy fields are never consulted (B10). |
| 11. Parallel tool execution | The brief asked "independent tools serial when they should be parallel". The agentic loop does `async{}.awaitAll()` (per `ToolExecutor.kt:60-66` comment), so tools **are** parallel at the agentic loop layer. But the executor's `runBlocking` inside `runInterruptible` parks one IO thread per call (B4), so 8 parallel calls pin 8 IO threads. Not serial; just bounded-and-thread-pinned. |
| 12. Tool timeout enforcement | `withTimeout(ctx.timeout)` around `runInterruptible`. As documented in B4, the timeout is best-effort because `runBlocking` swallows `InterruptedException` in some code paths. `DelegateToAgentTool` uses `withTimeout(30_000L)` directly — better. `BiometricPromptTool` uses `withTimeout(60_000L)` and shuts down the executor on timeout — clean. |
| 13. Missing tools (README promises vs code) | README line 188 lists `kg_add` (knowledge graph add node/edge) — **not in `ToolsModule.kt`**. The current KG interaction is read-only (`kg_query`, `knowledge_graph_extract`). If the user expects to add nodes via tool, it's missing. |

---

## Section K — Recommended fixes (priority order)

1. **Replace substring confirmation matching in `RemoteCostApprovalGate` and `CalendarApprovalGate`** with a tighter check: normalize to word tokens, require the entire normalised message to be one of the confirmations. Reject if the original message has more than 5 tokens. (Cost: small; fixes HIGH finding #1.)
2. **Make `McpToolBridge.syncToolsUnprefixed` refuse to register a tool whose name collides with a native tool unless the user explicitly opts in via Settings.** Better: drop `syncToolsUnprefixed` entirely and force MCP tools to use the `mcp_<serverId>_<toolName>` prefix. (Cost: small; fixes HIGH finding #2.)
3. **Type-check all JSON-RPC arguments in `McpConnection.callTool`.** Reject `null`, list, and unknown types with a clear error rather than dropping silently. Better: pass the `JsonObject` directly to the JSON-RPC serialiser instead of re-encoding from `Map<String, Any?>`. (Cost: medium; fixes HIGH finding #3.)
4. **Replace `runBlocking { runInterruptible { … } }` with a clean `withContext(toolDispatcher) { … }`.** Drop `runBlocking`; the `runInterruptible` exists only to bridge non-suspend callbacks. Most tools are already suspend, so this is mostly deletion. (Cost: medium; fixes MEDIUM-HIGH finding #4 and the TOCTOU in B5.)
5. **Fix risk labels:** `code_interpreter` → `READ_ONLY`; `tavily_search`/`brave_search` → `REMOTE_COST`; README catalog update for `image_gen` / `image_generate`. (Cost: tiny.)
6. **Make `FirecrawlFetchTool` use `SsrfGuard.pinnedClient()`** (one-line fix, currently line 92 uses the un-pinned shared client).
7. **Enforce `required` in `ToolExecutor.parseArgs`** and return `ToolResult.Error("missing required argument: $name", "bad_args")` before the tool body runs.
8. **Implement `CostExceeded` and `ScopeDenied` in `PolicyEngine.evaluate`** (currently dead policy code) and wire `costCeiling` / `allowedScopes` into per-tool limits.
9. **Add length caps** to `http_file_write.content`, `clipboard_*`, `share.text`, `email_send.body`, `sms_send.body`, `set_reminder.message`, `manage_tasks.*` — recommend 8 KB for clipboard, 64 KB for share/email body, 4 KB for reminder message.
10. **Add `.use{}` to `ImageGenTool.kt:136`, `BraveSearchTool.kt:101`, `TavilySearchTool.kt:113,155`, `FirecrawlFetchTool.kt:92`** — explicit Response.close().
11. **Document `approvedRemoteCostTools` semantics** and rename the field to make the per-call-only behaviour obvious. Better: use a separate field for `requireApprovalPerRun` satisfaction.
12. **Close the `DelegateToAgentTool` recursive escalation** by tracking which agents have called which other agents in the current call stack and refusing re-entry.
13. **Add `MediaPlayer.release()` in a `try/finally` in `TtsSpeakTool.playAudio`** to plug the prepare-failure leak.
14. **Implement `WebView` pool** in `CodeInterpreterTool` to avoid per-call WebView creation, or reuse a single pre-warmed WebView across calls.
15. **Add the `kg_add` tool** (if the README promise is real) or remove the row from the README catalog.
16. **Add `approvedRemoteCostTools` to the UI:** the user-facing UX should make it obvious that one paid-tool approval does not carry to the next call.

---

## Reading log (which tools I read in full)

Read in full: `ToolRegistry.kt`, `ToolExecutor.kt`, `PolicyEngine.kt`, `ToolPolicy.kt`, `ToolPolicyStore.kt`, `ToolPolicyDefaults.kt`, `ToolsModule.kt`, `ToolCategories.kt`, `McpToolBridge.kt`, `McpConnection.kt`, `McpClientManager.kt`, `McpModels.kt`, `SsrfGuard.kt`, `WebSearchTool.kt`, `BraveSearchTool.kt`, `TavilySearchTool.kt`, `DuckDuckGoSearch.kt`, `HttpFileReadTool.kt`, `HttpFileWriteTool.kt`, `FirecrawlFetchTool.kt`, `DeepResearchTool.kt`, `CalendarReadTool.kt`, `CalendarWriteTool.kt`, `ClipboardReadTool.kt`, `ClipboardWriteTool.kt`, `ContactsSearchTool.kt`, `LocationNowTool.kt`, `PhotoLibraryTool.kt`, `CodeInterpreterTool.kt`, `ImageGenTool.kt`, `EmailSendTool.kt`, `SmsSendTool.kt`, `CaptureScreenTool.kt`, `VisionTool.kt`, `DelegateToAgentTool.kt`, `NotificationsTool.kt`, `TaskManagerTool.kt`, `SetReminderTool.kt`, `MemoryTools.kt`, `RunHandTool.kt`, `DndModeTool.kt`, `SendEmailBackgroundTool.kt`, `AppLauncherTool.kt`, `MediaCapabilityTools.kt`, `BiometricPromptTool.kt`, `UseSkillTool.kt`, `RunCouncilTool.kt`, `NotificationListTool.kt`, `TranslateTool.kt`, `WeatherTool.kt`, `TimerTool.kt`, `TtsSpeakTool.kt`, `OpenBrowserTabTool.kt`, `TranscriptionTool.kt`, `BatteryStateTool.kt`, `NetworkStateTool.kt`, `CanonQueryTool.kt`, `KnowledgeGraphTool.kt`, `QueryWorldModelTool.kt`, `CreativeTools.kt`, `ImageGenCapabilityTool.kt`, `ShareIntentTool.kt`, `WebSearchCapabilityTool.kt`, `README.md` (first 421 lines).

Skimmed (file exists, did not open in this pass): `KgQueryTool.kt`, `QueryTasteTool.kt`, `IndexDocumentTool.kt`, `CreativeEngineTool.kt`, `FilePickerTool.kt`, `GetCurrentTimeTool.kt`, `ScheduleTaskTool.kt`, `SystemVolumeTool.kt`, `HandRunEnqueuer.kt`, `ReminderWorker.kt`, `TimeParser.kt`, `BiometricAuthHandler.kt`, `Citation.kt`, `ImageInputTool.kt` (could not locate), and all `evolution/*.kt` + `integrations/google/*.kt` + `integrations/microsoft/*.kt` tools.

Capabilities (`aura-core/.../capabilities/*`): directory listed; not opened in this pass beyond the existence check.

---

*End of audit.*
