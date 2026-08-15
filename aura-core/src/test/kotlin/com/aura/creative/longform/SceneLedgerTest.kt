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

    @Test
    fun `a fact with a blank value is dropped rather than stored`() = runTest {
        stubModel(
            """{"synopsis":"A thing happened.","facts":[
                 {"subjectType":"character","subjectId":"Mira","predicate":"location","value":""}]}"""
        )
        coEvery { projectStore.get("p1") } returns project()

        ledger().record(project(), "main", 0, "art1", "rev1", "x".repeat(600), "openai:gpt-4o")

        coVerify(exactly = 0) { canonFactDao.upsertAll(any()) }
    }

    /**
     * `canonFactDao.upsertAll` is `@Insert(onConflict = REPLACE)` keyed only on
     * `id`. A random id would turn every re-extraction of the same revision into
     * a duplicate row rather than a replace, which back-fill (Task 8) and any
     * later re-run both rely on not happening.
     */
    @Test
    fun `recording the same scene twice produces identical fact ids`() = runTest {
        stubModel(goodReply)
        val factSlot = slot<List<CanonFactEntity>>()
        coEvery { projectStore.get("p1") } returns project()
        coEvery { canonFactDao.upsertAll(capture(factSlot)) } returns Unit

        ledger().record(project(), "main", 0, "art1", "rev1", "x".repeat(600), "openai:gpt-4o")
        val first = factSlot.captured.single()

        ledger().record(project(), "main", 0, "art1", "rev1", "x".repeat(600), "openai:gpt-4o")
        val second = factSlot.captured.single()

        assertEquals(first.id, second.id)
        assertEquals("\"the lighthouse\"", first.valueJson)
        assertEquals(0.9f, first.confidence, 0.01f)
    }

    /**
     * The synopsis is back-fill's only sentinel for "this beat still needs
     * extraction". Writing it after a facts-write failure would strand those
     * facts unrecorded and unrecoverable, since back-fill would never revisit a
     * beat that already has one.
     */
    @Test
    fun `a facts write failure returns false and does not write the synopsis`() = runTest {
        stubModel(goodReply)
        coEvery { projectStore.get("p1") } returns project()
        coEvery { canonFactDao.upsertAll(any()) } throws RuntimeException("db down")

        val stored = ledger().record(project(), "main", 0, "art1", "rev1", "x".repeat(600), "openai:gpt-4o")

        assertEquals(false, stored)
        coVerify(exactly = 0) { projectStore.updateWorld(any(), any()) }
    }
}
