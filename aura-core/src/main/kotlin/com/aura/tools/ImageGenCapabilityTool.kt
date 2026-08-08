package com.aura.tools

import com.aura.agent.Tool
import com.aura.agent.ToolContext
import com.aura.agent.ToolResult
import com.aura.agent.ToolRisk
import com.aura.capabilities.CapabilityKind
import com.aura.capabilities.CapabilityRouter
import com.aura.capabilities.ImageProvider
import com.aura.capabilities.ImageRequest
import com.aura.capabilities.ImageResult
import com.aura.providers.ToolParameters
import com.aura.providers.ToolProperty
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Capability-backed image generation. Resolves through [CapabilityRouter], so
 * it serves hand-written adapters (Stability) and backends discovered from a
 * configured provider's model catalog alike.
 *
 * **No free fallback.** This KDoc used to claim it "falls back to the legacy
 * Pollinations.ai free URL when no provider is configured"; it never did — that
 * fallback lives in the other image tool, `image_gen` ([ImageGenTool]). When
 * nothing is configured this reports `no_provider` and stops, which is the
 * honest behaviour for a REMOTE_COST tool but is not what the comment promised.
 *
 * Risk: REMOTE_COST — always requires user approval for paid providers.
 */
@Singleton
class ImageGenCapabilityTool @Inject constructor(
    private val capabilityRouter: CapabilityRouter,
    private val userPreferences: com.aura.data.UserPreferences? = null,
) {
    val tool = Tool(
        name = "image_generate",
        description = "Generate an image from a text prompt using the configured AI image provider.",
        risk = ToolRisk.REMOTE_COST,
        parameters = ToolParameters(
            properties = mapOf(
                "prompt" to ToolProperty(
                    type = "string",
                    description = "Text description of the desired image",
                ),
                "size" to ToolProperty(
                    type = "string",
                    description = "Image size in format WxH (default: \"1024x1024\")",
                ),
                "negative_prompt" to ToolProperty(
                    type = "string",
                    description = "What to avoid in the image (optional)",
                ),
            ),
            required = listOf("prompt"),
        ),
        execute = { call, _ ->
            val prompt = call.arguments["prompt"] as? String
                ?: return@Tool ToolResult.Error("missing 'prompt' argument", "bad_args")
            val size = call.arguments["size"] as? String ?: "1024x1024"
            val negativePrompt = call.arguments["negative_prompt"] as? String

            val (width, height) = parseSize(size)

            // resolvePreferred, not resolve: a provider can expose SEVERAL image
            // models (Agnes AI publishes agnes-image-2.0-flash and
            // agnes-image-2.1-flash), each of which becomes its own backend.
            // Plain resolve() takes whichever comes first in catalog order, so
            // choosing the newer one in Settings had no effect here.
            val preferred = userPreferences?.imageModel?.first()
            val provider = capabilityRouter.resolvePreferred(CapabilityKind.ImageGeneration, preferred)
            if (provider == null) {
                val available = capabilityRouter.available(CapabilityKind.ImageGeneration).map { it.displayName }
                return@Tool ToolResult.Error(
                    if (available.isEmpty()) {
                        "No image generation provider is configured. Add an API key for a provider that " +
                            "offers image generation in Settings."
                    } else {
                        "No image generation provider is usable. Configured: ${available.joinToString()}."
                    },
                    "no_provider",
                )
            }
            if (provider !is ImageProvider) {
                return@Tool ToolResult.Error(
                    "Configured provider ${provider.displayName} does not support image generation.",
                    "provider_mismatch",
                )
            }

            try {
                val request = ImageRequest(
                    prompt = prompt,
                    model = preferred?.substringAfter(':').orEmpty(),
                    width = width,
                    height = height,
                    negativePrompt = negativePrompt,
                )
                val result = provider.generate(request)
                val output = formatResult(result, provider.displayName)
                ToolResult.Ok(output)
            } catch (e: Exception) {
                ToolResult.Error(
                    "Image generation failed: ${e.message}",
                    "generation_error",
                )
            }
        },
        category = "media",
    )

    private fun formatResult(result: ImageResult, providerName: String): String = buildString {
        appendLine("Image generated via $providerName.")
        result.url?.let {
            appendLine("URL: $it")
            // Structured marker for inline image rendering in chat
            append("[IMAGE:$it]")
        }
        result.bytes?.let { appendLine("Size: ${it.size} bytes") }
        append("MIME: ${result.mimeType}")
    }

    private fun parseSize(size: String): Pair<Int, Int> {
        val parts = size.lowercase().split("x")
        if (parts.size != 2) return 1024 to 1024
        val w = parts[0].toIntOrNull()
        val h = parts[1].toIntOrNull()
        return if (w != null && h != null) w to h else 1024 to 1024
    }
}