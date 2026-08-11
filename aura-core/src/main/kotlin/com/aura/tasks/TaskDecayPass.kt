package com.aura.tasks

import android.util.Log
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Applies [TaskSalience] to every pending task, and lets the list shrink.
 *
 * Rides the existing six-hourly memory decay worker rather than adding one:
 * this is the same idea Aura already applies to memories, one table over, and
 * two workers doing the same job on different schedules is how two answers to
 * "is this still relevant" start disagreeing.
 *
 * Writes are conditional. A pass over a hundred untouched tasks on a quiet
 * afternoon should cost nothing, so only rows whose salience actually moved
 * enough to matter are written back.
 */
@Singleton
class TaskDecayPass @Inject constructor(
    private val taskDao: TaskDao,
) {

    /** @return how many tasks newly went quiet. */
    suspend fun run(now: Long = System.currentTimeMillis()): Int {
        val tasks = runCatching { taskDao.allPending() }
            .onFailure { Log.w(TAG, "could not read tasks for decay: ${it.message}", it) }
            .getOrDefault(emptyList())
        if (tasks.isEmpty()) return 0

        var wentQuiet = 0
        for (task in tasks) {
            // Rows the trigger engine parks in this table are not tasks — they
            // are content hashes for watched URLs — so they are left alone
            // rather than being scored as if the user had written them down.
            if (task.description.startsWith(TRIGGER_HASH_PREFIX)) continue

            val updated = TaskSalience.decayed(task, now)
            val wasQuiet = TaskSalience.isQuiet(task.salience)
            val nowQuiet = TaskSalience.isQuiet(updated)
            if (!wasQuiet && nowQuiet) wentQuiet++

            val moved = kotlin.math.abs(updated - task.salience) >= WRITE_EPSILON
            val transitioned = wasQuiet != nowQuiet
            if (!moved && !transitioned) continue

            val row = task.copy(
                salience = updated,
                quietSince = when {
                    nowQuiet && task.quietSince == 0L -> now
                    !nowQuiet -> 0L
                    else -> task.quietSince
                },
                // The reason a task came back is only interesting until it is
                // read; a row that merely dimmed further should not keep
                // claiming it was revived.
                revivedReason = if (nowQuiet) "" else task.revivedReason,
            )
            runCatching { taskDao.update(row) }
                .onFailure { Log.w(TAG, "decay write failed for ${task.id}: ${it.message}", it) }
        }
        return wentQuiet
    }

    companion object {
        private const val TAG = "TaskDecayPass"

        /** Below this, a write is not worth the row. */
        private const val WRITE_EPSILON = 0.01

        /** How `TriggerEngine` marks the rows it parks in the tasks table. */
        const val TRIGGER_HASH_PREFIX = "trigger-hash:"
    }
}
