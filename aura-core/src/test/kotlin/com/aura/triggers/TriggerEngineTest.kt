package com.aura.triggers

import com.aura.tasks.TaskDao
import com.aura.tasks.TaskEntity
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TriggerEngineTest {
    private val webChangeDetector: WebChangeDetector = mockk(relaxed = true)
    private val taskDao: TaskDao = mockk(relaxed = true)
    private val engine = TriggerEngine(webChangeDetector, taskDao)

    @Test
    fun `schedule daily fires at matching time`() = runTest {
        val trigger = Trigger(
            id = "1",
            label = "Daily",
            condition = TriggerCondition.Schedule("daily@09:00"),
            action = TriggerAction.Notify("Hi", "Daily ping"),
        )
        val now = ZonedDateTime.of(2026, 8, 1, 9, 0, 0, 0, ZoneId.of("UTC"))
        val actions = engine.checkAll(listOf(trigger), now)
        assertEquals(1, actions.size)
        assertTrue(actions[0] is TriggerAction.Notify)
    }

    @Test
    fun `schedule daily is quiet at different time`() = runTest {
        val trigger = Trigger(
            id = "1",
            label = "Daily",
            condition = TriggerCondition.Schedule("daily@09:00"),
            action = TriggerAction.Notify("Hi", "Daily ping"),
        )
        val now = ZonedDateTime.of(2026, 8, 1, 10, 0, 0, 0, ZoneId.of("UTC"))
        val actions = engine.checkAll(listOf(trigger), now)
        assertTrue(actions.isEmpty())
    }

    @Test
    fun `webChanged fires when hash changes`() = runTest {
        coEvery { taskDao.observeAll() } returns flowOf(emptyList())
        coEvery { webChangeDetector.hash("https://example.com") } returns "hash1"
        val trigger = Trigger(
            id = "1",
            label = "Web",
            condition = TriggerCondition.WebChanged("https://example.com"),
            action = TriggerAction.Notify("Changed", "Site updated"),
        )
        // first run: no stored hash, should not fire (baseline)
        val first = engine.checkAll(listOf(trigger))
        assertTrue(first.isEmpty())

        // simulate stored hash different
        coEvery { taskDao.observeAll() } returns flowOf(
            listOf(
                TaskEntity(
                    id = "hash-task",
                    title = "hash0",
                    description = "trigger-hash:https://example.com",
                    createdAt = 0L,
                ),
            ),
        )
        val second = engine.checkAll(listOf(trigger))
        assertEquals(1, second.size)
        assertTrue(second[0] is TriggerAction.Notify)
        coVerify { taskDao.insert(match { it.title == "hash1" }) }
    }

    @Test
    fun `webChanged is quiet when hash unchanged`() = runTest {
        coEvery { taskDao.observeAll() } returns flowOf(
            listOf(
                TaskEntity(
                    id = "hash-task",
                    title = "hash1",
                    description = "trigger-hash:https://example.com",
                    createdAt = 0L,
                ),
            ),
        )
        coEvery { webChangeDetector.hash("https://example.com") } returns "hash1"
        val trigger = Trigger(
            id = "1",
            label = "Web",
            condition = TriggerCondition.WebChanged("https://example.com"),
            action = TriggerAction.Notify("Changed", "Site updated"),
        )
        val actions = engine.checkAll(listOf(trigger))
        assertTrue(actions.isEmpty())
    }
}
