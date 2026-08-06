package com.aura.widget

import android.content.BroadcastReceiver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

/**
 * Run suspending work from a widget [BroadcastReceiver] without losing
 * it on process death: `goAsync()` keeps the broadcast (and therefore
 * the process) alive until [android.content.BroadcastReceiver.PendingResult.finish]
 * is called, and finish() runs in a `finally` AFTER the work completes.
 *
 * The previous pattern called `pendingResult.finish()` before the
 * coroutine ran (or used a throwaway `CoroutineScope`), which let the
 * system kill the process mid-refresh and silently drop the update.
 *
 * GlobalScope is deliberate here (the documented Android pattern for
 * goAsync): the work must outlive any component lifecycle, and the
 * pending result — not a Job — is what bounds it. The system enforces
 * a ~10s ceiling on goAsync work; widget refreshes are well under it.
 */
@OptIn(DelicateCoroutinesApi::class)
internal fun BroadcastReceiver.goAsync(
    block: suspend CoroutineScope.() -> Unit,
) {
    val pendingResult = goAsync()
    GlobalScope.launch(Dispatchers.IO) {
        try {
            block()
        } finally {
            pendingResult.finish()
        }
    }
}
