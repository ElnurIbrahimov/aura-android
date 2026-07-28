package com.aura.evolution

enum class EvolutionDomain {
    SKILL,
    MEMORY,
    PROACTIVE,
}

enum class EvolutionAction {
    // Skill domain
    CREATE_SKILL,
    PATCH_SKILL,
    REWRITE_SKILL,
    MERGE_SKILLS,
    RETIRE_SKILL,
    PROMOTE_TO_HAND,
    PATCH_SPECIALIST_PROMPT,
    ADD_SKILL_EXAMPLE,

    // Memory domain
    CONSOLIDATE_MEMORIES,
    FORGET_MEMORY,
    UPDATE_MEMORY_CATEGORY,
    MERGE_MEMORIES,
    CREATE_BELIEF,
    UPDATE_BELIEF,
    RETIRE_BELIEF,

    // Proactive domain
    NEW_PROACTIVE_RULE,
    ADJUST_RULE_TIMING,
    DISABLE_RULE,
    ENABLE_RULE,
    REWRITE_RULE_MESSAGE,
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
