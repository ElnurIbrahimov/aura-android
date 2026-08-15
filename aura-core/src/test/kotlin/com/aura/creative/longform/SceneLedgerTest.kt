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
        // Returns the project, not null. `updateWorld` returns null *without
        // throwing* when the project row is gone, so stubbing null here made
        // `assertTrue(stored)` assert the bug rather than the behaviour named in
        // the test title — see the case below, which pins the null path itself.
        coEvery { projectStore.updateWorld("p1", capture(worldSlot)) } returns project()

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

    /**
     * `updateWorld` returns `CreativeProject?` and returns null *without throwing*
     * when the project row is gone. `runCatching { … }.isSuccess` was therefore
     * true for a write that persisted nothing, and `record` reported a synopsis
     * stored that no reader will ever find — which back-fill counts as filled and
     * never revisits.
     */
    @Test
    fun `a world write that silently persists nothing reports failure`() = runTest {
        stubModel(goodReply)
        coEvery { projectStore.get("p1") } returns project()
        coEvery { projectStore.updateWorld("p1", any()) } returns null

        val stored = ledger().record(project(), "main", 0, "art1", "rev1", "x".repeat(600), "openai:gpt-4o")

        assertEquals(false, stored)
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
        coEvery { projectStore.updateWorld("p1", capture(worldSlot)) } returns project()

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

    /**
     * `revisionId` is the identity every fact id is derived from. Blank makes the
     * deterministic id degenerate to "|type|subject|predicate": every legacy scene
     * stating the same triple collapses onto one row under REPLACE, and
     * `reconcile`'s `it.id != fact.id` filter then skips the old row as "itself",
     * so no contradiction can ever be detected. Canon that looks present and is
     * wrong is worse than canon that is absent.
     */
    @Test
    fun `it refuses to record canon without a revision id`() = runTest {
        stubModel(goodReply)
        coEvery { projectStore.get("p1") } returns project()

        val stored = ledger().record(project(), "main", 0, "art1", "", "x".repeat(600), "openai:gpt-4o")

        assertEquals(false, stored)
        coVerify(exactly = 0) { canonFactDao.upsertAll(any()) }
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

    /**
     * Stopwords LIKE-match nearly every scene, which is noise rather than retrieval.
     *
     * Every asserted word is four characters — long enough to survive the length
     * filter on its own — so each assertion can only pass because of stopword-list
     * membership, not because the word was also too short to reach the database.
     * A three-letter stopword like "the" would pass this test even with the
     * stopword filter deleted, since the length filter removes it either way;
     * that is exactly why none of the four assertions below use one.
     */
    @Test
    fun `it never searches on a stopword long enough to survive the length filter`() = runTest {
        val beats = listOf(
            StoryBeat(id = "b1", title = "One", status = "drafted", artifactId = "a1", revisionId = "r1"),
            StoryBeat(id = "b2", title = "Were have into with", summary = "lighthouse"),
        )
        coEvery { revisionDao.searchScenes(any(), any(), any(), any()) } returns emptyList()

        ledger().retrieve("p1", beats, beatIndex = 1)

        // A real term is present so the term list isn't empty and retrieve() actually
        // reaches the DAO — otherwise every coVerify below would pass vacuously.
        coVerify(exactly = 1) { revisionDao.searchScenes("p1", "lighthouse", any(), any()) }
        coVerify(exactly = 0) { revisionDao.searchScenes(any(), "were", any(), any()) }
        coVerify(exactly = 0) { revisionDao.searchScenes(any(), "have", any(), any()) }
        coVerify(exactly = 0) { revisionDao.searchScenes(any(), "into", any(), any()) }
        coVerify(exactly = 0) { revisionDao.searchScenes(any(), "with", any(), any()) }
    }

    /**
     * The previous scene is already supplied verbatim and in full as
     * `previousSceneTail`. Letting it match here spends the retrieval budget
     * printing it a second time.
     *
     * Excluded by **artifact** id, not revision id. `previousSceneTail` follows
     * the artifact, so keying this on the revision made the two disagree whenever
     * a revision pointer moved; and a beat drafted before this branch existed has
     * a blank `revisionId`, against which `r.id != ''` matches every row — the
     * previous scene was retrieved in full, for exactly the population that
     * cannot afford it.
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

        coVerify { revisionDao.searchScenes("p1", any(), "a2", any()) }
    }

    /**
     * A legacy beat has no `revisionId`, and the old exclusion keyed on it passed
     * `""` to a `r.id != :exclude` comparison that every row satisfies. The
     * artifact id is populated for every drafted beat ever written, so the
     * exclusion still holds for the scenes that most need it.
     */
    @Test
    fun `it still excludes the previous scene when that beat has no revision id`() = runTest {
        val beats = listOf(
            StoryBeat(id = "b1", title = "Arrival", status = "drafted", artifactId = "a1"),
            StoryBeat(id = "b2", title = "Lantern", status = "drafted", artifactId = "a2"),
            StoryBeat(id = "b3", title = "The lantern again", summary = "Mira returns"),
        )
        coEvery { revisionDao.searchScenes(any(), any(), any(), any()) } returns emptyList()

        ledger().retrieve("p1", beats, beatIndex = 2)

        coVerify { revisionDao.searchScenes("p1", any(), "a2", any()) }
        coVerify(exactly = 0) { revisionDao.searchScenes("p1", any(), "", any()) }
    }

    @Test
    fun `the first scene retrieves nothing and asks the database nothing`() = runTest {
        ledger().retrieve("p1", draftedBeats(3), beatIndex = 0)
        coVerify(exactly = 0) { revisionDao.searchScenes(any(), any(), any(), any()) }
    }

    @Test
    fun `it fills a drafted beat whose synopsis is blank`() = runTest {
        stubModel(goodReply)
        val beats = listOf(
            StoryBeat(id = "b1", title = "One", status = "drafted", artifactId = "a1", revisionId = "r1", synopsis = ""),
            StoryBeat(id = "b2", title = "Two", status = "drafted", artifactId = "a2", revisionId = "r2", synopsis = "already there"),
        )
        coEvery { projectStore.get("p1") } returns project(beats)
        coEvery { projectStore.updateWorld(any(), any()) } returns project(beats)
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
        coEvery { projectStore.updateWorld(any(), any()) } returns project(beats)
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

    private fun artifact(id: String, revisionId: String?) = com.aura.creative.CreativeArtifactEntity(
        id = id,
        projectId = "p1",
        branchId = "main",
        kind = "scene",
        title = "Scene",
        currentRevisionId = revisionId,
    )

    /**
     * Every scene drafted before this class existed has a blank `revisionId` —
     * which is the entire population back-fill was written to serve. Passing it
     * through would have degenerated every fact id to "|type|subject|predicate"
     * and stored empty provenance. The artifact the beat already points at knows
     * the revision, so back-fill recovers it there.
     */
    @Test
    fun `back-fill recovers a blank revision id from the artifact`() = runTest {
        stubModel(goodReply)
        val factSlot = slot<List<CanonFactEntity>>()
        val beats = listOf(
            StoryBeat(id = "b1", title = "One", status = "drafted", artifactId = "a1", revisionId = ""),
        )
        coEvery { projectStore.get("p1") } returns project(beats)
        coEvery { projectStore.updateWorld(any(), any()) } returns project(beats)
        coEvery { artifactStore.currentContent("a1") } returns "x".repeat(600)
        coEvery { artifactStore.get("a1") } returns artifact("a1", "recovered")
        coEvery { canonFactDao.upsertAll(capture(factSlot)) } returns Unit

        assertEquals(1, ledger().backFill(project(beats), "main", "openai:gpt-4o"))

        assertEquals("recovered", factSlot.captured.single().sourceRevisionId)
    }

    /**
     * `record` writes the recovered id back onto the beat, so a legacy beat is
     * repaired the first time it is back-filled and never has to be resolved
     * again — the synopsis alone would have left the blank in place forever.
     */
    @Test
    fun `the recovered revision id is written back onto the beat`() = runTest {
        stubModel(goodReply)
        val worldSlot = slot<WorldBible>()
        val beats = listOf(
            StoryBeat(id = "b1", title = "One", status = "drafted", artifactId = "a1", revisionId = ""),
        )
        coEvery { projectStore.get("p1") } returns project(beats)
        coEvery { projectStore.updateWorld("p1", capture(worldSlot)) } returns project(beats)
        coEvery { artifactStore.currentContent("a1") } returns "x".repeat(600)
        coEvery { artifactStore.get("a1") } returns artifact("a1", "recovered")

        ledger().backFill(project(beats), "main", "openai:gpt-4o")

        assertEquals("recovered", worldSlot.captured.outline[0].revisionId)
    }

    /**
     * A beat whose revision cannot be resolved cannot produce sound canon, so it
     * is skipped. Skipping is not a failed attempt: it must not spend the cap
     * that exists to bound a broken *extraction*, or one orphaned beat at the
     * front of the outline would starve every healable beat behind it on every
     * slice forever.
     */
    @Test
    fun `an unresolvable revision is skipped without consuming the cap`() = runTest {
        stubModel(goodReply)
        val beats = listOf(
            StoryBeat(id = "b0", title = "Orphan", status = "drafted", artifactId = "a0", revisionId = ""),
        ) + (1..3).map {
            StoryBeat(id = "b$it", title = "B$it", status = "drafted", artifactId = "a$it", revisionId = "r$it")
        }
        coEvery { projectStore.get("p1") } returns project(beats)
        coEvery { projectStore.updateWorld(any(), any()) } returns project(beats)
        coEvery { artifactStore.currentContent(any()) } returns "x".repeat(600)
        coEvery { artifactStore.get("a0") } returns null

        val filled = ledger().backFill(project(beats), "main", "openai:gpt-4o")

        assertEquals(SceneLedger.MAX_BACKFILL_PER_SLICE, filled, "the orphan must not eat a healable beat's turn")
        coVerify(exactly = 0) { canonFactDao.upsertAll(match { facts -> facts.any { it.sourceRevisionId.isNullOrBlank() } }) }
    }
}
