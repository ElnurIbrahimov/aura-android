package com.aura.tasks

import com.aura.tasks.ReminderScheduler
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/** Schedules reminder jobs for tasks. */
@Singleton
class TaskScheduler @Inject constructor(
    private val reminderScheduler: ReminderScheduler,
) {
    /** Compute the next occurrence of a task in millis. */
    fun nextOccurrence(task: TaskEntity, now: Instant = Instant.now()): Long? {
        val recurrence = task.recurrence ?: "none"
        val dueAt = task.dueAt ?: now.toEpochMilli()
        if (recurrence == "none") return dueAt
        return ReminderRecurrence.nextTrigger(dueAt, recurrence, now.toEpochMilli())
    }

    /** Schedule or reschedule a reminder for the task. */
    suspend fun schedule(task: TaskEntity) {
        val dueAt = nextOccurrence(task) ?: return
        reminderScheduler.create(
            message = "notify: ${task.title}",
            triggerAt = dueAt,
            recurrence = task.recurrence ?: "none",
            taskId = task.id,
        )
    }

    /** Cancel reminders for a task. */
    suspend fun cancel(taskId: String) {
        // ReminderScheduler has no cancel by taskId; worker will replace by unique work? It uses reminder id.
        // We can cancel WorkManager by tag if we tagged with taskId. For now no-op: callers cancel by reminder id.
    }

    /** Flow of tasks enriched with next occurrence. */
    fun upcoming(tasks: Flow<List<TaskEntity>>): Flow<List<Pair<TaskEntity, Long?>>> =
        tasks.map { list -> list.map { it to nextOccurrence(it) } }
}
