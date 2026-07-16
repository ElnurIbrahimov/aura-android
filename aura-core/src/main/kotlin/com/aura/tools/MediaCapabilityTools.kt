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
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Capability-backed media generation tools.
 *
 * - text_to_speech: configured TTS provider (ElevenLabs)
 * - video_generate: configured video provider (Kling)
 * - world_3d_generate: configured 3D world provider (WorldLabs)
 *
 * All resolve through [CapabilityRouter] and report honestly when no
 * provider is configured.
 */
@Singleton
class MediaCapabilityTools @Inject constructor(
    private val capabilityRouter: CapabilityRouter,
) {

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
            val provider = capabilityRouter.resolve(CapabilityKind.TextToSpeech) as? TextToSpeechProvider
                ?: return@Tool ToolResult.Error("No TTS provider configured. Add an ElevenLabs API key in Settings.", "no_provider")
            runCatching {
                val result = kotlinx.coroutines.runBlocking { provider.speak(TtsRequest(text = text, voice = voice)) }
                ToolResult.Ok("TTS audio generated (${result.audio.size} bytes, ${result.extension}).")
            }.getOrElse { ToolResult.Error("TTS failed: ${it.message}", "generation_error") }
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
            val provider = capabilityRouter.resolve(CapabilityKind.VideoGeneration) as? VideoProvider
                ?: return@Tool ToolResult.Error("No video provider configured. Add a Kling API key in Settings.", "no_provider")
            runCatching {
                val result = kotlinx.coroutines.runBlocking { provider.generate(VideoRequest(prompt = prompt, durationSeconds = duration, aspectRatio = aspectRatio)) }
                ToolResult.Ok(result.videoUrl?.let { "Video: $it" } ?: "Video generated (${result.bytes?.size ?: 0} bytes).")
            }.getOrElse { ToolResult.Error("Video generation failed: ${it.message}", "generation_error") }
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
            val provider = capabilityRouter.resolve(CapabilityKind.World3DGeneration) as? com.aura.capabilities.worldlabs.WorldLabs3DProvider
                ?: return@Tool ToolResult.Error("No 3D world provider configured. Add a WorldLabs API key in Settings.", "no_provider")
            runCatching {
                val result = kotlinx.coroutines.runBlocking { provider.generateWorld(prompt) }
                ToolResult.Ok(result.worldUrl?.let { "3D world: $it" } ?: "3D world generated (operation ${result.operationId}).")
            }.getOrElse { ToolResult.Error("3D generation failed: ${it.message}", "generation_error") }
        },
        category = "media",
    )
}
