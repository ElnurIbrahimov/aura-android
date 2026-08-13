package com.aura.situation

import android.content.Context
import android.media.AudioManager
import androidx.test.core.app.ApplicationProvider
import com.aura.agent.ConversationStore
import com.aura.notifications.CapturedNotification
import com.aura.notifications.NotificationCaptureStore
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A message from someone is not a call with them.
 *
 * `onACall` used to be derived by scanning the notification snapshot for package
 * names containing "whatsapp", "telegram", "zoom" and so on. Those are posted
 * notifications, so a single unread message made `onACall` true, which made
 * [Situation.interruptible] false, which suppressed every proactive notification
 * and held every daemon finding — while telling the user "you're on a call".
 *
 * The gates that bug closed had just been opened deliberately in `66bca9bd`, and
 * nothing disagreed with it: [SituationTest] pins what `onACall = true` *means*
 * and takes the value as given. Nothing tested where the value came from. This
 * does, which is why the first case here is the one that used to fail.
 */
@RunWith(RobolectricTestRunner::class)
class SituationReaderCallDetectionTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private val audio: AudioManager
        get() = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private fun reader(notifications: NotificationCaptureStore? = null) = SituationReader(
        context = context,
        conversationStore = mockk<ConversationStore>(relaxed = true),
        notificationCaptureStore = notifications,
    )

    private fun messagingNotifications() = NotificationCaptureStore().apply {
        setConnected(true)
        replaceAll(
            listOf(
                CapturedNotification(
                    key = "0|com.whatsapp|1|null|10",
                    packageName = "com.whatsapp",
                    title = "Sam",
                    text = "are you around later?",
                    postedAt = 1_700_000_000_000L,
                ),
                CapturedNotification(
                    key = "0|org.telegram.messenger|2|null|10",
                    packageName = "org.telegram.messenger",
                    title = "Group",
                    text = "see you at 6",
                    postedAt = 1_700_000_000_001L,
                ),
            ),
        )
    }

    @Test
    fun `unread messages from calling apps are not a call`() = runTest {
        audio.mode = AudioManager.MODE_NORMAL
        val situation = reader(messagingNotifications()).get(ttlMs = 0L)

        assertEquals(false, situation.onACall, "two unread chat notifications were read as a live call")
        assertTrue(situation.interruptible, "a suggestion was suppressed because a message was unread")
        assertEquals(null, situation.blockedBecause)
    }

    @Test
    fun `a VOIP call is a call`() = runTest {
        audio.mode = AudioManager.MODE_IN_COMMUNICATION
        val situation = reader().get(ttlMs = 0L)

        assertEquals(true, situation.onACall)
        assertFalse(situation.interruptible)
        assertEquals("you're on a call", situation.blockedBecause)
    }

    @Test
    fun `a cellular call is a call`() = runTest {
        audio.mode = AudioManager.MODE_IN_CALL
        val situation = reader().get(ttlMs = 0L)

        assertEquals(true, situation.onACall)
        assertFalse(situation.interruptible)
    }

    /**
     * The signal has to survive notification access being off, which is its
     * default. The old derivation could not: with no listener there was no
     * snapshot, so `onACall` was null whether or not a call was up.
     */
    @Test
    fun `a call is detected with no notification access at all`() = runTest {
        audio.mode = AudioManager.MODE_IN_COMMUNICATION
        val situation = reader(notifications = null).get(ttlMs = 0L)

        assertEquals(true, situation.onACall)
    }

    @Test
    fun `the notification count is still read`() = runTest {
        audio.mode = AudioManager.MODE_NORMAL
        val situation = reader(messagingNotifications()).get(ttlMs = 0L)

        // Dropping CALL_PACKAGES must not drop the store: how much is going on
        // is a separate signal from whether a call is up.
        assertEquals(2, situation.activeNotifications)
    }
}
