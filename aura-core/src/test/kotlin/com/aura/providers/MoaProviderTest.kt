package com.aura.providers

import app.cash.turbine.test
import com.aura.data.UserPreferences
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Unit tests for [MoaProvider] focused on cancellation and structure.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class MoaProviderTest {

    private val testPresets = mapOf(
        "default" to MoaProvider.Preset(
            name = "default",
            referenceModels = listOf(
                MoaProvider.ModelRef("ollama", "glm-5.1:cloud"),
                MoaProvider.ModelRef("ollama", "kimi-k2.6:cloud"),
            ),
            aggregator = MoaProvider.ModelRef("deepseek", "deepseek-v4-pro:cloud"),
        ),
    )

    @Test
    fun `cancel terminates in-flight MoA reference work`() = runTest {
        val hangFlow: Flow<ProviderChunk> = flow {
            delay(Long.MAX_VALUE)
        }

        val providers = mapOf(
            "ollama" to mockk<Provider>(relaxed = true) {
                every { this@mockk.prefix } returns "ollama"
                every { chat("glm-5.1:cloud", any(), any(), any()) } returns hangFlow
                every { chat("kimi-k2.6:cloud", any(), any(), any()) } returns hangFlow
            },
            "deepseek" to mockk<Provider>(relaxed = true) {
                every { prefix } returns "deepseek"
                every { displayName } returns "DeepSeek"
                every { chat("deepseek-v4-pro:cloud", any(), any(), any()) } returns flowOf()
            },
        )
        val registry = ProviderRegistry(providers, mockk(relaxed = true))
        val moa = MoaProvider(registry = mockk { every { get() } returns registry }, presets = testPresets)

        val job = launch {
            assertFailsWith<CancellationException> {
                moa.chat("default", emptyList(), ChatOptions(), emptyList()).collect {}
            }
        }

        advanceTimeBy(100L)
        moa.cancel()
        job.join()
    }

    @Test
    fun `aggregator is not called after cancellation`() = runTest {
        val hangFlow: Flow<ProviderChunk> = flow {
            delay(Long.MAX_VALUE)
        }

        val aggregator = mockk<Provider>(relaxed = true) {
            every { prefix } returns "deepseek"
            every { displayName } returns "DeepSeek"
            every { chat("deepseek-v4-pro:cloud", any(), any(), any()) } returns flowOf()
        }

        val providers = mapOf(
            "ollama" to mockk<Provider>(relaxed = true) {
                every { this@mockk.prefix } returns "ollama"
                every { chat("glm-5.1:cloud", any(), any(), any()) } returns hangFlow
                every { chat("kimi-k2.6:cloud", any(), any(), any()) } returns hangFlow
            },
            "deepseek" to aggregator,
        )
        val registry = ProviderRegistry(providers, mockk(relaxed = true))
        val moa = MoaProvider(registry = mockk { every { get() } returns registry }, presets = testPresets)

        val job = launch {
            assertFailsWith<CancellationException> {
                moa.chat("default", emptyList(), ChatOptions(), emptyList()).collect {}
            }
        }

        advanceTimeBy(100L)
        moa.cancel()
        job.join()

        verify(exactly = 0) { aggregator.chat("deepseek-v4-pro:cloud", any(), any(), any()) }
    }

    @Test
    fun `starting a new MoA run cancels the previous run`() = runTest(timeout = 10.seconds) {
        val started = Channel<Unit>(Channel.UNLIMITED)
        val hangFlow: Flow<ProviderChunk> = flow {
            started.send(Unit)
            awaitCancellation()
        }
        val providers = mapOf(
            "ollama" to mockk<Provider>(relaxed = true) {
                every { this@mockk.prefix } returns "ollama"
                every { chat(any(), any(), any(), any()) } returns hangFlow
            },
            "deepseek" to mockk<Provider>(relaxed = true) {
                every { prefix } returns "deepseek"
                every { chat(any(), any(), any(), any()) } returns flowOf()
            },
        )
        val registry = ProviderRegistry(providers, mockk(relaxed = true))
        val moa = MoaProvider(registry = mockk { every { get() } returns registry }, presets = testPresets)

        val first = launch { moa.chat("default", emptyList(), ChatOptions(), emptyList()).collect {} }
        started.receive()
        started.receive()
        val second = launch { moa.chat("default", emptyList(), ChatOptions(), emptyList()).collect {} }
        started.receive()
        // Wait for the condition, not for the scheduler.
        //
        // Cancelling the channelFlow's Job has to resume the async{} children,
        // throw CancellationException through await(), close the channel and
        // cancel the collecting launch. Part of that chain runs on
        // Dispatchers.Default — real threads — and advanceUntilIdle() drains
        // only the *virtual* queue. It cannot see or advance real threads, so a
        // single drain can observe the chain mid-unwind and read isCancelled as
        // false.
        //
        // That is why the previous attempt here did not hold. Its comment
        // claimed advanceUntilIdle() was deterministic, which is true of the
        // test scheduler and not of the work this test is actually waiting on;
        // it narrowed the race rather than removing it, and still failed on a
        // cold CI runner (run 31842771419).
        //
        // So pump the virtual queue and yield real time until the asserted
        // condition actually holds. Bounded, so a real regression — a
        // cancellation that never propagates — still fails, in about a second
        // rather than on runTest's 10s timeout.
        var waited = 0L
        while (!first.isCancelled && waited < CANCEL_WAIT_MS) {
            advanceUntilIdle()
            runCurrent()
            Thread.sleep(POLL_MS)
            waited += POLL_MS
        }

        assertTrue(
            first.isCancelled,
            "the previous MoA run was not cancelled within ${CANCEL_WAIT_MS}ms",
        )
        second.cancel()
    }

    @Test
    fun `reference output propagates to aggregator message`() = runTest {
        val providers = mapOf(
            "ollama" to mockk<Provider>(relaxed = true) {
                every { this@mockk.prefix } returns "ollama"
                every { chat("glm-5.1:cloud", any(), any(), any()) } returns flowOf(
                    ProviderChunk(text = "Reference A output"),
                    ProviderChunk(finishReason = FinishReason.stop),
                )
                every { chat("kimi-k2.6:cloud", any(), any(), any()) } returns flowOf(
                    ProviderChunk(text = "Reference B output"),
                    ProviderChunk(finishReason = FinishReason.stop),
                )
            },
            "deepseek" to mockk<Provider>(relaxed = true) {
                every { prefix } returns "deepseek"
                every { displayName } returns "DeepSeek"
                every { chat("deepseek-v4-pro:cloud", any(), any(), any()) } returns flowOf(
                    ProviderChunk(text = "Aggregated"),
                    ProviderChunk(finishReason = FinishReason.stop),
                )
            },
        )
        val registry = ProviderRegistry(providers, mockk(relaxed = true))
        val moa = MoaProvider(registry = mockk { every { get() } returns registry }, presets = testPresets)

        moa.chat(
            "default",
            listOf(ProviderMessage(role = ProviderMessage.Role.user, content = "hi")),
            ChatOptions(),
            emptyList(),
        ).test {
            assertEquals("Aggregated", awaitItem().text)
            // The aggregator emits a finishReason=stop chunk after the text.
            awaitItem()
            awaitComplete()
        }
    }

    @Test
    fun `user configured roles expose custom preset`() = runTest {
        val preferences = mockk<UserPreferences> {
            every { moaReferenceModels } returns flowOf(
                listOf("ref-one:test-a", "ref-two:test-b"),
            )
            every { moaAggregatorModel } returns flowOf("agg:test-c")
        }
        val providers = mapOf(
            "ref-one" to configuredProvider("ref-one"),
            "ref-two" to configuredProvider("ref-two"),
            "agg" to configuredProvider("agg"),
        )
        val registry = ProviderRegistry(providers, mockk(relaxed = true))
        val moa = MoaProvider(
            registry = mockk { every { get() } returns registry },
            userPreferences = preferences,
            scope = this,
        )

        runCurrent()

        assertTrue(moa.isConfigured())
        assertEquals(listOf("custom"), moa.listModels())
    }

    @Test
    fun `missing presets disable MoA and return a typed error`() = runTest {
        val registry = ProviderRegistry(emptyMap(), mockk(relaxed = true), mockk(relaxed = true))
        val moa = MoaProvider(
            registry = mockk { every { get() } returns registry },
            presets = emptyMap(),
        )

        assertEquals(false, moa.isConfigured())
        assertEquals(emptyList(), moa.listModels())
        moa.chat("default", emptyList(), ChatOptions(), emptyList()).test {
            assertEquals("moa_no_presets", awaitItem().error?.code)
            awaitComplete()
        }
    }

    private fun configuredProvider(providerPrefix: String): Provider = mockk(relaxed = true) {
        every { prefix } returns providerPrefix
        every { isConfigured() } returns true
    }

    private companion object {
        /**
         * Real milliseconds, not virtual: part of the cancellation chain runs on
         * `Dispatchers.Default`, which the test scheduler cannot advance.
         *
         * Comfortably long enough for a loaded CI runner and short enough that a
         * cancellation which never arrives fails in about a second instead of
         * hanging to runTest's 10s timeout.
         */
        const val CANCEL_WAIT_MS = 2_000L
        const val POLL_MS = 10L
    }
}
