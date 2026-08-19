package com.aura.hands

import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import io.mockk.mockk
import io.mockk.verify
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class HandSchedulerPolicyTest {
    private val now = ZonedDateTime.of(2026, 7, 13, 10, 0, 0, 0, ZoneId.of("UTC"))
    private val hand = Hand(
        id = "h1",
        name = "Morning",
        scheduleType = HandScheduleType.DAILY.value,
        scheduleHour = 11,
    )

    /** A hand whose steps drive another app's screen. */
    private val screenHand = hand.copy(
        id = "h2",
        name = "Send the daily update",
        steps = """[{"tool":"screen_act","args":{"action":"tap","selector":"{\"text\":\"Send\"}"}}]""",
    )

    @Test
    fun `a hand that drives the screen never has a next run`() {
        // screen_act needs a ScreenControlSession, and a session is opened only by a
        // confirmation the user answers. A scheduled one therefore stops at its first step
        // with NEEDS_APPROVAL and does nothing — at 09:00, with nobody there to read it,
        // every day, forever. Refusing the schedule outright is the honest version of a
        // thing that cannot work.
        assertNull(
            HandScheduler.nextRunAt(screenHand, now),
            "a screen-driving hand was given a run time it cannot use",
        )
    }

    @Test
    fun `the same schedule on a hand that does not touch the screen still runs`() {
        // The control. Without this the test above passes if scheduling breaks entirely.
        assertNotNull(
            HandScheduler.nextRunAt(hand.copy(steps = """[{"tool":"web_search","args":{"query":"x"}}]"""), now),
            "an ordinary hand must still schedule",
        )
    }

    @Test
    fun `editor scheduling replaces pending occurrence`() {
        val workManager = mockk<WorkManager>(relaxed = true)
        val scheduler = HandScheduler(workManager)

        scheduler.schedule(hand, now)

        verify {
            workManager.enqueueUniqueWork(
                HandScheduler.uniqueWorkName(hand.id),
                ExistingWorkPolicy.REPLACE,
                any<OneTimeWorkRequest>(),
            )
        }
    }

    @Test
    fun `terminal worker appends next occurrence without cancelling itself`() {
        val workManager = mockk<WorkManager>(relaxed = true)
        val scheduler = HandScheduler(workManager)

        scheduler.scheduleNextAfterRun(hand, now)

        verify {
            workManager.enqueueUniqueWork(
                HandScheduler.uniqueWorkName(hand.id),
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                any<OneTimeWorkRequest>(),
            )
        }
    }
}
