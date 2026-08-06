package com.aura.agents

import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withTimeout

/**
 * Orchestrates isolated subagent workers. Each subagent runs in its
 * own coroutine with independent context, model role, tool allowlist,
 * and budget. Results are validated against the expected schema.
 *
 * Rules:
 * - No nested unbounded fan-out (parent budget applies)
 * - Parent-child cancellation propagates
 * - Progress events are emitted for UI
 * - Results are structured, not raw chain-of-thought
 * - Shared state goes through artifacts, not mutable conversation
 */
@Singleton
class SubagentManager @Inject constructor() {

    private val _progress = MutableSharedFlow<SubagentProgress>(extraBufferCapacity = 100)
    val progress: SharedFlow<SubagentProgress> = _progress.asSharedFlow()

    /**
     * Spawn a single subagent for [task]. Returns its result.
     */
    suspend fun spawn(
        task: SubagentTask,
        executor: suspend (SubagentTask) -> SubagentResult,
    ): SubagentResult {
        val timeout = if (task.spec.budgetMs > 0) task.spec.budgetMs else 60_000L
        return try {
            val result = withTimeout(timeout) {
                _progress.emit(SubagentProgress.Started(task.id, task.spec.role, task.spec.objective))
                executor(task)
            }
            _progress.emit(SubagentProgress.Completed(task.id, result.success, result.durationMs))
            result
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            val result = SubagentResult(
                taskId = task.id,
                success = false,
                error = "Subagent timed out after ${timeout}ms",
                durationMs = timeout,
            )
            _progress.emit(SubagentProgress.Completed(task.id, false, timeout))
            result
        } catch (e: kotlinx.coroutines.CancellationException) {
            // Parent cancellation must propagate. Converting it into a
            // "failed" result broke structured concurrency: a cancelled
            // parent kept collecting fabricated results from children
            // that were supposed to be dead, and the coroutine machinery
            // never saw the cancellation complete. (The subagent's own
            // budget timeout is TimeoutCancellationException, handled
            // above — that one IS a result.) tryEmit because emit() is a
            // suspend call and would immediately rethrow in a cancelled
            // coroutine.
            _progress.tryEmit(SubagentProgress.Cancelled(task.id))
            throw e
        }
    }

    /**
     * Spawn multiple subagents in parallel. Returns all results.
     * Parent budget applies across all children.
     */
    suspend fun spawnAll(
        tasks: List<SubagentTask>,
        executor: suspend (SubagentTask) -> SubagentResult,
    ): List<SubagentResult> = coroutineScope {
        tasks.map { task ->
            async { spawn(task, executor) }
        }.map { it.await() }
    }

    /**
     * Create a [SubagentTask] from a [SubagentSpec].
     */
    fun createTask(spec: SubagentSpec, parentRunId: kotlin.String): SubagentTask =
        SubagentTask(
            id = UUID.randomUUID().toString(),
            spec = spec,
            parentRunId = parentRunId,
        )
}

sealed class SubagentProgress {
    data class Started(val taskId: kotlin.String, val role: kotlin.String, val objective: kotlin.String) : SubagentProgress()
    data class Completed(val taskId: kotlin.String, val success: kotlin.Boolean, val durationMs: kotlin.Long) : SubagentProgress()
    data class Cancelled(val taskId: kotlin.String) : SubagentProgress()
}