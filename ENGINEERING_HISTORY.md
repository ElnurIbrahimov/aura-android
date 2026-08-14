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

### Blocked on measurement, not on work

Four items are deliberately unfinished. Each is blocked on evidence that does not exist yet,
and building past them would mean choosing a design by preference instead of by measurement.

| Item | What it needs |
|------|---------------|
| **Prompt-cache effectiveness (Gate A)** | A week of ordinary use, then read **Settings → Usage**, which since 2026-08-10 shows the cache hit rate over the models that report it. The caching ships and defaults on; the hit rate decides whether dynamic tool-schema selection is worth building or already discounted to noise. The reasoning against building it is recorded at the tool-assembly site in `MemoryAugmentedAgenticLoop`, where someone would otherwise reinvent it. Still unconfirmed on a device: that a real Anthropic turn returns non-zero `cache_read_input_tokens`. |
| **The embedding business case (Gate B)** | Export a backup, `python scripts/build_eval_corpus.py <backup>`, judge the queries, then `pip install sentence-transformers`, `python scripts/gen_eval_vectors.py`, re-run the eval suite. The harness side is built and tested: `PrecomputedEmbedder` serves desktop-computed vectors by exact text lookup, the scorecard always carries a Gate B section, and it computes the verdict against bars set before any number existed. What is missing is the judgments: which memory answers which question is the one input nothing but a person can supply, and it is the input that defines what "better retrieval" means. |
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
| BouncyCastle 1.72 ships in the release APK | `com.tom-roush:pdfbox-android:2.0.27.0` pulls `bcprov`/`bcpkix`/`bcutil-jdk15to18:1.72` (Sept 2022). CVE-2023-33201 (LDAP `X509LDAPCertStoreSpi`) is unreachable — nothing configures an LDAP cert store. CVE-2023-33202 (ASN.1 OID parsing → OOM) is *plausibly* reachable, since PDFBox parses signed/encrypted PDF structures through BC. Not verified either way. Fix is a `dependencies { constraints { … } }` bump on the three `bc*-jdk15to18` artifacts, which needs a networked build to resolve and re-run PDF extraction tests against. |
| The version catalog does not describe what ships | `libs.versions.toml` declares `lifecycle 2.8.7` and `coreKtx 1.13.1`; the app actually resolves `2.11.0` and `1.16.0`, because the Compose BOM's constraints win. Anyone reading the catalog gets the wrong answer, and a future BOM change would silently drop the app back two years. `activity`/`activity-compose` really are pinned at `1.9.3` (Oct 2024) against Compose BOM `2026.06.01`. Align the declared versions with the resolved ones. |
| `targetSdk 35` against `compileSdk 37` | Two platform releases behind, so Android 16/17 compatibility modes apply; `lint` flags it as `OldTargetApi`. No Play deadline applies — this is a sideloaded personal build — so it is a behavioural-currency item, not a compliance one. Raising it needs a device pass over the permission, notification, and foreground-service paths. |
| `AskAuraWidget` is an exported receiver with an unprotected custom action | `exported="true"` is required for `APPWIDGET_UPDATE`, but the same filter also accepts `com.aura.action.REFRESH_WIDGET` from any app on the device. Worst case is a forced `MemoryStore.recent(1)` read plus a widget redraw — battery, not disclosure. Sending the refresh as an explicit `Intent` and dropping the action from the filter would close it; it needs a device to verify widget refresh still works. |
| `unitTests.isReturnDefaultValues = true` in both modules | Deliberate, and documented in `aura-core/build.gradle.kts` for `android.util.Log`. The cost is that *any* unmocked Android framework call returns 0/null/false instead of throwing, so a test can pass over a framework call the production path depends on. It also leaked into production once: 17 `Log.w` calls were wrapped in `catch (_: RuntimeException) {}` "because Log is unavailable in pure JVM tests", which made a deliberately-silent catch indistinguishable from an accidental one. Removed 2026-08-08. |
| `releases/` is an untracked local artifact directory | Gitignored, so it never bloated history — but nothing prunes it either, and it had reached 69 APKs / 2.5 GB before 2026-08-08. Now holds the current build plus two. It will grow again. |

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
- Instrumented coverage (64 device test methods: migration chains + smoke tests) runs
  only on a connected device — not in CI.
- Screen control and live voice each have a 12-row manual table in
  `docs/ANDROID_TEST_PLAN.md`, and **neither has been run**. The unit tests cover the
  decisions — what is refused, what is redacted, what is truncated, what order barge-in
  happens in. A device covers whether the platform behaves as documented, which is a
  different question and the one that matters for two subsystems built on beta protocols
  and OEM-modified accessibility stacks.

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
