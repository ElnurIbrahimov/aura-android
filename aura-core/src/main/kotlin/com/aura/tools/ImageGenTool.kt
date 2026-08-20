package com.aura.tools

import com.aura.agent.Tool
import com.aura.agent.ToolContext
import com.aura.agent.ToolResult
import com.aura.agent.ToolRisk
import com.aura.providers.ProviderKeys
import com.aura.providers.ToolParameters
import com.aura.providers.ToolProperty
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import javax.inject.Inject
import javax.inject.Singleton
import android.util.Log

/**
 * Image generation over any provider exposing an OpenAI-shaped
 * `/images/generations`, falling back to Pollinations.ai (free, no key).
 *
 * Selection order:
 * 1. The provider named by the `imageModel` preference (`prefix:model`, e.g.
 *    `agnes:agnes-image-2.1-flash`). A bare model name means OpenAI.
 * 2. Pollinations.ai — free fallback, no API key needed.
 *
 * The endpoint used to be the literal string
 * `https://api.openai.com/v1/images/generations` with the OpenAI key, so no
 * other configured provider could generate an image no matter what the user
 * selected. Providers whose images API is not OpenAI-shaped decline by
 * returning null from [com.aura.providers.Provider.imagesEndpoint].
 *
 * Risk: REMOTE_COST (invokes paid API per call, no phone permissions).
 */
@Singleton
class ImageGenTool @Inject constructor(
    private val httpClient: OkHttpClient,
    private val providerKeys: ProviderKeys,
    private val userPreferences: com.aura.data.UserPreferences,
    private val brain: com.aura.agent.Brain? = null,
    private val providerRegistry: com.aura.providers.ProviderRegistry? = null,
    private val capabilityRouter: com.aura.capabilities.CapabilityRouter? = null,
    // Optional and last, matching brain/providerRegistry above: Hilt supplies
    // it in production, and the JVM tests that construct this positionally do
    // not need it. Only the inline-base64 response path touches it.
    @dagger.hilt.android.qualifiers.ApplicationContext
    private val appContext: android.content.Context? = null,
    /**
     * Records that the image exists. Optional for the same reason as the others — the JVM
     * tests construct this positionally — and best-effort by contract: the image is the
     * product, the row is bookkeeping, and a store that cannot be written must not turn a
     * successful generation into an error.
     */
    private val generatedMedia: com.aura.media.GeneratedMediaStore? = null,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val mediaTypeJson = "application/json".toMediaType()

    private companion object {
        /** Used when `imageModel` is unset — the historical hardcoded default. */
        const val DEFAULT_OPENAI_MODEL = "dall-e-3"
    }

    fun definition() = ToolParameters(
        properties = mapOf(
            "prompt" to ToolProperty(
                type = "string",
                description = "Text description of the desired image",
            ),
            "provider_hint" to ToolProperty(
                type = "string",
                description = "Preferred provider (default: \"openai\")",
            ),
            "size" to ToolProperty(
                type = "string",
                description = "Image size in format WxH (default: \"1024x1024\")",
            ),
        ),
        required = listOf("prompt"),
    )

    val tool = Tool(
        name = "image_gen",
        description = "Generate an image from a text prompt using AI. Uses the configured image provider, falling back to a free one.",
        risk = ToolRisk.REMOTE_COST,
        parameters = definition(),
        execute = { call, _ ->
            val prompt = call.arguments["prompt"] as? String
                ?: return@Tool ToolResult.Error("missing 'prompt' argument", "bad_args")
            val size = call.arguments["size"] as? String ?: "1024x1024"

            try {
                val result = generateImage(prompt, size)
                // The single point where both paths meet: `result` is either a file:// URI
                // this tool just wrote, or the provider's own URL. Recorded after success,
                // so a failed generation cannot leave a row pointing at nothing — a broken
                // tile in the Library with no way to tell it from a real one.
                generatedMedia?.record(
                    kind = "image",
                    prompt = prompt,
                    storageUri = result,
                    remoteUrl = result.takeUnless { it.startsWith("file://") }.orEmpty(),
                    conversationId = call.id,
                )
                ToolResult.Ok("[IMAGE:$result]")
            } catch (e: Exception) {
                ToolResult.Error("image generation failed: ${e.message}", "http_error")
            }
        },
    category = "media")
    /**
     * Routes to the configured image provider, then to Pollinations.
     *
     * The `imageModel` preference carries an optional `prefix:model` — the same
     * shape the chat picker uses — so any configured provider with an
     * OpenAI-shaped `/images/generations` can serve it. A bare model name means
     * OpenAI, which is what every existing setting holds.
     *
     * This used to call `https://api.openai.com/v1/images/generations`
     * literally, with the OpenAI key. So a user who configured Agnes AI, saw
     * `agnes-image-2.1-flash` in the catalog, and selected it got nothing: the
     * only reachable backends were OpenAI and the free Pollinations fallback.
     * Selecting the image model in the CHAT picker — the one place it did
     * appear — just produced "Model agnes-image-2.1-flash is an image model.
     * Use /v1/images/generations." That model is now filtered out of the chat
     * picker (see OpenAiCompatProvider.canChat) and reachable here instead.
     */
    private suspend fun generateImage(prompt: String, size: String): String {
        // Prompt enhancement: expand bare prompts ("a cat") into
        // detailed image descriptions with style, lighting, and
        // composition cues. One cheap LLM call, massive quality
        // improvement for simple prompts. Skips if prompt is already
        // detailed (>80 chars) or Brain is unavailable.
        val enhancedPrompt = if (brain != null && prompt.length < 80) {
            try { enhancePrompt(prompt) } catch (_: Exception) { prompt }
        } else prompt

        val configured = userPreferences.imageModel.first()?.takeIf { it.isNotBlank() }

        // Resolve through the capability router, which serves hand-written
        // adapters (Stability) AND backends discovered from a configured
        // provider's catalog. A blank preference therefore means "whatever is
        // available" rather than the old hardcoded OpenAI — which is what makes
        // a newly connected token's image model work with no configuration.
        val provider = capabilityRouter
            ?.resolvePreferred(com.aura.capabilities.CapabilityKind.ImageGeneration, configured)
            as? com.aura.capabilities.ImageProvider
        if (provider != null) {
            try {
                val (width, height) = parseSize(size).let { it.width to it.height }
                val result = provider.generate(
                    com.aura.capabilities.ImageRequest(
                        prompt = enhancedPrompt,
                        model = configured?.substringAfter(':').orEmpty(),
                        width = width,
                        height = height,
                    ),
                )
                result.url?.takeIf { it.isNotBlank() }?.let { return it }
                result.bytes?.takeIf { it.isNotEmpty() }?.let { return persistImageBytes(it) }
                android.util.Log.w("ImageGenTool", "${provider.displayName} returned neither url nor bytes")
            } catch (e: Exception) {
                // Surface it: the user configured a paid provider and it did
                // not work. Silently serving a Pollinations image instead would
                // look like success.
                android.util.Log.w(
                    "ImageGenTool",
                    "image gen via ${provider.displayName} failed, falling back to Pollinations: ${e.message}",
                    e,
                )
            }
        } else {
            android.util.Log.w("ImageGenTool", "no image provider available — using Pollinations")
        }
        return generateWithPollinations(enhancedPrompt, size)
    }

    // ------------------------------------------------------------------
    // OpenAI-shaped images API — any provider exposing /images/generations
    // ------------------------------------------------------------------
    // POST <provider baseUrl>/images/generations
    // Body: { model, prompt, n: 1, size }
    // Response: data[0].url  OR  data[0].b64_json
    // ------------------------------------------------------------------

    /**
     * Write an inline base64 image to app storage and return a `file://` URI.
     *
     * Kept out of the conversation as base64: `Turn.generatedImages` is
     * persisted in the conversation JSON, and a 1024x1024 PNG is well over a
     * megabyte of text per image once encoded.
     *
     * `filesDir`, not `cacheDir`. The original reasoning was that a cache file can be
     * evicted "but so can a provider-hosted URL expire" — true, and beside the point. A URL
     * expiring is a decision someone else makes; an eviction is one we were making
     * ourselves, on every image, silently, with no record that the image had ever existed.
     * `BackupManager`'s interrupted-restore marker already lives in `filesDir` for exactly
     * this reason, and its KDoc says so.
     *
     * Note that these are not in the JSON backup — it is a text format and these are
     * megabytes of binary. They survive Clear cache and storage pressure, not a reinstall.
     */
    private fun persistImageBytes(bytes: ByteArray): kotlin.String {
        val ctx = appContext ?: throw RuntimeException("no Context available to store an inline image")
        val dir = java.io.File(ctx.filesDir, "generated_media").apply { mkdirs() }
        val file = java.io.File(dir, "img_${java.util.UUID.randomUUID()}.png")
        file.writeBytes(bytes)
        return android.net.Uri.fromFile(file).toString()
    }

    // ------------------------------------------------------------------
    // Pollinations.ai (free, no API key)
    // ------------------------------------------------------------------
    // GET https://image.pollinations.ai/prompt/{urlencoded_prompt}?width=...&height=...&nologo=true
    // Returns the image URL directly — no HTTP call needed.
    // ------------------------------------------------------------------

    private data class Size(val width: Int, val height: Int)

    private fun parseSize(size: String): Size {
        val parts = size.lowercase().split("x")
        if (parts.size != 2) return Size(1024, 1024)
        val w = parts[0].toIntOrNull()
        val h = parts[1].toIntOrNull()
        return if (w != null && h != null) Size(w, h) else Size(1024, 1024)
    }

    private fun generateWithPollinations(prompt: String, size: String): String {
        val encodedPrompt = java.net.URLEncoder.encode(prompt, "UTF-8")
        val parsedSize = parseSize(size)
        return "https://image.pollinations.ai/prompt/$encodedPrompt?width=${parsedSize.width}&height=${parsedSize.height}&nologo=true"
    }

    /**
     * Enhance a short prompt into a detailed image generation prompt
     * with style, lighting, and composition cues. Uses the Brain's
     * stream method with a cheap one-shot call.
     */
    private suspend fun enhancePrompt(original: String): String {
        val b = brain ?: return original
        val model = runCatching {
            val reg = providerRegistry ?: return@runCatching null
            val providers = reg.configured()
            val first = providers.firstOrNull()
            val firstModel = first?.listModels()?.firstOrNull()
            if (first != null && firstModel != null) "${first.prefix}:$firstModel" else null
        }.onFailure { Log.w("ImageGenTool", "runCatching failed: ${it.message}", it) }.getOrNull() ?: return original
        val messages = listOf(
            com.aura.providers.ProviderMessage(
                role = com.aura.providers.ProviderMessage.Role.system,
                content = "You are an image prompt enhancer. Take the user's short description and expand it into a detailed image generation prompt with style, lighting, colors, mood, composition, and quality descriptors. Add detail but stay true to the user's intent. Return ONLY the enhanced prompt, no explanation.",
            ),
            com.aura.providers.ProviderMessage(
                role = com.aura.providers.ProviderMessage.Role.user,
                content = "Enhance: $original",
            ),
        )
        val options = com.aura.providers.ChatOptions(temperature = 0.7, maxTokens = 150)
        val result = StringBuilder()
        b.stream(model, messages, options = options).collect { chunk ->
            if (chunk is com.aura.agent.BrainChunk.Text) result.append(chunk.text)
        }
        return result.toString().trim().take(500).ifBlank { original }
    }
}
