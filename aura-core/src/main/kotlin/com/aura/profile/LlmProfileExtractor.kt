package com.aura.profile

import android.util.Log
import com.aura.providers.ChatOptions
import com.aura.providers.ProviderMessage
import com.aura.providers.ProviderMessage.Role
import com.aura.providers.ProviderRegistry
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * LLM-based profile extraction.
 *
 * The existing regex-based [com.aura.agent.MemoryAugmentedAgenticLoop] extraction
 * catches 4 patterns: name, location, job, preferences. It misses everything
 * else: "I use Vim", "I'm allergic to peanuts", "my wife's name is Sarah",
 * "I work night shifts".
 *
 * This extractor runs as a FALLBACK after the regex — only when the regex
 * finds nothing. It asks a cheap model to extract structured facts from
 * the user's message and returns them as [ProfileExtraction].
 *
 * Non-blocking: if the LLM call fails or returns unparseable JSON, the
 * caller gets null and keeps whatever the regex found (if anything).
 */
@Singleton
class LlmProfileExtractor @Inject constructor(
    private val providerRegistry: ProviderRegistry,
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }

    /**
     * Extract structured user facts from [userText].
     *
     * @param userText The user's message (first-person text)
     * @param model Cheap model ID for the extraction call
     * @return [ProfileExtraction] with name/traits/facts, or null on failure
     */
    suspend fun extract(
        userText: kotlin.String,
        model: kotlin.String,
    ): ProfileExtraction? {
        if (userText.isBlank() || userText.length < 10) return null

        val systemPrompt = """Extract structured facts about the user from their message.
Return ONLY valid JSON with this shape: {"name": "", "traits": [], "facts": []}
- name: the user's name if they state it (e.g. "my name is X")
- traits: personal attributes, tools, preferences, habits (e.g. "uses Vim", "allergic to peanuts")
- facts: specific factual statements (e.g. "wife is Sarah", "works night shifts")
Only extract facts the USER stated about themselves.
If no personal facts are present, return empty arrays.
Do not include the assistant's claims. Do not hallucinate."""

        val messages = listOf(
            ProviderMessage(role = Role.system, content = systemPrompt),
            ProviderMessage(role = Role.user, content = userText.take(500)),
        )

        return try {
            val builder = StringBuilder()
            withTimeoutOrNull(EXTRACTION_TIMEOUT_MS) {
                providerRegistry.chat(model, messages, ChatOptions(temperature = 0.0, maxTokens = 200))
                    .collect { chunk ->
                        chunk.text?.let { builder.append(it) }
                    }
            }
            val raw = builder.toString().trim()
            if (raw.isBlank()) return null

            // The model may wrap JSON in markdown fences, add commentary,
            // or use case variants like "```JSON" or "``` json".
            // Extract the first { ... } block from the response.
            val cleaned = extractJsonBlock(raw)

            if (cleaned == null) return null
            val parsed = json.decodeFromString(ProfileExtraction.serializer(), cleaned)
            // Only return if we found something — don't return empty extractions
            if (parsed.name.isNullOrBlank() && parsed.traits.isEmpty() && parsed.facts.isEmpty()) {
                null
            } else {
                parsed
            }
        } catch (e: Exception) {
            Log.w("LlmProfileExtractor", "extraction failed: ${e.message}")
            null
        }
    }

    /**
     * Extract the first JSON object ({ ... }) from a raw string that may
     * contain markdown fences, prose, or commentary. Handles:
     * - ```json
{...}
``` (case-insensitive fence prefix)
     * - ```{...}``` (bare fence)
     * - Leading/trailing prose around a {...} block
     * - Multiple JSON objects (takes the first balanced one)
     */
    private fun extractJsonBlock(raw: kotlin.String): kotlin.String? {
        val start = raw.indexOf('{')
        if (start < 0) return null
        // Find the matching closing brace by counting depth
        var depth = 0
        for (i in start until raw.length) {
            when (raw[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return raw.substring(start, i + 1)
                }
            }
        }
        return null // unbalanced — return raw and let JSON parser fail
    }

    companion object {
        private const val EXTRACTION_TIMEOUT_MS = 5_000L
    }
}

/**
 * Structured extraction result from the LLM.
 */
@Serializable
data class ProfileExtraction(
    val name: kotlin.String? = null,
    val traits: List<kotlin.String> = emptyList(),
    val facts: List<kotlin.String> = emptyList(),
)