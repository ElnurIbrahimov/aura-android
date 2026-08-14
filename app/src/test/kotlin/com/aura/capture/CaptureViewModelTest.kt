package com.aura.capture

import com.aura.memory.MemoryStore
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class CaptureViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val memoryStore = mockk<MemoryStore>(relaxed = true)
    private lateinit var viewModel: CaptureViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        viewModel = CaptureViewModel(memoryStore)
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    // ---- the write ------------------------------------------------------

    @Test
    fun `a capture is written with no model and no network`() = runTest(dispatcher) {
        coEvery { memoryStore.storeIfAbsent(any(), any(), any(), any(), any(), any()) } returns "m1"

        viewModel.capture("The GPU budget was cut to 40k")
        advanceUntilIdle()

        // The whole point: nothing here consults a provider, a model catalog or
        // the network. The old fast path refused outright without a verified
        // model.
        coVerify(exactly = 1) {
            memoryStore.storeIfAbsent(
                content = "The GPU budget was cut to 40k",
                source = CaptureViewModel.SOURCE,
                category = any(),
                importance = CaptureViewModel.IMPORTANCE,
                tags = any(),
                scope = any(),
            )
        }
        assertIs<CaptureViewModel.State.Saved>(viewModel.state.value)
    }

    @Test
    fun `surrounding whitespace is not part of the memory`() = runTest(dispatcher) {
        val content = slotOfContent()
        viewModel.capture("   a thought with padding   ")
        advanceUntilIdle()

        assertEquals("a thought with padding", content())
    }

    @Test
    fun `blank text writes nothing`() = runTest(dispatcher) {
        viewModel.capture("   ")
        advanceUntilIdle()

        coVerify(exactly = 0) { memoryStore.storeIfAbsent(any(), any(), any(), any(), any(), any()) }
        assertIs<CaptureViewModel.State.Composing>(viewModel.state.value)
    }

    // ---- the gate is bypassed, deliberately ------------------------------

    /**
     * The write gate rejects pleasantries, and it is right to — for *incidental*
     * chat, where the cost of a false positive is a store full of "Hey you".
     * A capture is not incidental: the user selected the text and tapped a
     * button called Aura. There is nothing left to infer, so the gate's verdict
     * must not apply.
     */
    @Test
    fun `something the write gate would reject is still captured`() = runTest(dispatcher) {
        coEvery { memoryStore.storeIfAbsent(any(), any(), any(), any(), any(), any()) } returns "m1"

        viewModel.capture("hello")
        advanceUntilIdle()

        assertIs<CaptureViewModel.State.Saved>(viewModel.state.value)
    }

    @Test
    fun `the gate's categoriser is still used, only its verdict is not`() = runTest(dispatcher) {
        val category = slotOfCategory()

        viewModel.capture("I prefer terse answers with no preamble")
        advanceUntilIdle()

        assertEquals("preference", category())
    }

    @Test
    fun `an uncategorisable thought falls back rather than failing`() = runTest(dispatcher) {
        val category = slotOfCategory()

        viewModel.capture("The GPU budget was cut to 40k")
        advanceUntilIdle()

        assertEquals("fact", category())
    }

    // ---- undo -----------------------------------------------------------

    @Test
    fun `undo removes exactly the row this capture wrote`() = runTest(dispatcher) {
        coEvery { memoryStore.storeIfAbsent(any(), any(), any(), any(), any(), any()) } returns "m-written"

        viewModel.capture("a thought worth keeping")
        advanceUntilIdle()
        viewModel.undo()
        advanceUntilIdle()

        coVerify(exactly = 1) { memoryStore.forget("m-written") }
        assertIs<CaptureViewModel.State.Composing>(viewModel.state.value)
    }

    /**
     * `storeIfAbsent` returns null when an identical memory already exists.
     * That is a success — the thought is in Aura — but offering Undo would
     * delete a row this capture did not create, which is someone else's memory
     * disappearing because you shared the same sentence twice.
     */
    @Test
    fun `a duplicate offers nothing to undo`() = runTest(dispatcher) {
        coEvery { memoryStore.storeIfAbsent(any(), any(), any(), any(), any(), any()) } returns null

        viewModel.capture("already in there")
        advanceUntilIdle()

        assertIs<CaptureViewModel.State.Duplicate>(viewModel.state.value)

        viewModel.undo()
        advanceUntilIdle()
        coVerify(exactly = 0) { memoryStore.forget(any()) }
    }

    // ---- failing ---------------------------------------------------------

    @Test
    fun `a failed write is reported rather than swallowed`() = runTest(dispatcher) {
        coEvery {
            memoryStore.storeIfAbsent(any(), any(), any(), any(), any(), any())
        } throws IllegalStateException("disk full")

        viewModel.capture("a thought")
        advanceUntilIdle()

        val state = viewModel.state.value
        assertIs<CaptureViewModel.State.Failed>(state)
        assertTrue("disk full" in state.message, state.message)
    }

    // ---- helpers ---------------------------------------------------------

    private fun slotOfContent(): () -> String {
        val slot = io.mockk.slot<String>()
        coEvery {
            memoryStore.storeIfAbsent(capture(slot), any(), any(), any(), any(), any())
        } returns "m1"
        return { slot.captured }
    }

    private fun slotOfCategory(): () -> String {
        val slot = io.mockk.slot<String>()
        coEvery {
            memoryStore.storeIfAbsent(any(), any(), capture(slot), any(), any(), any())
        } returns "m1"
        return { slot.captured }
    }
}
