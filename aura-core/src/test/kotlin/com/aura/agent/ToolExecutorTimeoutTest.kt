package com.aura.agent

import io.mockk.mockk
import java.util.concurrent.Executors
import kotlin.system.measureTimeMillis
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for the per-tool timeout enforced by [ToolExecutor] via
 * `withTimeout(ctx.timeout)`. Tools that exceed their budget must be
 * aborted with a typed `tool_timeout` error instead of hanging the
 * agent loop indefinitely.
 *
 * Tool execution crosses an interruptible `Dispatchers.IO` boundary. These
 * tests therefore use real coroutine time: virtual `runTest` time would
 * advance the outer timeout independently of the worker thread, and the
 * nested `runBlocking` bridge parks a real thread that no TestDispatcher
 * can fast-forward. Determinism comes from margins instead: every
 * elapsed-time assertion has at least a 5-second gap between the pass and
 * fail bound, so a cold or heavily-loaded CI runner cannot flip the result.
 */
class ToolExecutorTimeoutTest {

    private val registry = mockk<ToolRegistry>()
    private val context = mockk<android.content.Context>(relaxed = true)
    private val executor = ToolExecutor(registry, context)

    private fun makeSuspendingTool(
        name: String,
        timeoutMs: Long = DEFAULT_TOOL_TIMEOUT_MS,
        work: suspend () -> ToolResult,
    ) = Tool(
        name = name,
        description = "$name test tool",
        risk = ToolRisk.READ_ONLY,
        execute = { _, _ -> work() },
        timeoutMs = timeoutMs,
    )

    @Test
    fun `a tool's own timeoutMs applies when the caller states no budget`() = runBlocking {
        // The regression. Before Tool.timeoutMs existed, ToolContext.timeout
        // defaulted to 30s and MemoryAugmentedAgenticLoop never passed one, so
        // a tool needing longer was truncated on every single call — which is
        // why deep_research, budgeting 120s internally, had never once
        // completed. Under the old code this tool would run to completion and
        // return Ok, because the 5s of work fits inside a 30s ceiling nobody
        // chose. It must now be the tool's own 300ms that decides.
        val slow = makeSuspendingTool("own_budget_tool", timeoutMs = 300L) {
            delay(5_000L)
            ToolResult.Ok("should not get here")
        }
        io.mockk.every { registry.get("own_budget_tool") } returns slow
        val result = executor.execute(
            name = "own_budget_tool",
            argumentsJson = """{}""",
            // No timeout: the caller has no opinion, so the tool's own applies.
            ctx = ToolContext(conversationId = "c1"),
        )
        assertTrue(result is ToolResult.Error, "expected Error, got $result")
        assertEquals("tool_timeout", (result as ToolResult.Error).code)
    }

    @Test
    fun `an explicit caller budget overrides the tool's own`() = runBlocking {
        // Precedence, in the direction RunHandWorker and AgentRunExecutorWorker
        // rely on: a caller that owns the run owns the ceiling. Without this the
        // pipeline's 300s budget could not tighten a tool that asked for more.
        val slow = makeSuspendingTool("override_tool", timeoutMs = 60_000L) {
            delay(5_000L)
            ToolResult.Ok("should not get here")
        }
        io.mockk.every { registry.get("override_tool") } returns slow
        val result = executor.execute(
            name = "override_tool",
            argumentsJson = """{}""",
            ctx = ToolContext(conversationId = "c1", timeout = 100L),
        )
        assertTrue(result is ToolResult.Error, "expected Error, got $result")
        assertEquals("tool_timeout", (result as ToolResult.Error).code)
    }

    @Test
    fun `slow tool returns tool_timeout error when exceeding ctx timeout`() = runBlocking {
        val slow = makeSuspendingTool("slow_tool") {
            delay(5_000L)
            ToolResult.Ok("slow_tool ran")
        }
        io.mockk.every { registry.get("slow_tool") } returns slow
        val result = executor.execute(
            name = "slow_tool",
            argumentsJson = """{}""",
            ctx = ToolContext(conversationId = "c1", timeout = 100L),
        )
        assertTrue(result is ToolResult.Error, "expected Error, got $result")
        val err = result as ToolResult.Error
        assertEquals("tool_timeout", err.code)
        assertTrue(err.message.contains("0s") || err.message.contains("timed out"),
            "message should mention timeout, got: ${err.message}")
    }

    @Test
    fun `fast tool completes normally within timeout`() = runBlocking {
        val fast = makeSuspendingTool("fast_tool") {
            delay(10L)
            ToolResult.Ok("fast_tool ran")
        }
        io.mockk.every { registry.get("fast_tool") } returns fast
        val result = executor.execute(
            name = "fast_tool",
            argumentsJson = """{}""",
            // Generous budget: a 10ms tool must never time out, even on a
            // runner where thread scheduling stalls for whole seconds.
            ctx = ToolContext(conversationId = "c1", timeout = 10_000L),
        )
        assertTrue(result is ToolResult.Ok, "expected Ok, got $result")
    }

    @Test
    fun `tool execution leaves the caller thread`() = runBlocking {
        val threadTool = makeSuspendingTool("thread_tool") {
            ToolResult.Ok(Thread.currentThread().name)
        }
        io.mockk.every { registry.get("thread_tool") } returns threadTool
        val caller = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "ui-caller")
        }.asCoroutineDispatcher()

        try {
            val result = withContext(caller) {
                executor.execute(
                    name = "thread_tool",
                    argumentsJson = "{}",
                    ctx = ToolContext(conversationId = "c1"),
                )
            } as ToolResult.Ok

            assertFalse(
                result.output.contains("ui-caller"),
                "tool ran on caller thread: ${result.output}",
            )
        } finally {
            caller.close()
        }
    }

    @Test
    fun `blocking tool is interrupted when timeout expires`() = runBlocking {
        // The sleep must be much longer than the pass threshold below: if
        // interruption were broken, execute() would only return after the
        // full sleep, so elapsed >= 10s fails; if it works, elapsed is
        // ~100ms plus scheduling noise, far under 5s even on a cold runner.
        val blocking = makeSuspendingTool("blocking_tool") {
            Thread.sleep(10_000L)
            ToolResult.Ok("finished")
        }
        io.mockk.every { registry.get("blocking_tool") } returns blocking
        lateinit var result: ToolResult

        val elapsedMs = measureTimeMillis {
            result = executor.execute(
                name = "blocking_tool",
                argumentsJson = "{}",
                ctx = ToolContext(conversationId = "c1", timeout = 100L),
            )
        }

        assertTrue(result is ToolResult.Error, "expected timeout error, got $result")
        assertEquals("tool_timeout", (result as ToolResult.Error).code)
        assertTrue(elapsedMs < 5_000L, "blocking timeout took ${elapsedMs}ms — thread was not interrupted promptly")
    }
}
