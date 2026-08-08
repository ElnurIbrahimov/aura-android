package com.aura.tools

import com.aura.agent.Tool
import com.aura.agent.ToolResult
import com.aura.agent.ToolRisk
import com.aura.capabilities.CapabilityKind
import com.aura.capabilities.CapabilityRouter
import com.aura.capabilities.TextToSpeechProvider
import com.aura.capabilities.TtsRequest
import com.aura.capabilities.VideoProvider
import com.aura.capabilities.VideoRequest
import com.aura.providers.ToolParameters
import com.aura.providers.ToolProperty
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton
import android.util.Log

/**
 * Capability-backed media generation tools.
 *
 * Each resolves through [CapabilityRouter], which now serves hand-written
 * vendor adapters AND backends discovered from a configured provider's model
 * catalog — so these work with, say, `agnes-video-v2.0` without anyone having
 * written an Agnes adapter.
 *
 * The user's Settings choice (`videoModel` / `voiceModel`) is honoured when it
 * is still available and ignored when it is not, so a stale preference degrades
 * to a working backend rather than to an error.
 */
@Singleton
class MediaCapabilityTools @Inject constructor(
    private val capabilityRouter: CapabilityRouter,
    private val userPreferences: com.aura.data.UserPreferences,
) {
    /**
     * Names what is actually available for [kind], for error messages.
     *
     * The old messages named one vendor each ("Add an ElevenLabs API key"),
     * which stopped being true the moment any provider's catalog could supply
     * the capability.
     */
    private fun noProviderMessage(kind: CapabilityKind, what: String): String {
        val available = capabilityRouter.available(kind).map { it.displayName }
        return if (available.isEmpty()) {
            "No $what provider is configured. Add an API key for a provider that offers $what in Settings."
        } else {
            "No $what provider is usable. Configured: ${available.joinToString()}."
        }
    }

    val ttsTool = Tool(
        name = "text_to_speech",
        description = "Synthesize speech from text using the configured TTS provider.",
        risk = ToolRisk.REMOTE_COST,
        parameters = ToolParameters(
            properties = mapOf(
                "text" to ToolProperty(type = "string", description = "Text to speak"),
                "voice" to ToolProperty(type = "string", description = "Voice id (provider-specific)"),
            ),
            required = listOf("text"),
        ),
        execute = { call, _ ->
            val text = call.arguments["text"] as? String
                ?: return@Tool ToolResult.Error("missing 'text'", "bad_args")
            val voice = call.arguments["voice"] as? String ?: ""
            val preferred = userPreferences.voiceModel.first()
            val provider = capabilityRouter.resolvePreferred(CapabilityKind.TextToSpeech, preferred) as? TextToSpeechProvider
                ?: return@Tool ToolResult.Error(noProviderMessage(CapabilityKind.TextToSpeech, "text-to-speech"), "no_provider")
            runCatching {
                val result = provider.speak(
                    TtsRequest(text = text, voice = voice, model = preferred?.substringAfter(':').orEmpty()),
                )
                ToolResult.Ok("TTS audio generated (${result.audio.size} bytes, ${result.extension}).")
            }.onFailure { Log.w("MediaCap", "op failed: ${it.message}", it) }.getOrElse { ToolResult.Error("TTS failed: ${it.message}", "generation_error") }
        },
        category = "media",
    )

    val videoTool = Tool(
        name = "video_generate",
        description = "Generate a video from a text prompt using the configured video provider.",
        risk = ToolRisk.REMOTE_COST,
        parameters = ToolParameters(
            properties = mapOf(
                "prompt" to ToolProperty(type = "string", description = "Video description"),
                "duration" to ToolProperty(type = "integer", description = "Duration in seconds (default 5)"),
                "aspect_ratio" to ToolProperty(type = "string", description = "Aspect ratio, e.g. 16:9"),
            ),
            required = listOf("prompt"),
        ),
        execute = { call, _ ->
            val prompt = call.arguments["prompt"] as? String
                ?: return@Tool ToolResult.Error("missing 'prompt'", "bad_args")
            val duration = (call.arguments["duration"] as? Int) ?: 5
            val aspectRatio = (call.arguments["aspect_ratio"] as? String) ?: "16:9"
            val preferred = userPreferences.videoModel.first()
            val provider = capabilityRouter.resolvePreferred(CapabilityKind.VideoGeneration, preferred) as? VideoProvider
                ?: return@Tool ToolResult.Error(noProviderMessage(CapabilityKind.VideoGeneration, "video generation"), "no_provider")
            runCatching {
                val result = provider.generate(
                    VideoRequest(
                        prompt = prompt,
                        model = preferred?.substringAfter(':').orEmpty(),
                        durationSeconds = duration,
                        aspectRatio = aspectRatio,
                    ),
                )
                ToolResult.Ok(result.videoUrl?.let { "Video: $it" } ?: "Video generated (${result.bytes?.size ?: 0} bytes).")
            }.onFailure { Log.w("MediaCap", "op failed: ${it.message}", it) }.getOrElse { ToolResult.Error("Video generation failed: ${it.message}", "generation_error") }
        },
        category = "media",
    )

    val world3dTool = Tool(
        name = "world_3d_generate",
        description = "Generate a 3D scene or world preview from a text prompt using the configured WorldLabs provider.",
        risk = ToolRisk.REMOTE_COST,
        parameters = ToolParameters(
            properties = mapOf("prompt" to ToolProperty(type = "string", description = "Scene description")),
            required = listOf("prompt"),
        ),
        execute = { call, _ ->
            val prompt = call.arguments["prompt"] as? String
                ?: return@Tool ToolResult.Error("missing 'prompt'", "bad_args")
            // Resolved by interface. This downcast to the concrete
            // WorldLabs3DProvider silently rejected every other backend, so a
            // correctly registered second vendor still reported "no provider".
            val provider = capabilityRouter.resolve(CapabilityKind.World3DGeneration)
                as? com.aura.capabilities.World3DProvider
                ?: return@Tool ToolResult.Error(noProviderMessage(CapabilityKind.World3DGeneration, "3D world generation"), "no_provider")
            runCatching {
                val result = provider.generateWorld(prompt)
                ToolResult.Ok(result.worldUrl?.let { "3D world: $it" } ?: "3D world generated (operation ${result.operationId}).")
            }.onFailure { Log.w("MediaCap", "op failed: ${it.message}", it) }.getOrElse { ToolResult.Error("3D generation failed: ${it.message}", "generation_error") }
        },
        category = "media",
    )
}
