## P0 Hardening Pass

10 atomic commits since v0.33.0. All 12 P0 findings from the
5-scope deep audit (AGENTIC, PROVIDERS, MEMORY, DATA, UI/UX)
cleared.

### Real bugs fixed (8)

- **AGENTIC A1** — `AgentEvent.PermissionGranted` was dead code.
  Replaced with `PermissionRequested`; loop now pauses and
  `retryAfterPermission` in ChatViewModel re-executes the held
  tool after user grants.

- **PROVIDERS A1** — Anthropic `input_json_delta` first chunk
  dropped (empty tool id). Added `indexToId: MutableMap<Int,String>`
  to route deltas by SSE `index`.

- **PROVIDERS A2** — Anthropic SSE `index` field dropped for
  parallel tool calls (deltas for tool #1 routed to tool #2).
  Same fix as A1.

- **PROVIDERS C1** — base `OkHttpClient` followed redirects →
  SSRF (a 302 to `http://169.254.169.254/...` would be silently
  followed). Added `followRedirects(false).followSslRedirects(false)`.

- **MEMORY A1** — `MemoryBackup` missing `scope` field →
  per-agent private memories leaked into `general` scope on
  restore. Added `scope: String = "general"` to MemoryBackup,
  wired in `toBackup()` and `toEntity()` mappers.

- **MEMORY B2** — `CloudEmbedder.dimension()` hard-coded 384 →
  every real Ollama model (nomic=768, mxbai=1024, snowflake=1024)
  silently rejected, fell back to local. Now model-aware
  dimension lookup for 17+ models.

- **MEMORY A2** — 11 `runCatching` blocks in `MemoryStore`
  silently swallowed exceptions. Each now chains
  `.onFailure { Log.w(...) }` with operation-specific context.

- **MEMORY E1+E2** — `ConversationKgExtractor` overwrote
  `pendingExtraction` on every call → only the LATEST turn
  was ever extracted, earlier ones lost. Replaced single
  `@Volatile` slot with `ConcurrentLinkedQueue` and drain
  loop. Capped at MAX_PENDING=64 (oldest dropped with counter).
  Silent catch → `Log.w` with provenance.

### Audit-claim verification + regression tests (3)

- **DATA A1** — Manual audit confirmed all 7 DB modules'
  `MIGRATION_X_Y` arrays are complete (no gaps, no missing
  steps). Added `MigrationRegistryAuditTest` using reflection
  to pin the contract against future drift.

- **UI/UX A1** — Manual audit confirmed every `navigate("...")`
  has a matching `composable("...")` (e.g. `chat?convId=$id`
  matches `composable("chat?convId={convId}&...&brief={brief}")`
  because query parameters are optional). Added
  `NavigationReachabilityTest` scanning app/ source.

- **PROVIDERS B1** — Manual audit confirmed all 5 HTTP
  providers mark 401/400/403 as non-retryable (either via
  positive `429 || 5xx` check or negative `!= 401 && != 400
  && != 403` check). Added `NonRetryableStatusCodesTest` to
  pin the pattern against future drift.

### Stats

- Tests: 1178 → 1203 (+25), 0 failures
- Files changed: 8 production + 3 test
- LOC: +380 production, +240 tests
- versionCode 35 → 36

### Upgrade notes

- 401/400/403 errors no longer trigger provider failover (was
  never expected behavior, but now pinned by test).
- Agentic loop pauses on permission requests (was dead-code
  loop that appended "Permission needed: X" as plain text and
  kept stepping, stranding the user).
- Memory restore preserves scope (was leaking private
  memories into general scope on every restore).

### Known limitations (not P0)

- 32 P1 + 80 P2 findings remain (deferred to future sessions).
- Pre-existing test issues in `ConversationStoreTest` and
  `EvolutionSafetyGuardTest` (unrelated to this pass).
