package com.aura.agentrun

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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

    // ── stuckReason ────────────────────────────────────────────────
    //
    // The executor already failed an unsatisfiable graph — a cycle never hung a
    // run. What it could not do was say which of three quite different things
    // had gone wrong, and the sentence it used fitted the commonest one worst.

    @Test
    fun `a satisfiable run is not stuck`() {
        val steps = listOf(
            step("a", status = "SUCCESS"),
            step("b", dependsOn = """["a"]"""),
            step("c", dependsOn = """["b"]"""),
        )
        assertNull(resolver.stuckReason(steps))
    }

    @Test
    fun `a run with nothing pending is not stuck`() {
        val steps = listOf(step("a", status = "SUCCESS"), step("b", status = "FAILED"))
        assertNull(resolver.stuckReason(steps))
    }

    @Test
    fun `a step waiting on an approval is not stuck`() {
        // BLOCKED is reachable: the user can still approve it. Calling this
        // stuck would fail runs that are merely waiting for a fingerprint.
        val steps = listOf(
            step("a", status = "BLOCKED"),
            step("b", dependsOn = """["a"]"""),
        )
        assertNull(resolver.stuckReason(steps))
    }

    @Test
    fun `a failed dependency is named, and named as failed`() {
        val steps = listOf(
            step("a", status = "FAILED"),
            step("b", dependsOn = """["a"]"""),
        )
        val reason = resolver.stuckReason(steps)
        assertNotNull(reason)
        assertTrue("should name the dependent step: $reason", "b" in reason!!)
        assertTrue("should name the failed step: $reason", "a" in reason)
        assertTrue("should say it failed: $reason", "failed" in reason)
    }

    @Test
    fun `failure propagates down a chain and still names the root cause`() {
        val steps = listOf(
            step("a", status = "FAILED"),
            step("b", dependsOn = """["a"]"""),
            step("c", dependsOn = """["b"]"""),
        )
        val reason = resolver.stuckReason(steps)!!
        assertTrue("both dependents are stuck: $reason", reason.startsWith("2 steps"))
        assertTrue("the root cause is the failure: $reason", "failed" in reason)
    }

    @Test
    fun `a dependency that is not in the run is reported as missing, not failed`() {
        val steps = listOf(step("b", dependsOn = """["ghost"]"""))
        val reason = resolver.stuckReason(steps)!!
        assertTrue("should name the ghost: $reason", "ghost" in reason)
        assertTrue("should say it is not in the run: $reason", "not part of this run" in reason)
    }

    @Test
    fun `a cycle is reported as a cycle rather than as unmet dependencies`() {
        // The case `hasCycle` existed for, and never had a caller to tell.
        val steps = listOf(
            step("a", dependsOn = """["b"]"""),
            step("b", dependsOn = """["a"]"""),
        )
        val reason = resolver.stuckReason(steps)!!
        assertTrue("should say circular: $reason", "circular" in reason)
        assertTrue("both steps are stuck: $reason", reason.startsWith("2 steps"))
    }

    @Test
    fun `a three-step cycle terminates and is reported`() {
        val steps = listOf(
            step("a", dependsOn = """["c"]"""),
            step("b", dependsOn = """["a"]"""),
            step("c", dependsOn = """["b"]"""),
        )
        assertTrue("circular" in resolver.stuckReason(steps)!!)
    }

    @Test
    fun `a step depending on itself is a cycle`() {
        assertTrue("circular" in resolver.stuckReason(listOf(step("a", dependsOn = """["a"]""")))!!)
    }

    @Test
    fun `a cycle beside healthy work leaves the healthy work alone`() {
        // Only the doomed steps are counted, so the message does not overstate
        // the damage on a run that is still making progress elsewhere.
        val steps = listOf(
            step("ok1", status = "SUCCESS"),
            step("ok2", dependsOn = """["ok1"]"""),
            step("x", dependsOn = """["y"]"""),
            step("y", dependsOn = """["x"]"""),
        )
        val reason = resolver.stuckReason(steps)!!
        assertTrue("only the cycle is stuck: $reason", reason.startsWith("2 steps"))
        assertTrue("circular" in reason)
    }

    @Test
    fun `a failed dependency outranks a cycle elsewhere`() {
        // Most actionable first: "go and read that step's error" beats
        // "your plan is not a DAG" when both are true.
        val steps = listOf(
            step("a", status = "FAILED"),
            step("b", dependsOn = """["a"]"""),
            step("x", dependsOn = """["y"]"""),
            step("y", dependsOn = """["x"]"""),
        )
        assertTrue("failed" in resolver.stuckReason(steps)!!)
    }

    @Test
    fun `one stuck step is singular`() {
        val steps = listOf(step("a", status = "FAILED"), step("b", dependsOn = """["a"]"""))
        assertTrue(resolver.stuckReason(steps)!!.startsWith("1 step cannot"))
    }
}