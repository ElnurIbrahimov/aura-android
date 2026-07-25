# Belief Revision — Design

**Date:** 2026-07-26
**Status:** Approved, not yet implemented
**Scope:** Close the loop between the knowledge graph, the world model, and the
dream cycle so Aura's model of the user demonstrably self-corrects and can show
its work.

---

## 1. Problem

Aura has a world model with beliefs, evidence, confidence, validity windows and
supersession. It has a knowledge graph extracted from every turn. It has a dream
cycle that detects contradictions. None of them are connected.

Concretely, verified against HEAD:

| Component | State |
|---|---|
| `BeliefEntity` | Has `confidence`, `validFrom`/`validTo`, `status`, `supersededBy`, `lastVerifiedAt`. All unused. |
| `BeliefDao.active(subject, predicate)` | 0 production callers |
| `BeliefDao.history(subject, predicate)` | 0 production callers |
| `BeliefDao.verify(...)` | 0 production callers |
| `BeliefDao.supersede(...)` | 2 callers, both passing an empty `supersededBy` — used as retire/rollback. **No supersession chain has ever been formed.** |
| Belief producers | **None.** `CREATE_BELIEF` exists as an evolution action, but no detector ever proposes one, so the table has no way to fill. |
| `DreamConsolidator.detectContradictions()` | Compares *dream summaries* by text pattern ("no longer", "switched from X to Y") at fixed 0.6 confidence. Writes `ContradictionEntity`. **Nothing consumes the output.** |

So the world model is a schema with no producer, and the contradiction detector
finds a different kind of conflict than belief revision needs — text conflict
between summaries, not conflict on a (subject, predicate, value) triple.

The substrate is real and well-designed. It was never wired into a loop.

## 2. Decisions

Three forks, decided before design:

1. **Arbitration — revise silently, always show the work.** No approval gate.
   Aura supersedes on its own and acts on the new belief immediately; the full
   chain stays inspectable. Chosen for research value: a system that genuinely
   self-corrects is the interesting claim, and an approval inbox means the model
   of the user goes stale at the speed of triage.
2. **Timing — cheap check live, hard adjudication offline.** The obvious case
   (same subject+predicate, different value) is a DB query and runs on the write
   path for free. Semantic conflict needs a model call and is deferred to the
   dream cycle, which already runs periodically on charge.
3. **Belief source — promote from KG edges.** `EdgeEntity` already carries
   `type` (predicate), `sourceId`→`targetId`, `confidence`, `weight`,
   `lastReinforced`, and `sourceTurnId`/`sourceConversationId`/
   `sourceTurnTimestamp`. It is a provenance-bearing triple, extracted by an LLM
   call already paid for every turn. A belief is a KG edge that crossed a
   reinforcement bar.

   One caveat established during review: `KnowledgeGraphRepository.insertEdge`
   REPLACEs on reinforcement, overwriting `sourceTurnId` with the *latest*
   supporting turn and setting `lastReinforced = now`. There is no reinforcement
   counter. So edge provenance is last-writer, not first-writer, and the edge
   alone cannot answer "when did I first say this". Section 5 works within that;
   section 4 explains why writing evidence rows at promotion time is what
   recovers the history the edge discards.

## 3. Architecture

```
per turn — no new cost
  ConversationKgExtractor → EdgeEntity(subject, predicate, object,
                                       confidence, sourceTurnId)
        │
        └─ BeliefConflictProbe (pure DB, no model call)
              beliefDao.active(subject, predicate)
              differs from the new edge's object?
                 → supersede now, record evidence

dream cycle — on charge, already scheduled
  Phase 10  PROMOTE     KG edges past the bar → BeliefEntity + EvidenceEntity
  Phase 11  ADJUDICATE  LLM over candidate pairs the probe could not judge
  Phase 12  RESOLVE     write supersession, mark ContradictionEntity RESOLVED
```

New dream phases are appended (existing phases run 1–9), so ordering of current
behaviour is untouched.

### Units

Each is independently testable and has one job.

| Unit | Responsibility | Depends on |
|---|---|---|
| `BeliefPromoter` | KG edge → belief + evidence, applying the reinforcement bar | `EdgeDao`, `BeliefDao`, `EvidenceDao` |
| `BeliefConflictProbe` | Structural conflict detection on the write path | `BeliefDao` |
| `BeliefArbiter` | Pure scoring: given two beliefs + their evidence, which wins, or neither | nothing (pure function) |
| `BeliefReviser` | Applies an arbiter verdict: supersession write + evidence + contradiction resolution | `BeliefDao`, `EvidenceDao`, `ContradictionDao` |

`BeliefArbiter` is deliberately dependency-free so the scoring rule can be
unit-tested exhaustively without Room, and so the convergence eval can drive it
directly.

## 4. Data model

**No change to `BeliefEntity`.** Every field this design needs already exists.

Two additions:

- `EvidenceEntity.source` gains the value `"kg_edge"`. `detailJson` carries
  `{"edgeId": …, "sourceTurnId": …, "conversationId": …}`. This is what makes
  "because Z" resolvable back to a turn where the user said it — no new
  provenance plumbing.

  Because the edge overwrites `sourceTurnId` on every reinforcement, each
  evidence row is a point-in-time snapshot of provenance the edge itself will
  later discard. Accumulated evidence rows are therefore the only durable record
  of *when* support arrived, which is exactly what the arbiter's recency and
  corroboration signals read. Evidence is append-only; it is never rewritten.
- `ContradictionEntity` gains nullable `olderBeliefId` and `newerBeliefId`.
  Today the table only links dream summaries. Nullable columns keep every
  existing row valid; migration is `ALTER TABLE ADD COLUMN` on
  DreamConsolidationDatabase v2→v3.

Superseding never deletes. The old row keeps its evidence and gains
`status = "superseded"`, `supersededBy = <new id>`, `validTo = now`. The new row
gets `validFrom = now`. The chain is therefore walkable backwards, and that walk
*is* the "I used to think X" feature rather than a separate audit log.

## 5. Promotion rule

An edge is promoted to a belief when **all** hold:

- The subject is the user (edges about third parties are not beliefs about you).
- `confidence >= 0.7`.
- `lastReinforced > createdAt` — the edge was seen again in a later turn. One
  offhand remark is not a belief.

The third condition is a proxy, not the ideal rule. `EdgeEntity` has no
reinforcement counter, so "seen in ≥ 2 turns" is the strongest bar expressible
today; "≥ 3 turns" is not. If the bar needs to be tunable, the clean upgrade is
a `reinforcementCount` column on `EdgeEntity` incremented in
`KnowledgeGraphRepository`, which is a one-column migration. Deliberately not
done in slice 1 — ship the proxy, tune only if promotion proves too noisy in
practice.

Promotion is idempotent: re-promoting an unchanged edge calls
`beliefDao.verify(...)` to bump `lastVerifiedAt` rather than creating a
duplicate.

## 6. Arbitration rule

Last-write-wins is wrong; a single stray remark must not overturn a
well-established belief. `BeliefArbiter` scores each side:

| Signal | Weight | Rationale |
|---|---|---|
| Recency of newest supporting evidence | strongest | People change; recent statements usually reflect current truth |
| Corroboration — distinct turns supporting | strong | Repetition separates a real change from a one-off |
| Source rank: `user_statement` > `tool_result` > `derived` | moderate | A direct statement outranks an inference |
| Existing belief confidence | tiebreak only | Incumbency should not by itself win |

**Refusing to decide is a valid, expected outcome.** If the margin between the
two sides is below `ARBITER_MIN_MARGIN` (starting value 0.15 on a 0..1 normalised
score, tuned against the convergence eval in section 8), nothing is revised: the
`ContradictionEntity` stays `UNRESOLVED` and is re-adjudicated on a later cycle
with more evidence. This is the main safety property of the design — the failure
mode we are protecting against is confident wrongness, not slowness.

## 7. Surface

Consistent with "revise silently, show the work":

- **Beliefs screen** (currently 100 lines, read-only) — each active belief
  expands to its supersession chain and the evidence on each link.
- **Chat** — `query_world_model` extended to return supersession history, so
  "why do you think I prefer X?" returns the real chain sourced to turns.
- **No notification and no inbox.** Silent by choice.

## 8. Testing

Ordinary unit coverage on: the promotion bar (including the 2-turn requirement
and idempotent re-promotion), the arbiter scoring function, refusal below margin,
and the invariant that supersession never deletes a row.

The distinctive test is a **convergence eval**. A fixture of fact sequences that
contradict over time —

```
("diet", "vegetarian", t0)  →  ("diet", "eats meat", t0 + 90d)
```

— is replayed through the loop, asserting both that the system settles on the
correct belief and *how many turns it takes to get there*. That produces a
convergence curve, which is the measurable claim this whole design exists to
support, and the artifact that makes it comparable against a
last-write-wins baseline.

## 9. Staging

Three slices, each independently useful:

1. **Promotion + Beliefs screen.** The world model finally has a producer and
   contains data. No revision yet.
2. **Live probe + supersession.** Structural conflicts revise immediately.
   Mostly wiring — `active()`, `supersede()` and `history()` already exist.
3. **LLM adjudication + convergence eval.** Semantic conflict, plus the
   measurement that makes the claim falsifiable.

## 10. Out of scope

- Revising raw memories. This design revises *beliefs* only; `MemoryEntity`
  keeps its existing decay/dedup behaviour.
- Replacing the existing summary-level `detectContradictions()`. It keeps
  running and keeps writing summary-linked rows; belief conflicts are a new,
  separately-keyed use of the same table.
- Any change to the evolution proposal flow. `CREATE_BELIEF` /
  `UPDATE_BELIEF` / `RETIRE_BELIEF` keep working as they do today.
