package com.aura.proactive

import com.aura.proactive.CalendarMonitor.Companion.pruneAnnounced
import com.aura.proactive.CalendarMonitor.Companion.selectNew
import com.aura.proactive.CalendarMonitor.InstanceRow
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pure-function tests for the calendar monitor's dedup seam:
 * [CalendarMonitor.selectNew] decides which Instances rows get
 * announced; [CalendarMonitor.pruneAnnounced] bounds the persisted
 * key set. Together they encode the fixes for the two old bugs —
 * recurring events never re-announcing (Events-table query saw only
 * the series DTSTART) and duplicate announcements after process
 * death (dedup set was in-memory only).
 */
class CalendarMonitorTest {

    private val now = 1_700_000_000_000L

    private fun row(eventId: Long, beginOffsetMin: Long, title: String = "Event $eventId", allDay: Boolean = false) =
        InstanceRow(eventId = eventId, title = title, begin = now + beginOffsetMin * 60_000L, allDay = allDay)

    // ---- selectNew ----

    @Test
    fun `announces an upcoming instance that has not been announced`() {
        val rows = listOf(row(1, 10))
        val fresh = selectNew(rows, announced = emptySet(), now = now)
        assertEquals(1, fresh.size)
        assertEquals("1:${now + 10 * 60_000L}", fresh[0].key)
    }

    @Test
    fun `skips instance keys already announced`() {
        val r = row(1, 10)
        val fresh = selectNew(listOf(r), announced = setOf(r.key), now = now)
        assertTrue(fresh.isEmpty())
    }

    @Test
    fun `re-announces the next occurrence of a recurring event`() {
        // Same eventId, different begin — the weekly stand-up case.
        val lastWeek = InstanceRow(eventId = 1, title = "Standup", begin = now - 7L * 24 * 60 * 60 * 1000)
        val thisWeek = row(1, 15, title = "Standup")
        val fresh = selectNew(listOf(thisWeek), announced = setOf(lastWeek.key), now = now)
        assertEquals(listOf(thisWeek), fresh)
    }

    @Test
    fun `skips all-day instances`() {
        val fresh = selectNew(listOf(row(1, 10, allDay = true)), announced = emptySet(), now = now)
        assertTrue(fresh.isEmpty())
    }

    @Test
    fun `skips instances that already began`() {
        val fresh = selectNew(listOf(row(1, -5)), announced = emptySet(), now = now)
        assertTrue(fresh.isEmpty())
    }

    @Test
    fun `dedupes duplicate keys within one batch`() {
        val r = row(1, 10)
        val fresh = selectNew(listOf(r, r.copy()), announced = emptySet(), now = now)
        assertEquals(1, fresh.size)
    }

    @Test
    fun `process death does not re-announce — persisted set still filters`() {
        // Pass 1: fresh process, empty in-memory state, persisted set empty.
        val rows = listOf(row(1, 10), row(2, 20))
        val firstPass = selectNew(rows, announced = emptySet(), now = now)
        assertEquals(2, firstPass.size)
        val persisted = pruneAnnounced(firstPass.map { it.key }.toSet(), now)

        // Simulated process death: only `persisted` survives. Pass 2
        // sees the same rows again and must announce nothing.
        val secondPass = selectNew(rows, announced = persisted, now = now + 5 * 60_000L)
        assertTrue(secondPass.isEmpty())
    }

    // ---- pruneAnnounced ----

    @Test
    fun `prune keeps keys that began within 24h and future keys`() {
        val recent = "1:${now - 60 * 60_000L}"          // 1h ago
        val future = "2:${now + 10 * 60_000L}"           // upcoming
        val pruned = pruneAnnounced(setOf(recent, future), now)
        assertEquals(setOf(recent, future), pruned)
    }

    @Test
    fun `prune drops keys older than 24h`() {
        val stale = "1:${now - 25L * 60 * 60 * 1000}"    // 25h ago
        val recent = "2:${now - 60 * 60_000L}"
        val pruned = pruneAnnounced(setOf(stale, recent), now)
        assertEquals(setOf(recent), pruned)
    }

    @Test
    fun `prune drops malformed keys`() {
        val pruned = pruneAnnounced(setOf("garbage", "1:notanumber", ""), now)
        assertTrue(pruned.isEmpty())
    }

    @Test
    fun `prune keeps the boundary key at exactly now minus 24h`() {
        val boundary = "1:${now - CalendarMonitor.ANNOUNCED_RETENTION_MS}"
        val pruned = pruneAnnounced(setOf(boundary), now)
        assertEquals(setOf(boundary), pruned)
    }
}
