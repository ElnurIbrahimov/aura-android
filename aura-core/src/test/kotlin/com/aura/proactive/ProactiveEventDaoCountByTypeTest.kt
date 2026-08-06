package com.aura.proactive

import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

/**
 * Regression test for ProactiveEventDao.countByType — verifies the
 * DAO method exists and can be used to count events by their eventType
 * field. This was added to support the Settings Emotion & Daemon
 * section's daemon thought count display.
 *
 * Uses FakeProactiveEventDao from ProactiveEventsTest.
 */
class ProactiveEventDaoCountByTypeTest {

    @Test
    fun `countByType returns zero for empty table`() {
        val dao = FakeProactiveEventDaoForCount()
        // Suspend function — call in runBlocking
        kotlinx.coroutines.runBlocking {
            assertEquals(0, dao.countByType("daemon_thought"))
        }
    }

    @Test
    fun `countByType counts only matching eventType`() {
        val dao = FakeProactiveEventDaoForCount()
        kotlinx.coroutines.runBlocking {
            dao.insert(ProactiveEventEntity(eventType = "daemon_thought", title = "Test", body = "", timestamp = 1000L))
            dao.insert(ProactiveEventEntity(eventType = "daemon_thought", title = "Test2", body = "", timestamp = 2000L))
            dao.insert(ProactiveEventEntity(eventType = "morning_brief", title = "Brief", body = "", timestamp = 3000L))
            assertEquals(2, dao.countByType("daemon_thought"))
            assertEquals(1, dao.countByType("morning_brief"))
            assertEquals(0, dao.countByType("nonexistent"))
        }
    }
}

private class FakeProactiveEventDaoForCount : ProactiveEventDao {
    private val rows = mutableListOf<ProactiveEventEntity>()

    override suspend fun insert(event: ProactiveEventEntity): Long {
        val id = (rows.maxOfOrNull { it.id } ?: 0L) + 1L
        rows.add(event.copy(id = id))
        return id
    }

    override suspend fun insertAll(events: List<ProactiveEventEntity>) {
        events.forEach { event ->
            rows.removeAll { it.id == event.id }
            rows.add(event)
        }
    }

    override suspend fun allForBackup(): List<ProactiveEventEntity> = rows.sortedBy { it.timestamp }

    override suspend fun deleteAll() { rows.clear() }

    override suspend fun recent(limit: Int): List<ProactiveEventEntity> = rows.sortedByDescending { it.timestamp }.take(limit)

    override suspend fun byId(id: Long): ProactiveEventEntity? = rows.firstOrNull { it.id == id }

    override suspend fun countSince(since: Long): Int = rows.count { it.timestamp > since }

    override suspend fun deleteOlderThan(cutoff: Long): Int {
        val before = rows.size
        rows.removeAll { it.timestamp < cutoff }
        return before - rows.size
    }

    override suspend fun byCorrelationTag(tag: String, limit: Int): List<ProactiveEventEntity> =
        rows.filter { it.correlationTag == tag }.sortedByDescending { it.timestamp }.take(limit)

    override suspend fun deleteByCorrelationTag(tag: kotlin.String): Int {
        val before = rows.size
        rows.removeAll { it.correlationTag == tag }
        return before - rows.size
    }

    override suspend fun countByType(type: kotlin.String): Int =
        rows.count { it.eventType == type }
}