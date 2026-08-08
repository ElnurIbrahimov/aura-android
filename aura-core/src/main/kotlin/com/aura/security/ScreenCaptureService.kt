package com.aura.security

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.aura.core.R
import dagger.hilt.android.AndroidEntryPoint
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject

/**
 * Short-lived foreground service that performs exactly one screen
 * capture and stops itself. targetSdk 35 requires that
 * [MediaProjectionManager.getMediaProjection] is called from an
 * already-foregrounded service of type `mediaProjection`, so the
 * sequence here is strict:
 *
 * 1. [startForeground] with FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
 *    (visible for the ~1-2 s the capture takes)
 * 2. getMediaProjection from the consent extras
 * 3. register [MediaProjection.Callback] — MUST happen before
 *    createVirtualDisplay on Android 14+
 * 4. ImageReader with an [ImageReader.OnImageAvailableListener] on a
 *    dedicated HandlerThread — the first frame arrives *async*, never
 *    synchronously — plus a 10 s watchdog so a frameless session
 *    fails instead of hanging
 * 5. row-stride-corrected bitmap → [ScreenCaptureHolder.onCaptureResult]
 * 6. idempotent teardown (release display, close reader, stop
 *    projection, quit thread, stopSelf)
 *
 * The consent Intent arrives via extras from [ScreenCaptureHolder];
 * on Android 14+ it is single-use, which is why the holder requests
 * fresh consent for every capture.
 */
@AndroidEntryPoint
class ScreenCaptureService : Service() {

    @Inject
    lateinit var holder: ScreenCaptureHolder

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var handlerThread: HandlerThread? = null

    /** Set once per capture — guards exactly-one delivery to the holder. */
    private val delivered = AtomicBoolean(false)

    /** Set once — guards the teardown path against double release. */
    private val tornDown = AtomicBoolean(false)

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            if (delivered.compareAndSet(false, true)) {
                holder.onCaptureFailed("Screen-capture session was stopped by the system.")
            }
            teardown()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        startInForeground()

        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
        @Suppress("DEPRECATION")
        val data: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            intent.getParcelableExtra(EXTRA_RESULT_DATA)
        }
        val width = intent.getIntExtra(EXTRA_WIDTH, 1080).coerceAtLeast(16)
        val height = intent.getIntExtra(EXTRA_HEIGHT, 1920).coerceAtLeast(16)
        if (data == null) {
            fail("Screen-capture consent data was missing.")
            return START_NOT_STICKY
        }

        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager
        val projection = try {
            manager?.getMediaProjection(resultCode, data)
        } catch (e: Exception) {
            null
        }
        if (projection == null) {
            fail("Could not start the projection — consent may have expired. Please try again.")
            return START_NOT_STICKY
        }
        this.projection = projection

        val thread = HandlerThread("aura-screen-capture").also { it.start() }
        handlerThread = thread
        val handler = Handler(thread.looper)

        // Android 14+ throws if createVirtualDisplay runs before a
        // callback is registered — register first, always.
        projection.registerCallback(projectionCallback, handler)

        val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        imageReader = reader
        reader.setOnImageAvailableListener({ r ->
            val image = try {
                r.acquireLatestImage()
            } catch (e: Exception) {
                null
            } ?: return@setOnImageAvailableListener
            val bitmap = try {
                image.toBitmap(width, height)
            } catch (e: Exception) {
                null
            } finally {
                image.close()
            }
            if (bitmap != null && delivered.compareAndSet(false, true)) {
                holder.onCaptureResult(bitmap)
                teardown()
            }
        }, handler)

        virtualDisplay = try {
            projection.createVirtualDisplay(
                "aura_capture",
                width,
                height,
                resources.displayMetrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface,
                null,
                handler,
            )
        } catch (e: Exception) {
            null
        }
        if (virtualDisplay == null) {
            fail("Could not create the capture display.")
            return START_NOT_STICKY
        }

        // Watchdog: the first frame is asynchronous; if it never
        // arrives, fail cleanly instead of leaking the session.
        handler.postDelayed({
            if (delivered.compareAndSet(false, true)) {
                holder.onCaptureFailed("No frame arrived within ${WATCHDOG_MS / 1000}s.")
                teardown()
            }
        }, WATCHDOG_MS)

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        teardown()
        super.onDestroy()
    }

    private fun startInForeground() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(): Notification {
        val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Screen capture",
            NotificationManager.IMPORTANCE_LOW,
        )
        mgr.createNotificationChannel(channel)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Aura")
            .setContentText("Capturing screen…")
            .setSmallIcon(R.drawable.ic_aura_notification)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun fail(reason: String) {
        if (delivered.compareAndSet(false, true)) {
            holder.onCaptureFailed(reason)
        }
        teardown()
    }

    /**
     * Idempotent teardown: release the virtual display, close the
     * reader, stop the projection, quit the handler thread, drop the
     * foreground notification, stop the service. Safe to call from
     * the frame path, the watchdog, the projection callback, and
     * onDestroy — only the first call does the work.
     */
    private fun teardown() {
        if (!tornDown.compareAndSet(false, true)) return
        // Best-effort: each step must run even if an earlier one throws
        // (double-release / already-stopped states are expected here).
        fun quietly(step: String, block: () -> Unit) {
            runCatching(block).onFailure {
                android.util.Log.w("ScreenCaptureService", "teardown $step failed: ${it.message}", it)
            }
        }
        quietly("display release") { virtualDisplay?.release() }
        virtualDisplay = null
        quietly("reader close") { imageReader?.close() }
        imageReader = null
        quietly("unregister callback") { projection?.unregisterCallback(projectionCallback) }
        quietly("projection stop") { projection?.stop() }
        projection = null
        quietly("thread quit") { handlerThread?.quitSafely() }
        handlerThread = null
        quietly("stop foreground") { stopForeground(STOP_FOREGROUND_REMOVE) }
        stopSelf()
    }

    companion object {
        const val CHANNEL_ID = "screen_capture"
        const val NOTIFICATION_ID = 1003
        const val WATCHDOG_MS = 10_000L

        const val EXTRA_RESULT_CODE = "com.aura.screen_capture.RESULT_CODE"
        const val EXTRA_RESULT_DATA = "com.aura.screen_capture.RESULT_DATA"
        const val EXTRA_WIDTH = "com.aura.screen_capture.WIDTH"
        const val EXTRA_HEIGHT = "com.aura.screen_capture.HEIGHT"

        /** Start a one-shot capture with the given consent grant. */
        fun start(context: Context, resultCode: Int, data: Intent, width: Int, height: Int) {
            val intent = Intent(context, ScreenCaptureService::class.java)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_RESULT_DATA, data)
                .putExtra(EXTRA_WIDTH, width)
                .putExtra(EXTRA_HEIGHT, height)
            ContextCompat.startForegroundService(context, intent)
        }
    }
}

/**
 * Convert an RGBA_8888 [Image] to a [Bitmap], correcting for the
 * plane's row stride. GPU-composited buffers routinely pad each row;
 * copying the buffer into a bitmap of exactly [width] would shear
 * every row after the first.
 */
private fun Image.toBitmap(width: Int, height: Int): Bitmap {
    val plane = planes[0]
    val buffer = plane.buffer
    val pixelStride = plane.pixelStride
    val rowStride = plane.rowStride
    val rowPadding = rowStride - pixelStride * width
    val paddedWidth = width + rowPadding / pixelStride
    val padded = Bitmap.createBitmap(paddedWidth, height, Bitmap.Config.ARGB_8888)
    padded.copyPixelsFromBuffer(buffer)
    return if (paddedWidth == width) padded else Bitmap.createBitmap(padded, 0, 0, width, height)
}
