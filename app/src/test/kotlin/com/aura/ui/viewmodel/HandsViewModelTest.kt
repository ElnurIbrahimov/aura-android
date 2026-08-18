package com.aura.ui.viewmodel

import kotlinx.coroutines.test.advanceUntilIdle
import kotlin.test.assertTrue
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.toList
import com.aura.agent.ToolContext
import com.aura.agent.ToolExecutor
import com.aura.agent.ToolRegistry
import com.aura.agent.ToolResult
import com.aura.hands.Hand
import com.aura.hands.HandRepository
import com.aura.hands.HandRun
import com.aura.hands.HandRunStatus
import com.aura.hands.HandRunTrigger
import com.aura.hands.HandScheduler
import com.aura.hands.HandStep
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
class HandsViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val repository = mockk<HandRepository>()
    private val executor = mockk<ToolExecutor>()
    private val registry = mockk<ToolRegistry>(relaxed = true)
    private val scheduler = mockk<HandScheduler>(relaxed = true)

    @Before
    fun setup() {
        Dispatchers.setMain(dispatcher)
        coEvery { repository.getAll() } returns emptyList()
        every { repository.observeRecentRuns(any()) } returns flowOf(emptyList())
        every { repository.variablesToJson(any()) } returns "{\"city\":\"Baku\"}"
        every { repository.conditionsToJson(any()) } returns "[]"
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `initial load includes durable run history`() = runTest {
        val hand = Hand("h1", "Morning")
        val run = HandRun(
            id = "r1",
            handId = "h1",
            handName = "Morning",
            trigger = "manual",
            status = HandRunStatus.SUCCESS.value,
        )
        coEvery { repository.getAll() } returns listOf(hand)
        every { repository.observeRecentRuns(100) } returns flowOf(listOf(run))

        val vm = viewModel()

        assertEquals(listOf(hand), vm.state.value.hands)
        assertEquals(listOf(run), vm.state.value.runs)
    }

    @Test
    fun `save persists full automation and schedules it`() = runTest {
        coEvery { repository.insert(any()) } returns Unit
        val vm = viewModel()
        val draft = HandDraft(
            name = "Morning",
            triggerPhrase = "start day",
            stepsJson = "[]",
            variables = mapOf("city" to "Baku"),
            conditions = emptyList(),
            scheduleType = "daily",
            scheduleHour = 8,
            scheduleMinute = 30,
            scheduleDayOfWeek = 1,
        )

        vm.save(null, draft)

        coVerify {
            repository.insert(match {
                it.name == "Morning" && it.variables.contains("Baku") &&
                    it.scheduleType == "daily" && it.scheduleHour == 8
            })
        }
        coVerify { scheduler.schedule(match { it.name == "Morning" }, any()) }
    }

    @Test
    fun `disabling a hand cancels scheduled work`() = runTest {
        val hand = Hand("h1", "Morning", enabled = true, scheduleType = "daily")
        coEvery { repository.update(any()) } returns Unit
        val vm = viewModel()

        vm.toggle(hand)

        coVerify { repository.update(match { !it.enabled }) }
        coVerify { scheduler.cancel("h1") }
    }

    @Test
    fun `delete cancels and removes only the stable hand id`() = runTest {
        val hand = Hand("h1", "Duplicate name")
        coEvery { repository.deleteById(hand.id) } returns Unit
        val vm = viewModel()

        vm.delete(hand)

        coVerify { scheduler.cancel("h1") }
        coVerify { repository.deleteById("h1") }
        coVerify(exactly = 0) { repository.deleteByName(any()) }
    }

    @Test
    fun `manual run passes variable overrides and clears running state`() = runTest {
        val hand = Hand("h1", "Weather")
        coEvery { repository.run(hand, executor, any<ToolContext>(), mapOf("city" to "Tokyo"), "manual") } returns
            ToolResult.Ok("done")
        val vm = viewModel()

        vm.runHand(hand, mapOf("city" to "Tokyo"))

        assertNull(vm.state.value.running)
        assertEquals("done", vm.state.value.lastResult)
    }

    @Test
    fun `approval resume authorizes only the stopped tool and starts at failed step`() = runTest {
        val hand = Hand(
            id = "h1",
            name = "Illustrate",
            steps = """[{"tool":"prepare","args":{}},{"tool":"image_gen","args":{"prompt":"{{prompt}}"}}]""",
            updatedAt = 100L,
        )
        val run = HandRun(
            id = "r1",
            handId = hand.id,
            handName = hand.name,
            trigger = HandRunTrigger.SCHEDULE.value,
            status = HandRunStatus.NEEDS_APPROVAL.value,
            startedAt = 200L,
            failedStep = 2,
            variablesJson = """{"prompt":"castle"}""",
        )
        coEvery { repository.getById("h1") } returns hand
        every { repository.parseVariables(run.variablesJson) } returns mapOf("prompt" to "castle")
        every { repository.parseSteps(hand.steps) } returns listOf(
            HandStep("prepare", emptyMap()),
            HandStep("image_gen", mapOf("prompt" to "{{prompt}}")),
        )
        coEvery {
            repository.run(
                hand,
                executor,
                match<ToolContext> {
                    it.conversationId == "hand:h1:resume:r1" &&
                        it.approvedRemoteCostTools == setOf("image_gen")
                },
                mapOf("prompt" to "castle"),
                HandRunTrigger.RESUME.value,
                1,
            )
        } returns ToolResult.Ok("resumed")
        val vm = viewModel()

        vm.resumeRun(run)

        assertEquals("resumed", vm.state.value.lastResult)
        coVerify {
            repository.run(
                hand,
                executor,
                any<ToolContext>(),
                mapOf("prompt" to "castle"),
                HandRunTrigger.RESUME.value,
                1,
            )
        }
    }

    @Test
    fun `clear history delegates and removes visible runs`() = runTest {
        coEvery { repository.deleteRunHistory() } returns Unit
        val vm = viewModel()

        vm.clearHistory()

        coVerify { repository.deleteRunHistory() }
    }

    @Test
    fun `status filter state is recorded`() {
        val vm = viewModel()
        vm.setStatusFilter("enabled")
        assertEquals("enabled", vm.state.value.statusFilter)
        vm.setStatusFilter("disabled")
        assertEquals("disabled", vm.state.value.statusFilter)
    }

    @Test
    fun `status filter actually filters the list the screen renders`() = runTest {
        // The test above sets a field and reads it back on the next line, so it
        // passes against a filter that is never applied — which is what shipped:
        // `filteredHands` was correct and had no consumer anywhere in the repo,
        // while HandsScreen recomputed the list from the search box alone. The
        // chips highlighted and changed nothing.
        //
        // Collected, because `filteredHands` is `stateIn(WhileSubscribed)` and
        // `.value` never leaves its `emptyList()` initial value without a
        // subscriber — the EvolutionBadgeViewModel defect, which produced two
        // vacuous tests including the one that looked real.
        coEvery { repository.getAll() } returns listOf(
            Hand("h1", "Morning", enabled = true),
            Hand("h2", "Evening", enabled = false),
        )
        every { repository.observeRecentRuns(100) } returns flowOf(emptyList())
        val vm = viewModel()
        val seen = mutableListOf<List<Hand>>()
        val job = launch { vm.filteredHands.toList(seen) }
        advanceUntilIdle()

        vm.setStatusFilter("enabled")
        advanceUntilIdle()
        assertEquals(listOf("Morning"), seen.last().map { it.name })

        vm.setStatusFilter("disabled")
        advanceUntilIdle()
        assertEquals(listOf("Evening"), seen.last().map { it.name })

        vm.setStatusFilter("all")
        advanceUntilIdle()
        assertEquals(listOf("Morning", "Evening"), seen.last().map { it.name })

        job.cancel()
    }

    @Test
    fun `status filter and search apply together`() = runTest {
        coEvery { repository.getAll() } returns listOf(
            Hand("h1", "Morning brief", enabled = true),
            Hand("h2", "Morning walk", enabled = false),
            Hand("h3", "Evening wind-down", enabled = true),
        )
        every { repository.observeRecentRuns(100) } returns flowOf(emptyList())
        val vm = viewModel()
        val seen = mutableListOf<List<Hand>>()
        val job = launch { vm.filteredHands.toList(seen) }
        advanceUntilIdle()

        vm.setStatusFilter("enabled")
        vm.setSearchQuery("morning")
        advanceUntilIdle()

        assertEquals(listOf("Morning brief"), seen.last().map { it.name })
        job.cancel()
    }

    private fun viewModel() = HandsViewModel(repository, executor, registry, scheduler)
}
