package com.aura.tasks

import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TaskSchedulerTest {
    private val reminderScheduler: ReminderScheduler = mockk(relaxed = true)
    private val scheduler = TaskScheduler(reminderScheduler)

    @Test
    fun `nextOccurrence returns dueAt for one-shot`() {
        val dueAt = Instant.parse("2026-08-01T12:00:00Z").toEpochMilli()
        val task = TaskEntity(id = "1", title = "One", dueAt = dueAt, recurrence = null, createdAt = 0L)
        assertEquals(dueAt, scheduler.nextOccurrence(task, Instant.now()))
    }

    @Test
    fun `nextOccurrence advances daily recurrence past now`() {
        val dueAt = Instant.parse("2026-08-01T12:00:00Z").toEpochMilli()
        val now = Instant.parse("2026-08-10T10:00:00Z")
        val task = TaskEntity(id = "1", title = "Daily", dueAt = dueAt, recurrence = "daily", createdAt = 0L)
        val next = scheduler.nextOccurrence(task, now)
        assertTrue(next != null && next > now.toEpochMilli())
    }

    @Test
    fun `schedule creates reminder via ReminderScheduler`() = runTest {
        val dueAt = Instant.parse("2026-08-01T12:00:00Z").toEpochMilli()
        val task = TaskEntity(id = "1", title = "T", dueAt = dueAt, recurrence = "daily", createdAt = 0L)
        scheduler.schedule(task)
        coVerify { reminderScheduler.create("notify: T", any(), "daily", task.id, any()) }
    }
}
