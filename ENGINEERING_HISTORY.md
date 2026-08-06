# Engineering History — Aura Android

Consolidated from 29 review, audit, findings, and plan documents written between
2026-07-17 and 2026-07-26, plus the 2026-07-26 external review. The originals were
deleted once merged here; every one of them is recoverable from git history
(`git log --diff-filter=D --name-only` to find the deleting commit, then
`git show <commit>^:<path>`).

This file has two jobs: record what was found and fixed so the same ground isn't
re-audited, and keep an honest list of what is still open. **Section 3 is the only
part that describes present-day state.** Sections 1 and 2 are history and will go
stale by design — do not treat them as a description of the current codebase.

---

## 1. Timeline

| Date | Pass | Baseline | Outcome |
|------|------|----------|---------|
| 07-17 | Initial assessment | 617 files, ~76K LOC, 1,004 tests | Graded B+. CI broken at HEAD, fixed. Diagnosis: "ambition has outpaced integration" — impressive substrate, much of it un-wired |
| 07-18 | Engineering review | 409 files, 59 tools, 187 test files | ToolRegistry thread safety, DagResolver dedup, provider failover dedup |
| 07-22 | Review cycle 5 | 1,115 tests | 6 data-integrity bugs (agentId loss, soft-delete resurrection, backup gaps) |
| 07-23 | Test gap round 3 | 1,151 tests | Coverage gaps after 5 prior cycles |
| 07-25 | Full review + 3 subagents | 1,238 tests | 11 fixes: OOM, emotion persistence, dead settings flows, taste bucketing |
| 07-25 | Round 5 audits (3) | v0.35.3 | 30+ findings across loop/memory/UI |
| 07-26 | Full pass | 1,257 tests | Anthropic parallel tool-call routing (P0) |
| 07-26 | Pass 2 | 1,259 tests | Same bug class in the OpenAI-compat family (P0) |
| 07-26 | Phase 1 audits (3) | v0.36.0 | 28 UI findings, data-layer gaps, agent-loop re-verification |
| 07-27 | runCatching + dual-state cleanup | 1,596 tests | Council/chat wiring, task cancellation, agent context, specialist cleanup, silent runCatching logging |
| 07-29 | Claude review + engineering pass | 1,423 tests | 10 fixes from Claude Code review (voice P0 double-send, evolution auto-apply, candidate purge SQL, daemon day boundary + trimIndent, CancellationException rethrow) + 3 new fixes (proposal purge case-mismatch, recentTopics gate, daemon nullable injections + privacy KDoc) |
| 07-29 | Pass 2 — deep subsystem audit | 1,423 tests | 3 fixes (triggers JSON silent decode, pipeline JSON escaping, GlobalSearch error logging) + 2 regression test files |
| 07-29 | Pass 3 — doc correction + build hygiene | 1,423 tests | Corrected stale §3 claims (pendingPermission was already ConcurrentHashMap, step counter failover was already fixed). Added suppressUnsupportedCompileSdk. Updated coverage counts. |

Ten passes in ten days. See §4 for what that produced and what it cost.

---

## 2. What was found and fixed

### 2.0 The 2026-08-06/07 A-grade sweep (v0.65.0)

An eight-phase remediation pass off a 5-reviewer audit, one branch (`fix/a-grade-sweep`),
one commit per phase. P0s: tool-call history is now serialized back to all providers
(per-provider request formats, previously every tool turn was silently dropped from
the second request onward); the `%%` LIKE-pad recall bug (every query returned
"freshest 15"); permission/confirmation/cost-approval unified onto one typed
pause/resume gate (per-conversation `PendingGate`, replacing magic-string parsing and
a dialog that fed the wrong approval set); per-call stream handles (concurrent streams
no longer clobber each other); dream tagging no longer destroys embeddings; backup
restore is snapshot-rollback + non-cancellable (no more purge-all on a failed import).
Then: reliability follow-ups (retry classification, MCP protocol compliance, Kling JWT,
embedder cache keying), consciousness wired to real DB signals (`DriveSignals`),
evolution rebuilt around LLM patch authoring (20 actions → 4, all with typed rollback
snapshots and deterministic outcome scoring), calendar monitoring moved from a
permanent FGS to a 15-min Instances-API worker, screen capture rebuilt as a proper
MediaProjection FGS with per-capture consent, UI fixes (brief consume-once, app-lock
tri-state, widget scopes), and the toolchain jumped two years: Kotlin 1.9.24 → 2.4.10
(K2), AGP 8.2.2 → 9.3.1, Gradle 9.7, Compose BOM 2026.06, Room 2.8.4, Hilt 2.60.1,
with real R8 minification and upload-keystore release signing. Also fixed along the
way: the AgentRun approval loop now retries the step after approve (with a
permission/cost distinction), MemoryDatabase schema exports 7–10 were regenerated
(1–16 all committed), and the four known-flaky time-dependent test classes were made
deterministic. Tests 1,821 → 2,014.

### 2.1 Concurrency and correctness

- **ToolRegistry `ConcurrentModificationException`** — `mutableMapOf` written by Hilt on
  main and read from coroutines. Now `ConcurrentHashMap`.
- **DagResolver duplicated** in `AgentRunExecutorWorker` with a string-splitting JSON
  parser that broke on step IDs containing commas. Worker now delegates; parser uses
  real deserialization.
- **Anthropic parallel tool-call deltas mis-routed (P0).** `Brain.fromProvider` discarded
  the provider-resolved tool id for delta-only chunks, so `lastOrNull()` sent arguments
  to whichever tool registered most recently. Two parallel tools got each other's
  arguments.
- **Same bug class in every OpenAI-compatible provider (P0).** The `tool_calls[index]`
  field was ignored; only the first delta carries an id. Fixed with an index→id map in
  `OpenAiCompatProvider` and `CustomOpenAiCompatProvider` — covers OpenAI, DeepSeek,
  Groq, Ollama Cloud, NVIDIA, Together, Fireworks.
- **EmotionEngine thread safety** — now `AtomicReference`.

### 2.2 Data integrity

- **`ConversationStore.save()` dropped `agentId`** — a latent bug that silently re-tagged
  every non-General agent conversation as General, affecting specialist selection, memory
  scoping, and persona.
- **Backup roundtrip resurrected soft-deleted conversations** — `deletedAt` missing from
  the backup DTO.
- **Soft-deleted conversations leaked** into `allWithEmbeddings()`, `missingEmbeddings()`,
  and `mostRecent()` — wasted embedding calls and could resume into a deleted chat.
- **Fresh installs missed the `deletedAt` index** — only the v5→v6 migration created it.
- **`MemoryBackup` omitted `scope`** — backup/restore lost every agent-scoped memory and
  leaked them across agents.
- **`memory_feedback` not purged** on restore — orphaned rows accumulated.
- **`daemonEnabled` missing from `PreferencesBackup`.**
- Backup schema reached v12 with all 23 entity types wired.
- **Schema v13 closed the last gap: 8 of the 48 Room entities had no backup class at
  all.** Export → wipe → restore silently dropped the creative artifact dependency
  graph, continuity issues, "what if" simulations, the entire evolution evidence
  trail, the user's responses to proactive suggestions, and model-routing outcomes.
  No error, no indication of what had gone. Seven are now covered;
  `CreativeGenerationJobEntity` is deliberately excluded as in-flight execution state
  (a restored `status = "running"` row holds a `providerOperationId` nothing is
  polling any more, so it would never advance). Note the 07-26 review's claim that v12
  "wired all 23 entity types" was itself wrong — those eight backup classes did not
  exist.

### 2.3 Security

- Evolution tools misclassified `READ_ONLY` while writing to `candidateDao` and
  `proposalStore`, bypassing incognito mode.
- MCP connections had no SSRF validation beyond an HTTPS prefix check — cloud metadata
  endpoints were reachable.
- SSRF TOCTOU in `HttpFileReadTool`, `HttpFileWriteTool`, `FirecrawlFetchTool`,
  `WeatherTool` — validate-then-fetch as separate calls.
- SSRF validation added to the user-supplied custom endpoint base URL.
- Redirects disabled on the base OkHttp client.
- Gemini API key moved from the URL query string to an `X-Goog-Api-Key` header, keeping
  it out of logs and proxy history.
- `HttpFileReadTool` OOM — read the whole body before truncating; a large URL crashed the
  app. Now streams with a byte cap.

### 2.4 Dead or placebo surfaces

These were UI that looked functional and did nothing — the most user-visible class of
bug found across all passes:

- `EmotionEngine.save()`/`load()` existed but were never called; 4D emotional state reset
  every cold start.
- `SettingsViewModel.emotionSnapshot` and `daemonThoughtsCount` were `MutableStateFlow`s
  nothing ever wrote to. The Emotion & Daemon section always showed "No emotional data
  yet".
- `DreamConsolidator` phase 6 was a no-op stub.
- `TasteEngine` bucketed by attribute *value* instead of *key*, collapsing `tone:concise`
  and `style:concise` into one bucket and making the style profile meaningless.
- `PolicyEngine.evaluate()` was never called; `ToolExecutor` duplicated the gates inline.
- `TraceSink` had zero production callers.
- `EvolutionScheduler` was never started.
- MCP was unwired from the tool registry.
- `BeliefsViewModel.select()` was a TODO stub.
- Duplicate evolution routes both loading the same screen.
- `ChatGptSubscriptionProvider.listModels()` always 401'd — it queried `api.openai.com`
  with a `chatgpt.com` session token, so the picker only ever showed `gpt-4o`.

### 2.5 Correctness of defaults

- **Auxiliary model selection ranked candidates by name length**, which inverted its own
  intent: `gpt-4o` (6 chars) sorts before `gpt-4o-mini` (11), `claude-opus-4-5` before
  `claude-haiku-4-5`. The suffix marking a model as small also lengthens its name, so
  every rerank, query rewrite, and compaction ran on the *expensive* model. Replaced with
  `CheapModelHeuristic` (tier markers, digit-boundary parameter matching) at all three
  sites: the agentic loop, `resolveCheapModel`, and `ConversationCompactor`.
- **The pre-answer planning call fired on every message** over ~20 chars, adding a second
  billed request and up to 15s before the first token. Now opt-in, default off, with a
  Settings toggle.
- Conversation compactor threshold was artificial; now queries the real per-model context
  window.

### 2.6 Test quality

Three defects where tests verified syntax rather than behavior, or nothing at all:

- **`NonRetryableStatusCodesTest` scanned provider `.kt` files as text** for literals like
  `"429"` and `"!= 401"`. It verified nothing real, and the 07-26 Pass 2 review records
  changing production code from a negative to a positive retryable check *purely to
  satisfy the string scan* — the test dictating implementation style while checking
  nothing. It could also pass vacuously: hardcoded absolute paths with a `mapNotNull`
  fallback meant an unmatched path left an empty file list and every loop over it
  succeeded trivially. Replaced with 11 MockWebServer tests asserting on
  `ProviderError.retryable` against real status codes.
- **`NavigationReachabilityTest` had the same silent-skip defect** — a hardcoded
  `D:/aura-android-clean` path and a bare `return` when nothing matched. Now uses relative
  paths and fails loudly.
- **Three source-scanning tests were kept deliberately** (`AuraPaletteBoundaryTest`,
  `InsetOwnershipPolicyTest`, `NavigationReachabilityTest`) — they check architectural
  boundaries with no runtime equivalent. The distinction that matters: scanning source to
  enforce a boundary is legitimate; scanning source to verify behavior that is directly
  testable is not.

### 2.7 Resolved despite being repeatedly listed as deferred

Both of these were carried as open items in the 07-25 and both 07-26 reviews. Both were
already done by 336e07c9 — the reports were stale and actively misinforming:

- **Agent scope on world model / taste / profile tables.** Shipped: `agentScope` is on the
  entities with indices, MemoryDatabase at v14.
- **Evolution rollback "covers 7 of 20 actions".** `EvolutionRollbackManager` now handles
  all 20.

---

## 3. Still open

Verified against HEAD after the 2026-08-06/07 A-grade sweep (v0.65.0). This is the
section to maintain. Items the sweep closed — stale dependencies, the AgentRun one-way
approval loop, the missing schema exports 7–10, the single global permission slot, and
the four known-flaky time-dependent test classes — have been removed; see §2.0.

### Correctness

| Item | Detail |
|------|--------|
| `ToolExecutor` pins an IO thread per tool | `runInterruptible(Dispatchers.IO) { runBlocking { … } }` occupies an IO thread for the tool's whole duration, including for purely-suspending tools that would otherwise release it. Bounded to 8 via `limitedParallelism`. **Cancellation itself is correct** — see `ToolExecutorCancellationProbeTest`. |
| `recentTopics` keyword quality | Now filters through the shared `StopWords` list, but the heuristic is fundamentally a word-frequency counter over titles + summaries. Expect low-signal keywords to still appear. |
| Per-step data assertions for MemoryDatabase hops 7–10 | Schema exports 1–16 are all committed now and the full-chain migration test validates the end state, but individual hops inside 6..10 still have no per-step data assertion. |

### Coverage

- 30 of 33 ViewModels have dedicated tests; Compose UI screens (~29) remain untested
  as screens. ViewModels with business logic are tested; Compose UI screens are harder
  to unit-test meaningfully and are exercised through manual use. The ROI of Compose UI
  unit tests on a personal app is low.
- `runCatching` blocks are enforced-handled by `SilentRunCatchingAuditTest` (every block
  must have an `.onFailure`/`.getOr*`/`.fold` handler within scan range; 1 allowlisted
  exception). Silent-but-handled fallbacks (`.getOrNull()` without logging) still exist,
  mostly JSON-parse sentinels where that is the correct behavior.
- Instrumented coverage (64 device test methods: migration chains + smoke tests) runs
  only on a connected device — not in CI.

### Architecture

- **11 separate Room databases.** No cross-database transactions or joins, 11 independent
  migration chains, global search fans out across all of them, and backup must coordinate
  11 schemas — which is why `BackupManager.kt` is the largest file in the project.
  Consolidating to one or two databases is a large but bounded change.
- **Scope versus depth.** 76 tools, 17 providers, ~29 screens, plus evolution, dream,
  taste, world model, creative council, production pipelines, agent DAG runs, and an MCP
  client — for a single-user personal app. Several of these subsystems are 200–500 lines:
  the surface area is large relative to the depth behind each. This was the 07-17
  assessment's central diagnosis ("too much honest substrate, not enough honest surface").
  The sweep deepened several seams (gates, evolution, capture) but did not shrink the
  surface.

---

## 4. On the review process itself

Ten passes in ten days produced real fixes — the parallel tool-call routing bugs and the
data-integrity family in §2.2 were genuine and would have hurt users. That is not in
question.

But the cadence developed failure modes worth naming, because they are the reason this
file exists:

**The passes began auditing each other.** The 07-26 review's largest section is a table of
findings from the 07-26 subagent audits that were already fixed — 12 verified false
positives. Its Pass 2 successor is largely a verification that Pass 1's findings held. The
audits were written against one HEAD and never re-verified after the next hardening pass
landed.

**Deferred items calcified.** "Agent scope" and "evolution rollback 7 of 20" appear in
three consecutive reviews as open. Both were done. Nobody re-checked before re-listing
them, so each pass copied the previous pass's stale list forward.

**Reports outnumbered the fixes they described.** 29 documents, several thousand lines,
for changes that git already records with more precision and no drift.

**Test count became the quality metric.** It grew 1,004 → 1,286 while 45 screens and 10
ViewModels stayed untested and at least three tests asserted nothing. Volume tracked where
testing was cheap, not where risk was.

The practical guidance: prefer a running app over another audit. The bugs that kept
surfacing across passes — a model picker showing one model, global search routing to a
dead name, parallel tool calls swapping arguments — are things a week of daily use would
have surfaced faster than ten review cycles did. When a pass is genuinely warranted,
update §3 of this file rather than adding a new dated report.

---

*Consolidated 2026-07-26.*
