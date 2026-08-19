# Value of information — ranking what Aura does not understand

**Date:** 2026-08-19
**Status:** design, phase 1 only
**Scope:** `com.aura.curiosity`, one screen, one migration

---

## Context

Aura already computes, from counted rows rather than vibes, an account of what it does not
understand about its user. `DriveSignals` (`consciousness/DriveSignals.kt:29-48`) reads three
real sources — knowledge-graph nodes with missing edges, unresolved contradictions between
things the user has said, and strategies the bandit has low confidence in — and
`QuestionScanner` turns them into `OpenQuestionEntity` rows already classified by kind
(`GAP`, `CONTRADICTION`, `STALE`, `SHALLOW`), already linked to their subject, and already
marked `ANSWERABLE_USER` or answerable by the world.

That is most of an uncertainty ledger, and it is unusual. Assistants that start each session
near-blank have no accumulated model to have gaps *in*.

Then it ends here:

```sql
-- OpenQuestionEntity.kt:150
SELECT * FROM open_questions WHERE status = 'open' ORDER BY createdAt ASC LIMIT 1
```

**The question Aura asks is whichever one is oldest.** A queue, not a judgement. Everything
downstream — the one question appended to the prompt at
`MemoryAugmentedAgenticLoop.kt:1046-1055`, the `SelfServeResearcher`'s daily budget, the
CURIOSITY drive that `IntrinsicMotivation` renders into the system prompt — is spent on
whatever happened to be noticed first.

Aura knows what it does not know. It has no idea which of those things matters.

## What this phase builds

Only the knowing half: **score each open question by how much resolving it would change what
Aura does, and order everything by that score instead of by age.**

Not in this phase, deliberately: the resolution planner, the silent resolver, watches, and
outcome scoring of resolutions. Those are phase 2, and they are worth building only if the
ranking here produces something a person reads and recognises as true. If it ranks noise, the
acting half has nothing worth acting on.

## Design

### 1. Arithmetic shortlist — free, deterministic, no key

`ValueOfInformation` (pure, `com.aura.curiosity`, no Android imports) scores every open
question from rows already present:

| Factor | Meaning | Source |
|---|---|---|
| **reach** | how much of the model touches this subject | KG edge count for `SUBJECT_KG_NODE`; memories and beliefs referenced for `SUBJECT_CONTRADICTION`; `accessCount` for `SUBJECT_MEMORY` |
| **liveness** | is the subject feeding anything right now | referenced by an `active` belief, a pending task, or a recall in the last 14 days |
| **recency** | when the subject was last touched | subject's `updatedAt` |
| **age** | how long the question has waited | `createdAt`, smallest weight |

`age` is deliberately the weakest term and deliberately still present: it makes today's FIFO
the tiebreak rather than the rule, so a low-reach question is not starved forever.

Output: the top `SHORTLIST = 10`. Pure function of its inputs, testable with no emulator and
no key, in the manner of `NotabilityScorer` and the living-world engine.

### 2. One model call a night to rank the shortlist

On the existing dream cycle (`DreamConsolidator.runCycle`, which already makes model calls
and already invokes `curiosityStore.scanAndAuthor()` at `:280`), a single call ranks the ten:

> Given these open questions and what each is about, rank them by how much knowing the answer
> would change the advice you would give. Return id, score 0-100, and one sentence saying why.

Arithmetic does what arithmetic is good at — counting what references what. The model does
the part arithmetic cannot: *consequence*. Reference count ranks what is talked about most,
which is reliably not what matters most.

**Bounded like everything else on that cycle:** one call, background model, no thinking
budget, the ten shortlisted questions only, and a token ceiling. A failed or skipped call
leaves `voiScore` at its default.

### 3. Selection becomes consequence-ordered

```sql
ORDER BY voiScore DESC, createdAt ASC LIMIT 1
```

**The fallback matters as much as the feature.** With no scores — first run, model call
failed, dream disabled — every `voiScore` is 0 and the ordering collapses to `createdAt ASC`,
which is exactly today's behaviour. The feature degrades to the thing it replaces rather than
to nothing.

### 4. Where it surfaces

`MindScreen` ("What Aura thinks") already lists open questions. Phase 1 changes what it shows
them as: ordered by score, each with the one-sentence reason it matters and the evidence
behind it. A person reading that list is the actual test of whether any of this works.

## Data

One migration, `MemoryDatabase` v29 → v30, two nullable columns on `open_questions`:

- `voiScore INTEGER NOT NULL DEFAULT 0`
- `voiReason TEXT` — the model's sentence, shown to the user, null until ranked

Defaults chosen so existing rows are valid and behave as they do today. Schema export,
`MigrationReplayTest` hop (which now compares indices), and an instrumented
`migrate29To30` — the seed row stating every NOT NULL column, per `migrate28To29`.

## Files

**New** — `curiosity/ValueOfInformation.kt` (pure scorer), `curiosity/VoiRanker.kt` (the
nightly call), plus tests for both.

**Modified** — `curiosity/OpenQuestionEntity.kt` (columns + the `current()` query),
`curiosity/CuriosityStore.kt` (expose ranked reads), `dream/DreamConsolidator.kt` (invoke the
ranker after `scanAndAuthor`), `memory/MemoryDatabase.kt` + `MemoryModule.kt` (v30),
`app/.../MindScreen.kt` (ranked rendering).

**Reused** — `QuestionScanner`, `QuestionAuthor`, `SelfServeResearcher`, `DriveSignals`,
`KnowledgeGraphDao`, `ContradictionDao`, `BeliefDao`, the dream cycle's model plumbing.

## Verification

**Unit, JVM, no key** — the scorer is pure, so all of it is testable:
- reach, liveness, recency and age each move the score in the stated direction
- a high-reach live subject outranks an old low-reach one, and the reverse when reach is equal
- **with no scores at all the order is identical to today's FIFO** — the degradation pin
- the ranker parses a well-formed model response, and drops a malformed one without
  disturbing existing scores
- the shortlist is capped at 10 regardless of how many questions are open

**Migration** — replay hop 29→30, and an instrumented `migrate29To30` with a complete seed row.

**The real test, which no test covers:** whether the top-ranked question, read by a person,
is one they recognise as worth answering. That needs weeks of real use and cannot be
simulated. If the ranking reads as arbitrary, phase 2 is not worth building and this design
is the cheapest possible way to find that out.

## Risks

**The ranking is subjective and unfalsifiable in phase 1.** There is no ground truth for
"which gap mattered" until phase 2 measures whether resolving one changed anything. Phase 1
is honest about being a judgement rendered legible, not a measurement.

**One nightly model call ranking ten items is a thin signal.** It will be noisy early. The
`voiReason` sentence is the mitigation: a reason a person can disagree with is falsifiable in
the only way that matters at this stage.

**A migration for two columns.** Justified because the score has to persist — it is computed
once nightly and read many times a day — and unjustifiable if this were computed on demand.

## Phase 2, for context only — not designed here

Resolution strategies in cost order: correlate what is already stored, set a watch on a
future event, then ask. Escalation only when the cheaper tier cannot answer. Scoring whether
a resolution held, feeding the existing outcome machinery. **Gated entirely on phase 1
producing a ranking worth acting on.**
