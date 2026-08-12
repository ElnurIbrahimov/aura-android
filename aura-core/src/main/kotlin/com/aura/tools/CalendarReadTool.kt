package com.aura.tools

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import com.aura.agent.Tool
import com.aura.agent.ToolContext
import com.aura.agent.ToolResult
import com.aura.agent.ToolRisk
import com.aura.providers.ToolDefinition
import com.aura.providers.ToolParameters
import com.aura.providers.ToolProperty
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import java.util.Calendar
import java.util.TimeZone

/**
 * Read calendar events. Default range: today + next 7 days (configurable
 * via the `days` parameter, max 30). The companion method
 * [readTodaysEvents] is a convenience wrapper that reads only today's
 * events (midnight to midnight) for the home screen and morning brief.
 * Mirrors aura/tools/calendar_tool.py.
 * Risk: PRIVACY (READ_CALENDAR).
 */
@Singleton
class CalendarReadTool @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun definition() = ToolDefinition(
        name = "calendar_read",
        description = "Read upcoming calendar events. Default: today + next 7 days, max 20 events.",
        parameters = ToolParameters(
            properties = mapOf(
                "days" to ToolProperty(type = "integer", description = "Number of days to look ahead (default 7, max 30)"),
                "max_results" to ToolProperty(type = "integer", description = "Maximum events to return (default 20, max 50)"),
            ),
            required = emptyList(),
        ),
    )

    val tool = Tool(
        name = "calendar_read",
        description = definition().description,
        risk = ToolRisk.PRIVACY,
        requiredPermissions = listOf(Manifest.permission.READ_CALENDAR),
        parameters = definition().parameters,
        execute = { call, ctx ->
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR)
                != PackageManager.PERMISSION_GRANTED
            ) {
                return@Tool ToolResult.NeedsPermission(
                    Manifest.permission.READ_CALENDAR,
                    "Calendar access is needed to read events.",
                )
            }
            val days = (call.arguments["days"] as? Int ?: 7).coerceIn(1, 30)
            val max = (call.arguments["max_results"] as? Int ?: 20).coerceIn(1, 50)
            try {
                val events = readEvents(days, max)
                ToolResult.Ok(formatEvents(events))
            } catch (e: SecurityException) {
                ToolResult.NeedsPermission(Manifest.permission.READ_CALENDAR, "Calendar permission revoked.")
            } catch (e: Exception) {
                ToolResult.Error("calendar read failed: ${e.message}", "exception")
            }
        },
    category = "productivity")
    private data class Event(val title: String, val begin: Long, val end: Long, val location: String, val allDay: Boolean)

    private fun readEvents(days: Int, max: Int): List<Event> {
        val cal = Calendar.getInstance(TimeZone.getDefault())
        val now = cal.timeInMillis
        cal.add(Calendar.DAY_OF_YEAR, days)
        val end = cal.timeInMillis

        val projection = arrayOf(
            CalendarContract.Events.TITLE,
            CalendarContract.Events.DTSTART,
            CalendarContract.Events.DTEND,
            CalendarContract.Events.EVENT_LOCATION,
            CalendarContract.Events.ALL_DAY,
        )
        val selection = "${CalendarContract.Events.DTSTART} >= ? AND ${CalendarContract.Events.DTSTART} <= ?"
        val args = arrayOf(now.toString(), end.toString())
        val sort = "${CalendarContract.Events.DTSTART} ASC LIMIT $max"

        val out = mutableListOf<Event>()
        context.contentResolver.query(
            CalendarContract.Events.CONTENT_URI, projection, selection, args, sort
        )?.use { c ->
            val titleIdx = c.getColumnIndex(CalendarContract.Events.TITLE)
            val beginIdx = c.getColumnIndex(CalendarContract.Events.DTSTART)
            val endIdx = c.getColumnIndex(CalendarContract.Events.DTEND)
            val locIdx = c.getColumnIndex(CalendarContract.Events.EVENT_LOCATION)
            val allDayIdx = c.getColumnIndex(CalendarContract.Events.ALL_DAY)
            while (c.moveToNext()) {
                out += Event(
                    title = c.getString(titleIdx) ?: "(no title)",
                    begin = c.getLong(beginIdx),
                    end = c.getLong(endIdx),
                    location = c.getString(locIdx) ?: "",
                    allDay = c.getInt(allDayIdx) == 1,
                )
            }
        }
        return out
    }

    /**
     * Read events for today (midnight to midnight) and return formatted strings
     * suitable for the home screen. Wraps [readEvents] internally and never
     * throws — returns an empty list on any error.
     */
    fun readTodaysEvents(limit: Int = 5): List<String> {
        return try {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR)
                != PackageManager.PERMISSION_GRANTED
            ) return emptyList()
            val events = readEvents(1, limit)
            if (events.isEmpty()) return emptyList()
            val df = java.text.SimpleDateFormat("HH:mm", java.util.Locale.US)
            events.map { e ->
                val time = if (e.allDay) "All day" else df.format(java.util.Date(e.begin))
                val loc = if (e.location.isNotEmpty()) " @ ${e.location}" else ""
                "· ${e.title} ($time)$loc"
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** Whether something is happening right now, and how soon the next thing is. */
    data class Window(val inEventNow: Boolean, val minutesToNext: Int?)

    /**
     * The calendar as it bears on *this moment*.
     *
     * [readEvents] selects `DTSTART >= now`, which by construction cannot see
     * the event you are currently sitting in — fine for "what's on today",
     * useless for "is this a terrible moment to interrupt". This asks the
     * other question: one query spanning [lookaheadMinutes], classified against
     * the clock.
     *
     * All-day events are ignored for [Window.inEventNow]. A birthday does not
     * make someone busy, and treating it as a meeting would mute Aura for a day
     * at a time.
     */
    fun window(
        now: Long = System.currentTimeMillis(),
        lookaheadMinutes: Int = 60,
    ): Window = try {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Window(inEventNow = false, minutesToNext = null)
        } else {
            val horizon = now + lookaheadMinutes * 60_000L
            val projection = arrayOf(
                CalendarContract.Events.DTSTART,
                CalendarContract.Events.DTEND,
                CalendarContract.Events.ALL_DAY,
            )
            var inEvent = false
            var soonest: Long? = null
            context.contentResolver.query(
                CalendarContract.Events.CONTENT_URI,
                projection,
                "${CalendarContract.Events.DTEND} >= ? AND ${CalendarContract.Events.DTSTART} <= ?",
                arrayOf(now.toString(), horizon.toString()),
                "${CalendarContract.Events.DTSTART} ASC LIMIT 20",
            )?.use { c ->
                val beginIdx = c.getColumnIndex(CalendarContract.Events.DTSTART)
                val endIdx = c.getColumnIndex(CalendarContract.Events.DTEND)
                val allDayIdx = c.getColumnIndex(CalendarContract.Events.ALL_DAY)
                while (c.moveToNext()) {
                    if (c.getInt(allDayIdx) == 1) continue
                    val begin = c.getLong(beginIdx)
                    val end = c.getLong(endIdx)
                    if (begin <= now && end >= now) inEvent = true
                    if (begin > now && (soonest == null || begin < soonest!!)) soonest = begin
                }
            }
            Window(
                inEventNow = inEvent,
                minutesToNext = soonest?.let { ((it - now) / 60_000L).toInt() },
            )
        }
    } catch (_: Exception) {
        Window(inEventNow = false, minutesToNext = null)
    }

    private fun formatEvents(events: List<Event>): String {
        if (events.isEmpty()) return "No upcoming events in the next 7 days."
        val df = java.text.SimpleDateFormat("EEE MMM d, HH:mm", java.util.Locale.US)
        return events.mapIndexed { i, e ->
            val time = if (e.allDay) "all day ${df.format(java.util.Date(e.begin)).substring(0, 10)}" else "${df.format(java.util.Date(e.begin))} - ${df.format(java.util.Date(e.end)).substringAfter(' ')}"
            val loc = if (e.location.isNotEmpty()) " @ ${e.location}" else ""
            "${i + 1}. ${e.title} ($time)$loc"
        }.joinToString("\n")
    }
}
