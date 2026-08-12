package com.aura.proactive

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.aura.data.UserPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Sends the notification a category has earned — and, far more often, doesn't.
 *
 * Reached only after the in-app record has already been written, so the
 * suggestion always exists somewhere the user can find it and the interruption
 * is the conditional extra. That ordering is what "everything starts in-app and
 * silent" means mechanically.
 */
@Singleton
class ProactiveNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
    private val ledger: InterruptionLedger,
    private val userPreferences: UserPreferences,
    private val situationReader: com.aura.situation.SituationReader? = null,
) {

    /**
     * @return true when a notification was actually posted, so the caller can
     *   record the surface as `notification` rather than `card`.
     */
    suspend fun maybeNotify(
        finding: ProactiveAwarenessEngine.ProactiveFinding,
        now: Long = System.currentTimeMillis(),
    ): Boolean {
        val type = ProactiveFindingType.from(finding.type) ?: return false

        val policy = runCatching {
            val stored = userPreferences.interruptionPolicies.first()[type.wire]
            stored?.let { runCatching { InterruptionPolicy.valueOf(it) }.getOrNull() }
                ?: InterruptionPolicy.EARNED
        }.onFailure { Log.w(TAG, "policy read failed: ${it.message}", it) }
            .getOrDefault(InterruptionPolicy.EARNED)

        val verdict = ledger.verdict(type, policy, now)
        if (!verdict.mayInterrupt) return false

        // Per-category earning must not add up to eight interruptions.
        if (!ledger.withinGlobalCaps(now)) {
            Log.i(TAG, "suppressed ${type.wire}: global cap reached")
            return false
        }

        // The ledger says this category has earned the right to interrupt, from
        // evidence about what has actually worked. It says nothing about right
        // now — it cannot tell a Tuesday 3pm at a desk from a Tuesday 3pm in a
        // meeting. This is the veto for the second question, and only the
        // second: an unreadable situation permits, because falling back to the
        // ledger's judgement is right and falling silent is not.
        //
        // An explicit Always is not overridden. That choice is about content,
        // not timing, and someone who asked to always hear about deadlines did
        // not mean "except during meetings", which is when deadlines matter.
        if (policy != InterruptionPolicy.ALWAYS) {
            val situation = runCatching { situationReader?.get(now) }
                .onFailure { Log.w(TAG, "situation read failed; not vetoing", it) }
                .getOrNull()
            if (situation != null && !situation.interruptible) {
                Log.i(TAG, "suppressed ${type.wire}: ${situation.blockedBecause}")
                return false
            }
        }

        return post(type, finding)
    }

    private fun post(type: ProactiveFindingType, finding: ProactiveAwarenessEngine.ProactiveFinding): Boolean =
        runCatching {
            val channels = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            channels.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "Suggestions that have earned the right to reach you"
                },
            )

            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(finding.title)
                .setContentText(finding.message)
                .setStyle(NotificationCompat.BigTextStyle().bigText(finding.message))
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setAutoCancel(true)
                .build()

            // Android 13+ can refuse this outright. Treated as "not notified"
            // rather than as an error: the suggestion is already recorded
            // in-app, and the caller uses the return value to decide whether
            // this counts as a notification surface for the ledger. Claiming
            // an interruption that never reached the user would let a category
            // be judged on evidence that does not exist.
            val granted = android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU ||
                androidx.core.content.ContextCompat.checkSelfPermission(
                    context,
                    android.Manifest.permission.POST_NOTIFICATIONS,
                ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            if (!granted || !NotificationManagerCompat.from(context).areNotificationsEnabled()) {
                Log.i(TAG, "cannot notify; ${type.wire} stays in-app")
                return@runCatching false
            }
            // Platform manager rather than the compat wrapper, matching
            // NotificationsTool: the permission is checked immediately above,
            // and lint's dataflow cannot follow that check across the compat
            // call inside this lambda.
            channels.notify(notificationId(type), notification)
            true
        }.onFailure { Log.w(TAG, "posting ${type.wire} failed: ${it.message}", it) }
            .getOrDefault(false)

    companion object {
        private const val TAG = "ProactiveNotifier"

        const val CHANNEL_ID = "aura_proactive"
        const val CHANNEL_NAME = "Aura suggestions"

        /**
         * A stable id per category, so a second suggestion of the same kind
         * replaces the first rather than stacking.
         *
         * 1100–1107 sits above the two fixed ids (1001 morning brief, 1002
         * calendar) and below the ad-hoc counter's floor of 2000, so it is
         * immune to that counter walking upward. Note `NotificationsTool`'s
         * KDoc claims reminders occupy 1000–1999; they actually use
         * 10 000–99 999, per `ReminderScheduler`.
         */
        fun notificationId(type: ProactiveFindingType): Int = 1100 + type.ordinal
    }
}
