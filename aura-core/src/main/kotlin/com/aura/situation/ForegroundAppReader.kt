package com.aura.situation

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Process
import android.util.Log
import com.aura.data.UserPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Which app is in the foreground, when the user has said Aura may look.
 *
 * The strongest available signal for "are you in the middle of something", and
 * the most invasive thing Aura reads — which is why it sits behind two
 * independent conditions rather than one. The switch is Aura's own
 * (`appAwarenessEnabled`); the grant is Android's special usage-access screen.
 * Either being off produces the same null, so no caller has to know which.
 *
 * The result is **never written to Room**, matching `NotificationCaptureStore`:
 * knowing what someone is doing right now has a legitimate use, and keeping a
 * history of it does not.
 */
@Singleton
class ForegroundAppReader @Inject constructor(
    @ApplicationContext private val context: Context,
    private val userPreferences: UserPreferences,
) {

    /** True when Android's usage-access grant is in place. */
    fun granted(): Boolean = runCatching { usageOpMode() == AppOpsManager.MODE_ALLOWED }
        .onFailure { Log.w(TAG, "usage-access check failed", it) }
        .getOrDefault(false)

    private fun usageOpMode(): Int {
        val ops = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        // unsafeCheckOpNoThrow is API 29; checkOpNoThrow is the pre-Q spelling
        // of the same question and minSdk here is 26.
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        } else {
            @Suppress("DEPRECATION")
            ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        }
    }

    /**
     * The package most recently moved to the foreground, or null.
     *
     * Reads events rather than aggregated stats: `queryUsageStats` returns
     * totals for a period, which answers "what have you used today" — a
     * different and much more revealing question than "what are you looking at
     * now". A short window keeps it to the latter.
     */
    suspend fun current(now: Long = System.currentTimeMillis()): String? {
        if (!runCatching { userPreferences.appAwarenessEnabled.first() }.getOrDefault(false)) return null
        if (!granted()) return null
        return runCatching {
            val usage = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val events = usage.queryEvents(now - WINDOW_MS, now)
            val event = UsageEvents.Event()
            var latest: String? = null
            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                if (event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                    latest = event.packageName
                }
            }
            // Aura being in the foreground is not information about the user's
            // situation — it is just the fact that they are talking to Aura.
            latest?.takeIf { it != context.packageName }
        }.onFailure { Log.w(TAG, "foreground read failed", it) }.getOrNull()
    }

    private companion object {
        const val TAG = "ForegroundAppReader"

        /** Long enough to catch the current app, short enough not to be a history. */
        const val WINDOW_MS = 5L * 60 * 1000
    }
}
