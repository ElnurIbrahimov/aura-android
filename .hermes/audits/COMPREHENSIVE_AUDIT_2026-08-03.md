# Comprehensive 18-Phase Audit — Aura Android

**Date:** 2026-08-03
**HEAD:** 1bf58539 (v0.57.1, versionCode 70)
**Branch:** feat/tier-1-friction
**Scope:** 553 main .kt files (~81,085 LOC), 295 test files, 1,759 tests
**Method:** Fresh evidence gathered this session (grep + read + full test run). No recycled claims — every finding below was verified against current source.

---

## 0. VERIFIED SNAPSHOT (ground truth)

| Metric | Value |
|---|---|
| Main Kotlin files | 553 |
| Test files | 295 |
| Tests (executed now) | **1,759 — 0 failures, 0 errors, 0 skipped** |
| Version | v0.57.1 / versionCode 70 (README claims v0.51.2/62 — stale) |
| Git | 707 commits total, 635 in last 30 days |
| Tools | 69 |
| Providers | 17 |
| Room DBs | 11 (Memory v15, Conversation v6, Task v5, ProactiveEvent v5, Dream v3, Evolution v3, Hand v2, UserProfile v2, Agent v1, StrategyBandit v1, AgentRun v1) |
| Entities | 48 |
| Backup classes | 51 (all 48 entities covered incl. Dream + StrategyBandit) |
| Hilt @Singleton | 227; @Inject constructors 189 |
| DAOs | 24; @Transaction usages: **1** |
| runBlocking in prod | 1 (ToolExecutor — the intended `runInterruptible { runBlocking { } }` pattern, verified correct) |
| collectAsState() w/o lifecycle | 0 (fully migrated) |
| MaterialTheme.colorScheme bypasses | 2 (was 50) |
| Hardcoded Color(0xFF…) | 105 remaining |
| Hardcoded dp | 845 remaining |
| GlobalScope | 1 (AskAuraWidget broadcast receiver) |
| SsrfGuard | 11 files |
| Migration chains | verified complete (MemoryModule 15, Conversation 6, Tasks 5, Proactive 5, Dream 4, Evolution 3) |

---

## 1. FINDINGS BY SEVERITY

### HIGH

**H1 — OAuth login-CSRF: no `state` parameter, no PKCE**
`aura-core/.../integrations/OAuthFlow.kt:68-94` (launch) and `:112-140` (handleRedirect).

- `launchGoogleAuth`/`launchMicrosoftAuth` never generate a `state` token; `handleRedirect` accepts any `code` for any launch. There is no session binding between the consent tab and the app.
- No PKCE (`code_challenge`/`code_verifier`). For a custom-scheme (`aura://`) redirect with a public client (no client_secret — correct for mobile, but then PKCE *is* the required protection), Google's own guidance requires PKCE for installed apps.
- Attack: an app or browser tab that can obtain/redirect an auth code can bind the victim's Gmail/Outlook to the attacker's consent — token store gets overwritten with attacker-account tokens; subsequent tool calls read/write the wrong account. Personal single-user app lowers blast radius, but the fix is small and textbook.
- Fix: generate 128-bit random `state` per launch, store in memory + pass through; validate in `handleRedirect` before exchange. Add S256 PKCE: `code_verifier` (43-char random), `code_challenge` = base64url(sha256(verifier)), send verifier in token exchange. Both Google and Microsoft v2.0 support PKCE.
- Effort: ~1h + 2-3 tests. Impact: closes the only real auth flaw found.

**H2 — Toolchain ~2 years stale**
`gradle/libs.versions.toml`: Kotlin 1.9.24, AGP 8.2.2, Compose BOM 2024.10.01, Room 2.6.1, Hilt 2.51, WorkManager 2.9.1, coroutines 1.9.0, biometric **1.2.0-alpha05**, compileSdk 35 (Android 16 / API 36 is current).

- Consequences: no Kotlin 2.0+ Compose compiler (kills `@Composable` performance features and the new compiler plugin), no Room 2.7 (KMP, better coroutines), no predictive-back, no newer lifecycle, alpha-level biometric dependency in production.
- Risk: medium — Room migration validation is already strong (15-migration chain + contract tests), KSP version must move with Kotlin, Compose compiler now ships with Kotlin. Recommend: Kotlin 2.0.x/2.1.x → AGP 8.9+/9.x → BOM 2026.x → Room 2.7.x in one committed step with full-suite verification between each bump.
- Effort: 2-4 days. Impact: unblocks every future library upgrade.

### MEDIUM

**M1 — Vector fallback is a full-table scan + full decode**
`MemoryStore.kt:224`: `dao.allByScopes(scopes).filter { it.embedding != null }` loads every scoped memory, deserializes every float[] embedding, then computes cosine over all. Worst case: O(N) heap churn per recall that misses lexical overlap (e.g. 10K memories × 768-dim ≈ 60+ MB of transient allocations per query on the cold path).
- Fix options, cheapest first: (a) bound the scan — `ORDER BY accessCount DESC, decayScore DESC LIMIT 2000` via a DAO query (active memories first, bounded allocation); (b) store normalized embeddings + precomputed L2 norm in the row to avoid re-normalizing; (c) only if recall quality suffers, add a cheap 8-dim IVF prefilter. (a) is a 30-min change; keep the current exact-cosine for correctness and add a test asserting bounded candidate count.

**M2 — God-classes remain**
SettingsViewModel 1,135 lines, MemoryScreen 1,096, ChatViewModel 1,077 (4 controllers extracted, still large), DreamConsolidator 969. Chat got controllers; Settings + Memory need the same `viewmodel-controller-extraction` treatment. P2 in Round 14, still open.

**M3 — README/architecture.md version drift (4th recurrence)**
README says v0.51.2/versionCode 62, build says v0.57.1/70; features list omits 6+ shipped subsystems. `docs/architecture.md` is 91 lines for an 81K-LOC app. The recurring fix is a CI check: `scripts/check-version-docs.sh` greps versionName/versionCode and fails the build on mismatch.

**M4 — Release-size headroom**
Debug APK 38.5 MB, 34 DEX files, no R8/shrinking anywhere in the release path; 2.8 MB of static fonts (8 files) that a variable font (Inter Variable ~1 file, Fraunces Variable) would cut to <1 MB. For a personal sideload app this is polish, not blocking, but R8 release + variable font is a ~half-day win.

**M5 — Four overlapping search tools**
`WebSearchTool` (DDG), `BraveSearchTool`, `TavilySearchTool`, `WebSearchCapabilityTool` all registered. `filterSearchTools` hides unconfigured ones, but with 2+ configured the LLM must guess which one "fits" — nondeterministic behavior and wasted calls. Consolidate behind a single `web_search` dispatcher that picks the configured backend (or expose `search:provider=` param).

**M6 — Only one @Transaction in 24 DAOs**
`KnowledgeGraphDao.kt:95` is the sole @Transaction. Multi-write paths (backup restore batches, `purgeAll` + `insertAll` sequences, memory edit + history insert) run as N separate SQLite transactions. Within a DB these should be @Transaction-annotated composite DAO methods; cross-DB (backup restore) can't be atomic — document the partial-restore semantics (current try/catch + purgeAll is a good recovery, but the within-DB paths are free wins).

### LOW

- **L1** `AskAuraWidget.kt:97` — `GlobalScope.launch(Dispatchers.IO)` in a receiver context. Works, but escapes lifecycle; a bounded `CoroutineScope(SupervisorJob())` owned by the receiver (or WorkManager enqueue) is the cleaner shape.
- **L2** Repo hygiene: 10 untracked `.hermes/` files (8 audit reports + 2 plans from prior sessions) never committed; one stale **Draft** duplicate release `v0.56.1` on GitHub (published twin exists).
- **L3** `ProactiveBootstrap` is `@Inject constructor` without `@Singleton` — safe today (single call site via `Provider<>` in AuraApp), latent footgun if a ViewModel/Worker starts injecting it.
- **L4** Accessibility: 340 `Icon`/`IconButton` usages vs 218 `contentDescription`s (delta partly decorative — needs a real audit pass). Explicitly deprioritized for this personal app — recorded, not pushed.
- **L5** `UserPreferences.kt:461` default `"dall-e-3"` — user-configurable, acceptable, but catalog-derived default would be consistent with the no-hardcoded-model rule.
- **L6** 105 hardcoded colors + 845 hardcoded dp remain after the token ratchet — ratchet is working (50→2 colorScheme bypasses), just incomplete. Scripted passes exist; schedule the remaining two sweeps.

### VERIFIED GOOD (kept honest)

- Full suite green (re-ran: 1,759/0/0/0).
- Exactly one real runBlocking, and it is the correct `runInterruptible` + `runBlocking` bridge with a timeout test.
- WebView lockdown correct (JS on, file/content access off, `MIXED_CONTENT_NEVER_ALLOW`).
- Cleartext blocked at OS level (network_security_config); SSRF guard present in all 11 network-touching files incl. MCP + CustomOpenAI.
- All 48 entities round-trip in backup (51 backup classes); Dream + StrategyBandit included.
- All Room migration chains complete and pinned by contract tests.
- Permissions least-privilege verified — every dangerous permission (location, contacts, camera, audio, calendar) has a real consumer.
- Keys in SecureDataStore (17 files); no hardcoded secrets found (sk-, AIza-, ghp- scans clean).
- Empty-response silent failure fixed in v0.57.1 with error emission.
- 0 non-lifecycle collectAsState; 0 TODO/FIXME/XXX in main sources.

---

## 2. PHASE-BY-PHASE SUMMARY

**P1 System understanding** — Single-user Android superapp; Python Aura port; offline-first Room (11 DBs) + cloud LLMs (17 providers); agentic loop (ReAct, 10 steps, planning off by default, StrategyBandit strategy sampling, reflection, extended thinking always-on); SOTA memory pipeline (rewrite → BM25 → RRF 6-signal → cross-encoder rerank → recall cache); proactive layer (daemon 15 min, motivation accumulator, 8 awareness checks); evolution loop (detectors → coordinator → proposals → apply saga → rollback); creative studio + council; 69 tools; OAuth integrations (Google/MS); widgets; backup v-schema with 51 types. Dependency graph clean (dagger.Lazy for the one cycle).

**P2 Codebase audit** — 0 TODO/FIXME; dead code largely drained by prior rounds; duplication concentrated in provider SSE handling (consolidated into OpenAiSseParser, 2 custom providers remain) and search tools (M5). God-classes (M2). No naming/SoC catastrophes found.

**P3 Architecture** — Sound overall: core/app split, controller extraction proven, Room per aggregate, module-per-subsystem. Next structural moves: SettingsViewModel/MemoryScreen controller extraction; single search dispatcher; consider event-sourcing only where the audit trail already exists (memory_edits, agent runs) — do not over-apply.

**P4 Performance** — Cold start is clean (no runBlocking in app init, 750 ms deferred proactive start). Biggest hot-path item is M1 (vector fallback). Reranker batches 4/LLM call, recall cache works. Streaming markdown renders per-chunk — watch for recomposition cost on long responses (profile with Compose metrics before optimizing). APK size (M4). Baseline Profiles + Macrobenchmark are the highest-leverage untapped perf tool — cold start is currently unbounded.

**P5 Database** — Schema quality high: 15-migration chain, exportSchema everywhere, 91 CREATE INDEX in migrations, contract tests with real Room. Gaps: 1 @Transaction (M6), no FK index audit after the last migrations (Round 14 count said 91 indexes; re-verify after next migration), backup restore cross-DB atomicity is documented-but-manual.

**P6 Security** — Strong baseline: no secrets, cleartext blocked, SSRF-guarded, SecureDataStore for keys/tokens, OAuth tokens encrypted. One real flaw: H1 (state/PKCE). No injection surfaces (no dynamic SQL; Room parameterized). No exported components beyond what must be (launcher, deep link, widgets, boot receiver, notification listener with proper permission).

**P7 Frontend** — SOTA pass landed (teal identity, AuraScreenShell, floating nav, Fraunces/Inter/JetBrains Mono, thinking blocks, teal user bubbles, streaming cursor, empty-state logomark). Remaining: 105 colors/845 dp ratchet (L6), icon a11y (L4), no light-mode tuning since 1:1 token mapping (honest caveat from the migration), ChatRoute 827 lines still heavy.

**P8 API** — Internal-only surface (providers, MCP, OAuth). Provider layer consistent: suspend chat(), keyForAwaiting(), SSE timeout everywhere, non-retryable 401/400/403, tool-call index routing fixed across families. No external REST to audit.

**P9 DevOps** — CI: JDK17, android-35, logging lint gate, 45-min timeout, SHA-pinned actions, concurrency cancel. Gradle 8.10.2. Not enabled: configuration cache, build cache on CI, baseline profiles. Local build ~10-20 min; `--offline` works. Releases are manual `gh release create` — the draft duplicate (L2) shows the loop needs a `gh release delete --yes` cleanup step.

**P10 Testing** — 1,759 tests / 295 files (verified green now). Real-Room contract tests, migration tests, MockWebServer SSE tests, ViewModel state-machine tests, source-scan contract tests (navigate/composable parity, migration registration, status-code classification). Gaps: 10+ screens still untested (ChatRoute 827 lines, MemoryScreen 1,096), no UI/E2E (Compose UI tests absent), no property/mutation testing — acceptable for a personal app; the highest-value addition is Compose UI tests for the chat timeline (thinking block rendering, tool badges, inline images) since that's the product's heart.

**P11 AI/ML** — This is the strongest subsystem: SOTA retrieval (rewrite→BM25→RRF→rerank→cache), StrategyBandit Thompson sampling, reflection, cross-encoder reranking, entity-aware compaction, taste-driven routing, evolution evaluators, extended thinking on all 17 providers. Fresh flags: (a) reranker + rewrite + embed are 3 LLM/embedding calls per recall — the recall cache amortizes across loop steps, verify it covers vector-fallback path too (it does — cache wraps the whole query), (b) thinking always-on at 32K budget is a real token cost — confirm the budget slider is discoverable, (c) M1 bounding, (d) planning step correctly off by default.

**P12 Dependencies** — No new deps found with obvious redundancy; pdfboxAndroid (2.0.27.0) is the only unusual one (PDF tooling) — check license/necessity during the toolchain upgrade. Staleness is the story (H2), plus biometric alpha.

**P13 Code quality** — Readability high, logging disciplined (throwable-passing lint gate), naming consistent. Complexity concentrated in the four god-classes (M2). Comments are unusually good — architecture rationale is in KDoc, not just code.

**P14 Product** — Feature surface is best-in-class for a personal AI assistant (nothing comparable ships memory consolidation, evolution loops, 69 tools, creative council, proactive daemon on-device). Frictions: search-tool ambiguity (M5), README not reflecting reality (M3), no in-app changelog/what's-new, light-mode untuned. Competitive moat: everything runs on the user's own providers, zero subscription.

**P15 Refactoring** — Highest ROI: H1 fix (small, security), M1 bound (small, perf), M2 splits (medium, maintainability), M6 transactions (small), H2 toolchain (large but unblocking).

**P16 Prioritization** — see table below.

**P17 Scorecard** — see below.

**P18 Master plan** — see below.

---

## 3. PRIORITIZED FINDINGS

| ID | Severity | Finding | Impact | Effort | Risk |
|----|----------|---------|--------|--------|------|
| H1 | High | OAuth state + PKCE missing | Security (login CSRF) | 1h + tests | Low |
| H2 | High | Toolchain 2y stale | All future work | 2-4d | Medium |
| M1 | Medium | Vector fallback full scan | Perf on cold recall | 30min | Low |
| M2 | Medium | 4 god-classes | Maintainability | 1-2d | Low |
| M3 | Medium | README drift (4th time) | Docs trust | 30min + CI gate | Low |
| M4 | Medium | APK 38MB / 34 DEX / fonts 2.8MB | Size | 0.5d | Low |
| M5 | Medium | 4 search tools overlap | Behavior | 2-4h | Medium |
| M6 | Medium | 1 @Transaction in 24 DAOs | Integrity | 1-2h | Low |
| L1-L6 | Low | see section 1 | varies | varies | Low |

## 4. FINAL SCORECARD

| Dimension | Score |
|-----------|-------|
| Architecture | 9.0 |
| Code Quality | 8.5 |
| Maintainability | 7.5 |
| Performance | 7.5 |
| Scalability | 7.0 (single-device by design) |
| Security | 8.0 (H1 holds it below 9) |
| Reliability | 8.5 |
| Developer Experience | 8.0 |
| User Experience | 8.5 |
| Documentation | 5.5 (README drift, thin architecture.md) |
| Testing | 8.0 |
| Accessibility | 5.5 (deliberately deprioritized) |
| Infrastructure | 6.5 (stale toolchain) |
| Deployment | 7.0 (manual, draft duplicates) |
| **Overall Engineering** | **8.1** |
| **Overall Product** | **8.7** |

## 5. MASTER IMPROVEMENT PLAN

### Immediate (day 1)
1. **H1** — PKCE (S256) + state param in OAuthFlow; 3 tests (state mismatch rejected, PKCE verifier round-trip, error path).
2. **L2** — commit `.hermes/` artifacts, delete stale Draft release.
3. **M3** — sync README/architecture.md; add `scripts/check-version-docs.sh` to CI (fails on versionName/versionCode mismatch).

### Short-term (week 1)
4. **M1** — bounded vector-fallback DAO query (`ORDER BY accessCount DESC, decayScore DESC LIMIT 2000`) + test asserting bound.
5. **M6** — @Transaction on composite DAO paths (memory edit+history, purge+insert in restore).
6. **M2** — extract SettingsConfigController + MemoryStore-controller equivalents (proven pattern).
7. **H2** — toolchain upgrade in lockstep commits: Kotlin 2.0.x (+KSP match) → AGP 8.9/9 → Compose BOM 2026 → Room 2.7 → compileSdk 36; full suite after each.

### Medium-term (weeks 2-4)
8. **M4** — R8 release variant (proguard rules exist at 30 lines; needs -dontwarn audit), variable-font conversion (Inter + Fraunces), APK target <25 MB.
9. **M5** — single `web_search` dispatcher over configured backends.
10. Compose UI tests for ChatTimeline (thinking block, tool badges, inline images, citations).
11. Baseline Profile + Macrobenchmark (cold start, first message latency) — the highest-leverage untapped perf tool.
12. Gradle configuration cache + build cache on CI (build time −30-50%).

### Long-term
13. Light-mode tuning (the 1:1 token mapping caveat).
14. In-app changelog (release notes rendered in Settings) — removes README-drift class of issues permanently.
15. Optional: split `:core` further only if a second consumer appears; do not pre-modularize a single-user app.
16. Revisit a11y + screen-reader support if distribution intent ever changes.

### Risks
- H2 migration: Room schema untouched (Room 2.7 is drop-in), biggest risk is KSP/Compose-compiler interplay — mitigate with lockstep commits + contract tests (they already pin migrations).
- M5: consolidating tools changes the LLM's tool-selection surface — keep old names as aliases for one release.
- M1: bounding the scan may drop recall for long-tail memories — keep exact scan behind the reranker path if quality regresses.

---

*Generated 2026-08-03 from fresh evidence: 12 terminal/read sweeps, full test execution (1,759 green), file-level verification of every finding.*
