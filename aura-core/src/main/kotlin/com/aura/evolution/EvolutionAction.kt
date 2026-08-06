package com.aura.evolution

enum class EvolutionDomain {
    SKILL,
    MEMORY,

    /**
     * Kept for evidence recording (proactive_delivered / proactive_dismissed
     * hooks have live callers) even though no producible action targets this
     * domain anymore. A future rule store may reintroduce proactive actions.
     */
    PROACTIVE,
}

/**
 * The producible evolution actions. Every value here has a detector that can
 * create it, a patch schema the LLM authors ([EvolutionPatchAuthor]), a
 * validator rule set ([EvolutionPatchValidator]), an apply handler
 * ([EvolutionApplySaga]) and a complete rollback ([EvolutionRollbackManager]).
 *
 * Historic actions (CREATE_SKILL, FORGET_MEMORY, NEW_PROACTIVE_RULE, …) were
 * removed in the evolution rebuild: no detector ever produced them and several
 * had broken rollbacks. Their persisted rows are cleaned up by the v3→v4
 * database migration.
 */
enum class EvolutionAction {
    // Skill domain
    PATCH_SKILL,
    RETIRE_SKILL,
    PROMOTE_TO_HAND,

    // Memory domain
    CONSOLIDATE_MEMORIES,
}

enum class ProposalStatus {
    PENDING_REVIEW,
    APPROVED,
    REJECTED,
    APPLIED,
    APPLY_FAILED,
    ROLLED_BACK,
    SUPERSEDED,
}

enum class CandidateStatus {
    PENDING,
    REFLECTED,
    REJECTED,
    PROMOTED,
    /** Auto-applied by EvolutionCoordinator when domain has autoApplyApproved. */
    AUTO_APPLIED,
}
