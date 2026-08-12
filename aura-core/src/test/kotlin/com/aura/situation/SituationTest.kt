package com.aura.situation

import org.junit.Test
import java.util.Calendar
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Knowing whether now is a bad moment.
 *
 * The property that matters most here is the one that is easiest to get
 * backwards: **an unknown situation permits.** `InterruptionLedger` is the
 * gate — it decides from evidence whether a category has earned the right to
 * interrupt at all. This is only a veto on top of it, so a device that will not
 * say whether the screen is on, or a calendar Aura cannot read, must fall back
 * to the ledger's judgement rather than silently mute the app forever. A veto
 * that fails closed is indistinguishable from a broken feature.
 */
class SituationTest {

    private fun at(hour: Int, day: Int = Calendar.TUESDAY) = Situation(
        at = 0L,
        localHour = hour,
        dayOfWeek = day,
        weekend = day == Calendar.SATURDAY || day == Calendar.SUNDAY,
    )

    @Test
    fun `knowing nothing does not block`() {
        assertTrue(at(14).interruptible, "an unreadable situation must defer to the ledger, not override it")
        assertNull(at(14).blockedBecause)
    }

    @Test
    fun `a calendar event blocks`() {
        val situation = at(14).copy(inEventNow = true)
        assertFalse(situation.interruptible)
        assertEquals("you're in something", situation.blockedBecause)
    }

    @Test
    fun `a call blocks`() {
        assertFalse(at(14).copy(onACall = true).interruptible)
    }

    @Test
    fun `the middle of the night blocks, unless you are visibly awake`() {
        assertFalse(at(3).interruptible, "3am with no screen signal is asleep until proven otherwise")
        assertFalse(at(3).copy(screenOn = false).interruptible)
        // Someone up at 3am is exactly who a silent app annoys.
        assertTrue(at(3).copy(screenOn = true).interruptible)
        assertTrue(at(23).copy(screenOn = true).interruptible)
    }

    @Test
    fun `an ordinary afternoon is fine`() {
        assertTrue(at(14).copy(screenOn = true, inEventNow = false, onACall = false).interruptible)
    }

    @Test
    fun `the description mentions only what is known`() {
        val bare = at(14).describe()
        assertTrue(bare.startsWith("Tuesday 14:00"), bare)
        // Nothing else was readable, so nothing else is claimed.
        assertFalse(bare.contains("null"), bare)
        assertFalse(bare.contains("charging"), bare)
    }

    @Test
    fun `the description says what is happening when it knows`() {
        val described = at(9).copy(
            inEventNow = true,
            charging = true,
            screenOn = true,
            minutesSinceLastMessage = 3 * 60,
            tension = 0.8f,
            foregroundApp = "com.android.chrome",
        ).describe()
        assertTrue(described.contains("in a calendar event"), described)
        assertTrue(described.contains("charging"), described)
        assertTrue(described.contains("last spoke 3h ago"), described)
        assertTrue(described.contains("seems tense"), described)
        assertTrue(described.contains("using chrome"), described)
    }

    @Test
    fun `an event starting soon is worth mentioning, one next week is not`() {
        assertTrue(at(9).copy(minutesToNextEvent = 10).describe().contains("starts in 10 min"))
        assertFalse(at(9).copy(minutesToNextEvent = 600).describe().contains("starts in"))
    }

    @Test
    fun `clockOnly fills the clock and nothing else`() {
        // Thursday 2026-08-13, 11:00 local is not knowable here, so assert the
        // shape rather than the values: every non-clock field is unknown.
        val situation = Situation.clockOnly(1_700_000_000_000L)
        assertNull(situation.screenOn)
        assertNull(situation.inEventNow)
        assertNull(situation.foregroundApp)
        assertTrue(situation.localHour in 0..23)
    }
}
