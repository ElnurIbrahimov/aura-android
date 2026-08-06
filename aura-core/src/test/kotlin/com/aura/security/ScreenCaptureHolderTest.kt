package com.aura.security

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * JVM tests for [ScreenCaptureHolder]'s per-capture rendezvous:
 * the consent and capture [kotlinx.coroutines.CompletableDeferred]s,
 * their timeouts (runTest virtual time), and the completion paths
 * driven by [ScreenCaptureHolder.onPermissionResult] /
 * [ScreenCaptureHolder.onCaptureResult] / [ScreenCaptureHolder.onCaptureFailed].
 *
 * The Android-facing composition ([ScreenCaptureHolder.captureOnce] →
 * consent dialog → foreground service → frame) is device-test
 * territory; these tests exercise the state machine underneath via
 * the internal awaitConsent/awaitCapture seams with no-op launch
 * lambdas.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ScreenCaptureHolderTest {

    private fun holder() = ScreenCaptureHolder(mockk<Context>(relaxed = true))

    // ---- consent ----

    @Test
    fun `consent granted completes the pending deferred with the grant`() = runTest {
        val h = holder()
        val data = mockk<Intent>()
        val result = async { runCatching { h.awaitConsent {} } }
        runCurrent() // deferred is now registered
        h.onPermissionResult(Activity.RESULT_OK, data)
        runCurrent()
        val grant = result.await().getOrThrow()
        assertEquals(Activity.RESULT_OK, grant.resultCode)
        assertSame(data, grant.data)
        assertNull(h.consentDeferred, "consent deferred must be cleared after completion")
    }

    @Test
    fun `consent denial fails the capture with a SecurityException`() = runTest {
        val h = holder()
        val result = async { runCatching { h.awaitConsent {} } }
        runCurrent()
        h.onPermissionResult(Activity.RESULT_CANCELED, null)
        runCurrent()
        assertTrue(result.await().exceptionOrNull() is SecurityException)
        assertNull(h.consentDeferred)
    }

    @Test
    fun `consent times out after 60s of silence`() = runTest {
        val h = holder()
        val result = async { runCatching { h.awaitConsent {} } }
        runCurrent()
        advanceTimeBy(ScreenCaptureHolder.CONSENT_TIMEOUT_MS + 1)
        runCurrent()
        val error = result.await().exceptionOrNull()
        assertTrue(
            error is IllegalStateException && error.message.orEmpty().contains("Timed out"),
            "expected consent timeout, got $error",
        )
        assertNull(h.consentDeferred)
    }

    @Test
    fun `permission result before the timeout wins over the timeout`() = runTest {
        val h = holder()
        val result = async { runCatching { h.awaitConsent {} } }
        runCurrent()
        advanceTimeBy(ScreenCaptureHolder.CONSENT_TIMEOUT_MS - 1)
        h.onPermissionResult(Activity.RESULT_OK, mockk<Intent>())
        runCurrent()
        assertTrue(result.await().isSuccess)
    }

    @Test
    fun `permission result with no pending consent is a no-op`() {
        val h = holder()
        // Must not throw even though nothing is awaiting.
        h.onPermissionResult(Activity.RESULT_OK, mockk<Intent>())
        h.onPermissionResult(Activity.RESULT_CANCELED, null)
        assertNull(h.consentDeferred)
    }

    // ---- capture ----

    @Test
    fun `capture completes with the bitmap delivered by the service`() = runTest {
        val h = holder()
        val bitmap = mockk<Bitmap>()
        val result = async { runCatching { h.awaitCapture {} } }
        runCurrent()
        h.onCaptureResult(bitmap)
        runCurrent()
        assertSame(bitmap, result.await().getOrThrow())
        assertNull(h.captureDeferred, "capture deferred must be cleared after completion")
    }

    @Test
    fun `capture fails when the service reports an error`() = runTest {
        val h = holder()
        val result = async { runCatching { h.awaitCapture {} } }
        runCurrent()
        h.onCaptureFailed("No frame arrived within 10s.")
        runCurrent()
        val error = result.await().exceptionOrNull()
        assertTrue(
            error is IllegalStateException && error.message.orEmpty().contains("No frame"),
            "expected service failure, got $error",
        )
        assertNull(h.captureDeferred)
    }

    @Test
    fun `capture times out after 15s of silence`() = runTest {
        val h = holder()
        val result = async { runCatching { h.awaitCapture {} } }
        runCurrent()
        advanceTimeBy(ScreenCaptureHolder.CAPTURE_TIMEOUT_MS + 1)
        runCurrent()
        val error = result.await().exceptionOrNull()
        assertTrue(
            error is IllegalStateException && error.message.orEmpty().contains("Timed out"),
            "expected capture timeout, got $error",
        )
        assertNull(h.captureDeferred)
    }

    @Test
    fun `service callbacks with no pending capture are no-ops`() {
        val h = holder()
        h.onCaptureResult(mockk<Bitmap>())
        h.onCaptureFailed("late failure")
        assertNull(h.captureDeferred)
    }

    @Test
    fun `each capture uses a fresh consent deferred — nothing is reused`() = runTest {
        val h = holder()

        // First capture: grant.
        val first = async { runCatching { h.awaitConsent {} } }
        runCurrent()
        val firstDeferred = h.consentDeferred
        h.onPermissionResult(Activity.RESULT_OK, mockk<Intent>())
        runCurrent()
        assertTrue(first.await().isSuccess)

        // Second capture: a brand-new deferred; the old grant is gone.
        val second = async { runCatching { h.awaitConsent {} } }
        runCurrent()
        assertFalse(firstDeferred === h.consentDeferred, "consent deferred must be per-capture")
        h.onPermissionResult(Activity.RESULT_CANCELED, null)
        runCurrent()
        assertTrue(second.await().exceptionOrNull() is SecurityException)
    }
}
