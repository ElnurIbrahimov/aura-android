package com.aura.ui.viewmodel

import com.aura.agent.Conversation
import java.io.ByteArrayOutputStream
import java.io.InputStream

/** Attach a completed tool result with the same ID used for the call. */
internal fun Conversation.attachCompletedToolTurn(
    id: String,
    name: String,
    arguments: String,
    result: String,
): Conversation = addToolCall(id, name, arguments).setToolResult(id, result)

/**
 * Read at most [maxBytes] from [input]. Returns null as soon as byte
 * [maxBytes] + 1 is observed, so oversized content is never fully buffered.
 */
internal fun readStreamWithinLimit(input: InputStream, maxBytes: Int): ByteArray? {
    require(maxBytes >= 0) { "maxBytes must be non-negative" }
    val output = ByteArrayOutputStream(minOf(maxBytes, 64 * 1024))
    val buffer = ByteArray(8 * 1024)
    var remaining = maxBytes
    while (true) {
        val requested = minOf(buffer.size, remaining + 1)
        val read = input.read(buffer, 0, requested)
        if (read < 0) return output.toByteArray()
        if (read > remaining) return null
        output.write(buffer, 0, read)
        remaining -= read
    }
}

/** Power-of-two BitmapFactory sample size that keeps the largest side near target. */
internal fun calculateImageSampleSize(width: Int, height: Int, target: Int): Int {
    if (target <= 0) return 1
    val largest = maxOf(width, height).coerceAtLeast(1)
    var sample = 1
    while (largest / sample > target && sample <= Int.MAX_VALUE / 2) {
        sample *= 2
    }
    return sample
}

/** Marker image tools emit around a generated image URL. */
private val IMAGE_MARKER = Regex("""\[IMAGE:(.+?)]""")

/**
 * The tools whose results may carry a `[BROWSER:url]` or `[IMAGE:url]` marker.
 *
 * The markers were parsed from the result of **every** tool. `read_url` and
 * `fetch_url` are READ_ONLY, so they run unattended and return the fetched page
 * body verbatim — which means a page containing the literal text
 * `[BROWSER:https://attacker.example/…]` caused that URL to be loaded in a
 * JavaScript-enabled WebView with no gesture and no SSRF check, and a literal
 * `[IMAGE:…]` became a GET on an unguarded client. The image case was the worse
 * of the two: [withImagesFromToolResults] re-derives markers from *stored* tool
 * results on every conversation reload, so an injected pixel was baked into the
 * saved conversation and re-fired every time it was opened.
 *
 * The intent was always narrow — the call sites' own comments said "from
 * open_browser_tab tool" and "from image_gen tools" — and `extractCitations`
 * one line below already took the tool name. These two did not.
 *
 * Emitters: `OpenBrowserTabTool`, `ImageGenTool`, `ImageGenCapabilityTool`.
 *
 * The name check is the whole defence, and it is sufficient because the URL in
 * a marker emitted by one of these tools has already been validated where that
 * can be done safely. `OpenBrowserTabTool` runs `SsrfGuard.validate` before it
 * emits, on the tool dispatcher; the image tools return a URL from the user's
 * own configured provider. Re-checking here would mean a blocking DNS lookup on
 * whatever thread parses the event — and both call sites run inside
 * `viewModelScope`, i.e. `Dispatchers.Main.immediate`. The guard belongs at the
 * tool, not on the main thread.
 */
internal const val BROWSER_MARKER_TOOL = "open_browser_tab"
internal val IMAGE_MARKER_TOOLS = setOf("image_gen", "image_generate")

/**
 * Image URLs a tool result is allowed to contribute, empty for any tool that is
 * not an image generator.
 *
 * Centralised rather than inlined at the call sites so a third consumer of
 * these markers cannot reintroduce the hole by forgetting the check — which is
 * exactly how it arose: `extractCitations` took the tool name, and the two
 * marker parsers beside it did not.
 */
internal fun imageUrlsFrom(toolName: String, result: String): List<String> {
    if (toolName !in IMAGE_MARKER_TOOLS) return emptyList()
    return IMAGE_MARKER.findAll(result).map { it.groupValues[1] }.toList()
}

/**
 * The URL a `[BROWSER:url]` marker asks the in-app browser to open, or null
 * when the emitting tool is not allowed to ask.
 */
internal fun browserUrlFrom(toolName: String, result: String): String? {
    if (toolName != BROWSER_MARKER_TOOL) return null
    return BROWSER_MARKER.find(result)?.groupValues?.get(1)
}

/** Marker [com.aura.tools.OpenBrowserTabTool] emits around a URL to open. */
private val BROWSER_MARKER = Regex("""\[BROWSER:(.+?)]""")

/**
 * Rebuild each turn's [Conversation.Turn.generatedImages] from its own tool
 * results.
 *
 * Generated images used to vanish the moment a run finished. `AgentEvent.-
 * ToolResult` attached them to the UI's copy of the conversation, and then
 * `AgentEvent.Result` replaced that copy wholesale with the loop's — which had
 * never been told about them. The image rendered while streaming and
 * disappeared on completion, and nothing was persisted, so reopening the
 * conversation from History never showed it either.
 *
 * Deriving them from `toolTurns` instead of carrying them across two
 * conversation objects fixes all three: the loop's conversation is the one that
 * gets saved, so the images persist and replay, and there is no index-matching
 * between two turn lists that can drift apart.
 *
 * Nothing is stored locally — a provider-hosted URL is a reference, and losing
 * the reference was the whole bug. Existing entries are preserved and deduped
 * so a UI-attached image is never dropped.
 */
internal fun Conversation.withImagesFromToolResults(): Conversation = copy(
    turns = turns.map { turn ->
        val fromTools = turn.toolTurns.flatMap { tt -> imageUrlsFrom(tt.name, tt.result) }
        if (fromTools.isEmpty()) {
            turn
        } else {
            turn.copy(generatedImages = (turn.generatedImages + fromTools).distinct())
        }
    },
)
