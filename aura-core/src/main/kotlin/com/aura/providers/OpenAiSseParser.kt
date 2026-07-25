package com.aura.providers

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Shared SSE event parser for OpenAI-compatible streaming responses
 * (`/v1/chat/completions` format). Used by [OpenAiCompatProvider] and
 * [CustomOpenAiCompatProvider] so both providers share the same
 * tool-call index→id resolution, text-delta extraction, and
 * finish-reason mapping.
 *
 * The parser is stateful: it tracks `toolCallIndexToId` across events
 * in a single stream so that argument deltas (which carry `index` but
 * not `id` or `name`) can be routed to the correct tool call. Without
 * this, parallel tool calls have their argument deltas mis-routed by
 * Brain.fromProvider's lastOrNull() fallback.
 */
internal class OpenAiSseParser {

    /** Index→id mapping accumulated across events in one stream. */
    private val toolCallIndexToId = mutableMapOf<Int, String>()

    /**
     * Parse one SSE `data:` event into a [ProviderChunk], or null if
     * the event is not interesting (e.g. a comment, an empty delta,
     * or unparseable JSON).
     *
     * Returns a [ProviderChunk] with `finishReason = stop` when the
     * data is `[DONE]`.
     */
    fun parseEvent(data: String): ProviderChunk? {
        if (data == "[DONE]") return ProviderChunk(finishReason = FinishReason.stop)
        val obj = try { Json.parseToJsonElement(data).jsonObject } catch (e: Exception) { return null }
        val choice = (obj["choices"] as? JsonArray)?.firstOrNull()?.jsonObject ?: return null
        val delta = (choice["delta"] as? JsonObject) ?: return null

        // Text content
        val text = (delta["content"] as? JsonPrimitive)?.content
        val textChunk = if (text != null) ProviderChunk(text = text) else null

        // Tool calls — resolve id via index for parallel tool calls
        val toolChunk = parseToolCalls(delta)

        // Finish reason
        val finish = (choice["finish_reason"] as? JsonPrimitive)?.content
        val finishChunk = if (finish != null) {
            val reason = when (finish) {
                "stop" -> FinishReason.stop
                "length" -> FinishReason.length
                "tool_calls" -> FinishReason.tool_calls
                else -> FinishReason.stop
            }
            ProviderChunk(finishReason = reason)
        } else null

        // Prefer tool call > finish > text > null
        return toolChunk ?: finishChunk ?: textChunk
    }

    private fun parseToolCalls(delta: JsonObject): ProviderChunk? {
        val toolCalls = (delta["tool_calls"] as? JsonArray) ?: return null
        for (tc in toolCalls) {
            val tco = tc.jsonObject
            val fn = tco["function"]?.jsonObject ?: continue
            val tcId = (tco["id"] as? JsonPrimitive)?.content ?: ""
            val name = (fn["name"] as? JsonPrimitive)?.content ?: ""
            val args = (fn["arguments"] as? JsonPrimitive)?.content ?: ""
            val index = (tco["index"] as? JsonPrimitive)?.intOrNull
            // On the first delta for a tool call, OpenAI sends
            // id + name. Subsequent deltas carry index but not
            // id or name. Resolve the id from the index map.
            val resolvedId = if (tcId.isNotEmpty()) {
                if (index != null) toolCallIndexToId[index] = tcId
                tcId
            } else if (index != null) {
                toolCallIndexToId[index] ?: ""
            } else {
                ""
            }
            return ProviderChunk(toolCall = ToolCall(id = resolvedId, name = name, arguments = args))
        }
        return null
    }
}