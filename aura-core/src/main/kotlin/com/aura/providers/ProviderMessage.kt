package com.aura.providers

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

@Serializable
data class ProviderMessage(
    val role: Role,
    val content: String,
    val name: String? = null,
    @SerialName("tool_calls") val toolCalls: List<ToolCall>? = null,
    @SerialName("tool_call_id") val toolCallId: String? = null,
    /**
     * Reasoning this assistant turn produced, for providers that require it
     * back on the next request.
     *
     * Only Anthropic reads it. It is on the shared DTO rather than an
     * Anthropic-specific type because [com.aura.agent.Conversation.toMessages]
     * builds one message list for all seventeen providers and cannot know which
     * one will receive it. Every other serialiser builds its JSON explicitly and
     * therefore ignores this field — which is the requirement, since an unknown
     * key on a strict endpoint is a 400.
     */
    val thinking: String? = null,
    /**
     * The signature Anthropic issued over [thinking]. A thinking block replayed
     * without it, or with one this account never received, is rejected — so a
     * trace that arrives here unsigned (a conversation saved before signatures
     * were captured, or reasoning produced by a different provider) is dropped
     * rather than sent and guessed at.
     */
    val thinkingSignature: String? = null,
) {
    @Serializable
    enum class Role { system, user, assistant, tool }
}

/**
 * OpenAI Chat Completions wire shape for one message. Assistant messages
 * echo their `tool_calls`, and `role=tool` messages carry `tool_call_id` —
 * both are required by strict endpoints (api.openai.com 400s on a tool
 * message whose call was never echoed). Shared by OpenAiCompatProvider
 * and CustomOpenAiCompatProvider.
 */
internal fun ProviderMessage.toOpenAiJson(): JsonObject = buildJsonObject {
    put("role", role.name)
    put("content", content)
    if (role == ProviderMessage.Role.tool && toolCallId != null) {
        put("tool_call_id", toolCallId)
    }
    val calls = toolCalls
    if (role == ProviderMessage.Role.assistant && !calls.isNullOrEmpty()) {
        putJsonArray("tool_calls") {
            for (call in calls) {
                add(
                    buildJsonObject {
                        put("id", call.id)
                        put("type", "function")
                        put("function", buildJsonObject {
                            put("name", call.name)
                            put("arguments", call.arguments)
                        })
                    },
                )
            }
        }
    }
}
