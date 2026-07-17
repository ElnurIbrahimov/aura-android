package com.aura.agentrun

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.aura.agent.ToolContext
import com.aura.agent.ToolExecutor
import com.aura.agent.ToolResult
import dagger.Lazy
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException

/**
 * Processes pending steps for an AgentRun. Each step calls a tool via
 * [ToolExecutor] and records the result. Steps with dependencies
 * ([dependsOn]) are only executed after their dependencies complete
 * successfully.
 *
 * The worker is enqueued by [AgentRunExecutorService] when a run has
 * pending steps. It processes all ready steps, then either finishes
 * the run or re-enqueues itself if more steps became ready.
 *
 * This is the missing piece that makes Hands and Production Pipelines
 * actually execute — [HandRunEnqueuer] and
 * [ProductionPipelineEngine] create AgentRuns with planned steps,
 * and this worker executes them.
 */
@HiltWorker
class AgentRunExecutorWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val agentRunStore: AgentRunStore,
    private val executor: Lazy<ToolExecutor>,
) : CoroutineWorker(appContext, params) {

    companion object {
        const val KEY_RUN_ID = "agent_run_id"
        private const val TAG = "AgentRunExecutor"
        const val WORK_NAME_PREFIX = "agent_run_executor_"
    }

    override suspend fun doWork(): Result {
        val runId = inputData.getString(KEY_RUN_ID) ?: return Result.failure()
        val run = agentRunStore.loadRun(runId) ?: return Result.success()

        if (run.status != "RUNNING") {
            Log.d(TAG, "Run $runId is ${run.status}, skipping")
            return Result.success()
        }

        val steps = agentRunStore.stepsForRun(runId)
        if (steps.isEmpty()) {
            agentRunStore.finish(runId, "COMPLETED")
            return Result.success()
        }

        val completedIds = steps.filter { it.status == "SUCCESS" }.map { it.id }.toSet()
        val failedIds = steps.filter { it.status == "FAILED" }.map { it.id }.toSet()

        // If any step failed, check whether the run can continue (non-dependent steps)
        // or should be marked as failed.
        if (failedIds.isNotEmpty()) {
            val remaining = steps.filter { it.status == "PENDING" }
            if (remaining.isEmpty()) {
                agentRunStore.finish(runId, "FAILED", "Step(s) failed: ${failedIds.size}")
                return Result.success()
            }
        }

        // Find ready steps: PENDING with all dependencies SUCCESS
        val ready = steps.filter { step ->
            step.status == "PENDING" && allDependenciesComplete(step, completedIds)
        }

        if (ready.isEmpty()) {
            // No ready steps — either all done or stuck (circular dependency)
            val pending = steps.filter { it.status == "PENDING" }
            if (pending.isEmpty()) {
                agentRunStore.finish(runId, "COMPLETED")
            } else {
                // Stuck — mark as failed
                agentRunStore.finish(runId, "FAILED", "Stuck: ${pending.size} steps pending with unmet dependencies")
            }
            return Result.success()
        }

        // Execute each ready step
        for (step in ready) {
            val result = executeStep(run, step)
            when (result) {
                is ToolResult.Ok -> agentRunStore.completeStep(step.id, result.output)
                is ToolResult.Error -> {
                    agentRunStore.failStep(step.id, result.message)
                    // Continue executing other ready steps — one failure
                    // shouldn't block independent steps in the same batch.
                }
                is ToolResult.NeedsPermission -> {
                    agentRunStore.requestApproval(
                        runId = runId,
                        stepId = step.id,
                        toolName = step.toolName,
                        rationale = "Permission needed: ${result.permission}",
                    )
                    // Mark step as blocked — it will be retried after approval
                    // For now, just fail it so the run doesn't hang forever
                    agentRunStore.failStep(step.id, "Permission required: ${result.permission}")
                }
                is ToolResult.NeedsApproval -> {
                    agentRunStore.requestApproval(
                        runId = runId,
                        stepId = step.id,
                        toolName = step.toolName,
                        rationale = result.rationale,
                    )
                    agentRunStore.failStep(step.id, "Approval required: ${result.rationale}")
                }
            }
        }

        // Check if more steps became ready after completing this batch
        val updatedSteps = agentRunStore.stepsForRun(runId)
        val stillPending = updatedSteps.any { it.status == "PENDING" }
        if (stillPending) {
            // Re-enqueue to process the next batch
            AgentRunExecutorService.enqueue(applicationContext, runId)
        } else {
            val allSuccess = updatedSteps.all { it.status == "SUCCESS" }
            agentRunStore.finish(runId, if (allSuccess) "COMPLETED" else "FAILED",
                if (allSuccess) "" else "Some steps failed")
        }

        return Result.success()
    }

    private suspend fun executeStep(
        run: AgentRunEntity,
        step: StepEntity,
    ): ToolResult {
        val ctx = ToolContext(
            conversationId = run.conversationId.ifBlank { "agent_run:${run.id}" },
            timeout = 120_000L,
        )
        return try {
            executor.get().execute(step.toolName, step.toolArgs, ctx)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ToolResult.Error(e.message ?: e::class.java.simpleName, "step_exception")
        }
    }

    private fun allDependenciesComplete(step: StepEntity, completedIds: Set<String>): Boolean {
        val deps = parseDependsOn(step.dependsOn)
        return deps.all { it in completedIds }
    }

    private fun parseDependsOn(json: kotlin.String): List<kotlin.String> {
        if (json.isBlank() || json == "[]") return emptyList()
        return try {
            kotlinx.serialization.json.Json.decodeFromString<List<kotlin.String>>(json)
        } catch (_: Exception) {
            emptyList()
        }
    }
}

/**
 * Service for enqueuing [AgentRunExecutorWorker] instances.
 * Called by [HandRunEnqueuer] and [ProductionPipelineEngine]
 * after creating runs with pending steps.
 */
object AgentRunExecutorService {
    fun enqueue(context: Context, runId: kotlin.String) {
        val request = OneTimeWorkRequestBuilder<AgentRunExecutorWorker>()
            .setInputData(workDataOf(AgentRunExecutorWorker.KEY_RUN_ID to runId))
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(
                "${AgentRunExecutorWorker.WORK_NAME_PREFIX}$runId",
                androidx.work.ExistingWorkPolicy.REPLACE,
                request,
            )
    }
}