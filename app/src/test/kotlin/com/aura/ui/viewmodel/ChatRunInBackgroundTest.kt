package com.aura.ui.viewmodel

import com.aura.agent.Conversation
import com.aura.agent.ConversationStore
import com.aura.agentrun.AgentRunEntity
import com.aura.agentrun.AgentRunStore
import com.aura.data.UserPreferences
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals

/**
 * Turning a message into a task instead of a turn.
 *
 * The difference is what survives closing the app. A turn lives in the conversation and
 * dies with the collector; a task is a row with a goal, a status and its own steps, run by
 * a WorkManager job that finishes whether the app is open or not.
 *
 * Every part of that already existed — `createRun` has always taken a goal description, and
 * `AgentRunsScreen` has always been able to list, resume and cancel — and none of it was
 * reachable, because only `HandRunEnqueuer` and `ProductionPipelineEngine` ever created a
 * run. These cover the path that was missing.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatRunInBackgroundTest {

    @Before fun setUp() { Dispatchers.setMain(UnconfinedTestDispatcher()) }
    @After fun tearDown() { Dispatchers.resetMain() }

    private val store = mockk<AgentRunStore>(relaxed = true)

    @Test
    fun `the draft becomes the task's goal`() = runTest {
        coEvery { store.createRun(any(), any(), any(), any(), any()) } returns run()
        val vm = makeViewModel()
        vm.setDraft("find me a flight to Baku next Tuesday")

        vm.runInBackground()

        coVerify {
            store.createRun(
                trigger = "user",
                goalDescription = "find me a flight to Baku next Tuesday",
                conversationId = any(),
                modelId = any(),
                metadata = any(),
            )
        }
    }

    @Test
    fun `a launch that fails does not leave a task nothing will run`() = runTest {
        // createRun writes the row as RUNNING. If enqueueing then fails, the task exists,
        // nothing will ever execute it, and it reads to the user as still working — the
        // same orphan state AgentTaskWorker guards against on a crash.
        //
        // This is the path the JVM naturally exercises: WorkManager is not initialised in a
        // unit test, so AgentTaskService.enqueue always throws here. The success path needs
        // a device, and is listed as such.
        coEvery { store.createRun(any(), any(), any(), any(), any()) } returns run()
        val vm = makeViewModel()
        vm.setDraft("summarise my week")

        vm.runInBackground()

        coVerify { store.finish("r1", "FAILED", "the task could not be started") }
    }

    @Test
    fun `a launch that fails keeps what was typed`() = runTest {
        // Losing the draft to a failure the user cannot see is worse than the task not
        // starting: the text is gone and there is nothing to retry with.
        coEvery { store.createRun(any(), any(), any(), any(), any()) } returns run()
        val vm = makeViewModel()
        vm.setDraft("something I typed carefully")

        vm.runInBackground()

        assertEquals("something I typed carefully", vm.state.value.draft)
    }

    @Test
    fun `an empty draft starts nothing`() = runTest {
        val vm = makeViewModel()
        vm.setDraft("   ")

        vm.runInBackground()

        coVerify(exactly = 0) { store.createRun(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `a store that cannot create the run starts nothing at all`() = runTest {
        coEvery { store.createRun(any(), any(), any(), any(), any()) } throws
            IllegalStateException("database is locked")
        val vm = makeViewModel()
        vm.setDraft("something I typed carefully")

        vm.runInBackground()

        assertEquals("something I typed carefully", vm.state.value.draft)
        coVerify(exactly = 0) { store.finish(any(), any(), any()) }
    }

    private fun run() = AgentRunEntity(id = "r1", goalId = "g1", triggerType = "user")

    private fun makeViewModel(): ChatViewModel {
        val userPreferences = mockk<UserPreferences>(relaxed = true)
        every { userPreferences.defaultModel } returns flowOf("")
        every { userPreferences.ttsEnabled } returns flowOf(false)
        every { userPreferences.planningEnabled } returns flowOf(false)
        return ChatViewModel(
            application = mockk(relaxed = true),
            loop = mockk(relaxed = true),
            providerKeys = mockk(relaxed = true),
            providerRegistry = mockk(relaxed = true),
            toolRegistry = mockk(relaxed = true),
            toolExecutor = mockk(relaxed = true),
            delegateToAgentTool = mockk(relaxed = true),
            textToSpeech = mockk(relaxed = true),
            userPreferences = userPreferences,
            memoryStore = mockk(relaxed = true),
            conversationStore = mockk<ConversationStore>(relaxed = true).also { s ->
                coEvery { s.mostRecent() } returns Conversation()
            },
            knowledgeGraphRepository = mockk(relaxed = true),
            crashLogger = mockk(relaxed = true),
            documentTextExtractor = null,
            modelCatalogRepository = null,
            skillsStore = null,
            tasteEngine = mockk(relaxed = true),
            agentStore = mockk(relaxed = true),
            strategyBandit = mockk(relaxed = true),
            agentRunStore = store,
        )
    }
}
