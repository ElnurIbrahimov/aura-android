package com.aura.agent.policy

import com.aura.agent.ToolRisk

/**
 * Default policy factory. Returns a [ToolPolicy] for a tool based on its
 * built-in [ToolRisk]. Policy is always the strictest safe default — users
 * can loosen it in Settings only within the bounds of the risk.
 *
 * WRITE_LOCAL and WRITE_REMOTE tools default to NONE confirmation because
 * they are user-initiated actions on the user's own device (remember a
 * fact, set a reminder, create a task). Blocking them with IMPLICIT
 * confirmation would break the core chat loop — the model can't call
 * remember, set_reminder, manage_tasks, run_hand, etc. without the user
 * tapping approve every time.
 *
 * DESTRUCTIVE tools default to EXPLICIT confirmation — the user must
 * type "yes" before the action runs.
 *
 * REMOTE_COST tools are handled separately by the per-run approval gate
 * in [PolicyEngine] (approvedRemoteCostTools), not by confirmation level.
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
            confirmation = ConfirmationLevel.NONE,
        )
        ToolRisk.DESTRUCTIVE -> ToolPolicy(
            toolName = name,
            enabled = true,
            confirmation = ConfirmationLevel.EXPLICIT,
        )
    }
}