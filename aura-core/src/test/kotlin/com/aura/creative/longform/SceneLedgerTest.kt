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
import io.mockk.coVerifyOrder
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.util.UUID
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

    private fun draftedBeats(count: Int, synopsisChars: Int = 40) = (1..count).map { i ->
        StoryBeat(
            id = "b$i",
            title = "Beat $i",
            status = "drafted",
            synopsis = "S$i " + "z".repeat(synopsisChars),
        )
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
        coVerify(exactly = 0) { canonFactDao.updateStatus(any(), any(), any()) }
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

    /**
     * `category` is a documented taxonomy of issue *kinds*, coarser than a
     * predicate. Writing `fact.predicate` straight through would have put
     * "allegiance" in a column whose KDoc never mentions it — this is the
     * assertion that would have caught that.
     */
    @Test
    fun `a changed allegiance maps to the relationship category, not the raw predicate`() = runTest {
        stubModel(replyWith("allegiance", "the Rebellion"))
        val issueSlot = slot<com.aura.creative.ContinuityIssueEntity>()
        coEvery { projectStore.get("p1") } returns project()
        coEvery { canonFactDao.forSubject("p1", "main", "character", "Mira") } returns
            listOf(existingFact("allegiance", "the Crown"))
        coEvery { continuityIssueDao.upsert(capture(issueSlot)) } returns Unit

        ledger().record(project(), "main", 1, "art2", "rev2", "x".repeat(600), "openai:gpt-4o")

        assertEquals("relationship", issueSlot.captured.category)
    }

    /**
     * `record` runs outside the caller's `NonCancellable` block on purpose, so
     * being interrupted mid-`reconcile` is an expected condition, not an edge
     * case. Superseding first would leave a fact retired with no replacement —
     * `forSubject` filters to active, so the next pass would find nothing to
     * compare against and lose the continuity issue for good. Writing first
     * fails toward two active facts instead, which is recoverable. If a later
     * change moves the write back to the end, this fails.
     */
    @Test
    fun `the new fact is written before the old one is superseded`() = runTest {
        stubModel(replyWith("location", "Kesh"))
        coEvery { projectStore.get("p1") } returns project()
        coEvery { canonFactDao.forSubject("p1", "main", "character", "Mira") } returns
            listOf(existingFact("location", "Varn"))

        ledger().record(project(), "main", 1, "art2", "rev2", "x".repeat(600), "openai:gpt-4o")

        coVerifyOrder {
            canonFactDao.upsertAll(any())
            canonFactDao.updateStatus("old1", "superseded", any())
        }
    }

    /**
     * `it.id != fact.id` and the `valueJson` equality check are not the same
     * guard, and only one shape tells them apart: a returned row with the
     * *same* id as the fact under reconciliation but a *different* value — a
     * stale read racing the write `reconcile` just did. Same id, same
     * subject+predicate, different value is exactly what a genuine
     * contradiction from a *different* fact also looks like; only the id
     * distinguishes "this is the row I just wrote, read back before its value
     * caught up" from "this is really a different fact." Without the id
     * filter, the value check does not save it — the fact would be recorded
     * as contradicting itself. The id is computed with `toEntity`'s own
     * formula rather than hardcoded, so this cannot silently stop meaning
     * anything if that formula changes.
     */
    @Test
    fun `a stale read of the same fact is not treated as a contradiction`() = runTest {
        stubModel(replyWith("location", "Kesh"))
        val factId = UUID.nameUUIDFromBytes("rev2|character|Mira|location".toByteArray()).toString()
        coEvery { projectStore.get("p1") } returns project()
        coEvery { canonFactDao.forSubject("p1", "main", "character", "Mira") } returns
            listOf(existingFact("location", "Varn", id = factId))

        ledger().record(project(), "main", 1, "art2", "rev2", "x".repeat(600), "openai:gpt-4o")

        coVerify(exactly = 0) { continuityIssueDao.upsert(any()) }
        coVerify(exactly = 0) { canonFactDao.updateStatus(any(), any(), any()) }
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
}
