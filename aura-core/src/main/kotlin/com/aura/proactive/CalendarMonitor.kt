package com.aura.proactive

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import com.aura.data.UserPreferences
import com.aura.proactive.ProactiveEventBus.Event.CalendarEventSoon
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One-shot calendar check over [CalendarContract.Instances]. Called by
 * the periodic [CalendarCheckWorker] (every 15 minutes) and by the
 * ProactiveRunner's "fire now" button. WorkManager owns the cadence —
 * there is no poll loop and no foreground service any more.
 *
 * The Instances view expands recurring events into concrete
 * occurrences, so a weekly stand-up is announced every week (the old
 * Events-table query only ever saw the series' first DTSTART).
 *
 * Dedup is persisted: each announced occurrence is remembered as an
 * instance key `"$eventId:$begin"` in [UserPreferences], pruned to
 * occurrences that began within the last 24 h. Surviving process
 * death this way means no duplicate announcements after a restart,
 * and re-announcing the *next* occurrence of a recurring event still
 * works because its `begin` differs.
 */
@Singleton
class CalendarMonitor @Inject constructor(
    @ApplicationContext private val context: Context,
    private val proactiveEvents: ProactiveEvents,
    private val userPreferences: UserPreferences,
) {
    /** A single expanded occurrence from the Instances table. */
    data class InstanceRow(
        val eventId: Long,
        val title: String,
        val begin: Long,
        val allDay: Boolean = false,
    ) {
        /** Persisted dedup key — unique per occurrence, not per series. */
        val key: String get() = "$eventId:$begin"
    }

    /** Kept for the ProactiveRunner's "fire now" button. */
    suspend fun pollOnce() = checkOnce()

    /**
     * One check pass: query the next [LOOKAHEAD_MS] of calendar
     * instances, announce the ones we haven't announced yet via
     * [ProactiveEvents.record] (persist + emit — reliable even when
     * no ViewModel has constructed the event singleton), and persist
     * the pruned announced-set.
     */
    suspend fun checkOnce(now: Long = System.currentTimeMillis()) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR)
            != PackageManager.PERMISSION_GRANTED
        ) return
        try {
            val rows = queryUpcomingInstances(now)
            val announced = userPreferences.announcedCalendarInstances.first()
            val fresh = selectNew(rows, announced, now)
            for (row in fresh) {
                val minutesUntil = ((row.begin - now) / 60_000L).toInt().coerceAtLeast(0)
                proactiveEvents.record(CalendarEventSoon(row.title, minutesUntil))
            }
            val updated = pruneAnnounced(announced + fresh.map { it.key }, now)
            if (updated != announced) {
                userPreferences.setAnnouncedCalendarInstances(updated)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: SecurityException) {
            // permission revoked mid-check
        } catch (e: Exception) {
            try {
                android.util.Log.w("CalendarMonitor", "calendar check failed: ${e.message}")
            } catch (_: RuntimeException) {
                // android.util.Log unavailable in pure JVM tests
            }
        }
    }

    /**
     * Query [CalendarContract.Instances] for occurrences beginning in
     * `[now, now + LOOKAHEAD_MS]`. The window goes in the URI path
     * (that's the Instances-API contract), the selection excludes
     * all-day, self-declined, and cancelled events.
     */
    private fun queryUpcomingInstances(now: Long): List<InstanceRow> {
        val uriBuilder = CalendarContract.Instances.CONTENT_URI.buildUpon()
        ContentUris.appendId(uriBuilder, now)
        ContentUris.appendId(uriBuilder, now + LOOKAHEAD_MS)
        val projection = arrayOf(
            CalendarContract.Instances.EVENT_ID,
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.ALL_DAY,
        )
        val selection =
            "${CalendarContract.Instances.ALL_DAY} = 0" +
                " AND (${CalendarContract.Instances.SELF_ATTENDEE_STATUS} IS NULL" +
                " OR ${CalendarContract.Instances.SELF_ATTENDEE_STATUS} != ${CalendarContract.Attendees.ATTENDEE_STATUS_DECLINED})" +
                " AND (${CalendarContract.Instances.STATUS} IS NULL" +
                " OR ${CalendarContract.Instances.STATUS} != ${CalendarContract.Events.STATUS_CANCELED})"
        val rows = mutableListOf<InstanceRow>()
        context.contentResolver.query(
            uriBuilder.build(),
            projection,
            selection,
            null,
            "${CalendarContract.Instances.BEGIN} ASC",
        )?.use { c ->
            while (c.moveToNext()) {
                rows.add(
                    InstanceRow(
                        eventId = c.getLong(0),
                        title = c.getString(1) ?: "(no title)",
                        begin = c.getLong(2),
                        allDay = c.getInt(3) != 0,
                    )
                )
            }
        }
        return rows
    }

    companion object {
        /** How far ahead each check looks. */
        const val LOOKAHEAD_MS = 30L * 60L * 1000L

        /** How long an announced instance key is retained after its begin time. */
        const val ANNOUNCED_RETENTION_MS = 24L * 60L * 60L * 1000L

        /**
         * Pure selection: which of [rows] should be announced now?
         * Skips all-day rows, occurrences that already began, keys
         * already in [announced], and duplicate keys within the batch.
         */
        fun selectNew(rows: List<InstanceRow>, announced: Set<String>, now: Long): List<InstanceRow> =
            rows.asSequence()
                .filter { !it.allDay }
                .filter { it.begin >= now }
                .filter { it.key !in announced }
                .distinctBy { it.key }
                .toList()

        /**
         * Pure pruning: keep only keys whose occurrence began within
         * the last [ANNOUNCED_RETENTION_MS] (or hasn't begun yet).
         * Malformed keys are dropped.
         */
        fun pruneAnnounced(announced: Set<String>, now: Long): Set<String> {
            val cutoff = now - ANNOUNCED_RETENTION_MS
            return announced.filterTo(mutableSetOf()) { key ->
                val begin = key.substringAfterLast(':', "").toLongOrNull() ?: return@filterTo false
                begin >= cutoff
            }
        }
    }
}
