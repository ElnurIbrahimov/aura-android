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

    private fun fixture(triggers: List<Trigger> = emptyList(), hand: Hand? = null): Fixture {
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
        )
        return Fixture(worker, handRunEnqueuer, handRepository, notificationsTool, engine)
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

        coVerify { f.notificationsTool.post("Aura", "Summarize my day") }
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
