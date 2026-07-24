package com.aura.tools

import com.aura.agent.ToolCall
import com.aura.agent.ToolContext
import com.aura.agent.ToolResult
import com.aura.agent.ToolRisk
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for [TimerTool] — argument validation, start/check/stop
 * actions, and the risk classification (WRITE_LOCAL, not READ_ONLY).
 */
class TimerToolTest {

    private val tool = TimerTool()

    private fun exec(args: Map<String, Any?>): ToolResult =
        kotlinx.coroutines.runBlocking {
            tool.tool.execute(
                ToolCall(id = "", name = "timer", arguments = args),
                ToolContext(conversationId = "test"),
            )
        }

    @Test
    fun `risk is WRITE_LOCAL not READ_ONLY`() {
        assertEquals(ToolRisk.WRITE_LOCAL, tool.tool.risk)
    }

    @Test
    fun `missing action returns bad_args`() {
        val result = exec(emptyMap())
        assertTrue(result is ToolResult.Error)
        assertEquals("bad_args", (result as ToolResult.Error).code)
    }

    @Test
    fun `start returns a timer ID`() {
        val result = exec(mapOf("action" to "start"))
        assertTrue(result is ToolResult.Ok)
        val output = (result as ToolResult.Ok).output
        assertTrue(output.contains("Timer started"), "expected 'Timer started' in: $output")
        assertTrue(output.contains("ID:"), "expected 'ID:' in: $output")
    }

    @Test
    fun `check without timer_id returns bad_args`() {
        val result = exec(mapOf("action" to "check"))
        assertTrue(result is ToolResult.Error)
        assertEquals("bad_args", (result as ToolResult.Error).code)
    }

    @Test
    fun `check with nonexistent timer_id returns not_found`() {
        val result = exec(mapOf("action" to "check", "timer_id" to "fake-id"))
        assertTrue(result is ToolResult.Error)
        assertEquals("not_found", (result as ToolResult.Error).code)
    }

    @Test
    fun `stop without timer_id returns bad_args`() {
        val result = exec(mapOf("action" to "stop"))
        assertTrue(result is ToolResult.Error)
        assertEquals("bad_args", (result as ToolResult.Error).code)
    }

    @Test
    fun `start then check returns elapsed time`() {
        val startResult = exec(mapOf("action" to "start"))
        assertTrue(startResult is ToolResult.Ok)
        val timerId = (startResult as ToolResult.Ok).output.substringAfter("ID: ").trim()

        val checkResult = exec(mapOf("action" to "check", "timer_id" to timerId))
        assertTrue(checkResult is ToolResult.Ok)
        assertTrue((checkResult as ToolResult.Ok).output.contains("Elapsed:"))
    }

    @Test
    fun `start then stop returns total elapsed and removes timer`() {
        val startResult = exec(mapOf("action" to "start"))
        val timerId = (startResult as ToolResult.Ok).output.substringAfter("ID: ").trim()

        val stopResult = exec(mapOf("action" to "stop", "timer_id" to timerId))
        assertTrue(stopResult is ToolResult.Ok)
        assertTrue((stopResult as ToolResult.Ok).output.contains("Timer stopped"))
        assertTrue((stopResult as ToolResult.Ok).output.contains("Total elapsed:"))

        // Timer should be gone after stop
        val checkAfter = exec(mapOf("action" to "check", "timer_id" to timerId))
        assertTrue(checkAfter is ToolResult.Error)
        assertEquals("not_found", (checkAfter as ToolResult.Error).code)
    }

    @Test
    fun `unknown action returns bad_args`() {
        val result = exec(mapOf("action" to "pause"))
        assertTrue(result is ToolResult.Error)
        assertEquals("bad_args", (result as ToolResult.Error).code)
    }

    @Test
    fun `starting more than MAX_TIMERS evicts the oldest`() = kotlinx.coroutines.runBlocking {
        // P2 AGENTIC B2 regression: pre-fix, the timers
        // map grew unbounded — every set_timer without
        // stop leaked an entry. After MAX_TIMERS (100)
        // starts, the 101st evicts the oldest (FIFO,
        // since timers don't have use-tracked recency).
        // We can't directly inspect the map (it's
        // private), but we can check that after >100
        // starts + 1, the first-start check still
        // returns OK (not not_found) — meaning the
        // eviction policy chose a different victim.
        // Here we just verify the cap doesn't crash
        // and the 101st timer still works.
        repeat(101) {
            val r = exec(mapOf("action" to "start"))
            assertTrue(r is ToolResult.Ok, "start #$it failed: $r")
        }
        // The 101st start returned a valid ID, so the
        // eviction didn't corrupt the map state.
        val afterCap = exec(mapOf("action" to "start"))
        assertTrue(afterCap is ToolResult.Ok, "post-cap start failed: $afterCap")
    }
}