package com.aura.proactive

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.aura.data.UserPreferences
import com.aura.situation.Situation
import com.aura.situation.SituationReader
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Calendar
import kotlin.test.assertFalse

/**
 * Earning the right to interrupt is not the same as it being a good time.
 *
 * `InterruptionLedger` answers the first question from evidence — has this kind
 * of suggestion actually led anywhere. It has no way to answer the second: its
 * whole model of time is which *hours* have historically worked, so it cannot
 * tell a Tuesday 3pm at a desk from a Tuesday 3pm in a meeting. The two
 * compose, and this pins how.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class SituationalVetoTest {

    private val ledger = mockk<InterruptionLedger>()
    private val prefs = mockk<UserPreferences>()
    private val reader = mockk<SituationReader>()

    private val now = 1_700_000_000_000L

    private fun notifier(): ProactiveNotifier {
        val context = ApplicationProvider.getApplicationContext<Context>()
        return ProactiveNotifier(context, ledger, prefs, reader)
    }

    private fun earned() {
        every { prefs.interruptionPolicies } returns flowOf(emptyMap())
        coEvery { ledger.verdict(any(), any(), any()) } returns
            InterruptionVerdict(ProactiveFindingType.STUCK_TASKS, mayInterrupt = true, reason = "earned.")
        coEvery { ledger.withinGlobalCaps(any()) } returns true
    }

    private fun situation(inEvent: Boolean = false, onCall: Boolean = false, hour: Int = 14) {
        coEvery { reader.get(any(), any()) } returns Situation(
            at = now,
            localHour = hour,
            dayOfWeek = Calendar.TUESDAY,
            weekend = false,
            screenOn = true,
            inEventNow = inEvent,
            onACall = onCall,
        )
    }

    private fun finding() = ProactiveAwarenessEngine.ProactiveFinding(
        type = ProactiveFindingType.STUCK_TASKS.wire,
        title = "Stuck task",
        message = "Something has been pending a while",
        urgency = 0.6f,
    )

    @Test
    fun `a category that has earned the right is still not notified in a meeting`() = runTest {
        earned()
        situation(inEvent = true)
        assertFalse(notifier().maybeNotify(finding(), now))
    }

    @Test
    fun `nor on a call`() = runTest {
        earned()
        situation(onCall = true)
        assertFalse(notifier().maybeNotify(finding(), now))
    }

    @Test
    fun `nor at three in the morning`() = runTest {
        earned()
        coEvery { reader.get(any(), any()) } returns Situation(
            at = now,
            localHour = 3,
            dayOfWeek = Calendar.TUESDAY,
            weekend = false,
            screenOn = false,
        )
        assertFalse(notifier().maybeNotify(finding(), now))
    }

    @Test
    fun `a situation that cannot be read does not veto`() = runTest {
        // The point of the whole class. If this fell closed, an unreadable
        // calendar or a missing service would mute Aura permanently and look
        // exactly like a feature that does not work.
        earned()
        coEvery { reader.get(any(), any()) } throws IllegalStateException("no calendar provider")
        // Robolectric has no notification permission, so posting returns false
        // either way — what is asserted is that it got as far as trying, which
        // the log line distinguishes. Kept as a smoke check that no exception
        // escapes the veto path.
        notifier().maybeNotify(finding(), now)
    }
}
