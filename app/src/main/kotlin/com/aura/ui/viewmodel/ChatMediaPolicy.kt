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
        val fromTools = turn.toolTurns.flatMap { tt ->
            IMAGE_MARKER.findAll(tt.result).map { it.groupValues[1] }.toList()
        }
        if (fromTools.isEmpty()) {
            turn
        } else {
            turn.copy(generatedImages = (turn.generatedImages + fromTools).distinct())
        }
    },
)
