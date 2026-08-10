package com.aura.providers

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
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

        // Usage BEFORE the `choices` guard below. When `stream_options`
        // requests it, OpenAI sends usage on a final event whose `choices` is
        // an EMPTY ARRAY — so reading usage after that guard drops every
        // report, on the one event that carries it.
        val usageChunk = parseUsage(obj)

        val choice = (obj["choices"] as? JsonArray)?.firstOrNull()?.jsonObject
            ?: return listOfNotNull(usageChunk)
        val delta = (choice["delta"] as? JsonObject) ?: return listOfNotNull(usageChunk)

        // Tool calls — emit one chunk per array entry. resolveIds() updates
        // the index->id map as a side effect.
        val toolChunks = parseToolCalls(delta)

        // Text content.
        //
        // contentOrNull, NOT content: JsonNull is itself a JsonPrimitive and
        // its `.content` is the literal string "null". Servers send
        // `"content": null` on every reasoning/tool delta, so `.content`
        // typed the word "null" into the assistant's reply.
        val text = (delta["content"] as? JsonPrimitive)?.contentOrNull
        val textChunk = if (text != null) ProviderChunk(text = text) else null

        // Thinking / reasoning content (DeepSeek: reasoning_content, OpenAI o-series: reasoning)
        val reasoning = (delta["reasoning_content"] as? JsonPrimitive)?.contentOrNull
            ?: (delta["reasoning"] as? JsonPrimitive)?.contentOrNull
        val thinkingChunk = if (reasoning != null) ProviderChunk(thinking = reasoning) else null

        // Finish reason. Same JsonNull trap, but far more damaging here:
        // ordinary deltas carry `"finish_reason": null`, which read as the
        // string "null", fell through `when`'s else branch to
        // FinishReason.stop, and closed the stream on the first chunk.
        val finish = (choice["finish_reason"] as? JsonPrimitive)?.contentOrNull
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
        // chunk), then thinking, then text. Usage last — it is metadata and
        // must not sit ahead of a finish chunk the caller acts on.
        return toolChunks + listOfNotNull(finishChunk, thinkingChunk, textChunk, usageChunk)
    }

    /**
     * The `usage` object, when present.
     *
     * Most OpenAI-compatible servers only send this when the request asked via
     * `stream_options: {include_usage: true}`, so until that was added this
     * parser had no usage path at all and twelve of seventeen prefixes were
     * billed on a `content.length` estimate in [ProviderRegistry].
     *
     * `prompt_tokens_details.cached_tokens` is the cache-hit figure. It is a
     * subset of `prompt_tokens`, not an addition.
     */
    private fun parseUsage(obj: JsonObject): ProviderChunk? {
        val usage = (obj["usage"] as? JsonObject) ?: return null
        val prompt = (usage["prompt_tokens"] as? JsonPrimitive)?.intOrNull ?: 0
        val completion = (usage["completion_tokens"] as? JsonPrimitive)?.intOrNull ?: 0
        val total = (usage["total_tokens"] as? JsonPrimitive)?.intOrNull ?: (prompt + completion)
        val cached = ((usage["prompt_tokens_details"] as? JsonObject)
            ?.get("cached_tokens") as? JsonPrimitive)?.intOrNull ?: 0
        // A usage object of all zeros carries no information and would only
        // add a no-op chunk for every stream.
        if (prompt == 0 && completion == 0 && total == 0) return null
        return ProviderChunk(
            usage = Usage(
                promptTokens = prompt,
                completionTokens = completion,
                totalTokens = total,
                cachedPromptTokens = cached,
            ),
        )
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
            // contentOrNull throughout — continuation deltas send explicit
            // nulls for id/name, and "null" ids would break index resolution.
            val tcId = (tco["id"] as? JsonPrimitive)?.contentOrNull ?: ""
            val name = (fn["name"] as? JsonPrimitive)?.contentOrNull ?: ""
            val args = (fn["arguments"] as? JsonPrimitive)?.contentOrNull ?: ""
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