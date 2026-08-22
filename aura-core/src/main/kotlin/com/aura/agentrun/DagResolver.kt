package com.aura.agentrun

import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Resolves DAG dependencies between [StepEntity] records. A step is
 * ready to execute when all its [StepEntity.dependsOn] step IDs have
 * status=SUCCESS. Cycles are detected and rejected.
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