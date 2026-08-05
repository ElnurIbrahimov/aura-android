package com.aura.triggers

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import androidx.core.content.ContextCompat
import com.aura.tasks.TaskDao
import com.aura.tasks.TaskEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import java.time.ZonedDateTime
import javax.inject.Inject
import javax.inject.Singleton

/** Evaluates registered [Trigger] conditions and returns fired actions. */
@Singleton
class TriggerEngine @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val webChangeDetector: WebChangeDetector,
    private val taskDao: TaskDao,
) {
    /** Check all triggers; returns list of (triggerId, action) pairs that fired. */
    suspend fun checkAll(triggers: List<Trigger>, now: ZonedDateTime = ZonedDateTime.now()): List<TriggerAction> {
        return triggers.filter { it.enabled }.mapNotNull { trigger ->
            when (val condition = trigger.condition) {
                is TriggerCondition.Schedule -> checkSchedule(condition, now)
                is TriggerCondition.WebChanged -> checkWebChanged(condition)
                is TriggerCondition.LocationEntered -> checkLocation(condition)
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

    /**
     * LocationEntered fires when the device's last-known position is within
     * [TriggerCondition.LocationEntered.radiusMeters] of the configured point.
     *
     * Uses the passive last-known fix from [LocationManager] — no GPS lock,
     * no Play Services, no extra battery. If location permission is missing
     * or no fix is available, the trigger silently does not fire (the same
     * no-op semantics as a condition that isn't met).
     */
    @SuppressLint("MissingPermission")
    private fun checkLocation(condition: TriggerCondition.LocationEntered): Unit? {
        val hasFine = ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        val hasCoarse = ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        if (!hasFine && !hasCoarse) return null
        val mgr = ContextCompat.getSystemService(appContext, LocationManager::class.java) ?: return null
        var best: Location? = null
        for (provider in mgr.getProviders(true)) {
            val loc = try { mgr.getLastKnownLocation(provider) } catch (e: SecurityException) { null } ?: continue
            if (best == null || loc.accuracy < best.accuracy) best = loc
        }
        val loc = best ?: return null
        val distance = haversineMetersStatic(loc.latitude, loc.longitude, condition.lat, condition.lon)
        return if (distance <= condition.radiusMeters) Unit else null
    }

    companion object {
        /** Great-circle distance in meters between two WGS-84 coordinates (pure, testable). */
        internal fun haversineMetersStatic(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val earthRadiusM = 6_371_000.0
            val dLat = Math.toRadians(lat2 - lat1)
            val dLon = Math.toRadians(lon2 - lon1)
            val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                Math.sin(dLon / 2) * Math.sin(dLon / 2)
            return earthRadiusM * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
        }
    }
}
