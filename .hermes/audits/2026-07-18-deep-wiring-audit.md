# Aura Android — Deep Wiring Audit

Date: 2026-07-18
Repository: `D:\aura-android-clean`
Branch / HEAD: `feat/tier-1-friction` / `5fdb9a72`
Scope: live source, DI/registration, workers, tool policy, permissions, providers/capabilities, MCP, evolution, AgentRun, Hands, creative pipelines, Room migrations, backup/restore, settings/UI, emulator runtime, local verification, GitHub Actions/releases.

## Verdict

The project compiles and lints, but **v0.23.0 is not a trustworthy release**. Several headline features are shells or guaranteed failure paths, the proactive subsystem has a production infinite loop, permission/cost approval paths do not close, the full test suite is red and can hang forever, and the live bottom navigation/onboarding CTA are clipped on the Android 11 emulator.

This is not a “few polish items remain” state. The remaining defects are subsystem-contract failures.

## Verification truth

- `:aura-core:assembleDebug`, `:aura-core:lintDebug`, `:app:assembleDebug`, `:app:lintDebug`: **green**.
- Unit gate: **red**.
  - `ConversationStoreTest.save then load...`: crashes because production `Log.w` is unmocked in JVM tests.
  - `EvolutionSafetyGuardTest.detects api key leak`: stale/invalid fixture no longer matches the 20+ character guard.
  - `ProactiveEventsTest`: never finishes because production code loops event insert → re-emit → insert forever. A live thread dump showed the test worker burning CPU in `FakeProactiveEventDao.insert()` from `ProactiveEvents.kt:115`.
- `:aura-core:connectedDebugAndroidTest`: **does not compile** — missing `assertTrue` import in `MemoryDatabaseMigrationTest.kt:175`.
- `:app:connectedDebugAndroidTest`: 38 tests started, **1 failed** — `ModelSelectionFlowTest` timed out waiting for provider verification.
- Current branch has **no GitHub Actions run** and no PR. The latest repository CI run is on `main` from 2026-06-26.
- Live Android 11 emulator launch succeeded with no fatal crash, but exposed the clipped navigation/CTA defect below.

# P0 — release blockers

## 1. Proactive events create an infinite persistence feedback loop

Evidence:
- `ProactiveEvents.kt:109-123` collects `bus.events`, inserts each event, then calls `bus.tryEmit(event.withId(insertedId))` onto the **same bus**.
- The collector receives the re-emitted event, inserts it again, emits it again, indefinitely.
- The full unit run hung for ~30 minutes; `jcmd` showed the test worker continuously executing `FakeProactiveEventDao.insert()` through `ProactiveEvents.kt:115`.

Impact:
- Any morning brief/calendar/memory-decay event can cause continuous Room inserts, unbounded event growth, battery/CPU drain, and a hot process.

## 2. Runtime permission retry can never succeed

Evidence:
- `ToolExecutor.kt:56-62` trusts only `ToolContext.permissions`.
- `MemoryAugmentedAgenticLoop.kt:307-311` constructs `ToolContext` without permissions.
- `ChatViewModel.kt:799-835` receives the granted permission but suppresses the parameter as unused, then retries with another empty `ToolContext`.
- Seven tools declare required permissions: biometric, calendar read/write, contacts, location, notifications, and photo library.

Impact:
- The tool returns `NeedsPermission` again after the user grants permission. Agent-triggered device tools loop or dead-end. Multi-permission tools can never advance.

## 3. REMOTE_COST approval has no end-to-end UI/runtime path

Evidence:
- `ToolExecutor.kt:66-71` rejects every `REMOTE_COST` tool not present in `approvedRemoteCostTools`.
- Normal agent contexts never populate that set.
- `AgentEvent.ToolResult` exposes permission fields but no approval field (`MemoryAugmentedAgenticLoop.kt:477-485`).
- `ChatSendController.kt:242-275` handles permissions only.
- Direct image/audio UI paths call `tool.execute(...)` directly (`ChatViewModel.kt:903-919`, `989-1002`), bypassing `ToolExecutor` and therefore bypassing the cost gate entirely.

Impact:
- Through chat: deep research, vision, transcription, translation, image generation, and TTS can be permanently blocked.
- Through direct UI: some of the same paid tools run without the declared approval policy.

## 4. Evolution database upgrade chain is broken

Evidence:
- `EvolutionDatabase` is version 3.
- `EvolutionModule.kt` defines both `MIGRATION_1_2` and `MIGRATION_2_3`.
- The Room builder registers only `arrayOf(MIGRATION_2_3)`.
- The v3 exported schema is currently untracked and there is no Evolution migration test.

Impact:
- A device with evolution DB v1 upgrading to current v3 will fail to open the database.

## 5. Creative Council always resolves an invalid chat model

Evidence:
- `CreativeStudioViewModel.resolveSubagentModel()` returns an image/search capability **provider prefix** such as `stability`/`exa`, or literal `default` (`CreativeStudioViewModel.kt:190-203`).
- It passes that value to `ProviderRegistry.chat()`.
- `ProviderRegistry.parse()` requires `provider:model` and rejects both a bare prefix and `default` (`ProviderRegistry.kt:23-30`).
- The configured `CREATIVE_DRAFT` / `CREATIVE_CRITIC` model roles are not consulted.

Impact:
- Every Creative Council member call fails before provider execution. The 10-role council UI is a non-working headline feature.

## 6. Production pipelines contain guaranteed failure steps

Evidence:
- `ProductionPipelineEngine` wraps every stage as tool `creative_engine`, including stages named `image_generate` and `tts_speak`.
- `CreativeEngineTool` accepts only `brainstorm`, `outline`, `draft`, `rewrite`, `simulate`, `continuity`; media stage names return `unknown_stage`.
- Every pipeline appends `creative_add_world_item` with args `section` + `content`; the tool requires `type` + `name` + `description` (`CreativeTools.kt:91-98`).
- Every generated step has `dependsOn = "[]"`; outputs are never fed into subsequent stages.
- The richer dependency-aware definitions in `pipeline/ProductionPipeline.kt` have zero production consumers.

Impact:
- All built-in pipelines fail at the final step; short-film/trailer/podcast paths fail earlier. Successful stage output is not assembled into a durable artifact.

## 7. Live first-run CTA and bottom navigation are clipped under the system edge

Live evidence from Android 11 x86_64 emulator:
- Window bounds: `[0,0][1080,2072]`.
- Onboarding primary button: `[44,2032][1036,2072]`; its text node is `[0,0][0,0]`.
- Home bottom navigation tabs: y=`2054..2072` — only 18 px visible; every label node is `[0,0][0,0]`.
- The defect persists after waiting for animations and appears on Home and Settings.

Likely boundary:
- Edge-to-edge `MainActivity` + `Scaffold`/`AnimatedVisibility` bottom-bar inset handling (`NavGraph.kt:83-111`, `AuraBottomNavigation.kt:91-153`).

Impact:
- The primary onboarding CTA and all five navigation tabs are nearly invisible and only tappable through a tiny sliver.

# P1 — high-severity broken/unwired flows

## 8. AgentRun approval and resume are one-way dead ends

- `AgentRunExecutorWorker` creates an approval request, then marks the step `FAILED`.
- `AgentRunsViewModel.approve()` changes only the approval row; it does not reset the step or enqueue the worker.
- `resume()` changes run status/checkpoint only; it does not enqueue execution.
- A failed run exposes no valid recovery path.

## 9. Agent-triggered Hands bypass Hand semantics

`RunHandTool` always takes `HandRunEnqueuer` when a hand exists. The enqueuer:
- does not check `enabled`;
- does not evaluate conditions;
- does not perform `{{variable}}` substitution;
- does not create/update `hand_runs` history;
- gives all steps no dependencies;
- merely merges variables as extra top-level args.

The correct `HandRepository.run()` path becomes unreachable for agent-issued runs, while manual/scheduled runs use different semantics.

## 10. Tool Permissions settings are a dead control surface

- `PolicyEngine` and `ToolPolicyStore` exist.
- Settings can modify policies.
- `ToolExecutor` never injects or calls `PolicyEngine`; it hardcodes a separate partial policy.
- Settings renders only `toolPolicies.take(8)` with no expansion.

Result: policy changes do not affect runtime behavior, and most registered tools cannot even be edited in the UI.

## 11. Tool risk metadata is internally inconsistent

Verified examples:
- `creative_engine`: `READ_ONLY` but calls a paid model and increments/persists project state.
- `knowledge_graph_extract`: `READ_ONLY` but calls a paid cloud model.
- every dynamically bridged MCP tool: forced to `READ_ONLY`, even if the remote tool mutates state.
- `http_file_write`: `REMOTE_COST` despite mutating arbitrary remote endpoints; should be `WRITE_REMOTE`.
- `use_skill`: `READ_ONLY` but persists evolution evidence.

Because policy enforcement already bypasses `PolicyEngine`, these mismatches compound rather than merely mislabel.

## 12. Capability providers are wired in core but disabled in Settings

- Stability, ElevenLabs, Kling, and World Labs are bound through `CapabilityModule` and used by capability tools.
- `SETTINGS_CREDENTIAL_SPECS` marks all four `isConsumed = false`.
- `ProviderKeyField` disables editing and says “Coming soon — this key isn't consumed by any tool yet.”
- Live emulator verified this state for Kling and World Labs.

Result: the backend exists but the user cannot configure the credentials needed to use it.

## 13. MCP authenticated setup and risk boundary are broken

- `SettingsViewModel.testMcpConnection()` builds a config containing `authToken`, then calls `mcpClientManager.connect(config)` without passing the token argument; first connection to authenticated servers fails. Restart reconnect does pass it.
- MCP tools are all treated as `READ_ONLY` regardless of server annotations/behavior.
- `McpClientManager.connections` and `McpToolBridge.registeredNames` are unsynchronized mutable collections.
- MCP endpoint requests do not use the same DNS-pinned SSRF path as hardened URL tools.

## 14. Evolution is mostly proposal theater

- 13 of 19 `EvolutionAction` values return `not yet implemented`.
- Active detectors produce at least `PROMOTE_TO_HAND`, `CONSOLIDATE_MEMORIES`, and `REWRITE_RULE_MESSAGE` — all unimplemented.
- UI approval marks the proposal approved before apply; an implementation error leaves it open/approved with no resolution path.
- `NEW_PROACTIVE_RULE` inserts a `ProactiveEventEntity`; it creates no rule or schedule, and unknown event types are ignored by `toEvent()`.
- `autoApplyApproved`, shadow mode, budgets, retention, and the global `evolutionShadowEnabled` setting are not used to close the apply/evaluation loop.

## 15. World model and canon query read tables with no producers

- `BeliefDao`, `WorldEventDao`, and `OpportunityDao` are queried by UI/tools, but production has no writer (the only synthesizer is unreferenced; `CREATE_BELIEF` is unimplemented).
- `CanonFactDao` is queried by `canon_query`, but no production path inserts canon facts.
- Creative world-bible edits write a separate JSON project store.

Result: world/canon features are registered and displayed but remain empty on normal use.

## 16. Taste Twin records noisy data but influences nothing

- Chat reactions write preference signals and recompute a profile.
- No production path consumes `TasteEngine.getProfile()` or `bestModelForRole()` for prompts/routing.
- `recordRoutingOutcome()` has no production caller.
- Clearing a reaction records a new negative signal; switching reactions accumulates contradictory rows rather than replacing the prior signal.

Result: “style profiling/model routing” in the README is not true.

## 17. Backup claims “full local state” but silently drops current features

- `AuraBackup` declares evolution proposals/settings/revisions, but `BackupManager.snapshot()` never populates them.
- `PreferencesBackup` has evolution fields, but snapshot leaves them at defaults.
- Current role models, vision/background/deep models, MoA config, image model, MCP server config, evolution shadow state, memory edit history, AgentRun data, beliefs/world state, taste state, canon state, and artifact revisions are omitted.
- Secret omission is appropriate; non-secret configuration/state omission is not documented.

## 18. SMTP password is stored in plain Preferences DataStore

`UserPreferences.KEY_SMTP_PASSWORD` and `setSmtpConfig()` persist the password alongside ordinary preferences. Provider and MCP credentials use `SecureDataStore`; SMTP does not.

## 19. User-URL HTTP tools retain a DNS-rebinding gap

`HttpFileReadTool` / `HttpFileWriteTool` call `SsrfGuard.validate(url)` and then issue the request with the ordinary `OkHttpClient`, causing a second DNS resolution. They do not carry the inspected address into `SsrfGuard.pinnedClient(...)` as `DeepResearchTool` does.

# Release and verification integrity

## 20. v0.19.0 through v0.23.0 tags all point at the same stale main commit

Remote tag target for every release: `5ff05e338f352fc52e923411d225f7c657e9944b`.
Current release branch HEAD: `5fdb9a72d2147a9f9c20f1f37afe9122809f4831`.

The v0.23 APK hash matches the local branch build, but the release tag/source page points to unrelated old source.

## 21. APK / README / release version identities disagree

- GitHub release: `v0.23.0`.
- README: `v0.21.0`.
- APK manifest: `versionCode=18`, `versionName=0.15.2-debug`.

## 22. “~1000 tests passing, 0 failures” is false

Current source has roughly 1,014 unit `@Test` methods, but:
- 2 isolated unit failures are reproducible;
- the full core run hangs in the proactive feedback loop;
- core connected tests do not compile;
- app connected tests fail 1 of 38;
- no current-branch GitHub Actions run exists.

# Unwired substrate inventory (not all are standalone bugs)

These are implemented/tested primitives with no meaningful production consumer:

- `TraceSink` + `RunContext` / agent trace events;
- `DocumentChunkEntity` / `DocumentChunkDao` (actual document indexing stores whole text as memory);
- `CreativeArtifactStore` + `CreativeBranchStore` and associated artifact/revision/branch/job tables;
- dependency-aware `pipeline/ProductionPipeline.kt` definitions;
- `EvolutionSkillDetector`, `EvolutionMemorySynthesizer`, `EvolutionProactiveRuleGenerator`, `EvolutionSubagentExecutor`, `EvolutionShadowEvaluator`;
- `ProactivePolicyEngine`;
- `ReferenceIdentityDao`;
- model-role values `FAST`, `REASONING`, `CREATIVE_DRAFT`, `CREATIVE_CRITIC`, `PLANNER`, `VERIFIER` (stored/configurable, not consumed by normal runtime routing).

# Prior/stale findings explicitly rejected

Current source verification shows these are no longer valid:

- “MCP tools are not bridged into ToolRegistry” — `McpToolBridge` now exists and is called.
- “Evolution scheduler is never started” — `ProactiveBootstrap` now reconciles it.
- “trigger_evolution_run is READ_ONLY” — it is now `WRITE_LOCAL`.
- “vision is READ_ONLY” — it is now `REMOTE_COST`.
- “EvolutionSafetyGuard only recognizes OpenAI keys” — patterns were expanded.
- “production route is missing” — route is now registered.
- “Citation.kt is dead” — it has active production consumers.
- literal navigation-route scan found no currently missing route registration.

# Recommended execution order

1. Stop proactive event re-emission loop and lock it with a non-recursive persistence test.
2. Fix system insets on onboarding and bottom navigation; verify live on API 26/30/35.
3. Repair permission and remote-cost approval contracts end-to-end; make ToolExecutor the single boundary.
4. Register Evolution `MIGRATION_1_2`; add migration test and commit schema v3.
5. Fix Creative Council model-role resolution and production-pipeline tool/dependency/output contracts.
6. Repair AgentRun approval/resume and HandRun semantic parity.
7. Wire PolicyEngine; correct tool risks; harden URL tools/MCP.
8. Enable capability credentials and close MCP authenticated first-connect flow.
9. Either wire evolution/world/taste/canon/artifact substrate or remove/label it honestly.
10. Repair backup coverage and move SMTP password to secure storage.
11. Make all unit/connected tests green, then fix CI/release targeting and version identity before another APK release.
