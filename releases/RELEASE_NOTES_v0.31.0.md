# Aura Android v0.31.0 (2026-07-23)

**Version code:** 33
**Previous:** v0.30.2 (32)

## Headline

Dream consolidator v2 (9-phase pipeline matching Python Aura), MoA test
fix, ProactiveEvents scope leak fix, and dream summaries screen.

## Features

### Dream Consolidator v2 (phases 3-9)

Extended the v1 cluster+summarize pipeline to the full 9-phase cycle
matching the Python Aura's DreamConsolidator:

- **Phase 5: Extract Routines** — mines tool-call N-grams from recent
  conversation turns. 2-4 length sequences that appear in 3+ distinct
  conversations are upserted into `routines` table with a stable signature.
- **Phase 6: Update Profile** — refreshes user profile from consolidated
  dream summaries (name, traits, facts).
- **Phase 7: Prune Stale** — archives low-importance, old memories by
  setting decayScore to 0 (non-destructive, FadeMem treats 0 as forgotten).
- **Phase 8: Contradiction Report** — detects summaries that contradict
  older versions of the same cluster via negation-pattern heuristics.
- **Phase 9: Densify Graph** — proposes new KG edges between similar
  nodes using Jaccard similarity on node attributes.

### Dream Summaries Screen

New `DreamsScreen` accessible from Memory tab. Shows mined routines
(ordered by occurrence count) and detected contradictions (with trigger
phrase, older/newer text, status). The MemoryScreen routine and
contradiction chips now navigate to this screen instead of being dead
TODOs.

### Evolution Loop Closed

- Rejection reason dialog (4 preset chips + free text). Reasons feed
  EvolutionCandidateDetectors to suppress similar future candidates and
  EvolutionSafetyGuard for escalating hard-blocks.
- Bottom-nav badge showing pending proposal count (live across all tabs
  via EvolutionBadgeViewModel at scaffold scope).

## Bug Fixes

### P0: MoA test cancellation propagation

`MoaProviderTest."starting a new MoA run cancels the previous run"` was
failing. `runCurrent()` alone didn't give the cancellation chain enough
rounds to propagate through the channelFlow's internal channel. Added
`advanceTimeBy(1L) + runCurrent()` to let the CancellationException
propagate: cancel -> async resume -> await -> rethrow -> channel close
-> collect -> launch cancelled.

### P0: ProactiveEvents scope leak

`ProactiveEvents` had `@Inject constructor` but no `@Singleton`. Injected
into `HomeViewModel` (activity-scoped), every Activity recreation created
a new instance with a new `SupervisorJob` scope that never closes. Rotating
the phone 3-4 times leaked 3-4 orphaned coroutine scopes. Added
`@Singleton` so the scope lives for the app lifetime.

### P1: MCP client version string stale

`McpConnection.kt` sent `clientInfo.version = "0.16.0"` in the MCP
initialize handshake. The app is v0.31.0. Updated to match.

## Documentation

- README: updated version (v0.30.2 -> v0.31.0), test count (1,156 ->
  1,173), test file count (192 -> 215), daemon interval (8 min -> ~15
  min), commit count (416 -> 468).
- architecture.md: updated version string (0.10.2 -> 0.31.0, versionCode
  3 -> 33). Was 21 versions stale.

## Tests

1,173 unit tests (912 aura-core + 261 app), 0 failures. assembleDebug
green.