package com.aura.agentrun

import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Domain store wrapping [AgentRunDao], [GoalDao], [StepDao],
 * [AgentEventDao], [ApprovalRequestDao], and [RunCheckpointDao].
 *
 * Provides CRUD, DAG step planning, checkpoint/resume, and goal
 * verification. All mutations are mutex-protected per run.
 */
@Singleton
class AgentRunStore @Inject constructor(
    private val runDao: AgentRunDao,
    private val goalDao: GoalDao,
    private val stepDao: StepDao,
    private val eventDao: AgentEventDao,
    private val approvalDao: ApprovalRequestDao,
    private val checkpointDao: RunCheckpointDao,
    private val dagResolver: DagResolver,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()

    suspend fun createRun(
        trigger: kotlin.String,
        goalDescription: kotlin.String,
        conversationId: kotlin.String = "",
        modelId: kotlin.String = "",
        metadata: kotlin.String = "{}",
    ): AgentRunEntity = mutex.withLock {
        val goalId = UUID.randomUUID().toString()
        val runId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val goal = GoalEntity(
            id = goalId,
            agentRunId = runId,
            description = goalDescription,
        )
        goalDao.upsert(goal)
        val run = AgentRunEntity(
            id = runId,
            goalId = goalId,
            status = "RUNNING",
            triggerType = trigger,
            conversationId = conversationId,
            modelId = modelId,
            metadata = metadata,
            startedAt = now,
            updatedAt = now,
        )
        runDao.upsert(run)
        emitEvent(runId, "RUN_STARTED")
        run
    }

    suspend fun loadRun(id: kotlin.String): AgentRunEntity? = runDao.getById(id)

    suspend fun listRecent(limit: Int = 50): List<AgentRunEntity> = runDao.recent(limit)

    suspend fun updateStatus(id: kotlin.String, status: kotlin.String) = mutex.withLock {
        runDao.updateStatus(id, status, System.currentTimeMillis())
    }

    suspend fun finish(id: kotlin.String, status: kotlin.String, error: kotlin.String = "") = mutex.withLock {
        runDao.finish(id, status, error, System.currentTimeMillis())
        emitEvent(id, if (status == "COMPLETED") "RUN_COMPLETED" else "RUN_FAILED")
    }

    suspend fun planSteps(runId: kotlin.String, steps: List<StepSpec>) = mutex.withLock {
        stepDao.upsertAll(steps.mapIndexed { index, spec ->
            StepEntity(
                id = spec.id ?: UUID.randomUUID().toString(),
                agentRunId = runId,
                toolName = spec.toolName,
                toolArgs = spec.toolArgs,
                dependsOn = spec.dependsOn,
                position = index,
            )
        })
    }

    /**
     * Append one step to a run that is already underway, and return its id.
     *
     * [planSteps] cannot do this. It assigns positions with `mapIndexed` over the list it
     * is given and writes fresh `PENDING` rows, so calling it once per step would put every
     * step at position 0 and reset the ones that had already finished. That is correct for
     * its own caller — a plan is known in full before it runs — and wrong for a loop, which
     * discovers each tool call only as the model makes it.
     */
    suspend fun appendStep(
        runId: kotlin.String,
        toolName: kotlin.String,
        toolArgs: kotlin.String = "{}",
        stepId: kotlin.String = UUID.randomUUID().toString(),
        position: Int = 0,
    ): kotlin.String = mutex.withLock {
        stepDao.upsertAll(
            listOf(
                StepEntity(
                    id = stepId,
                    agentRunId = runId,
                    toolName = toolName,
                    toolArgs = toolArgs,
                    status = "RUNNING",
                    position = position,
                    startedAt = System.currentTimeMillis(),
                ),
            ),
        )
        emitEvent(runId, "STEP_STARTED", stepId = stepId, toolName = toolName)
        stepId
    }

    suspend fun stepsForRun(runId: kotlin.String): List<StepEntity> =
        stepDao.forRun(runId)

    suspend fun completeStep(stepId: kotlin.String, result: kotlin.String) = mutex.withLock {
        val step = stepDao.getById(stepId) ?: return@withLock
        stepDao.complete(stepId, "SUCCESS", result, System.currentTimeMillis())
        emitEvent(step.agentRunId, "STEP_COMPLETED", stepId = stepId)
    }

    suspend fun failStep(stepId: kotlin.String, error: kotlin.String) = mutex.withLock {
        val step = stepDao.getById(stepId) ?: return@withLock
        stepDao.fail(stepId, "FAILED", error, System.currentTimeMillis())
        emitEvent(step.agentRunId, "STEP_FAILED", stepId = stepId, success = false)
    }

    /**
     * Mark a step as BLOCKED (waiting on permission/approval). Distinct
     * from FAILED — a blocked step is not an error, just paused. The
     * executor worker calls this when the tool returns NeedsPermission
     * or NeedsApproval; the run is resumed only after the user grants
     * the permission via AgentRunsViewModel.approve().
     *
     * Until v0.30.x the worker used failStep() for these cases, which
     * (a) surfaced blocked steps as failures in the AgentRun detail UI
     * and (b) emitted STEP_FAILED events, making approval flows look
     * broken to the user.
     */
    suspend fun blockStep(stepId: kotlin.String, reason: kotlin.String) = mutex.withLock {
        val step = stepDao.getById(stepId) ?: return@withLock
        stepDao.fail(stepId, "BLOCKED", reason, System.currentTimeMillis())
        emitEvent(step.agentRunId, "STEP_BLOCKED", stepId = stepId)
    }

    suspend fun checkpoint(runId: kotlin.String): RunCheckpointEntity = mutex.withLock {
        val steps = stepDao.forRun(runId)
        val state = CheckpointState(
            activeStepIds = steps.filter { it.status == "PENDING" || it.status == "RUNNING" }.map { it.id },
        )
        val checkpoint = RunCheckpointEntity(
            id = UUID.randomUUID().toString(),
            agentRunId = runId,
            stateJson = json.encodeToString(state),
        )
        checkpointDao.upsert(checkpoint)
        checkpointDao.cleanupOld(runId, checkpoint.id)
        emitEvent(runId, "CHECKPOINT")
        checkpoint
    }

    suspend fun eventsForRun(runId: kotlin.String): List<AgentEventEntity> =
        eventDao.forRun(runId)

    suspend fun pendingApprovals(runId: kotlin.String): List<ApprovalRequestEntity> =
        approvalDao.pendingForRun(runId)

    suspend fun requestApproval(
        runId: kotlin.String,
        stepId: kotlin.String,
        toolName: kotlin.String,
        rationale: kotlin.String,
        expiresAt: kotlin.Long = 0L,
    ): ApprovalRequestEntity = mutex.withLock {
        val approval = ApprovalRequestEntity(
            id = UUID.randomUUID().toString(),
            agentRunId = runId,
            stepId = stepId,
            toolName = toolName,
            rationale = rationale,
            expiresAt = expiresAt,
        )
        approvalDao.upsert(approval)
        emitEvent(runId, "APPROVAL_REQUESTED", stepId = stepId, toolName = toolName)
        approval
    }

    suspend fun approve(id: kotlin.String) = mutex.withLock {
        approvalDao.decide(id, "APPROVED", "", System.currentTimeMillis())
        val approval = approvalDao.getById(id) ?: return@withLock
        emitEvent(approval.agentRunId, "APPROVAL_DECIDED", stepId = approval.stepId)
    }

    suspend fun deny(id: kotlin.String, reason: kotlin.String = "") = mutex.withLock {
        approvalDao.decide(id, "DENIED", reason, System.currentTimeMillis())
        val approval = approvalDao.getById(id) ?: return@withLock
        emitEvent(approval.agentRunId, "APPROVAL_DECIDED", stepId = approval.stepId, success = false)
    }

    /**
     * Reset a step from AWAITING_APPROVAL/FAILED back to PENDING so the
     * executor worker can pick it up again after an approval or manual retry.
     * Emits a STEP_RESET event for observability.
     */
    suspend fun resetStep(stepId: kotlin.String) = mutex.withLock {
        val step = stepDao.getById(stepId) ?: return@withLock
        stepDao.markStarted(stepId, "PENDING", System.currentTimeMillis())
        emitEvent(step.agentRunId, "STEP_RESET", stepId = stepId)
    }

    private suspend fun emitEvent(
        runId: kotlin.String,
        type: kotlin.String,
        stepId: kotlin.String? = null,
        toolName: kotlin.String? = null,
        success: kotlin.Boolean = true,
    ) {
        eventDao.insert(
            AgentEventEntity(
                id = UUID.randomUUID().toString(),
                agentRunId = runId,
                stepId = stepId,
                timestamp = System.currentTimeMillis(),
                type = type,
                toolName = toolName,
                success = success,
            )
        )
    }
}

data class StepSpec(
    val toolName: kotlin.String,
    val toolArgs: kotlin.String = "{}",
    val dependsOn: kotlin.String = "[]",
    /** Optional pre-generated step ID. If set, planSteps uses this
     * instead of generating a UUID, so callers can reference it in
     * [dependsOn] of subsequent steps. */
    val id: kotlin.String? = null,
)

@Serializable
data class CheckpointState(
    val activeStepIds: List<kotlin.String> = emptyList(),
)