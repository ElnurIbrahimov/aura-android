package com.aura.tools

import android.util.Log
import android.graphics.Bitmap
import android.util.Base64
import com.aura.agent.Tool
import com.aura.agent.ToolResult
import com.aura.agent.ToolRisk
import com.aura.security.ScreenCaptureHolder
import com.aura.providers.ToolDefinition
import com.aura.providers.ToolParameters
import com.aura.providers.ToolProperty
import kotlinx.coroutines.flow.first
import java.io.ByteArrayOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Capture the device screen and return it as base64 JPEG.
 * Requires the user to grant screen-capture permission first.
 * Risk: PRIVACY.
 */
@Singleton
class CaptureScreenTool @Inject constructor(
    private val screenCaptureHolder: ScreenCaptureHolder,
    /**
     * Optional so the tool keeps working when screen control is not built into
     * a given configuration; null simply means the MediaProjection path.
     */
    private val screenControlBridge: com.aura.a11y.ScreenControlBridge? = null,
    /**
     * Gates the accessibility screenshot path on the screen-control master
     * switch. Optional for the same reason as [screenControlBridge]; absent
     * means the accessibility path is unavailable, not that it is ungated.
     */
    private val userPreferences: com.aura.data.UserPreferences? = null,
) {
    /**
     * Whether the silent accessibility screenshot path may be used.
     *
     * The accessibility route takes a screenshot with **no consent dialog**,
     * while Aura is backgrounded — which is the whole point of it, and also why
     * it has to answer to the master switch. It did not: the only condition was
     * `bridge.connected`, i.e. whether the OS accessibility service was on. A
     * user who enabled the service for screen control and then turned screen
     * control **off** in Settings still got silent, dialog-free screenshots,
     * and this tool's own description told them each capture "shows a one-time
     * system consent dialog... the user must confirm it".
     *
     * Fails closed, matching `ScreenActTool.isEnabled`. With the switch off the
     * tool falls back to MediaProjection, which does show the dialog the
     * description promises.
     */
    private suspend fun accessibilityCaptureAllowed(): Boolean =
        userPreferences?.let {
            runCatching { it.screenControlEnabled.first() }
                .onFailure { e -> Log.w("CaptureScreen", "screenControlEnabled read failed; denying", e) }
                .getOrDefault(false)
        } ?: false

    fun definition() = ToolDefinition(
        name = "capture_screen",
        description = "Capture the device screen and return it as a base64 JPEG. Shows a one-time system consent dialog for each capture, unless screen control is enabled — with it on, the capture is silent and needs no dialog.",
        parameters = ToolParameters(
            properties = mapOf(
                "width" to ToolProperty(type = "integer", description = "Capture width (default 1080)"),
                "height" to ToolProperty(type = "integer", description = "Capture height (default 1920)"),
                "quality" to ToolProperty(type = "integer", description = "JPEG quality 1-100 (default 80)"),
            ),
            required = emptyList(),
        ),
    )

    val tool = Tool(
        name = "capture_screen",
        description = definition().description,
        risk = ToolRisk.PRIVACY,
        parameters = definition().parameters,
        execute = { call, _ ->
            val width = (call.arguments["width"] as? Int) ?: 1080
            val height = (call.arguments["height"] as? Int) ?: 1920
            val quality = ((call.arguments["quality"] as? Int) ?: 80).coerceIn(1, 100)

            // Refuse while a password field is on screen.
            //
            // `ScreenControlGuard` already refuses to *act* on a screen with a
            // visible password field, and `StepInference` already refuses to
            // *record* one. This tool shipped the pixels of that same screen to
            // a third-party vision model. The text path is carefully redacted
            // — `UiTraversal.redact` treats the password flag as authoritative
            // — and none of that reaches a JPEG, because a regex cannot edit an
            // image. So the one surface where the secret is actually legible
            // was the one with no guard on it.
            //
            // A fresh snapshot rather than `currentSnapshot()`: the cached one
            // can be from a different screen entirely, which would give both
            // false negatives and false positives on a check whose whole job is
            // to be right about the screen in front of the user.
            //
            // This can only be checked when the accessibility service is
            // connected. With it off there is no view tree to ask, so a
            // MediaProjection capture proceeds unguarded — that path shows the
            // user a system consent dialog for every capture, which is the
            // control that covers it, and it is stated here rather than left to
            // be discovered.
            val passwordVisible = screenControlBridge?.let { bridge ->
                if (!bridge.connected.value) {
                    null
                } else {
                    runCatching { bridge.snapshot().hasPasswordField }
                        .onFailure { Log.w("CaptureScreen", "password-field check failed", it) }
                        .getOrNull()
                }
            }
            if (passwordVisible == true) {
                return@Tool ToolResult.Error(
                    "A password field is on screen. Aura will not capture it. " +
                        "Close or leave that screen and ask again.",
                    "password_field_visible",
                )
            }

            // Accessibility screenshot FIRST when the service is connected.
            //
            // It needs no consent dialog and works while Aura is backgrounded,
            // where MediaProjection needs an attached Activity and a fresh
            // consent prompt for EVERY capture (tokens are single-use on
            // Android 14+). That prompt is why capture has always been
            // unpleasant to use and impossible to loop on.
            //
            // Null is a fallback signal, not a failure: below API 30, with the
            // service off, or on a FLAG_SECURE window there is legitimately
            // nothing to return, and MediaProjection may still succeed.
            val viaA11y = screenControlBridge?.let { bridge ->
                if (bridge.connected.value && accessibilityCaptureAllowed()) {
                    runCatching { bridge.screenshot(quality) }
                        .onFailure { Log.w("CaptureScreen", "a11y screenshot failed; falling back", it) }
                        .getOrNull()
                } else {
                    null
                }
            }
            if (viaA11y != null) {
                return@Tool ToolResult.Ok(
                    "data:image/jpeg;base64," +
                        android.util.Base64.encodeToString(viaA11y, android.util.Base64.NO_WRAP),
                )
            }

            // One-shot suspend flow: consent dialog → foreground
            // capture service → first frame. Fresh consent every
            // time (tokens are single-use on Android 14+).
            try {
                val bitmap = screenCaptureHolder.captureOnce(width, height)
                ToolResult.Ok("data:image/jpeg;base64,${bitmapToBase64(bitmap, quality)}")
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                ToolResult.Error(
                    "Screen capture failed: ${e.message ?: e.javaClass.simpleName}",
                    "capture_failed",
                )
            }
        },
        category = "device",
    )

    private fun bitmapToBase64(bitmap: Bitmap, quality: Int): String {
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, stream)
        return Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
    }
}
