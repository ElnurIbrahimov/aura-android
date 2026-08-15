package com.aura.creative.longform

import com.aura.agent.Brain
import com.aura.agent.BrainChunk
import com.aura.creative.CreativeArtifactEntity
import com.aura.creative.CreativeArtifactStore
import com.aura.creative.CreativeGenerationJobEntity
import com.aura.creative.CreativeProject
import com.aura.creative.CreativeProjectStore
import com.aura.creative.SmartCodexInjector
import com.aura.creative.StoryBeat
import com.aura.creative.WorldBible
import com.aura.providers.ChatOptions
import com.aura.providers.ModelRole
import com.aura.providers.ModelRoleRouter
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The runner's decisions — which beat is next, when to stop, what gets committed
 * — driven with a mocked [Brain].
 *
 * This is why the logic does not live in `doWork()`. A `CoroutineWorker` needs
 * WorkManager, which does not initialise in a JVM test, and the precedent here
 * (`AgentRunExecutorWorker`) has no unit test of its execution logic at all as a
 * result.
 *
 * What these cannot prove is the thing the feature exists for: that a run
 * survives the process being killed. Only a device shows that.
 */
class LongformRunnerTest {

    private val runStore = mockk<LongformRunStore>(relaxed = true)
    private val projectStore = mockk<CreativeProjectStore>(relaxed = true)
    private val artifactStore = mockk<CreativeArtifactStore>(relaxed = true)
    private val brain = mockk<Brain>()
    private val progressBus = LongformProgressBus()
    private val modelRoleRouter = mockk<ModelRoleRouter>(relaxed = true)
    private val sceneLedger = mockk<SceneLedger>(relaxed = true)

    private fun runner() = LongformRunner(
        runStore = runStore,
        projectStore = projectStore,
        artifactStore = artifactStore,
        contextBuilder = SceneContextBuilder(SmartCodexInjector()),
        brain = brain,
        progressBus = progressBus,
        modelRoleRouter = modelRoleRouter,
        sceneLedger = sceneLedger,
    )

    private fun beats(count: Int, draftedUpTo: Int = 0) = (1..count).map { i ->
        StoryBeat(
            id = "b$i",
            title = "Beat $i",
            summary = "Something happens in $i",
            status = if (i <= draftedUpTo) "drafted" else "planned",
            artifactId = if (i <= draftedUpTo) "art$i" else "",
        )
    }

    private fun project(beats: List<StoryBeat>) = CreativeProject(
        id = "p1",
        name = "The Lighthouse",
        description = "A keeper who cannot swim",
        genre = "literary",
        tone = "spare",
        world = WorldBible(outline = beats),
        templateId = "novel",
        turnCount = 0,
        createdAt = 0L,
        updatedAt = 0L,
    )

    private fun job(status: String = LongformStatus.QUEUED) = CreativeGenerationJobEntity(
        id = "j1",
        projectId = "p1",
        branchId = "main",
        capabilityKind = LongformRunStore.CAPABILITY_KIND,
        requestJson = "{}",
        status = status,
    )

    private fun stubScene(text: String = "x".repeat(600)) {
        coEvery { brain.stream(any(), any(), any(), any()) } returns flowOf(BrainChunk.Text(text))
    }

    private fun stubArtifacts() {
        var n = 0
        coEvery { artifactStore.create(any(), any(), any(), any(), any(), any(), any(), any(), any()) } answers {
            n++
            mockk<CreativeArtifactEntity>(relaxed = true).also { coEvery { it.id } returns "newArt$n" }
        }
        coEvery { artifactStore.currentContent(any()) } returns "the previous scene ended here"
    }

    private fun setUpRun(beatList: List<StoryBeat>, jobStatus: String = LongformStatus.QUEUED) {
        coEvery { runStore.get("j1") } returns job(jobStatus)
        coEvery { projectStore.get("p1") } returns project(beatList)
        coEvery { modelRoleRouter.explicit(ModelRole.CREATIVE_DRAFT) } returns "openai:gpt-4o"
        stubScene()
        stubArtifacts()
    }

    @Test
    fun `it drafts every planned beat and completes`() = runTest {
        val worldSlot = slot<WorldBible>()
        var current = beats(3)
        setUpRun(current)
        // Each committed beat updates worldJson; feed that back so the loop advances.
        coEvery { projectStore.updateWorld("p1", capture(worldSlot)) } answers {
            current = worldSlot.captured.outline
            coEvery { projectStore.get("p1") } returns project(current)
            project(current)
        }

        val outcome = runner().runSlice("j1", deadlineMs = Long.MAX_VALUE, isStopped = { false })

        assertEquals(LongformOutcome.COMPLETED, outcome)
        assertTrue(current.all { it.status == "drafted" }, "every beat should be drafted")
        coVerify(exactly = 3) { brain.stream(any(), any(), any(), any()) }
        coVerify { runStore.finish("j1", LongformStatus.SUCCEEDED, any()) }
    }

    @Test
    fun `an already-complete outline finishes without calling the model`() = runTest {
        setUpRun(beats(3, draftedUpTo = 3))

        val outcome = runner().runSlice("j1", deadlineMs = Long.MAX_VALUE, isStopped = { false })

        assertEquals(LongformOutcome.COMPLETED, outcome)
        coVerify(exactly = 0) { brain.stream(any(), any(), any(), any()) }
    }

    /** Resume: the next beat is the first one not drafted, not the first one at all. */
    @Test
    fun `it resumes at the first undrafted beat`() = runTest {
        val worldSlot = slot<WorldBible>()
        setUpRun(beats(3, draftedUpTo = 2))
        coEvery { projectStore.updateWorld("p1", capture(worldSlot)) } returns null

        runner().runSlice("j1", deadlineMs = Long.MAX_VALUE, isStopped = { false })

        val written = worldSlot.captured.outline
        assertEquals("drafted", written[2].status, "the third beat is the one that should have been written")
        coVerify(exactly = 1) { brain.stream(any(), any(), any(), any()) }
    }

    /**
     * The expensive failure mode, and the reason the progress guard exists.
     *
     * The loop advances by re-reading the project and finding the first beat
     * that is not "drafted". The commit that marks it is best-effort — wrapped
     * in runCatching and logged. If it silently stops persisting, every pass
     * finds the same beat still "planned" and redrafts it, and the model is
     * billed each time. This test writes nothing back, which is exactly that
     * state, and it hung the suite before the guard existed.
     */
    @Test
    fun `a beat that never persists stops the run instead of billing forever`() = runTest {
        setUpRun(beats(3))
        // updateWorld accepted but never reflected in what get() returns.
        coEvery { projectStore.updateWorld(any(), any()) } returns null

        val outcome = runner().runSlice("j1", deadlineMs = Long.MAX_VALUE, isStopped = { false })

        assertEquals(LongformOutcome.FAILED, outcome)
        coVerify(exactly = 1) { brain.stream(any(), any(), any(), any()) }
        coVerify { runStore.fail("j1", "no_progress", any()) }
    }

    /** The wall-clock guard: hand back rather than be killed mid-scene. */
    @Test
    fun `it pauses when the slice budget is spent`() = runTest {
        setUpRun(beats(5))

        val outcome = runner().runSlice(
            "j1",
            deadlineMs = 1_000L,
            isStopped = { false },
            nowMs = { 2_000L },
        )

        assertEquals(LongformOutcome.PAUSED_FOR_TIME, outcome)
        coVerify(exactly = 0) { brain.stream(any(), any(), any(), any()) }
        coVerify(exactly = 0) { runStore.finish(any(), any(), any()) }
    }

    @Test
    fun `a stopped worker cancels and records what was drafted`() = runTest {
        setUpRun(beats(3, draftedUpTo = 1))

        val outcome = runner().runSlice("j1", deadlineMs = Long.MAX_VALUE, isStopped = { true })

        assertEquals(LongformOutcome.CANCELLED, outcome)
        coVerify { runStore.finish("j1", LongformStatus.CANCELLED, listOf("art1")) }
    }

    /**
     * Cancellation is read from Room, not only from the worker's flag.
     * `markCancelling` writes there first precisely so a worker that is
     * mid-scene, or that re-enqueues in the race window, still sees the request.
     */
    @Test
    fun `a cancelling status in the database stops the run`() = runTest {
        setUpRun(beats(3), jobStatus = LongformStatus.CANCELLING)

        val outcome = runner().runSlice("j1", deadlineMs = Long.MAX_VALUE, isStopped = { false })

        assertEquals(LongformOutcome.CANCELLED, outcome)
        coVerify(exactly = 0) { brain.stream(any(), any(), any(), any()) }
    }

    @Test
    fun `an empty outline fails loudly rather than reporting success`() = runTest {
        setUpRun(emptyList())

        val outcome = runner().runSlice("j1", deadlineMs = Long.MAX_VALUE, isStopped = { false })

        assertEquals(LongformOutcome.FAILED, outcome)
        coVerify { runStore.fail("j1", "no_outline", any()) }
    }

    @Test
    fun `no configured model fails with a message rather than drafting nothing`() = runTest {
        setUpRun(beats(3))
        coEvery { modelRoleRouter.explicit(ModelRole.CREATIVE_DRAFT) } returns null
        coEvery { modelRoleRouter.resolve(ModelRole.CONVERSATION) } returns null

        val outcome = runner().runSlice("j1", deadlineMs = Long.MAX_VALUE, isStopped = { false })

        assertEquals(LongformOutcome.FAILED, outcome)
        coVerify { runStore.fail("j1", "no_model", any()) }
    }

    /**
     * A model returning nothing must not spin. With the beat still un-drafted,
     * looping would call the model again on the same beat immediately and bill
     * for every attempt.
     */
    @Test
    fun `an unusable scene stops the slice instead of retrying in a tight loop`() = runTest {
        setUpRun(beats(3))
        stubScene(text = "no.")

        val outcome = runner().runSlice("j1", deadlineMs = Long.MAX_VALUE, isStopped = { false })

        assertEquals(LongformOutcome.PAUSED_FOR_TIME, outcome)
        coVerify(exactly = 1) { brain.stream(any(), any(), any(), any()) }
        coVerify { runStore.recordAttempt("j1") }
    }

    /**
     * Per-scene budgets are stated explicitly. Leaving `thinkingBudget` null
     * would apply the user's global 32,000 to a 1,200-word scene, twelve times
     * over.
     */
    @Test
    fun `each scene call carries explicit budgets`() = runTest {
        val options = slot<ChatOptions>()
        setUpRun(beats(1))
        coEvery { brain.stream(any(), any(), any(), capture(options)) } returns flowOf(BrainChunk.Text("x".repeat(600)))

        runner().runSlice("j1", deadlineMs = Long.MAX_VALUE, isStopped = { false })

        assertEquals(8_192, options.captured.maxTokens)
        assertEquals(2_048, options.captured.thinkingBudget)
    }

    @Test
    fun `it drafts with the Creative Draft model when one is set`() = runTest {
        val model = slot<String>()
        setUpRun(beats(1))
        coEvery { modelRoleRouter.explicit(ModelRole.CREATIVE_DRAFT) } returns "groq:llama-3-70b"
        coEvery { brain.stream(capture(model), any(), any(), any()) } returns flowOf(BrainChunk.Text("x".repeat(600)))

        runner().runSlice("j1", deadlineMs = Long.MAX_VALUE, isStopped = { false })

        assertEquals("groq:llama-3-70b", model.captured)
    }

    /** Live text is cleared between scenes, or the UI shows a finished scene as still writing. */
    @Test
    fun `the live scene is cleared when the run ends`() = runTest {
        setUpRun(beats(1))

        runner().runSlice("j1", deadlineMs = Long.MAX_VALUE, isStopped = { false })

        assertEquals(null, progressBus.live.value)
    }

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
        // sceneLedger is a mock here, not the real SceneLedger — LongformRunnerTest
        // proves the wiring (the result reaches the prompt), SceneLedgerTest proves
        // storySoFar's own accumulation logic. Stubbed with the same text beat one's
        // synopsis carries, so the assertion below is checking the wiring, not MockK's
        // default relaxed-mock return value.
        every { sceneLedger.storySoFar(any(), any()) } returns
            "Mira reached the lighthouse and the keeper refused her."
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
        val worldSlot = slot<WorldBible>()
        setUpRun(beats(1))
        // Feed the commit back into get(), the same idiom `it drafts every planned
        // beat and completes` uses. Without it, get() keeps returning beat one as
        // "planned" on the loop's next pass, which is indistinguishable from the
        // no-progress case and trips the FAILED guard rather than the outcome this
        // test is about — leaving no beat left undrafted, so the run completes.
        coEvery { projectStore.updateWorld("p1", capture(worldSlot)) } answers {
            coEvery { projectStore.get("p1") } returns project(worldSlot.captured.outline)
            null
        }
        coEvery { sceneLedger.record(any(), any(), any(), any(), any(), any(), any()) } throws
            IllegalStateException("extraction blew up")

        val outcome = runner().runSlice("j1", deadlineMs = Long.MAX_VALUE, isStopped = { false })

        assertEquals(LongformOutcome.COMPLETED, outcome)
        coVerify(exactly = 1) { sceneLedger.record(any(), any(), 0, any(), any(), any(), any()) }
    }
}
