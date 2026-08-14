package com.aura.triggers

import android.content.Context
import androidx.work.WorkerParameters
import com.aura.hands.Hand
import com.aura.hands.HandRepository
import com.aura.tools.HandRunEnqueuer
import com.aura.tools.NotificationsTool
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals

class TriggerWorkerTest {

    private class Fixture(
        val worker: TriggerWorker,
        val handRunEnqueuer: HandRunEnqueuer,
        val handRepository: HandRepository,
        val notificationsTool: NotificationsTool,
        val engine: TriggerEngine,
    )

    private fun fixture(
        triggers: List<Trigger> = emptyList(),
        hand: Hand? = null,
        recorder: com.aura.health.WorkerRunRecorder? = null,
    ): Fixture {
        val context = mockk<Context>(relaxed = true)
        val params = mockk<WorkerParameters>(relaxed = true)
        val engine = mockk<TriggerEngine>(relaxed = true)
        val notificationsTool = mockk<NotificationsTool>(relaxed = true)
        val userPreferences = mockk<com.aura.data.UserPreferences>(relaxed = true)
        val handRunEnqueuer = mockk<HandRunEnqueuer>(relaxed = true)
        val handRepository = mockk<HandRepository>(relaxed = true)

        every { userPreferences.triggersEnabled } returns flowOf(true)
        every { userPreferences.triggers } returns flowOf(triggers)
        coEvery { engine.checkAll(any(), any()) } returns triggers.filter { it.enabled }.map { it.action }
        coEvery { handRepository.getById(any()) } returns hand
        coEvery { handRepository.getByName(any()) } returns hand

        val worker = TriggerWorker(
            appContext = context,
            params = params,
            triggerEngine = engine,
            notificationsTool = notificationsTool,
            userPreferences = userPreferences,
            handRunEnqueuer = handRunEnqueuer,
            handRepository = handRepository,
            recorder = recorder,
        )
        return Fixture(worker, handRunEnqueuer, handRepository, notificationsTool, engine)
    }

    /**
     * A recorder that reproduces the one behaviour of the real one that matters
     * here: [com.aura.health.WorkerRunRecorder.record] **swallows** a throwing
     * block and returns null, having already written the failed row. It does not
     * rethrow. That is what made `recorder?.record(...) ?: runPass()` re-run the
     * whole pass on the failure path.
     *
     * A mock rather than a real recorder over Room, because the contract being
     * tested is the null return, not the persistence.
     */
    private fun swallowingRecorder(
        onOutcome: (com.aura.health.WorkerRunRecorder.Result) -> Unit = {},
    ): com.aura.health.WorkerRunRecorder {
        val recorder = mockk<com.aura.health.WorkerRunRecorder>()
        coEvery {
            recorder.record<androidx.work.ListenableWorker.Result>(any(), any())
        } coAnswers {
            val block = arg<suspend () -> Pair<androidx.work.ListenableWorker.Result, com.aura.health.WorkerRunRecorder.Result>>(1)
            runCatching { block() }
                .onSuccess { onOutcome(it.second) }
                .getOrNull()
                ?.first
        }
        return recorder
    }

    @Test
    fun `a throwing pass runs once, not twice`() = runBlocking {
        // runPass() here has no try/catch of its own, so anything the engine
        // throws reaches record(), which swallows it and returns null. Under the
        // elvis form the pass then ran again — re-posting every notification it
        // had already sent before the throw.
        var checks = 0
        val f = fixture(recorder = swallowingRecorder())
        coEvery { f.engine.checkAll(any(), any()) } coAnswers {
            checks++
            throw IllegalStateException("engine blew up")
        }

        f.worker.doWork()

        assertEquals(1, checks, "the trigger pass ran twice — the elvis form is back")
    }

    @Test
    fun `a completed pass records what it checked, not an empty string`() = runBlocking {
        // lastOutcome was initialised to ok("") and the success path never
        // touched it, so a worker running every 15 minutes filled the run log
        // with blank rows that BackgroundHealth then rendered as nothing at all.
        var recorded: com.aura.health.WorkerRunRecorder.Result? = null
        val trigger = Trigger(
            id = "t1",
            label = "Morning",
            condition = TriggerCondition.Schedule("daily@09:00"),
            action = TriggerAction.Notify("Hi", "Body"),
        )
        val f = fixture(triggers = listOf(trigger), recorder = swallowingRecorder { recorded = it })

        f.worker.doWork()

        assertEquals("checked 1, fired 1", recorded?.detail)
    }

    @Test
    fun `having no triggers is reported as such, not as a quiet success`() = runBlocking {
        var recorded: com.aura.health.WorkerRunRecorder.Result? = null
        val f = fixture(recorder = swallowingRecorder { recorded = it })

        f.worker.doWork()

        // "you have none configured" and "all four were checked and none
        // matched" are different facts, and only one of them is worth acting on.
        assertEquals("no triggers configured", recorded?.detail)
    }

    @Test
    fun `RunHand action enqueues the hand by name`() = runBlocking {
        val hand = Hand(
            id = "hand-1",
            name = "morning-summary",
            enabled = true,
            steps = "[]",
        )
        val trigger = Trigger(
            id = "t1",
            label = "Morning",
            condition = TriggerCondition.Schedule("daily@09:00"),
            action = TriggerAction.RunHand("hand-1"),
        )
        val f = fixture(triggers = listOf(trigger), hand = hand)
        f.worker.doWork()

        val handSlot = io.mockk.slot<String>()
        coVerify { f.handRunEnqueuer.enqueue(handName = capture(handSlot), any(), any(), any(), any(), any()) }
        assertEquals("morning-summary", handSlot.captured)
    }

    @Test
    fun `RunHand action falls back to lookup by name when id not found`() = runBlocking {
        val hand = Hand(
            id = "hand-1",
            name = "morning-summary",
            enabled = true,
            steps = "[]",
        )
        val trigger = Trigger(
            id = "t1",
            label = "Morning",
            condition = TriggerCondition.Schedule("daily@09:00"),
            action = TriggerAction.RunHand("morning-summary"),
        )
        val f = fixture(triggers = listOf(trigger))
        coEvery { f.handRepository.getById("morning-summary") } returns null
        coEvery { f.handRepository.getByName("morning-summary") } returns hand

        f.worker.doWork()

        val handSlot = io.mockk.slot<String>()
        coVerify { f.handRunEnqueuer.enqueue(handName = capture(handSlot), any(), any(), any(), any(), any()) }
        assertEquals("morning-summary", handSlot.captured)
    }

    @Test
    fun `StartChat action posts a notification with the prompt`() = runBlocking {
        val trigger = Trigger(
            id = "t1",
            label = "Prompt",
            condition = TriggerCondition.Schedule("daily@09:00"),
            action = TriggerAction.StartChat("Summarize my day"),
        )
        val f = fixture(triggers = listOf(trigger))
        f.worker.doWork()

        coVerify { f.notificationsTool.post("Aura", "Summarize my day", any()) }
    }

    @Test
    fun `Notify action posts a notification`() = runBlocking {
        val trigger = Trigger(
            id = "t1",
            label = "Ping",
            condition = TriggerCondition.Schedule("daily@09:00"),
            action = TriggerAction.Notify("Hi", "Hello"),
        )
        val f = fixture(triggers = listOf(trigger))
        f.worker.doWork()

        coVerify { f.notificationsTool.post("Hi", "Hello") }
    }
}
