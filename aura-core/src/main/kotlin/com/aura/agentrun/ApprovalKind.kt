package com.aura.agentrun

/**
 * Distinguishes the two things an [ApprovalRequestEntity] can be waiting on.
 *
 * They look identical in the Agent Runs UI but need opposite handling:
 *
 * - A **cost** approval is satisfied by the user tapping Approve. The tool was
 *   gated on the user agreeing to spend money, and the decision record *is*
 *   the grant.
 * - A **permission** approval is not. Marking the record APPROVED changes
 *   nothing about what `ContextCompat.checkSelfPermission` returns, so
 *   re-running the step hits `NeedsPermission` again and blocks again. Before
 *   this distinction existed, approving a permission-gated step in Agent Runs
 *   produced an unbreakable approve → block → approve loop: the retry path
 *   was wired correctly, but the retry could not possibly succeed because
 *   nothing ever asked Android for the permission.
 *
 * The kind is carried in the rationale rather than a dedicated column so this
 * needs no AgentRunDatabase migration. [PERMISSION_PREFIX] is the contract
 * between [AgentRunExecutorWorker], which writes it, and the UI, which reads
 * it — not an incidental piece of display text. Change it in one place only.
 */
object ApprovalKind {

    /** Marks a rationale as a runtime-permission request and precedes the permission id. */
    const val PERMISSION_PREFIX = "Permission needed: "

    /** Build the rationale for a runtime-permission approval. */
    fun permissionRationale(permission: String): String = "$PERMISSION_PREFIX$permission"

    /**
     * The Android permission this approval is gated on, or null when it is an
     * ordinary cost/confirmation approval that Approve alone can satisfy.
     */
    fun permissionOf(rationale: String): String? =
        rationale.takeIf { it.startsWith(PERMISSION_PREFIX) }
            ?.removePrefix(PERMISSION_PREFIX)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
}
