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
     * Parse one SSE `data:` event into zero or more [ProviderChunk]s.
     *
     * Returns a list rather than a single chunk because a single SSE event
     * can carry multiple parallel tool calls in its `tool_calls` array
     * (a real pattern in vLLM, Together, and some OpenAI proxies). The
     * downstream caller must emit every chunk in the list so that
     * Brain.fromProvider's index->id routing sees every tool call start.
     *
     * Returns:
     *   - empty list for uninteresting events (comments, empty deltas,
     *     unparseable JSON)
     *   - a list with one `finishReason = stop` chunk when `data == "[DONE]"`
     *   - a list of text/tool/finish chunks otherwise
     *
     * P0-AGENTIC-F1: previous implementation returned a single chunk,
     * which **dropped** all but the last tool call when multiple were
     * batched into one event. The Brain's LRU nameById then mis-routed
     * the dropped calls' argument deltas to the surviving tool call.
     *
     * Order: tool calls come first so a parallel-call chunk adjacent to
     * a finishReason (which some servers emit) is still routed correctly.
     */
    fun parseEvent(data: String): List<ProviderChunk> {
        if (data == "[DONE]") return listOf(ProviderChunk(finishReason = FinishReason.stop))
        val obj = try { Json.parseToJsonElement(data).jsonObject } catch (e: Exception) { return emptyList() }
        val choice = (obj["choices"] as? JsonArray)?.firstOrNull()?.jsonObject ?: return emptyList()
        val delta = (choice["delta"] as? JsonObject) ?: return emptyList()

        // Tool calls — emit one chunk per array entry. resolveIds() updates
        // the index->id map as a side effect.
        val toolChunks = parseToolCalls(delta)

        // Text content
        val text = (delta["content"] as? JsonPrimitive)?.content
        val textChunk = if (text != null) ProviderChunk(text = text) else null

        // Thinking / reasoning content (DeepSeek: reasoning_content, OpenAI o-series: reasoning)
        val reasoning = (delta["reasoning_content"] as? JsonPrimitive)?.content
            ?: (delta["reasoning"] as? JsonPrimitive)?.content
        val thinkingChunk = if (reasoning != null) ProviderChunk(thinking = reasoning) else null

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

        // Order: tool calls first (so Brain captures the start), then
        // finish (so a finishing event cannot swallow a queued tool
        // chunk), then thinking, then text.
        return toolChunks + listOfNotNull(finishChunk, thinkingChunk, textChunk)
    }

    /**
     * Return one [ProviderChunk] per `tool_calls` array entry. Updates
     * the index->id map on every call so subsequent argument-only deltas
     * carry the index back to the same id.
     */
    private fun parseToolCalls(delta: JsonObject): List<ProviderChunk> {
        val toolCalls = (delta["tool_calls"] as? JsonArray) ?: return emptyList()
        val out = mutableListOf<ProviderChunk>()
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
            out += ProviderChunk(toolCall = ToolCall(id = resolvedId, name = name, arguments = args))
        }
        return out
    }
}