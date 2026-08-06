package com.aura.agents

import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class SubagentManagerTest {

    private val manager = SubagentManager()

    private fun makeTask(
        role: kotlin.String = "writer",
        objective: kotlin.String = "Write a scene",
        budgetMs: kotlin.Long = 5000L,
    ) = SubagentTask(
        id = "task1",
        spec = SubagentSpec(
            role = role,
            objective = objective,
            budgetMs = budgetMs,
        ),
        parentRunId = "run1",
    )

    @Test
    fun spawn_returns_success_when_executor_completes() = runTest {
        val task = makeTask()
        val result = manager.spawn(task) { t ->
            SubagentResult(
                taskId = t.id,
                success = true,
                output = "Scene written",
                rationale = "Drafted based on brief",
            )
        }
        assertTrue(result.success)
        assertEquals("task1", result.taskId)
        assertEquals("Scene written", result.output)
    }

    @Test
    fun spawn_returns_failure_on_timeout() = runTest {
        val task = makeTask(budgetMs = 50L)
        val result = manager.spawn(task) { _ ->
            delay(200)
            SubagentResult(taskId = "task1", success = true)
        }
        assertFalse(result.success)
        assertTrue(result.error.contains("timed out"))
    }

    @Test
    fun spawn_rethrows_cancellation_instead_of_converting_it_to_a_result() = runTest {
        // Regression: spawn used to swallow CancellationException and
        // return a fabricated "Subagent cancelled" result — a cancelled
        // parent kept collecting results from children that were supposed
        // to be dead, breaking structured concurrency. Cancellation must
        // propagate. (The subagent's own budget timeout is a different
        // exception and still becomes a result — see
        // spawn_returns_failure_on_timeout.)
        val task = makeTask()
        var thrown: Throwable? = null
        try {
            manager.spawn(task) { _ ->
                throw kotlinx.coroutines.CancellationException("Parent cancelled")
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            thrown = e
        }
        assertTrue("CancellationException must propagate out of spawn", thrown != null)
        assertEquals("Parent cancelled", thrown!!.message)
    }

    @Test
    fun spawnAll_executes_multiple_tasks() = runTest {
        val tasks = listOf(
            manager.createTask(SubagentSpec(role = "researcher", objective = "Find sources"), "run1"),
            manager.createTask(SubagentSpec(role = "writer", objective = "Draft outline"), "run1"),
            manager.createTask(SubagentSpec(role = "critic", objective = "Review draft"), "run1"),
        )
        val results = manager.spawnAll(tasks) { task ->
            SubagentResult(
                taskId = task.id,
                success = true,
                output = "Done for ${task.spec.role}",
            )
        }
        assertEquals(3, results.size)
        assertTrue(results.all { it.success })
    }

    @Test
    fun createTask_generates_unique_id() = runTest {
        val spec = SubagentSpec(role = "writer", objective = "test")
        val task1 = manager.createTask(spec, "run1")
        val task2 = manager.createTask(spec, "run1")
        assertTrue(task1.id != task2.id)
        assertEquals("run1", task1.parentRunId)
    }
}