# Manuscript Ledger Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Long-form drafting reads the scenes it has already written, and records where the manuscript contradicts itself.

**Architecture:** One new class, `SceneLedger`, in `com.aura.creative.longform`. After each scene commits, one cheap-tier model call produces a two-sentence synopsis and a list of canon triples; the synopsis is stored on the beat inside `worldJson`, the facts in `canon_facts`, and any single-valued contradiction in `continuity_issues`. `SceneContextBuilder`'s two never-supplied parameters — `storySoFar` and `retrieved` — are then fed from stored synopses and a lexical search over drafted scenes. No Room migration, no new tables, no new routes.

**Tech Stack:** Kotlin 2.4.10 (K2), Room 2.8.4, Hilt 2.60.1, kotlinx.serialization, coroutines. Tests: JUnit4 + MockK + `kotlinx-coroutines-test` (`runTest`) + `kotlin.test` assertions.

**Spec:** `docs/superpowers/specs/2026-08-15-manuscript-ledger-design.md`

## Global Constraints

Every task's requirements implicitly include all of these.

- **No Room migration, no new schema export, no backup schema bump.** `StoryBeat` is `@Serializable` inside `CreativeProjectEntity.worldJson` (decoded `ignoreUnknownKeys = true`), so new fields need defaults and nothing else. `canon_facts` and `continuity_issues` already exist with DAOs and backup mappers. If any task finds itself writing a `Migration`, stop — the design is being violated.
- **No new nav destination, route, or tab.** `check-version-docs.sh` gates `NAV_DESTINATIONS` and `SECONDARY_ROUTES` counts; `CreativeProjectScreen` already scrolls its tab row at eight.
- **Logging passes the throwable.** `scripts/lint-logging.sh` fails any `Log.x(TAG, "...${it.message}")` without a third `, it` argument. Always `Log.w(TAG, "msg: ${it.message}", it)`.
- **Every `runCatching` needs a handler.** `SilentRunCatchingAuditTest` requires `.onFailure` / `.getOr*` / `.fold` within scan range of each block.
- **Never `@Insert(onConflict = REPLACE)` on a CASCADE parent.** `CascadeParentReplaceAuditTest` scans for it. `creative_revisions` uses `@Upsert` deliberately. This plan adds only `@Query` SELECTs there, so it does not apply — but do not "tidy" existing annotations.
- **Model resolution for auxiliary calls uses `CheapModelResolver`, never `ModelRoleRouter.resolve`.** `resolve` falls through to the conversation default.
- **Test style:** MockK (`mockk`, `coEvery`, `coVerify`, `slot`), `runTest`, `kotlin.test` assertions (`assertEquals`, `assertTrue`, `assertNull`). Match `LongformRunnerTest` and `SceneContextBuilderTest`.
- **Commits:** plain imperative sentence titles matching recent history ("Make the write gate actually gate"), not conventional-commit prefixes. Every commit ends with:
  ```
  Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>
  ```
- **Build commands:** `./gradlew :aura-core:testDebugUnitTest --offline` and `./gradlew :app:testDebugUnitTest --offline`. Single test: add `--tests "com.aura.creative.longform.SceneLedgerTest"`.

## File Structure

| File | Responsibility | Task |
|---|---|---|
| `aura-core/.../creative/longform/SceneContextBuilder.kt` | Modify: raise `SUMMARY_CAP` | 1 |
| `aura-core/.../creative/WorldBible.kt` | Modify: `StoryBeat.synopsis` field | 2 |
| `aura-core/.../creative/longform/LongformRunner.kt` | Modify: write `revisionId`; call ledger; pass context; back-fill | 2, 7, 8 |
| `aura-core/.../creative/longform/SceneLedger.kt` | **Create**: extraction, canon writes, conflict detection, `storySoFar`, `retrieve`, back-fill | 3–6, 8 |
| `aura-core/.../creative/CreativeArtifactDao.kt` | Modify: `CreativeRevisionDao.searchScenes` query | 6 |
| `aura-core/.../tools/CanonQueryTool.kt` | Modify: read canon instead of personal memory | 9 |
| `app/.../ui/screens/creative/CreativeProjectScreen.kt` | Modify: canon card in Manuscript tab | 10 |
| `app/.../ui/viewmodel/CreativeStudioViewModel.kt` | Modify: expose canon state, dismiss action | 10 |
| `aura-core/src/test/.../longform/SceneLedgerTest.kt` | **Create**: ledger unit tests | 3–6, 8 |
| `aura-core/src/test/.../longform/SceneContextBuilderTest.kt` | Modify: section-render + budget tests | 1, 5 |
| `aura-core/src/test/.../longform/LongformRunnerTest.kt` | Modify: the regression gate | 7 |
| `README.md`, `architecture.md` | Modify: describe the ledger | 11 |

---

### Task 1: SceneContextBuilder renders the two missing sections

The smallest real slice, and it stands alone: prove the builder emits both sections when supplied, and give `storySoFar` a budget that fits a book. No ledger yet.

**Files:**
- Modify: `aura-core/src/main/kotlin/com/aura/creative/longform/SceneContextBuilder.kt:201` (`SUMMARY_CAP`)
- Test: `aura-core/src/test/kotlin/com/aura/creative/longform/SceneContextBuilderTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces: `SceneContextBuilder.SUMMARY_CAP = 8_000` — Task 5 budgets `storySoFar` against this exact constant.

- [ ] **Step 1: Write the failing test**

Append to `SceneContextBuilderTest`:

```kotlin
/**
 * The two sections the builder documents and has never been given. Both were
 * defaulted parameters that no production caller passed, so `section()` saw an
 * empty body and emitted nothing at all — the headings did not appear, which is
 * why nothing ever looked wrong.
 */
@Test
fun `it renders the story-so-far and manuscript sections when supplied`() {
    val ctx = builder.build(
        project = project(beats(12)),
        beats = beats(12),
        beatIndex = 5,
        previousSceneTail = "the door closed behind her",
        storySoFar = "Mira reached the lighthouse. The keeper refused her.",
        retrieved = listOf("the lamp had not been lit in forty years"),
    )

    assertTrue(ctx.systemPrompt.contains("== STORY SO FAR =="), ctx.systemPrompt)
    assertTrue(ctx.systemPrompt.contains("The keeper refused her."))
    assertTrue(ctx.systemPrompt.contains("== FROM THE MANUSCRIPT =="))
    assertTrue(ctx.systemPrompt.contains("the lamp had not been lit"))
}

/**
 * Thirty two-sentence synopses do not fit 1,500 characters, which is what the
 * cap was when nothing ever filled the section.
 */
@Test
fun `the story-so-far budget holds a book's worth of synopses`() {
    assertTrue(
        SceneContextBuilder.SUMMARY_CAP >= 8_000,
        "SUMMARY_CAP is ${SceneContextBuilder.SUMMARY_CAP}; 30 synopses at 400 chars need 8,000",
    )
}
```

- [ ] **Step 2: Run the tests to verify they fail**

```bash
./gradlew :aura-core:testDebugUnitTest --offline --tests "com.aura.creative.longform.SceneContextBuilderTest"
```

Expected: `it renders the story-so-far...` PASSES already (the builder is correct — only its callers are wrong), and `the story-so-far budget...` FAILS with "SUMMARY_CAP is 1500".

If the first test *fails*, stop and report — that would mean the builder itself is broken, which contradicts the spec's diagnosis.

- [ ] **Step 3: Raise the cap**

In `SceneContextBuilder.kt` companion object, replace:

```kotlin
        const val SUMMARY_CAP = 1_500
```

with:

```kotlin
        /**
         * Thirty two-sentence synopses, at the 400-character cap `SceneLedger`
         * writes them under. Was 1,500 while nothing ever filled the section —
         * a budget for content that never arrived, so its size was never tested
         * against real input.
         *
         * `SceneLedger.storySoFar` budgets against this constant and drops from
         * the oldest end, so `section()`'s `.take()` is a backstop rather than
         * the mechanism. That matters: `.take()` truncates the tail, which would
         * keep scene one and discard the scene just written.
         */
        const val SUMMARY_CAP = 8_000
```

Also update the KDoc budget table at the top of the file — change the `story so far` row's cap from `1,500` to `8,000`.

- [ ] **Step 4: Run the tests to verify they pass**

```bash
./gradlew :aura-core:testDebugUnitTest --offline --tests "com.aura.creative.longform.SceneContextBuilderTest"
```

Expected: PASS, all cases.

- [ ] **Step 5: Commit**

```bash
git add aura-core/src/main/kotlin/com/aura/creative/longform/SceneContextBuilder.kt \
        aura-core/src/test/kotlin/com/aura/creative/longform/SceneContextBuilderTest.kt
git commit -m "$(cat <<'EOF'
Assert the two context sections the builder has never been given

SceneContextBuilder documents an eight-section budget and LongformRunner
supplies six. `storySoFar` and `retrieved` are defaulted parameters that no
production caller passes, and `section()` returns "" for an empty body — so the
headings never appeared and nothing looked wrong.

The existing tests only fill them with "y".repeat(50_000) to prove the caps
truncate. The content has never arrived. This adds the assertion that the
sections render at all, which is the one that would have caught it.

SUMMARY_CAP rises 1,500 -> 8,000: it was sized for content that never arrived,
and thirty synopses do not fit 1,500 characters.

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 2: StoryBeat carries a synopsis, and revisionId gets a writer

Two serialisation-level changes with no migration. `revisionId` has been declared and never written since it was added; canon provenance depends on it.

**Files:**
- Modify: `aura-core/src/main/kotlin/com/aura/creative/WorldBible.kt:82-97` (`StoryBeat`)
- Modify: `aura-core/src/main/kotlin/com/aura/creative/longform/LongformRunner.kt:260-262`
- Test: `aura-core/src/test/kotlin/com/aura/creative/longform/LongformRunnerTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces: `StoryBeat.synopsis: String = ""` — Tasks 3, 5, 8, 10 read and write it. `StoryBeat.revisionId` populated on commit — Task 3 uses it as `CanonFactEntity.sourceRevisionId`.

- [ ] **Step 1: Write the failing test**

Append to `LongformRunnerTest`:

```kotlin
/**
 * `StoryBeat.revisionId` is documented as "the revision of that artifact holding
 * this beat's text" and was written by nothing: the commit copied `artifactId`
 * only, while `CreativeArtifactStore.create` already returns an entity carrying
 * `currentRevisionId`. `CanonFactEntity.sourceRevisionId` is the provenance
 * field the canon store rests on and cannot be filled honestly without it.
 */
@Test
fun `a committed beat records the revision its text lives in`() = runTest {
    val worldSlot = slot<WorldBible>()
    setUpRun(beats(1))
    coEvery { artifactStore.create(any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns
        CreativeArtifactEntity(
            id = "art1",
            projectId = "p1",
            branchId = "main",
            kind = "scene",
            title = "1. Beat 1",
            currentRevisionId = "rev1",
        )
    coEvery { projectStore.updateWorld("p1", capture(worldSlot)) } returns null

    runner().runSlice("j1", deadlineMs = Long.MAX_VALUE, isStopped = { false })

    val written = worldSlot.captured.outline.first()
    assertEquals("art1", written.artifactId)
    assertEquals("rev1", written.revisionId, "the beat must name the revision holding its text")
}
```

Note: this test constructs a real `CreativeArtifactEntity` rather than a relaxed mock, because it asserts on `currentRevisionId`. Check the entity's required constructor parameters in `CreativeArtifactEntity.kt` and fill any this snippet omits with their defaults.

- [ ] **Step 2: Run it to verify it fails**

```bash
./gradlew :aura-core:testDebugUnitTest --offline --tests "com.aura.creative.longform.LongformRunnerTest"
```

Expected: FAIL — `expected:<rev1> but was:<>`.

- [ ] **Step 3: Add the synopsis field**

In `WorldBible.kt`, inside `StoryBeat`, after `revisionId`:

```kotlin
    /**
     * Two sentences on what this scene changed, written once by [SceneLedger]
     * when the scene was fresh and never re-summarised afterwards.
     *
     * Lives here rather than in a table because it is a fact about this beat and
     * travels with it — through `worldJson`, through backup, through a branch
     * fork — with no migration, no mapper and no doc count to keep in step.
     *
     * Blank means "not extracted yet", which is a state the back-fill in
     * [com.aura.creative.longform.LongformRunner] exists to clear. It is not the
     * same as "this scene changed nothing".
     */
    val synopsis: String = "",
```

- [ ] **Step 4: Write the revision id on commit**

In `LongformRunner.draftScene`, replace:

```kotlin
                val id = artifactStore.create(
                    projectId = project.id,
                    branchId = beatBranch(jobId),
                    kind = KIND_SCENE,
                    title = "${index + 1}. ${beat.title}",
                    initialContent = body,
                    authorKind = "generation",
                    modelId = model,
                    prompt = beat.summary.ifBlank { beat.title },
                ).id
                val updated = beats.toMutableList().also {
                    it[index] = beat.copy(status = STATUS_DRAFTED, artifactId = id)
                }
```

with:

```kotlin
                val artifact = artifactStore.create(
                    projectId = project.id,
                    branchId = beatBranch(jobId),
                    kind = KIND_SCENE,
                    title = "${index + 1}. ${beat.title}",
                    initialContent = body,
                    authorKind = "generation",
                    modelId = model,
                    prompt = beat.summary.ifBlank { beat.title },
                )
                val id = artifact.id
                // `revisionId` too, not just `artifactId`. It has been declared
                // since this class was written and set by nothing, while `create`
                // has always returned the entity carrying it. Canon provenance
                // (`CanonFactEntity.sourceRevisionId`) cannot be filled without it.
                val updated = beats.toMutableList().also {
                    it[index] = beat.copy(
                        status = STATUS_DRAFTED,
                        artifactId = id,
                        revisionId = artifact.currentRevisionId.orEmpty(),
                    )
                }
```

If `currentRevisionId` is non-nullable in `CreativeArtifactEntity`, drop the `.orEmpty()`.

- [ ] **Step 5: Run the tests to verify they pass**

```bash
./gradlew :aura-core:testDebugUnitTest --offline --tests "com.aura.creative.longform.LongformRunnerTest"
```

Expected: PASS, including all pre-existing cases.

- [ ] **Step 6: Commit**

```bash
git add aura-core/src/main/kotlin/com/aura/creative/WorldBible.kt \
        aura-core/src/main/kotlin/com/aura/creative/longform/LongformRunner.kt \
        aura-core/src/test/kotlin/com/aura/creative/longform/LongformRunnerTest.kt
git commit -m "$(cat <<'EOF'
Give a beat somewhere to remember what its scene did

StoryBeat gains `synopsis`. It rides inside worldJson, which is decoded with
ignoreUnknownKeys and defaults on every field, so this is a serialisation change
and not a Room migration — existing projects decode with "".

And `revisionId` finally gets a writer. It has been declared since this class
was written, documented as "the revision of that artifact holding this beat's
text", and set by nothing: the commit copied artifactId only, while
CreativeArtifactStore.create has always returned an entity already carrying
currentRevisionId. CanonFactEntity.sourceRevisionId is the provenance field the
canon store rests on and cannot be filled honestly without it.

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 3: SceneLedger records a synopsis and canon facts

The core. One cheap-tier call per committed scene, parsed with `StructuredJson`, written to the beat and to `canon_facts`. Conflict detection is Task 4 — this task writes facts and never inspects existing ones.

**Files:**
- Create: `aura-core/src/main/kotlin/com/aura/creative/longform/SceneLedger.kt`
- Create: `aura-core/src/test/kotlin/com/aura/creative/longform/SceneLedgerTest.kt`

**Interfaces:**
- Consumes: `StoryBeat.synopsis`, `StoryBeat.revisionId` (Task 2).
- Produces:
  - `SceneLedger.record(project: CreativeProject, branchId: String, beatIndex: Int, artifactId: String, revisionId: String, sceneText: String, sceneModel: String): Boolean` — true when a synopsis was stored. Task 7 calls it; Task 8 reuses it for back-fill.
  - `SceneLedger.SYNOPSIS_CAP: Int = 400`
  - `SceneLedger.SUBJECT_TYPES: Set<String>`

- [ ] **Step 1: Write the failing tests**

Create `aura-core/src/test/kotlin/com/aura/creative/longform/SceneLedgerTest.kt`:

```kotlin
package com.aura.creative.longform

import com.aura.creative.CanonFactDao
import com.aura.creative.CanonFactEntity
import com.aura.creative.ContinuityIssueDao
import com.aura.creative.CreativeArtifactStore
import com.aura.creative.CreativeProject
import com.aura.creative.CreativeProjectStore
import com.aura.creative.CreativeRevisionDao
import com.aura.creative.StoryBeat
import com.aura.creative.WorldBible
import com.aura.providers.CheapModelResolver
import com.aura.providers.ModelRole
import com.aura.providers.ModelRoleRouter
import com.aura.providers.ProviderChunk
import com.aura.providers.ProviderRegistry
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The ledger's decisions, driven with a mocked [ProviderRegistry] — the same
 * discipline [LongformRunner] follows and for the same reason: everything that
 * decides something lives in a plain class a JVM test can drive.
 */
class SceneLedgerTest {

    private val projectStore = mockk<CreativeProjectStore>(relaxed = true)
    private val artifactStore = mockk<CreativeArtifactStore>(relaxed = true)
    private val revisionDao = mockk<CreativeRevisionDao>(relaxed = true)
    private val canonFactDao = mockk<CanonFactDao>(relaxed = true)
    private val continuityIssueDao = mockk<ContinuityIssueDao>(relaxed = true)
    private val registry = mockk<ProviderRegistry>(relaxed = true)
    private val modelRoleRouter = mockk<ModelRoleRouter>(relaxed = true)
    private val cheapModelResolver = mockk<CheapModelResolver>(relaxed = true)

    private fun ledger() = SceneLedger(
        projectStore = projectStore,
        artifactStore = artifactStore,
        revisionDao = revisionDao,
        canonFactDao = canonFactDao,
        continuityIssueDao = continuityIssueDao,
        registry = registry,
        modelRoleRouter = modelRoleRouter,
        cheapModelResolver = cheapModelResolver,
    )

    private fun beats(count: Int) = (1..count).map {
        StoryBeat(id = "b$it", title = "Beat $it", summary = "Summary $it", status = "planned")
    }

    private fun project(beatList: List<StoryBeat> = beats(3)) = CreativeProject(
        id = "p1",
        name = "The Lighthouse",
        description = "A keeper who cannot swim",
        genre = "literary",
        tone = "spare",
        world = WorldBible(outline = beatList),
        templateId = "novel",
        turnCount = 0,
        createdAt = 0L,
        updatedAt = 0L,
    )

    private fun stubModel(reply: String) {
        coEvery { modelRoleRouter.explicit(ModelRole.CREATIVE_CRITIC) } returns "cheap:haiku"
        coEvery { registry.chat(any(), any(), any(), any()) } returns flowOf(ProviderChunk(text = reply))
    }

    private val goodReply = """
        {"synopsis":"Mira reached the lighthouse. The keeper refused her entry.",
         "facts":[{"subjectType":"character","subjectId":"Mira","predicate":"location",
                   "value":"the lighthouse","confidence":0.9}]}
    """.trimIndent()

    @Test
    fun `it stores the synopsis on the beat it describes`() = runTest {
        stubModel(goodReply)
        val worldSlot = slot<WorldBible>()
        coEvery { projectStore.get("p1") } returns project()
        coEvery { projectStore.updateWorld("p1", capture(worldSlot)) } returns null

        val stored = ledger().record(
            project = project(),
            branchId = "main",
            beatIndex = 0,
            artifactId = "art1",
            revisionId = "rev1",
            sceneText = "x".repeat(600),
            sceneModel = "openai:gpt-4o",
        )

        assertTrue(stored)
        val written = worldSlot.captured.outline
        assertTrue(written[0].synopsis.contains("The keeper refused her entry."))
        assertEquals("", written[1].synopsis, "only the drafted beat gets a synopsis")
    }

    @Test
    fun `it writes each extracted fact to canon with its source revision`() = runTest {
        stubModel(goodReply)
        val factSlot = slot<List<CanonFactEntity>>()
        coEvery { projectStore.get("p1") } returns project()
        coEvery { canonFactDao.upsertAll(capture(factSlot)) } returns Unit

        ledger().record(project(), "main", 0, "art1", "rev1", "x".repeat(600), "openai:gpt-4o")

        val facts = factSlot.captured
        assertEquals(1, facts.size)
        assertEquals("character", facts[0].subjectType)
        assertEquals("Mira", facts[0].subjectId)
        assertEquals("location", facts[0].predicate)
        assertEquals("rev1", facts[0].sourceRevisionId)
        assertEquals("active", facts[0].status)
    }

    @Test
    fun `a synopsis longer than the cap is truncated on write`() = runTest {
        stubModel("""{"synopsis":"${"y".repeat(2_000)}","facts":[]}""")
        val worldSlot = slot<WorldBible>()
        coEvery { projectStore.get("p1") } returns project()
        coEvery { projectStore.updateWorld("p1", capture(worldSlot)) } returns null

        ledger().record(project(), "main", 0, "art1", "rev1", "x".repeat(600), "openai:gpt-4o")

        assertTrue(worldSlot.captured.outline[0].synopsis.length <= SceneLedger.SYNOPSIS_CAP)
    }

    @Test
    fun `a fact with an unknown subject type is dropped rather than stored`() = runTest {
        stubModel(
            """{"synopsis":"A thing happened.","facts":[
                 {"subjectType":"vibe","subjectId":"Mira","predicate":"location","value":"here"}]}"""
        )
        coEvery { projectStore.get("p1") } returns project()

        ledger().record(project(), "main", 0, "art1", "rev1", "x".repeat(600), "openai:gpt-4o")

        coVerify(exactly = 0) { canonFactDao.upsertAll(any()) }
    }

    @Test
    fun `an unparseable reply leaves the beat alone and reports failure`() = runTest {
        stubModel("I'm sorry, I can't help with that.")
        coEvery { projectStore.get("p1") } returns project()

        val stored = ledger().record(project(), "main", 0, "art1", "rev1", "x".repeat(600), "openai:gpt-4o")

        assertEquals(false, stored)
        coVerify(exactly = 0) { projectStore.updateWorld(any(), any()) }
    }

    /**
     * `ModelRoleRouter.resolve` falls through to the conversation default, so an
     * unset Creative Critic row would run every extraction on the user's
     * flagship — an auxiliary call priced like a third of the scene it
     * describes, on every scene, with nothing reporting it.
     */
    @Test
    fun `with Creative Critic unset it asks the cheap resolver, not the chat default`() = runTest {
        coEvery { modelRoleRouter.explicit(ModelRole.CREATIVE_CRITIC) } returns null
        coEvery { cheapModelResolver.resolve(any(), any()) } returns "cheap:haiku"
        coEvery { registry.chat(any(), any(), any(), any()) } returns flowOf(ProviderChunk(text = goodReply))
        coEvery { projectStore.get("p1") } returns project()

        ledger().record(project(), "main", 0, "art1", "rev1", "x".repeat(600), "openai:gpt-4o")

        coVerify(exactly = 1) { cheapModelResolver.resolve("openai:gpt-4o", "openai:gpt-4o") }
        coVerify(exactly = 0) { modelRoleRouter.resolve(any()) }
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

```bash
./gradlew :aura-core:testDebugUnitTest --offline --tests "com.aura.creative.longform.SceneLedgerTest"
```

Expected: compilation failure — `Unresolved reference: SceneLedger`.

- [ ] **Step 3: Write SceneLedger**

Create `aura-core/src/main/kotlin/com/aura/creative/longform/SceneLedger.kt`:

```kotlin
package com.aura.creative.longform

import android.util.Log
import com.aura.creative.CanonFactDao
import com.aura.creative.CanonFactEntity
import com.aura.creative.ContinuityIssueDao
import com.aura.creative.CreativeArtifactStore
import com.aura.creative.CreativeProject
import com.aura.creative.CreativeProjectStore
import com.aura.creative.CreativeRevisionDao
import com.aura.providers.ChatOptions
import com.aura.providers.CheapModelResolver
import com.aura.providers.ModelRole
import com.aura.providers.ModelRoleRouter
import com.aura.providers.ProviderMessage
import com.aura.providers.ProviderRegistry
import com.aura.providers.ResponseSchema
import com.aura.providers.StructuredJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What the manuscript remembers about itself.
 *
 * `SceneContextBuilder` documents an eight-section budget for a scene and
 * `LongformRunner` supplied six of them: `storySoFar` and `retrieved` were
 * defaulted parameters no production caller passed. Scene twelve of a novel saw
 * the outline titles and the last 2,000 characters of scene eleven, and had not
 * read scenes one through ten. This class is what fills them.
 *
 * **Deliberately has no `Context` and is not a Worker**, for the reason
 * [LongformRunner]'s KDoc gives: everything that decides something stays in a
 * plain class a JVM test can drive.
 *
 * The three jobs are one class rather than three because the act is one act —
 * extract, compare against what canon already holds, decide whether the
 * difference is a contradiction, write three places. Split across three injected
 * components, the orchestration reassembles inside the runner, which is what
 * `AgentRunExecutorWorker` did and why it has no test of its logic at all.
 */
@Singleton
class SceneLedger @Inject constructor(
    private val projectStore: CreativeProjectStore,
    private val artifactStore: CreativeArtifactStore,
    private val revisionDao: CreativeRevisionDao,
    private val canonFactDao: CanonFactDao,
    private val continuityIssueDao: ContinuityIssueDao,
    private val registry: ProviderRegistry,
    private val modelRoleRouter: ModelRoleRouter,
    private val cheapModelResolver: CheapModelResolver,
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }

    /**
     * Read one committed scene and record what it established.
     *
     * Called **after** the artifact commit and outside its `NonCancellable`
     * block, so a bookkeeping call can never endanger a scene that has already
     * been generated, streamed and paid for — the lesson `LongformRunner`'s
     * commit block records from a real device failure.
     *
     * The consequence is that a failure here leaves a committed scene with a
     * blank synopsis, which the runner's back-fill clears on a later slice.
     *
     * @return true when a synopsis was stored.
     */
    suspend fun record(
        project: CreativeProject,
        branchId: String,
        beatIndex: Int,
        artifactId: String,
        revisionId: String,
        sceneText: String,
        sceneModel: String,
    ): Boolean {
        val model = resolveModel(sceneModel) ?: run {
            Log.w(TAG, "no model available for the ledger; scene $beatIndex left unrecorded")
            return false
        }
        val extraction = extract(sceneText, model) ?: return false

        val synopsis = extraction.synopsis.trim().take(SYNOPSIS_CAP)
        if (synopsis.isBlank()) return false

        // Re-read rather than writing through the caller's snapshot. The commit
        // that marked this beat drafted has already landed, and the user can
        // edit the outline in the World tab while a run is in flight — the same
        // reason the runner re-reads the project on every pass.
        val current = runCatching { projectStore.get(project.id) }
            .onFailure { Log.w(TAG, "could not re-read the project: ${it.message}", it) }
            .getOrNull() ?: return false
        val beats = current.world.outline
        if (beatIndex !in beats.indices) return false

        val facts = extraction.facts
            .filter { it.subjectType in SUBJECT_TYPES && it.subjectId.isNotBlank() && it.predicate.isNotBlank() }
            .map { it.toEntity(project.id, branchId, revisionId) }

        if (facts.isNotEmpty()) {
            runCatching { canonFactDao.upsertAll(facts) }
                .onFailure { Log.w(TAG, "could not write canon facts: ${it.message}", it) }
        }

        val updated = beats.toMutableList().also {
            it[beatIndex] = it[beatIndex].copy(synopsis = synopsis)
        }
        return runCatching { projectStore.updateWorld(project.id, current.world.copy(outline = updated)) }
            .onFailure { Log.w(TAG, "could not store the synopsis: ${it.message}", it) }
            .isSuccess
    }

    /**
     * The model the ledger runs on.
     *
     * **Not [ModelRoleRouter.resolve]**, which is the obvious call and the wrong
     * one: it falls through to the conversation default, so an unset Creative
     * Critic row would run every extraction on the user's flagship model with
     * nothing anywhere reporting it. [CheapModelResolver] exists for exactly
     * this failure. `sceneModel` is both the fallback and the exclusion — take
     * anything cheaper, but do the work rather than skipping it.
     */
    private suspend fun resolveModel(sceneModel: String): String? =
        modelRoleRouter.explicit(ModelRole.CREATIVE_CRITIC)?.takeIf(String::isNotBlank)
            ?: cheapModelResolver.resolve(sceneModel, sceneModel)

    private suspend fun extract(sceneText: String, model: String): SceneExtraction? =
        StructuredJson.requestJson(
            registry = registry,
            modelId = model,
            messages = listOf(
                ProviderMessage(role = ProviderMessage.Role.system, content = SYSTEM_PROMPT),
                ProviderMessage(role = ProviderMessage.Role.user, content = sceneText.take(MAX_SCENE_CHARS)),
            ),
            options = ChatOptions(temperature = 0.0, maxTokens = 700),
            schema = EXTRACTION_SCHEMA,
            timeoutMs = EXTRACTION_TIMEOUT_MS,
            tag = TAG,
        ) { cleaned ->
            runCatching { json.decodeFromString(SceneExtraction.serializer(), cleaned) }
                .onFailure { Log.w(TAG, "unparseable scene extraction: ${it.message}", it) }
                .getOrNull()
        }

    private fun ExtractedFact.toEntity(projectId: String, branchId: String, revisionId: String) =
        CanonFactEntity(
            id = UUID.randomUUID().toString(),
            projectId = projectId,
            branchId = branchId,
            subjectType = subjectType,
            subjectId = subjectId.trim(),
            predicate = predicate.trim().lowercase(),
            valueJson = JsonPrimitive(value.trim()).toString(),
            confidence = confidence.coerceIn(0f, 1f),
            sourceRevisionId = revisionId,
            status = "active",
        )

    companion object {
        private const val TAG = "SceneLedger"

        /**
         * A prompt asking for two sentences is a request; a cap is a guarantee.
         * `SceneContextBuilder.SUMMARY_CAP` is budgeted as thirty of these.
         */
        const val SYNOPSIS_CAP = 400

        /** Matches `CanonFactEntity.subjectType`'s documented values. */
        val SUBJECT_TYPES = setOf(
            "character", "location", "faction", "object", "rule", "timeline", "relationship",
        )

        /** A scene is 1,200 words; this is generous and bounds a runaway one. */
        private const val MAX_SCENE_CHARS = 12_000

        /** Longer than the write gate's 8s: this reads a whole scene, not a message. */
        private const val EXTRACTION_TIMEOUT_MS = 20_000L

        private val SYSTEM_PROMPT = """
            You are the continuity clerk for a long-form creative project. You are given one
            scene that has just been written. Record what it established, for the writer of
            the next scene.

            Return two things.

            synopsis: two sentences. What changed in this scene, and what is now true that was
            not true before. Write it for someone who has not read the scene. No praise, no
            summary of the prose style, no commentary.

            facts: the concrete, checkable things this scene established. Each is a subject, a
            predicate and a value — for example a character's location, age, allegiance,
            occupation, rank, or whether they are alive; a relationship formed or broken; a
            rule of the world stated outright.

            subjectType must be one of: character, location, faction, object, rule, timeline,
            relationship.

            Record only what the scene states or plainly shows. Do not infer, do not carry
            facts forward from earlier scenes, and do not record atmosphere, mood, or
            interpretation. An empty facts list is a correct answer for a scene that only
            moves people around a room.
        """.trimIndent()

        private val EXTRACTION_SCHEMA = ResponseSchema(
            name = "scene_ledger_extraction",
            schema = buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject {
                    put("synopsis", buildJsonObject { put("type", "string") })
                    put("facts", buildJsonObject {
                        put("type", "array")
                        put("items", buildJsonObject {
                            put("type", "object")
                            put("properties", buildJsonObject {
                                put("subjectType", buildJsonObject {
                                    put("type", "string")
                                    put("enum", buildJsonArray { SUBJECT_TYPES.forEach { add(JsonPrimitive(it)) } })
                                })
                                put("subjectId", buildJsonObject { put("type", "string") })
                                put("predicate", buildJsonObject { put("type", "string") })
                                put("value", buildJsonObject { put("type", "string") })
                                put("confidence", buildJsonObject { put("type", "number") })
                            })
                            put("required", buildJsonArray {
                                add(JsonPrimitive("subjectType"))
                                add(JsonPrimitive("subjectId"))
                                add(JsonPrimitive("predicate"))
                                add(JsonPrimitive("value"))
                            })
                        })
                    })
                })
                put("required", buildJsonArray { add(JsonPrimitive("synopsis")) })
            },
        )
    }
}

/** Typed rather than hand-parsed — see `StructuredJson`'s KDoc on why. */
@Serializable
internal data class SceneExtraction(
    val synopsis: String = "",
    val facts: List<ExtractedFact> = emptyList(),
)

@Serializable
internal data class ExtractedFact(
    val subjectType: String = "",
    val subjectId: String = "",
    val predicate: String = "",
    val value: String = "",
    val confidence: Float = 1.0f,
)
```

- [ ] **Step 4: Run the tests to verify they pass**

```bash
./gradlew :aura-core:testDebugUnitTest --offline --tests "com.aura.creative.longform.SceneLedgerTest"
```

Expected: PASS, all six cases.

If `ProviderChunk`'s constructor differs from `ProviderChunk(text = ...)`, copy the shape from `LlmWriteGateTest.makeRegistry`, which is the working reference.

- [ ] **Step 5: Run the whole aura-core suite**

```bash
./gradlew :aura-core:testDebugUnitTest --offline
```

Expected: 0 failures. A new `@Singleton @Inject` class with all-required dependencies is a Hilt graph change; if any DI-shape audit test fails, read its message before changing anything.

- [ ] **Step 6: Commit**

```bash
git add aura-core/src/main/kotlin/com/aura/creative/longform/SceneLedger.kt \
        aura-core/src/test/kotlin/com/aura/creative/longform/SceneLedgerTest.kt
git commit -m "$(cat <<'EOF'
Write down what each scene established

SceneLedger reads a committed scene on the cheap tier and records two things:
two sentences on what changed, stored on the beat, and the concrete facts the
scene established, stored in canon_facts.

canon_facts has existed with a full DAO, indices, foreign keys and backup
mappers since it was written, and its only production consumers were
BackupManager's snapshot, restore and purge. Nothing has ever inserted a row.
This is its first writer.

It resolves its model through explicit(CREATIVE_CRITIC) then CheapModelResolver,
never ModelRoleRouter.resolve — resolve falls through to the conversation
default, so an unset Creative Critic row would run every extraction on the
user's flagship model, on every scene, with nothing reporting it.

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 4: The ledger flags a contradiction instead of absorbing it

Canon that silently supersedes can never tell you the model lost track. This adds the comparison — pure triple arithmetic, no model call — and fills `continuity_issues`, the second table nothing has ever written to.

**Files:**
- Modify: `aura-core/src/main/kotlin/com/aura/creative/longform/SceneLedger.kt`
- Test: `aura-core/src/test/kotlin/com/aura/creative/longform/SceneLedgerTest.kt`

**Interfaces:**
- Consumes: `SceneLedger.record(...)` (Task 3).
- Produces: `SceneLedger.SINGLE_VALUED: Set<String>` — Task 10's UI copy refers to these categories.

- [ ] **Step 1: Write the failing tests**

Append to `SceneLedgerTest`:

```kotlin
private fun existingFact(
    predicate: String,
    value: String,
    id: String = "old1",
) = CanonFactEntity(
    id = id,
    projectId = "p1",
    branchId = "main",
    subjectType = "character",
    subjectId = "Mira",
    predicate = predicate,
    valueJson = "\"$value\"",
    sourceRevisionId = "rev0",
    status = "active",
)

private fun replyWith(predicate: String, value: String) = """
    {"synopsis":"Mira moved.",
     "facts":[{"subjectType":"character","subjectId":"Mira","predicate":"$predicate",
               "value":"$value","confidence":0.9}]}
""".trimIndent()

/**
 * A single-valued predicate cannot hold two values at once, so a different one
 * is a contradiction rather than a change. The issue is what makes canon catch
 * drift instead of merely remembering it.
 */
@Test
fun `a changed single-valued fact is flagged and the old one superseded`() = runTest {
    stubModel(replyWith("location", "Kesh"))
    coEvery { projectStore.get("p1") } returns project()
    coEvery { canonFactDao.forSubject("p1", "main", "character", "Mira") } returns
        listOf(existingFact("location", "Varn"))

    ledger().record(project(), "main", 1, "art2", "rev2", "x".repeat(600), "openai:gpt-4o")

    coVerify(exactly = 1) { continuityIssueDao.upsert(match { it.status == "open" && it.category == "location" }) }
    coVerify(exactly = 1) { canonFactDao.updateStatus("old1", "superseded", any()) }
}

/** Traits, allies and possessions accumulate. Nothing about them is a conflict. */
@Test
fun `a changed multi-valued fact writes no issue at all`() = runTest {
    stubModel(replyWith("traits", "reckless"))
    coEvery { projectStore.get("p1") } returns project()
    coEvery { canonFactDao.forSubject("p1", "main", "character", "Mira") } returns
        listOf(existingFact("traits", "cautious"))

    ledger().record(project(), "main", 1, "art2", "rev2", "x".repeat(600), "openai:gpt-4o")

    coVerify(exactly = 0) { continuityIssueDao.upsert(any()) }
    coVerify(exactly = 0) { canonFactDao.updateStatus(any(), any(), any()) }
}

/** Restating a fact is not a contradiction. */
@Test
fun `repeating an identical single-valued fact writes no issue`() = runTest {
    stubModel(replyWith("location", "Varn"))
    coEvery { projectStore.get("p1") } returns project()
    coEvery { canonFactDao.forSubject("p1", "main", "character", "Mira") } returns
        listOf(existingFact("location", "Varn"))

    ledger().record(project(), "main", 1, "art2", "rev2", "x".repeat(600), "openai:gpt-4o")

    coVerify(exactly = 0) { continuityIssueDao.upsert(any()) }
}

/**
 * `evidenceFactIdsJson` is named for what it holds. Each fact already carries
 * its own `sourceRevisionId`, and that chain is how the card names the scene
 * each half came from without duplicating the link.
 */
@Test
fun `the issue cites the two fact ids, not artifact ids`() = runTest {
    stubModel(replyWith("location", "Kesh"))
    val issueSlot = slot<com.aura.creative.ContinuityIssueEntity>()
    val factSlot = slot<List<CanonFactEntity>>()
    coEvery { projectStore.get("p1") } returns project()
    coEvery { canonFactDao.forSubject("p1", "main", "character", "Mira") } returns
        listOf(existingFact("location", "Varn"))
    coEvery { canonFactDao.upsertAll(capture(factSlot)) } returns Unit
    coEvery { continuityIssueDao.upsert(capture(issueSlot)) } returns Unit

    ledger().record(project(), "main", 1, "art2", "rev2", "x".repeat(600), "openai:gpt-4o")

    val newFactId = factSlot.captured.first().id
    assertTrue(issueSlot.captured.evidenceFactIdsJson.contains("old1"))
    assertTrue(issueSlot.captured.evidenceFactIdsJson.contains(newFactId))
    assertEquals("art2", issueSlot.captured.artifactId)
}
```

- [ ] **Step 2: Run them to verify they fail**

```bash
./gradlew :aura-core:testDebugUnitTest --offline --tests "com.aura.creative.longform.SceneLedgerTest"
```

Expected: the four new cases FAIL — `continuityIssueDao.upsert` was never called (Task 3 never inspects existing facts). The six from Task 3 still pass.

- [ ] **Step 3: Add conflict detection**

> **Two types share this name. Write the Room one.**
> `ContinuityIssue` (`WorldBible.kt:110`) is a `@Serializable` class inside
> `worldJson`, read by `CreativeEngine.buildNarrativeWorldContext` as "KNOWN
> CONTINUITY ISSUES" and today only ever user-authored — it stays that way, for
> the author's own notes. `ContinuityIssueEntity` (`CanonEntities.kt:117`) is
> the Room one: indexed, backed up, richer, never written. Everything in this
> task writes `ContinuityIssueEntity`. Writing to `world.continuityNotes`
> instead would compile, pass, and put machine-generated flags in the field the
> author types into.

In `SceneLedger`, replace the fact-writing block in `record`:

```kotlin
        if (facts.isNotEmpty()) {
            runCatching { canonFactDao.upsertAll(facts) }
                .onFailure { Log.w(TAG, "could not write canon facts: ${it.message}", it) }
        }
```

with:

```kotlin
        if (facts.isNotEmpty()) {
            runCatching { reconcile(project.id, branchId, artifactId, facts) }
                .onFailure { Log.w(TAG, "could not reconcile canon facts: ${it.message}", it) }
        }
```

and add these members to the class:

```kotlin
    /**
     * Write the new facts, and record where they disagree with what canon holds.
     *
     * Nothing here calls a model: a contradiction is a comparison of two triples.
     *
     * The new fact always wins and the old is superseded, so canon stays clean
     * and singular and a reader never has to decide which of two rows is
     * current. The disagreement is recorded *beside* canon rather than inside
     * it, which is the whole point — silently superseding absorbs every mistake
     * as though the character simply moved.
     */
    private suspend fun reconcile(
        projectId: String,
        branchId: String,
        artifactId: String,
        facts: List<CanonFactEntity>,
    ) {
        for (fact in facts) {
            if (fact.predicate !in SINGLE_VALUED) continue
            val existing = canonFactDao
                .forSubject(projectId, branchId, fact.subjectType, fact.subjectId)
                .filter { it.predicate == fact.predicate && it.status == "active" }
            for (old in existing) {
                if (old.valueJson == fact.valueJson) continue
                continuityIssueDao.upsert(
                    com.aura.creative.ContinuityIssueEntity(
                        id = UUID.randomUUID().toString(),
                        projectId = projectId,
                        branchId = branchId,
                        artifactId = artifactId,
                        category = fact.predicate,
                        severity = "warning",
                        message = "${fact.subjectId}: ${fact.predicate} was ${old.valueJson} " +
                            "and this scene says ${fact.valueJson}.",
                        evidenceFactIdsJson = json.encodeToString(
                            kotlinx.serialization.builtins.ListSerializer(String.serializer()),
                            listOf(old.id, fact.id),
                        ),
                        status = "open",
                    ),
                )
                canonFactDao.updateStatus(old.id, "superseded", System.currentTimeMillis())
            }
        }
        canonFactDao.upsertAll(facts)
    }
```

Add to the imports:

```kotlin
import kotlinx.serialization.builtins.serializer
```

Add to the companion object:

```kotlin
        /**
         * Predicates that cannot hold two values at once, and are therefore the
         * only ones where a different value is a contradiction rather than an
         * addition. Everything else — traits, allies, possessions, knowledge —
         * accumulates.
         *
         * Conservative on purpose. Pausing a run on a conflict was rejected
         * precisely because early false positives are likely, and a flag earns
         * trust by having its first ten be real. Tune this by reading real
         * flags, not by argument.
         */
        val SINGLE_VALUED = setOf("location", "age", "alive", "allegiance", "occupation", "rank")
```

- [ ] **Step 4: Run the tests to verify they pass**

```bash
./gradlew :aura-core:testDebugUnitTest --offline --tests "com.aura.creative.longform.SceneLedgerTest"
```

Expected: PASS, all ten cases.

- [ ] **Step 5: Commit**

```bash
git add aura-core/src/main/kotlin/com/aura/creative/longform/SceneLedger.kt \
        aura-core/src/test/kotlin/com/aura/creative/longform/SceneLedgerTest.kt
git commit -m "$(cat <<'EOF'
Record where the manuscript contradicts itself

Scene 5 puts Mira in Varn; scene 9 puts her in Kesh. Superseding silently is the
obvious behaviour and it absorbs every mistake as though the character simply
moved — canon could then never tell you the model lost track, which is the one
thing it is for.

The new fact wins and the old is superseded, so canon stays singular. The
disagreement is written beside it, to continuity_issues, which like canon_facts
has had a DAO and a backup mapper since it was written and no writer at all.

Only a fixed allowlist of single-valued predicates can conflict — location, age,
alive, allegiance, occupation, rank. Traits and allies accumulate. Conservative
because a flag earns trust by having its first ten be real, and this is meant to
be tuned by reading flags rather than by argument.

No model call: a contradiction is a comparison of two triples.

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 5: storySoFar, dropping from the oldest end

**Files:**
- Modify: `aura-core/src/main/kotlin/com/aura/creative/longform/SceneLedger.kt`
- Test: `aura-core/src/test/kotlin/com/aura/creative/longform/SceneLedgerTest.kt`

**Interfaces:**
- Consumes: `StoryBeat.synopsis` (Task 2), `SceneContextBuilder.SUMMARY_CAP` (Task 1).
- Produces: `SceneLedger.storySoFar(beats: List<StoryBeat>, beatIndex: Int): String` — Task 7 passes the result to `SceneContextBuilder.build`.

- [ ] **Step 1: Write the failing tests**

Append to `SceneLedgerTest`:

```kotlin
private fun draftedBeats(count: Int, synopsisChars: Int = 40) = (1..count).map { i ->
    StoryBeat(
        id = "b$i",
        title = "Beat $i",
        status = "drafted",
        synopsis = "S$i " + "z".repeat(synopsisChars),
    )
}

@Test
fun `story so far reads the beats before this one, in order`() {
    val beats = draftedBeats(4) + StoryBeat(id = "b5", title = "Beat 5")
    val text = ledger().storySoFar(beats, beatIndex = 4)

    assertTrue(text.indexOf("S1") < text.indexOf("S2"), "chronological order")
    assertTrue(text.indexOf("S3") < text.indexOf("S4"))
    assertTrue(!text.contains("S5"), "the beat being drafted is not part of the story so far")
}

@Test
fun `a beat with no synopsis is skipped rather than leaving a hole`() {
    val beats = listOf(
        StoryBeat(id = "b1", title = "Beat 1", status = "drafted", synopsis = "S1 happened"),
        StoryBeat(id = "b2", title = "Beat 2", status = "drafted", synopsis = ""),
        StoryBeat(id = "b3", title = "Beat 3", status = "drafted", synopsis = "S3 happened"),
        StoryBeat(id = "b4", title = "Beat 4"),
    )
    val text = ledger().storySoFar(beats, beatIndex = 3)

    assertTrue(text.contains("S1 happened"))
    assertTrue(text.contains("S3 happened"))
    assertTrue(!text.contains("Beat 2"))
}

/**
 * The direction is the point. `section()` applies `.take(cap)`, which truncates
 * the tail — so a book long enough to exceed the budget would keep scene one and
 * discard the scene just written, which is backwards for continuity.
 */
@Test
fun `over budget it keeps the most recent synopses and drops the oldest`() {
    val beats = draftedBeats(60, synopsisChars = 380) + StoryBeat(id = "last", title = "Last")
    val text = ledger().storySoFar(beats, beatIndex = 60)

    assertTrue(text.length <= SceneContextBuilder.SUMMARY_CAP, "length was ${text.length}")
    assertTrue(text.contains("S60 "), "the most recent synopsis must survive")
    assertTrue(!text.contains("S1 "), "the oldest is the one to drop")
    assertTrue(text.indexOf("S58") < text.indexOf("S59"), "what survives is still chronological")
}

@Test
fun `the first scene of a book has no story so far`() {
    assertEquals("", ledger().storySoFar(draftedBeats(3), beatIndex = 0))
}
```

- [ ] **Step 2: Run them to verify they fail**

```bash
./gradlew :aura-core:testDebugUnitTest --offline --tests "com.aura.creative.longform.SceneLedgerTest"
```

Expected: compilation failure — `Unresolved reference: storySoFar`.

- [ ] **Step 3: Implement storySoFar**

Add to `SceneLedger`:

```kotlin
    /**
     * Everything the book has established before the beat being drafted.
     *
     * **No model call.** Each synopsis was written once, by [record], when the
     * scene was fresh. Nothing re-summarises them. Rolling re-summarisation is
     * where a story-so-far drifts: every pass compounds the previous pass's
     * compression, and by scene twelve it is a summary of summaries with nothing
     * left to check it against.
     *
     * Budgeted here rather than by `section()`'s `.take()`, because `.take()`
     * truncates the tail — it would keep scene one and drop the scene just
     * written. Accumulates backwards from the current beat until the budget is
     * spent, then reverses, so the most recent scenes are the ones guaranteed to
     * survive. A book long enough to lose its early synopses still carries every
     * beat title in the OUTLINE section, which is what that section is for.
     */
    fun storySoFar(beats: List<StoryBeat>, beatIndex: Int): String {
        val kept = ArrayDeque<String>()
        var used = 0
        for (i in (beatIndex - 1) downTo 0) {
            val line = beats.getOrNull(i)?.synopsis?.trim().orEmpty()
            if (line.isEmpty()) continue
            val cost = line.length + 1
            if (used + cost > SceneContextBuilder.SUMMARY_CAP) break
            kept.addFirst(line)
            used += cost
        }
        return kept.joinToString("\n")
    }
```

Add the import:

```kotlin
import com.aura.creative.StoryBeat
```

- [ ] **Step 4: Run the tests to verify they pass**

```bash
./gradlew :aura-core:testDebugUnitTest --offline --tests "com.aura.creative.longform.SceneLedgerTest"
```

Expected: PASS, all fifteen cases.

- [ ] **Step 5: Commit**

```bash
git add aura-core/src/main/kotlin/com/aura/creative/longform/SceneLedger.kt \
        aura-core/src/test/kotlin/com/aura/creative/longform/SceneLedgerTest.kt
git commit -m "$(cat <<'EOF'
Assemble the story so far without re-summarising it

Concatenates the synopses record() already wrote, in chronological order, with
no model call. Each was written once when its scene was fresh and is never
compressed again — rolling re-summarisation is where a story-so-far drifts,
because every pass compounds the last pass's compression and by scene twelve it
is a summary of summaries with nothing left to check it against.

It budgets here rather than letting section() do it. section() applies
.take(cap), which truncates the tail: a book long enough to exceed the budget
would keep scene one and discard the scene just written. This accumulates
backwards from the current beat and reverses, so the newest survive.

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 6: Retrieval over the drafted manuscript

**Files:**
- Modify: `aura-core/src/main/kotlin/com/aura/creative/CreativeArtifactDao.kt` (`CreativeRevisionDao`)
- Modify: `aura-core/src/main/kotlin/com/aura/creative/longform/SceneLedger.kt`
- Test: `aura-core/src/test/kotlin/com/aura/creative/longform/SceneLedgerTest.kt`

**Interfaces:**
- Consumes: `StoryBeat.revisionId` (Task 2).
- Produces:
  - `CreativeRevisionDao.searchScenes(projectId: String, term: String, excludeRevisionId: String, limit: Int): List<CreativeRevisionEntity>`
  - `SceneLedger.retrieve(projectId: String, beats: List<StoryBeat>, beatIndex: Int): List<String>` — Task 7 passes the result to `SceneContextBuilder.build`.

- [ ] **Step 1: Write the failing tests**

Append to `SceneLedgerTest`:

```kotlin
private fun revision(id: String, text: String) = com.aura.creative.CreativeRevisionEntity(
    id = id,
    artifactId = "art-$id",
    branchId = "main",
    contentText = text,
)

@Test
fun `it searches the manuscript for the beat's distinctive words`() = runTest {
    val beats = listOf(
        StoryBeat(id = "b1", title = "Arrival", status = "drafted", artifactId = "a1", revisionId = "r1"),
        StoryBeat(id = "b2", title = "The lantern room", summary = "Mira climbs to the lantern"),
    )
    coEvery { revisionDao.searchScenes("p1", any(), any(), any()) } returns emptyList()
    coEvery { revisionDao.searchScenes("p1", "lantern", any(), any()) } returns
        listOf(revision("r1", "a".repeat(500) + "the lantern had not been lit" + "b".repeat(500)))

    val passages = ledger().retrieve("p1", beats, beatIndex = 1)

    assertEquals(1, passages.size)
    assertTrue(passages[0].contains("the lantern had not been lit"))
    assertTrue(passages[0].length <= SceneContextBuilder.RETRIEVED_ITEM_CAP)
}

/** Stopwords LIKE-match nearly every scene, which is noise rather than retrieval. */
@Test
fun `it never searches on a stopword`() = runTest {
    val beats = listOf(
        StoryBeat(id = "b1", title = "One", status = "drafted", artifactId = "a1", revisionId = "r1"),
        StoryBeat(id = "b2", title = "The and of it", summary = "with a to for"),
    )
    coEvery { revisionDao.searchScenes(any(), any(), any(), any()) } returns emptyList()

    ledger().retrieve("p1", beats, beatIndex = 1)

    coVerify(exactly = 0) { revisionDao.searchScenes(any(), "the", any(), any()) }
    coVerify(exactly = 0) { revisionDao.searchScenes(any(), "and", any(), any()) }
    coVerify(exactly = 0) { revisionDao.searchScenes(any(), "with", any(), any()) }
}

/**
 * The previous scene is already supplied verbatim and in full as
 * `previousSceneTail`. Letting it match here spends the retrieval budget
 * printing it a second time.
 */
@Test
fun `it excludes the immediately preceding scene`() = runTest {
    val beats = listOf(
        StoryBeat(id = "b1", title = "Arrival", status = "drafted", artifactId = "a1", revisionId = "r1"),
        StoryBeat(id = "b2", title = "Lantern", status = "drafted", artifactId = "a2", revisionId = "r2"),
        StoryBeat(id = "b3", title = "The lantern again", summary = "Mira returns"),
    )
    coEvery { revisionDao.searchScenes(any(), any(), any(), any()) } returns emptyList()

    ledger().retrieve("p1", beats, beatIndex = 2)

    coVerify { revisionDao.searchScenes("p1", any(), "r2", any()) }
}

@Test
fun `the first scene retrieves nothing and asks the database nothing`() = runTest {
    ledger().retrieve("p1", draftedBeats(3), beatIndex = 0)
    coVerify(exactly = 0) { revisionDao.searchScenes(any(), any(), any(), any()) }
}
```

- [ ] **Step 2: Run them to verify they fail**

```bash
./gradlew :aura-core:testDebugUnitTest --offline --tests "com.aura.creative.longform.SceneLedgerTest"
```

Expected: compilation failure — `Unresolved reference: searchScenes` and `retrieve`.

- [ ] **Step 3: Add the query**

In `CreativeArtifactDao.kt`, inside `interface CreativeRevisionDao`:

```kotlin
    /**
     * Current-revision text of this project's scenes containing [term].
     *
     * Joins on `currentRevisionId` rather than scanning every revision, so a
     * scene that has been revised five times contributes its current text once
     * and not six variants of itself.
     *
     * `LIKE '%term%'` cannot use an index and scans the scene rows. That is the
     * right trade at this scale — a long novel on one branch is forty rows —
     * and §3's Gate B records that the embedding business case for anything
     * cleverer is still unproven.
     */
    @Query(
        """
        SELECT r.* FROM creative_revisions r
        INNER JOIN creative_artifacts a ON a.currentRevisionId = r.id
        WHERE a.projectId = :projectId
          AND a.kind = 'scene'
          AND r.id != :excludeRevisionId
          AND r.contentText LIKE '%' || :term || '%'
        ORDER BY r.createdAt DESC
        LIMIT :limit
        """
    )
    suspend fun searchScenes(
        projectId: kotlin.String,
        term: kotlin.String,
        excludeRevisionId: kotlin.String,
        limit: kotlin.Int,
    ): List<CreativeRevisionEntity>
```

- [ ] **Step 4: Implement retrieve**

Add to `SceneLedger`:

```kotlin
    /**
     * What the manuscript itself already says about this beat.
     *
     * **No model call, no embeddings.** Terms come from the beat's own text —
     * title, summary, setting, pov — filtered through the shared [StopWords]
     * list retrieval already uses, because a query word like "the" LIKE-matches
     * every scene ever written and floods the result with noise.
     *
     * The immediately preceding scene is excluded: it is already supplied
     * verbatim and in full as `previousSceneTail`, and letting it match here
     * would spend the retrieval budget printing it a second time.
     */
    suspend fun retrieve(projectId: String, beats: List<StoryBeat>, beatIndex: Int): List<String> {
        if (beatIndex <= 0) return emptyList()
        val beat = beats.getOrNull(beatIndex) ?: return emptyList()
        val exclude = beats.getOrNull(beatIndex - 1)?.revisionId.orEmpty()

        val terms = "${beat.title} ${beat.summary} ${beat.setting} ${beat.pov}"
            .lowercase()
            .split(Regex("[^a-z0-9']+"))
            .filter { it.length >= MIN_TERM_LENGTH && it !in StopWords.ENGLISH }
            .distinct()
            .take(MAX_TERMS)
        if (terms.isEmpty()) return emptyList()

        val seen = LinkedHashMap<String, String>()
        for (term in terms) {
            if (seen.size >= MAX_PASSAGES) break
            val hits = runCatching { revisionDao.searchScenes(projectId, term, exclude, MAX_PASSAGES) }
                .onFailure { Log.w(TAG, "manuscript search failed for '$term': ${it.message}", it) }
                .getOrDefault(emptyList())
            for (hit in hits) {
                if (seen.size >= MAX_PASSAGES) break
                if (seen.containsKey(hit.id)) continue
                seen[hit.id] = window(hit.contentText, term)
            }
        }
        return seen.values.toList()
    }

    /**
     * The text around the match, not the scene.
     *
     * A scene is thousands of characters and the budget for all of retrieval is
     * [SceneContextBuilder.RETRIEVED_CAP]; returning whole scenes would spend it
     * on one hit and, worse, would grow with the manuscript — the property
     * `SceneContextBuilder` exists to preserve.
     */
    private fun window(content: String, term: String): String {
        val at = content.indexOf(term, ignoreCase = true)
        if (at < 0) return content.take(SceneContextBuilder.RETRIEVED_ITEM_CAP)
        val half = SceneContextBuilder.RETRIEVED_ITEM_CAP / 2
        val start = (at - half).coerceAtLeast(0)
        val end = (at + half).coerceAtMost(content.length)
        return content.substring(start, end).trim()
    }
```

Add the import:

```kotlin
import com.aura.core.util.StopWords
```

Add to the companion object:

```kotlin
        /** Below this, a word is noise even when it is not a stopword. */
        private const val MIN_TERM_LENGTH = 4

        /** Each term is one table scan; a beat does not need more than this. */
        private const val MAX_TERMS = 5

        /** Four fit `SceneContextBuilder.RETRIEVED_CAP` at the per-item cap. */
        private const val MAX_PASSAGES = 4
```

- [ ] **Step 5: Run the tests to verify they pass**

```bash
./gradlew :aura-core:testDebugUnitTest --offline --tests "com.aura.creative.longform.SceneLedgerTest"
```

Expected: PASS, all nineteen cases.

- [ ] **Step 6: Run both suites**

```bash
./gradlew :aura-core:testDebugUnitTest :app:testDebugUnitTest --offline
```

Expected: 0 failures. Adding a `@Query` to a DAO is compiled and verified by Room's annotation processor — a malformed SQL string fails the build, not a test.

- [ ] **Step 7: Commit**

```bash
git add aura-core/src/main/kotlin/com/aura/creative/CreativeArtifactDao.kt \
        aura-core/src/main/kotlin/com/aura/creative/longform/SceneLedger.kt \
        aura-core/src/test/kotlin/com/aura/creative/longform/SceneLedgerTest.kt
git commit -m "$(cat <<'EOF'
Let a scene look up what the manuscript already said

Lexical search over the current revision of this project's drafted scenes, for
the distinctive words of the beat being written — no model call and no
embeddings. Terms come from the beat's own text through the shared StopWords
list, because "the" LIKE-matches every scene ever written.

It returns a window around each match rather than the scene. Whole scenes would
spend the whole retrieval budget on one hit, and would grow with the manuscript
— the property SceneContextBuilder exists to preserve.

The immediately preceding scene is excluded: previousSceneTail already supplies
it verbatim and in full, so matching it here prints it twice.

LIKE cannot use an index and scans the scene rows. At forty rows for a long
novel that is the right trade, and Gate B records that the case for anything
cleverer is still unproven.

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 7: Wire the ledger into the runner

The task the whole plan exists for. After this, scene twelve has read scenes one through eleven.

**Files:**
- Modify: `aura-core/src/main/kotlin/com/aura/creative/longform/LongformRunner.kt`
- Test: `aura-core/src/test/kotlin/com/aura/creative/longform/LongformRunnerTest.kt`

**Interfaces:**
- Consumes: `SceneLedger.record`, `.storySoFar`, `.retrieve` (Tasks 3–6).
- Produces: `LongformRunner` constructor gains `sceneLedger: SceneLedger` as its **last** parameter.

- [ ] **Step 1: Write the failing test**

Append to `LongformRunnerTest`:

```kotlin
/**
 * The regression gate for the defect this whole change exists to fix.
 *
 * `storySoFar` and `retrieved` were defaulted parameters that no production
 * caller ever passed, so scene twelve saw the outline titles and the last 2,000
 * characters of scene eleven and had not read scenes one through ten. The only
 * places either was non-empty were two lines of SceneContextBuilderTest filling
 * them with "y".repeat(50_000) to prove the caps truncate.
 */
@Test
fun `drafting scene two sends the story so far`() = runTest {
    val messagesSlot = slot<List<com.aura.providers.ProviderMessage>>()
    setUpRun(
        listOf(
            StoryBeat(
                id = "b1", title = "Arrival", status = "drafted",
                artifactId = "art1", revisionId = "rev1",
                synopsis = "Mira reached the lighthouse and the keeper refused her.",
            ),
            StoryBeat(id = "b2", title = "The lantern room", summary = "Mira climbs"),
        ),
    )
    coEvery { brain.stream(any(), capture(messagesSlot), any(), any()) } returns
        flowOf(BrainChunk.Text("x".repeat(600)))
    coEvery { projectStore.updateWorld(any(), any()) } returns null

    runner().runSlice("j1", deadlineMs = Long.MAX_VALUE, isStopped = { false })

    val system = messagesSlot.captured.first { it.role == com.aura.providers.ProviderMessage.Role.system }
    assertTrue(system.content.contains("== STORY SO FAR =="), system.content)
    assertTrue(system.content.contains("the keeper refused her"))
}

/** A committed scene is handed to the ledger, and the ledger's failure is not the scene's. */
@Test
fun `it records each committed scene and survives the ledger failing`() = runTest {
    setUpRun(beats(1))
    coEvery { projectStore.updateWorld(any(), any()) } returns null
    coEvery { sceneLedger.record(any(), any(), any(), any(), any(), any(), any()) } throws
        IllegalStateException("extraction blew up")

    val outcome = runner().runSlice("j1", deadlineMs = Long.MAX_VALUE, isStopped = { false })

    assertEquals(LongformOutcome.COMPLETED, outcome)
    coVerify(exactly = 1) { sceneLedger.record(any(), any(), 0, any(), any(), any(), any()) }
}
```

Also add the mock and pass it in the existing helpers:

```kotlin
    private val sceneLedger = mockk<SceneLedger>(relaxed = true)
```

and add `sceneLedger = sceneLedger,` as the last argument of `runner()`.

- [ ] **Step 2: Run it to verify it fails**

```bash
./gradlew :aura-core:testDebugUnitTest --offline --tests "com.aura.creative.longform.LongformRunnerTest"
```

Expected: compilation failure — `LongformRunner` has no `sceneLedger` parameter.

- [ ] **Step 3: Add the dependency**

In `LongformRunner`'s constructor, **append** after `modelRoleRouter`:

```kotlin
    // Appended, not inserted. LongformRunnerTest constructs this by name, but
    // the ProactiveBootstrap KDoc's rule holds regardless: a parameter added
    // mid-list silently re-binds every positional argument after it.
    private val sceneLedger: SceneLedger,
```

- [ ] **Step 4: Supply the two context sections**

In `draftScene`, replace:

```kotlin
        val context = contextBuilder.build(
            project = project,
            beats = beats,
            beatIndex = index,
            previousSceneTail = previousTail,
        )
```

with:

```kotlin
        val context = contextBuilder.build(
            project = project,
            beats = beats,
            beatIndex = index,
            previousSceneTail = previousTail,
            storySoFar = sceneLedger.storySoFar(beats, index),
            retrieved = runCatching { sceneLedger.retrieve(project.id, beats, index) }
                .onFailure { Log.w(TAG, "manuscript retrieval failed: ${it.message}", it) }
                .getOrDefault(emptyList()),
        )
```

- [ ] **Step 5: Record the committed scene**

In `draftScene`, replace the final two lines:

```kotlin
        progressBus.clear()
        return artifactId != null
```

with:

```kotlin
        progressBus.clear()
        val (artifactId, revisionId) = committed ?: return false

        // After the commit, and outside its NonCancellable block. A scene that
        // has been generated, streamed and paid for must never be put at risk by
        // a bookkeeping call — the same reasoning the commit block above
        // records. The cost is that a failure here leaves a blank synopsis,
        // which backFill clears on a later slice.
        runCatching {
            sceneLedger.record(
                project = project,
                branchId = beatBranch(jobId),
                beatIndex = index,
                artifactId = artifactId,
                revisionId = revisionId,
                sceneText = body,
                sceneModel = model,
            )
        }.onFailure { Log.w(TAG, "could not record scene ${index + 1}: ${it.message}", it) }
        return true
```

The variable this replaces is named `artifactId` in the current source; the destructuring above reuses that name, so no other line in `draftScene` needs touching.

The `withContext(NonCancellable)` block currently returns just the artifact id, so `revisionId` is not in scope at the `record` call. Change the block to return both. Replace:

```kotlin
        val artifactId = withContext(NonCancellable) {
            runCatching {
```

with:

```kotlin
        val committed: Pair<String, String>? = withContext(NonCancellable) {
            runCatching {
```

and the block's final expression, currently:

```kotlin
                projectStore.updateWorld(project.id, project.world.copy(outline = updated))
                id
            }.onFailure { Log.w(TAG, "could not persist scene ${index + 1}: ${it.message}", it) }.getOrNull()
        }
```

with:

```kotlin
                projectStore.updateWorld(project.id, project.world.copy(outline = updated))
                id to artifact.currentRevisionId.orEmpty()
            }.onFailure { Log.w(TAG, "could not persist scene ${index + 1}: ${it.message}", it) }.getOrNull()
        }
```

Then the tail of `draftScene` reads:

```kotlin
        progressBus.clear()
        val (artifactId, revisionId) = committed ?: return false
```

followed by the `runCatching { sceneLedger.record(...) }` block from the previous step, with `revisionId = revisionId` (drop the `.orEmpty()` there — it is already a `String`).

- [ ] **Step 6: Run the tests to verify they pass**

```bash
./gradlew :aura-core:testDebugUnitTest --offline --tests "com.aura.creative.longform.LongformRunnerTest"
```

Expected: PASS, including every pre-existing case.

- [ ] **Step 7: Run both suites**

```bash
./gradlew :aura-core:testDebugUnitTest :app:testDebugUnitTest --offline
```

Expected: 0 failures.

- [ ] **Step 8: Commit**

```bash
git add aura-core/src/main/kotlin/com/aura/creative/longform/LongformRunner.kt \
        aura-core/src/test/kotlin/com/aura/creative/longform/LongformRunnerTest.kt
git commit -m "$(cat <<'EOF'
Draft scene twelve having read scenes one through eleven

SceneContextBuilder documents an eight-section budget. This is the commit where
it finally gets eight: storySoFar and retrieved were defaulted parameters that
no production caller passed, and section() returns "" for an empty body, so the
headings never appeared and nothing looked wrong.

The only places either was ever non-empty were two lines of
SceneContextBuilderTest filling them with "y".repeat(50_000) to prove the caps
truncate. The caps were tested; the content had never arrived. That is why the
new assertion is that drafting scene two *sends* the story so far.

record() runs after the commit and outside its NonCancellable block, wrapped so
a bookkeeping failure can never cost a scene that was generated, streamed and
paid for. The price is a blank synopsis, which back-fill clears.

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 8: Back-fill blank synopses at the start of a slice

Makes the ledger self-healing, and is the migration path for every scene drafted before this change.

**Files:**
- Modify: `aura-core/src/main/kotlin/com/aura/creative/longform/SceneLedger.kt`
- Modify: `aura-core/src/main/kotlin/com/aura/creative/longform/LongformRunner.kt`
- Test: `aura-core/src/test/kotlin/com/aura/creative/longform/SceneLedgerTest.kt`

**Interfaces:**
- Consumes: `SceneLedger.record` (Task 3), `LongformRunner`'s `sceneLedger` field (Task 7).
- Produces: `SceneLedger.backFill(project: CreativeProject, branchId: String, sceneModel: String): Int` — returns how many were filled.

- [ ] **Step 1: Write the failing tests**

Append to `SceneLedgerTest`:

```kotlin
@Test
fun `it fills a drafted beat whose synopsis is blank`() = runTest {
    stubModel(goodReply)
    val beats = listOf(
        StoryBeat(id = "b1", title = "One", status = "drafted", artifactId = "a1", revisionId = "r1", synopsis = ""),
        StoryBeat(id = "b2", title = "Two", status = "drafted", artifactId = "a2", revisionId = "r2", synopsis = "already there"),
    )
    coEvery { projectStore.get("p1") } returns project(beats)
    coEvery { projectStore.updateWorld(any(), any()) } returns null
    coEvery { artifactStore.currentContent("a1") } returns "x".repeat(600)

    val filled = ledger().backFill(project(beats), "main", "openai:gpt-4o")

    assertEquals(1, filled)
    coVerify(exactly = 0) { artifactStore.currentContent("a2") }
}

/**
 * A persistently failing extraction must not consume the drafting window it
 * exists to support — the same reasoning as MAX_SCENE_ATTEMPTS.
 */
@Test
fun `back-fill is capped so a broken extraction cannot eat the slice`() = runTest {
    stubModel(goodReply)
    val beats = (1..10).map {
        StoryBeat(id = "b$it", title = "B$it", status = "drafted", artifactId = "a$it", revisionId = "r$it")
    }
    coEvery { projectStore.get("p1") } returns project(beats)
    coEvery { projectStore.updateWorld(any(), any()) } returns null
    coEvery { artifactStore.currentContent(any()) } returns "x".repeat(600)

    val filled = ledger().backFill(project(beats), "main", "openai:gpt-4o")

    assertTrue(filled <= SceneLedger.MAX_BACKFILL_PER_SLICE, "filled $filled")
}

@Test
fun `a beat with no stored text is skipped rather than retried forever`() = runTest {
    stubModel(goodReply)
    val beats = listOf(
        StoryBeat(id = "b1", title = "One", status = "drafted", artifactId = "a1", revisionId = "r1"),
    )
    coEvery { projectStore.get("p1") } returns project(beats)
    coEvery { artifactStore.currentContent("a1") } returns null

    assertEquals(0, ledger().backFill(project(beats), "main", "openai:gpt-4o"))
}

@Test
fun `an undrafted beat is never back-filled`() = runTest {
    stubModel(goodReply)
    val beats = listOf(StoryBeat(id = "b1", title = "One", status = "planned"))
    coEvery { projectStore.get("p1") } returns project(beats)

    assertEquals(0, ledger().backFill(project(beats), "main", "openai:gpt-4o"))
    coVerify(exactly = 0) { artifactStore.currentContent(any()) }
}
```

- [ ] **Step 2: Run them to verify they fail**

```bash
./gradlew :aura-core:testDebugUnitTest --offline --tests "com.aura.creative.longform.SceneLedgerTest"
```

Expected: compilation failure — `Unresolved reference: backFill`.

- [ ] **Step 3: Implement backFill**

Add to `SceneLedger`:

```kotlin
    /**
     * Fill in synopses for scenes that were committed without one.
     *
     * Two populations need this and both matter. A scene whose extraction failed
     * — the deliberate consequence of running [record] outside the commit's
     * `NonCancellable` block — and every scene drafted before this class
     * existed, which is every scene in every existing project. Neither should
     * stay permanently invisible to the scene after it.
     *
     * Capped per slice, for the reason `MAX_SCENE_ATTEMPTS` exists: a
     * persistently failing extraction must not consume the drafting window it is
     * there to support.
     *
     * @return how many were filled.
     */
    suspend fun backFill(
        project: CreativeProject,
        branchId: String,
        sceneModel: String,
    ): Int {
        var filled = 0
        val beats = project.world.outline
        for ((index, beat) in beats.withIndex()) {
            if (filled >= MAX_BACKFILL_PER_SLICE) break
            if (beat.status != STATUS_DRAFTED) continue
            if (beat.synopsis.isNotBlank()) continue
            if (beat.artifactId.isBlank()) continue

            val text = runCatching { artifactStore.currentContent(beat.artifactId) }
                .onFailure { Log.w(TAG, "could not read scene ${index + 1} to back-fill: ${it.message}", it) }
                .getOrNull()
            if (text.isNullOrBlank()) continue

            // Re-read inside the loop: record() writes worldJson, so the snapshot
            // this loop started with is stale after the first success.
            val current = runCatching { projectStore.get(project.id) }
                .onFailure { Log.w(TAG, "could not re-read the project: ${it.message}", it) }
                .getOrNull() ?: break

            val ok = record(
                project = current,
                branchId = branchId,
                beatIndex = index,
                artifactId = beat.artifactId,
                revisionId = beat.revisionId,
                sceneText = text,
                sceneModel = sceneModel,
            )
            if (ok) filled++ else break
        }
        return filled
    }
```

Add to the companion object:

```kotlin
        /** Bounds a broken extraction's cost per slice. */
        const val MAX_BACKFILL_PER_SLICE = 3

        private const val STATUS_DRAFTED = "drafted"
```

- [ ] **Step 4: Call it from the runner**

In `LongformRunner.runSlice`, immediately after the `beats.isEmpty()` guard and before the cancellation check, add:

```kotlin
            // Heal any scene committed without a synopsis before drafting the
            // next one, so the context this slice assembles is as complete as
            // the manuscript allows. Bounded inside the ledger.
            if (scenesThisSlice == 0) {
                runCatching { sceneLedger.backFill(project, beatBranch(jobId), model) }
                    .onFailure { Log.w(TAG, "back-fill failed: ${it.message}", it) }
            }
```

The `scenesThisSlice == 0` guard runs it once per slice rather than once per beat.

- [ ] **Step 5: Run the tests to verify they pass**

```bash
./gradlew :aura-core:testDebugUnitTest --offline --tests "com.aura.creative.longform.SceneLedgerTest" --tests "com.aura.creative.longform.LongformRunnerTest"
```

Expected: PASS, all cases in both classes.

- [ ] **Step 6: Commit**

```bash
git add aura-core/src/main/kotlin/com/aura/creative/longform/SceneLedger.kt \
        aura-core/src/main/kotlin/com/aura/creative/longform/LongformRunner.kt \
        aura-core/src/test/kotlin/com/aura/creative/longform/SceneLedgerTest.kt
git commit -m "$(cat <<'EOF'
Heal the scenes that were committed without a synopsis

Two populations need this. A scene whose extraction failed — the deliberate
consequence of running record() outside the commit's NonCancellable block, so a
bookkeeping call can never cost a scene already paid for. And every scene
drafted before this class existed, which is every scene in every existing
project. Neither should stay permanently invisible to the scene after it.

So this is also the migration, and there is no Room migration to write: an old
project heals on its next run.

Capped at three per slice, for the reason MAX_SCENE_ATTEMPTS exists — a
persistently failing extraction must not consume the drafting window it is there
to support.

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 9: canon_query reads canon

Independent of everything above, and small. The tool has never returned a canon fact.

**Files:**
- Modify: `aura-core/src/main/kotlin/com/aura/tools/CanonQueryTool.kt`
- Test: `aura-core/src/test/kotlin/com/aura/tools/CanonQueryToolTest.kt` (create)

**Interfaces:**
- Consumes: `CanonFactDao` (existing), facts written by Task 3.
- Produces: nothing other tasks depend on.

- [ ] **Step 1: Write the failing test**

Create `aura-core/src/test/kotlin/com/aura/tools/CanonQueryToolTest.kt`:

```kotlin
package com.aura.tools

import com.aura.agent.ToolCall
import com.aura.agent.ToolResult
import com.aura.creative.CanonFactDao
import com.aura.creative.CanonFactEntity
import com.aura.creative.CreativeBranchStore
import com.aura.creative.CreativeProject
import com.aura.creative.CreativeProjectStore
import com.aura.creative.WorldBible
import com.aura.memory.MemoryStore
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertTrue

/**
 * The tool ran `memoryStore.query("$question project:$projectId")` against the
 * user's *personal* memory store. `project:` is not a scope filter — it is
 * literal text inside a BM25 query, so it added noise rather than scoping — and
 * the four canon tables the tool is named after have never held a row.
 */
class CanonQueryToolTest {

    private val memoryStore = mockk<MemoryStore>(relaxed = true)
    private val projectStore = mockk<CreativeProjectStore>(relaxed = true)
    private val branchStore = mockk<CreativeBranchStore>(relaxed = true)
    private val canonFactDao = mockk<CanonFactDao>(relaxed = true)

    private fun tool() = CanonQueryTool(memoryStore, projectStore, branchStore, canonFactDao).tool

    private fun project() = CreativeProject(
        id = "p1", name = "The Lighthouse", description = "", genre = "", tone = "",
        world = WorldBible(), templateId = "novel", turnCount = 0, createdAt = 0L, updatedAt = 0L,
    )

    private fun mainBranch() = CreativeBranchEntity(id = "main", projectId = "p1", name = "main")

    /** `Tool.execute` is `suspend (ToolCall, ToolContext) -> ToolResult`; neither is nullable. */
    private fun call(vararg pairs: Pair<String, Any?>) =
        ToolCall(id = "tc1", name = "canon_query", arguments = mapOf(*pairs))

    private fun ctx() = ToolContext(conversationId = "conv-1")

    @Test
    fun `it answers from canon and never touches personal memory`() = runTest {
        coEvery { projectStore.get("p1") } returns project()
        coEvery { branchStore.createMainBranch("p1") } returns mainBranch()
        coEvery { canonFactDao.activeForBranch("p1", "main") } returns listOf(
            CanonFactEntity(
                id = "f1", projectId = "p1", branchId = "main",
                subjectType = "character", subjectId = "Mira",
                predicate = "location", valueJson = "\"the lighthouse\"",
            ),
        )

        val result = tool().execute(
            call("projectId" to "p1", "question" to "where is Mira"),
            ctx(),
        )

        assertTrue(result is ToolResult.Ok)
        assertTrue((result as ToolResult.Ok).output.contains("Mira"))
        assertTrue(result.output.contains("the lighthouse"))
        coVerify(exactly = 0) { memoryStore.query(any(), any()) }
    }

    @Test
    fun `an empty canon says so rather than inventing an answer`() = runTest {
        coEvery { projectStore.get("p1") } returns project()
        coEvery { branchStore.createMainBranch("p1") } returns mainBranch()
        coEvery { canonFactDao.activeForBranch("p1", "main") } returns emptyList()

        val result = tool().execute(
            call("projectId" to "p1", "question" to "where is Mira"),
            ctx(),
        )

        assertTrue((result as ToolResult.Ok).output.contains("No canon"))
    }
}
```

Add these imports alongside the others: `com.aura.agent.ToolContext`, `com.aura.creative.CreativeBranchEntity`. `BraveSearchToolTest` is the house pattern for `call()`/`ctx()` helpers if anything here does not line up.

- [ ] **Step 2: Run it to verify it fails**

```bash
./gradlew :aura-core:testDebugUnitTest --offline --tests "com.aura.tools.CanonQueryToolTest"
```

Expected: compilation failure — `CanonQueryTool` takes two constructor parameters, not four.

- [ ] **Step 3: Repoint the tool**

Replace the body of `CanonQueryTool.kt` below the imports:

```kotlin
/**
 * Answer a question from a creative project's recorded canon.
 *
 * This used to run `memoryStore.query("$question project:$projectId")` against
 * the user's *personal* memory store — the one holding facts about their life.
 * `project:` is not a scope filter; it is literal text inside a BM25 query, so
 * it contributed noise rather than scoping. Meanwhile the canon tables the tool
 * is named after had no writer at all, so there was nothing to read either way.
 * `SceneLedger` is now that writer.
 */
@Singleton
class CanonQueryTool @Inject constructor(
    private val memoryStore: MemoryStore,
    private val projectStore: CreativeProjectStore,
    private val branchStore: CreativeBranchStore,
    private val canonFactDao: CanonFactDao,
) {
    fun definition() = ToolDefinition(
        name = "canon_query",
        description = "Look up what is established in a creative project's canon — where a " +
            "character is, who they serve, what a rule of the world says. Returns recorded facts, " +
            "not guesses.",
        parameters = ToolParameters(
            properties = mapOf(
                "projectId" to ToolProperty("string", "Creative project ID"),
                "question" to ToolProperty("string", "Question about canon, plot, characters, or world rules"),
            ),
            required = listOf("projectId", "question"),
        ),
    )

    val tool = Tool(
        name = definition().name,
        description = definition().description,
        risk = ToolRisk.READ_ONLY,
        parameters = definition().parameters,
        category = "creative",
        execute = { call, _ ->
            val projectId = call.arguments["projectId"] as? String
                ?: return@Tool ToolResult.Error("missing 'projectId'", "bad_args")
            val question = call.arguments["question"] as? String
                ?: return@Tool ToolResult.Error("missing 'question'", "bad_args")
            projectStore.get(projectId)
                ?: return@Tool ToolResult.Error("Project not found", "not_found")

            val branchId = branchStore.createMainBranch(projectId).id
            val facts = canonFactDao.activeForBranch(projectId, branchId)
            // Terms rather than a ranked search: canon is tens of rows, not
            // thousands, and a subject-name match is what the question is
            // almost always about. Ranking this would be machinery over noise.
            val terms = question.lowercase()
                .split(Regex("[^a-z0-9']+"))
                .filter { it.length >= 3 && it !in StopWords.ENGLISH }
            val matched = facts.filter { fact ->
                terms.isEmpty() || terms.any {
                    fact.subjectId.lowercase().contains(it) ||
                        fact.predicate.contains(it) ||
                        fact.valueJson.lowercase().contains(it)
                }
            }.ifEmpty { facts }

            val output = buildString {
                appendLine("Canon for: $question")
                if (matched.isEmpty()) {
                    appendLine("No canon recorded for this project yet. Canon is written as scenes are drafted.")
                } else {
                    matched.take(MAX_FACTS).forEach {
                        appendLine("- ${it.subjectId} (${it.subjectType}) ${it.predicate}: ${it.valueJson.trim('"')}")
                    }
                }
            }
            ToolResult.Ok(output.trim())
        },
    )

    private companion object {
        /** A tool result is truncated at 4,000 chars upstream; this stays well under. */
        const val MAX_FACTS = 40
    }
}
```

Update the imports to add `com.aura.creative.CanonFactDao`, `com.aura.creative.CreativeBranchStore` and `com.aura.core.util.StopWords`. Leave `MemoryStore` injected — removing it changes the Hilt graph for no benefit, and the field is retained deliberately so a later pass can add a memory tier without re-plumbing. If a dead-field audit test objects, remove the parameter and drop it from the test's constructor too.

- [ ] **Step 4: Run the test to verify it passes**

```bash
./gradlew :aura-core:testDebugUnitTest --offline --tests "com.aura.tools.CanonQueryToolTest"
```

Expected: PASS, both cases.

- [ ] **Step 5: Run both suites**

```bash
./gradlew :aura-core:testDebugUnitTest :app:testDebugUnitTest --offline
```

Expected: 0 failures. `ToolsModule` constructs `CanonQueryTool`; if the tool count changes, `check-version-docs.sh` will fail in Task 11 — it should not, since this adds no tool.

- [ ] **Step 6: Commit**

```bash
git add aura-core/src/main/kotlin/com/aura/tools/CanonQueryTool.kt \
        aura-core/src/test/kotlin/com/aura/tools/CanonQueryToolTest.kt
git commit -m "$(cat <<'EOF'
Make canon_query query canon

It ran memoryStore.query("$question project:$projectId") against the user's
personal memory store — the one holding facts about their life. "project:" is
not a scope filter; it is literal text inside a BM25 query, so it added noise
rather than scoping.

The four canon tables the tool is named after had no writer at all, so there was
nothing to read either way. SceneLedger is now that writer, and this is the
reader.

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 10: The canon card in the Manuscript tab

**Files:**
- Modify: `app/src/main/kotlin/com/aura/ui/viewmodel/CreativeStudioViewModel.kt`
- Modify: `app/src/main/kotlin/com/aura/ui/screens/creative/CreativeProjectScreen.kt`
- Test: `app/src/test/kotlin/com/aura/ui/viewmodel/CreativeStudioCanonTest.kt` (create)

**Interfaces:**
- Consumes: `ContinuityIssueDao.observeOpen`, `ContinuityIssueDao.resolve`, `CanonFactDao.activeForBranch`.
- Produces: nothing other tasks depend on.

> **Two existing tests construct this ViewModel positionally with all 21
> arguments** — `CreativeStudioViewModelTest.newViewModel()` and
> `LongformPlanningTest`. Appending two parameters breaks both at compile time,
> which is the intended and safe failure (the constructor's own comment records
> why appending beats inserting). Fix them by appending two more
> `mockk(relaxed = true)` arguments to each call. The **new** test below uses
> named arguments instead, so it never has to be counted again.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/kotlin/com/aura/ui/viewmodel/CreativeStudioCanonTest.kt`:

```kotlin
package com.aura.ui.viewmodel

import com.aura.creative.CanonFactDao
import com.aura.creative.CanonFactEntity
import com.aura.creative.ContinuityIssueDao
import com.aura.creative.ContinuityIssueEntity
import com.aura.creative.CreativeBranchStore
import com.aura.creative.CreativeProject
import com.aura.creative.CreativeProjectStore
import com.aura.creative.WorldBible
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The card exists so a contradiction is something the author sees rather than
 * something canon absorbed. A flag that never reaches the UI state is the same
 * as no flag.
 *
 * Constructed with **named** arguments, unlike its two neighbours. They pass 21
 * positional mocks, which means every future parameter is a counting exercise
 * with a silent-rebinding failure mode at the end of it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CreativeStudioCanonTest {
    private val dispatcher = StandardTestDispatcher()
    private val store = mockk<CreativeProjectStore>(relaxed = true)
    private val branchStore = mockk<CreativeBranchStore>(relaxed = true)
    private val canonFactDao = mockk<CanonFactDao>(relaxed = true)
    private val continuityIssueDao = mockk<ContinuityIssueDao>(relaxed = true)

    private val project = CreativeProject(
        "p1", "Glass City", "", "fantasy", "haunting", WorldBible(overview = "Glass remembers"),
        "novel", 0, 1L, 1L,
    )

    private fun fact(id: String) = CanonFactEntity(
        id = id, projectId = "p1", branchId = "main",
        subjectType = "character", subjectId = "Mira",
        predicate = "location", valueJson = "\"Varn\"",
    )

    private fun issue(id: String) = ContinuityIssueEntity(
        id = id, projectId = "p1", branchId = "main",
        artifactId = "art9", category = "location", severity = "warning",
        message = "Mira: location was \"Varn\" and this scene says \"Kesh\".",
        status = "open",
    )

    private fun newViewModel() = CreativeStudioViewModel(
        store = store,
        engine = mockk(relaxed = true),
        council = mockk(relaxed = true),
        providerRegistry = mockk(relaxed = true),
        capabilityRouter = mockk(relaxed = true),
        modelRoleRouter = mockk(relaxed = true),
        proseCraftTools = mockk(relaxed = true),
        voiceCalibration = mockk(relaxed = true),
        tensionAnalyzer = mockk(relaxed = true),
        progressionTracker = mockk(relaxed = true),
        artifactStore = mockk(relaxed = true),
        branchStore = branchStore,
        brain = mockk(relaxed = true),
        longformRunStore = mockk(relaxed = true),
        longformProgressBus = com.aura.creative.longform.LongformProgressBus(),
        livingWorldStore = mockk(relaxed = true),
        worldSeeder = com.aura.creative.livingworld.WorldSeeder(),
        worldTickBus = com.aura.creative.livingworld.WorldTickBus(),
        worldNarrator = mockk(relaxed = true),
        appContext = mockk(relaxed = true),
        creativeAnalysisStore = mockk(relaxed = true),
        canonFactDao = canonFactDao,
        continuityIssueDao = continuityIssueDao,
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        every { store.observeAll() } returns flowOf(listOf(project))
        coEvery { store.get("p1") } returns project
        coEvery { branchStore.createMainBranch("p1") } returns
            com.aura.creative.CreativeBranchEntity(id = "main", projectId = "p1", name = "main")
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `open conflicts and the fact count reach the ui state`() = runTest {
        coEvery { canonFactDao.activeForBranch("p1", "main") } returns listOf(fact("f1"), fact("f2"))
        every { continuityIssueDao.observeOpen("p1", "main") } returns flowOf(listOf(issue("i1")))

        val vm = newViewModel()
        vm.loadProject("p1")
        advanceUntilIdle()

        assertEquals(2, vm.state.value.canonFactCount)
        assertEquals(1, vm.state.value.openConflicts.size)
        assertTrue(vm.state.value.openConflicts.first().message.contains("Mira"))
    }

    /**
     * `intentional_exception`, not `dismissed`. The schema distinguishes them,
     * and "the author meant this" is a different fact from "the author is not
     * interested" the next time the same pair is compared.
     */
    @Test
    fun `dismissing a conflict resolves it as intentional`() = runTest {
        coEvery { canonFactDao.activeForBranch(any(), any()) } returns emptyList()
        every { continuityIssueDao.observeOpen(any(), any()) } returns flowOf(emptyList())

        val vm = newViewModel()
        vm.loadProject("p1")
        advanceUntilIdle()
        vm.dismissConflict("i1")
        advanceUntilIdle()

        coVerify { continuityIssueDao.resolve("i1", "intentional_exception", any(), "user") }
    }
}
```

`createMainBranch` returns a concrete `CreativeBranchEntity`, so the stub returns a real one rather than a nested mock.

- [ ] **Step 2: Run it to verify it fails**

```bash
./gradlew :app:testDebugUnitTest --offline --tests "com.aura.ui.viewmodel.CreativeStudioCanonTest"
```

Expected: FAIL — `canonFactCount` and `openConflicts` do not exist on the UI state.

- [ ] **Step 3: Add state and actions to the ViewModel**

Add to `CreativeStudioUiState`:

```kotlin
    /** Facts `SceneLedger` has recorded for the open project's active branch. */
    val canonFactCount: Int = 0,
    /** Contradictions the ledger flagged and nobody has judged yet. */
    val openConflicts: List<com.aura.creative.ContinuityIssueEntity> = emptyList(),
```

Add two injected dependencies to `CreativeStudioViewModel`'s constructor — appended, not inserted, for the reason its own comment at line 228 already gives:

```kotlin
    private val canonFactDao: com.aura.creative.CanonFactDao,
    private val continuityIssueDao: com.aura.creative.ContinuityIssueDao,
```

Add an observer, called from `loadProject` alongside `observeLongform`:

```kotlin
    /**
     * Canon and its open disagreements for [projectId].
     *
     * `observeOpen` is already a Flow, so a conflict flagged by a background
     * drafting run appears without the screen polling or being reopened.
     */
    private fun observeCanon(projectId: String) {
        canonJob?.cancel()
        canonJob = viewModelScope.launch {
            val branchId = runCatching { branchStore.createMainBranch(projectId).id }
                .onFailure { android.util.Log.w("CreativeVM", "branch resolve failed: ${it.message}", it) }
                .getOrNull() ?: return@launch
            val facts = runCatching { canonFactDao.activeForBranch(projectId, branchId) }
                .onFailure { android.util.Log.w("CreativeVM", "canon read failed: ${it.message}", it) }
                .getOrDefault(emptyList())
            _state.update { it.copy(canonFactCount = facts.size) }
            continuityIssueDao.observeOpen(projectId, branchId).collect { issues ->
                _state.update { it.copy(openConflicts = issues) }
            }
        }
    }

    /**
     * Mark a flagged contradiction as deliberate.
     *
     * `intentional_exception` rather than `dismissed`: the schema distinguishes
     * them, and "the author meant this" is a different fact from "the author is
     * not interested", which matters the next time the same pair is compared.
     */
    fun dismissConflict(issueId: String) {
        viewModelScope.launch {
            runCatching {
                continuityIssueDao.resolve(issueId, "intentional_exception", System.currentTimeMillis(), "user")
            }.onFailure { error ->
                _state.update { it.copy(error = error.message ?: "Could not dismiss the flag.") }
            }
        }
    }
```

Declare `private var canonJob: Job? = null` beside the existing job fields — **above** `init`, not below it, for the reason commit `719ae507` records: Kotlin runs property initialisers in declaration order, so a field declared after `init` is still null when a coroutine launched in `init` assigns to it.

- [ ] **Step 4: Add the card**

In `CreativeProjectScreen.kt`, inside `manuscriptSection`, after the `manuscript-progress` item:

```kotlin
    if (state.canonFactCount > 0 || state.openConflicts.isNotEmpty()) {
        item(key = "manuscript-canon") {
            ManuscriptCard(title = "Canon") {
                Text(
                    "${state.canonFactCount} facts recorded from the scenes so far.",
                    style = MaterialTheme.typography.bodySmall,
                    color = AuraThemeTokens.colors.textSecondary,
                )
                state.openConflicts.forEach { issue ->
                    Row(
                        Modifier.fillMaxWidth().padding(top = AuraSpacing.sm),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            issue.message,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { viewModel.dismissConflict(issue.id) }) {
                            Text("Intentional")
                        }
                    }
                }
            }
        }
    }
```

- [ ] **Step 5: Repair the two positional constructors**

Appending the two parameters breaks `CreativeStudioViewModelTest.newViewModel()` and `LongformPlanningTest`, which pass all 21 arguments positionally. Append two more arguments to each call:

```kotlin
        mockk(relaxed = true),
        // canonFactDao, continuityIssueDao — these tests drive the generation
        // state machine, not the canon card.
        mockk(relaxed = true),
```

Do not reorder anything. A parameter inserted mid-list re-binds every positional argument after it, which is the failure both that constructor's comment and `ProactiveBootstrap`'s record.

- [ ] **Step 6: Run the tests to verify they pass**

```bash
./gradlew :app:testDebugUnitTest --offline --tests "com.aura.ui.viewmodel.CreativeStudioCanonTest" --tests "com.aura.ui.viewmodel.CreativeStudioViewModelTest"
```

Expected: PASS, all cases in both classes.

- [ ] **Step 7: Run both suites**

```bash
./gradlew :aura-core:testDebugUnitTest :app:testDebugUnitTest --offline
```

Expected: 0 failures.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/kotlin/com/aura/ui/viewmodel/CreativeStudioViewModel.kt \
        app/src/main/kotlin/com/aura/ui/screens/creative/CreativeProjectScreen.kt \
        app/src/test/kotlin/com/aura/ui/viewmodel/CreativeStudioCanonTest.kt \
        app/src/test/kotlin/com/aura/ui/viewmodel/CreativeStudioViewModelTest.kt \
        app/src/test/kotlin/com/aura/ui/viewmodel/LongformPlanningTest.kt
git commit -m "$(cat <<'EOF'
Show the author what canon holds, and where it disagrees

One card in the Manuscript tab: how many facts the scenes have established, and
any contradiction the ledger flagged, dismissible as intentional. A flag nobody
can see is the same as no flag.

No new route and no new tab. CreativeProjectScreen already scrolls its tab row
at eight, and a route would move the NAV_DESTINATIONS and SECONDARY_ROUTES
counts check-version-docs.sh gates.

Dismissal writes intentional_exception rather than dismissed: the schema
distinguishes them, and "the author meant this" is a different fact from "the
author is not interested".

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 11: Documentation and the full gate run

**Files:**
- Modify: `README.md`
- Modify: `architecture.md`
- Modify: `ENGINEERING_HISTORY.md` (§3)

**Interfaces:**
- Consumes: everything above.
- Produces: nothing.

- [ ] **Step 1: Run the full gate set**

```bash
./gradlew :aura-core:testDebugUnitTest :app:testDebugUnitTest --offline
./gradlew :app:assembleRelease
./gradlew :aura-core:lintDebug :app:lintDebug
bash scripts/lint-logging.sh
bash scripts/check-version-docs.sh
bash scripts/check-test-count.sh
```

Expected: 0 test failures; `assembleRelease` succeeds; lint clean of errors; all three scripts pass. `check-test-count.sh` will **fail** until Step 2 updates the count in the docs — that is the gate working.

- [ ] **Step 2: Update the counts and the prose**

Read the new test total from the failing `check-test-count.sh` output. Then:

- `README.md`: update the unit-test count line. In the Memory-stack / Creative bullets, add one line describing the ledger. Do **not** change the tool count — Task 9 repointed `canon_query`, it did not add a tool.
- `architecture.md`: update the test count under **Build Configuration**. Add a short subsection under **Key Subsystems** describing `SceneLedger` — what it writes, that `storySoFar` and `retrieved` are now supplied, and that `canon_facts` and `continuity_issues` have their first writer.

- [ ] **Step 3: Update ENGINEERING_HISTORY §3**

Per this repo's standing rule, do **not** write a new dated audit report. Edit §3 in place:

- Under **Architecture**, the "Scope versus depth" entry: note that the creative subsystem's canon layer is now written rather than declared.
- Add a short entry recording the defect class this change closes: `SceneContextBuilder` documented eight sections and was given six, and the two omitted were the manuscript's only memory. Name that it was found by reading the caller against the KDoc, and that the regression gate is now `LongformRunnerTest`'s "drafting scene two sends the story so far".
- Note the two never-written fields fixed in passing: `StoryBeat.revisionId`, and `canon_query` reading the personal memory store.

- [ ] **Step 4: Re-run the gates**

```bash
bash scripts/check-version-docs.sh
bash scripts/check-test-count.sh
```

Expected: both pass.

- [ ] **Step 5: Commit**

```bash
git add README.md architecture.md ENGINEERING_HISTORY.md
git commit -m "$(cat <<'EOF'
Record the ledger, and what finding it says about the last twelve passes

SceneContextBuilder documented an eight-section budget and LongformRunner
supplied six. The two it omitted were the manuscript's only memory, and the
tests that touched them proved the caps truncate rather than that the content
arrives. Twelve review passes read this repo and none read the caller against
the KDoc.

Also recorded: StoryBeat.revisionId, declared and never written, and canon_query
querying the personal memory store — both the same shape, both fixed in passing.

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>
EOF
)"
```

- [ ] **Step 6: Device verification**

This cannot run in CI and must not run via `connectedAndroidTest` against the daily install — that uninstalls the package and destroys the Keystore-encrypted API keys, which has already cost one real key.

```bash
bash scripts/smoke.sh
```

Then, by hand in the app: open a creative project, plan an outline, draft at least six scenes, and confirm

1. the Canon card shows a non-zero fact count,
2. scene six's prose does not contradict scenes one through five on anything in `SINGLE_VALUED`,
3. a deliberate contradiction — edit a beat summary to move a character somewhere they cannot be — produces a flag.

Record what you find in `ENGINEERING_HISTORY.md` §3. A ledger that has never run on a real model against a real book is not verified, and this plan's tests cannot tell you whether the extraction prompt produces useful facts — only that whatever it produces is stored correctly.

---

## Notes for the implementer

- **The tests in Task 3 onward stub `ProviderRegistry`, not `Brain`.** `StructuredJson.requestJson` takes a registry. `LlmWriteGateTest.makeRegistry` is the working reference for the stub shape.
- **`record` writes `worldJson` a second time, after the commit already wrote it.** That is deliberate — folding the synopsis into the commit's `updateWorld` would require the model call to finish before the commit, putting a paid-for scene at risk of a bookkeeping failure. `record` re-reads the project before writing, which is the same discipline `LongformRunner` already applies on every pass.
- **If a task's test passes before you write the implementation**, stop and report it rather than moving on. In Task 1 that is expected and stated; anywhere else it means the test is not testing what it claims.
