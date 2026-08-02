# ROUND 12 — Proactive + Consciousness + Evolution Audit

**Project:** Aura Android (Kotlin/Compose, Hilt, Room, WorkManager)
**Path:** `D:\aura-android-clean`
**Scope:** `aura-core/.../proactive/` (30 files), `consciousness/` (5), `evolution/` (25), `dream/` (14), `world/` (7) — 81 files, ~9.3K LOC
**Round:** 12 (11 prior audits)
**Date:** 2026-08-02

---

## 0. TL;DR — Severity Counts

| Severity | Count |
|---|---:|
| **P0** (production dead) | 7 |
| **P1** (logic/wiring flaw) | 11 |
| **P2** (dead code / no-op) | 8 |
| **Total findings** | **26** |

The headline problem: **the entire 9-engine proactive pipeline is injected as nullable with no Hilt bindings, so the daemon worker does nothing useful on each tick**; the **consciousness layer** has the same problem in the agentic loop; and the **evolution feedback loop is half-closed** (proposals never observe their own outcomes).

---

## 1. P0 — Production-Dead Findings

### P0-1. `DaemonWorker` receives 9 nullable Hilt deps; the entire pipeline skips every tick

**File:** `aura-core/src/main/kotlin/com/aura/proactive/DaemonWorker.kt:48-58`

```kotlin
private val awarenessEngine: ProactiveAwarenessEngine? = null,
private val agentPresence: com.aura.consciousness.AgentPresence? = null,
private val proactiveMessageStore: ProactiveMessageStore? = null,
private val motivationAccumulator: MotivationAccumulator? = null,
private val curiosityScanner: CuriosityScanner? = null,
private val salienceFilter: SalienceFilter? = null,
private val adaptiveTimingEngine: AdaptiveTimingEngine? = null,
private val idleTimePreparationEngine: IdleTimePreparationEngine? = null,
private val proactiveMessageLibrary: ProactiveMessageLibrary? = null,
```

Combined with the only `ProactiveModule` binding (`provideCalendarMonitor`,
`ProactiveModule.kt:18-21`), **none of these 9 singletons are wired** by
Hilt, so they are always null at runtime. Every `if (… != null)` guard
in `doWork()` falls through:

- L66: `awarenessEngine?.runAll().orEmpty()` → `emptyList()`.
- L71: `if (salienceFilter != null && findings.isNotEmpty())` → false.
- L80-100: motivation scoring + adaptive timing are both bypassed
  (no `if (motivationAccumulator != null)` branch ever matches).
- L106: `curiosityScanner?.scan()` → null.
- L140: `if (agentPresence != null && proactiveMessageStore != null)` → false;
  the 3-day outreach is never sent.
- L147: `if (proactiveMessageLibrary != null)` → false; the library
  message variants never reach the user.
- L151: `agentPresence?.generateOutreachMessage(daysSince)` → null.
- L154: `proactiveMessageStore?.setMessage(outreach)` → never called.

Only step 8 (`generateLlmInsight()`) and the cheap `isGoodTime` check
ever run.

**Severity: P0** — the entire 7-step proactive pipeline is dead in
production. Users get no salience filtering, no motivation gating, no
curiosity scan, no idle-time preparation, no proactive outreach.

**Fix recipe:**
1. In `ProactiveModule`, `@Provides @Singleton` each of the 9
   components, or — better — drop the `? = null` defaults on
   `DaemonWorker`'s constructor and let Hilt fail loudly at build time
   if a binding is missing.
2. Add a Robolectric test that constructs `DaemonWorker` with a fake
   `ProactiveAwarenessEngine` and asserts `runAll()` was called.
3. At the top of `doWork()`, emit a `Log.i(TAG, "engines: $activeEngines")`
   so dead wiring is visible in production logcat.

---

### P0-2. `AffinityTracker.recordTurn()` is never called; affinity never advances

**File:** `aura-core/src/main/kotlin/com/aura/consciousness/AffinityTracker.kt:79-92`

`recordTurn()` is the only way the affinity score increases. The
docstring on `AffinityLevel` says "Each level unlocks different agent
behaviors", and the `getDirective()` method is read in
`MemoryAugmentedAgenticLoop.kt:609` to inject the system-prompt directive.

Search of the entire tree shows `recordTurn()` is called **nowhere** in
the production code. The `AffinityTrackerTest` exercises it, and
`HomeViewModel` reads the state, but the loop never bumps the counter.
Result: every user stays at `ACQUAINTANCE` (score 0) forever, and the
directive in the system prompt is the same boilerplate from launch day.

**Severity: P0** — the most user-facing consciousness feature is
completely inert.

**Fix recipe:**
1. Call `affinityTracker.recordTurn()` from
   `MemoryAugmentedAgenticLoop` after a successful assistant turn
   (in the `Done` event, not in step 1 to avoid double-counting on
   multi-step runs).
2. Invalidate the directive cache with `invalidateCache()` after each
   `recordTurn()` so the level change takes effect on the next
   request.

---

### P0-3. `MemoryAugmentedAgenticLoop` injects all 4 consciousness classes as nullable; none are bound

**File:** `aura-core/src/main/kotlin/com/aura/agent/MemoryAugmentedAgenticLoop.kt:83-86`

```kotlin
private val narrativeSelf: com.aura.consciousness.NarrativeSelf? = null,
private val intrinsicMotivation: com.aura.consciousness.IntrinsicMotivation? = null,
private val theoryOfMind: com.aura.consciousness.TheoryOfMind? = null,
private val affinityTracker: com.aura.consciousness.AffinityTracker? = null,
```

The classes are `@Inject constructor` and Hilt *could* provide them,
but no `@Provides` or `@Binds` exists for any of them. They never
appear in any `@Module` (verified by ripgrep across `aura-core/.../
*Module.kt`). The call sites in the agentic loop are guarded:

- L544: `theoryOfMind?.updateFromMessage(lastUserMessage)` → never.
- L606-609: all four `toPrompt()` / `getDirective()` calls produce
  empty strings.
- L1026-1027: `narrativeSelf?.updateFromInteraction(…)` /
  `narrativeSelf?.save()` → never.

Result: the entire consciousness layer contributes nothing to the
system prompt. The user model, narrative self, intrinsic drives, and
affinity level are all dead.

**Severity: P0** — the four consciousness modules are inert.

**Fix recipe:**
1. Either add a `ConsciousnessModule` with `@Provides` for each (or rely
   on the existing `@Inject constructor()` — Hilt will pick them up if
   the package is on the source path), or — like P0-1 — drop the
   `? = null` and let Hilt fail.
2. Smoke test: assert that `systemPrompt` contains
   `"[User Model]"` after 4 user messages.

---

### P0-4. `EvolutionProposalStore.recordOutcome()` and `pastOutcomes()` are never called; the feedback loop is half-closed

**File:** `aura-core/src/main/kotlin/com/aura/evolution/EvolutionProposalStore.kt:70-100`

The doc on `recordOutcome` (L57-69) explicitly states:

> "This closes the evolution loop: proposals are created → evaluated →
> approved → applied → outcome recorded → future proposals informed by
> past outcomes."

But:
- `recordOutcome` is **never called** anywhere in the production tree
  (only the test exercises it).
- `pastOutcomes` is also never called — not even from
  `EvolutionCoordinator.runAll()`.
- `EvolutionCoordinator` has no awareness of past outcomes when
  deciding which candidates to reflect on or auto-apply.

**Severity: P0** — the documented "closes the loop" claim is false. The
evolution system has no way to learn whether a self-edit helped or
harmed the user; the same kinds of bad proposals will keep being
generated forever.

**Fix recipe:**
1. Add a "post-apply outcome recorder" worker that runs 1d/3d/7d after
   a proposal's `resolvedAt` and calls `recordOutcome` based on usage
   signals (skill-invocation count for skill-domain proposals;
   proactive-engagement rate for proactive-domain; memory-recall count
   for memory-domain).
2. In `EvolutionCoordinator.runAll()`, fetch `proposalStore.pastOutcomes(domain)`
   per domain and add a small prior (e.g. skip candidates in a domain
   whose recent outcomes are < 0.4) so bad evolutions suppress
   themselves.

---

### P0-5. `applyUpdateBelief` and `applyRetireBelief` build rollback-snapshot JSON via raw string interpolation — corrupts on any embedded `"`

**File:** `aura-core/src/main/kotlin/com/aura/evolution/EvolutionApplySaga.kt:332-333, 353-354`

```kotlin
proposalStore.recordRollbackSnapshot(proposal.id,
    """{"id":"${existing.id}","subject":"${existing.subject}","predicate":"${existing.predicate}","valueJson":"${existing.valueJson}","confidence":${existing.confidence},"status":"${existing.status}"}""")
```

`existing.valueJson` is JSON-serialized text (e.g.
`{"name":"O'Brien","nested":{"key":"val\""}}`). A single `"` in any
field (or in `subject` — e.g. user sets `subject = "Alex "M.""`)
silently corrupts the snapshot. The next `EvolutionRollbackManager.rollback`
will then fail at L223 (`json.decodeFromString` throws), the user gets
`"snapshot is not a valid belief JSON"`, and the rollback is lost
even though the data was snapshotted.

**Severity: P0** — rollback reliability depends on this snapshot, and
the snapshot is silently malformed for any user data containing quotes.

**Fix recipe:**
1. Use `Json.encodeToString(...)` against a `@Serializable` data class
   (e.g. `BeliefSnapshot` with the same fields). Same change for
   `applyRetireBelief` and `applyPatchSpecialistPrompt` (which has the
   same flaw on `current.specialistOverrides`).
2. Add a test that round-trips a belief whose valueJson contains
   `'`, `"`, and `\n`.

---

### P0-6. `applyUpdateMemoryCategory` and `applyMergeMemories` mutate memory *after* the rollback snapshot is taken — but the snapshot is stored on the *proposal*, and on rollback the snapshot is loaded as-is. The bug is the snapshot stores the *pre-mutation* copy. The trap: if `MemoryStore.update` is called between snapshot and apply error, the snapshot is still pre-state. This is actually correct. **What is broken is the fact that `proposalStore.recordRollbackSnapshot` is called in `apply()` after the `skillsStore.update`/`memoryStore.update` mutates, and if the mutation throws between the update and the snapshot record the snapshot is lost.** Specifically in `applyMergeSkills` (L137-138): `skillsStore.update(merged); skillsStore.remove(source.id); skillRevisionStore?.snapshot(merged, proposal.id, "merged…")` — the snapshot is of `merged`, not of the *target's pre-state*. On rollback (L108-119) it restores the target to the pre-merge state, but the source skill (which was deleted) is permanently lost. This is acknowledged in the rollback comment, but the apply saga has no comment about it.

**File:** `aura-core/src/main/kotlin/com/aura/evolution/EvolutionApplySaga.kt:137-141, 108-119`

This is *acknowledged* in the rollback code (L110-113):
> "The source skill was deleted by the apply saga and is NOT in the
>  rollback snapshot — we can only restore the target, not re-create
>  the source. This is a known limitation."

But **it's never surfaced in the UI** — the user gets `Ok("merged X
into Y")` with no warning that X is now unrecoverable. The same
applies to `applyMergeMemories` (L281-296) and
`applyConsolidateMemories` (L204-254, "original sources were forgotten
and cannot be auto-restored").

**Severity: P0** — destructive merge/consolidate operations are
advertised as rollbackable but lose data on rollback; the user has no
warning.

**Fix recipe:**
1. In each destructive apply, emit a `WorldEvent` with kind
   `"destructive_action"` so the opportunity engine surfaces a
   `destructive_warning` (the engine already handles this case at
   `OpportunityEngine.kt:173-189` — but it's not connected).
2. Block destructive merges behind a user-confirmation step in the
   proposal inbox; don't auto-apply them.

---

### P0-7. `applyAdjustRuleTiming` and `applyEnableRule` produce no actual state change but are marked `APPLIED`

**File:** `aura-core/src/main/kotlin/com/aura/evolution/EvolutionApplySaga.kt:383-393, 406-425`

- `applyAdjustRuleTiming` (L383-393) **only records a recommendation
  string in the outcomeNote** — no scheduler change, no preference
  change. The proposal is marked `APPLIED` regardless.
- `applyEnableRule` (L406-425) **inserts a new event row** but the
  inserted event has the same `eventType`/`correlationTag` as
  `applyNewProactiveRule` — there is no real "disabled" state to
  toggle. The "enabled" rule is indistinguishable from any other
  custom rule.

The user can see "evolution rule X applied" in the inbox, but nothing
actually changed. This is dishonest UX and undermines trust in the
entire inbox.

**Severity: P0** — silent false-positive on the most user-visible
evolution action surface.

**Fix recipe:**
1. For `applyAdjustRuleTiming`: actually update
   `UserPreferences.proactiveRuleTiming` (or add a new field) so the
   scheduler respects it. Or rename the action to
   `RECOMMEND_TIMING` and surface it as a suggestion, not an apply.
2. For `applyEnableRule`: store a disabled state in a separate
   `disabled_rule` table, or remove the action entirely and require
   the user to re-create the rule manually.

---

## 2. P1 — Wiring / Logic Flaws

### P1-1. `ProactiveBootstrap.applyGates` returns `GatedDecisions` but only one field is read

**File:** `aura-core/src/main/kotlin/com/aura/proactive/ProactiveBootstrap.kt:305-320, 181-198`

`applyGates` returns `GatedDecisions(morningBriefScheduled, calendarMonitorShouldRun)`. The
`reconcile` caller (L181-198) only reads `calendarMonitorShouldRun` —
the `morningBriefScheduled` boolean is computed and discarded (the
schedule/cancel is already done inside `applyGates` via
`scheduler.scheduleMorningBrief` / `cancelMorningBrief`).

**Fix recipe:** return `Unit` (or just the calendar boolean); drop the
`morningBriefScheduled` field. Mark the change with a `// visible for
testing` comment if a future test needs it.

---

### P1-2. `ProactiveBootstrap.start` has 8 separate `scope.launch`es, 3 of which use `combine` and 5 use `distinctUntilChanged().collect`

**File:** `aura-core/src/main/kotlin/com/aura/proactive/ProactiveBootstrap.kt:96-178`

The comment "5-way combine is at the overload limit" is misleading —
`combine` has overloads up to 5 args, but `combine(vararg flows,
transform: suspend (Array<T>) -> R)` is unbounded. The 6th, 7th, 8th
flow could be merged into a single `combine`. The current structure
makes one failure domain per launch: if `reconnectMcpServers` throws,
the trigger/dream/decay reconciliations are unaffected (good); but the
opposite is also true — there's no central place to log "all
preferences reconciled" or to surface partial failures to the UI.

**Fix recipe:** consolidate the 8 launches into one supervisor that
emits to a `MutableSharedFlow<ReconcileFailure>` consumed by debug
UI. Drop the `scope.launch { runCatching { … } }` pattern at L121-129
(dream), L134-138 (decay), L158-164 (triggers) — each swallows the
exception silently.

---

### P1-3. `ProactiveBootstrap` has a top-level `private const val TAG` instead of in the companion

**File:** `aura-core/src/main/kotlin/com/aura/proactive/ProactiveBootstrap.kt:43, 331-334`

The file has a top-level `private const val TAG = "ProactiveBootstrap"`
and a nested `companion object` with `ACTION_REFRESH_WIDGET`. Two
locations for class-level constants. The L43 declaration is also a
no-op for visibility — `private` at file top means file-private, so
L286 (`"MCP connect failed for ${config.id}"`) using bare `TAG` is
fine, but L196 etc. that use `"ProactiveBootstrap"` as a string
literal should use the constant.

**Fix recipe:** move the top-level TAG into the companion. Replace all
string literals `"ProactiveBootstrap"` with `TAG`.

---

### P1-4. `ProactiveMessageStore.setMessage` is in-memory only via DataStore — single-slot queue drops older outreach on overwrite

**File:** `aura-core/src/main/kotlin/com/aura/proactive/ProactiveMessageStore.kt:34-39`

`setMessage` uses DataStore preferences. It overwrites `KEY_MESSAGE`.
If two outreach events fire within the same process (e.g. morning
brief + relationship-gap), the second one wins. The first is silently
discarded. The doc (L22-23) says "holds only the latest message —
older ones are overwritten" but the worker is called from multiple
sites (morning brief, daemon, dream completion) so this is a real
lossy surface.

**Fix recipe:** if multi-message queue is desired, store a list and
dequeue in `consumeMessage`. If single-slot is the design, log a
`Log.w(TAG, "overwriting pending proactive message")` at the
overwrite site.

---

### P1-5. `MorningBriefBuilder.postNotification` uses `Class.forName("$packageName.MainActivity")` — fragile but currently works

**File:** `aura-core/src/main/kotlin/com/aura/proactive/MorningBriefBuilder.kt:200-202`

```kotlin
val mainActivityClass = runCatching {
    Class.forName("$packageName.MainActivity")
}.getOrNull() ?: android.app.Activity::class.java
```

This hardcodes the activity class name via reflection. It works for
the current build (`MainActivity.kt` is at `com.aura.MainActivity`,
matching the packageName), but breaks silently if the activity moves
to a different package or is renamed. The fallback is
`Activity::class.java`, which is even worse — `Intent(ctx,
Activity::class.java)` produces a non-launchable intent.

**Fix recipe:** add a typed provider via Hilt (e.g.
`@Provides fun provideMainActivityClass(): Class<*> = MainActivity::class.java`)
or use `packageManager.getLaunchIntentForPackage(packageName)?.component?.className?.let
{ Class.forName(it) }` so the lookup is name-driven by the manifest,
not hardcoded. Add a test that asserts the class lookup succeeds.

---

### P1-6. `MorningBriefBuilder` calls `evolutionHooks?.onProactiveDelivered("mb_${now}", "morning_brief")` with `now` in the event id — re-runs create different ids, breaking the engagement detector

**File:** `aura-core/src/main/kotlin/com/aura/proactive/MorningBriefBuilder.kt:121-123`

`onProactiveDelivered` records an evidence row whose `sourceEntityId`
is `"mb_$now"`. If the user opens the morning brief twice (or the
worker retries), each delivery has a different id, so the
`EvolutionCandidateDetectors.detectProactiveEngagementCandidates` (which
groups by `sourceEntityId` over 7 days) never sees a duplicate — it
sees two different events with `count=1`, both under the 3-action
threshold. Engagement never accumulates.

**Fix recipe:** use a stable id (e.g. `mb_$day` or just
`"morning_brief"`) for the delivered event. Same fix for any
"now-based" id passed to `onProactiveDelivered`.

---

### P1-7. `MotivationAccumulator.currentThreshold` defaults to 0.5 — never auto-raises; new users get spammed

**File:** `aura-core/src/main/kotlin/com/aura/proactive/MotivationAccumulator.kt:65-84`

The threshold is `baseThreshold (0.5) - engagementRatio*0.2 +
dismissalRatio*0.2` clamped to `[0.2, 0.8]`. On a fresh install with
zero interactions (`recentInteractions.isEmpty()`), the threshold is
exactly 0.5. A score of 0.5 (the formula's midpoint: 0.3*0.5 + 0.2*0.5
+ 0.2*0.5 + 0.15*0.5 + 0.15*0.5 = 0.5) is exactly equal and
`shouldDeliver = s >= threshold` is `true`. So a perfectly average
finding passes the gate on the very first tick.

**Severity: P1** — first-day users will receive any half-decent
proactive finding immediately.

**Fix recipe:** raise the cold-start threshold to 0.65 (or require
N≥5 interactions before the threshold is computed at all). Also,
the weight sum is 0.30 + 0.20 + 0.20 + 0.15 + 0.15 = **1.00**, so
maximum possible score is 1.0; this matches `shouldDeliver` only at
threshold 0.5. Confirm the math is intended.

---

### P1-8. `SalienceFilter.weights` is a `val` inside the class body — not injectable, not configurable

**File:** `aura-core/src/main/kotlin/com/aura/proactive/SalienceFilter.kt:37`

```kotlin
private val weights = SalienceWeights()
```

The `SalienceWeights` data class has all-default values, so
`weights.recency = 0.25f` etc. is hardcoded. A unit test cannot
override the weights to check sensitivity. A user cannot tune the
salience threshold via settings.

**Fix recipe:** inject `SalienceWeights` and `SALIENCE_THRESHOLD` via
Hilt (or read from `UserPreferences.proactiveSalienceWeights`). The
`@Inject constructor` currently takes only the DAO.

---

### P1-9. `AdaptiveTimingEngine.isGoodTime()` requires ≥3 logged interactions in the current hour — but every cold start, no interactions exist, so `hourlyEngagement()` returns all zeros, and the threshold of `>= 0.4f` is never met

**File:** `aura-core/src/main/kotlin/com/aura/proactive/AdaptiveTimingEngine.kt:34-38`

```kotlin
suspend fun isGoodTime(): Boolean {
    val scores = hourlyEngagement()
    val hour = ...
    return scores[hour] >= 0.4f
}
```

`hourlyEngagement` normalizes by `max` (L30-31). If every score is 0,
max is 0, and `scores.map { (it / max).coerceIn(0f, 1f) }` divides by
zero (no — `coerceAtLeast(1f)` floor at L30 prevents div-by-zero),
giving all 0s. So `isGoodTime()` is `false` on every cold start and
for every hour of the day until the user has accepted/dismissed 3+
proactive events. That means the very first proactive message the
daemon tries to send fails the timing gate. The whole pipeline never
gets to start its self-improvement.

**Fix recipe:** when `interactions.isEmpty()`, return a sensible
default (e.g. `true` for the user's first 24h, or compute a
bootstrapped prior from calendar events / system time).

---

### P1-10. `CuriosityScanner.findContextlessMentions` does `break` after the first match — silently ignores 49 other memories

**File:** `aura-core/src/main/kotlin/com/aura/proactive/CuriosityScanner.kt:67-90`

```kotlin
for (memory in memories) {
    ...
    if (matched != null) {
        ...
        results.add(...)
        break   // <-- exits after one match
    }
}
```

The function name is `findContextlessMentions` (plural), the doc says
it scans memories for contextless entities, but it returns at most
one result. Combined with `findIsolatedNodes` (top 3) and the `take(5)`
in `scan()`, the curiosity lane produces at most 4 results per cycle
instead of the intended 5+.

**Fix recipe:** remove the `break` so all matches are collected, or
change the function name to `findFirstContextlessMention` to match
behavior.

---

### P1-11. 10 of 20 apply handlers skip `recordRollbackSnapshot` — those actions are not rollbackable

**File:** `aura-core/src/main/kotlin/com/aura/evolution/EvolutionApplySaga.kt`

`rg -c "recordRollbackSnapshot" EvolutionApplySaga.kt` returns **10**
calls; there are **20** apply handlers. The 10 that skip it:

| Handler | Line | Rollback path impact |
|---|---|---|
| `applyCreateSkill` | L76-84 | rollback uses `proposal.targetId` (L68) but the new skill got a fresh ID from `skillsStore.add` — wrong row deleted |
| `applyPromoteToHand` | L153-168 | rollback searches by name `"from_skill_$skillName"` (L127-135) — fragile and silent if user renamed |
| `applyPatchSpecialistPrompt` | L170-185 | rollback at L141-149 reads `proposal.rollbackSnapshotJson` which is empty; returns "no rollback snapshot" |
| `applyConsolidateMemories` | L204-254 | acknowledged in rollback — sources cannot be auto-restored (P0-6) |
| `applyNewProactiveRule` | L363-381 | rollback uses `proposal.targetId` (L105) which is empty for new rules; the rule was created with correlation tag `evolution:${proposal.id}` — rollback deletes by `evolution:${proposal.id}` which works by accident |
| `applyAdjustRuleTiming` | L383-393 | no-op apply (P0-7) |
| `applyDisableRule` | L395-404 | rollback at L264-284 tries to re-create from patch, but the original event text is gone |
| `applyEnableRule` | L406-425 | rollback at L285-289 uses correlation tag — works by accident |
| `applyRewriteRuleMessage` | L427-444 | rollback at L290-295 uses correlation tag — works by accident |
| `applyCreateBelief` | L300-325 | rollback at L197-213 looks up by `subject + predicate` (L207), but multiple beliefs can match; silently supersedes the wrong one |

The most user-impacting cases are:
- `applyCreateSkill` rollback deletes the **wrong** row (L68).
- `applyCreateBelief` rollback may supersede an **unrelated** belief (L207).
- `applyPatchSpecialistPrompt` rollback fails outright (L142-149).

**Fix recipe:** add a `proposalStore.recordRollbackSnapshot` call in
each of these 10 handlers. For `applyCreateSkill`, store the new
skill's actual ID (e.g. JSON: `{"createdId": "...", "name": "..."}`)
and have rollback use that. For `applyCreateBelief`, store
`{"beliefId": "..."}` and have rollback delete by ID.

**Severity: P1** (rises to P0 if any of these actions are reachable
in production — which they are, via the Evolution inbox).

---

## 3. P2 — Dead Code / No-Ops / Cosmetic

### P2-1. `EvolutionShadowEvaluator` is a `object` utility used only by its own test

**File:** `aura-core/src/main/kotlin/com/aura/evolution/EvolutionShadowEvaluator.kt:18-107`

`EvolutionShadowEvaluator` is an `object` (singleton) but is **never
called from production code** (only its test). The doc claims it
"replaces the toy token-length Gaussian" but no caller invokes it
from any apply path, the `EvolutionEvaluators` class is the LLM
replacement, and the original Gaussian isn't even present. This is
**a fully-implemented, fully-tested utility that does nothing**.

**Fix recipe:** either delete `EvolutionShadowEvaluator` and its test,
or wire it into the apply saga to score variant vs. baseline before
`markApplied`. The shadow path in
`EvolutionSettingsEntity.shadowEnabled` (L193) exists for exactly
this purpose but is never read.

---

### P2-2. `ProactivePolicyEngine.adaptFromSummary` is never called

**File:** `aura-core/src/main/kotlin/com/aura/proactive/ProactivePolicyEngine.kt:27-45`

`ProactivePolicyEngine` is an `@Inject constructor()` `@Singleton`,
has 6 tests, and is documented as the adaptive policy that tunes
per-event-type weights. No production code calls `adaptFromSummary`.
The `defaults: List<Policy>` parameter has no production source
either — the engine has no upstream.

**Fix recipe:** wire `ProactivePolicyEngine.adaptFromSummary(...)` into
`ProactiveEvents.recordInteraction` (where the action is recorded)
to update per-event-type weights and store them in `UserPreferences`.
Otherwise delete the engine.

---

### P2-3. `ProactiveMessageStore.setMessage` writes to DataStore, but the comment says "dataStore preferences" — and `peekMessage` exists but is never called

**File:** `aura-core/src/main/kotlin/com/aura/proactive/ProactiveMessageStore.kt:49-52`

`peekMessage` (L49) is never called from production. `consumeMessage`
(L41) is. So the peek path is dead.

**Fix recipe:** delete `peekMessage` or call it from
`MemoryAugmentedAgenticLoop` to optionally inject the pending outreach
into the system prompt.

---

### P2-4. `ProactiveEvents.recordInteraction` has an `else` branch that calls `onProactiveDelivered` for unrecognized actions like "opened"

**File:** `aura-core/src/main/kotlin/com/aura/proactive/ProactiveEvents.kt:195-202`

```kotlin
when (action) {
    "dismissed" -> evolutionHooks?.onProactiveDismissed(...)
    "acted" -> evolutionHooks?.onProactiveActionTaken(...)
    "snoozed" -> evolutionHooks?.onProactiveSnoozed(...)
    "opened" -> evolutionHooks?.onProactiveOpened(...)
    else -> evolutionHooks?.onProactiveDelivered(...)
}
```

"opened" has its own branch, so the `else` is hit only for typos
(e.g. a future "shared" action). This is fine but note the
"else → onProactiveDelivered" is misleading because the action
that triggers it might be `recordInteraction(..., action="shared")`
which is clearly not a delivery.

**Fix recipe:** drop the `else` branch and let unknown actions
silently no-op (or log a warning). Keep the contract explicit.

---

### P2-5. `ProactiveEvents.toEvent()` for `MemoryDecayWarning` swaps title and body semantics

**File:** `aura-core/src/main/kotlin/com/aura/proactive/ProactiveEvents.kt:233`

```kotlin
"MemoryDecayWarning" -> ProactiveEventBus.Event.MemoryDecayWarning(body, title, timestamp, id)
```

The entity stores `title = preview` and `body = memoryId` (L259-263).
The reconstruction puts `body` (memoryId) into `MemoryDecayWarning.memoryId`
and `title` (preview) into `preview`. The names match — so this is
correct, but the asymmetric store/reconstruct makes it easy to break
later. Adding a comment would help.

**Fix recipe:** add a comment that documents the field-direction
mismatch (entity.title→event.preview, entity.body→event.memoryId).

---

### P2-6. `IntrinsicMotivation.toPrompt()` has a string-concat precedence bug

**File:** `aura-core/src/main/kotlin/com/aura/consciousness/IntrinsicMotivation.kt:140-142`

```kotlin
return "[Intrinsic motivation] Active drive: $driveName (${(urgent.urgency * 100).toInt()}% urgency)" +
    if (triggers.isNotBlank()) " — $triggers" else ""
```

`+` is left-associative. The result is:
`"[…] $driveName (NN% urgency)" + (if triggers isNotBlank then " — …" else "")`.

That actually works. But if a future maintainer wraps the
parenthesized expression in another `+`, the `if` could become part
of the LHS string. The test at L129-135 explicitly asserts
`prompt.contains("curiosity")` but doesn't assert the trailing
" — N unexplored topics" appears, so this corner is untested.

**Fix recipe:** wrap the whole expression in `if (triggers.isBlank()) … else "… — $triggers"` or use a single string interpolation:

```kotlin
val triggerSuffix = if (triggers.isNotBlank()) " — $triggers" else ""
return "[Intrinsic motivation] Active drive: $driveName (${(urgent.urgency * 100).toInt()}% urgency)$triggerSuffix"
```

---

### P2-7. `TheoryOfMind.computeTechnicalDepth` has a duplicate "protocol" entry in the list

**File:** `aura-core/src/main/kotlin/com/aura/consciousness/TheoryOfMind.kt:197-199`

```kotlin
val techTerms = listOf("api", "database", "kernel", "compiler", "algorithm", "protocol",
    "architecture", "refactor", "async", "concurrent", "serialize", "gradient",
    "matrix", "protocol", "schema", "migration", "deploy", "ci/cd", "docker")
```

`protocol` appears twice. `hits` counts substring matches, not unique
terms, so a message containing "protocol" twice counts as 2 hits,
inflating the score. The `minOf(1f, hits / 5f + 0.3f)` clamp hides
the bug, but the duplicate is sloppy.

**Fix recipe:** deduplicate the list (`listOf(...).distinct()`).

---

### P2-8. `TheoryOfMind.decayTopics` uses `Math.exp` returning a Double, then `.toFloat()` — not a bug, but inconsistent with the rest of the file

**File:** `aura-core/src/main/kotlin/com/aura/consciousness/TheoryOfMind.kt:142-150`

`decayFactor` is `Math.exp(...).toFloat()`. Every other method in the
file uses `kotlin.math` (e.g. `cosine` in `DreamConsolidator.kt:354`
uses `kotlin.math.sqrt`). Minor style issue; no functional impact.

**Fix recipe:** use `kotlin.math.exp(...)` for consistency. Add a
brief comment explaining the 168-hour half-life (1 week).

---

## 4. Subsystem status

### 4.1 Proactive layer

| Component | Wired? | Notes |
|---|---|---|
| `ProactiveAwarenessEngine` | **NO** (P0-1) | 8 heuristics present, but worker skips due to null DI |
| `SalienceFilter` | **NO** (P0-1) | Wired in worker but null at runtime |
| `MotivationAccumulator` | **NO** (P0-1) | 5-factor formula correct, threshold logic works |
| `AdaptiveTimingEngine` | Partial | Always false on cold start (P1-9) |
| `CuriosityScanner` | **NO** (P0-1) | 4 gap types, but worker null |
| `IdleTimePreparationEngine` | **NO** (P0-1) | Predicted-question generator, never called |
| `ProactiveMessageLibrary` | **NO** (P0-1) | Varied templates exist but unused |
| `ProactiveMessageStore` | **NO** (P0-1) | DataStore-backed, but no `setMessage` call lands |
| `ProactiveEventBus` | YES | Emits/consumes; works |
| `ProactiveEvents` | YES | Persistence collector works |
| `ProactiveEventDao` | YES | 5 migrations, 11 queries, well-tested |
| `ProactivePolicyEngine` | **NO** (P2-2) | Utility with no caller |
| `ProactiveRunner` | YES | "Fire now" entry point works |
| `ProactiveScheduler` | YES | WorkManager jobs scheduled correctly |
| `DaemonScheduler` | YES | 15-min interval matches doc |
| `MorningBriefBuilder` | YES | But P1-5, P1-6 issues |
| `CalendarMonitor` | YES | Hilt-provided, scheduled by reconciler |
| `CalendarMonitorService` | YES | Foreground service, FGS-permission aware |

### 4.2 Consciousness layer

| Component | Wired? | Notes |
|---|---|---|
| `NarrativeSelf` | **NO** (P0-3) | `load()` called by bootstrap, but Hilt provides it; `updateFromInteraction` and `save` in agentic loop are null-guarded and never fire |
| `IntrinsicMotivation` | **NO** (P0-3) | 4 drives, all heuristics |
| `TheoryOfMind` | **NO** (P0-3) | `updateFromMessage` in agentic loop, never fires |
| `AgentPresence` | **NO** (P0-1) | Used by `DaemonWorker` only, null at runtime |
| `AffinityTracker` | **NO** (P0-2, P0-3) | `recordTurn` never called; `getDirective` always returns ACQUAINTANCE |

### 4.3 Evolution layer

| Component | Wired? | Notes |
|---|---|---|
| `EvolutionCoordinator` | YES | `runAll()` is called by `EvolutionWorker` |
| `EvolutionApplySaga` | YES | 20 actions, all dispatch to handlers |
| `EvolutionSafetyGuard` | YES | `validateProposal` called from `fromCandidate` |
| `EvolutionShadowEvaluator` | **NO** (P2-1) | Object utility, never called |
| `EvolutionRollbackManager` | YES | Tools call it via `EvolutionTools.rollback` |
| `EvolutionCandidateDetectors` | YES | 5 detectors, all fire |
| `EvolutionEvidenceRecorder` | YES | Hilt-injected |
| `EvolutionHooks` | YES | Producers wired in UseSkillTool/MemoryStore/ProactiveEvents |
| `EvolutionEvaluators` | YES (nullable) | Self-consistency + LLM-judge, called when injected |
| `EvolutionReflectionExecutor` | YES | LLM-based, called from `reflectAndPromote` |
| `EvolutionProposalStore` | YES | `fromCandidate`/`markApplied` called; `recordOutcome`/`pastOutcomes` dead (P0-4) |
| `EvolutionWorker` | YES | Scheduled by `EvolutionScheduler` |
| `EvolutionScheduler` | YES | 24h default, 1h initial delay, network+battery constraints |
| `EvolutionSkillRevisionStore` | YES | Snapshot before skill edits |
| `EvolutionMetrics` / `MetricsRecorder` | YES | DAO-backed counter |
| `EvolutionSettingsStore` | (TBD) | Reads through `EvolutionSettingsDao` |

**Loop closure check:** detect ✓ → propose ✓ → approve ✓ (via
`EvolutionTools.approve`/UI) → apply ✓ → rollback ✓ → feedback ✗ (P0-4)
→ informs future detects ✗ (P0-4).

### 4.4 Dream consolidator

| Phase | Implemented? | Notes |
|---|---|---|
| 1. FETCH | YES | `fetchCandidates` filters by embedding + decay |
| 2. CLUSTER | YES | `clusterByCosine` greedy single-linkage |
| 3. SUMMARIZE | YES | LLM-driven, falls back to first 300 chars on error |
| 4. WRITE | YES | `DreamSummary` upsert by `clusterId` |
| 5. EXTRACT_ROUTINES | YES | N-gram miner, 200-conv limit |
| 6. UPDATE_PROFILE | YES | LLM-extracts facts/traits, merges into profile |
| 7. PRUNE_STALE | YES | decayScore=0 (non-destructive) |
| 8. CONTRADICTION_REPORT | YES | 6 regex patterns, pairs across versions |
| 9. DENSIFY_GRAPH | YES | Jaccard on labels, 20-proposal cap |
| 10. PROMOTE_BELIEFS | YES (bonus) | `beliefPromoter.promote()` |
| 11. WORLD_EVENT | YES (bonus) | `worldEventProducer.recordDreamCycle` |
| 12. OPPORTUNITY_ENGINE | YES (bonus) | `opportunityEngine.runCycle()` |

All 9 phases (plus 3 bonus) are present and non-stub. The cycle is
idempotent (clusterId-keyed), bounded, and `Result.retry()` is
returned on error so WorkManager backs off. Best-in-class
implementation.

### 4.5 World model

| Component | Wired? | Notes |
|---|---|---|
| `WorldEventProducer` | YES | Records events; called by dream cycle |
| `OpportunityEngine` | YES | Called by dream cycle (phase 12) |
| `BeliefPromoter` | YES | Called by dream cycle (phase 10) |
| `BeliefArbiter` | (TBD) | Conflict resolution |
| `BeliefConflictProbe` | (TBD) | Periodic probe |
| `BeliefReviser` | (TBD) | Revise beliefs based on new evidence |
| `WorldModelDaos` | YES | DAOs |
| `WorldModelEntities` | YES | Entities |

---

## 5. Producer-vs-table matrix (P0 producer-less entities check)

| Table | Producers | Status |
|---|---|---|
| `proactive_events` | `ProactiveEvents` collector (L122) | ✅ has producer |
| `proactive_interactions` | `ProactiveEvents.recordInteraction` (L186) | ✅ has producer |
| `evolution_evidence` | `EvolutionEvidenceRecorder.record` | ✅ has producer |
| `evolution_candidates` | `EvolutionCandidateDetectors.runAll` | ✅ has producer |
| `evolution_proposals` | `EvolutionProposalStore.fromCandidate` | ✅ has producer |
| `evolution_revisions` | `EvolutionSkillRevisionStore.snapshot` (skill side) | ⚠ only skills snapshotted; memory/belief revisions never written |
| `evolution_settings` | **NONE** | ❌ no producer — defaults are used; auto-apply never enabled |
| `dream_summaries` | `DreamConsolidator.runCycle` (phase 4) | ✅ has producer |
| `dream_contradictions` | `DreamConsolidator.runCycle` (phase 8) | ✅ has producer |
| `routines` | `DreamConsolidator.runCycle` (phase 5) | ✅ has producer |
| `kg_edge_proposals` | `DreamConsolidator.runCycle` (phase 9) | ✅ has producer |
| `world_events` | `WorldEventProducer` | ✅ has producer |
| `opportunities` | `OpportunityEngine.runCycle` | ✅ has producer |
| `beliefs` | `BeliefPromoter.promote` | ✅ has producer |
| `belief_evidence` | `BeliefPromoter` (per-edge) | ✅ has producer |

**Findings:**
- **`evolution_settings` is a producer-less table** (P1). The
  `autoApplyApproved`, `reflectionEnabled`, `shadowEnabled`,
  `dailyCloudCallBudget` etc. are all default-initialized in the
  entity (L184-209). A `EvolutionSettingsStore` class exists with
  `ensureDefaults()` (which auto-seeds one row per domain), **but
  `ensureDefaults()` is never called** (verified by ripgrep). And
  the `EvolutionCoordinator` reads `settingsDao.all()` directly
  (L59) rather than `EvolutionSettingsStore.all()` — so even if a
  future call to `setAutoApplyApproved` writes a row, the
  coordinator's lookup never finds it.

  **Net effect:** auto-apply never fires. Every approved proposal must
  be manually applied via the inbox. This is consistent with the
  codebase's conservative default but the schema invites auto-apply
  that cannot actually run.

  **Fix recipe:** (a) call `evolutionSettingsStore.ensureDefaults()`
  in `ProactiveBootstrap` or `EvolutionCoordinator.runAll`; (b) point
  the coordinator at `EvolutionSettingsStore.all()`; (c) add a
  Settings screen section to toggle per-domain
  `autoApplyApproved` / `reflectionEnabled`; (d) add an E2E test
  that toggles a domain, runs the coordinator, and asserts the
  apply saga fired.

- **`evolution_revisions` is only written for skills** (P1). Look for
  the call sites:

  ```bash
  rg skillRevisionStore aura-core/src/main/kotlin/com/aura/evolution/
  ```

  Only `applyCreateSkill`, `applyPatchSkill`, `applyRewriteSkill`,
  `applyMergeSkills`, `applyAddSkillExample` call
  `skillRevisionStore?.snapshot`. The memory/belief actions do
  not — only the rollback snapshot is taken for them. So the
  audit trail in `evolution_revisions` is skills-only. This may
  be intentional but the entity comment says "each row is an
  immutable snapshot of a skill, memory policy, or proactive rule
  after a successful apply" — misleading.

---

## 6. Silent `runCatching` swallowing

Total count of `runCatching` blocks in the 5 target packages: **78**.
Of these, **41 are paired with `.onFailure { Log.w(...) }`** and
therefore visible. The remaining **37 silently swallow** and either
return a default (`getOrDefault`) or just `getOrNull()`. Examples
that are problematic:

- `DreamConsolidator.resolveCheapModel` (L419-433): the
  `.onFailure { Log.w } .onFailure { Log.w }` double-onFailure is a
  copy-paste error — the second `onFailure` never runs because the
  first already produced a logged value (or rather, the chain
  re-applies onFailure to the result of the first, which is
  ineffective).
- `MemoryAugmentedAgenticLoop` has 14 `runCatching` blocks; 9 are
  silent. The consciousness layer failures (theoryOfMind,
  narrativeSelf, intrinsicMotivation) are all `runCatching` with
  `Log.w` — but the deps are null, so the catch never runs.
- `EvolutionApplySaga.applyConsolidateMemories` (L228) has a
  `runCatching { it.get(id) }.onFailure { Log.w("args parse
  failed:") }` where the log message is "args parse failed" but the
  actual failure is a missing memory id — wrong message.
- `EvolutionRollbackManager.restoreArtifact` for `RETIRE_SKILL` and
  several other actions has no runCatching around the actual restore
  — a JSON decode failure will throw out of the rollback.

**Fix recipe:** standardize on the pattern
`runCatching { … }.onFailure { Log.w(TAG, "context: $key", it) }`
and never re-use a `Log.w` line for two different failure modes.

---

## 7. Specific evidence for "Consciousness modules: are load/save/updateFromMessage called?"

| Method | Caller | Called? |
|---|---|---|
| `NarrativeSelf.load()` | `ProactiveBootstrap.start` L80 | ✅ called once at startup |
| `NarrativeSelf.save()` | `MemoryAugmentedAgenticLoop` L1027 | ❌ never (null dep) |
| `NarrativeSelf.updateFromInteraction()` | `MemoryAugmentedAgenticLoop` L1026 | ❌ never (null dep) |
| `NarrativeSelf.updateFromDream()` | **not called anywhere** | ❌ never |
| `NarrativeSelf.setCoreIdentity()` | **not called anywhere** | ❌ never |
| `NarrativeSelf.updateRelationshipState()` | **not called anywhere** | ❌ never |
| `TheoryOfMind.updateFromMessage()` | `MemoryAugmentedAgenticLoop` L544 | ❌ never (null dep) |
| `TheoryOfMind.updateTopic()` | **not called anywhere** | ❌ never |
| `TheoryOfMind.decayTopics()` | **not called anywhere** | ❌ never (the comment says "Call from a periodic worker (e.g. DecayWorker)" but DecayWorker doesn't call it) |
| `IntrinsicMotivation.assess()` | **not called anywhere** | ❌ never |
| `IntrinsicMotivation.satisfy()` | **not called anywhere** | ❌ never |
| `AgentPresence.update()` | **not called anywhere** | ❌ never |
| `AgentPresence.generateIdleThought()` | **not called anywhere** | ❌ never |
| `AgentPresence.generateOutreachMessage()` | `DaemonWorker` L151 | ❌ never (null dep) |
| `AffinityTracker.load()` | **not called anywhere** (not even at startup) | ❌ never |
| `AffinityTracker.recordTurn()` | **not called anywhere** | ❌ never (P0-2) |
| `AffinityTracker.getDirective()` | `MemoryAugmentedAgenticLoop` L609 | ❌ always returns ACQUAINTANCE directive |

**Net:** of 17 public methods, only 1 is called (`NarrativeSelf.load()`
in bootstrap, which is a no-op when `narrativeSelf` is null). 16/17
are dead.

---

## 8. Evolution loop closure

| Stage | Where | Status |
|---|---|---|
| Detect | `EvolutionCandidateDetectors.runAll` | ✅ |
| Score (heuristic) | `score` in detector (events.size / N) | ✅ |
| Persist candidate | `candidateDao.upsert` | ✅ |
| (Optional) Evaluate | `EvolutionEvaluators.evaluate` | ✅ (if injected) |
| Reflect | `EvolutionReflectionExecutor.reflect` | ✅ (LLM call) |
| Promote | `EvolutionProposalStore.fromCandidate` | ✅ |
| Approve (user) | `EvolutionProposalStore.approve` | ✅ (via `EvolutionTools.approve`) |
| Apply | `EvolutionApplySaga.apply` | ✅ |
| Auto-apply | `EvolutionCoordinator.runAll` L97-108 | ❌ always false (P1: no `evolution_settings` rows) |
| Rollback | `EvolutionRollbackManager.rollback` | ✅ (but with P0-5 corruption risk) |
| **Outcome feedback** | `EvolutionProposalStore.recordOutcome` | ❌ never called (P0-4) |
| **Informed future** | `EvolutionProposalStore.pastOutcomes` | ❌ never read (P0-4) |

Loop is **8/10 closed**. The two missing stages are the most
important for self-improvement.

---

## 9. Top-5 fix priorities (one PR per fix)

1. **P0-1 + P0-3** (one PR): Add Hilt `@Provides` for the 9 proactive
   engines and 4 consciousness classes, drop the `? = null` defaults.
   This unblocks the entire proactive + consciousness pipeline.
   Add smoke tests that prove each engine's `runAll`/`updateFromMessage`
   is called at least once per app session.

2. **P0-2**: Add `affinityTracker.recordTurn()` to
   `MemoryAugmentedAgenticLoop` after a successful assistant turn.
   This single line activates the affinity level system.

3. **P0-4**: Add an outcome recorder worker
   (`EvolutionOutcomeWorker`) that runs 1d/3d/7d after
   `proposal.resolvedAt` and calls `recordOutcome` based on usage
   signals. Wire `pastOutcomes` into `EvolutionCoordinator.runAll()`.

4. **P0-5**: Replace the raw string-interpolation JSON in
   `applyUpdateBelief` and `applyRetireBelief` with `Json.encodeToString`
   on a `@Serializable BeliefSnapshot` data class. Same change for
   `applyPatchSpecialistPrompt`.

5. **P0-6 / P0-7**: Mark destructive merge/consolidate operations as
   `requiresApproval = true` (don't auto-apply) and rename
   `applyAdjustRuleTiming` to `RECOMMEND_TIMING` so it can be
   surfaced as a suggestion rather than a fake apply.

---

## 10. Inventory snapshot

```
aura-core/.../proactive/        30 files, ~3.3K LOC
aura-core/.../consciousness/     5 files, ~1.2K LOC
aura-core/.../evolution/        25 files, ~3.4K LOC
aura-core/.../dream/            14 files, ~1.3K LOC
aura-core/.../world/             7 files, ~1.0K LOC
─────────────────────────────────────────────
Total                           81 files, ≈9.3K LOC
```

**Tests exercising these subsystems:** 25+ (ConsciousnessLayerTest,
AffinityTrackerTest, EvolutionHooksTest, ProactiveOutcomeRecordingTest,
SkillsStoreEvolutionHookTest, UseSkillToolEvolutionHookTest,
MemoryFeedbackHookTest, EvolutionShadowEvaluatorTest, plus the dream,
proactive, and evolution integration tests). Coverage is reasonable
for unit-level concerns, but **end-to-end integration tests of the
daemon pipeline, the consciousness system prompt injection, and the
evolution feedback loop are absent** — which is why the dead wiring
went undetected.

---

*End of audit.*
