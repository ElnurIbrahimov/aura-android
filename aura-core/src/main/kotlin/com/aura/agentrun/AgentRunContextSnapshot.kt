package com.aura.agentrun

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Minimal snapshot of the [com.aura.agent.ToolContext] that created an
 * AgentRun, stored in [AgentRunEntity.metadata] so background execution can
 * recreate a matching context. Only fields that affect tool gating are
 * preserved; the full conversation history is not duplicated here.
 */
@Serializable
data class AgentRunContextSnapshot(
    val userMessage: String = "",
    val approvedRemoteCostTools: Set<String> = emptySet(),
    val memoryEnabled: Boolean = true,
    val activeAgentId: String = "",
) {
    fun toJson(): String = Json.encodeToString(this)

    companion object {
        fun fromJson(json: String): AgentRunContextSnapshot =
            runCatching { Json.decodeFromString<AgentRunContextSnapshot>(json) }.getOrDefault(AgentRunContextSnapshot())
    }
}
