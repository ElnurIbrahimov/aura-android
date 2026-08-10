package com.aura.agent.policy

import com.aura.agent.Tool
import com.aura.agent.ToolContext
import com.aura.agent.ToolRisk
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Evaluates [ToolPolicy] against a tool and execution context.
 * Policy can only tighten the built-in [ToolRisk] — never loosen it.
 *
 * Resolution order (innermost wins):
 * 1. Built-in ToolRisk (READ_ONLY → DESTRUCTIVE)
 * 2. Incognito gate (memoryEnabled=false blocks >= WRITE_LOCAL)
 * 3. User ToolPolicy (enabled/disabled, confirmation, cost, scope, approval)
 * 4. Per-run approval scope (approvedRemoteCostTools)
 */
@Singleton
class PolicyEngine @Inject constructor(
    private val policyStore: ToolPolicyStore,
) {
    /**
     * Evaluate whether [tool] can execute in [ctx].
     * Returns [PolicyResult.Allowed] if all checks pass, or a specific
     * denial/approval/confirmation result otherwise.
     */
    suspend fun evaluate(tool: Tool, ctx: ToolContext, scope: String? = null): PolicyResult {
        // 1. Incognito gate — hard lower boundary, cannot be overridden by policy
        if (!ctx.memoryEnabled && tool.risk.ordinal >= ToolRisk.WRITE_LOCAL.ordinal) {
            return PolicyResult.Disabled(tool.name)
        }

        // 2. User policy, falling back to risk-based defaults
        val policy = policyStore.getPolicy(tool.name)
            ?: ToolPolicyDefaults.forTool(tool.name, tool.risk)

        if (!policy.enabled) {
            return PolicyResult.Disabled(tool.name)
        }

        // 3. Confirmation level check — satisfied by the per-conversation
        // grant recorded when the user confirms the gate dialog. Without
        // the ctx check this returned NeedsConfirmation unconditionally:
        // nothing could ever satisfy it, so confirmation-gated tools
        // (WRITE_REMOTE/PRIVACY/DESTRUCTIVE by default) were permanently
        // blocked. A confirmed tool falls through to the approval checks
        // below, so a REMOTE_COST tool with confirmation set still needs
        // its cost approval.
        if (policy.confirmation != ConfirmationLevel.NONE && tool.name !in ctx.confirmedTools) {
            return PolicyResult.NeedsConfirmation(policy.confirmation, policy)
        }

        // 3b. Scope allowlist. `ToolPolicy.allowedScopes` and
        // `PolicyResult.ScopeDenied` were both declared and neither was ever
        // evaluated, so a user who restricted a tool to one app or domain got a
        // setting that did nothing and said nothing.
        scopeDenial(policy, scope)?.let { return it }

        // 4. Per-run approval for REMOTE_COST tools
        if (tool.risk == ToolRisk.REMOTE_COST && tool.name !in ctx.approvedRemoteCostTools) {
            return PolicyResult.NeedsApproval(policy)
        }

        // 5. Per-run approval override from policy
        if (policy.requireApprovalPerRun && tool.name !in ctx.approvedRemoteCostTools) {
            return PolicyResult.NeedsApproval(policy)
        }

        return PolicyResult.Allowed(policy)
    }

    /**
     * Check a call target against the tool's scope allowlist.
     *
     * Public because a scope is often only known inside the tool: `screen_act`
     * learns its target package by reading the foreground app, which happens
     * well after [evaluate] has run. Such a tool calls this itself once it knows.
     *
     * **Fails closed.** An allowlist that is configured but unenforceable denies
     * rather than allows: the user asked for a restriction, and silently
     * ignoring it because the call site forgot to pass a scope is how a security
     * control becomes decoration. Denying is visible and debuggable; allowing is
     * neither. An empty allowlist means "no restriction" and permits everything,
     * which is the default and the common case.
     */
    suspend fun scopeDenial(toolName: String, scope: String?): PolicyResult.ScopeDenied? {
        val policy = policyStore.getPolicy(toolName) ?: return null
        return scopeDenial(policy, scope)
    }

    private fun scopeDenial(policy: ToolPolicy, scope: String?): PolicyResult.ScopeDenied? {
        if (policy.allowedScopes.isEmpty()) return null
        if (scope == null) return PolicyResult.ScopeDenied(policy.toolName, "<no scope supplied>")
        // Exact, or extended at a PATH separator: "example.com" covers
        // "example.com/inbox" because every real URL carries a path, and an
        // exact-only rule would make a domain allowlist unusable.
        //
        // Deliberately NOT extended at a dot. The first version allowed that so
        // "com.google" would cover "com.google.android.gm" — and its own test
        // caught that the same rule lets "example.com.evil.net" past an
        // "example.com" allowlist, because a package hierarchy and a lookalike
        // domain are the same string shape and only the intent differs. There is
        // no way to tell them apart here, so the permissive reading is dropped:
        // an allowlist is built by naming apps, and naming one exactly is
        // precise, obvious, and has no lookalike hole.
        val ok = policy.allowedScopes.any { allowed ->
            scope == allowed || scope.startsWith("$allowed/")
        }
        return if (ok) null else PolicyResult.ScopeDenied(policy.toolName, scope)
    }
}