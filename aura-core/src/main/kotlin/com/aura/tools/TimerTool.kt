package com.aura.tools

import com.aura.agent.Tool
import com.aura.agent.ToolCall
import com.aura.agent.ToolContext
import com.aura.agent.ToolResult
import com.aura.agent.ToolRisk
import com.aura.providers.ToolDefinition
import com.aura.providers.ToolParameters
import com.aura.providers.ToolProperty
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Timer / stopwatch tool. The agent can start a timer, check how much
 * time has elapsed, and stop it. Timers are in-memory only (not persisted)
 * — they reset on app restart.
 *
 * Risk: WRITE_LOCAL. The `start` and `stop` actions mutate the in-process
 * `timers` map, which constitutes local state. The privacy boundary in
 * [com.aura.agent.ToolExecutor] refuses tools at this risk level when
 * the user is in incognito mode — the `check` action (read-only) would
 * still pass through, but `start`/`stop` are blocked.
 */
@Singleton
class TimerTool @Inject constructor() {

    // Map of timer_id → start time (millis). Bounded by
    // MAX_TIMERS to prevent unbounded growth from
    // long-running sessions where the user starts many
    // timers but never stops them. When the cap is hit,
    // the OLDEST timer is evicted (FIFO) on the next
    // start. The evict is "first" not "least recently
    // used" because timers don't have a use-tracked
    // recency — the user can check/stop an old timer
    // and "old" here means "started longest ago."
    //
    // P2 AGENTIC B2: pre-fix, the map grew unbounded.
    // In a session where the agent repeatedly calls
    // set_timer without stop (e.g. for background
    // countdowns the model forgot about), the map
    // would accumulate thousands of entries. Process
    // restart resets the map (timers are in-memory
    // only) so the leak is per-session.
    private val timers = LinkedHashMap<String, Long>(16, 0.75f, false)
    private val timersLock = Any()

    fun definition() = ToolDefinition(
        name = "timer",
        description = "Start, check, or stop a timer. Actions: 'start' (returns a timer ID), 'check' (returns elapsed seconds), 'stop' (returns final elapsed seconds and removes the timer). Timers are in-memory only and reset on app restart — do not rely on them across sessions.",
        parameters = ToolParameters(
            properties = mapOf(
                "action" to ToolProperty(
                    type = "string",
                    description = "One of: start, check, stop",
                ),
                "timer_id" to ToolProperty(
                    type = "string",
                    description = "Timer ID (required for check and stop, ignored for start)",
                ),
            ),
            required = listOf("action"),
        ),
    )

    val tool = Tool(
        name = "timer",
        description = definition().description,
        risk = ToolRisk.WRITE_LOCAL,
        parameters = definition().parameters,
        execute = { call, _ ->
            val action = call.arguments["action"] as? String
                ?: return@Tool ToolResult.Error("missing 'action' argument", "bad_args")
            when (action.lowercase()) {
                "start" -> {
                    val id = UUID.randomUUID().toString()
                    synchronized(timersLock) {
                        // Evict oldest if at cap. accessOrder=false so
                        // .keys.first() gives insertion order (oldest).
                        while (timers.size >= MAX_TIMERS) {
                            val first = timers.keys.firstOrNull() ?: break
                            timers.remove(first)
                        }
                        timers[id] = System.currentTimeMillis()
                    }
                    ToolResult.Ok("Timer started. ID: $id")
                }
                "check" -> {
                    val id = call.arguments["timer_id"] as? String
                        ?: return@Tool ToolResult.Error("missing 'timer_id' for check action", "bad_args")
                    val start = synchronized(timersLock) { timers[id] }
                        ?: return@Tool ToolResult.Error("No timer with ID $id", "not_found")
                    val elapsedSec = (System.currentTimeMillis() - start) / 1000.0
                    ToolResult.Ok("Elapsed: %.1f seconds".format(elapsedSec))
                }
                "stop" -> {
                    val id = call.arguments["timer_id"] as? String
                        ?: return@Tool ToolResult.Error("missing 'timer_id' for stop action", "bad_args")
                    val start = synchronized(timersLock) { timers.remove(id) }
                        ?: return@Tool ToolResult.Error("No timer with ID $id", "not_found")
                    val elapsedSec = (System.currentTimeMillis() - start) / 1000.0
                    ToolResult.Ok("Timer stopped. Total elapsed: %.1f seconds".format(elapsedSec))
                }
                else -> ToolResult.Error("Unknown action '$action'. Use: start, check, stop", "bad_args")
            }
        },
    category = "productivity")

    companion object {
        /** Max concurrent timers. ~100 active countdowns is enough for daily use. */
        private const val MAX_TIMERS = 100
    }
}