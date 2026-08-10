package com.aura.profile

import android.util.Log
import com.aura.providers.ChatOptions
import com.aura.providers.ProviderMessage
import com.aura.providers.ProviderMessage.Role
import com.aura.providers.ProviderRegistry
import com.aura.providers.ResponseSchema
import com.aura.providers.StructuredJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
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

        val parsed = StructuredJson.requestJson(
            registry = providerRegistry,
            modelId = model,
            messages = messages,
            options = ChatOptions(temperature = 0.0, maxTokens = 200),
            schema = PROFILE_SCHEMA,
            timeoutMs = EXTRACTION_TIMEOUT_MS,
            tag = "LlmProfileExtractor",
        ) { cleaned ->
            runCatching { json.decodeFromString(ProfileExtraction.serializer(), cleaned) }
                .onFailure { Log.w("LlmProfileExtractor", "unparseable extraction: ${it.message}", it) }
                .getOrNull()
        } ?: return null

        // An extraction that found nothing is not a failure, but it is also not
        // worth writing — the caller keeps whatever the regex pass found.
        return parsed.takeUnless {
            it.name.isNullOrBlank() && it.traits.isEmpty() && it.facts.isEmpty()
        }
    }

    companion object {
        private const val EXTRACTION_TIMEOUT_MS = 5_000L

        /**
         * Mirrors [ProfileExtraction]. `name` is nullable in Kotlin and stays
         * un-required here, because "the user said nothing about their name" is
         * the common case and forcing the field would push the model toward
         * inventing one.
         */
        private val PROFILE_SCHEMA = ResponseSchema(
            name = "extract_user_profile",
            schema = buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject {
                    put("name", buildJsonObject { put("type", "string") })
                    put("traits", buildJsonObject {
                        put("type", "array")
                        put("items", buildJsonObject { put("type", "string") })
                    })
                    put("facts", buildJsonObject {
                        put("type", "array")
                        put("items", buildJsonObject { put("type", "string") })
                    })
                })
                put("required", buildJsonArray {
                    add(JsonPrimitive("traits"))
                    add(JsonPrimitive("facts"))
                })
            },
        )
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