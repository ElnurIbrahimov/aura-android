package com.aura.situation

import android.content.Context
import android.net.ConnectivityManager
import android.media.AudioManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.PowerManager
import android.util.Log
import com.aura.agent.ConversationStore
import com.aura.emotion.EmotionEngine
import com.aura.notifications.NotificationCaptureStore
import com.aura.tools.CalendarReadTool
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Assembles [Situation] from sources Aura is already allowed to read.
 *
 * Shaped after `consciousness/DriveSignals.kt` — same mutex, same
 * re-check-under-lock, same per-source best-effort so one dead source cannot
 * starve the rest. The TTL is much shorter than DriveSignals' five minutes:
 * this answers "is now a bad moment", and a five-minute-old answer to that is
 * a wrong answer. Sixty seconds bounds the calendar query and the usage-stats
 * read to once a minute, which is the only real cost here.
 *
 * Every source is wrapped individually and returns null on failure, because
 * `Situation` treats null as "could not tell" rather than "no" — see its
 * `interruptible`, which defaults to permitting rather than blocking.
 */
@Singleton
class SituationReader @Inject constructor(
    @ApplicationContext private val context: Context,
    private val conversationStore: ConversationStore,
    private val calendarReadTool: CalendarReadTool? = null,
    private val emotionEngine: EmotionEngine? = null,
    private val notificationCaptureStore: NotificationCaptureStore? = null,
    private val foregroundAppReader: ForegroundAppReader? = null,
) {
    @Volatile
    private var cache: Situation? = null
    private val mutex = Mutex()

    suspend fun get(now: Long = System.currentTimeMillis(), ttlMs: Long = DEFAULT_TTL_MS): Situation {
        cache?.takeIf { now - it.at < ttlMs }?.let { return it }
        return mutex.withLock {
            cache?.takeIf { now - it.at < ttlMs }?.let { return it }
            read(now).also { cache = it }
        }
    }

    private suspend fun read(now: Long): Situation {
        val base = Situation.clockOnly(now)
        val calendar = orNull { calendarReadTool?.window(now) }
        return base.copy(
            screenOn = orNull {
                (context.getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive
            },
            charging = orNull {
                val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
                bm.isCharging
            },
            meteredNetwork = orNull {
                val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                val caps = cm.getNetworkCapabilities(cm.activeNetwork)
                caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)?.not()
            },
            inEventNow = calendar?.inEventNow,
            minutesToNextEvent = calendar?.minutesToNext,
            minutesSinceLastMessage = orNull {
                conversationStore.mostRecent()?.turns?.lastOrNull()?.timestamp
                    ?.let { (now - it) / 60_000L }
                    ?.coerceAtLeast(0L)
            },
            tension = orNull { emotionEngine?.snapshot()?.tension },
            activeNotifications = orNull { notificationCaptureStore?.snapshot(MAX_NOTIFICATIONS)?.size },
            // The audio mode, not a list of app names.
            //
            // This used to scan the notification snapshot for packages whose
            // name contained "whatsapp", "telegram", "zoom" and so on. Those
            // are *posted notifications*, not calls: one unread WhatsApp
            // message set onACall, which made `interruptible` false, which
            // suppressed every proactive notification and held every daemon
            // finding — telling the user "you're on a call" when they were not.
            // It also only worked at all when notification capture was granted,
            // which is off by default, so on a stock setup this was permanently
            // null in one direction and permanently wrong in the other.
            //
            // MODE_IN_CALL is the cellular case, MODE_IN_COMMUNICATION the VOIP
            // one. Every dialer and every calling app sets one of them, including
            // the ones no list would think to name, and reading it needs no
            // permission and no listener.
            //
            // Aura's own live voice call sets MODE_IN_COMMUNICATION too
            // (`AndroidAudioIo.applyCallAudioMode`), so Aura reads itself as
            // on a call and stops interrupting a conversation it is having.
            // That is deliberate, not a side effect.
            onACall = orNull {
                val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                val mode = audio.mode
                mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_IN_COMMUNICATION
            },
            foregroundApp = orNull { foregroundAppReader?.current(now) },
        )
    }

    /**
     * One source failing is normal — a revoked permission, a provider that is
     * not there, a service the OEM removed. Null is a first-class answer here,
     * so a failure and an absence are deliberately indistinguishable.
     */
    private suspend inline fun <T> orNull(block: suspend () -> T?): T? = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (t: Throwable) {
        Log.w(TAG, "situation source failed: ${t.message}", t)
        null
    }

    private companion object {
        const val TAG = "SituationReader"

        /** Short on purpose — see the class KDoc. */
        const val DEFAULT_TTL_MS = 60_000L

        const val MAX_NOTIFICATIONS = 20
    }
}
