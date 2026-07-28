package com.aura.agentrun

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DagResolverTest {

    private val resolver = DagResolver()

    private fun step(
        id: kotlin.String,
        dependsOn: kotlin.String = "[]",
        status: kotlin.String = "PENDING",
    ) = StepEntity(
        id = id,
        agentRunId = "run1",
        toolName = "test_tool",
        dependsOn = dependsOn,
        status = status,
    )

    @Test
    fun readySteps_returns_root_steps_with_no_dependencies() {
        val steps = listOf(
            step("s1"),
            step("s2"),
            step("s3", dependsOn = "[\"s1\"]"),
        )
        val ready = resolver.readySteps(steps)
        assertEquals(2, ready.size)
        assertTrue(ready.any { it.id == "s1" })
        assertTrue(ready.any { it.id == "s2" })
    }

    @Test
    fun readySteps_waits_for_dependencies() {
        val steps = listOf(
            step("s1", status = "RUNNING"),
            step("s2", dependsOn = "[\"s1\"]"),
        )
        val ready = resolver.readySteps(steps)
        assertTrue("Expected no ready steps, got ${ready.size}", ready.isEmpty())
    }

    @Test
    fun readySteps_unblocks_when_dependency_succeeds() {
        val steps = listOf(
            step("s1", status = "SUCCESS"),
            step("s2", dependsOn = "[\"s1\"]"),
        )
        val ready = resolver.readySteps(steps)
        assertEquals(1, ready.size)
        assertEquals("s2", ready.first().id)
    }

    @Test
    fun topologicalBatches_returns_correct_layers() {
        val steps = listOf(
            step("s1"),
            step("s2"),
            step("s3", dependsOn = "[\"s1\"]"),
            step("s4", dependsOn = "[\"s1\",\"s2\"]"),
            step("s5", dependsOn = "[\"s3\",\"s4\"]"),
        )
        val batches = resolver.topologicalBatches(steps)
        assertTrue("Expected at least 3 batches, got ${batches.size}", batches.size >= 3)
        // First batch should have s1 and s2 (no deps)
        val firstBatch = batches.first()
        assertTrue(firstBatch.any { it.id == "s1" })
        assertTrue(firstBatch.any { it.id == "s2" })
    }

    @Test
    fun hasCycle_returns_true_for_circular_dependency() {
        val steps = listOf(
            step("s1", dependsOn = "[\"s2\"]"),
            step("s2", dependsOn = "[\"s1\"]"),
        )
        assertTrue(resolver.hasCycle(steps))
    }

    @Test
    fun hasCycle_returns_false_for_valid_dag() {
        val steps = listOf(
            step("s1"),
            step("s2", dependsOn = "[\"s1\"]"),
            step("s3", dependsOn = "[\"s2\"]"),
        )
        assertFalse(resolver.hasCycle(steps))
    }

    @Test
    fun readySteps_excludes_non_pending_steps() {
        val steps = listOf(
            step("s1", status = "SUCCESS"),
            step("s2", status = "FAILED"),
            step("s3", status = "RUNNING"),
            step("s4"),
        )
        val ready = resolver.readySteps(steps)
        assertEquals(1, ready.size)
        assertEquals("s4", ready.first().id)
    }

    /**
     * P1-AGENTIC-F4 regression: when a run is paused awaiting user
     * approval, the executor must NOT mark it FAILED. The resolver
     * now reports the BLOCKED step IDs so the executor can transition
     * to PAUSED instead.
     */
    @Test
    fun `blockedStepIds returns the ids of BLOCKED steps`() {
        val resolver = DagResolver()
        val steps = listOf(
            step(id = "a", status = "SUCCESS", dependsOn = "[]"),
            step(id = "b", status = "BLOCKED", dependsOn = "[]"),
            step(id = "c", status = "PENDING", dependsOn = """["b"]"""),
        )
        val blocked = resolver.blockedStepIds(steps)
        assertEquals(listOf("b"), blocked)
    }

    @Test
    fun `blockedStepIds is empty when no step is BLOCKED`() {
        val resolver = DagResolver()
        val steps = listOf(
            step(id = "a", status = "SUCCESS", dependsOn = "[]"),
            step(id = "b", status = "PENDING", dependsOn = """["a"]"""),
        )
        assertEquals(emptyList<kotlin.String>(), resolver.blockedStepIds(steps))
    }
}