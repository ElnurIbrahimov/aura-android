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
