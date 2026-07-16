package com.aura.agent.policy

import com.aura.agent.ToolRisk

/**
 * Default policy factory. Returns a [ToolPolicy] for a tool based on its
 * built-in [ToolRisk]. Policy is always the strictest safe default — users
 * can loosen it in Settings only within the bounds of the risk.
 */
object ToolPolicyDefaults {
    fun forTool(name: kotlin.String, risk: ToolRisk): ToolPolicy = when (risk) {
        ToolRisk.READ_ONLY -> ToolPolicy(
            toolName = name,
            enabled = true,
            confirmation = ConfirmationLevel.NONE,
        )
        ToolRisk.REMOTE_COST -> ToolPolicy(
            toolName = name,
            enabled = true,
            confirmation = ConfirmationLevel.NONE,
        )
        ToolRisk.WRITE_LOCAL,
        ToolRisk.WRITE_REMOTE,
        ToolRisk.PRIVACY,
        -> ToolPolicy(
            toolName = name,
            enabled = true,
            confirmation = ConfirmationLevel.IMPLICIT,
        )
        ToolRisk.DESTRUCTIVE -> ToolPolicy(
            toolName = name,
            enabled = true,
            confirmation = ConfirmationLevel.EXPLICIT,
        )
    }
}
