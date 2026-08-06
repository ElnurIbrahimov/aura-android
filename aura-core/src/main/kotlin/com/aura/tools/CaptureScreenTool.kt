package com.aura.tools

import android.graphics.Bitmap
import android.util.Base64
import com.aura.agent.Tool
import com.aura.agent.ToolResult
import com.aura.agent.ToolRisk
import com.aura.security.ScreenCaptureHolder
import com.aura.providers.ToolDefinition
import com.aura.providers.ToolParameters
import com.aura.providers.ToolProperty
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
) {
    fun definition() = ToolDefinition(
        name = "capture_screen",
        description = "Capture the device screen and return it as a base64 JPEG. Shows a one-time system consent dialog for each capture; the user must confirm it.",
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
