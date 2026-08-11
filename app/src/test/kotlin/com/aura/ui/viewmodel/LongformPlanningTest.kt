package com.aura.ui.viewmodel

import com.aura.capabilities.CapabilityRouter
import com.aura.creative.CreativeCouncil
import com.aura.creative.CreativeEngine
import com.aura.creative.CreativeMode
import com.aura.creative.CreativeProject
import com.aura.creative.CreativeProjectStore
import com.aura.creative.WorldBible
import com.aura.creative.longform.LongformProgressBus
import com.aura.creative.longform.LongformRunStore
import com.aura.providers.ProviderRegistry
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Outline planning is the gate in front of a dozen expensive calls, so what it
 * does when the model misbehaves matters more than the happy path.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LongformPlanningTest {

    private val dispatcher = StandardTestDispatcher()
    private val store = mockk<CreativeProjectStore>(relaxed = true)
    private val engine = mockk<CreativeEngine>()
    private val longformRunStore = mockk<LongformRunStore>(relaxed = true)

    private val project = CreativeProject(
        id = "p1",
        name = "The Lighthouse",
        description = "A keeper who cannot swim",
        genre = "literary",
        tone = "spare",
        world = WorldBible(),
        templateId = "novel",
        turnCount = 0,
        createdAt = 1L,
        updatedAt = 1L,
    )

    private fun viewModel() = CreativeStudioViewModel(
        store, engine, mockk<CreativeCouncil>(relaxed = true), mockk<ProviderRegistry>(relaxed = true),
        mockk<CapabilityRouter>(relaxed = true), mockk(relaxed = true),
        mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true),
        mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true),
        mockk(relaxed = true),
        longformRunStore, LongformProgressBus(),
        mockk(relaxed = true), com.aura.creative.livingworld.WorldSeeder(), mockk(relaxed = true),
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        every { store.observeAll() } returns emptyFlow()
        coEvery { store.get("p1") } returns project
        every { longformRunStore.observeForProject(any()) } returns flowOf(emptyList())
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun stubOutline(vararg replies: String) {
        var call = 0
        coEvery { engine.generate(any(), CreativeMode.OUTLINE, any(), any(), any()) } answers {
            flowOf(replies.getOrElse(call++) { replies.last() })
        }
    }

    @Test
    fun `a well-formed outline is saved as beats`() = runTest(dispatcher) {
        stubOutline(
            """
            BEAT 1 | Arrival | She reaches the island
            BEAT 2 | The light fails | The beam dies at midnight
            BEAT 3 | The descent | She goes down to the machine room
            """.trimIndent(),
        )
        val world = slot<WorldBible>()
        coEvery { store.updateWorld("p1", capture(world)) } returns project

        val vm = viewModel()
        vm.loadProject("p1")
        advanceUntilIdle()
        vm.planOutline("a keeper who cannot swim")
        advanceUntilIdle()

        assertEquals(3, world.captured.outline.size)
        assertEquals("Arrival", world.captured.outline.first().title)
        assertNull(vm.state.value.error)
    }

    /**
     * Models answer in prose when asked for a format. One terse retry is worth
     * it; a second would just be paying twice for the same refusal.
     */
    @Test
    fun `prose is retried once and the retry is used`() = runTest(dispatcher) {
        stubOutline(
            "Here is a lovely outline for your novel about a lighthouse keeper.",
            """
            BEAT 1 | Arrival | She reaches the island
            BEAT 2 | The light fails | The beam dies
            BEAT 3 | The descent | She goes down
            """.trimIndent(),
        )
        val world = slot<WorldBible>()
        coEvery { store.updateWorld("p1", capture(world)) } returns project

        val vm = viewModel()
        vm.loadProject("p1")
        advanceUntilIdle()
        vm.planOutline("a keeper who cannot swim")
        advanceUntilIdle()

        assertEquals(3, world.captured.outline.size)
        coVerify(exactly = 2) { engine.generate(any(), CreativeMode.OUTLINE, any(), any(), any()) }
    }

    /**
     * The failure that matters: never save an empty outline. A run started
     * against no beats writes nothing and reports success, which is worse than
     * saying the outline could not be parsed.
     */
    @Test
    fun `unparseable prose saves nothing and says so`() = runTest(dispatcher) {
        stubOutline("I would be delighted to help you outline your novel!")

        val vm = viewModel()
        vm.loadProject("p1")
        advanceUntilIdle()
        vm.planOutline("a keeper who cannot swim")
        advanceUntilIdle()

        coVerify(exactly = 0) { store.updateWorld(any(), any()) }
        val error = assertNotNull(vm.state.value.error)
        assertTrue(error.contains("outline"), error)
        assertTrue(!vm.state.value.planningOutline, "the spinner must stop")
    }

    /** Too few beats is the same failure as none — three is the floor. */
    @Test
    fun `an outline below the minimum is rejected`() = runTest(dispatcher) {
        stubOutline("BEAT 1 | Only one thing happens | and that is all")

        val vm = viewModel()
        vm.loadProject("p1")
        advanceUntilIdle()
        vm.planOutline("a keeper who cannot swim")
        advanceUntilIdle()

        coVerify(exactly = 0) { store.updateWorld(any(), any()) }
        assertNotNull(vm.state.value.error)
    }

    /** Drafting without a plan must not create a job. */
    @Test
    fun `drafting refuses to start with no outline`() = runTest(dispatcher) {
        val vm = viewModel()
        vm.loadProject("p1")
        advanceUntilIdle()
        vm.startDrafting()
        advanceUntilIdle()

        coVerify(exactly = 0) { longformRunStore.create(any(), any(), any()) }
        assertNotNull(vm.state.value.error)
    }
}
