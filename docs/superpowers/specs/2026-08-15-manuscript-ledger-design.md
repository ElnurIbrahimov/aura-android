# The manuscript ledger — drafting that reads what it wrote

**Date:** 2026-08-15
**Status:** approved, single phase

## Context

`SceneContextBuilder`'s KDoc documents an eight-section context budget for one
scene. `LongformRunner` supplies six of them. The two it omits are the two that
carry any memory of the manuscript:

```kotlin
// SceneContextBuilder.kt:51-52
storySoFar: String = "",
retrieved: List<String> = emptyList(),
```

`LongformRunner.kt:196` is the only production caller and passes neither. The
only places either is ever non-empty are `SceneContextBuilderTest:83-84` and
`:102`, which fill them with `"y".repeat(50_000)` to prove the caps truncate.
The caps are tested; the content has never arrived. `section()` returns `""` for
an empty body, so the two headings do not even appear in the assembled prompt.

What scene twelve of a novel actually sees: craft guidance, the project header,
the beat-filtered world bible, a list of beat *titles*, and the last 2,000
characters of scene eleven. It has not read scenes one through ten.

Three things compound it.

- **The world bible never learns.** The writers to
  `CreativeProjectStore.updateWorld` are the user in the World tab, the outline
  planner (`CreativeStudioViewModel.kt:403`), and `LongformRunner.kt:263`, which
  writes a beat's status and nothing else. A scene that introduces a character,
  kills one, or establishes a rule updates nothing. The bible describes the
  plan; it never describes the book.
- **The canon tables have never held a row.** `canon_facts`,
  `continuity_issues`, `artifact_dependencies` and `creative_simulations` exist
  in `MemoryDatabase` with full DAOs, indices, foreign keys and backup mappers.
  Their only production consumers are `BackupManager`'s snapshot, restore and
  purge. Nothing inserts. The backup faithfully preserves four empty tables.
- **`canon_query` does not query canon.** `CanonQueryTool.kt:48` runs
  `memoryStore.query("$question project:$projectId")` against the user's
  *personal* memory store. `project:` is not a scope filter — it is literal text
  inside a BM25 query, so it contributes noise rather than scoping. The tool
  ships in every turn's tool schema and can only ever return nothing.

Continuity drift is the known failure mode of long-form generation, and every
mechanism for catching it is present here, tested in isolation, and connected to
nothing that runs. That is `ENGINEERING_HISTORY` §3's recurring finding,
appearing in the highest-value part of Creative Studio.

**Goal:** the drafting loop reads what it has already written, and records where
it disagrees with itself.

**Settled with the user:**

| Question | Answer |
|---|---|
| Which direction | A — wire the memory that already exists, before revision loops (B) or living-world coupling (C) |
| Producing the summary and the facts | A separate cheap-tier pass after each scene commits, not folded into the prose call |
| Conflicting facts | Flag and record. Never silently resolve, never pause the run |

## The idea

One new class, `SceneLedger`, in `com.aura.creative.longform`. No `Context`, not
a Worker, every decision drivable by a test with a mocked `Brain` — the
discipline `LongformRunner`'s own KDoc sets out, and the reason
`AgentRunExecutorWorker` has no test of its logic at all.

`LongformRunner` gains one dependency, not three. The orchestration — extract,
compare against the active facts, decide whether the difference is a conflict,
write three places — is a single act of reasoning. Split across three injected
components it would reassemble inside the runner, which is precisely what is
being avoided.

Three jobs:

- **`record(...)`** — after a scene commits, one cheap-tier call reads the scene
  and returns a two-sentence synopsis plus a list of canon triples. Writes the
  synopsis onto the beat, the facts to `canon_facts`, and any conflict to
  `continuity_issues`.
- **`storySoFar(...)`** — returns the stored synopses of the beats before the one
  being drafted, in chronological order, skipping any that are blank, and
  dropping from the oldest end when they exceed the budget (see **Cost** — the
  direction matters and the obvious `.take(cap)` gets it backwards). **No model
  call.** Each synopsis was written once, when the scene was fresh, and is never
  re-summarised. Rolling re-summarisation is where a story-so-far drifts: every
  pass compounds the previous pass's compression, and by scene twelve it is a
  summary of summaries with nothing left to check it against.
- **`retrieve(...)`** — lexical search over already-drafted scene revisions for
  the current beat's distinctive terms. **No model call, no embeddings.** One
  new `@Query` over `creative_revisions.contentText`. At novella scale — twelve
  to forty scenes on one branch — `LIKE` is the correct tool, and §3's Gate B
  already records that the embedding business case is unproven.

  Terms come from the beat's own text — title, summary, setting, pov — filtered
  through the shared `com.aura.core.util.StopWords`, the same list retrieval
  already uses, so "the" and "and" do not match every scene ever written. The
  **immediately preceding scene is excluded**: it is already supplied verbatim
  and in full as `previousSceneTail`, and letting it match here would spend the
  retrieval budget printing it a second time. Up to four passages, each capped
  at `RETRIEVED_ITEM_CAP`.

## No migrations

Worth stating plainly, because it is unusual for a change of this size.

`StoryBeat` is `@Serializable`, lives inside `CreativeProjectEntity.worldJson`,
and is decoded with `ignoreUnknownKeys = true` with a default on every field.
Its own KDoc records that extending it is a serialisation change rather than a
Room migration. Adding `synopsis: String = ""` costs nothing — existing projects
decode with the default, and a hand-authored outline still drafts.

`canon_facts` and `continuity_issues` already exist, already have DAOs, are
already in the committed schema exports, and are already mapped through backup.
This change fills tables; it does not create them.

So: no Room migration, no new schema export, no backup schema bump, and no
movement in any figure `check-version-docs.sh` derives.

## What the ledger writes

**The synopsis** goes on the beat, in `worldJson`, beside the `artifactId` that
already points at the scene it describes. The prompt asks for two sentences —
what changed, and what is now true that was not before — and the field is capped
at 400 characters on write, because a prompt asking for brevity is a request and
a cap is a guarantee. It is storage of a fact about the manuscript, so it
belongs with the manuscript's plan rather than in a table of its own.

**The facts** go to `canon_facts` as `(subjectType, subjectId, predicate,
valueJson)` with `sourceRevisionId` set to the revision the scene was committed
as, `confidence` from the extraction, and `status = "active"`. The schema was
designed for exactly this and has been waiting for a writer.

**The conflicts** go to `continuity_issues`. See below.

Extraction uses `StructuredJson.requestJson`, not a hand-rolled parser.
`StructuredJson`'s own KDoc names `LlmWriteGate`'s bare-object regex as the
broken one it was written to replace; writing a fourth parser here would be the
same mistake with a new name.

## Conflict rules

Only predicates on a fixed single-valued allowlist can conflict:

`location`, `age`, `alive`, `allegiance`, `occupation`, `rank`

Everything else — traits, allies, possessions, knowledge — is multi-valued and
accumulates without ever disagreeing. Conservative deliberately: the
pause-the-run option was rejected because early false positives are likely, and
the way a flag earns trust is for the first ten to be real. The allowlist is a
constant in `SceneLedger` and is meant to be tuned by reading real flags rather
than by argument.

A conflict writes a `ContinuityIssueEntity` with `category` derived from the
predicate, `severity = "warning"`, `artifactId` set to the scene that introduced
the disagreement, and `status = "open"`. `evidenceFactIdsJson` holds the two
**canon fact** ids — the superseded one and the new one — not artifact ids: the
field is named for what it holds, each fact already carries its own
`sourceRevisionId`, and that chain is what lets the card say which scene each
half came from without duplicating the link. The new fact goes `active`; the old
moves to `superseded` via `CanonFactDao.updateStatus`. Canon stays clean and
singular, and the disagreement is recorded beside it rather than inside it.

**Two types share the name, and conflating them is the trap.**
`ContinuityIssue` (`WorldBible.kt:110`) is the JSON one inside `worldJson`, read
by `CreativeEngine.buildNarrativeWorldContext` as "KNOWN CONTINUITY ISSUES" and
today only ever user-authored. `ContinuityIssueEntity` (`CanonEntities.kt:117`)
is the Room one — indexed, backed up, richer, never written. **We write the Room
one.** The JSON one stays for hand-written author notes. Anything else
guarantees a later pass mistakes one for the other.

## Which model runs the ledger

`modelRoleRouter.explicit(ModelRole.CREATIVE_CRITIC)`, then
`cheapModelResolver.resolve(fallback = sceneModel, exclude = sceneModel)`.

**Not `modelRoleRouter.resolve(CREATIVE_CRITIC)`**, which is the obvious call and
the wrong one. `resolve` falls through to the conversation default
(`ModelRoleRouter.kt:131-134`), so an unset Creative Critic row would silently
run every ledger call on the user's flagship model. That takes the cheapest part
of this design — an auxiliary call costing about one percent of the scene it
describes — and prices it like a third of that scene instead, on every scene of
every run, with nothing anywhere reporting it. `CheapModelResolver` exists for
precisely this failure and its KDoc says so in as many words.

The Creative Critic row already exists in Settings and is already backed up. No
new preference, no new setting, no new UI.

## Cost

Per scene, one extra call: roughly 1,900 tokens in (the scene text plus the
extraction prompt) and 400 out, on the cheap tier. Against a scene call of
roughly 4,500 in and 1,600 out on the drafting model, plus its 2,048 thinking
budget.

The two new context sections add to the scene call itself: `retrieve` up to
2,800 characters, and `storySoFar` growing with the book — roughly 3,000
characters by scene twelve and 8,000 by scene thirty. Call it 900 extra input
tokens early in a novella and 2,700 late in a novel. Current assembly runs
around 17,000 characters against a `MAX_CONTEXT_CHARS` ceiling of 48,000, so
even the worst case lands near 28,000 and the ceiling is never the binding
constraint.

`SUMMARY_CAP` rises from 1,500 to 8,000 characters. Every other cap is
unchanged, which is the point of having per-section budgets rather than one
overall truncation.

**`storySoFar` drops from the oldest end, not the newest.** `section()` applies
`.take(cap)`, which truncates the *tail* — so a book long enough to exceed the
budget would keep scene one and silently lose the scene it just wrote, which is
the opposite of what continuity needs. `storySoFar` therefore accumulates
backwards from the current beat until the budget is spent and then reverses into
chronological order, so the most recent scenes are the ones guaranteed to
survive. A book long enough to lose its early synopses still carries every beat
title in the `OUTLINE` spine, which is what that section is for.

Netting out: the ledger call is about one percent of a run, and the context
growth is the real cost — climbing toward a fifth of the drafting model's input
bill late in a long book. **Ten to twenty percent on a draft run**, most of it
spent on the scene call rather than the extraction, which is the right place for
it to land.

Wall clock: five to ten seconds per scene, sequential. The next scene needs this
scene's synopsis, so running the ledger concurrently with the following scene
would defeat its purpose. Roughly two minutes across a run measured in tens.

## Failure, and back-fill

`record` runs **after** the artifact commit and **outside** its `NonCancellable`
block. A scene that has been generated, streamed to the screen and paid for must
never be put at risk by a bookkeeping call; `LongformRunner.kt:236-247` records
that exact lesson from a real device failure.

The consequence is that a failed or unparseable extraction leaves a committed
scene with an empty synopsis. That is recoverable, and recovering it is part of
the design: at the top of each slice, before drafting anything, any beat marked
drafted whose synopsis is blank is back-filled from its stored revision. Capped
at three per slice, so a persistently failing extraction cannot consume the
drafting window it was meant to support.

This is also the migration path. Every scene drafted before this change — every
existing project — heals on its next run rather than staying permanently blind.

## Surface

One card in the Manuscript tab: the canon fact count, and open conflicts as a
short list — *"scene 5 put Mira in Varn; scene 9 puts her in Kesh"* — each
dismissible as intentional, through `ContinuityIssueDao.resolve`. Backed by
`observeOpen`, which is already a `Flow` and already exists.

No new route and no new tab. `CreativeProjectScreen` is already at eight tabs
and already had to move to a scrollable tab row to fit them; a ninth is a
different problem than this one, and adding a route would move the
`NAV_DESTINATIONS` and `SECONDARY_ROUTES` counts that `check-version-docs.sh`
gates.

## Fixed in passing

- **`canon_query` reads canon.** Repointed at `CanonFactDao.activeForBranch` and
  `forSubject` over the project's active branch, with its description corrected.
  It has never returned a canon fact in its life.
- **`StoryBeat.revisionId` gets a writer.** Declared at `WorldBible.kt:96` as
  "the revision of that artifact holding this beat's text", and written by
  nothing: `LongformRunner.kt:261` copies `artifactId` only, while
  `CreativeArtifactStore.create` returns a `CreativeArtifactEntity` that already
  carries `currentRevisionId`. A one-line change.
  `CanonFactEntity.sourceRevisionId` is the provenance field the entire canon
  store rests on, and it cannot be filled honestly without this.

## Not building

No revision loop — a critic that rewrites a scene is direction B, and it needs
this to exist first: a critic that cannot see the manuscript can only judge
prose, never consistency. No living-world coupling — direction C. No embeddings
or FTS for retrieval; `LIKE` until a real corpus argues otherwise, per Gate B.
No automatic resolution of a flagged conflict — the user decides, and the whole
value of the design is that the disagreement is visible rather than absorbed.

Not touching the one-shot `DRAFT` mode in the Write tab, whose "aim for
12,000–16,000 words. Do not stop early" instruction (`CreativeEngine.kt:165`) is
a wish rather than a mechanism. Removing it is a separate change and should be
argued on its own terms.

## Verification

1. `SceneContextBuilder` emits a `STORY SO FAR` and a `FROM THE MANUSCRIPT`
   section when supplied — the assertion that does not exist today, and the one
   that would have caught this.
2. `LongformRunner` passes a non-empty `storySoFar` when drafting scene two.
   This is the regression gate for the defect being fixed.
3. A mocked-`Brain` extraction writes the synopsis onto the beat and the facts
   to `canon_facts`, and writes nothing to `continuity_issues` when there is no
   conflict.
4. A changed **single-valued** predicate writes one `ContinuityIssueEntity`
   naming both scenes, marks the prior fact superseded, and leaves the new one
   active.
5. A changed **multi-valued** predicate writes no issue at all.
6. An extraction failure leaves the scene committed with a blank synopsis, and
   the next slice back-fills it — with the three-per-slice cap asserted, because
   an uncapped back-fill is the spend loop `MAX_SCENE_ATTEMPTS` already exists to
   prevent.
7. The ledger never resolves to the conversation default when Creative Critic is
   unset.
8. `storySoFar` over more synopses than the budget holds keeps the **most
   recent** and drops the oldest, and the surviving text is still in
   chronological order. A `.take(cap)` regression fails this and nothing else
   would notice it.
9. `retrieve` never returns the immediately preceding scene, which
   `previousSceneTail` already supplies in full.
10. `canon_query` returns a fact a scene wrote, and does not consult the personal
    memory store.
11. On device, through a real draft run: draft at least six scenes, confirm scene
    six's assembled prompt contains synopses of one through five, and confirm
    `canon_facts` is non-empty. `scripts/smoke.sh` is the non-destructive
    harness — `connectedAndroidTest` must not be pointed at the daily install,
    for the reason its own header records.
12. Full suite, `assembleRelease`, both lint tasks and all three gate scripts
    before it lands.
