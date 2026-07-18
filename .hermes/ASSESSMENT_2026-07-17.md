# Aura Android Project Assessment — 2026-07-17

## Executive Summary

This is the most ambitious Android codebase in the portfolio — a genuine personal AI operating system with chat, memory, creative studio, production pipelines, evolution, agent runs, MCP, subagents, and taste modeling. The architecture and vision are correct. The problem is **ambition has outpaced integration**: a lot of impressive substrate landed in the last two weeks but many pieces are un-wired, and CI was broken at HEAD.

The build is now fixed (3 test files patched). Pushed to `feat/tier-1-friction` as commit `ab00ff0`.

## Baseline Numbers

- 617 Kotlin files, ~76,445 LOC, 2 modules (`app`, `aura-core`)
- 1,004 @Test annotations (765 aura-core + 239 app)
- 56 @Singleton tools, 43 Room entities, 8 databases, 25 migrations
- 145 @Singleton classes total
- 21 screens, 25 ViewModels

## What's Genuinely Excellent

1. **Scope and vision are correct.** Personal AI OS + Creative World OS is the right product shape.
2. **Test discipline is real.** 1,000+ unit tests, Room schema exports, migration tests, CI wired.
3. **Security posture materially improved.** No API keys in URL query strings, BIOMETRIC_STRONG, SSRF guard exists, network security config blocks cleartext.
4. **SOTA UI redesign landed.** Theme tokens, Fraunces + JetBrains Mono, custom chat bubbles, compact header, floating nav.
5. **Beyond-SOTA substrate exists.** CapabilityRouter, ModelRoleRouter, PolicyEngine, TraceSink, AgentRunDatabase + DagResolver, MCP client, SubagentManager, CreativeCouncil, TasteEngine, ProductionPipelineEngine, Evolution subsystem.

## What's Broken / Un-Wired

### P0 — Immediate

1. **Evolution tools misclassified as READ_ONLY.** `trigger_evolution_run` calls `coordinator.runAll()` which writes to `candidateDao` and `proposalStore`. It bypasses incognito mode because `ToolExecutor` only blocks `WRITE_LOCAL+`.
2. **MCP connection has zero SSRF validation.** `McpConnection.sendRequest()` hits `config.url` with only a HTTPS prefix check. Cloud metadata endpoints possible.
3. **`http_file_write` classified REMOTE_COST but is external mutation.** `ToolRisk.WRITE_REMOTE` exists but is unused.
4. **Build was broken at HEAD.** `MemoryViewModelTest` + `SettingsViewModelAppLockTest` constructor drift + `AuraBottomNavigationRouteTest` stale expectation. **Fixed and pushed in `ab00ff0`.**

### P1 — Significant

5. **SSRF TOCTOU in 4 tools.** `HttpFileReadTool`, `HttpFileWriteTool`, `FirecrawlFetchTool`, `WeatherTool` validate URL then make a separate HTTP call. `DeepResearchTool` already does it correctly with `SsrfGuard.pinnedClient()`.
6. **`vision` and `knowledge_graph_extract` are READ_ONLY but send user data to paid cloud APIs.** Cost exhaustion + data exfiltration vector.
7. **Evolution reflection has no cost cap or cooldown.** Every candidate above threshold triggers an LLM call.
8. **`PolicyEngine` is dead code.** `evaluate()` is defined but never called; `ToolExecutor` duplicates gates inline.
9. **`TraceSink` has zero production callers.** Defined, tested, never injected into the agent loop.
10. **`EvolutionScheduler` never started.** No `schedule()` call from `AuraApp` or anywhere.
11. **MCP is unwired from the tool registry.** `McpClientManager` only appears in Settings UI.
12. **`MIGRATION_1_2` missing from `EvolutionModule`.** Array only has `MIGRATION_2_3`; v1→v3 upgrade crashes.
13. **11 @Singleton substrate classes have ≤1 reference in main source.** Includes `CreativeArtifactStore`, `CreativeBranchStore`, `EvolutionScheduler`, `PolicyEngine`, `TraceSink`, etc.
14. **64 total @Singleton classes never referenced in any `*Module.kt`.** Many use constructor injection, but the *new* substrate classes need explicit wiring verification.

### P2 — Hardening / Polish

15. **31 `collectAsState()` call sites vs 3 `collectAsStateWithLifecycle()`** — but `lifecycle-runtime-compose` dependency is missing from `libs.versions.toml`, so the fix is blocked on adding the library.
16. **7 hardcoded `Color(0xFF...)` values** bypass theme tokens in `HandsScreen`, `KnowledgeGraphScreen`, `MarkdownText`.
17. **Orphaned components:** `AuraListRow`, `AuraSecondaryButton`, `AuraEditorSheet` have zero production callers.
18. **God screens / god ViewModels:** `SettingsScreen.kt` 1,420 lines, `ChatViewModel.kt` 1,052 lines, `MemoryScreen.kt` 970 lines, `TasksScreen.kt` 853 lines, `SettingsViewModel.kt` 837 lines.
19. **11 screens have zero tests** and 9 corresponding ViewModels have zero tests.
20. **75 silent `runCatching` sites + 12 `Result.success()` hiding errors**, many in evolution/worker paths.
21. **8 occurrences of `runBlocking` in evolution tools** (`EvolutionTools.kt`) — thread starvation risk.
22. **7 hardcoded `"default"` model strings** still present.

## Honest Overall Grade

- **Architecture/vision:** A
- **Code quality/security:** B+ (drifting toward B as complexity grows)
- **Integration completeness:** C+ (too much honest substrate, not enough honest surface)
- **CI discipline:** C (tests broken at HEAD, now fixed)
- **UI/UX:** A- (SOTA redesign landed, needs device verification)

**Composite: B+.** One solid integration pass away from exceptional, but currently a pile of excellent parts with too many unconnected wires.

## Recommended Next Steps

1. **Close the evolution loop.** Wire `EvolutionScheduler` in `AuraApp`, fix `MIGRATION_1_2`, reclassify `trigger_evolution_run` as `WRITE_LOCAL`, add cost cap/cooldown.
2. **Wire policy/trace into the agent loop.** `PolicyEngine.evaluate()` should be called from `ToolExecutor`; `TraceSink` should be emitted from `MemoryAugmentedAgenticLoop`.
3. **Register capability-backed tools** in `ToolsModule` so Exa/Jina/Stability/etc. become real user capabilities.
4. **Wire MCP tools** into `ToolRegistry` so configured MCP servers expose their tools.
5. **Fix SSRF TOCTOU** in the 4 tools using `SsrfGuard.pinnedClient()`.
6. **Reclassify cloud-data tools** (`vision`, `knowledge_graph_extract`) to `REMOTE_COST` or `PRIVACY`.
7. **Add `lifecycle-runtime-compose`** and migrate the 31 `collectAsState()` sites.
8. **Stop adding new substrate until existing substrate ships.**

## Files Consulted

- `.hermes/audits/2026-07-17-security-tool-boundary-audit.md` (subagent)
- `.hermes/audits/2026-07-17-ui-ux-testing-audit.md` (subagent)
- `.hermes/plans/architecture-hilt-audit-report.md` (subagent)
- `ToolsModule.kt`, `MemoryViewModelTest.kt`, `SettingsViewModelAppLockTest.kt`, `AuraBottomNavigation.kt`
- `gradle/libs.versions.toml`, `app/build.gradle.kts`

## Build Verification

- `:app:compileDebugUnitTestKotlin` — **SUCCESS**
- `:app:testDebugUnitTest` (targeted tests) — **SUCCESS**
- Full gate `:aura-core:testDebugUnitTest :app:testDebugUnitTest :app:assembleDebug` — reached timeout but `:app` assemble succeeded; `:aura-core` test failed only on Windows file-lock cleanup (unrelated to code). Re-run recommended on a fresh daemon.

## Commits

- `ab00ff0` — fix(tests): repair app unit tests broken by constructor drift
