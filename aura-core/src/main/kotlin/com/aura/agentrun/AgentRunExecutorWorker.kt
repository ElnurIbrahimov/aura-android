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
import com.aura.agent.truncateToolResult
import dagger.Lazy
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

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
    private val dagResolver: DagResolver,
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

        // P2-BUILD-DX: completedIds is not used after the P1-AGENTIC-F3
        // refactor that moved readiness to DagResolver. Compute failedIds
        // directly so we still know whether to short-circuit the run.
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
        val ready = dagResolver.readySteps(steps)

        if (ready.isEmpty()) {
            // P1-AGENTIC-F4: distinguish "stuck on a hard failure" from
            // "paused awaiting approval". Previously every empty-ready
            // batch with pending siblings was marked FAILED with the
            // message "Stuck: N steps pending with unmet dependencies"
            // — which lied when the actual cause was a BLOCKED upstream
            // step waiting for user approval. Now we leave the run in
            // RUNNING state and surface "PAUSED" so the UI can route
            // the user to the approval flow.
            val pending = steps.filter { it.status == "PENDING" }
            val blockedIds = dagResolver.blockedStepIds(steps)
            when {
                pending.isEmpty() && blockedIds.isEmpty() -> {
                    agentRunStore.finish(runId, "COMPLETED")
                }
                blockedIds.isNotEmpty() -> {
                    // Run is paused for approval. Leave RUNNING so the
                    // next worker tick (after the user approves) can
                    // resume. Re-enqueue after a delay so the UI can
                    // refresh even if the user grants via Settings.
                    Log.d(TAG, "Run $runId paused for approval on ${blockedIds.size} step(s)")
                    agentRunStore.updateStatus(runId, "PAUSED")
                    AgentRunExecutorService.enqueue(applicationContext, runId)
                }
                else -> {
                    // Hard stuck — no ready, no PENDING-with-met-deps, no
                    // BLOCKED. The dep graph is unsatisfiable (cycles /
                    // referenced deleted steps). Mark FAILED.
                    agentRunStore.finish(runId, "FAILED", "Stuck: ${pending.size} steps pending with unmet dependencies")
                }
            }
            return Result.success()
        }

        // Execute each ready step in parallel. DAG-ready steps have no
        // dependencies on each other, so they can run concurrently.
        coroutineScope {
            ready.map { step ->
                async {
                    val result = executeStep(run, step)
                    step to result
                }
            }.awaitAll().forEach { (step, result) ->
                when (result) {
                    is ToolResult.Ok -> agentRunStore.completeStep(step.id, truncateToolResult(result.output))
                    is ToolResult.Error -> {
                        agentRunStore.failStep(step.id, result.message)
                        // Continue executing other ready steps — one failure
                        // shouldn't block independent steps in the same batch.
                    }
                    is ToolResult.NeedsPermission -> {
                        // Mark step as BLOCKED (not FAILED) so the run can
                        // resume after the user grants the permission via
                        // AgentRunsViewModel.approve(). Until v0.30.x this
                        // called failStep(), which surfaced approval-gated
                        // tools as run failures.
                        agentRunStore.requestApproval(
                            runId = runId,
                            stepId = step.id,
                            toolName = step.toolName,
                            rationale = ApprovalKind.permissionRationale(result.permission),
                        )
                        agentRunStore.blockStep(step.id, "Permission required: ${result.permission}")
                    }
                    is ToolResult.NeedsApproval -> {
                        agentRunStore.requestApproval(
                            runId = runId,
                            stepId = step.id,
                            toolName = step.toolName,
                            rationale = result.rationale,
                        )
                        agentRunStore.blockStep(step.id, "Approval required: ${result.rationale}")
                    }
                    is ToolResult.NeedsConfirmation -> {
                        agentRunStore.requestApproval(
                            runId = runId,
                            stepId = step.id,
                            toolName = step.toolName,
                            rationale = result.rationale,
                        )
                        agentRunStore.blockStep(step.id, "Confirmation required: ${result.rationale}")
                    }
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
        val snapshot = AgentRunContextSnapshot.fromJson(run.metadata)
        val ctx = com.aura.agent.ToolContext(
            conversationId = run.conversationId.ifBlank { "agent_run:${run.id}" },
            userMessage = snapshot.userMessage,
            approvedRemoteCostTools = snapshot.approvedRemoteCostTools,
            memoryEnabled = snapshot.memoryEnabled,
            activeAgentId = snapshot.activeAgentId,
            timeout = snapshot.toolTimeoutMs,
        )
        return try {
            executor.get().execute(step.toolName, step.toolArgs, ctx)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ToolResult.Error(e.message ?: e::class.java.simpleName, "step_exception")
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