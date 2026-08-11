package com.aura.creative.livingworld

import com.aura.agent.Brain
import com.aura.agent.BrainChunk
import com.aura.providers.ModelRole
import com.aura.providers.ModelRoleRouter
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The only place in this feature that costs money, so the tests are mostly
 * about **not** spending it: the floor, the daily ceiling, and one batched
 * request rather than one per event.
 */
class WorldNarratorTest {

    private val brain = mockk<Brain>(relaxed = true)
    private val store = mockk<LivingWorldStore>(relaxed = true)
    private val router = mockk<ModelRoleRouter>(relaxed = true)
    private val narrator = WorldNarrator(brain, store, router)

    private val world = LivingWorldEntity(
        id = "w1", projectId = "p1", branchId = "main", rootSeed = 1L,
        worldEpochMs = 0L, currentTick = 50L, stateJson = "{}",
    )

    private fun event(id: String, summary: String = "something happened") = LivingEventEntity(
        id = id, worldId = "w1", branchId = "main", tickIndex = 10, seq = 0,
        kind = WorldEngine.KIND_CLAIM_WON, actorId = "a", summary = summary,
        notability = 0.8, createdAt = 0L,
    )

    private fun replyWith(text: String) {
        coEvery { router.explicit(any()) } returns null
        coEvery { router.resolve(ModelRole.BACKGROUND) } returns "openai:gpt-4o-mini"
        coEvery { store.decode(any()) } returns WorldState()
        coEvery { brain.stream(any(), any(), any(), any()) } returns flowOf(BrainChunk.Text(text))
    }

    @Test
    fun `nothing above the floor means no request at all`() = runTest {
        coEvery { store.narratedSince(any(), any()) } returns 0
        coEvery { store.topUnnarrated(any(), any(), any()) } returns emptyList()

        assertEquals(0, narrator.narratePending(world, now = 1_000L))
        coVerify(exactly = 0) { brain.stream(any(), any(), any(), any()) }
    }

    @Test
    fun `the daily ceiling stops the call before it is made`() = runTest {
        coEvery { store.narratedSince(any(), any()) } returns WorldNarrator.MAX_PER_DAY

        assertEquals(0, narrator.narratePending(world, now = 1_000L))
        coVerify(exactly = 0) { store.topUnnarrated(any(), any(), any()) }
        coVerify(exactly = 0) { brain.stream(any(), any(), any(), any()) }
    }

    @Test
    fun `three events cost one request, not three`() = runTest {
        coEvery { store.narratedSince(any(), any()) } returns 0
        coEvery { store.topUnnarrated(any(), any(), any()) } returns
            listOf(event("e1"), event("e2"), event("e3"))
        replyWith("1. The first thing.\n2. The second thing.\n3. The third thing.")

        assertEquals(3, narrator.narratePending(world, now = 1_000L))
        coVerify(exactly = 1) { brain.stream(any(), any(), any(), any()) }
    }

    @Test
    fun `narration is attached to the matching event, not to whichever came back first`() = runTest {
        coEvery { store.narratedSince(any(), any()) } returns 0
        coEvery { store.topUnnarrated(any(), any(), any()) } returns listOf(event("e1"), event("e2"))
        replyWith("1. Ashfall crossed the river.\n2. Bramwatch closed its gates.")

        narrator.narratePending(world, now = 1_000L)

        coVerify { store.attachNarration("e1", "Ashfall crossed the river.", 1_000L) }
        coVerify { store.attachNarration("e2", "Bramwatch closed its gates.", 1_000L) }
    }

    @Test
    fun `no configured model leaves the world readable rather than failing`() = runTest {
        coEvery { store.narratedSince(any(), any()) } returns 0
        coEvery { store.topUnnarrated(any(), any(), any()) } returns listOf(event("e1"))
        coEvery { router.explicit(any()) } returns null
        coEvery { router.resolve(any()) } returns null

        assertEquals(0, narrator.narratePending(world, now = 1_000L))
        coVerify(exactly = 0) { store.attachNarration(any(), any(), any()) }
    }

    @Test
    fun `a failed call writes nothing rather than a half-narrated history`() = runTest {
        coEvery { store.narratedSince(any(), any()) } returns 0
        coEvery { store.topUnnarrated(any(), any(), any()) } returns listOf(event("e1"))
        coEvery { router.explicit(any()) } returns null
        coEvery { router.resolve(any()) } returns "openai:gpt-4o-mini"
        coEvery { store.decode(any()) } returns WorldState()
        coEvery { brain.stream(any(), any(), any(), any()) } returns
            flowOf(BrainChunk.Error(code = "rate_limited", message = "rate limited", retryable = true))

        assertEquals(0, narrator.narratePending(world, now = 1_000L))
        coVerify(exactly = 0) { store.attachNarration(any(), any(), any()) }
    }

    @Test
    fun `a reply that ignores the numbering is kept rather than thrown away`() {
        // Prose that has already been paid for should not be discarded because
        // the model forgot to number it.
        val split = narrator.splitNumbered("The river rose and the bridge went with it.", expected = 3)
        assertTrue(split.first().isNotBlank())
    }

    @Test
    fun `numbering is honoured even when the model reorders or uses parentheses`() {
        val split = narrator.splitNumbered("2) second thing\n1) first thing", expected = 2)
        assertEquals("first thing", split[0])
        assertEquals("second thing", split[1])
    }

    @Test
    fun `a single event does not need numbering at all`() {
        assertEquals("just the one thing", narrator.splitNumbered("1. just the one thing", expected = 1))
            .let { }
        assertEquals("bare prose", narrator.splitNumbered("bare prose", expected = 1).first())
    }

    private fun assertEquals(expected: String, actual: List<String>) {
        kotlin.test.assertEquals(expected, actual.first())
    }
}
