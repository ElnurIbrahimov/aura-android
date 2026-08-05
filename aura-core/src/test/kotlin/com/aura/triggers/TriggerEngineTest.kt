package com.aura.triggers

import android.content.Context
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
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TriggerEngineTest {
    private val webChangeDetector: WebChangeDetector = mockk(relaxed = true)
    private val taskDao: TaskDao = mockk(relaxed = true)
    private val context: Context = mockk(relaxed = true)
    private val engine = TriggerEngine(context, webChangeDetector, taskDao)

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

    // ── LocationEntered (haversine math — pure function) ─────────────

    @Test
    fun `haversine returns ~0 for identical coordinates`() {
        val d = TriggerEngine.haversineMetersStatic(40.4093, 49.8671, 40.4093, 49.8671)
        assertTrue(d < 0.001, "identical points should be ~0m, got $d")
    }

    @Test
    fun `haversine matches known Baku distance`() {
        // Baku center (28 May) → Baku airport: ~20.3 km
        val d = TriggerEngine.haversineMetersStatic(40.3686, 49.8249, 40.4675, 50.0467)
        assertEquals(20_300.0, d, 1_500.0)
    }

    @Test
    fun `haversine is symmetric`() {
        val a = TriggerEngine.haversineMetersStatic(40.4093, 49.8671, 52.5200, 13.4050)
        val b = TriggerEngine.haversineMetersStatic(52.5200, 13.4050, 40.4093, 49.8671)
        assertEquals(a, b, 1e-6)
    }

    @Test
    fun `haversine 1 degree latitude is ~111 km`() {
        val d = TriggerEngine.haversineMetersStatic(0.0, 0.0, 1.0, 0.0)
        assertEquals(111_195.0, d, 1_000.0)
    }
}
