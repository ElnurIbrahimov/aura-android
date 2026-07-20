package com.aura.world

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorldModelTest {

    // ---------------------------------------------------------------
    // BeliefEntity
    // ---------------------------------------------------------------

    @Test
    fun `belief has required fields`() {
        val b = BeliefEntity(
            id = "b1",
            subject = "user",
            predicate = "name",
            valueJson = "\"Elnur\"",
        )
        assertEquals("b1", b.id)
        assertEquals("user", b.subject)
        assertEquals("name", b.predicate)
        assertEquals("\"Elnur\"", b.valueJson)
        assertEquals("active", b.status)
        assertEquals("personal", b.privacyClass)
        assertEquals(1.0f, b.confidence)
        assertEquals(0L, b.validTo)
        assertTrue(b.createdAt > 0)
    }

    @Test
    fun `belief can be superseded`() {
        val original = BeliefEntity(id = "b1", subject = "user", predicate = "name", valueJson = "\"Old\"")
        val replacement = BeliefEntity(
            id = "b2", subject = "user", predicate = "name",
            valueJson = "\"New\"", supersededBy = "b3",
        )
        assertEquals(null, original.supersededBy)
        assertEquals("b3", replacement.supersededBy)
    }

    @Test
    fun `belief validTo default means no end bound`() {
        val b = BeliefEntity(id = "b1", subject = "user", predicate = "preference", valueJson = "\"dark\"")
        assertEquals(0L, b.validTo) // 0 = no end bound
    }

    // ---------------------------------------------------------------
    // EvidenceEntity
    // ---------------------------------------------------------------

    @Test
    fun `evidence links to belief`() {
        val e = EvidenceEntity(
            id = "e1",
            beliefId = "b1",
            source = "user_statement",
            summary = "User said they prefer dark mode",
        )
        assertEquals("b1", e.beliefId)
        assertEquals("user_statement", e.source)
        assertTrue(e.summary.contains("dark mode"))
    }

    @Test
    fun `evidence has default detail and confidence`() {
        val e = EvidenceEntity(id = "e1", beliefId = "b1", source = "tool_result", summary = "x")
        assertEquals("{}", e.detailJson)
        assertEquals(1.0f, e.confidence)
    }

    // ---------------------------------------------------------------
    // WorldEventEntity
    // ---------------------------------------------------------------

    @Test
    fun `world event is created unconsumed`() {
        val w = WorldEventEntity(
            id = "w1",
            eventType = "calendar_event",
            source = "calendar_monitor",
            summary = "Meeting at 3pm",
        )
        assertEquals("calendar_event", w.eventType)
        assertEquals("calendar_monitor", w.source)
        assertEquals(false, w.consumed)
    }

    @Test
    fun `world event can be marked consumed`() {
        val w = WorldEventEntity(
            id = "w1", eventType = "notification", source = "daemon",
            summary = "thought",
        )
        val consumed = w.copy(consumed = true)
        assertTrue(consumed.consumed)
    }

    @Test
    fun `world event has default payload`() {
        val w = WorldEventEntity(id = "w1", eventType = "test", source = "test", summary = "test")
        assertEquals("{}", w.payloadJson)
    }

    // ---------------------------------------------------------------
    // OpportunityEntity
    // ---------------------------------------------------------------

    @Test
    fun `opportunity has required fields`() {
        val o = OpportunityEntity(
            id = "o1",
            title = "Review inbox",
            description = "You have 3 unread messages",
        )
        assertEquals("o1", o.id)
        assertEquals("Review inbox", o.title)
        assertEquals("suggestion", o.kind)
        assertEquals("proposed", o.status)
        assertEquals(0.5f, o.benefit)
        assertEquals(0.5f, o.urgency)
        assertEquals(0.5f, o.confidence)
    }

    @Test
    fun `opportunity can be approved and executed`() {
        val o = OpportunityEntity(
            id = "o1", title = "t", description = "d",
            status = "proposed",
        )
        val approved = o.copy(status = "approved")
        assertEquals("approved", approved.status)
        val executed = approved.copy(status = "executed", resolvedAt = System.currentTimeMillis())
        assertEquals("executed", executed.status)
        assertNotNull(executed.resolvedAt)
    }

    @Test
    fun `opportunity can be snoozed`() {
        val snoozeTime = System.currentTimeMillis() + 3600_000
        val o = OpportunityEntity(
            id = "o1", title = "t", description = "d",
            status = "snoozed", snoozeUntil = snoozeTime,
        )
        assertEquals("snoozed", o.status)
        assertEquals(snoozeTime, o.snoozeUntil)
    }

    @Test
    fun `opportunity has default cost estimate and evidence`() {
        val o = OpportunityEntity(id = "o1", title = "t", description = "d")
        assertEquals("{}", o.costEstimateJson)
        assertEquals("[]", o.evidenceJson)
        assertEquals("{}", o.suggestedActionJson)
    }
}