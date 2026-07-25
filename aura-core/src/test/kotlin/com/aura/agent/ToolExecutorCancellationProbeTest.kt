package com.aura.agent

import io.mockk.mockk
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Probe: when a tool exceeds its timeout, is the tool body actually stopped,
 * or is only the caller unblocked while the tool keeps running?
 *
 * The distinction matters. `ToolExecutor` runs tools as
 * `withTimeout { runInterruptible(Dispatchers.IO) { runBlocking { ... } } }`.
 * `runInterruptible` cancels by interrupting the worker thread, which is the
 * documented mechanism for *blocking* calls. The open question is whether a
 * purely-suspending tool — which never blocks and so never sits in an
 * interruptible wait — also stops.
 *
 * It does: `runBlocking`'s event loop checks `Thread.interrupted()` on each
 * pass and calls `cancelCoroutine()` before throwing `InterruptedException`,
 * so the interrupt is converted into ordinary coroutine cancellation and
 * propagates to the suspending body.
 *
 * These tests exist to pin that, because it is not obvious from the call
 * shape and a future refactor that drops `runInterruptible` or `runBlocking`
 * would silently regress it into "caller times out, tool keeps burning a
 * thread and the user's API quota".
 *
 * Real coroutine time throughout: virtual time would advance the outer
 * timeout independently of the worker thread.
 */
class ToolExecutorCancellationProbeTest {

    private val registry = mockk<ToolRegistry>()
    private val context = mockk<android.content.Context>(relaxed = true)
    private val executor = ToolExecutor(registry, context)

    @Test
    fun `suspending tool body stops running after the timeout fires`() = runBlocking {
        val reachedEnd = AtomicBoolean(false)
        val ticks = AtomicInteger(0)

        val tool = Tool(
            name = "ticker",
            description = "counts until cancelled",
            risk = ToolRisk.READ_ONLY,
            execute = { _, _ ->
                repeat(100) {
                    delay(50L)
                    ticks.incrementAndGet()
                }
                reachedEnd.set(true)
                ToolResult.Ok("finished")
            },
        )
        io.mockk.every { registry.get("ticker") } returns tool

        val result = executor.execute(
            name = "ticker",
            argumentsJson = "{}",
            ctx = ToolContext(conversationId = "c1", timeout = 200L),
        )

        assertTrue(result is ToolResult.Error, "expected timeout error, got $result")
        assertEquals("tool_timeout", (result as ToolResult.Error).code)

        val ticksAtTimeout = ticks.get()
        // Give the tool a window far larger than its own tick interval. If
        // cancellation did not propagate, the counter keeps climbing.
        Thread.sleep(600L)

        assertFalse(reachedEnd.get(), "tool body ran to completion despite the timeout")
        assertEquals(
            ticksAtTimeout,
            ticks.get(),
            "tool kept ticking after the timeout — cancellation did not reach the suspending body",
        )
    }

    @Test
    fun `blocking tool body stops running after the timeout fires`() = runBlocking {
        val reachedEnd = AtomicBoolean(false)

        val tool = Tool(
            name = "blocker",
            description = "blocks the thread",
            risk = ToolRisk.READ_ONLY,
            execute = { _, _ ->
                // Genuine blocking work, the case runInterruptible targets.
                try {
                    Thread.sleep(5_000L)
                    reachedEnd.set(true)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw e
                }
                ToolResult.Ok("finished")
            },
        )
        io.mockk.every { registry.get("blocker") } returns tool

        val result = executor.execute(
            name = "blocker",
            argumentsJson = "{}",
            ctx = ToolContext(conversationId = "c1", timeout = 200L),
        )

        assertTrue(result is ToolResult.Error, "expected an error result, got $result")
        Thread.sleep(600L)
        assertFalse(reachedEnd.get(), "blocking tool ran to completion despite the timeout")
    }
}
