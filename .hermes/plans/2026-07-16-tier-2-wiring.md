# Tier 2 wiring plan for Aura Android v0.18.0

**Goal:** Wire the remaining beyond-SOTA backend substrates into real user-facing surfaces so they actually deliver value.

**Prerequisite:** Tier 1 complete (capability tools registered, Tool Permissions / Model Roles / MCP Servers in Settings).

**Scope:** 6 items. All are "backend exists, surface missing" — no new Room migrations, no new architectural substrate.

---

## Item 1 — AgentRun history screen + wire RunHandTool to it

**Backend already exists:**
- `AgentRunStore.kt` (CRUD, checkpoint/resume)
- `AgentRunDatabase.kt` (6 entities)
- `RunHandTool.kt` (currently a no-op / stub — it should queue a run)

**Gap:** Agent runs exist only in code. User cannot see durable runs, resume a failed run, or approve pending steps.

**Work:**
1. `app/src/main/kotlin/com/aura/ui/viewmodel/AgentRunsViewModel.kt` — expose `runs: StateFlow<List<AgentRun>>`, `resume(runId)`, `approve(runId, stepId)`, `cancel(runId)`.
2. `app/src/main/kotlin/com/aura/ui/screens/AgentRunsScreen.kt` — list runs with status chip (pending/approved/running/done/error), tap to expand steps, show approval buttons, resume button for error runs.
3. `app/src/main/kotlin/com/aura/ui/screens/AgentRunDetailScreen.kt` — single-run timeline: goals → steps → events → approvals. Show checkpoint state if present.
4. `aura-core/src/main/kotlin/com/aura/tools/RunHandTool.kt` — change execute body to create a `AgentRun` via `AgentRunStore` for the hand, return `ToolResult.Ok("Queued hand '${hand.name}' as run ${run.id}")`.
5. `app/src/main/kotlin/com/aura/ui/NavGraph.kt` — add `/agent_runs` and `/agent_runs/{runId}` routes; add bottom-bar or overflow-menu entry.
6. Tests:
   - `AgentRunsViewModelTest.kt`: resume, approve, cancel flows.
   - `RunHandToolTest.kt`: verify it creates a run.

**Files to touch:** ~8
**Est. time:** 1h

---

## Item 2 — Creative Council trigger button in Creative Studio

**Backend already exists:**
- `CreativeCouncil.kt` (10 roles, director orchestration)
- `CreativeEngine.kt` (six modes)
- `CreativeProjectStore.kt` / `CreativeArtifactStore.kt` / `CreativeBranchStore.kt`

**Gap:** Council exists but nothing calls `runCouncil(projectId, prompt)` from the UI. Creative Studio only has manual tabs.

**Work:**
1. `app/src/main/kotlin/com/aura/ui/viewmodel/CreativeStudioViewModel.kt` — add `runCouncil(projectId, brief)` that calls `CreativeCouncil.run()` and writes artifacts/continuity notes.
2. `app/src/main/kotlin/com/aura/ui/screens/CreativeStudioScreen.kt` / `CreativeProjectScreen.kt` — add "Run Council" floating action button or top-bar action. Show progress while council runs.
3. Persist council output: each role's output becomes an artifact revision; director summary becomes `canon_fact`.
4. Tests:
   - `CreativeCouncilViewModelTest.kt`: verify calling runCouncil produces ≥1 artifact and no crash on missing project.

**Files to touch:** ~4
**Est. time:** 45m

---

## Item 3 — TasteEngine feedback UI in chat

**Backend already exists:**
- `TasteEngine.kt` (signal recording, profile aggregation, model routing)
- `UserPreferences.kt` has `preferenceSignals`, `styleProfiles`, `referenceIdentities`

**Gap:** TasteEngine records signals but the user has no way to vote "this output was good/bad". Without signals, routing never improves.

**Work:**
1. Add thumbs-up/thumbs-down affordance on assistant message bubbles in `MessageBubble.kt` (only on completed assistant turns, not streaming).
2. `ChatViewModel.kt` — `recordTasteSignal(turnId, helpful: Boolean, note: String?)` calls `TasteEngine.recordSignal()` with:
   - model used
   - specialist/role active
   - tool calls invoked
   - response length
   - user thumbs direction
3. Optional: "Why?" sheet when user taps thumbs-down (predefined tags: wrong, too long, too short, unsafe, off-topic, other).
4. Tests:
   - `TasteEngineSignalTest.kt`: signal recorded on thumbs-up.
   - `ChatViewModelTasteTest.kt`: verify turn ID resolves and `recordSignal` invoked once.

**Files to touch:** ~5
**Est. time:** 1h

---

## Item 4 — Production pipeline execution engine + UI

**Backend already exists:**
- `ProductionPipeline.kt` (6 pipelines: novel, screenplay, short film, trailer, podcast drama, RPG campaign)
- `CapabilityRouter.kt` + `ImageGenCapabilityTool` + `WebSearchCapabilityTool`
- `CreativeArtifactStore.kt` for outputs

**Gap:** Pipelines are data classes with stage definitions but no executor. No UI to start a pipeline.

**Work:**
1. `aura-core/src/main/kotlin/com/aura/creative/ProductionPipelineEngine.kt` — pure function `execute(pipeline, projectId, onStageComplete): Result<Artifact>`:
   - Resolve each stage's required capability via `CapabilityRouter`.
   - Call `ImageGenCapabilityTool` / `WebSearchCapabilityTool` / LLM as needed.
   - Store each stage artifact; fail fast if a required capability is unconfigured.
2. `app/src/main/kotlin/com/aura/ui/screens/ProductionPipelineScreen.kt` — pick pipeline, enter prompt, show stage progress, open final artifact in Creative Studio.
3. `app/src/main/kotlin/com/aura/ui/viewmodel/ProductionPipelineViewModel.kt` — orchestrate execution, surface stage status.
4. Add route `/production` and a card on Home screen.
5. Tests:
   - `ProductionPipelineEngineTest.kt`: happy path with mocked capabilities; fail path when capability unavailable.

**Files to touch:** ~7
**Est. time:** 1.5h

---

## Item 5 — Document RAG indexing + canon/world/taste query tools

**Backend already exists:**
- `MemoryDatabase.kt` v7+ has `document_chunk` table + embedding metadata
- `MemoryStore.kt` has CRUD for memories but no document chunking
- Canon/world/taste tables exist (DB v9/v10/v11)

**Gap:** Document chunks schema exists but nothing creates chunks. No chat tools query canon / world / taste data.

**Work:**
1. `aura-core/src/main/kotlin/com/aura/memory/DocumentChunker.kt` — split text/markdown/PDF-plaintext into chunks (max 1000 chars, overlap 200), assign deterministic chunk IDs.
2. `aura-core/src/main/kotlin/com/aura/tools/IndexDocumentTool.kt` — tool `index_document` (WRITE_LOCAL) accepts text/uri and stores chunks via `MemoryStore`/`DocumentChunkDao`.
3. Add query tools:
   - `QueryCanonTool.kt`: search `canon_fact`, `simulation`, `continuity_issue` tables by text/vector.
   - `QueryWorldModelTool.kt`: search `belief`, `evidence`, `world_event`, `opportunity`.
   - `QueryTasteTool.kt`: read `preference_signal`, `style_profile`, `reference_identity` to answer "what do I like?"
4. Register all in `ToolsModule.kt`.
5. Tests:
   - `DocumentChunkerTest.kt`: chunk count and overlap.
   - `QueryCanonToolTest.kt`: returns facts matching query.

**Files to touch:** ~9
**Est. time:** 1.5h

---

## Item 6 — Jina / Kling / WorldLabs provider tools + capability wiring

**Backend already exists:**
- `CapabilityKind.kt` has `ImageGeneration`, `VideoGeneration`, `World3DGeneration`, `WebSearch`
- `CapabilityRegistry.kt` is extensible; only Exa + DDG are wired for WebSearch
- `ProviderKeys.kt` has prefixes for `jina`, `kling`, `worldlabs` if configured

**Gap:** Kling/WorldLabs/Jina provider keys can be saved, but no tools use them. Image gen still falls back to legacy `ImageGenTool`.

**Work:**
1. `aura-core/src/main/kotlin/com/aura/capabilities/ImageGenProvider.kt` — add `StabilityProvider`, `KlingProvider`, `WorldLabsProvider` implementations of `CapabilityProvider<ImageGeneration>`.
2. `aura-core/src/main/kotlin/com/aura/tools/WebSearchCapabilityTool.kt` — add `JinaProvider` for search result extraction/embedding.
3. `aura-core/src/main/kotlin/com/aura/capabilities/CapabilityRegistry.kt` — register new providers keyed by prefix.
4. Update `ToolsModule.kt` so `ImageGenCapabilityTool` resolves through new providers (already registered in Tier 1; ensure registry wiring).
5. Tests:
   - `CapabilityRegistryTest.kt`: all configured providers return non-null for their kinds.
   - `ImageGenCapabilityToolTest.kt`: uses first configured image provider.

**Files to touch:** ~7
**Est. time:** 1.5h

---

## Verification gates for Tier 2

After each item:
1. `./gradlew :aura-core:testDebugUnitTest --tests '<new-test-pattern>'`
2. `./gradlew :app:testDebugUnitTest --tests '<new-test-pattern>'`
3. `./gradlew :app:compileDebugKotlin :app:lintDebug`

End-of-Tier-2 gate:
- `./gradlew :aura-core:testDebugUnitTest :app:testDebugUnitTest :app:lintDebug :app:assembleDebug`
- 5 pre-existing FirecrawlFetchToolTest SSRF failures are expected.

---

## Persona split / recommended order

1. **Taste feedback** — lowest risk, daily-use UX win. Start here.
2. **Creative Council button** — unlocks the council backend already shipped.
3. **AgentRun history** — closes the loop on durable runs; high leverage.
4. **Production pipeline engine** — bigger UI lift; do after council.
5. **Document RAG + query tools** — unlocks v7/v9/v10 DB migrations.
6. **Jina/Kling/WorldLabs providers** — extends capability surface; depends on registry understanding from Item 4.

**Total estimate:** ~7h across 6 atomic commits (one per item).
