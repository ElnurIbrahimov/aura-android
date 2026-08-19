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

### Correction, 2026-08-19, after reading the callers

An earlier draft of this document claimed Aura "asks whichever question is oldest," citing
`ORDER BY createdAt ASC LIMIT 1` at `OpenQuestionEntity.kt:150`. That query is real and that
reading was wrong. `CuriosityStore.scanAndAuthor:48` opens with:

```kotlin
if (dao.openCount() > 0) return 0
```

**Only one question is ever open.** `current()` orders a set of size one, and the DAO's own
KDoc says why — *"Singular by design. One open question at a time is the entire defence
against an assistant that plays twenty questions."* There is no queue and no FIFO problem.
The error was reading a query without reading its caller, and it is left recorded here
rather than quietly edited out.

### Where the decision actually is

Ranking happens earlier, at scan time, and it already exists:

```kotlin
// QuestionScanner.scan:75
.sortedByDescending { it.priority }

CONTRADICTION_BASE = 1.0f    priority = base × row.confidence
GAP_BASE           = 0.7f    priority = base × node.confidence
SHALLOW_BASE       = 0.6f    priority = base × node.confidence
STALE_BASE         = 0.5f    priority = base × memory.importance
```

A fixed weight per kind, times **a detector's confidence that it found something**.
Confidence answers *"am I sure this is a gap"* and never *"does this gap matter"*. A
high-confidence contradiction about a throwaway remark outranks a lower-confidence gap about
the project with months of work behind it.

And because there is exactly one slot, the scan-time choice is the **entire** decision.
Choosing badly spends the only question Aura gets to ask, and nothing better can be authored
until that one is answered or dismissed. Everything downstream — the question appended to the
prompt at `MemoryAugmentedAgenticLoop.kt:1046-1055`, the `SelfServeResearcher`'s daily
budget, the CURIOSITY drive `IntrinsicMotivation` renders into the system prompt — spends
itself on that single choice.

Aura knows what it does not know. It ranks that by how sure it is, not by what it would change.

## What this phase builds

Only the knowing half: **score each candidate subject by how much knowing would change what
Aura does, and choose the one question by that rather than by detector confidence.**

Not in this phase, deliberately: the resolution planner, the silent resolver, watches, and
outcome scoring of resolutions. Those are phase 2, and they are worth building only if the
ranking here produces something a person reads and recognises as true. If it ranks noise, the
acting half has nothing worth acting on.

## Design

### 1. Arithmetic scoring — free, deterministic, no key

`ValueOfInformation` (pure, `com.aura.curiosity`, no Android imports) scores each candidate
`QuestionScanner.Subject` from signals the scanner's existing DAOs already reach — it takes
pre-fetched values rather than DAOs, so it stays a pure function with no mocks in its tests.

| Factor | Meaning | Source |
|---|---|---|
| **kind** | the existing per-kind judgement | `Subject.priority`, kept unchanged — a wrong belief corrupts every future recall, a gap only limits one |
| **reach** | how much of the model touches this subject | `edgesForNode(id).size` for a KG node; `accessCount` for a memory; shared-id count for a contradiction |
| **recency** | when the subject was last touched | `accessedAt` / `createdAt` on the subject |

Reach saturates rather than growing without bound, so one enormous hub cannot swamp the
ranking. Pure function of its inputs, testable with no emulator and no key, in the manner of
`NotabilityScorer` and the living-world engine.

### 2. The consequence judgement rides the model call that already happens

`QuestionAuthor.author(subjects)` already sends up to eight numbered subjects to the
background model in **one** call and keeps the best it could phrase
(`CuriosityStore.kt:79-81`). That call is extended to return a reason per line, so no second
call is added — an earlier draft proposed one, which reading the author made unnecessary:

> Given these open questions and what each is about, rank them by how much knowing the answer
> would change the advice you would give. Return id, score 0-100, and one sentence saying why.

Arithmetic does what arithmetic is good at — counting what references what. The model does
the part arithmetic cannot: *consequence*. Reference count ranks what is talked about most,
which is reliably not what matters most.

**Bounded like everything else on that cycle:** one call, background model, no thinking
budget, the ten shortlisted questions only, and a token ceiling. A failed or skipped call
leaves `voiScore` at its default.

### 3. Selection becomes consequence-ordered at scan time

`QuestionScanner.scan` sorts by the new score instead of raw `priority`, and the author picks
by the returned judgement rather than by position among the phrasable.

**The fallback matters as much as the feature.** With no reach and no age difference the
score reduces to `priority`, which is exactly today's ordering. A failed model call, a
missing reason, an empty graph — each degrades to the behaviour being replaced rather than to
nothing.

### 4. Where it surfaces

`MindScreen` ("What Aura thinks") already renders `openQuestionsSection` (`:105`, `:290`).
Phase 1 shows the open question with the reason it was chosen, and beneath it the ranked
candidates that lost — computed live from `scanner.scan()`, which is pure DB reads and needs
no storage. That list is the ledger; the single question is its tip. A person reading it is
the actual test of whether any of this works.

## Data

One migration, `MemoryDatabase` v29 → v30, two nullable columns on `open_questions`:

- `voiScore INTEGER NOT NULL DEFAULT 0`
- `voiReason TEXT` — the model's sentence, shown to the user, null until ranked

Defaults chosen so existing rows are valid and behave as they do today. Schema export,
`MigrationReplayTest` hop (which now compares indices), and an instrumented
`migrate29To30` — the seed row stating every NOT NULL column, per `migrate28To29`.

## Files

**New** — `curiosity/ValueOfInformation.kt` (pure scorer) and its test.

**Modified** — `curiosity/QuestionScanner.kt` (rank via the scorer), `curiosity/QuestionAuthor.kt`
(a reason per line), `curiosity/CuriosityStore.kt` (carry score and reason onto the row),
`curiosity/OpenQuestionEntity.kt` (two columns), `memory/MemoryDatabase.kt` +
`memory/MemoryModule.kt` (v30 and its migration), `app/.../MindScreen.kt` and `MindViewModel`
(reason plus ranked candidates).

**Reused** — `QuestionScanner`, `QuestionAuthor`, `SelfServeResearcher`, `DriveSignals`,
`KnowledgeGraphDao`, `ContradictionDao`, `BeliefDao`, the dream cycle's model plumbing.

## Verification

**Unit, JVM, no key** — the scorer is pure, so all of it is testable:
- kind, reach and recency each move the score in the stated direction
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
