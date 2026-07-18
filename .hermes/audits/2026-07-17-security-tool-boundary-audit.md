# Security & Tool Boundary Audit — D:\aura-android-clean
**Date:** 2026-07-17 | **Auditor:** Hermes Agent  
**Scope:** 617 Kotlin files, 2 modules, 56 tools, 8 LLM providers, evolution subsystem

---

## Summary

| Severity | Count | Description |
|----------|-------|-------------|
| **P0**   | 3     | Immediate risk: data loss, credential leak, or bypass of security boundaries |
| **P1**   | 6     | Significant: misclassification enabling unauthorized state mutation or cost bypass |
| **P2**   | 5     | Notable: hardening gaps, defense-in-depth failures, inconsistent patterns |

---

## P0 Findings

### P0-1: `trigger_evolution_run` classified READ_ONLY but CREATES/DELETES database records

**File:** `aura-core/src/main/kotlin/com/aura/tools/evolution/EvolutionTools.kt:90-100`  
**Risk:** `ToolRisk.READ_ONLY`  
**Evidence:**
```kotlin
val tool = Tool(
    name = "trigger_evolution_run",
    risk = ToolRisk.READ_ONLY,  // <-- FALSE: calls coordinator.runAll()
    ...
)
```

`coordinator.runAll()` (`EvolutionCoordinator.kt:25-32`) calls `detectors.runAll()` which calls `candidateDao.upsert()` for EVERY candidate entity (writes to DB). Then `reflectAndPromote()` calls `proposalStore.fromCandidate()` which inserts into the proposals table. These are **database writes**, not reads.

**Impact:** Incognito mode check (`ToolExecutor.kt:47`) only blocks `WRITE_LOCAL+`. Since this tool is `READ_ONLY`, it runs even when `memoryEnabled=false`, allowing the agent to create/delete records during incognito sessions. Also bypasses any remote-cost gate.

**Fix:** Change to `ToolRisk.WRITE_LOCAL`. Add user confirmation gate since evolution changes are high-impact.

---

### P0-2: MCP connection makes HTTP requests WITHOUT SSRF validation

**File:** `aura-core/src/main/kotlin/com/aura/mcp/McpConnection.kt:181-197`  
**Evidence:**
```kotlin
private fun sendRequest(requestBody: JsonObject): JsonObject? {
    val builder = Request.Builder().url(config.url).post(body)  // <-- NO SSRF CHECK
    ...
    httpClient.newCall(builder.build()).execute()
}
```

The `McpClientManager.connect()` (`McpClientManager.kt:32-38`) only checks `trustedLocal || url.startsWith("https://")` — it does NOT validate against private IPs, localhost, or SSRF targets. A malicious or misconfigured MCP server config could point to `http://169.254.169.254/latest/meta-data/` (cloud metadata) or an internal service.

**Impact:** Server-Side Request Forgery. An attacker who can configure an MCP server (or a malicious MCP server address returned from a discovery mechanism) could probe internal network services, read cloud metadata endpoints, or attack internal infrastructure.

**Fix:** Apply `SsrfGuard.inspect(url)` on `config.url` before `sendRequest()`. In `McpConnection`, add SSRF validation in the `initialize()` or directly in `sendRequest()`. Use the pinnedClient pattern for the HTTP request.

---

### P0-3: `HttpFileWriteTool` classified REMOTE_COST but performs external state mutation (should be WRITE_REMOTE)

**File:** `aura-core/src/main/kotlin/com/aura/tools/HttpFileWriteTool.kt:40-76`  
**Evidence:**
```kotlin
val tool = Tool(
    name = "http_file_write",
    risk = ToolRisk.REMOTE_COST,  // <-- Should be WRITE_REMOTE
    ...
    execute = { call, _ ->
        // Sends PUT/POST with user content to arbitrary URL
        val body = content.toRequestBody(contentType.toMediaType())
        val req = Request.Builder().url(url).apply { if (method == "PUT") put(body) else post(body) }.build()
```

`WRITE_REMOTE` exists as a risk level but is **never used** by any tool. `http_file_write` writes arbitrary content to an attacker-controlled URL — this is the textbook definition of `WRITE_REMOTE`.

**Impact:** No permission/approval gating for tools that write to external infrastructure. The `RemoteCostApprovalGate` only checks `REMOTE_COST` — changing to `WRITE_REMOTE` would require adding a corresponding gate. But at minimum the risk classification must be corrected to allow policy engines to filter it.

**Fix:** Change to `ToolRisk.WRITE_REMOTE`. Add `WRITE_REMOTE` to the incognito filter in `ToolExecutor.kt:47`. Consider whether a user-approval gate should apply.

---

## P1 Findings

### P1-1: SSRF TOCTOU in `HttpFileReadTool` and `HttpFileWriteTool`

**Files:**  
- `aura-core/src/main/kotlin/com/aura/tools/HttpFileReadTool.kt:47-53`  
- `aura-core/src/main/kotlin/com/aura/tools/HttpFileWriteTool.kt:50-59`  

**Evidence (HttpFileReadTool):**
```kotlin
val ssrfError = SsrfGuard.validate(url)   // Time-of-check: resolves DNS, validates
if (ssrfError != null) ...
val req = Request.Builder().url(url)...   // Time-of-use: separate DNS resolution
httpClient.newCall(req).execute()
```

`SsrfGuard.validate(url)` resolves the hostname and checks addresses. But then the code uses `SsrfGuard.pinnedClient()` — `httpClient.newCall()` does its OWN DNS resolution, potentially to a DIFFERENT address. DNS rebinding attack window exists between the check and the use.

`DeepResearchTool.kt:276-278` does this correctly:
```kotlin
val pinnedClient = SsrfGuard.pinnedClient(httpClient, target)
pinnedClient.newCall(req).execute()
```

**Impact:** DNS rebinding bypass of SSRF guard for file read/write operations to arbitrary URLs.

**Fix:** Replace direct `httpClient.newCall()` with `SsrfGuard.pinnedClient(httpClient, target)` for all tools that accept user-provided URLs. Apply to FirecrawlFetchTool, HttpFileReadTool, HttpFileWriteTool, and WeatherTool.

---

### P1-2: `vision` tool classified READ_ONLY but uses paid cloud APIs

**File:** `aura-core/src/main/kotlin/com/aura/tools/VisionTool.kt:51-53`  
**Evidence:**
```kotlin
val tool = Tool(
    name = "vision",
    risk = ToolRisk.READ_ONLY,  // <-- Sends images to paid APIs (OpenAI, Anthropic, Gemini)
```

This tool transmits base64-encoded images to OpenAI (`api.openai.com`), Anthropic (`api.anthropic.com`), or Google Gemini (`generativelanguage.googleapis.com`) — all metered, paid APIs. Yet it's marked `READ_ONLY`, which means:
- No `RemoteCostApprovalGate` triggered
- Runs in incognito mode
- Agent can call it unlimited times without user approval for cost

Same issue applies to:
- `knowledge_graph_extract` (`KnowledgeGraphTool.kt:60`) — `READ_ONLY` but calls cloud LLM
- `transcribe` (`TranscriptionTool.kt:61`) — `REMOTE_COST` ✓ (correct)

**Impact:** Cost exhaustion. An agent loop could call `vision` hundreds of times, incurring API charges without the user ever being asked for approval.

**Fix:** Change `vision` and `knowledge_graph_extract` risk to `ToolRisk.REMOTE_COST`. They both consume paid cloud API credits.

---

### P1-3: `trigger_evolution_run` runs unlimited model reflection without cost control

**File:** `aura-core/src/main/kotlin/com/aura/evolution/EvolutionCoordinator.kt:41-73`  
**Evidence:**
```kotlin
val result = reflection.reflect(
    systemPrompt = REFLECTION_SYSTEM_PROMPT,
    userPrompt = buildReflectionPrompt(candidate),
)
```

The evolution pipeline calls the configured EVOLUTION model (a cloud LLM) for EVERY candidate above the score threshold during reflection. There is:
- No limit on the number of reflection calls per `runAll()`
- No cost cap or user approval for the model usage
- No rate limiting on how often `trigger_evolution_run` can be called

**Impact:** Cost + resource exhaustion. A runaway agent loop calling `trigger_evolution_run` repeatedly could generate hundreds of LLM API calls in minutes.

**Fix:** Add a cooldown to `EvolutionCoordinator` (minimum interval between runs). Add a max-reflection-calls-per-run cap. Wire the tool through `REMOTE_COST` gate.

---

### P1-4: `trigger_evolution_run` creates DB records but classified READ_ONLY (detailed)

Same as P0-1 with additional detail on the DB writes:

**EvolutionCandidateDetectors.kt:138-147:**
```kotlin
suspend fun runAll(): List<EvolutionCandidateEntity> {
    val all = detectSkillPatchCandidates() + detectSkillPromotionCandidates() + ...
    for (candidate in all) {
        candidateDao.upsert(candidate)  // WRITE to DB
    }
    return all
}
```

**EvolutionCoordinator.kt:54-65:**
```kotlin
proposalStore.fromCandidate(candidate.copy(reflectionResult = verdict.reason))  // WRITE to DB
candidateDao.setStatus(candidate.id, ...)  // WRITE to DB
```

**Fix:** See P0-1.

---

### P1-5: EvolutionSafetyGuard credential regex is too narrow

**File:** `aura-core/src/main/kotlin/com/aura/evolution/EvolutionSafetyGuard.kt:24-26`  
**Evidence:**
```kotlin
private val credentialPatterns = listOf(
    Regex("sk-[a-zA-Z0-9]{20,}", RegexOption.IGNORE_CASE),
)
```

This only detects OpenAI-style keys (`sk-...`). It will NOT detect:
- Anthropic keys (`sk-ant-...`)
- Gemini/Google API keys (AIza...)
- AWS keys (AKIA...)
- GitHub tokens (ghp_...)
- Generic `Bearer ...` tokens
- JWT tokens
- API keys in URL query strings (`?key=...`, `?apiKey=...`)

**Impact:** Evolution proposals could contain (and persist) credentials from other providers that the safety guard fails to detect.

**Fix:** Expand credential patterns to match common API key formats. At minimum add patterns for Anthropic (`sk-ant-`), Gemini (`AIza`), and generic base64-ish tokens.

---

### P1-6: `clipboard_read` has no permission requirement or user notification

**File:** `aura-core/src/main/kotlin/com/aura/tools/ClipboardReadTool.kt:33-36`  
**Evidence:**
```kotlin
val tool = Tool(
    name = "clipboard_read",
    risk = ToolRisk.PRIVACY,
    requiredPermissions = emptyList(),  // <-- No permission gating
```

On Android 10+, apps can read clipboard silently unless they're the focused app or the default input method. On Android 12+, clipboard reads show a toast notification, but the tool still reads the content without any explicit user consent flow.

**Impact:** An agent could silently read passwords, 2FA codes, or sensitive data from the clipboard without the user knowing (the toast is easily missed).

**Fix:** Add `no permission required — reads clipboard` is technically correct but consider adding an explicit user confirmation for clipboard read (e.g., a `NeedsApproval` path). At minimum document the implication clearly in the tool description.

---

## P2 Findings

### P2-1: `clipboard_read` should not be callable without user-facing notification

Same as P1-6, but classified as P2 because Android itself provides minimal guard (toast on 12+).

**Fix:** Consider adding a biometric or explicit confirmation before returning clipboard content.

---

### P2-2: Internal tool timeouts don't respect ToolContext.timeout

**Evidence:**
- `KgQueryTool.kt:62`: hardcoded `withTimeout(15_000L)` — ignores `ctx.timeout`
- `DeepResearchTool.kt:110`: hardcoded `withTimeout(RESEARCH_TIMEOUT_MS)` (60s) — ignores `ctx.timeout`

`ToolContext.timeout` defaults to 30s (`ToolRegistry.kt:54`). Tools that set their own shorter timeout will cancel before the context timeout. But tools with LONGER internal timeouts (DeepResearchTool at 60s) will keep running after the context timeout fires.

**Impact:** The `withTimeout(ctx.timeout)` in `ToolExecutor.kt:75` should be the authority. Internal tool timeouts should either use `ctx.timeout` or at maximum `min(internalTimeout, ctx.timeout)`.

**Fix:** Replace hardcoded timeouts with `ctx.timeout`-aware values, or document that the tool overrides the default timeout.

---

### P2-3: RunBlocking in tool execute methods can cause ANR

**File:** `aura-core/src/main/kotlin/com/aura/tools/evolution/EvolutionTools.kt:103-104`  
**Evidence:**
```kotlin
private inline fun <T> runBlockingTool(crossinline block: suspend () -> T): T =
    kotlinx.coroutines.runBlocking { block() }
```

`Tool.execute` is a non-suspend lambda. Tools that need to call suspend functions (like evolution tools) use `runBlocking` to bridge. The `ToolExecutor` wraps this in `runInterruptible(Dispatchers.IO){ runBlocking{ tool.execute() } }` — but `runBlocking` inside `runInterruptible` is still blocking the IO thread. If the tool's internal suspend function hangs, it blocks an IO thread.

**Impact:** Thread starvation under load. Multiple concurrent tool calls using `runBlocking` will consume Dispatchers.IO threads.

**Fix:** Change `Tool.execute` to be a `suspend` function instead of wrapping with `runBlocking`. This requires changing the `Tool` data class and all tool implementations.

---

### P2-4: Evolution proposals can create/rewrite skills via arbitrary JSON deserialization

**File:** `aura-core/src/main/kotlin/com/aura/evolution/EvolutionApplySaga.kt:56-64`  
**Evidence:**
```kotlin
private suspend fun applyCreateSkill(proposal: EvolutionProposalEntity): ApplyResult {
    val skill = runCatching {
        Json.decodeFromString<Skill>(proposal.patchJson)
    }.getOrNull() ?: return ApplyResult.Error(...)
    skillsStore?.add(skill) ?: return ApplyResult.Error(...)
```

The `proposal.patchJson` field is deserialized directly into a `Skill` object and then added to the `SkillsStore`. Skills are arbitrary markdown + metadata that the agent loop executes. There's no validation that the skill content doesn't contain malicious instructions, system prompt injections, or tool abuse patterns.

**Impact:** If an evolution reflection model generates a proposal with crafted skill content, the agent could be instructed to perform dangerous actions the next time the skill is invoked.

**Fix:** Add Skill content validation — reject skills with suspicious patterns, enforce max length, require explicit fields. Add a "sandbox" mode for new skills where they're reviewed by the user before first use.

---

### P2-5: `KnowledgeGraphTool` classified READ_ONLY but transmits user text to cloud LLM

**File:** `aura-core/src/main/kotlin/com/aura/tools/KnowledgeGraphTool.kt:57-61`  
**Evidence:**
```kotlin
val tool = Tool(
    name = "knowledge_graph_extract",
    risk = ToolRisk.READ_ONLY,  // <-- sends arbitrary user text to cloud LLM
```

This tool sends the user's input text to a cloud LLM provider for knowledge graph extraction. This is a data exfiltration vector if the text contains sensitive information. The tool should be at minimum `REMOTE_COST` and arguably `PRIVACY` since user text is transmitted to a third party.

**Fix:** Change to `ToolRisk.PRIVACY` (handles user data transmitted externally). Add a warning in the tool description that data is sent to a cloud provider.

---

## Cross-Cutting Observations

### Observation A: `WRITE_REMOTE` risk level is defined but NEVER used

`ToolRisk.WRITE_REMOTE` (enum entry 3) exists in `ToolRegistry.kt:18` but zero tools use it. `http_file_write` is an obvious candidate, and evolution's skill-creation/deletion could arguably be `WRITE_REMOTE` when skills are synced.

### Observation B: `PRIVACY` risk level defined but not used for cloud data transmission

`ToolRisk.PRIVACY` exists but neither `vision`, `knowledge_graph_extract`, nor `transcribe` use it despite sending user data (images, text, audio) to cloud APIs. Only `clipboard_read`, `photo_library`, and `capture_screen` use `PRIVACY` for local data reads.

### Observation C: No SSRF validation on DuckDuckGoSearch (intentional, but unchecked)

`DuckDuckGoSearch.kt:16-28` hardcodes `html.duckduckgo.com`. This is safe by design but worth noting that if the DDG domain were ever replaced (e.g., via a config), there's no SSRF guard.

### Observation D: No environment/configuration secret injection guards

`ProviderKeys` reads API keys from `SecureDataStore`. No tools or provider code uses environment variables or system properties for secrets. The service endpoints for each provider are hardcoded (no user-supplied base URLs reach tools). This is **good practice** but leaves a gap if future features allow user-configured provider endpoints.

---

## Tool Risk Classification Heatmap

| Tool | Current Risk | Correct Risk | Issue |
|------|-------------|--------------|-------|
| `trigger_evolution_run` | READ_ONLY | WRITE_LOCAL | P0 — writes DB records |
| `vision` | READ_ONLY | REMOTE_COST | P1 — cloud API cost |
| `knowledge_graph_extract` | READ_ONLY | PRIVACY + REMOTE_COST | P2 — transmits user data |
| `http_file_write` | REMOTE_COST | WRITE_REMOTE | P0 — mutates external state |
| `deep_research` | REMOTE_COST | REMOTE_COST | ✓ Correct |
| `tavily_search` | REMOTE_COST | REMOTE_COST | ✓ Correct |
| `brave_search` | REMOTE_COST | REMOTE_COST | ✓ Correct |
| `firecrawl_fetch` | REMOTE_COST | REMOTE_COST | ✓ Correct |
| `web_search` | READ_ONLY | READ_ONLY | ✓ Correct (DDG free) |
| `transcribe` | REMOTE_COST | REMOTE_COST | ✓ Correct |
| `tts_speak` | REMOTE_COST | REMOTE_COST | ✓ Correct |
| `image_gen` | REMOTE_COST | REMOTE_COST | ✓ Correct |
| `clipboard_read` | PRIVACY | PRIVACY | ✓ Correct, but no permission guard (P1-6) |
| `photo_library` | PRIVACY | PRIVACY | ✓ Correct |
| `capture_screen` | PRIVACY | PRIVACY | ✓ Correct |

---

## Priority Remediation Roadmap

### Immediate (P0)
1. Fix `trigger_evolution_run` risk → `WRITE_LOCAL` (EvolutionTools.kt:91)
2. Add SSRF validation to MCP connection (McpConnection.kt:181)
3. Fix `http_file_write` risk → `WRITE_REMOTE` (HttpFileWriteTool.kt:43)

### Short-term (P1)
4. Apply `SsrfGuard.pinnedClient` to all user-URL tools (HttpFileReadTool, HttpFileWriteTool, FirecrawlFetchTool, WeatherTool)
5. Fix `vision` and `knowledge_graph_extract` risk → `REMOTE_COST`
6. Add evolution rate limiting / cost control to `trigger_evolution_run`
7. Expand EvolutionSafetyGuard credential patterns
8. Add clipboard read confirmation mechanism

### Medium-term (P2)
9. Make Tool.execute suspend instead of runBlocking
10. Add Skill content validation in EvolutionApplySaga
11. Align internal tool timeouts with ToolContext.timeout
12. Add `WRITE_REMOTE` to incognito filter in ToolExecutor
