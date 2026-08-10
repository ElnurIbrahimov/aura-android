package com.aura.providers

import android.util.Log
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Ask a model for JSON and parse it, with one schema-free retry.
 *
 * Four call sites grew four different fence-strippers, all subtly different and
 * one outright broken (`LlmWriteGate`'s bare-object regex `\{(.*?)}` is
 * non-greedy, so it returned a *truncated* object on any nested brace). This is
 * the one implementation.
 *
 * Structured output makes a parse failure rare. It does not make it impossible,
 * and on Anthropic without a schema it is not even involved — Anthropic has no
 * bare JSON mode, so a prompt-level request plus [stripFences] is the entire
 * mechanism there. Every caller keeps a lenient parse for that reason.
 */
object StructuredJson {

    /**
     * One attempt with [schema], then at most one without it.
     *
     * The retry exists because "the endpoint 400s on `response_format`" and
     * "the model wrote prose" are indistinguishable from here — the flow just
     * fails or yields something unparseable. Dropping the schema and asking in
     * the prompt instead is the only recovery that works for both, and it is
     * the exact behaviour the four call sites had before, so the retry is a
     * floor on quality rather than a new cost.
     *
     * Deliberately not implemented as retry inside [ProviderRegistry]: that
     * would apply to the user-facing chat path too, where an extra round-trip
     * is a latency regression the user feels.
     *
     * @param parse must return null on failure rather than throwing.
     * @return the parsed value, or null when both attempts failed.
     */
    suspend fun <T> requestJson(
        registry: ProviderRegistry,
        modelId: String,
        messages: List<ProviderMessage>,
        options: ChatOptions = ChatOptions(),
        schema: ResponseSchema? = null,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        tag: String = "StructuredJson",
        parse: (String) -> T?,
    ): T? {
        if (schema != null) {
            collect(registry, modelId, messages, options.copy(responseSchema = schema), timeoutMs, tag)
                ?.let { raw -> parse(stripFences(raw))?.let { return it } }
            Log.w(tag, "schema attempt produced nothing parseable on $modelId; retrying without one")
        }

        val nudged = messages.nudgeForJson()
        val raw = collect(
            registry,
            modelId,
            nudged,
            options.copy(responseSchema = null, responseFormat = ResponseFormat.JSON),
            timeoutMs,
            tag,
        ) ?: return null
        return parse(stripFences(raw))
    }

    /** Drain the stream to text, or null on timeout/error. */
    private suspend fun collect(
        registry: ProviderRegistry,
        modelId: String,
        messages: List<ProviderMessage>,
        options: ChatOptions,
        timeoutMs: Long,
        tag: String,
    ): String? = withTimeoutOrNull(timeoutMs) {
        runCatching {
            registry.chat(modelId, messages, options)
                .toList()
                .mapNotNull { it.text }
                .joinToString("")
        }.onFailure { Log.w(tag, "chat failed on $modelId: ${it.message}", it) }
            .getOrNull()
    }?.takeIf { it.isNotBlank() }

    /**
     * Append a JSON-only instruction to the last system message, or add one.
     *
     * Only used on the retry. Providers that honour a schema do not need it,
     * and adding it unconditionally would change the prompt — and therefore the
     * cached prefix — on every call.
     */
    private fun List<ProviderMessage>.nudgeForJson(): List<ProviderMessage> {
        val lastSystem = indexOfLast { it.role == ProviderMessage.Role.system }
        if (lastSystem < 0) {
            return listOf(ProviderMessage(ProviderMessage.Role.system, JSON_ONLY)) + this
        }
        return mapIndexed { i, m ->
            if (i == lastSystem) m.copy(content = m.content + "\n\n" + JSON_ONLY) else m
        }
    }

    /**
     * Recover a JSON object from whatever the model actually wrote.
     *
     * Brace-depth scanning rather than a regex, because the regexes this
     * replaces could not survive nesting. String-aware, so a `{` or `}` inside
     * a quoted value — `{"note": "a } here"}` — does not end the scan early.
     * Returns the input trimmed when no balanced object is found, leaving the
     * caller's parser to fail on something it can log.
     */
    fun stripFences(raw: String): String {
        val text = raw.trim()
            .removePrefix("```json").removePrefix("```JSON").removePrefix("```")
            .removeSuffix("```")
            .trim()

        val start = text.indexOfFirst { it == '{' || it == '[' }
        if (start < 0) return text

        val opener = text[start]
        val closer = if (opener == '{') '}' else ']'
        var depth = 0
        var inString = false
        var escaped = false
        for (i in start until text.length) {
            val c = text[i]
            when {
                escaped -> escaped = false
                c == '\\' && inString -> escaped = true
                c == '"' -> inString = !inString
                inString -> Unit
                c == opener -> depth++
                c == closer -> {
                    depth--
                    if (depth == 0) return text.substring(start, i + 1)
                }
            }
        }
        return text
    }

    private const val JSON_ONLY =
        "Reply with a single valid JSON value and nothing else. No prose, no markdown fences."

    /** Matches the tightest existing call-site timeout (LlmProfileExtractor's 5s). */
    const val DEFAULT_TIMEOUT_MS = 15_000L
}
