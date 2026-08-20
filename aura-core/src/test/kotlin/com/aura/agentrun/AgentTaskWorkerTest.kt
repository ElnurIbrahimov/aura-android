package com.aura.agentrun

import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.aura.agent.AgentEvent
import com.aura.agent.Conversation
import com.aura.agent.MemoryAugmentedAgenticLoop
import com.aura.data.UserPreferences
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals

/**
 * A goal that runs to completion after you close the app.
 *
 * Everything this needs already existed and none of it was reachable: `createRun` takes a
 * goal, `GoalEntity`/`StepEntity`/`AgentEventEntity` are all there, and `AgentRunsScreen`
 * already lists runs with approve, deny, resume and cancel. What was missing was any way
 * for the user to make one, and a runner that takes a goal rather than a finished plan —
 * `AgentRunExecutorWorker` executes a pre-planned DAG and nothing produced the plan.
 *
 * These cover the mapping from what the loop emits to what the detail screen reads, and the
 * two states a task must never be left in: RUNNING forever, or reporting success after a
 * failure.
 */
class AgentTaskWorkerTest {

    private val store = mockk<AgentRunStore>(relaxed = true)
    private val goalDao = mockk<GoalDao>(relaxed = true)
    private val loop = mockk<MemoryAugmentedAgenticLoop>(relaxed = true)
    private val prefs = mockk<UserPreferences>(relaxed = true)

    private fun worker(
        events: List<AgentEvent>,
        status: String = "RUNNING",
        goal: String? = "summarise my week",
        model: String = "ollama:test",
        fallbackModel: String = "ollama:fallback",
        notifier: AgentTaskNotifier? = null,
    ): AgentTaskWorker {
        coEvery { store.loadRun("r1") } returns AgentRunEntity(
            id = "r1", goalId = "g1", status = status, triggerType = "user", modelId = model,
        )
        coEvery { goalDao.forRun("r1") } returns goal?.let {
            GoalEntity(id = "g1", agentRunId = "r1", description = it)
        }
        every { prefs.defaultModel } returns flowOf(fallbackModel)
        every {
            loop.run(
                conversation = any(), model = any(), strategy = any(), maxSteps = any(),
                specialist = any(), memoryEnabled = any(), approvedRemoteCostTools = any(),
                confirmedTools = any(), agentId = any(), planningEnabled = any(),
                recentTopics = any(),
            )
        } returns flowOf(*events.toTypedArray())

        val params = mockk<WorkerParameters>(relaxed = true)
        every { params.inputData } returns workDataOf(AgentTaskWorker.KEY_RUN_ID to "r1")
        return AgentTaskWorker(
            mockk(relaxed = true), params, store, goalDao, { loop }, prefs, notifier,
        )
    }

    @Test
    fun `each tool call becomes a step, in the order it happened`() = runTest {
        worker(
            listOf(
                AgentEvent.ToolCallStart("s1", "web_search"),
                AgentEvent.ToolResult("s1", "web_search", "{}", "three results"),
                AgentEvent.ToolCallStart("s2", "remember"),
                AgentEvent.ToolResult("s2", "remember", "{}", "stored"),
                AgentEvent.Done,
            ),
        ).doWork()

        coVerify { store.appendStep(runId = "r1", toolName = "web_search", stepId = "s1", position = 0) }
        coVerify { store.appendStep(runId = "r1", toolName = "remember", stepId = "s2", position = 1) }
        coVerify { store.completeStep("s1", "three results") }
        coVerify { store.completeStep("s2", "stored") }
    }

    @Test
    fun `a run that finishes cleanly is marked succeeded`() = runTest {
        val result = worker(listOf(AgentEvent.Done)).doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        coVerify { store.finish("r1", "SUCCEEDED", "") }
    }

    @Test
    fun `an error in the stream fails the run rather than reporting success`() = runTest {
        worker(
            listOf(
                AgentEvent.ToolCallStart("s1", "web_search"),
                AgentEvent.Error("rate_limited", "slow down", retryable = true),
                AgentEvent.Done,
            ),
        ).doWork()

        coVerify { store.finish("r1", "FAILED", "rate_limited: slow down") }
    }

    @Test
    fun `a refused tool blocks its step instead of failing it`() = runTest {
        // BLOCKED and FAILED are different states on purpose — blockStep's KDoc records the
        // version where using failStep for both made every approval flow look broken.
        worker(
            listOf(
                AgentEvent.ToolCallStart("s1", "sms_send"),
                AgentEvent.ToolResult("s1", "sms_send", "{}", "", needsPermission = "SEND_SMS"),
                AgentEvent.Done,
            ),
        ).doWork()

        coVerify { store.blockStep("s1", "SEND_SMS") }
        coVerify(exactly = 0) { store.completeStep("s1", any()) }
    }

    @Test
    fun `a crash mid-run does not leave the task running forever`() = runTest {
        // The state a task must never be left in: RUNNING with nothing running. It can
        // never be resumed or cleared, and it reads to the user as still working.
        every {
            loop.run(
                conversation = any(), model = any(), strategy = any(), maxSteps = any(),
                specialist = any(), memoryEnabled = any(), approvedRemoteCostTools = any(),
                confirmedTools = any(), agentId = any(), planningEnabled = any(),
                recentTopics = any(),
            )
        } throws IllegalStateException("the provider vanished")

        val params = mockk<WorkerParameters>(relaxed = true)
        every { params.inputData } returns workDataOf(AgentTaskWorker.KEY_RUN_ID to "r1")
        coEvery { store.loadRun("r1") } returns AgentRunEntity(
            id = "r1", goalId = "g1", status = "RUNNING", triggerType = "user", modelId = "ollama:test",
        )
        coEvery { goalDao.forRun("r1") } returns GoalEntity(id = "g1", agentRunId = "r1", description = "go")

        AgentTaskWorker(mockk(relaxed = true), params, store, goalDao, { loop }, prefs, null).doWork()

        coVerify { store.finish("r1", "FAILED", "the provider vanished") }
    }

    @Test
    fun `a task with no model configured says so rather than hanging`() = runTest {
        // Both the run's own model and the preference are blank: nothing to run on.
        worker(listOf(AgentEvent.Done), model = "", fallbackModel = "").doWork()

        coVerify { store.finish("r1", "FAILED", "no model is configured for background tasks") }
    }

    @Test
    fun `a cancelled run is not restarted`() = runTest {
        // WorkManager will re-run a job after a process death. Without this, cancelling a
        // task and closing the app would start it again.
        worker(listOf(AgentEvent.Done), status = "CANCELLED").doWork()

        coVerify(exactly = 0) { store.finish(any(), any(), any()) }
        coVerify(exactly = 0) { store.appendStep(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `finishing tells the user`() = runTest {
        val told = mutableListOf<Pair<String, Boolean>>()
        val notifier = object : AgentTaskNotifier {
            override suspend fun onFinished(runId: String, summary: String, succeeded: Boolean) {
                told += runId to succeeded
            }
        }

        worker(
            listOf(
                AgentEvent.Result(Conversation().addUser("go").addAssistant("here it is"), null),
                AgentEvent.Done,
            ),
            notifier = notifier,
        ).doWork()

        assertEquals(listOf("r1" to true), told)
    }
}
