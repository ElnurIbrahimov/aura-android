package com.aura.realtime

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Keeps a live voice session running while Aura is backgrounded.
 *
 * Without it, a call ends the moment the user locks the screen or switches
 * apps — which is most of when a hands-free assistant is worth having.
 *
 * Started only while the app is visible, because the user taps to call. That is
 * what satisfies API 34's background-start restriction: a foreground service of
 * type `microphone` cannot be started from the background, and there is no
 * workaround that is not a bug.
 */
@AndroidEntryPoint
class RealtimeVoiceService : Service() {

    @Inject lateinit var sessionHolder: RealtimeSessionHolder

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                // The kill switch from the notification. Ends the session
                // rather than only the service — stopping the service while a
                // socket stays open would keep billing per audio-minute with no
                // UI to show for it.
                sessionHolder.requestEnd("user ended the call from the notification")
                stopSelf()
                return START_NOT_STICKY
            }
        }

        startInForeground()
        return START_NOT_STICKY
    }

    private fun startInForeground() {
        createChannel()
        val notification = buildNotification()
        // The typed overload exists only on API 29+; below that the untyped one
        // is correct and the type is implicit. Same shape as
        // ScreenCaptureService.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(): Notification {
        val stopIntent = PendingIntent.getService(
            this,
            0,
            Intent(this, RealtimeVoiceService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Aura is listening")
            // Says the microphone is live, in words, because a voice session
            // that is not obviously running is the kind of thing users are
            // right to be uneasy about.
            .setContentText("Live voice call — the microphone is on")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "End call", stopIntent).build())
            .build()
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        runCatching {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Live voice", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Shown while a live voice call is running"
                    setShowBadge(false)
                },
            )
        }.onFailure { Log.w(TAG, "notification channel creation failed: ${it.message}", it) }
    }

    override fun onDestroy() {
        sessionHolder.requestEnd("voice service destroyed")
        super.onDestroy()
    }

    companion object {
        private const val TAG = "RealtimeVoiceService"
        private const val CHANNEL_ID = "aura_live_voice"
        private const val NOTIFICATION_ID = 4711
        const val ACTION_STOP = "com.aura.realtime.action.STOP"

        fun start(context: Context) {
            androidx.core.content.ContextCompat.startForegroundService(
                context,
                Intent(context, RealtimeVoiceService::class.java),
            )
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, RealtimeVoiceService::class.java))
        }
    }
}

/**
 * Lets the service end a session it does not own.
 *
 * The service knows when the user hit "End call"; the orchestrator owns the
 * socket. A Context-free holder between them keeps the service from having to
 * reach into session state, and is the same shape as `NotificationCaptureStore`
 * and `ScreenControlBridge`.
 */
@javax.inject.Singleton
class RealtimeSessionHolder @Inject constructor() {
    private val _endRequests = kotlinx.coroutines.flow.MutableSharedFlow<String>(
        replay = 0,
        extraBufferCapacity = 4,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
    )

    /** Reasons a session should end, emitted by the service. */
    val endRequests: kotlinx.coroutines.flow.Flow<String> = _endRequests

    fun requestEnd(reason: String) {
        _endRequests.tryEmit(reason)
    }
}
