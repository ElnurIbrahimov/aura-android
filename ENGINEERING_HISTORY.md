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
- **Source-scanning tests were kept deliberately** — they check architectural boundaries
  with no runtime equivalent. The distinction that matters: scanning source to enforce a
  boundary is legitimate; scanning source to verify behavior that is directly testable is
  not.

  *Corrected 2026-08-08:* this bullet said "three" and named `AuraPaletteBoundaryTest`,
  `InsetOwnershipPolicyTest`, `NavigationReachabilityTest`. There are **nine**, across both
  modules — the three above plus `ScreenContractTest`, `ExtendedScreenContractTest`,
  `ScreenViewModelWiringTest`, `StartupThemeContractTest`, `SilentRunCatchingAuditTest` and
  `AgentRunExecutorWorkerParallelContractTest`, ~23 test methods in total.

  More important than the count: **the silent-skip defect this section claims to have
  eliminated was still present in four of them.** `ScreenViewModelWiringTest` carried a
  literal `if (!dir.exists()) continue`, and `ScreenContractTest`,
  `ExtendedScreenContractTest` and `SilentRunCatchingAuditTest` resolved
  `File(System.getProperty("user.dir"), …)` with no existence check — so a working-directory
  change would have left every one of them scanning an empty file list and reporting no
  violations. The five that already failed loudly did so because they read *named files*
  (`readText()` throws) or used `?: error(...)` on a candidate list. Closed 2026-08-08: all
  scans now go through a `sourceDir` / `requireNonEmpty` pair per module, and
  `SourceScanGuardTest` asserts that both guards actually throw.

### 2.7 Resolved despite being repeatedly listed as deferred

Both of these were carried as open items in the 07-25 and both 07-26 reviews. Both were
already done by 336e07c9 — the reports were stale and actively misinforming:

- **Agent scope on world model / taste / profile tables.** Shipped: `agentScope` is on the
  entities with indices, MemoryDatabase at v14.
- **Evolution rollback "covers 7 of 20 actions".** `EvolutionRollbackManager` now handles
  all 20.

### 2.8 Lexical recall moved to FTS4 (2026-08-08)

Two findings that turned out to be one problem, recorded here because the shape of the
mistake is worth keeping.

`MemoryStore.query` fetched candidates with six `content LIKE '%word%'` clauses and then
built its BM25 index from those candidates. The six clauses were a hard six-term ceiling
written into a DAO signature — and since the query is the user's whole message, everything
past the sixth non-stopword was silently dropped. Vector search still saw the full text, so
recall *degraded* rather than broke, which is exactly why eleven review passes never
surfaced it.

The IDF failure was subtler. Every candidate contained a query term by construction, so
`df` approached `N` and `ln((N − df + 0.5) / (df + 0.5))` went negative for precisely the
terms that should discriminate; all of them clamped to the 0.1 floor. `normalizedScore`
then divided by the bare sum of IDF while `score` multiplies by `(k1 + 1)`, so results
routinely clamped to exactly 1.0. Because `Retrieval.rankCandidates` fuses its six signals
by **rank order**, a tied `textScore` is indistinguishable from no lexical signal at all —
the pipeline was documented as "BM25 + RRF + cross-encoder reranking" and was running five
signals and a coin flip.

Neither could be fixed alone: there was no cheap way to get corpus statistics
(`countOnce()` is unscoped, `allByScopes` is an unbounded `SELECT *` including the
embedding BLOB), which is what FTS provides.

The general lesson: **a component can be present, tested, and inert.** `BM25Test` had a
test named `IDF weights rare terms higher` that asserted only `rareScore > 0f` — true
whether or not IDF discriminated. It passed for the entire life of the bug. A test that
cannot fail for the reason it is named after is worth less than no test, because it
occupies the slot where a real one would go.

### 2.9 Three provider-layer defects found while scoping prompt caching (2026-08-10)

Found by tracing the request path end to end rather than by a review pass. Two were inert;
the interesting part is *why* each stayed invisible.

**`ToolRegistry.definitions()` returned tools in undefined order.** The backing map is a
`ConcurrentHashMap`, so `values` iteration order is unspecified and shifts as the table
resizes — and `McpToolBridge.syncTools` registers after startup, so it does resize
mid-process. Harmless until it isn't: every provider puts the tool array at the head of the
request, and providers that cache prompt prefixes hash those bytes, so an unstable order is
a total cache miss that reports no error anywhere. Fixed by sorting on name, with a
version-counter cache behind it. The sort is the fix; the cache is incidental.

**Gemini dropped `enum` and `default` from every tool schema.** `GeminiProvider` carried its
own inline schema renderer emitting only `type`/`description`/`required`, diverging from the
`toJsonSchema()` the other three providers share. Live blast radius today is zero — the only
tool declaring an `enum` is `tavily_search`, and `filterSearchTools` removes it from every
request — which is precisely why it survived: the defect was real, permanent, and
unreachable, so no amount of use would surface it. The next enum on a live tool would have
been dropped silently, and a model given no enum invents values. Fixed by sharing the
renderer behind a `sanitizeForGemini` adapter. The adapter has to keep the old renderer's
`else -> "string"` type coercion, which is likewise inert today and is the only thing
standing between a future exotic `ToolProperty` type and a 400.

**`KnowledgeGraphTool` asked for a model id that could never resolve.** It called
`providerRegistry.chat("default", …)` on the belief that `parse` resolved `"default"` to the
user's default model. `parse` requires a non-blank `provider:model` pair, so it always threw;
the `getOrElse` fallback was the only path that had ever executed, paying for a live
`/models` listing on every extraction and then taking whichever model happened to be first.
A ten-line comment above it described behaviour that had never run.

This one is the most instructive of the three, because there *was* a test — and the test
asserted the bug. `KnowledgeGraphToolTest` stubbed `chat("default", …)` on a mockk, which
answers any argument happily, so the suite pinned the broken call shape and reported green
for its whole life. §2.8's lesson was that a component can be present, tested, and inert;
this is the sharper version — **a mock can make a call that always throws in production look
like the contract**. The replacement injects `CheapModelResolver` (whose KDoc already named
this class of call as its target) and adds `never asks the registry for the literal string
default` as an explicit `coVerify(exactly = 0)`, which is the assertion a mock cannot absorb.

### 2.10 The 2026-08-10 capability sweep (21 commits, 2,346 → 2,609 tests)

Four tracks: the provider layer, retrieval quality, screen control, live voice. §2.9 is the
first three commits of it. What follows is the part worth keeping — not the feature list,
which git has, but the defects the work exposed and why each was invisible.

**Every usage report from every OpenAI-compatible provider was being discarded.**
`OpenAiSseParser` returned early on an empty `choices` array, and OpenAI's final usage frame
has exactly `choices: []`. Twelve of seventeen providers therefore reported no usage at all,
and `ProviderRegistry` billed them against a `content.length` estimate. The parse was moved
above the early return. Nothing about this was visible from the outside: an estimate looks
exactly like a measurement once it is in a ledger.

**`EvolutionPatchAuthor` permanently discarded a self-improvement candidate whenever the
model's JSON arrived wrapped in prose.** A parse failure became `Result.Rejected`, which is
terminal — a transient formatting slip was recorded as a considered verdict. `Result` gained
`Inconclusive` so the candidate stays pending. The lesson is narrower than "handle errors":
the type had two cases where the domain has three, and the missing case was silently folded
into the one that destroys work.

**Four subsystems each carried their own fence-stripper**, one of which (`LlmWriteGate`'s
non-greedy `\{(.*?)}`) returns a *truncated* object on any nested brace. Now one
`StructuredJson.stripFences`, depth-based and string-aware. The four fallbacks were kept
rather than deleted — structured output makes them rare, not unnecessary, and on Anthropic
in schema-free mode the fallback *is* the mechanism.

**Three of the six RRF retrieval signals were echoes of the SQL ordering.** `rankBy` used
dense ranking, so tied values still received distinct ranks broken by input order — which is
`ORDER BY m.decayScore DESC`. `WriteGate` emits five importance values with a catch-all at
0.5, so on a 25-row pool most rows tie: `importance` was in practice a second `decayScore`
vote, `decayScore` a third on a fresh install, and `textScore` a fourth in the vector
fallback branch, which hardcodes it to zero. Standard competition ranking fixes all four at
once, because a signal whose values are all equal now gives everyone rank 1 and cancels out
of the comparison. This is the kind of defect that cannot be found by reading the fusion —
it lives in the interaction between a tie-break and a query two files away.

**The retrieval stack had no way to be wrong.** Every ranking test in `RetrievalTest` was a
monotone-dominance assertion that cannot fail, and two were worse: one is misnamed and
supplies different scores where it claims equal ones, the other asserts an OR over a 3-slot
window on a 10-document pool and passes by chance. A real harness now lives in
`aura-core/src/test/.../memory/eval/` — nDCG@5/@10, recall@k, MRR, zero-result rate, per
query-class breakdown, gated on no-regression against a committed baseline rather than an
absolute score, because absolute thresholds get quietly lowered. Its first run reported
`correctly_empty_rate = 0.0`: the vector fallback's 0.05 cosine floor means "return nothing"
is weaker in production than `MemoryStoreQueryTest` implies. That finding is the harness
paying for itself on day one, and it is still open.

**Changing the embedding model corrupted the corpus in three places and re-embedded nothing.**
`rebuildEmbeddings` filtered on `embedding == null`, so a model switch re-embedded zero rows
and could never converge; the update path wrote the vector without its model tag, so even
the rows it did touch kept lying. Meanwhile `DreamConsolidator` filtered only for
`embedding != null` and then clustered stale and fresh vectors together **and wrote the
results back as new memories** — the one durable-corruption path in the set, and the reason
the embedder change and the consolidator fix had to land in the same commit. The likely
on-device case is 384→384, where the existing dimension guard never fires and the failure is
silently meaningless cosines with no log at all. `Embedder` now reports what it produced,
and a `ReembedWorker` drains the backlog highest-value-first so an interrupted rebuild fixes
what matters before what doesn't.

**A relaxed mockk intercepts interface default methods.** Adding `isCurrent`/`embedTagged` as
defaulted members of `Embedder` silently changed the behaviour of every existing test that
mocked it, because the mock answers the default method instead of running it. Eight failures,
all of them the mock rather than the code. This is §2.9's lesson in a new costume: a mock
does not merely fail to catch a bug, it can manufacture one.

Two features were added rather than fixed, and both are documented in `architecture.md`
rather than here. The parts worth recording as history are the constraints that shaped them:
screen control reads the accessibility tree and not a screenshot because `MediaProjection`
requires an attached Activity and screen control by definition happens while another app is
foreground — the alternative is not expensive, it is impossible; and live voice sits beside
`Provider` rather than inside it because `chat` is a one-shot non-suspend `Flow` and forcing
a duplex session into it means a default-throwing method on all seventeen implementations.

**`end()` deadlocked on three of the four ways a call ends.** It ran
`cancelAndJoin` on the very jobs it can be invoked from: the notification's End
action arrives on `endJob`, a fatal provider error on `eventJob`, budget expiry
on `micJob`. Joining the coroutine that is doing the joining waits forever, and
because everything after that line is skipped, the socket stays open and keeps
billing per audio-minute while the UI already shows the call as over. Silent: no
exception, no log, just a call that will not end. Found within minutes of writing
the controller's first direct test — the coverage gap and the bug were the same
fact, which is the argument for the test rather than for the fix. Fixed by
cancelling without joining any job the current coroutine is running inside.

**And the sweep committed the same defect twice while documenting it** — then
built the gate that would have caught it, which immediately found two more.
`DeadConfigFieldTest` scans the config types for fields with no reads anywhere.
Its first run flagged `ToolPolicy.costCeiling` and `approvalExpiryMs`, both
pre-existing and both genuinely unenforceable as the types stand (see §3). Its
exception list is a map, not a set, so no entry can be added without a written
reason — "we will use it later" is how every one of these got in, and it is not
one. It is mutation-tested: an injected dead field fails it.

`RetrievalConfig.rerankMode` and `.trace` were both added by the config commit
and neither was ever read: the rerank decision tested `rerankModel != null`
inline at two call sites, and `RetrievalTrace` was a data class nobody
constructed. Found by grepping every field of the config for reads after the
`allowedScopes` fix, which is the check worth repeating rather than the fix.
`rerankMode` also shipped two values the code could not honour — `LOCAL`, for a
cross-encoder that does not exist, and an `LLM` / `LLM_IF_MODEL_SET` pair that
would have behaved identically, since neither can rerank without a model. Both
deleted; the enum is two values because two is how many there are.

**`ToolPolicy.allowedScopes` and `PolicyResult.ScopeDenied` were both declared
and neither was ever evaluated.** `PolicyEngine` had no scope branch at all, so a
user who restricted a tool to specific apps or domains got a setting that did
nothing and reported nothing — the §2.4 "dead or placebo surface" pattern, in the
security layer. It is now evaluated, and it fails closed: an allowlist that is
configured but unenforceable denies, because a call site forgetting to pass its
scope must not silently bypass a restriction the user deliberately set. The
matcher's first version extended a match at a dot so `com.google` would cover
`com.google.android.gm` — its own test caught that the same rule lets
`example.com.evil.net` past an `example.com` allowlist, since a package
hierarchy and a lookalike domain are the same string shape and nothing at that
layer can tell them apart. The permissive reading was dropped rather than
special-cased.

**`MemoryReranker` failed silently, twice, and swallowed cancellation.** Both
`catch (e: Exception)` blocks returned a plausible list with no log — the outer
one falling back to RRF order, the inner one scoring a whole batch neutral at
0.5, which ties every candidate in it so they keep RRF order while the batches
that did score get reordered around them. A half-reranked list that looks
reranked. In both, `CancellationException` is an `Exception`, so a caller giving
up was reported as a successful rerank while the model call kept running for a
result nobody wanted — the third subsystem in this repo's history with that
exact defect. Worth noting why the CI gate missed it: `SilentRunCatchingAuditTest`
scans `runCatching`, and these were `try`/`catch`.

Three of the sweep's own gates fired against its own code. `SilentRunCatchingAuditTest`
caught a new `runCatching` with no handler; `ScreenControlContractTest`'s absence assertions
failed because the comments *explaining* the absences named the flags they assert are absent
(the scanner now strips comments — the same false positive the two older source scans have);
and `check-test-count.sh` blocked three commits until the docs matched. That is the CI
working as designed on the person who wrote it, which is the only real test of a gate.

---

## 3. Still open

Re-verified against HEAD on 2026-08-08, on 2026-08-10, and on 2026-08-13. This is the
section to maintain. Items closed by
the 2026-08-06/07 sweep — stale dependencies, the AgentRun one-way approval loop, the
missing schema exports 7–10, the single global permission slot, and the four known-flaky
time-dependent test classes — have been removed; see §2.0.

Baseline at the 2026-08-08 check: **2,152 unit tests, 0 failures**; `assembleRelease`
(R8 + resource shrinking) succeeds, 11.97 MB APK; `lintDebug` clean of errors (18 warnings
across both modules); `lint-logging.sh` and `check-version-docs.sh` both pass.

*2026-08-09:* **2,225 unit tests, 0 failures.** That number had been stated as 2,152 in
README.md and architecture.md for a day's worth of commits — `check-version-docs.sh` gates
the version string and looks at nothing else, so every other figure in those documents
drifts freely. `scripts/check-test-count.sh` now reads the JUnit XML and fails when a doc
disagrees with it, when the suite is not green, or when a listed doc has dropped the count
entirely. It runs in the `build-test` CI job rather than `gates`, which has no JDK and could
only guess, and it errors out when no XML is present — a gate that reports OK over an empty
file list is the §2.6 defect, not a gate. The baseline figure above is left as written: it
is a dated record of one run, which is this file's job.

*2026-08-10:* **2,609 unit tests, 0 failures**, after the §2.10 capability sweep. Both lint
tasks clean of errors, `assembleRelease` succeeds under real R8, all three gate scripts
pass. Every one of the sweep's 21 commits was gated on that full set before the next began.

*2026-08-13:* **2,913 unit tests / 436 suites, 0 failures** at v0.66.0 (versionCode 81).
Both lint tasks clean of errors — 70 warnings, of which the three `TrustAllX509TrustManager`
security findings are all inside BouncyCastle 1.72 rather than app code, corroborating the
dependency item below. `assembleRelease` succeeds, 12.29 MB. All three gate scripts pass.

*2026-08-18:* **3,272 unit tests / 491 suites, 0 failures** after the remediation below.
All four gate scripts pass. `assembleRelease` succeeds under real R8, 12.50 MB. MemoryDB is
at v27. The count moved 3,256 → 3,290 → **3,245** → 3,272 across the pass: the dip is 45
tests deleted with the dead code they covered, and it is recorded here because a figure that
only ever rises is the mechanism §4 describes.

*2026-08-18 (second pass):* **3,317 unit tests / 497 suites, 0 failures.** The four
dependency-and-configuration rows below are closed or corrected — BouncyCastle pinned to
1.80, the version catalog aligned with what resolves, `REFRESH_WIDGET` replaced by an
injected `WidgetRefresher` and gated by `ManifestExposesNoCustomActionsTest`, and the
gitignore exception that was quietly tracking a 40 MB APK removed. Two smaller defects
found by the same audit went with them: `QuickAskActivity.updateWidgetWithResponse` read
the app-lock DataStore with `runBlocking` on the main thread (the WidgetConfigActivity
pattern, applied — the read now fails closed off the main thread), and the in-app
browser's WebViewClient consumed nothing, so an in-page redirect could reach `intent://`
or `javascript:`; `allowedInPageScheme` now restricts navigation to http(s), tested.
Widget push-refresh verification stays on the device pass.

*2026-08-19:* **3,337 unit tests / 499 suites, 0 failures.** The Living World gained its
belief layer (four commits on feat/world-author): deviations-from-truth inside WorldState —
no row means accurate common knowledge — with witness/rumor/stale mechanics and discoveries
as capped belief_reveal events; contested claims weighted by rival-believed might, with
Effect.SpreadLie as the lie and bluster_when_weak seeded into new worlds; the scorer's two
documented-absent factors (reach, surprise) measured on the event itself, neutral at zero so
belief-free worlds score byte-identically; and living_events compaction finally wired —
trimNoiseBefore keeps the notable spine, paid narration and every quiet_interval (replay
walks those), trimBefore becomes the emergency valve it never had a caller for, and both run
as DecayWorker's sixth sweep above the decayEnabled gate. Device look owed: reveal rows on
the Living tab.

*2026-08-19 (Phase B):* **3,356 unit tests / 500 suites, 0 failures.** Scenes became
transactions, without a migration and without ever endangering paid text. A StoryBeat now
declares REQUIRES and EFFECT assertions (subject @ predicate = value, parsed skip-never-guess);
declared effects land as authored canon in a declared| id namespace at confidence 1.0, prior
single-valued facts they contradict are superseded silently — declaring the change is the
ruling — and the extractor disagreeing with the declaration files a severity-error issue that
carries its machine-readable remedy (suggestedPatchJson's first writer; advisory, no applier).
Continuity issues gained deterministic pair-derived ids and stopped re-opening pairs the author
ruled intentional. Canon now enters drafting: a capped CANON section (facts for the beat's POV,
setting and assertion subjects; a disagreeing precondition renders as one informational note)
and a THE WORLD RIGHT NOW section rendering WorldStateBrief's standings, injected only while
the new WorldBible.storyCursorTick pins the Living World's present exactly — the Living tab
grew the pin button. Both reads are best-effort: a throwing canon DAO still commits its scene.
Device look owed: the pin flow and a CANON-fed draft.

*2026-08-19 (Phase C):* **3,371 unit tests / 503 suites, 0 failures.** Timelines fork.
The one migration the spine needs landed first: living_worlds.genesisJson (MemoryDatabase v29,
backup schema 29, replay hop + instrumented migrate28To29, every doc literal in the same
commit) — chosen over re-seeding because a bible edit or a template retune would silently
reconstruct a different genesis. Fork-at-present copies the moment and derives its salt
(deterministic: a fork is a ref, not a dice roll; same name + same moment = same world).
Fork-at-past replays genesis through the *recorded* fold spans — quiet_interval rows are the
fold record, A4's compaction preserves them by construction — with the tripwire stated: any
future god-edit must be event-sourced or fork-at-past breaks silently. Pre-v29 worlds are
honestly refused. observeEventsDeep gives a fork its inheritance (ancestor pages bounded at
the fork tick); TimelineDiff names the first field-wise parting; living_world_query (81st
tool, READ_ONLY) ranks drama by the notability already paid for and diffs branches
page-honestly; the Living tab grew branch chips, fork buttons (present and from-any-event
where genesis allows), the comparison card, and a Biggest-moments filter running on the same
DAO as the tool. Device look owed: fork a world and watch both branches tick.

*2026-08-19 (Phase D):* **3,376 unit tests / 504 suites, 0 failures.** The return ritual.
LIVING_WORLD joined the finding-type registry (appended — notification ids are 1100+ordinal),
and LivingWorldReporter now hands its report to ProactiveNotifier after the in-app card is
recorded, never before; the category defaults to EARNED, so it stays silent until the ledger
sees engagement, with the Home card as the always-on surface. Opening Home or the Living tab
nudges a world two or more ticks behind through the existing REPLACE-unique catch-up (one
tick behind is hourly steady state — LivingWorldCatchUp is the pure policy). The Living tab
gained "Since you left": a per-open snapshot of the moments missed past a per-world lastSeen
marker (worldId=tick CSV in preferences), top five by notability, then the marker moves.
Narration stayed inside its caps throughout. Device look owed: one EARNED-to-ALWAYS
notification and the since-you-left card after a real absence.

*2026-08-19 (Phase E — the spine closes):* **3,387 unit tests / 508 suites, 0 failures.**
The taste loop. Drafted beats are tappable; the editor dialog is deliberately plain — the
goal is the revision chain and the signal — and its save is CreativeArtifactStore.revise's
first production caller: an identical save writes no revision and records mild approval
(+0.5), a change lands as an authorKind="edit" child of the generation revision (the beat
keeps pointing at what the extractor read; the artifact's current revision moves to the
author's text), and EditDistanceLite's keep-ratio picks the tier — touch-up −0.25, the
designed recordEdit −0.5 finally called, rewrite −1.0. Every signal recomputes the project
profile and the global one. Rewrites alone file skill_failed evidence against the craft
skill's id ("author_rewrote"), touching none of evolution's gates. Taste reaches drafting at
the resolver, capped at 600 chars — with no profile the resolve is byte-identical, pinned.
Found and fixed along the way: the interruption-policy writer had shipped its own template
escape (every explicit ALWAYS/NEVER fell back to EARNED on read) — codec extracted, round
trip tested. All five phases of the world-author spine are code-complete; what remains is
the device pass this file has asked for since §4 was written.

### The 2026-08-18 remediation (eleven passes in, and the eleventh was an audit)

Prompted by a four-reviewer read of the whole repo. Its finding was not a list of
bugs but a shape: **the code is right and the seams are wrong.** Of the eight
headline defects, not one was a logic error — every one was two correct pieces
wired together incorrectly, and 3,300 unit tests at 0.03 s apiece could not see
any of them. `DeviceSmokeTest`'s KDoc had already said this; the pass confirmed it
held for a different eight defects than the thirteen it named.

What that means for the next pass is in §4, and it did not change: this one found
real things, and it found them by reading, and reading is still not the cheapest
way to find them.

**Closed.**

- **`deep_research` and `parallel_research` could never complete.** `ToolContext`
  was built without a timeout and took the 30 s default, which `ToolExecutor`
  enforces; `DeepResearchTool` budgets 120 s internally. Every call died a quarter
  of the way in, after paying for the searches, and returned a generic
  `tool_timeout` indistinguishable from a slow network. `delegate_to_agent` and
  `knowledge_graph_extract` sat at *exactly* 30 s, a tie the executor always won,
  so their own timeout messages were unreachable code. `Tool.timeoutMs` now
  carries a per-tool budget and `ToolTimeoutConsistencyTest` refuses a tool whose
  internal budget exceeds what it declares. `ProductionPipelineEngine` had already
  found and fixed this same defect on the agent-run path; the chat loop never got
  it.
- **The strategy bandit was being taught that failure is success**, in three
  distinct ways. `AgentEvent.Error` does not end the stream — the loop emits it
  and falls through to the unconditional `Done` — so `max_steps_exceeded`
  recorded a failure *and* a success (both α and β, which leaves the Beta mean
  alone and halves its variance, so a failed run made the arm more confident);
  and a provider error or an empty response set `finished = true`, missing the
  failure branch's code check entirely and recording a clean win. One latch, read
  once in `Done`. The same Error-then-Done shape had also made
  `consecutiveFailures` unable to reach 2, so the `>= 3` Deep Mode escalation was
  unreachable for every failure it was written to catch.
- **Every thumbs-up inside an agent conversation was a silent no-op.** Scope and
  project are different columns on `preference_signals`; the reaction was written
  with `projectId = ""` and then `recomputeProfile("agent:<id>")` was called,
  whose only parameter is the project id. Zero rows, early return. Only reactions
  in the general chat had ever reached a profile.
- **`BootReceiver` re-registered 7 of 11 periodic workers**, omitting
  `BackupWorker` — on precisely the OEMs that clear WorkManager state, which is
  the only reason that receiver exists. The weekly backup stopped at the first
  reboot and stayed stopped until the app was next opened, leaving no run record
  to be conspicuously absent. It now delegates to `ProactiveScheduler` rather than
  rebuilding requests: the inline copies had already drifted, and the dream
  request had lost its two-hour initial delay.
- **Three workers recorded failures as healthy runs.** `WorkerRunRecorder.record`
  takes the worker's own verdict, and `lastOutcome` initialises to `ok("")`, so a
  catch block that handles the throw and returns reports success over a failed
  run. `EvolutionWorker` (which also logged nothing at all),
  `CalendarCheckWorker` and `DecayWorker`. `Result.failed(cause)` now exists —
  its absence was load-bearing, because every catch block had to construct the
  failure by hand and two did not — and `WorkerFailureIsRecordedTest` scans for
  the shape.
- **The FTS update trigger fired on every column.** `MemoryDao.touch` runs once
  per returned memory on every recall, so a ten-hit query performed ten
  delete-and-reindex cycles over text that had not changed. `MIGRATION_26_27`
  scopes it to `AFTER UPDATE OF content`. The migration is not optional: every
  statement in `MemoryFtsSchema.TRIGGERS` is `CREATE TRIGGER IF NOT EXISTS`, so
  editing the SQL reaches fresh installs and silently leaves upgraded devices on
  the old definition — the hardest divergence to notice, because both look
  correct alone.
- **`AFTER UPDATE OF content` does not fix the decay pass**, and the trigger fix
  looks like it does. Room's `@Update` names every column in the SET list, so
  `updateAll` fires the trigger even when content is byte-identical.
  `runDecayPass` now reads a four-column projection and writes narrow updates —
  which also removed the `recent(10_000)` cap that had quietly stopped the tail
  of a large store from fading at all, and stopped ten thousand embedding BLOBs
  being materialised in a background worker to compute a number from three
  integers.
- **`evolution_evidence` grew without bound for a feature that is off.**
  `EvolutionHooks` consulted no preference, and `MemoryStore` calls
  `onMemoryRecalled` once per result from both recall branches — a five-index row
  per hit, on the user's critical path, into a table whose only readers are
  detectors `EvolutionWorker` never schedules. Gated inside the hooks with a
  cached read that fails closed, and `deleteOlderThan` finally has a caller.
  `EveryPruneHasACallerTest` is derived from the sources rather than a list,
  because this was the **fourth** time a retention window turned out to have none.
- **`saveGraph` was N+1 and non-transactional** — twenty to sixty round trips per
  turn, and a crash partway through left nodes stored with their edges missing,
  which `BeliefPromoter` would then read as fact.
- **The live-voice stack is reachable.** 1,476 lines and ~60 tests behind
  `RealtimeCallController`, which had no production caller, through
  `LiveCallSheet`, which had none either. `ProjectSpineIsWiredTest` was written
  *about* this stack, gated four other paths, and left this one dead for another
  two weeks — a gate protects what it asserts and nothing else. It asserts this
  one now. The non-atomic `update` extension shadowing kotlinx's at all nine call
  sites was fixed first, since wiring made the race reachable.
- **App lock covered one door of five.** "Unlocked" was `remember`-scoped inside
  `MainActivity`'s composition, so `QuickAskActivity` — the same `ChatViewModel`
  with memory recall — opened straight from the home screen with no check, and
  both widgets painted memories and reminder bodies onto the home screen
  regardless. `AppLockState` is process-scoped and relocks on a started-activity
  count reaching zero, *not* on one activity stopping: `MainActivity` launching
  `QuickAskActivity` stops `MainActivity`, and a per-activity relock demands a
  fingerprint mid-navigation.
- **`CaptureActivity` let any app write rows that read as the user's own words.**
  It is `exported="true"` and must be, and it auto-captured incoming text with
  `source = "user"` and the write gate deliberately skipped. Intent-delivered text
  is now `source = "shared"` with the gate's verdict applied. Not gated behind the
  lock, because `CaptureTileService` documents why capture-while-locked is
  deliberate — refusing the write would break the feature; refusing to *believe*
  it does not.
- **The streaming render was quadratic, and worse than the review thought.** The
  parse ran on every recomposition, and the cursor blink is an infinite
  transition — so the entire cumulative message was re-parsed on every *animation
  frame*, not every token. Memoized on `(text, colors)`, cursor split into a
  second cheap `remember`, publication throttled to 50 ms while every delta is
  still consumed. The asymptotics are not fixed and architecture.md says so:
  bounding publications makes the cost quadratic in elapsed time rather than in
  token count, and true O(n) needs incremental parsing.
- **Dead code removed:** `SpecialistRouter` (167 lines, no caller — the README
  claimed keyword routing that did not exist), `com.aura.pipeline.ProductionPipeline`
  (a duplicate of the live `creative.ProductionPipelineEngine`),
  `AgentTextAccumulator`, `ContextBundle`, `McpToolBridge.syncToolsUnprefixed`,
  `CapabilityRouter.providerKeys`.
- **`mapping.txt` is archived by CI for 90 days.** Every release crash before this
  was unreadable: R8 renames everything, the mapping lived in gitignored `build/`,
  and there is no Crashlytics — `CrashLogger` writing an obfuscated trace to a
  local file is the whole of what a user can report.

**Found while fixing, and worth naming because none were in the review.**

- `QuickAskActivity.updateWidgetWithResponse` rebuilt `RemoteViews` with only the
  text set, and `updateAppWidget` replaces the whole view tree — so after every
  Quick Ask the widget's body and Ask button stopped responding until the next
  30-minute refresh.
- Both `EvolutionBadgeViewModel` tests were vacuous, *including the one that
  looked real*. `pendingCount` is `stateIn(WhileSubscribed)`, so `.value` never
  leaves `initialValue = 0` without a subscriber; the sibling asserting `0` was
  reading the initial value, not the DAO. Both would have passed against a
  ViewModel wired to nothing.
- The first real `UserPreferences` test caught its own harness: the DataStore file
  persists across Robolectric test *methods*, so a mutating test made the defaults
  test read a written value and report it as the shipped default.
- `pip install sentence-transformers` is insufficient for Gate B —
  `nomic-embed-text-v1.5` needs `einops` through `trust_remote_code`, and the
  failure lands two models in, after two vector files already exist.

**A correction to the review itself**, recorded because the same mistake is easy
to repeat: one agent reported `Specialist.ALL` and its presets as dead. They are
not — `AgentStore` seeds the seven builtin agents from them, and `SpecialistRouter`
was merely their only *other* reader. Deleting the file wholesale, as proposed,
would have removed live coverage. The same held for `BeyondSotaBaselineTest`
(2 vacuous tests of 5) and the four `cancel does not throw` tests (not no-ops: an
exception fails them, and cancel-when-idle is exactly where
`RealtimeCallController` had a self-join deadlock). **Verify a deletion target
yourself before removing it.**

**What the instrumented job found on its first run, which is the point of it.**

The `app-instrumented` CI job added by this pass had never executed. Its first run
was red, and it earned its place immediately: 38 tests, 5 failures, identical
across two runs, none of them in code this pass had touched.

- **The chat header pushed its own buttons off screen.** A `maxWidth * 0.55f`
  budget was applied to the model pill *and* the project chip — 110% of the
  header between them — so at 320 dp with a long model name the three 48 dp
  trailing buttons were laid out past the right edge: present in the semantics
  tree, and unreachable by a thumb. The budget now subtracts the touch targets
  and the header chrome before the pills get a share.
- **Two `HomeContentTest` assertions could never have passed**, and this was the
  *third* blind fix to the same line — `performScrollToIndex`, then
  `performScrollTo`, both wrong for one reason nobody had checked: `HomeContent`'s
  root is a `LazyColumn`, which does not compose what is off screen, and both of
  those act on a node that must already be composed. `performScrollToNode` asks
  the list to bring the node in, which is the only ordering that exists here.
- **`ModelSelectionFlowTest` — the repo's only true end-to-end flow — was failing
  on a click that never happened.** `performScrollTo` on the key field stops as
  soon as the *field* is inside the viewport, leaving "Save & Test" below the
  fold, and Compose's touch injection does not bounds-check: it taps the node's
  origin, off screen, dispatches nothing, and throws nothing. The click appeared
  to succeed, `saveAndTestProvider` was never called, and the failure surfaced ten
  seconds later at an unrelated `waitUntil`. Established with a probe inside
  `saveAndTestProvider` that never logged, not by reading.

**And behind it, two real defects in `SettingsViewModel.reload()`** — both from
one cause. `reload()` assigns a whole new `SettingsUiState` rather than patching
the existing one, so every field it does not list silently reverts to a default.
The note beside its `credentialStates` line already records this mechanism biting
once. It was biting two more fields.

- **A key typed while Settings was still loading was erased, and saving then
  cleared the stored one.** `reload()` performs ~80 sequential DataStore and Room
  reads before it publishes, and the screen is interactive throughout; it then
  re-seeded every key field from disk. Tapping "Save & Test" wrote the *empty*
  draft, and `ProviderKeys.set("")` treats blank as a clear — so retyping a key
  during the load window could delete the working one. This is what made the
  instrumented test nondeterministic: two identical runs, one logging
  `draftLen=14` and passing, the next `draftLen=0` and failing, differing only in
  whether reload landed before or after the keystroke. `editedDrafts` now holds
  the prefixes the user is mid-edit on, and disk wins everywhere else.
- **Every model picker in Settings emptied on reload.** `availableModels`,
  `imageModels`, `videoModels`, `voiceModels` and `embeddingModels` are not among
  the fields `reload()` sets, and nothing refilled them: `applyCatalog` runs off a
  `StateFlow` subscription, and a `StateFlow` does not re-emit because a consumer
  discarded its last value. The picker opened with no rows immediately after a
  successful verify. Masked in normal use only because `ChatViewModel` forces a
  catalog refresh at startup. `reload()` now ends by re-deriving from
  `modelCatalogRepository.catalog.value`.

Neither is a logic error. Both are the seam shape this pass opened with, found the
way §4 says they get found — by running the thing.

**The document pipeline (2026-08-18), staged.**

`document_chunks` had a 13-method DAO, its own indices, backup mappers on both
sides, and had never held a row. Import wrote its chunks into `memories` instead,
which works — it is what recall still reads — and puts a thousand book passages
into the same corpus statistics as a few hundred personal memories, where BM25
takes its document frequencies. Ordinary words stop discriminating between
memories because they are common *in the book*.

Done in two steps, deliberately not three. Import writes both tables now, with
the citations a chunk row exists to carry (documentId, ordinal, character range,
content hash) and deterministic `documentId:ordinal` ids so a re-import replaces
rather than accumulates. `document_chunks_fts` and `MIGRATION_27_28` make them
searchable as documents, with `N` and `df` over the chunk corpus alone, behind a
new `search_documents` tool — `index_document` had shipped with no counterpart,
so the only way back out of an imported document was general recall, which
returns a passage as an undifferentiated memory and cannot cite it.

**Recall is byte-for-byte unchanged**, which is the point of stopping here: the
third step is dropping the double write, and whether personal recall improves
once documents leave `memories` is a scorecard question. `vectorPoolSize` is the
standing reason not to answer it by opinion — shipped on intuition, measured at
0.4837 against 0.7976.

Found while doing it, all three from writing to a table that had never been
written to:

- The chunk insert has to follow the document insert, because the CASCADE
  foreign key is enforced. `BackupManager` had already learned this twice, for
  project notes and claim resolutions. Moving that insert earlier also gave a
  failed import a parent row to strand, so the rollback grew a case.
- The migration's backfill was `INSERT INTO document_chunks_fts(docid, …)`, and
  `docid` is FTS4's alias for the rowid — so running it over rows the triggers
  had already indexed inserts at an occupied docid and errors. Room never
  re-runs a migration, so this was only reachable from a hand-rolled repair, but
  a migration that throws is the failure that stops the app opening at all.
- `DocumentChunkDao.deleteAll()` was `WHERE documentId IN (SELECT id FROM
  documents)`, called by the restore *after* the documents are deleted, so it
  matched nothing. Dead rather than dangerous — see the correction below — but a
  wipe whose correctness depends on call order is not a wipe.

**A correction, kept because the method is the point.** The claim above was
first written as a live bug: that `ON DELETE CASCADE` skips the child table's
DELETE triggers, leaving orphaned index rows inflating `df`. That is false —
cascaded deletes do fire triggers; the rule about a delete skipping them belongs
to REPLACE conflict resolution, which is why the *insert* trigger deletes by
`chunkId` first and this does not. It was caught by the revert step: the test
written to prove the orphan problem passed against code that could not have
fixed it. Three pieces of documentation and one redundant `clearFtsIndex()`
method had been written on the strength of it.

### The audit that inverted the backlog (2026-08-18, same day)

Five open items remained after the pass above, and they had been *assumed*
rather than checked. Twelve agents — eight read-only auditors, three adversarial
lenses (evidence, necessity, risk), one synthesis — produced 63 claims over the
whole repo.

**Two of the five should never have been open.**

- **Retention on the primary tables.** The premise was wrong. `kg_nodes` and
  `kg_edges` are content-addressed — `KgId.node()`/`edge()` are SHA-256 of
  type+label — so mentioning a thing again *overwrites* its row. Four of the six
  tables named do not grow with use at all, and the two that do reach ~45 MB
  after three years, on a phone with 246 GB free.
- **The `contentHash` index.** The recorded justification, "indexing `content`
  would roughly double the store's on-disk size", is wrong by about 4× — it is
  +16%. And `DocumentChunkEntity.contentHash`, cited as proof the shape works,
  is written and indexed and **read by nothing**. Against that: the migration
  would run SHA-256 over the whole store on first open, with no progress bar and
  no backup behind it.

Both rows in §3 now carry the correction rather than the original reasoning. Three
of the eleven false claims the audit found had been load-bearing in decisions
already taken, which is the argument for fixing a journal rather than appending to
it.

**What was actually worth doing was new, and most of it was hours old.**

- **The chat header fix from earlier the same day did not work.** The width budget
  counted three fixed-width controls beside a Row that draws four — the agent
  picker, present on every install because `ProactiveBootstrap` seeds seven
  builtins at startup. `ChatHeaderTest` was green because `availableAgents`
  defaults to empty and no test set it: the suite certified the one configuration
  production never has.
  **Correcting the count was still not enough**, and that is the more useful half.
  `pillBudget.coerceAtLeast(112.dp)` is a floor that can exceed the space
  available: at 320dp there are 88dp left for the two pills and the floor handed
  them 112dp anyway. A `Row` measures unweighted children in order, so the pills
  took width that did not exist and the last child — the overflow button —
  absorbed the shortfall. Measured on an emulator at 28dp, then 0dp at 280dp,
  *after* the first fix. A floor that can exceed what is available is an
  overdraft, not a floor.
- **A configured custom endpoint read as blank and refused to save.**
  `customBaseUrl`, `customApiKey` and `customIsConfigured` were not among the
  fields `reload()` assigns — the *third* instance in that one function of the
  defect diagnosed and half-fixed hours earlier. Five occurrences in total now:
  `credentialStates`, `keyDrafts`, the catalog lists, and these three. That earns
  `SettingsReloadCoversStateTest`, which reads the declared fields and the
  assigned ones and fails on any difference not named in one of two documented
  lists — *restored elsewhere* and *deliberately reset*, kept separate so a sixth
  omission cannot hide behind the wrong label.
- **Two of three Hands filter chips did nothing.** `filteredHands` applied status
  and search correctly and had no consumer anywhere in the repo; the screen
  recomputed from the search box alone. Its only test set the field and read it
  back on the next line.
- **Document search returned passages from one document.** `ORDER BY
  c.documentId, c.ordinal LIMIT :limit` is a prefix *by document*, because SQLite
  applies LIMIT after ORDER BY — so past ~100 matching chunks in the
  lexicographically first file, every other document was invisible. Replaced with
  one query for the matching ids and a bounded query per document; deliberately
  **not** `ROW_NUMBER() OVER (PARTITION BY …)`, which compiles, passes on a modern
  emulator, and throws at runtime on Android 8-10 at `minSdk = 26`.
- **The character range in every citation was fiction.** The offsets address the
  normalised text `DocumentChunker` produces and discards, and nothing persists
  it — while `DocumentChunkEntity` documented the same columns as "offset in the
  original text". Two confident statements, one file apart, one wrong. The range
  is no longer printed and the KDoc says what is true.
- **`search_documents` overran its own budget.** It could build ~36,800 characters
  against the loop's 4,000-character cap, so most of what it computed was
  discarded and the last passage arrived chopped mid-word.
- **Two backup safety fixes.** The manual backup was enqueued with a *tag* rather
  than a unique name, so two taps in the same second raced the same file; and
  `purgeAll()` sat one statement outside the `try` whose catch performs the
  rollback, so a failure *during the wipe* skipped recovery entirely.
- **Documents imported before the chunk table had a writer are invisible to
  document search**, while the library shows a chunk count for them. Surfaced in
  the UI rather than repaired: the offsets are unrecoverable and fabricating them
  would be the same error as printing a range that addresses nothing. Re-import
  fixes it, because document ids are content hashes.

**The pattern, stated once.** Every defect above is two correct pieces wired
together wrongly, and each was invisible to a 3,300-test suite because both pieces
are individually right and individually tested. A width formula counting three
buttons beside a row that draws four. A state rebuild that restores eighty fields
and forgets three. A filter that works, on a screen that never calls it.

**Method note.** Every fix carries a regression test shown to fail against the
unfixed code — reverted, watched go red, restored — rather than merely to pass
against the fixed one. Applied again for the two `reload()` defects: with both
fixes backed out the class ran 19 tests with exactly 2 red, and the third new test
stayed green because it pins behaviour the old code already had. And once more
for the chunk wipe, where it did the more useful thing and refuted the premise. That is not ceremony: it caught one FTS test that could
never have demonstrated the old behaviour, because `MIGRATION_16_17` shares the
trigger source with the fix. The OAuth CSRF tests were verified by mutation
(`pendingFlows.values.firstOrNull()`, the realistic bug), and the scaffold guard
in the Gate B report was verified to fire *against numbers that clear both bars*.

### The missing guarantees (2026-08-13 →)

A separate pass asked what is *absent* rather than what is broken. The answer was
that Aura is not missing features — it has more than one user can exercise — but
missing guarantees. Five, being landed one at a time.

- **Automatic backup — done.** `allowBackup="false"` is correct (Android's cloud
  backup would hand the whole memory store to Google) but it left every memory,
  graph node, correction, belief and dream on one device behind a button somebody
  had to remember to press. The only unrecoverable failure mode in the app.
  `BackupWorker` runs weekly while charging, writes through the existing
  `BackupManager.snapshot`/`encodeToJson`, and keeps three. The destination is a
  SAF tree the user picks — a folder their own cloud may already sync, which is
  the part no code here can do. Encryption is `BackupCrypto`: AES-GCM from
  `KeyManager`, unchanged, but keyed from a **passphrase** rather than the
  Keystore, because a Keystore key dies with the phone and that is precisely the
  case a backup exists for. The salt and iteration count travel in the file, so a
  future cost bump cannot orphan old backups. **A forgotten passphrase is an
  unrecoverable backup** and the dialog says so once, plainly, at the only moment
  it can be acted on. Not verified: the SAF write itself, which is a device
  concern and sits in the manual plan — *a backup that has never been restored is
  not a backup.*
  - Found while testing it: `recorder?.record(...) ?: runNow()` re-ran the entire
    snapshot, key derivation and write whenever `record` returned null — which is
    exactly the failure path. Now an explicit `if`/`else`, gated by a test.

- **A ceiling on unattended spend — done.** `UsageTracker` counted tokens and
  never capped them; `ToolPolicy.costCeiling` has been allowlisted in
  `DeadConfigFieldTest` as a field that decides nothing since it was written. So
  nothing bounded the daemon, dream consolidation, the morning brief, curiosity
  authoring or daily research — and seeding `backgroundModel` (above) switched
  four of them on at once, which is what made this urgent rather than theoretical.
  `ChatOptions.attended` marks a call nobody is waiting for; `BackgroundBudget`
  keeps a per-local-day token count; `ProviderRegistry.chat` — the single point
  every LLM call passes through, and therefore the only place the check cannot be
  forgotten — refuses unattended calls past the ceiling.
  - **Defaults to attended.** A call site that forgets the flag is treated as the
    user's own turn and never blocked. Failing open on spend is a far better
    outcome than a chat message refused because a dream cycle ran overnight — but
    it fails *silently*, so `UnattendedCallersAreMarkedTest` lists the seven
    subsystems that must carry it and names the file to fix when a new one does not.
  - Hitting the ceiling is a **skip, not a failure**, mapped once in
    `WorkerRunRecorder` rather than in each of the six workers that can hit it —
    a per-worker catch is a thing the seventh worker forgets. It therefore reads
    as "daily background budget spent" in BackgroundHealth, which is the second
    real customer for the run log added the day before.
  - Tokens, not currency: `ModelCatalog` carries no pricing, and a hand-maintained
    price table across seventeen providers would drift into being confidently wrong.
  - Stored in SharedPreferences beside `UsageTracker`, **not** the Room table the
    plan called for. A counter that resets at midnight and means nothing tomorrow
    does not need a schema version, a migration, a backup mapper and three doc
    counts; the guarantee is that spend is bounded, not that it is queryable.
  - `ToolPolicy.costCeiling` deliberately untouched — it is a per-tool
    pre-execution estimate, a different thing, and its dead-field entry is accurate.

- **Redaction at the capture sites — done.** The privacy engineering inside the
  device is careful and always has been; all of it stopped at the network
  boundary, where screen contents and notification text went to a third-party API
  in plaintext. `com.aura.security.Redactor` masks phone numbers, email addresses
  and long digit runs, generalising the `UiTraversal.redact` hook that already
  existed for password fields and secret-hinted ids.
  - **Not at `ProviderRegistry.chat`**, though that is the one place everything
    passes through and the obvious home. Scrubbing on the way out would strip the
    number from "call mum on 0555 123 4567" and break the assistant to protect a
    number the user typed deliberately. The distinction that decides this is *how
    Aura came to have the text*, not what the text contains: a screen read returns
    whatever was on screen and a notification list whatever arrived, so all of it
    is unchosen; `contacts_search` returns the contact that was asked for, and a
    masked answer there is not an answer. `RedactorScopeTest` asserts both halves,
    because over-reach — a redactor that has quietly spread until nothing can be
    answered — is the likelier failure and would look like working.
  - **Two sites the plan named turned out to be wrong**, found by reading them
    rather than by trusting the plan: `NotificationsTool` *posts* notifications
    and captures nothing, and `CaptureScreenTool` returns a base64 JPEG with no
    text for a regex to touch. Both are now in the must-not-redact list with that
    reasoning attached, so the next pass does not rediscover it. Screenshot
    content reaching a vision model is still unredacted and there is no honest
    way to fix that with a text rule.
  - The phone pattern counts digits in code rather than encoding the constraint
    in the regex. Its first draft masked `2026-08-13` as a phone number and a
    16-digit card as one too — caught by the negative half of `RedactorTest`,
    which is roughly half its cases and exists for exactly that.
  - `docs/architecture/privacy-boundaries.md` updated: a new ground rule for
    captured-versus-given text, and the export section corrected — it claimed
    "the Personal export is not encrypted" while the automatic one now is.

- **A time axis — done.** `MindScreen` answered "what does Aura think" well and
  answered it entirely in the present tense. Nothing anywhere answered *what
  changed this week* — what it learned, what it stopped believing, which
  correction took effect. For a system whose whole premise is that it accumulates,
  being unable to show the accumulation was the strangest of the five gaps: every
  input already existed and was already indexed on time, and nobody had read
  across the tables.
  - `ChangeLog` is **a read, not a store**: no new tables, no model call, no
    writes, so it needed no migration, backup mapper or schema export. It merges
    corrections, consolidations, beliefs, contradictions and world events into one
    ordered window, each source wrapped individually so a dead store cannot starve
    the rest — the `SituationReader` rule.
  - Surfaced as the *first* section of `MindScreen` rather than a new route.
    A route would have moved the `NAV_DESTINATIONS` and `SECONDARY_ROUTES` counts
    that `check-version-docs.sh` gates, and MindScreen exists precisely because
    these views were scattered.
  - Two `since(cutoff, limit)` queries added to `ContradictionDao` and
    `WorldEventDao`; the others already had bounded ordered queries. Merging
    N-from-each and taking N overall is exact — the true top N cannot hold more
    than N from one source.
  - Known gap, recorded rather than papered over: a *superseded* belief is
    arguably the most interesting change of all and does not appear, because
    `allActive` is the bounded query that exists and it filters to active.
  - All five DAOs are nullable but **not** defaulted. Defaults on every parameter
    make Kotlin synthesise a no-arg overload, which Hilt reads as a second
    `@Inject` constructor and refuses outright — found at build time, not by
    reading.

- **A place log — done.** The knowledge graph was built essentially from chat, and
  no work on the retrieval stack raises that ceiling: six-signal RRF over "things
  I told it" is still a corpus of things the user told it. Place was chosen over
  screenshots and messages because it is passive, costs **zero model calls**, and
  the permission and plumbing already existed. `place_visits` in `MemoryDatabase`
  (v22) rather than a twelfth database — §3's architecture note is that eleven is
  already too many.
  - **Coarse by construction.** Coordinates are rounded to three decimals — about
    100 m — at the point of capture, so no precise coordinate is ever stored and
    no later feature can decide to start using one. Four decimals would
    distinguish rooms in a building; two could not tell home from the shop.
    `PlaceLogTest` asserts the rounding, because it is the claim the whole table
    rests on. Rows are *visits* (arrival, last-seen, sample count), not fixes.
  - Last-known-location only, on WorkManager's 15-minute floor. A live
    subscription would be more accurate and would also be a continuous listener
    running all day — both the battery cost and a far more invasive product.
  - **Off by default**, and the only background switch that is: everything else
    reads what Aura already has, and this collects something new.
    `PlaceRetentionIsWiredTest` gates the default, the 90-day prune having a
    caller, and that prune staying *above* the `decayEnabled` gate — the third
    time in three days that "a retention window with no caller" has come up here,
    so it gets a gate rather than trust.
  - Backed up (schema 24), unlike `worker_runs`: this is personal data the user
    chose to let Aura keep, not per-installation telemetry. Row ids are
    deliberately not carried, since a restore appends.
  - `ForegroundAppIsNeverStoredTest` failed on a *KDoc* in `PlaceLog` that cited
    `ForegroundAppReader` as precedent. The gate matched raw file text, so prose
    counted as consumption. Tightened to strip comments before matching, and
    re-verified that it still fires on a real reference — a privacy gate that
    fires on prose is one that gets weakened the first time it is wrong.

### The 2026-08-13 pass

A full read of the repo at `e6bd21da`. The finding worth recording is what it did **not**
find: after twelve passes there was no logic defect in the code it read. Every real defect
was in the seam between code and process — declared, documented, unit-tested in isolation,
and connected to nothing that runs. Four of the five would have surfaced in five minutes of
using the app on a phone, which is §4's point, unchanged and still unacted-on.

- **CI had stopped finishing.** `build-test` sat 70–91 minutes on three of the last five
  pushes to `main`; two were killed at the 90-minute job ceiling, so `15d2cc0d` and
  `5fd18a0f` landed with nothing having tested them. Not a hanging test: a cold
  `--rerun-tasks` of `:aura-core:testDebugUnitTest` takes 4m11s. Robolectric resolves its
  `android-all-instrumented` jars itself, over the network, from inside the running test
  task, into `~/.m2/repository` — 326 MB for the two SDK levels pinned here — and the
  workflow cached only `~/.gradle/caches/*`. `lint-release`, which runs no unit tests,
  finished in ~15 minutes in the same runs. Addressed with a separate restore/save cache
  pair for `~/.m2/repository/org/robolectric` (separate because adding a path to the
  existing entry would leave `cache-hit == 'true'` and skip the save, which is the same
  never-warms deadlock the file already documents from 2026-08-05), plus a 40-minute
  `timeout` on both modules' `Test` tasks so a stall fails the task with an uploadable
  report instead of killing the job.

  **The download diagnosis was wrong. The timeout is what proved it.** On the first
  pushed run (2026-08-13, `31737120032`) the task failed at its 40-minute mark instead of
  taking the job down at 90, and the `if: failure()` upload produced 391 KB of partial
  results: **261 test classes had run** before the stall, so nothing was waiting on a
  download. The reasoning that should have been applied at the time is that the runs which
  *passed* paid the same cold download and finished the whole job in 15 minutes — the
  pattern is bimodal, about 10 minutes or forever, which is a deadlock and not a slow
  transfer. The cache still removes a real cost and stays; it was never the cause.

  What the evidence supports now: the last class *recorded* is
  `com.aura.providers.ProviderKeysTest`, which is itself fully bounded — 21 `runTest`
  blocks, 21 explicit timeouts — so it completed, and the hanging class is whichever ran
  next and never got written. Three classes in that same package —
  `AnthropicProviderTest` (15), `GeminiProviderTest` (14) and `ProviderConcurrentStreamTest`
  (2) — drive MockWebServer from `runBlocking`, which unlike `runTest` carries **no
  timeout at all**, over unbounded `join()`/`await()`. On a two-core runner a starved
  coroutine blocks the worker thread with nothing above it. All three now carry a JUnit
  `Timeout.seconds(60)` rule — a rule rather than a conversion to `runTest`, because
  `runTest` substitutes virtual time and these depend on a real socket.
  `ConversationStoreRaceTest` was the first suspect and was **ruled out** by reading it:
  its gates are `CompletableDeferred` inside `runTest`, and its KDoc records fixing this
  exact deadlock already.

  That is a hypothesis with three supports, not a conclusion, so the run also had to be
  made able to answer for itself: `testLogging { events("started") }`, CI only, in both
  modules. Partial results can only ever name the last class to *finish*; the hanging one
  is by definition the one that never got written, so the log has to record what started.

  **Answered 2026-08-15, and the hypothesis above was wrong in its one load-bearing
  step.** The `started` logging worked exactly as intended and named the hang outright:
  on run `31902357923`, `ProviderKeysTest > loaded becomes true even when init load
  encounters errors` STARTED at 18:57:55, nothing else ever started, and the task hit its
  40-minute ceiling at 19:36:51. The hang was *inside* the class this entry exonerated.
  "21 `runTest` blocks, 21 explicit timeouts" was a miscount: two of that class's tests
  are `runBlocking` — `decryption failure during init sets StorageError terminal state`
  and the one that hung — each awaiting a real `Dispatchers.IO` load with no timeout over
  it. That is precisely the shape this entry had just identified and fixed in three
  sibling classes; the count is what stopped it being checked here. `ProviderKeysTest`
  now carries the same class-level `Timeout.seconds(60)` rule, which also covers the
  second unbounded test and any added later.

  Worth keeping as the lesson: the diagnosis was right about the mechanism and wrong
  about the location, and it was wrong because a class was cleared by counting rather
  than by reading. The instrumentation added *because* the reasoning was uncertain is
  what closed it — two days later, on the first run that hung after it shipped.

  **And the timeout was not the fix (2026-08-16).** It bounded the symptom. The next green
  run was read as confirmation and recorded here as one; three CI runs later the same two
  tests failed again, now in 60 seconds rather than 40 minutes. The cause was in
  `ProviderKeys.init`, not in the tests: `_loaded.value = true` was the last statement of
  the happy path and only the per-provider loop was guarded, so anything thrown after it —
  by `loadEmbeddingModel`, or by the mutex block — skipped the assignment and left
  `_loaded` false forever. `awaitLoaded()`'s `_loaded.first { it }` then had nothing
  remaining that could wake it. The KDoc on `loaded` promised consumers were "never stuck
  in a perpetual loading state"; that held only for failures *inside* the loop. The
  escaping exception also had no parent on a process-scoped `SupervisorJob`, which is why
  the second failure read `UncaughtExceptionsBeforeTest` in whichever test ran next — a
  test that was fine, named as the culprit.

  `_loaded` now flips in a `finally`. The first attempt stopped there and was wrong in a
  way the new test caught at once: `loaded` went true while `_credentialStates` stayed at
  `Loading` for every provider, because the throw still skipped the publish. That is worse
  than the hang was — it reports loading as finished and shows none of it. The
  embedding-model read is now guarded on its own and falls back to blank, the documented
  "use the local embedder" value, so a trailing failure cannot discard provider state that
  was already gathered.

  The lesson on top of the lesson: a symptom that stops being visible is not a cause that
  stopped happening, and a green run immediately after a timeout is added is the weakest
  available evidence that anything was repaired.
- **`onACall` fired on any WhatsApp notification.** `SituationReader` matched package
  substrings over `NotificationCaptureStore.snapshot(20)`, which holds *posted
  notifications*. One unread message set `onACall`, which set `interruptible` false, which
  suppressed every proactive notification and held every daemon finding — while telling the
  user "you're on a call". It re-closed the three gates `66bca9bd` had just opened, three
  days after they opened. It also only functioned when notification access was granted,
  which is off by default, so on a stock install the signal was permanently null. Closed by
  reading `AudioManager.mode`: no permission, no package list, no listener, and Aura's own
  live call reads as a call, which is correct. `SituationTest` pinned what `onACall = true`
  *means* and took the value as given; nothing tested where it came from, so nothing
  disagreed. `SituationReaderCallDetectionTest` now does — four of its five cases fail
  against the old derivation.
- **`WorkerRunRecorder.prune()` had no production caller.** Its KDoc named "the same sweep
  that prunes proactive events"; that sweep is `ProactiveEvents.init`, which prunes the
  event and outcome tables and never this one. The only caller was its own unit test, which
  passed and proved nothing, while the table grew a row per worker run with no bound. Wired
  into `DecayWorker` above the `decayEnabled` gate, alongside the outcome pass and for the
  identical reason. Two cases in `DecayWorkerTest` gate it, including the one that would
  regress silently — that retention still runs when memory decay is switched off.
- **The whole autonomous layer was dark on a fresh install.** `backgroundModel` had no
  default and two writers, Settings and a restored backup. Onboarding was not one of them,
  so any install that never opened Settings → AI & Models kept it null — and
  `QuestionAuthor`, `SelfServeResearcher`, `DaemonWorker`, `IdleTimePreparationEngine` and
  `MorningBriefBuilder` all hard-return on exactly that, quietly, each looking identical to
  a feature with nothing to say. §2.10 shipped `BackgroundHealth` listing this switch first
  with that note attached: the diagnosis landed and the fix did not. Closed by
  `UserPreferences.seedBackgroundModelOnce()` — one writer, called from onboarding for new
  installs and from `ProactiveBootstrap` for the ones that already exist, since the first
  reaches none of them. The once-ness is a flag rather than an emptiness check, so a
  deliberate clear stays cleared.
- **The version had not moved in 114 commits.** v0.65.0 / versionCode 80 was set
  2026-08-06; realtime voice, screen control, the capability sweep, living worlds,
  curiosity, the correction spine, situation and health all shipped under it.
  `check-version-docs.sh` gates docs against source, which is structurally why it could not
  catch this — both agreed on a number that had stopped moving. Now v0.66.0 / 81.
- Plus: `TasteProfileScreen.kt` and `WorldModelScreen.kt` still exported full-screen
  composables that nothing had called since `15d2cc0d` folded them into `MindScreen`.
  Renamed to `TasteSection.kt` / `WorldModelSection.kt` rather than exempted —
  `ExtendedScreenContractTest` failed on the deletion, correctly, because the file names had
  been describing something that was no longer in them. And README claimed the five
  consciousness components were "not in the backup schema yet" thirty lines above the line
  saying schema v18 added them.

### The manuscript ledger (2026-08-15)

`SceneContextBuilder`'s KDoc documented an eight-section context budget;
`LongformRunner`, its only production caller, supplied six. `storySoFar` and
`retrieved` (`SceneContextBuilder.kt:51-52`) were defaulted parameters nothing
passed — the two sections carrying any memory of the manuscript already
written, so scene twelve of a novel had never read scenes one through ten.
`section()` returns `""` for an empty body, so the two headings never even
appeared in the assembled prompt. The only place either was ever non-empty was
`SceneContextBuilderTest`, filling them with `"y".repeat(50_000)` to prove the
caps truncate — which proved the caps, not that content arrives. Found by
reading the caller against the KDoc, the check none of the twelve prior passes
ran — see §4. The regression gate is now `LongformRunnerTest`'s "drafting scene
two sends the story so far" and "drafting scene two sends what the manuscript
retrieved", both asserted to fail when the wiring that supplies them is
removed.

Two more fields fixed in passing, the same shape — declared and never written:
`StoryBeat.revisionId` (`WorldBible.kt:96`) now carries the committed
revision's id rather than a copy of `artifactId` — the provenance field the
whole canon store rests on (`CanonFactEntity.sourceRevisionId`) could not be
filled honestly without this; and `canon_query` (`CanonQueryTool.kt`) now
reads `CanonFactDao.activeForBranch`
for the project's active branch instead of running
`memoryStore.query("$question project:$projectId")` against the user's
*personal* memory store, where `project:` was literal text inside a BM25 query
rather than a scope filter and the tool had never once returned a canon fact.

New class `SceneLedger` (`com.aura.creative.longform`) is the first writer
`canon_facts` and `continuity_issues` have had: both tables shipped with full
DAOs, indices and backup mappers — `canon_facts` with a cascading foreign key
to `creative_projects`, `continuity_issues` with none — and their only
production consumers were `BackupManager`'s snapshot, restore and purge. No Room
migration: `StoryBeat` decodes with `ignoreUnknownKeys = true` and a default on
every field, and the two canon tables already existed. A per-slice back-fill
(capped at 3) heals beats whose extraction failed and is the migration path
for every scene drafted before this existed.

A whole-branch review then found that back-fill — the one path that exists for
legacy scenes — passed `beat.revisionId`, which is `""` for every scene drafted
before this branch. `CanonFactEntity.id` is
`UUID.nameUUIDFromBytes("$revisionId|$subjectType|$subjectId|$predicate")`, so a
blank one degenerated to `"|type|subject|predicate"`: every legacy scene stating
the same triple collapsed onto one row under `upsertAll`'s REPLACE, and
`reconcile`'s `it.id != fact.id` filter — there to stop a fact contradicting
itself — then skipped the old row as itself. Contradiction detection, the
headline of this work, was off for every pre-existing project, silently. `record`
now refuses a blank `revisionId` outright, `backFill` resolves one from the
artifact the beat already points at (skipping, not `break`ing, when it cannot, so
an orphan does not starve the beats behind it), and `record` writes the recovered
id back so each legacy beat is repaired once. Two neighbours fell out of the same
read: `retrieve` excluded the previous scene by `revisionId`, which is blank for
those same beats and made `r.id != ''` match every row — so a 700-character
window of `previousSceneTail`'s scene came back through retrieval too, spending
part of the 2,800-character budget on text the prompt already carried, which is
the one thing `retrieve` documents it does not do; it now excludes by artifact
id, the key `previousSceneTail` itself follows. And `record` ended on
`runCatching { updateWorld(…) }.isSuccess`, while
`updateWorld` returns null *without throwing* when the project row is gone — so
`record` reported a synopsis stored that nothing had persisted. Now
`.getOrNull() != null`. That last one survived eleven per-task reviews because
its test stubbed `updateWorld` to return null and asserted success: the test
asserted the bug. It is repaired, and a separate case pins the null path.

Full gate re-verified at this pass: **456 suites, 3,113 unit tests, 0
failures, 0 errors** (357 suites / 2,579 tests in `:aura-core`, 99 suites / 534
tests in `:app`; baseline before this branch was 3,065). Both lint tasks clean
of errors — 78 warnings (63 `:app`, 15 `:aura-core`), 9 hints. `assembleRelease`
succeeds, 11.80 MB. All three gate scripts pass. Device verification
(`scripts/smoke.sh` plus the manual six-scene draft check) was **not**
performed as part of this pass — see the task-11 report.

### The audit pass (2026-08-16)

A full-repository audit: inventory, baseline, then category-by-category
inspection with every claim tied to source. Four defects found and fixed. What
was checked and found sound is recorded at the end, because a pass that lists
only what it broke is not evidence that anything else was read — and because
three of this pass's own first drafts were wrong, which is worth more as a
record than a clean narrative would be.

**A shared `SimpleDateFormat` lost three quarters of every concurrent parse.**
`TimeParser` is an `object` — one instance for the process — and kept its ISO
formatter in a field (`TimeParser.kt:18`). `SimpleDateFormat` carries a mutable
`Calendar` across `parse`, and three tools reach this one: `set_reminder`,
`manage_tasks` and `calendar_write`, which `ToolExecutor` runs up to eight at a
time on a bounded dispatcher. "Remind me at 3, 4 and 5" is a single model turn
emitting three parallel calls into it. Measured at 8 threads × 400 parses of one
valid ISO string: **2,427 of 3,200 came back null — 76%**. Null is not a visible
failure here. The torn parse throws, `tryIso`'s `catch (_: Exception)` maps it to
null, the `HH:mm` fallback cannot read ISO, and the caller reads the result as
"the user typed a bad time" and drops the reminder with a plausible error and no
log. The formatter is now built per call — which is what `format()`, two lines
below it, had always done. The shared field was the inconsistency, not the fix.
`CrashLogger` holds a formatter the same way and is *not* affected: every path
that touches it is inside `synchronized(this)`.

**A fetched page reached the model unframed.** `PromptFraming`'s own KDoc names
the threat it was written for — the model reads a page, judges a line memorable,
calls `remember`, and that line is recalled into a later prompt — and frames the
recall block, the compaction summary, beliefs and taste accordingly. That is the
two-hop path. The one-hop path was open: a page fetched by `web_search` or
`firecrawl_fetch` landed in the same turn's tool result, in a conversation that
can also reach `screen_act` and `send_email_background`, with nothing marking it
as data. The derivative was defended and the original was not.
`frameToolResult` now prefixes results from `ToolCategories.WEB` tools with the
existing `UNTRUSTED_DATA_DIRECTIVE`, after truncation so the directive does not
eat the content budget, and on errors as well as successes because an error can
carry an echoed response body. It keys off `category` rather than a new flag so
there is one fact rather than two that can drift; `ToolFramingAuditTest` pins the
fifteen-tool membership so recategorising a tool for the Tools browser cannot
quietly remove a security control. This is defence in depth and not a boundary —
the confirmation gates are what actually stand between a hostile page and an
irreversible action, and no JVM test can show the directive changes a real
model's behaviour. The regression gate is a wire test, not a format test:
`MemoryAugmentedAgenticLoopToolResultFramingTest` asserts on the messages the
loop hands the provider, and was confirmed to fail when the call site is reverted.

**The backup screen warned about the omission that heals itself and not the one
that does not.** `AuraBackup` and `BackupManager` both document that the export
deliberately omits API keys and OAuth tokens — correctly, since they live under
an Android Keystore key that never leaves the install and writing them into a
JSON file would be a security regression. Nothing on the screen said so. The
restore dialog named embeddings, which `Rebuild embeddings` regenerates in one
pass, and stayed silent on the keys, which cannot be recovered from anywhere and
have to be re-issued by the provider. Export had no disclosure at all: the user
taps *Export to JSON*, receives a file, and reasonably believes the keys they
pasted are inside it. Both ends now say what is missing, and the export line says
it before the file is relied on rather than after.

**Nothing pinned `ToolRisk`'s declaration order.** Four checks compare risks with
`>=` on `ordinal` — the incognito gate in `PolicyEngine`, its fallback in
`ToolExecutor`, world-event recording in the loop, and `ToolRegistry.byRisk` — so
the order of the enum *is* the policy, and `ScreenActTool`'s KDoc already said as
much. The only test that touched it mapped the entries `.toSet()` and asserted
membership, which passes under every permutation. Alphabetising the enum, or
grouping the two `WRITE_` risks because they read better together, would have
silently changed what incognito blocks and what the world model records, with a
green suite. `ToolRiskOrdinalAuditTest` now holds the order and the
`>= WRITE_LOCAL` boundary explicitly.

That boundary has one consequence worth stating rather than fixing here.
`REMOTE_COST` sits *below* it, so anything classified by what a call costs
escapes all four capability gates. For the native tools this is deliberate and
correct — `deep_research` and `code_interpreter` write nothing local. But
`McpToolBridge` registers **every** third-party MCP tool as `REMOTE_COST` by
default, reasoning explicitly about billing ("they call external network
endpoints that may consume paid API credits") for tools whose capabilities it
cannot know. A user-configured MCP server exposing a write tool therefore runs in
incognito and is recorded as no world event. The literal incognito promise —
"cannot write memory or profile facts" — still holds, since MCP tools do not
touch Aura's stores. `ToolRisk` conflating cost with capability is the design
question underneath, and it should be answered deliberately rather than patched.

**Checked and found sound**, recorded so the next pass need not re-derive it: the
policy gate is genuinely a chokepoint — `tool.execute` has exactly one call site
(`ToolExecutor.kt:140`), and the chat loop, background agent runs and hands all
reach it through `execute(name, args, ctx)`, so none can bypass policy. Dagger
does inject a real `PolicyEngine` (verified in the generated
`ToolExecutor_Factory`, not assumed from the source); the `= null` default is
test-only, though it is a shape worth remembering, since removing the binding
would silently disable the primary gate rather than fail the build. `email_send`
and `sms_send` are `WRITE_LOCAL` and correctly so — both only open the platform
composer via `ACTION_SENDTO`, delegating confirmation to an OS surface stronger
than an in-app dialog. `KeyManager` is textbook AES-256-GCM with a per-call
random IV. Release builds never destructively migrate — the fallback is
`OnDowngrade` and `BuildConfig.DEBUG`-only. MCP's deny list and prefix allow list
exist as claimed. `HttpFileReadTool` runs through `SsrfGuard.pinnedClient`. The
JS sandbox blocks network in `shouldInterceptRequest`. Cleartext is off except
loopback; cloud backup and device transfer are fully excluded. Zero
`allowMainThreadQueries`, zero SQL string interpolation in `@Query`, zero
`printStackTrace`, zero TODO/FIXME, and 124 `collectAsStateWithLifecycle` against
zero bare `collectAsState`.

Three first drafts of this pass's own findings were wrong, each because a count
was trusted over a read — the failure §4 already names. A case-sensitive regex
reported six suites with no assertions; all six assert via `coVerify`, and the
true count is zero. A line-based grep reported eleven lazy lists missing item
keys; nine are static enums or fixed-count skeletons where keys are irrelevant,
one has its key on the following line, and only `ProactiveHistoryScreen.kt:129`
is a real dynamic list — low impact, left alone. The same line-based error
reported that 18% of `runCatching` sites log their failure; counted properly it
is 592 `onFailure` against 750 `runCatching`, about 79%, which is a strength
rather than a gap.

Full gate re-verified at this pass: **463 suites, 3,157 unit tests, 0 failures,
0 errors, 0 skipped** (baseline before it was 460 / 3,151). `assembleRelease`
succeeds. All three gate scripts pass. Nothing here was verified on a device —
the concurrency fix is measured on the JVM, the framing change cannot be shown to
alter a real model's behaviour without one, and the backup disclosure is text on
a screen no test in this repo renders.

### Blocked on measurement, not on work

Four items are deliberately unfinished. Each is blocked on evidence that does not exist yet,
and building past them would mean choosing a design by preference instead of by measurement.

| Item | What it needs |
|------|---------------|
| **Prompt-cache effectiveness (Gate A)** | A week of ordinary use, then read **Settings → Usage**, which since 2026-08-10 shows the cache hit rate over the models that report it. The caching ships and defaults on; the hit rate decides whether dynamic tool-schema selection is worth building or already discounted to noise. The reasoning against building it is recorded at the tool-assembly site in `MemoryAugmentedAgenticLoop`, where someone would otherwise reinvent it. Still unconfirmed on a device: that a real Anthropic turn returns non-zero `cache_read_input_tokens`. |
| **The embedding business case (Gate B)** | Export a backup, `python scripts/build_eval_corpus.py <backup>`, judge the queries, then `pip install sentence-transformers`, `python scripts/gen_eval_vectors.py`, re-run the eval suite. The harness side is built and tested: `PrecomputedEmbedder` serves desktop-computed vectors by exact text lookup, the scorecard always carries a Gate B section, and it computes the verdict against bars set before any number existed. What was missing was the judgments. That was recorded here as "the one input nothing but a person can supply", and the 2026-08-16 harvest shows it was half right. The *queries* now come from questions actually asked, in their real class distribution — which is the half no fixture author could have supplied honestly, since the share of synonym-only queries is a fact about how the user asks things and is half of the Gate B bar. Grades arrive from the two signals Aura can infer per (question, memory) — the consult pass and explicit "not for this question" corrections — and `scripts/pool_eval_queries.py` pools the rest and judges the union, because grading only what the shipped ranker returned would score the very documents Gate B is about as 0 by omission and print "Do not proceed" from a corpus incapable of showing otherwise. What still needs a person is the decision to trust the result: model judging is advisory, and `synonym-only` labels are verified mechanically rather than believed. **2026-08-18: the tooling is no longer unproven.** The whole path was run once against the scaffold — `einops` added to the install line, all three models encode, the suite reads the vectors — and the scaffold guard was shown to fire *against numbers that clear both bars* (synonym-only 20% of queries, best gain +0.2039 nDCG@10). That is the exact false positive `EvalFixtures.isScaffold()` exists to refuse, refused. **Only step 1 remains: a backup export.** Also settled and recorded in `docs/RETRIEVAL_EVAL.md`: no default embedding model is seeded, because doing so spends money on every store, re-embeds the whole corpus on first run, hardcodes a `provider:model` this repo has already shipped a crash from, and pre-empts the measurement it is meant to follow. The defect was the *silence* — Settings rendered a blank Embedding row, which reads as a chosen default, and now reads "Not set — recall is keyword-only". |
| **ONNX on-device embeddings** | Gate B clearing its bar (synonym-only ≥15% of real queries and ≥0.15 nDCG@10 gain). The tokenizer is the real cost — a WordPiece port needs a `transformers`-generated fixture as a CI test, because an off-by-one in `##` handling degrades every embedding a few percent, silently, forever. |
| **ONNX cross-encoder rerank** | The above, plus a p95 measurement. It is 200–500 ms per recall on the critical path, and four *tool* callers recall several times per turn. |

`RetrievalConfig` exists so these can be A/B'd rather than argued about; it is not a settings
screen and should not become one.

### The 2026-08-08 remediation sweep

An external review verified the project's own claims by building and running it — test
count, APK size, tool count, lint state all matched exactly — and then found eleven
defects. Five branches, one per phase, each gated on the full suite plus `assembleRelease`
plus lint before the next began. What it closed, and why each mattered more than it looked:

- **Sequential tool steps replayed as a fabricated parallel batch.** The loop only opens a
  new `Turn` when a step produces assistant text, so a tool-only step — the normal shape
  for a tool-calling model with extended thinking on — appended its calls to the previous
  step's turn, and `toMessages` emitted them as one assistant message. A search-then-read
  chain claimed the model had asked for both at once. Structurally valid for every
  provider, so nothing rejected it. Fixed by tagging `ToolTurn.step` and grouping on it.
- **Retrieved content entered the system prompt unframed.** Recalled memories, beliefs,
  taste and topics were appended bare beside Aura's identity. That content is
  attacker-reachable in one hop (`read_url` → `remember` → recalled into a later system
  message). `toMessages` and both summarisation prompts already framed theirs; the
  highest-trust region was the one place that did not.
- **Consciousness state evaporated on every cold start.** `IntrinsicMotivation` and
  `TheoryOfMind` had no persistence path — the same defect §2.4 records fixing for
  `EmotionEngine`, surviving in two siblings. `DriveState.urgency`'s 24-hour ramp was
  therefore unreachable code, and `TheoryOfMind.toPrompt()`'s 3-sample threshold could
  effectively never be met. Two of the four drives (`COMPETENCE`, `COHERENCE`) also had no
  `satisfy()` caller at all, so they could only ever climb.
- **BM25's IDF was computed over the wrong corpus**, and lexical recall was capped at six
  query terms by a DAO signature. Both fixed together by moving to FTS4 — see §2.8.
- **Four source-scanning tests could pass having read nothing**, including one carrying a
  literal `if (!dir.exists()) continue`. §2.6 records this defect being eliminated from
  one test; it was still present in four others.
- Plus: `filterSearchTools`' KDoc stated the opposite of its body, `ReasoningTree`
  understated its own cost 2×, 17 log sites were wrapped in `catch (_: RuntimeException)
  {}` to appease tests, two `architecture.md` files disagreed, and `releases/` held 2.5 GB
  of stale APKs.

Two bugs were found *by the new tests* rather than by the review, and both would have
shipped silently: Room's `createAllTables` does not create triggers, so a fresh install
would have carried a permanently empty FTS index; and SQLite runs `INSERT OR REPLACE`'s
implicit deletion without firing DELETE triggers, so every replace orphaned an index row
and inflated the document frequencies BM25 had just started depending on.

### Correctness

| Item | Detail |
|------|--------|
| No retention on the primary tables | **Closed as declined (2026-08-18), and the premise was wrong.** `kg_nodes` and `kg_edges` are *content-addressed* — `KgId.node()`/`edge()` are SHA-256 of type+label (`KgId.kt:17-21`) — so mentioning the same thing again overwrites its row rather than adding one. Their growth tracks the user's vocabulary, not their usage, and four of the six tables originally named do not grow with use at all. The two that genuinely do reach roughly 45 MB after three years of heavy single-user use, on a phone with 246 GB free. `memories` is additionally bounded in practice by `decayScore` and `retiredAt`, and `MemoryEntity` states outright that nothing there is ever destroyed — so deleting it is a product change, not a fix. Nothing to do. |
| `existsByContent` is a full table scan on every insert | **Closed as declined (2026-08-18), and the reasoning that was here was wrong.** The scan is real — `content` is unindexed and the equality runs per stored memory and per imported chunk — but it needs on the order of 40,000 rows before a person could perceive it, and on the chat path it hides behind a multi-second model call. The claim that indexing `content` would *roughly double the store's on-disk size* is wrong by about 4×: measured, it is +16% for chat text and +26% for document text. The real objection is different and smaller — the index key would average ~1,850 bytes against a 4 KB page. The `contentHash` alternative was also mis-recorded as proven by precedent: `DocumentChunkEntity.contentHash` is written and indexed and **read by nothing**, so it is not a working example of the shape, it is dead weight. Against that, the migration would run SHA-256 over the entire memory store on first app open, with no progress indicator and no backup to fall back on. Not worth it. |
| Document import writes chunks as memories | **Stages 1 and 2 done (2026-08-18); stage 3 blocked on measurement.** Import now writes `document_chunks` as well as `memories`, with real citations (documentId, ordinal, character range, content hash) and deterministic ids so a re-import replaces rather than accumulates. `document_chunks_fts` plus `MIGRATION_27_28` makes them searchable as documents, with `N` and `df` taken over the chunk corpus alone, read by a new `search_documents` tool — `index_document` had had no counterpart, so the only way back out of a document was general recall. Recall itself is untouched and reads `memories` exactly as before, so this cannot have made it worse. **Stage 3 — dropping the double write — is a retrieval change and is not being made on intuition:** whether personal recall improves once a thousand book chunks stop contributing to its IDF is a scorecard question, and Gate B still needs a corpus. |
| `ToolExecutor` pins an IO thread per tool | `runInterruptible(Dispatchers.IO) { runBlocking { … } }` occupies an IO thread for the tool's whole duration, including for purely-suspending tools that would otherwise release it. Bounded to 8 via `limitedParallelism`. **Cancellation itself is correct** — see `ToolExecutorCancellationProbeTest`. |
| `recentTopics` keyword quality | Now filters through the shared `StopWords` list, but the heuristic is fundamentally a word-frequency counter over titles + summaries. Expect low-signal keywords to still appear. |
| Per-step data assertions for MemoryDatabase hops 7–10 | Schema exports 1–17 are all committed and the chain test now runs 6→17 (it stopped at 14 until 2026-08-08, leaving `MIGRATION_14_15` and `MIGRATION_15_16` with no coverage at all), but individual hops inside 6..10 still have no per-step data assertion. |
| ~~No consciousness state is in the backup schema~~ | Closed. `AuraBackupSchema18.kt` covers all five — `NarrativeSelf`, `EmotionEngine`, `AffinityTracker`, `IntrinsicMotivation`, `TheoryOfMind` — exactly as this row proposed. It had been closed since the §2.10 sweep and was still listed here on 2026-08-13, which is the §4 failure mode reappearing in the section that exists to prevent it. `BackupCoverageAuditTest` is now the thing that would notice, rather than a re-read. |
| BM25 document frequency costs one FTS probe per query term | Bounded by `MAX_QUERY_TERMS` (24) and each probe is an index lookup, so it is cheap — but FTS4's `matchinfo()` would return the whole corpus statistic in the same query that fetches candidates. It needs `@RawQuery` plus manual BLOB parsing, and there is no `@RawQuery` precedent in `MemoryDao`. Recorded rather than done. |
| Bigram IDF still comes from the candidate set | `BM25.tokenize` emits words *and* adjacent bigrams; only the unigrams get a corpus document frequency, because measuring every bigram would double the probe count for a term class that is rare by construction. Bigrams fall back to candidate-set `df`, which is the pre-2026-08-08 behaviour for that subset. Since 2026-08-10 this is `RetrievalConfig.bm25Bigrams` and the eval harness can settle it: bigrams also *double* `docLength`, depressing every unigram through BM25's length normalisation, so dropping them may well win outright. Left at the shipped default until measured. |
| `correctly_empty_rate` is 0.0 in the eval harness | The vector fallback scores every scanned row and admits anything above a 0.05 cosine floor, so a query with no real answer still returns something. `MemoryStoreQueryTest` asserts empty results as product behaviour and passes, because its fixtures never reach that branch. The harness disagrees with the unit test about what the system does, and the harness is right. Fix is a relevance floor on the fused score, not on the cosine — but it needs the golden set to avoid trading recall for it. |
| ~~`RealtimeCallController` is covered indirectly~~ | Closed 2026-08-10 by `RealtimeCallControllerTest` (14 fake-driven cases). It found a self-join deadlock in `end()` on its first run — see §2.10. |

### Dependencies and configuration

| Item | Detail |
|------|--------|
| BouncyCastle 1.72 ships in the release APK | `com.tom-roush:pdfbox-android:2.0.27.0` pulls `bcprov`/`bcpkix`/`bcutil-jdk15to18:1.72` (Sept 2022). CVE-2023-33201 (LDAP `X509LDAPCertStoreSpi`) is unreachable — nothing configures an LDAP cert store. CVE-2023-33202 (ASN.1 OID parsing → OOM) is *plausibly* reachable, since PDFBox parses signed/encrypted PDF structures through BC. Not verified either way. Fix is a `dependencies { constraints { … } }` bump on the three `bc*-jdk15to18` artifacts, which needs a networked build to resolve and re-run PDF extraction tests against. **Closed 2026-08-18:** a `constraints` block in `app/build.gradle.kts` pins `bcprov`/`bcpkix`/`bcutil-jdk15to18` at 1.80; the graph resolves it and `DocumentTextExtractorTest` passes against it. |
| The version catalog does not describe what ships | `libs.versions.toml` declares `lifecycle 2.8.7` and `coreKtx 1.13.1`; the app actually resolves `2.11.0` and `1.16.0`, because the Compose BOM's constraints win. Anyone reading the catalog gets the wrong answer, and a future BOM change would silently drop the app back two years. `activity`/`activity-compose` really are pinned at `1.9.3` (Oct 2024) against Compose BOM `2026.06.01`. Align the declared versions with the resolved ones. **Closed 2026-08-18:** the catalog now declares `lifecycle 2.11.0` / `coreKtx 1.16.0` — the versions the BOM already resolved, so the graph is unchanged and the catalog stops lying. |
| `targetSdk 35` against `compileSdk 37` | Two platform releases behind, so Android 16/17 compatibility modes apply; `lint` flags it as `OldTargetApi`. No Play deadline applies — this is a sideloaded personal build — so it is a behavioural-currency item, not a compliance one. Raising it needs a device pass over the permission, notification, and foreground-service paths. |
| `AskAuraWidget` is an exported receiver with an unprotected custom action | `exported="true"` is required for `APPWIDGET_UPDATE`, but the same filter also accepts `com.aura.action.REFRESH_WIDGET` from any app on the device. Worst case is a forced `MemoryStore.recent(1)` read plus a widget redraw — battery, not disclosure. Sending the refresh as an explicit `Intent` and dropping the action from the filter would close it; it needs a device to verify widget refresh still works. **Narrowed 2026-08-18, not closed:** the widget now reads nothing at all while app lock is on, so a forced refresh cannot surface memory text — but the unprotected action remains, and with the lock off the disclosure is the same as it ever was. **Closed 2026-08-18 in code:** the action is out of both receiver filters. `ProactiveBootstrap` calls an injected `WidgetRefresher` (declared in core, provided by `:app`, sending the explicit `APPWIDGET_UPDATE` push), and `ManifestExposesNoCustomActionsTest` fails if a custom action returns to a receiver filter. The between-ticks redraw still wants one look on the device pass. |
| `unitTests.isReturnDefaultValues = true` in both modules | Deliberate, and documented in `aura-core/build.gradle.kts` for `android.util.Log`. The cost is that *any* unmocked Android framework call returns 0/null/false instead of throwing, so a test can pass over a framework call the production path depends on. It also leaked into production once: 17 `Log.w` calls were wrapped in `catch (_: RuntimeException) {}` "because Log is unavailable in pure JVM tests", which made a deliberately-silent catch indistinguishable from an accidental one. Removed 2026-08-08. |
| `releases/` is an untracked local artifact directory | Gitignored, so it never bloated history — but nothing prunes it either, and it had reached 69 APKs / 2.5 GB before 2026-08-08. Now holds the current build plus two. It will grow again. **Corrected 2026-08-18:** "gitignored" had drifted — a `!releases/aura-debug-v0.65.0.apk` exception was tracking one 40 MB APK after all. Exception removed and the APK untracked; the blob already in history stays there. |

### Coverage

- 30 files declare a `: ViewModel()` (28 on 2026-08-10, 33 in a figure before that which
  did not match HEAD either — this number drifts every time a screen lands, and re-reading
  it is the point of this section). Name-matching undercounts, since a VM may be covered by
  a differently-named suite. 24 `*Screen.kt` files remain untested as screens.
  ViewModels with business logic are tested; Compose UI screens are harder to unit-test
  meaningfully and are exercised through manual use. The ROI of Compose UI unit tests on a
  personal app is low — but note that "exercised through manual use" is doing real work in
  that sentence, and the device pass below has still not happened.
- `runCatching` blocks are enforced-handled by `SilentRunCatchingAuditTest` (every block
  must have an `.onFailure`/`.getOr*`/`.fold` handler within scan range; 1 allowlisted
  exception). Silent-but-handled fallbacks (`.getOrNull()` without logging) still exist,
  mostly JSON-parse sentinels where that is the correct behavior.
- Instrumented coverage: `:aura-core`'s migration chains have run in CI on an emulator
  since 2026-08-14. **`:app`'s 43 methods now have a job too** (`app-instrumented`,
  2026-08-18) — every `createComposeRule` test, the bottom-nav and onboarding tests, and
  `ModelSelectionFlowTest`, the only true end-to-end flow in the repo. They had been
  compiled and never executed, which is not a distinction the count made:
  `HomeContentTest`'s own comment records a version asserting strings that exist nowhere
  in the app, counting toward the instrumented total for months. `DeviceSmokeTest` is
  excluded by name — its KDoc is explicit that `connectedAndroidTest` destroys the
  Keystore entries the encrypted credentials depend on.
  **That job has never executed.** Its first CI run is diagnostic, and red would confirm
  the finding rather than refute it.
- Screen control and live voice each have a 12-row manual table in
  `docs/ANDROID_TEST_PLAN.md`, and **neither has been run**. Live voice is now reachable
  from the chat mic button, which makes this more pressing rather than less: it is a beta
  protocol over a real socket, and until 2026-08-18 nothing could reach it, so "untested
  on a device" and "unreachable" were the same fact. They are now different facts.
  The unit tests cover the decisions — what is refused, what is redacted, what is
  truncated, what order barge-in happens in. A device covers whether the platform behaves
  as documented, which is a different question and the one that matters here.

### Architecture

- **11 separate Room databases.** No cross-database transactions or joins, 11 independent
  migration chains, global search fans out across all of them, and backup must coordinate
  11 schemas — which is why `BackupManager.kt` is the largest file in the project.
  Consolidating to one or two databases is a large but bounded change.
- **Scope versus depth.** 78 tools, 17 providers, ~29 screens, plus evolution, dream,
  taste, world model, creative council, production pipelines, agent DAG runs, and an MCP
  client — for a single-user personal app. Several of these subsystems are 200–500 lines:
  the surface area is large relative to the depth behind each. This was the 07-17
  assessment's central diagnosis ("too much honest substrate, not enough honest surface").
  The sweep deepened several seams (gates, evolution, capture) but did not shrink the
  surface. The manuscript ledger (2026-08-15, §3 above) is the same move applied to
  creative: `canon_facts` and `continuity_issues` existed, fully wired to DAOs and
  backup, since the schema was designed, and had never held a row. They have a writer
  now, with no new tool, route, or screen added — depth given to existing surface
  rather than surface piled on top.

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
