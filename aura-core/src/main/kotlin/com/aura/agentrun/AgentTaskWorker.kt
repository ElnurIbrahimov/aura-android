package com.aura.agentrun

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.aura.agent.AgentEvent
import com.aura.agent.Conversation
import com.aura.agent.MemoryAugmentedAgenticLoop
import com.aura.data.UserPreferences
import dagger.Lazy
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first

/**
 * Runs a goal to completion in the background, as a task rather than a conversation turn.
 *
 * The pieces for this were already here and never met. [AgentRunStore.createRun] takes a
 * goal description; [GoalEntity], [StepEntity], [AgentEventEntity] and
 * [RunCheckpointEntity] all exist; `AgentRunsScreen` already lists runs and offers approve,
 * deny, resume and cancel. What was missing was any way for the *user* to create one —
 * only `HandRunEnqueuer` and `ProductionPipelineEngine` called `createRun` — and a runner
 * that could take a goal rather than a plan.
 *
 * [AgentRunExecutorWorker], its sibling, executes a pre-planned DAG of tool calls. Nothing
 * turned a goal into that plan. This takes the other route: it runs the loop that already
 * knows how to decide its own next tool call, and records what the loop does as it does it.
 * The trade is deliberate — no dependency ordering and no parallel steps, in exchange for
 * reusing the execution path that every conversation already goes through, and for a wrong
 * decision being recoverable mid-run rather than baked into a plan.
 *
 * The step rows are a record, not a schedule. They exist so the detail screen can show what
 * the task is doing and what it did, which is the whole difference between a task and a
 * spinner.
 */
@HiltWorker
class AgentTaskWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val agentRunStore: AgentRunStore,
    private val goalDao: GoalDao,
    private val loop: Lazy<MemoryAugmentedAgenticLoop>,
    private val userPreferences: UserPreferences,
    private val notifier: AgentTaskNotifier? = null,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val runId = inputData.getString(KEY_RUN_ID) ?: return Result.failure()
        val run = agentRunStore.loadRun(runId) ?: return Result.success()

        // Anything not RUNNING has been cancelled, finished, or is being handled elsewhere.
        // Re-running it would duplicate its work and its notification.
        if (run.status != "RUNNING") {
            Log.d(TAG, "run $runId is ${run.status}, nothing to do")
            return Result.success()
        }

        val goal = goalDao.forRun(runId)?.description?.takeIf { it.isNotBlank() }
        if (goal == null) {
            agentRunStore.finish(runId, STATUS_FAILED, "the task had no goal to work on")
            return Result.success()
        }

        val model = run.modelId.ifBlank { runCatching { userPreferences.defaultModel.first() }.getOrNull().orEmpty() }
        if (model.isBlank()) {
            // Said rather than swallowed. A task that sits at RUNNING forever because no
            // model was configured is indistinguishable from one that is still thinking.
            agentRunStore.finish(runId, STATUS_FAILED, "no model is configured for background tasks")
            notifier?.onFinished(runId, goal, succeeded = false)
            return Result.success()
        }

        var position = 0
        var finalText = ""
        var failure: String? = null

        return try {
            loop.get().run(
                conversation = Conversation().addUser(goal),
                model = model,
                // Background work writes to memory like any other turn: the point of running
                // it as Aura rather than as a script is that it remembers having done so.
                memoryEnabled = true,
            ).collect { event ->
                when (event) {
                    is AgentEvent.ToolCallStart -> {
                        agentRunStore.appendStep(
                            runId = runId,
                            toolName = event.name,
                            stepId = event.id,
                            position = position++,
                        )
                    }

                    is AgentEvent.ToolResult -> {
                        // The loop reports a refused tool through `needsPermission` rather
                        // than an error. BLOCKED, not FAILED — the distinction
                        // AgentRunStore.blockStep exists to keep, so the detail screen does
                        // not show a paused task as a broken one.
                        val blocked = event.needsPermission
                        if (blocked != null) {
                            agentRunStore.blockStep(event.id, blocked)
                        } else {
                            agentRunStore.completeStep(event.id, event.result.take(STEP_RESULT_CHARS))
                        }
                    }

                    is AgentEvent.Error -> {
                        failure = "${event.code}: ${event.message}"
                    }

                    is AgentEvent.Result -> {
                        finalText = event.conversation.turns.lastOrNull()?.assistant.orEmpty()
                    }

                    else -> Unit
                }
            }

            val succeeded = failure == null
            agentRunStore.finish(
                runId,
                if (succeeded) STATUS_SUCCEEDED else STATUS_FAILED,
                failure.orEmpty(),
            )
            notifier?.onFinished(runId, finalText.ifBlank { goal }, succeeded = succeeded)
            Result.success()
        } catch (t: Throwable) {
            // A crash mid-run must not leave the row at RUNNING. A task stuck in that state
            // can never be resumed or cleared, and reads to the user as still working.
            Log.w(TAG, "task $runId failed: ${t.message}", t)
            agentRunStore.finish(runId, STATUS_FAILED, t.message ?: t::class.java.simpleName)
            notifier?.onFinished(runId, goal, succeeded = false)
            Result.success()
        }
    }

    companion object {
        const val KEY_RUN_ID = "agent_task_run_id"
        const val WORK_NAME_PREFIX = "agent_task_"
        const val STATUS_SUCCEEDED = "SUCCEEDED"
        const val STATUS_FAILED = "FAILED"

        /** Enough for the detail screen to show what a step returned, not the whole payload. */
        const val STEP_RESULT_CHARS = 2_000

        private const val TAG = "AgentTaskWorker"
    }
}

/**
 * Tells the user a background task finished.
 *
 * A task nobody can see finish is not a background task — it is a task that disappeared.
 * Kept as an interface so the worker stays testable on the JVM, where posting a real
 * notification is not possible.
 */
interface AgentTaskNotifier {
    suspend fun onFinished(runId: String, summary: String, succeeded: Boolean)
}

/** Starts a background task for an already-created run. */
object AgentTaskService {
    fun enqueue(context: Context, runId: String) {
        val request = OneTimeWorkRequestBuilder<AgentTaskWorker>()
            .setInputData(workDataOf(AgentTaskWorker.KEY_RUN_ID to runId))
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            "${AgentTaskWorker.WORK_NAME_PREFIX}$runId",
            androidx.work.ExistingWorkPolicy.KEEP,
            request,
        )
    }
}
