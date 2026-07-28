# Claude Review — 2026-07-29

Branch `feat/tier-1-friction`, diff covering `fd2e3716..994586f1`
(evolution autoApply + MCP version, voice barge-in + daemon tool access,
cross-conversation continuity).

All findings below were verified against source. One first-pass finding
(StateFlow replay in the barge-in collector) was **refuted on
verification** and is recorded in "Refuted" at the bottom.

---

## P0 — Duplicate STT collector during SPEAKING causes double-send

**File:** `app/src/main/kotlin/com/aura/ui/voice/ContinuousVoiceViewModel.kt:177`

`speakResponse()` now calls `speechToText.start()` and spawns
`bargeInCollector`. But the *previous* collector — `sttCollectorJob`,
created in `startListening()` (line 105) — is **never cancelled when the
state machine leaves LISTENING**. It is only cancelled at the top of the
*next* `startListening()` call (line 89).

So during SPEAKING there are two live collectors on the same
`speechToText.state` StateFlow:

| collector | phase guard | on FinalResult |
|---|---|---|
| `bargeInCollector` (new, line 178) | `phase != SPEAKING → return` | stops TTS, `onSend(text)` |
| `sttCollectorJob` (old, still running) | **none** | `onSend(text)` |

**Failure scenario:** user enters voice mode, speaks, the assistant starts
replying, the user barges in with "actually make it shorter". STT emits
`FinalResult("actually make it shorter")`. Both collectors fire →
`onSend()` runs **twice** with identical text → two agent runs, two billed
provider calls, and two `responseWaitJob`s. `responseWaitJob` is
reassigned without cancelling the first (lines 189 and 206), so the earlier
polling loop leaks until `stopLoop()`.

Before this diff the old collector was inert because STT was cancelled for
the duration of SPEAKING. `speechToText.start()` at line 177 is exactly
what brought it back to life.

**Fix:** cancel `sttCollectorJob` at the top of `speakResponse()` and
assign the barge-in collector to it, preserving the single-owner
discipline the class comment (lines 52-55) claims to maintain.

---

## P1 — Barge-in has no acoustic echo suppression; TTS will trigger itself

**File:** `ContinuousVoiceViewModel.kt:177-197`

STT is started while TTS is actively playing through the device speaker.
`SpeechToText.start()` (`SpeechToText.kt:85-95`) builds a plain
`ACTION_RECOGNIZE_SPEECH` intent with `EXTRA_PREFER_OFFLINE` — no
`AudioManager` mode change, no `AcousticEchoCanceler`, no
`VOICE_COMMUNICATION` audio source. On a phone speaker at normal volume
the recognizer hears the assistant's own output.

The barge-in threshold is `text.length > 2` (line 184) — three characters
of any partial transcript.

**Failure scenario:** the assistant begins speaking; the mic picks up its
own first word; `PartialResult("the")` arrives; length 3 > 2 → TTS is
killed and "the" is sent to the agent as a user turn. The agent replies,
TTS speaks, self-triggers again. The loop never converges and the user
cannot get a complete spoken answer.

This is the difference between barge-in working on a headset and being
unusable on speaker. **Fix:** route through
`MediaRecorder.AudioSource.VOICE_COMMUNICATION` with AEC, or gate
barge-in on a much higher confidence bar (word count + a
`textToSpeech.isSpeaking` grace window), or make barge-in opt-in and
headset-only.

---

## P1 — TTS `Ready` race resets phase mid-barge-in, swallowing the response

**File:** `ContinuousVoiceViewModel.kt:185-187`, `221-231`

In the barge-in handler the order is:

```kotlin
textToSpeech.stop()               // async → pushes TTS state to Ready
coroutineContext[Job]?.cancel()
_state.update { phase = THINKING }
onSend(text)
```

`ttsCollectorJob` is still collecting. If the `Ready` emission caused by
`stop()` is observed *before* `_state.update` commits `THINKING`, the TTS
collector's guard `phase == SPEAKING` passes and it calls
`startListening()` — which resets phase to LISTENING, restarts STT, and
cancels the `responseWaitJob` the barge-in just created.

**Failure scenario:** user barges in; the message reaches the agent, but
the voice loop drops back to LISTENING and never speaks the reply. The
response is generated, billed, and silently discarded.

**Fix:** commit `phase = THINKING` *before* `textToSpeech.stop()`, and
cancel `ttsCollectorJob` in the barge-in path.

---

## P1 — `bargeInCollector` leaks on `stopLoop()` and reactivates on the next cycle

**File:** `ContinuousVoiceViewModel.kt:178`, `74-82`

`bargeInCollector` is a **local `val`**, not one of the tracked
single-owner fields (`sttCollectorJob` / `ttsCollectorJob` /
`responseWaitJob`, lines 56-58). `stopLoop()` cancels all three fields but
holds no reference to the barge-in job, so it survives.

It is cancelled on exactly one path: the TTS collector observing `Ready`
(line 224). If the user taps Stop while the assistant is speaking — the
single most likely moment to tap Stop — that path never runs.

**Failure scenario:** user stops voice mode mid-reply. The leaked collector
stays subscribed to `speechToText.state`, inert only because of its
`phase != SPEAKING` guard. The user restarts voice mode, speaks, and the
assistant begins replying — phase flips to SPEAKING and the **leaked
collector wakes up alongside the new one**. Two barge-in collectors plus
the P0 stale `sttCollectorJob` all fire on the next `FinalResult` → three
`onSend()` calls for one utterance. Every stop-during-speech cycle adds
another permanently-subscribed collector for the life of the ViewModel.

**Fix:** store it in a `bargeInCollectorJob` field; cancel it in
`stopLoop()` and at the top of `speakResponse()`.

---

## P1 — Barge-in bypasses `STOP_PHRASES`

**File:** `ContinuousVoiceViewModel.kt:182-214` vs `121-124`

The `startListening` collector checks `text.lowercase() in STOP_PHRASES`
and calls `stopLoop()`. The barge-in collector has no such check.

**Failure scenario:** the assistant is mid-sentence, the user says "stop"
to end voice mode. `"stop"` is 4 chars → clears the `length > 2` bar →
`onSend("stop")` sends the word *to the agent as a message* instead of
exiting. Today the still-live old collector (P0) may catch it first, which
means fixing P0 as written will *expose* this bug rather than fix it —
they must be fixed together.

**Fix:** hoist the `STOP_PHRASES` check into a shared handler used by both
collectors.

---

## P1 — `coroutineContext[Job]?.cancel()` is fragile and leaves the mic hot

**File:** `ContinuousVoiceViewModel.kt:186`, `203`

Inside `collect { }`, `coroutineContext` is the collector coroutine's, so
this cancels `bargeInCollector` itself. That is the apparent intent, but:

- it makes `bargeInCollector.cancel()` at line 224 dead code;
- execution *continues* past `cancel()` because cancellation only lands at
  the next suspension point. Correct today, but adding any `suspend` call
  between line 186 and `onSend(text)` would silently drop the user's
  utterance with a `CancellationException` — a latent trap for the next
  editor;
- `speechToText.cancel()` is never called on this path, so the recognizer
  keeps running through THINKING, burning mic and battery, and the next
  `startListening()` → `start()` → `cleanup()` tears down a recognizer
  mid-session.

**Fix:** use the named reference (`bargeInJob?.cancel()`) and add
`speechToText.cancel()`.

---

## P1 — Auto-apply can perform irreversible merges without user consent

**File:** `aura-core/src/main/kotlin/com/aura/evolution/EvolutionCoordinator.kt:90-102`

The new comment asserts: *"The EvolutionRollbackManager supports rollback
for all 20 actions, so auto-applied changes are safe to revert."* The
rollback code contradicts this at two sites it documents itself:

- `EvolutionRollbackManager.kt:113` — `"no rollback snapshot (source skill
  cannot be restored)"` / `"...source skill was deleted and cannot be
  auto-restored"` (MERGE_SKILLS)
- `EvolutionRollbackManager.kt:188` — `"no rollback snapshot (source memory
  cannot be restored)"` (MERGE_MEMORIES)

**Failure scenario:** `autoApplyApproved` is enabled for the `skills`
domain. The reflection model approves a `MERGE_SKILLS` candidate. The saga
merges and deletes the source skill. The user later opens the inbox and
hits Rollback: the target skill is restored, the source skill is gone
permanently — and the user was never shown the change beforehand, because
auto-apply bypassed the inbox by design.

Mitigating: `EvolutionSettingsEntity.autoApplyApproved` defaults to
`false` (`EvolutionEntities.kt:189`, asserted in
`EvolutionContractTest.kt:56`), so this requires explicit opt-in per
domain. That keeps it P1 rather than P0.

**Fix:** allowlist auto-apply to genuinely reversible actions
(`PATCH_SKILL`, `ADD_EXAMPLE`, `ADJUST_BELIEF`, …) and always route
destructive merges/retires to the inbox. At minimum, correct the comment —
it is the justification a future reader will rely on.

---

## P1 — `applySaga.apply()` is unguarded; one throw wedges the pipeline

**File:** `EvolutionCoordinator.kt:95`

Every other fallible call in this class is wrapped (`runCatching` at line
33). `applySaga.apply(proposal)` is not. The saga touches `skillsStore`,
`memoryStore`, `beliefDao`, and `Json.decodeFromString` — all of which can
throw.

**Failure scenario:** candidate 3 of 10 carries a `patchJson` that makes
the skills store throw. The exception propagates out of
`reflectAndPromote` → out of `runAll()`. `metrics.recordRun` never fires,
and candidates 4-10 go unprocessed. On the next run the same candidate is
re-selected by `take(MAX_REFLECTIONS_PER_RUN)` (line 65) and throws again —
the evolution pipeline is permanently wedged, and every run silently costs
the reflection LLM calls for candidates 1-2 before dying.

Compounding: the candidate was already marked `PROMOTED` at lines 84-87
before the apply attempt, so on a throw it is left claiming success.

**Fix:** `runCatching { applySaga.apply(proposal) }.onFailure { Log.w(...) }`
and fall through to the "auto-apply failed, pending review" status that
already exists at line 100.

---

## P2 — Candidate retention purge is case-mismatched and now also misses AUTO_APPLIED

**File:** `aura-core/src/main/kotlin/com/aura/evolution/EvolutionDaos.kt:54`

```sql
DELETE FROM evolution_candidates WHERE createdAt <= :cutoff
  AND status IN ('rejected', 'promoted')
```

Statuses are written as `CandidateStatus.PROMOTED.name` → `"PROMOTED"`,
`"REJECTED"` (uppercase). SQLite `IN` on TEXT is case-sensitive without
`COLLATE NOCASE`, so this `DELETE` has never matched a row.

**Failure scenario:** the candidates table grows without bound; every
evolution run re-reads a table that only ever accumulates. Pre-existing,
but this diff adds a third terminal status (`AUTO_APPLIED`) that would
also need listing once the casing is fixed — worth fixing in the same
change while the file is open.

**Fix:** `status IN ('REJECTED', 'PROMOTED', 'AUTO_APPLIED')`.

---

## P2 — DaemonWorker: "today" boundary misses the first minute of the day

**File:** `aura-core/src/main/kotlin/com/aura/proactive/DaemonWorker.kt:66-69`

```kotlin
Calendar.getInstance().apply {
    set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
}.timeInMillis
```

`SECOND` and `MILLISECOND` are never cleared, so `today` is
`00:00:ss.SSS` — the current second-and-millisecond of the minute — and
`tomorrow = today + 24h` inherits the same offset.

**Failure scenario:** the worker fires at 09:14:37.412. The window becomes
`[00:00:37.412, next-day 00:00:37.412)`. A task due at 00:00:12 today is
excluded and never surfaced; a task due at 00:00:20 *tomorrow* is wrongly
reported as "due today".

`HomeViewModel.kt:345-349` does this correctly with
`set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)` — the daemon just
dropped two lines. **Fix:** copy those two lines.

---

## P2 — DaemonWorker: `trimIndent()` is now a no-op; prompts ship 16-space gutters

**File:** `DaemonWorker.kt:96-113`

Both branches open with content on the first line
(`"""You are Aura's background thinking daemon.`). `trimIndent()` takes the
minimum indent across all non-blank lines, which is now `0`, so the
16-space indent on every continuation line survives into the shipped
prompt. The pre-diff version put a newline immediately after `"""` and
trimmed correctly.

Impact: every daemon invocation (~every 8 min) pays tokens for ~100
characters of ragged whitespace, and line 103 is a whitespace-only line
inside the prompt body. Small, but a regression from working code and a
trivial fix — move the text down one line.

---

## P2 — Privacy: calendar, memory, and task contents now leave the device

**File:** `DaemonWorker.kt:53-80`

The daemon previously sent only conversation turns — content the user had
already typed at a model. It now unconditionally concatenates today's
calendar event strings, decayed memory *contents*, and task titles into
the **system prompt** sent to `backgroundModel`, which may be any
configured remote provider.

This materially expands what is transmitted, gated only by the pre-existing
`daemonEnabled` flag. A user who enabled "background thinking" consented to
conversation review, not to shipping their calendar and private memories to
a third-party inference endpoint. **Fix:** a separate preference (or at
minimum an explicit release-note callout and a settings-screen
description change).

---

## P2 — DaemonWorker's nullable injections are not actually optional

**File:** `DaemonWorker.kt:34-36`

```kotlin
private val calendarReadTool: com.aura.tools.CalendarReadTool? = null,
```

Dagger ignores Kotlin default parameter values, and `Foo?` / `Foo` are the
same binding key — so these resolve to the real singletons. The `?:
emptyList()` fallbacks (lines 54, 60, 70) are unreachable in production.
Conversely, if any of the three bindings were removed the build would
break rather than degrade gracefully.

The nullable-with-default idiom advertises an optionality that does not
exist, and will mislead the next person writing a test. Make them non-null
or use `@BindsOptionalOf`.

---

## P2 — `recentTopics` runs on every send and includes the current conversation

**Files:** `app/src/main/kotlin/com/aura/ui/viewmodel/ChatViewModel.kt:376`,
`app/src/main/kotlin/com/aura/ui/viewmodel/ChatSendController.kt:328`,
`aura-core/src/main/kotlin/com/aura/agent/ConversationStore.kt:350`

The KDoc says the value is "for injection into the system prompt **on new
conversations**", but `ChatSendController` invokes it on *every* send.
Each invocation is a `recent(5)` DB read plus `entityToConversation`
deserialization of five conversations' `turnsJson`.

**Failure scenario:** `recent(5)` includes the conversation currently being
sent to, so its own title and `contextSummary` feed the keyword counter.
The resulting "Recent topics" block echoes the current chat back at the
model on every turn, and the appended instruction *"If relevant, offer to
continue where the user left off"*
(`MemoryAugmentedAgenticLoop.kt:582`) fires in the middle of an active
conversation — the assistant offers to resume the thing the user is
currently doing.

**Fix:** compute once when `conversation.turns.isEmpty()`, and exclude the
active conversation id from `recent()`.

---

## P2 — No test covers any behavioral change in this diff

Verified absent across `aura-core/src/test` and `app/src/test`:

| changed behavior | matching test files |
|---|---|
| voice barge-in | none (`grep -l "barge"` → 0) |
| `ConversationStore.recentTopics` | none (`grep -l "recentTopics"` → 0) |
| `DaemonWorker` tool context | none (no `DaemonWorker` test file) |
| evolution auto-apply | none — `EvolutionCoordinatorTest` and `EvolutionCoordinatorReflectionTest` exist but neither references `autoApply` or `AUTO_APPLIED`; the only hit is `EvolutionContractTest.kt:56`, asserting the *default* is `false` |

The only tests added are the three `ProductionPipelineTest` cases, which
cover a file this diff does not modify. The release notes describe the
pipeline tests as the testing contribution — accurate, but easy to misread
as coverage of this release's actual features.

The auto-apply branch (`EvolutionCoordinator.kt:94-102`) is cheap to test:
`applySaga` is already a constructor-injected nullable, so a fake returning
`Ok` / `Error` covers both status transitions and would have caught the
unguarded-throw issue above.

---

## P3 — `runCatching` around a suspend call swallows `CancellationException`

**File:** `ChatViewModel.kt:376-378`

```kotlin
recentTopics = {
    runCatching { conversationStore.recentTopics(5) }.getOrDefault("")
},
```

`runCatching` catches `Throwable`, including `CancellationException`. The
lambda is invoked from inside the streaming coroutine
(`ChatSendController.kt:328`), immediately before `loop.run(...)`.

**Failure scenario:** the user taps Stop (cancelling `runJob`) while the
`recent(5)` DB read is suspended. The `CancellationException` is caught and
converted to `""`, the coroutine continues past its cancellation point, and
`loop.run(...)` starts the agent run the user just cancelled. It dies at
the next suspension point — but only after the provider request is issued.

**Fix:** rethrow — `.onFailure { if (it is CancellationException) throw it }`
— or catch `Exception` explicitly.

---

## P3 — `recentTopics` keyword extraction is likely low-signal

**File:** `ConversationStore.kt:349-373`

- `STOP_WORDS` has 24 entries and omits high-frequency filler that clears
  the `length > 3` bar: `want`, `need`, `make`, `using`, `should`, `into`,
  `then`, `them`, `will`, `does`, `here`, `only`, `much`. Expect generic
  verbs to dominate the top 8 rather than topics.
- URL fragments in `contextSummary` survive the `[^a-z0-9\s]` strip
  (`https`, `github`, `docs`).
- Ties in `sortedByDescending { it.value }` resolve by map iteration order,
  so identical data can yield a different injected string between runs.

Not a defect, but worth logging one real output before trusting the
feature — a top-8 of `["that", "using", "would", ...]` costs prompt tokens
for nothing.

---

## P3 — Untrusted text flows verbatim into the system prompt

**File:** `aura-core/src/main/kotlin/com/aura/agent/MemoryAugmentedAgenticLoop.kt:581-583`

`topicContext` interpolates `recentTopics` — derived from LLM-generated
conversation titles and compaction summaries — directly into the system
prompt with no delimiter or escaping. The `[^a-z0-9\s]` strip in the
extractor incidentally defuses most injection payloads today, but that
sanitization is a side effect of the word-frequency logic rather than a
deliberate boundary. It would vanish silently if the extractor were
swapped for the LLM-based version the plan contemplates.

---

## P3 — `ProductionPipelineTest` additions

**File:** `aura-core/src/test/kotlin/com/aura/pipeline/ProductionPipelineTest.kt`

- `topological_order_is_valid` uses `positions[dep] ?: Int.MAX_VALUE` and
  `positions[stage.id] ?: -1`. A dependency on a **non-existent** stage id
  evaluates `MAX_VALUE < -1` → false → the test fails with a message about
  ordering rather than about a dangling reference. Split into two
  assertions (exists, then ordered) so the failure localizes.
- `image_stages_have_image_output_type` hardcodes `setOf("storyboard")`.
  Verified non-vacuous today — `ProductionPipeline.kt:96` and `:115` both
  define `storyboard` stages with `outputType = "image"` — but a rename
  would turn this into a silently-passing no-op. Assert at least one stage
  was checked.
- `no_circular_dependencies`: `visited` is shared across the outer
  `for (stage in pipeline.stages)` loop, so a node marked visited during an
  earlier root's DFS short-circuits later roots. This is correct for cycle
  detection (any cycle is found on the first visit that reaches it) but
  reads like a bug — worth one comment line.
- File still has no trailing newline.

---

## Verified clean

- `NavGraph.kt:241` — route is registered at lines 337-338 as
  `"agent_editor?agentId={agentId}"` with `nullable = true; defaultValue =
  null`. Navigating to `"agent_editor"` correctly lets the default take
  effect; the previous `"agent_editor?agentId="` passed an empty string.
  Correct fix.
- `McpConnection.kt:58` — client version string bump only, no protocol
  impact.
- `EvolutionApplySaga` / `EvolutionRollbackManager` — all 16 additions are
  purely additive `.onFailure { Log.w(...) }` inserted between
  `runCatching` and `getOrNull()`. Control flow, return values, and error
  strings unchanged.
- `CandidateStatus.AUTO_APPLIED` — additive enum constant persisted via
  `.name`. No `valueOf` runs against candidate status anywhere, and
  `EvolutionCandidateDao.byStatus` is parameterized, so no existing query
  silently drops the new value. (The purge query is broken, but for an
  unrelated pre-existing reason — see P2 above.)
- `MemoryAugmentedAgenticLoop.kt:592` — `topicContext` is concatenated
  after `joinToString("\n\n")` and before `memoryContext`, matching the
  established pattern for the other context blocks. Default `""` keeps the
  prompt byte-identical when the feature yields nothing.
- `DaemonWorker` early-return restructure — the removed
  `recentTurns.size < 2` guard is correctly subsumed by the
  `userMessage.isBlank()` check at line 122. No path reaches the provider
  with an empty user message.

---

## Refuted on verification

**"StateFlow replay makes barge-in fire on the previous utterance."**
First-pass concern: `SpeechToText.state` is a `MutableStateFlow`
(`SpeechToText.kt:37`), so a new collector immediately receives the
current value — which would be the `FinalResult` from the turn that just
ended, causing an instant false barge-in.

Refuted: `SpeechToText.start()` ends with `_state.value = State.Idle`
(`SpeechToText.kt:118`), executed synchronously before
`viewModelScope.launch` on line 178 schedules the collector. The stale
`FinalResult` is overwritten before any barge-in collector can observe it.
No replay bug. (This does *not* rescue the old `sttCollectorJob`, which was
already subscribed — P0 stands.)
