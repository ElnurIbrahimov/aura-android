# The correction spine — telling Aura it was wrong

**Date:** 2026-08-12
**Status:** approved, phased

## Context

Aura cannot be corrected. Three subsystems each collect a signal about being
wrong and none of them consume it — the same defect, three times:

- **Memory.** Every memory has a *Helpful / Not helpful* control. It writes to
  `memory_feedback`. **Nothing reads that table** — not retrieval, not the
  evolution detectors, not the outcome scorer. `MemoryStore.recordFeedback`
  exists with zero callers; the UI bypasses it and inserts directly.
  `MemoryFeedbackDao.byMemoryId` and `count` have no callers at all.
- **Skills.** The only thing that records a skill failure is
  `UseSkillTool.kt:60`, and it passes the literal `"_unknown_"` as the skill id
  — because the branch fires when a *name lookup misses*, which is not a skill
  failing. A skill that runs and gives terrible advice records exactly what a
  perfect one records: a single `skill_invoked`.
- **Proactive.** Fixed in the previous cycle; its outcome loop is the working
  model this spec generalises.

Underneath that, two things make the repair urgent rather than merely nice:

**Evolution has never produced a proposal on a real device.**
`EvolutionCoordinator.kt:78` skips every candidate unless
`settings.reflectionEnabled` is true. It defaults to `false` and
`setReflectionEnabled` has **no caller in either module** — only tests set it,
and there is no Settings control. Detectors run, evidence accumulates,
candidates are written and refreshed forever, and zero proposals are created.
All four actions are dead, not just the broken one.

**The one action that could auto-apply would eat the best memories.**
`CONSOLIDATE_MEMORIES` fires on a memory recalled 21+ times in 30 days — the
most useful memory in the store — and proposes merging it away. Apply
hard-deletes the sources, mints a replacement with `importance` hardcoded to
0.7, and drops tags, access count, decay score and provenance. Its outcome
score is a hardcoded `0.7` that reads no evidence. Because recall count is the
only memory signal any detector reads, a memory the user has downvoted ten
times is a *stronger* consolidation candidate than one they never see. The
unsettable flag has been protecting the user from this.

**Goal:** one shared way to say "that was wrong" that memory, skills and
evolution all read from — and an evolution pipeline that is safe enough to turn
on.

**Settled with the user:**

| Question | Answer |
|---|---|
| Scope | Both, as one shared correction spine |
| The gesture | In chat, on what Aura just used |
| Propagation | One hop to derived artifacts, and report what it touched |
| Evolution | Turn it on propose-only, after the destructive parts are fixed |

## The idea

Four kinds of correction, because Aura currently cannot tell them apart and the
distinction is the whole point:

- **Never true** — a mistake. Retract; it stops being retrievable.
- **No longer true** — it *was* right and the world moved. The old memory
  survives as history with an end point; the replacement wins. Today
  "I never lived in Baku" and "I moved away from Baku" produce the identical
  outcome — a slow fade. Those are different facts.
- **Irrelevant here** — true, but it should not have surfaced for *this kind of
  question*. A demotion scoped to the query context, not a global penalty.
- **Bad answer** — aimed at a skill invocation. This is the missing negative
  signal, and it is what supplies a **real skill id** to a pipeline that has
  only ever seen `"_unknown_"`.

Nothing is ever deleted. A correction is an additive record, so being wrong
about being wrong is recoverable.

## Phase 0 — make evolution safe, then switch it on

Repair, and it must land before anything else is safe to enable.

- **Consolidation stops being destructive.** Carry forward `importance`, `tags`,
  `accessCount` and provenance instead of hardcoding 0.7 and dropping them.
  Retire sources rather than hard-delete so rollback is real. Do not widen scope
  to `general` silently on a cross-scope merge — refuse instead.
- **Consolidation stops targeting the most-used memory as such.** High recall
  count alone is not evidence a memory should be merged away.
- **Real outcome scoring** for `CONSOLIDATE_MEMORIES`, replacing the constant.
- **Attribution fix.** `UseSkillTool`'s miss branch records a *lookup* miss
  under its own kind, not a skill failure under `"_unknown_"`.
- **`reflectionEnabled` gets a writer** and a Settings control, defaulting to
  propose-only. Auto-apply stays off.
- **`ratesAround` denominator bug**: the before-rate divides by 14 while the
  after-rate divides by `days.coerceAtMost(14)`, so at the earliest scoring
  moment (day 7) an identical event count reads 2× higher and biases every
  outcome toward "worse".
- **Rollback reachability**: the inbox lists `proposalDao.open()`, which by its
  own `WHERE` clause can never contain an `APPLIED` row, so the rollback screen
  always renders "not found" and the rollback button can never appear.

## Phase 1 — recall provenance

`onMemoryRecalled` passes `runId`, `conversationId` and `turnTimestamp` as
literal `null` at both call sites, so recall events cannot be joined to the turn
that produced them. Fill them in and persist which memories served a turn. This
is the prerequisite for the gesture, and it is small.

## Phase 2 — the correction spine

- `corrections` table: `targetKind`, `targetId`, `kind`, `replacementId?`,
  `note`, `sourceTurn`, `propagatedJson`, `createdAt`.
- The chat gesture: a turn can show what Aura recalled to answer it, and the
  user strikes one out in place. This solves the attribution problem that makes
  "just say it in words" fragile — with six memories in play, knowing *which*
  one poisoned the answer is the hard part.
- Propagation follows **one hop** to what was directly derived (graph entities
  extracted from the same turn, beliefs citing it) and reports in one line what
  it touched, with undo. One hop deliberately: unbounded propagation through a
  knowledge graph is how one correction silently rewrites half a history.

## Phase 3 — consumers

- **Retrieval** excludes retracted memories, lets a superseding memory outrank
  what it replaced, and applies the scoped penalty for *irrelevant here*.
- **Skills**: a *bad answer* correction records `skill_failed` against a real
  id, which makes `PATCH_SKILL` reachable for the first time.
- **Evolution detectors** read corrections rather than only counting usage, so
  a downvoted memory stops being a consolidation candidate.

## Fixed in passing

- **The retrieval eval gate is dead.** `correctly_empty_rate` is committed as
  `0.0` against a measured `0.5` — the floor it was written for was raised from
  0.05 to 0.15 and the baseline was never regenerated. The assertion evaluates
  `0.5 >= -0.005` and passes with half a point of slack, so silence behaviour
  could regress completely and still report green. This is `ENGINEERING_HISTORY`
  §2.8 verbatim: a test that cannot fail for the reason it is named after.
- The harness constructs `MemoryStore` with `reranker` and `queryRewriter` null
  while `RETRIEVAL_EVAL.md` claims it measures both, and scores the `deictic`
  class with rewriting off.

## Not building

No inferring corrections from prose — a mis-attributed correction is worse than
none, because Aura would confidently suppress the wrong memory. No ML. No
deletion. No auto-apply for evolution. No propagation beyond one hop.

## Verification

1. Consolidation never hard-deletes, and a rollback restores exactly.
2. A cross-scope consolidation refuses rather than widening to `general`.
3. `ratesAround` returns equal rates for equal counts at day 7.
4. An applied proposal is reachable from the rollback UI.
5. A `never true` correction removes the memory from recall; a `no longer true`
   correction makes the replacement outrank the original; an `irrelevant`
   correction demotes only for similar queries.
6. Propagation touches one hop and reports it; undo restores.
7. A `bad answer` correction produces a `skill_failed` row with a real skill id,
   and the `PATCH_SKILL` detector produces a candidate whose target resolves.
8. The regenerated eval baseline fails when silence regresses.
9. On device: correct a memory in chat, confirm it stops being recalled and that
   the report named what else it touched.
10. Full suite + `assembleRelease` + both lint tasks + the three gate scripts
    before each phase lands.
