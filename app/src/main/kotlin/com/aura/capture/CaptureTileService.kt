package com.aura.capture

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.TileService
import androidx.annotation.RequiresApi

/**
 * Capture from the quick-settings shade: one pull-down, one tap.
 *
 * The shortest path Aura has. It is reachable over the lock screen and from
 * inside any app, without leaving whatever you were doing to find an icon.
 *
 * `isSecure` is not consulted deliberately — a tile that refuses to work while
 * the phone is locked is a tile that fails at exactly the moment a thought
 * arrives. The platform handles the unlock itself: on API 34+
 * `startActivityAndCollapse(PendingIntent)` prompts for the keyguard when the
 * device is locked, and below that `startActivityAndCollapse(Intent)` does the
 * same. Nothing is shown or written before the user is through it.
 */
@RequiresApi(Build.VERSION_CODES.N)
class CaptureTileService : TileService() {

    // Both branches are required and lint can only see one of them.
    //
    // startActivityAndCollapse(PendingIntent) does not exist below API 34, and
    // startActivityAndCollapse(Intent) throws UnsupportedOperationException on
    // 34 and above. minSdk here is 26, so the version check is the whole point
    // — but the lint check flags the legacy call regardless of the guard, and
    // @Suppress("DEPRECATION") does not reach lint.
    @android.annotation.SuppressLint("StartActivityAndCollapseDeprecated")
    override fun onClick() {
        super.onClick()
        val capture = Intent(this, CaptureActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // The Intent overload throws on API 34+, and the replacement wants a
            // PendingIntent so the system can hold it across the unlock.
            startActivityAndCollapse(
                PendingIntent.getActivity(
                    this,
                    0,
                    capture,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(capture)
        }
    }
}
