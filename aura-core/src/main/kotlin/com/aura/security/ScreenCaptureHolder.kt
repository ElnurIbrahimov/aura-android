package com.aura.security

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.projection.MediaProjectionManager
import androidx.activity.result.ActivityResultLauncher
import androidx.fragment.app.FragmentActivity
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.lang.ref.WeakReference
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Bridges the [com.aura.tools.CaptureScreenTool] to the platform's
 * MediaProjection machinery. The activity registers the consent
 * launcher and forwards its result here ([attach] / [detach] /
 * [onPermissionResult] — same signatures as before, so the
 * MainActivity wiring is unchanged); [ScreenCaptureService] hands
 * the captured frame back via [onCaptureResult] / [onCaptureFailed].
 *
 * Every capture is a fresh two-step rendezvous on per-capture
 * [CompletableDeferred]s:
 *
 * 1. consent — launch the system dialog, await the activity result
 *    (60 s timeout). Consent Intents are single-use on Android 14+,
 *    so nothing is ever stored for reuse.
 * 2. capture — start the foreground [ScreenCaptureService] with the
 *    grant, await the first frame (15 s timeout; the service's own
 *    10 s watchdog fires first on a frameless session).
 *
 * A [Mutex] serializes captures — MediaProjection sessions are
 * strictly one-at-a-time.
 */
@Singleton
class ScreenCaptureHolder @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    internal data class ConsentGrant(val resultCode: Int, val data: Intent)

    private var activityRef: WeakReference<FragmentActivity>? = null
    private var launcher: ActivityResultLauncher<Intent>? = null

    val activity: FragmentActivity?
        get() = activityRef?.get()

    private val captureMutex = Mutex()

    @Volatile
    internal var consentDeferred: CompletableDeferred<ConsentGrant>? = null

    @Volatile
    internal var captureDeferred: CompletableDeferred<Bitmap>? = null

    fun attach(activity: FragmentActivity, launcher: ActivityResultLauncher<Intent>) {
        activityRef = WeakReference(activity)
        this.launcher = launcher
    }

    fun detach() {
        activityRef = null
        launcher = null
        // A pending consent survives a configuration change — the
        // recreated activity re-attaches and its launcher callback
        // still lands in onPermissionResult.
    }

    /** Activity-result callback — resolves the pending consent, if any. */
    fun onPermissionResult(resultCode: Int, data: Intent?) {
        val pending = consentDeferred ?: return
        if (resultCode == Activity.RESULT_OK && data != null) {
            pending.complete(ConsentGrant(resultCode, data))
        } else {
            pending.completeExceptionally(
                SecurityException("Screen-capture permission was denied."),
            )
        }
    }

    /** Called by [ScreenCaptureService] when the first frame lands. */
    fun onCaptureResult(bitmap: Bitmap) {
        captureDeferred?.complete(bitmap)
    }

    /** Called by [ScreenCaptureService] on any failure path. */
    fun onCaptureFailed(reason: String) {
        captureDeferred?.completeExceptionally(IllegalStateException(reason))
    }

    /**
     * One-shot capture: fresh consent → foreground service → first
     * frame. Throws with a user-explainable message on denial,
     * timeout, or when the app isn't foregrounded (no attached
     * activity means no way to show the consent dialog).
     */
    suspend fun captureOnce(width: Int = 1080, height: Int = 1920): Bitmap =
        captureMutex.withLock {
            val activity = activityRef?.get()
                ?: throw IllegalStateException(FOREGROUND_REQUIRED_MESSAGE)
            val launcher = launcher
                ?: throw IllegalStateException(FOREGROUND_REQUIRED_MESSAGE)
            val manager = activity
                .getSystemService(Context.MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager
                ?: throw IllegalStateException("Screen capture is unavailable on this device.")

            val grant = awaitConsent {
                withContext(Dispatchers.Main) {
                    launcher.launch(manager.createScreenCaptureIntent())
                }
            }
            awaitCapture {
                ScreenCaptureService.start(context, grant.resultCode, grant.data, width, height)
            }
        }

    /**
     * Set up the per-capture consent deferred, run [launch] (which
     * shows the system dialog), and await the grant. Internal seam:
     * unit tests pass a no-op [launch] and drive the deferred via
     * [onPermissionResult].
     */
    internal suspend fun awaitConsent(launch: suspend () -> Unit): ConsentGrant {
        val deferred = CompletableDeferred<ConsentGrant>()
        consentDeferred = deferred
        return try {
            launch()
            withTimeout(CONSENT_TIMEOUT_MS) { deferred.await() }
        } catch (e: TimeoutCancellationException) {
            throw IllegalStateException("Timed out waiting for screen-capture consent.")
        } finally {
            consentDeferred = null
        }
    }

    /**
     * Set up the per-capture frame deferred, run [start] (which
     * starts the capture service), and await the bitmap. Internal
     * seam mirroring [awaitConsent] for tests.
     */
    internal suspend fun awaitCapture(start: suspend () -> Unit): Bitmap {
        val deferred = CompletableDeferred<Bitmap>()
        captureDeferred = deferred
        return try {
            start()
            withTimeout(CAPTURE_TIMEOUT_MS) { deferred.await() }
        } catch (e: TimeoutCancellationException) {
            throw IllegalStateException("Timed out waiting for the captured frame.")
        } finally {
            captureDeferred = null
        }
    }

    companion object {
        const val CONSENT_TIMEOUT_MS = 60_000L
        const val CAPTURE_TIMEOUT_MS = 15_000L
        const val FOREGROUND_REQUIRED_MESSAGE =
            "Aura must be open in the foreground to capture the screen. " +
                "Open the app and try again."
    }
}
