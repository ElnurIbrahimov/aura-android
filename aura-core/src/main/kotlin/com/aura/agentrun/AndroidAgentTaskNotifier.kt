package com.aura.agentrun

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Tells the user a background task finished.
 *
 * Deliberately not routed through `ProactiveNotifier`. That exists to decide whether Aura
 * has *earned the right* to interrupt with something the user did not ask for, and runs an
 * interruption ledger and a situation check to answer it. A task the user started
 * themselves is the opposite case: they asked for it, they closed the app expecting it to
 * carry on, and the answer arriving is the thing they wanted. Passing it through the earn
 * machinery would let a global cap silently swallow the result of a deliberate request.
 *
 * Its own channel, so it can be silenced independently of everything else — a task that
 * takes two minutes and a proactive brief at 8am do not want the same setting.
 */
@Singleton
class AndroidAgentTaskNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
) : AgentTaskNotifier {

    override suspend fun onFinished(runId: String, summary: String, succeeded: Boolean) {
        // POST_NOTIFICATIONS is a runtime permission from API 33. Without it the post is a
        // silent no-op at the platform level, so check rather than pretend it worked.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            Log.i(TAG, "task $runId finished but notifications are not permitted")
            return
        }

        runCatching {
            ensureChannel()
            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_popup_reminder)
                .setContentTitle(if (succeeded) "Task finished" else "Task failed")
                .setContentText(summary.lineSequence().firstOrNull()?.take(TITLE_CHARS).orEmpty())
                // The whole answer, not the first line of it. A background task's result is
                // usually the only thing the user wanted, and making them open the app to
                // read one paragraph defeats running it in the background.
                .setStyle(NotificationCompat.BigTextStyle().bigText(summary.take(BODY_CHARS)))
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .build()
            // Keyed on the run id, so two tasks finishing do not overwrite each other.
            NotificationManagerCompat.from(context).notify(runId.hashCode(), notification)
        }.onFailure { Log.w(TAG, "could not notify for $runId: ${it.message}", it) }
    }

    private fun ensureChannel() {
        val mgr = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        mgr.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_DEFAULT),
        )
    }

    private companion object {
        const val CHANNEL_ID = "aura_tasks"
        const val CHANNEL_NAME = "Background tasks"
        const val TITLE_CHARS = 80
        const val BODY_CHARS = 4_000
        const val TAG = "AgentTaskNotifier"
    }
}
