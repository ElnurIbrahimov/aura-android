package com.aura.agentrun

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import android.util.Log

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
    val toolTimeoutMs: Long = 60_000L,
) {
    fun toJson(): String = Json.encodeToString(this)

    companion object {
        fun fromJson(json: String): AgentRunContextSnapshot =
            runCatching { Json.decodeFromString<AgentRunContextSnapshot>(json) }.onFailure { Log.w("AgentRunContextSnapshot", "runCatching failed: ${it.message}", it) }.getOrDefault(AgentRunContextSnapshot())
    }
}
