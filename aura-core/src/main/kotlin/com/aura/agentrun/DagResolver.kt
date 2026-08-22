package com.aura.agentrun

import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Resolves DAG dependencies between [StepEntity] records. A step is
 * ready to execute when all its [StepEntity.dependsOn] step IDs have
 * status=SUCCESS.
 *
 * This said "Cycles are detected and rejected", and for a long time neither
 * half was true here: a `hasCycle` existed with no caller, so nothing detected
 * them, and what rejected an unsatisfiable graph was `AgentRunExecutorWorker`
 * noticing that no step was ready and failing the run. That worked — a cycle
 * never hung a run — but the message it failed with, "N steps pending with
 * unmet dependencies", is the same sentence for a cycle, a dependency on a
 * deleted step, and an upstream step that simply failed. Three different things
 * for the user to do, one sentence to go on.
 *
 * [stuckReason] is the detection, and the executor is its caller.
 */
@Singleton
class DagResolver @Inject constructor() {

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Returns steps that are ready to execute (all dependencies satisfied).
     */
    fun readySteps(steps: List<StepEntity>): List<StepEntity> {
        val stepMap = steps.associateBy { it.id }
        return steps.filter { step ->
            step.status == "PENDING" && dependenciesSatisfied(step, stepMap)
        }
    }


    /**
     * Returns true if all dependencies of [step] have status=SUCCESS.
     * A BLOCKED dependency is NOT satisfied — that step is paused for
     * user approval, not failed — and the caller must wait for the
     * approval before the dependent step can run.
     */
    private fun dependenciesSatisfied(step: StepEntity, stepMap: Map<kotlin.String, StepEntity>): kotlin.Boolean {
        val depIds = parseDependsOn(step.dependsOn)
        if (depIds.isEmpty()) return true
        return depIds.all { depId ->
            stepMap[depId]?.status == "SUCCESS"
        }
    }

    /**
     * P1-AGENTIC-F4: when [readySteps] is empty but the run has
     * in-progress work, distinguish between "stuck on a hard failure"
     * (every remaining step is FAILED or has no path to SUCCESS) and
     * "paused awaiting approval" (one or more steps are BLOCKED).
     *
     * The old behavior was to mark the entire run FAILED with the
     * message "Stuck: N steps pending with unmet dependencies" — which
     * lied: the run was just waiting for the user to grant a permission.
     *
     * Returns the list of step IDs that are currently BLOCKED, or
     * empty if the run is genuinely stuck (no BLOCKED, no PENDING-ready).
     */
    fun blockedStepIds(steps: List<StepEntity>): List<kotlin.String> {
        return steps.filter { it.status == "BLOCKED" }.map { it.id }
    }

    /**
     * Why no PENDING step can ever become ready, or null while one still can.
     *
     * Answers the question the executor already had to answer and could only
     * answer vaguely. A PENDING step can reach SUCCESS only if every step it
     * depends on can; that recursion bottoms out in four ways, and each is a
     * different instruction to whoever is reading the run:
     *
     * - a dependency that **failed** — look at that step's error, not this one
     * - a dependency that is **not in the run** — the plan references a step
     *   that was deleted or never created, which is a planning bug
     * - a **cycle** — the plan is not a DAG, which is also a planning bug, but
     *   a different one
     * - nothing wrong: some step is still reachable, and the caller is looking
     *   at a run that is merely waiting
     *
     * Reported in that order, most actionable first. A failed upstream step is
     * both the commonest cause and the one the old wording actively
     * mis-attributed to "unmet dependencies".
     *
     * Pure and total: no I/O, terminates on cyclic input by construction, and
     * treats BLOCKED as still-reachable because an approval can still arrive.
     */
    fun stuckReason(steps: List<StepEntity>): kotlin.String? {
        val byId = steps.associateBy { it.id }
        val pending = steps.filter { it.status == "PENDING" }
        if (pending.isEmpty()) return null

        val verdict = HashMap<kotlin.String, kotlin.Boolean>()
        val visiting = HashSet<kotlin.String>()
        var cycleAt: kotlin.String? = null
        var missingDep: Pair<kotlin.String, kotlin.String>? = null
        var failedDep: Pair<kotlin.String, kotlin.String>? = null

        fun canReachSuccess(id: kotlin.String): kotlin.Boolean {
            verdict[id]?.let { return it }
            val step = byId[id] ?: return false
            when (step.status) {
                "SUCCESS" -> { verdict[id] = true; return true }
                "FAILED", "SKIPPED" -> { verdict[id] = false; return false }
            }
            // Already on the stack: following this edge would go round again.
            // Returned without memoising — the frame that owns this id computes
            // the real verdict once its other edges are known.
            if (!visiting.add(id)) {
                cycleAt = id
                return false
            }
            var reachable = true
            for (depId in parseDependsOn(step.dependsOn)) {
                val dep = byId[depId]
                if (dep == null) {
                    if (missingDep == null) missingDep = id to depId
                    reachable = false
                    continue
                }
                if (!canReachSuccess(depId)) {
                    if (dep.status == "FAILED" || dep.status == "SKIPPED") {
                        if (failedDep == null) failedDep = id to depId
                    }
                    reachable = false
                }
            }
            visiting.remove(id)
            verdict[id] = reachable
            return reachable
        }

        val doomed = pending.filterNot { canReachSuccess(it.id) }
        if (doomed.isEmpty()) return null

        val n = doomed.size
        val plural = if (n == 1) "step" else "steps"
        failedDep?.let { (stepId, depId) ->
            return "$n $plural cannot run: $stepId depends on $depId, which failed"
        }
        missingDep?.let { (stepId, depId) ->
            return "$n $plural cannot run: $stepId depends on $depId, which is not part of this run"
        }
        cycleAt?.let {
            return "$n $plural cannot run: the plan has a circular dependency reaching $it"
        }
        return "$n $plural cannot run: their dependencies can never all succeed"
    }

    /**
     * Parse the dependsOn JSON array string into a list of step IDs.
     * Uses proper JSON deserialization to handle whitespace, quotes,
     * and edge cases correctly.
     */
    internal fun parseDependsOn(jsonStr: kotlin.String): List<kotlin.String> {
        if (jsonStr.isBlank() || jsonStr == "[]") return emptyList()
        return try {
            json.decodeFromString<List<kotlin.String>>(jsonStr)
        } catch (_: Exception) {
            emptyList()
        }
    }
}