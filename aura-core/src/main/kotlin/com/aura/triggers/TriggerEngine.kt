package com.aura.triggers

import com.aura.tasks.TaskDao
import com.aura.tasks.TaskEntity
import kotlinx.coroutines.flow.first
import java.time.ZonedDateTime
import javax.inject.Inject
import javax.inject.Singleton

/** Evaluates registered [Trigger] conditions and returns fired actions. */
@Singleton
class TriggerEngine @Inject constructor(
    private val webChangeDetector: WebChangeDetector,
    private val taskDao: TaskDao,
) {
    /** Check all triggers; returns list of (triggerId, action) pairs that fired. */
    suspend fun checkAll(triggers: List<Trigger>, now: ZonedDateTime = ZonedDateTime.now()): List<TriggerAction> {
        return triggers.filter { it.enabled }.mapNotNull { trigger ->
            when (val condition = trigger.condition) {
                is TriggerCondition.Schedule -> checkSchedule(condition, now)
                is TriggerCondition.WebChanged -> checkWebChanged(condition)
                is TriggerCondition.LocationEntered -> null // Not yet implemented: requires location permission + FusedLocationProvider. UI labels this as "not yet implemented" in TriggersSection.
                is TriggerCondition.IntentReceived -> null // handled by BroadcastReceiver, not periodic worker
            }?.let { trigger.action }
        }
    }

    private fun checkSchedule(condition: TriggerCondition.Schedule, now: ZonedDateTime): Unit? {
        // Minimal cron: support "hourly", "daily@HH:mm", "weekly@DAY@HH:mm".
        val parts = condition.cron.split("@")
        return when (parts[0]) {
            "hourly" -> if (now.minute == 0) Unit else null
            "daily" -> {
                val time = parts.getOrNull(1) ?: return null
                if (formatTime(now) == time) Unit else null
            }
            "weekly" -> {
                val day = parts.getOrNull(1) ?: return null
                val time = parts.getOrNull(2) ?: return null
                if (now.dayOfWeek.name.take(3).lowercase() == day.lowercase() && formatTime(now) == time) Unit else null
            }
            else -> null
        }
    }

    private fun formatTime(now: ZonedDateTime): String =
        String.format("%02d:%02d", now.hour, now.minute)

    private suspend fun checkWebChanged(condition: TriggerCondition.WebChanged): Unit? {
        val latestHash = webChangeDetector.hash(condition.url) ?: return null
        val stored = taskDao.observeAll().first().find { it.description == "trigger-hash:${condition.url}" }
        val storedHash = stored?.title
        val taskId = stored?.id ?: java.util.UUID.randomUUID().toString()
        taskDao.insert(
            TaskEntity(
                id = taskId,
                title = latestHash,
                description = "trigger-hash:${condition.url}",
                createdAt = System.currentTimeMillis(),
            ),
        )
        return if (storedHash != null && storedHash != latestHash) Unit else null
    }
}
