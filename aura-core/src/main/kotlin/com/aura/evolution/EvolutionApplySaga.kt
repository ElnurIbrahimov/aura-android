package com.aura.evolution

import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Applies an approved evolution proposal. Each [EvolutionAction] has a
 * dedicated handler. The saga records the attempt, creates a revision,
 * and returns success/failure without silently mutating state.
 *
 * Commit 6 implements the skeleton and the SKILL create/patch handlers;
 * memory/proactive handlers follow in later commits.
 */
@Singleton
class EvolutionApplySaga @Inject constructor(
    private val proposalStore: EvolutionProposalStore,
    private val skillsStore: com.aura.skills.SkillsStore? = null,
    private val skillRevisionStore: EvolutionSkillRevisionStore? = null,
) {
    suspend fun apply(proposal: EvolutionProposalEntity): ApplyResult {
        val action = runCatching { EvolutionAction.valueOf(proposal.action) }.getOrNull()
            ?: return ApplyResult.Error(proposal.id, "unknown action ${proposal.action}")

        return when (action) {
            EvolutionAction.CREATE_SKILL -> applyCreateSkill(proposal)
            EvolutionAction.PATCH_SKILL -> ApplyResult.Error(proposal.id, "not yet implemented: $action")
            EvolutionAction.REWRITE_SKILL -> ApplyResult.Error(proposal.id, "not yet implemented: $action")
            EvolutionAction.MERGE_SKILLS -> ApplyResult.Error(proposal.id, "not yet implemented: $action")
            EvolutionAction.RETIRE_SKILL -> ApplyResult.Error(proposal.id, "not yet implemented: $action")
            EvolutionAction.PROMOTE_TO_HAND -> ApplyResult.Error(proposal.id, "not yet implemented: $action")
            EvolutionAction.PATCH_SPECIALIST_PROMPT -> ApplyResult.Error(proposal.id, "not yet implemented: $action")
            EvolutionAction.ADD_SKILL_EXAMPLE -> ApplyResult.Error(proposal.id, "not yet implemented: $action")
            EvolutionAction.CONSOLIDATE_MEMORIES -> ApplyResult.Error(proposal.id, "not yet implemented: $action")
            EvolutionAction.FORGET_MEMORY -> ApplyResult.Error(proposal.id, "not yet implemented: $action")
            EvolutionAction.UPDATE_MEMORY_CATEGORY -> ApplyResult.Error(proposal.id, "not yet implemented: $action")
            EvolutionAction.MERGE_MEMORIES -> ApplyResult.Error(proposal.id, "not yet implemented: $action")
            EvolutionAction.CREATE_BELIEF -> ApplyResult.Error(proposal.id, "not yet implemented: $action")
            EvolutionAction.UPDATE_BELIEF -> ApplyResult.Error(proposal.id, "not yet implemented: $action")
            EvolutionAction.RETIRE_BELIEF -> ApplyResult.Error(proposal.id, "not yet implemented: $action")
            EvolutionAction.NEW_PROACTIVE_RULE -> ApplyResult.Error(proposal.id, "not yet implemented: $action")
            EvolutionAction.ADJUST_RULE_TIMING -> ApplyResult.Error(proposal.id, "not yet implemented: $action")
            EvolutionAction.DISABLE_RULE -> ApplyResult.Error(proposal.id, "not yet implemented: $action")
            EvolutionAction.ENABLE_RULE -> ApplyResult.Error(proposal.id, "not yet implemented: $action")
            EvolutionAction.REWRITE_RULE_MESSAGE -> ApplyResult.Error(proposal.id, "not yet implemented: $action")
        }
    }

    private suspend fun applyCreateSkill(proposal: EvolutionProposalEntity): ApplyResult {
        val skill = runCatching {
            Json.decodeFromString<com.aura.skills.Skill>(proposal.patchJson)
        }.getOrNull() ?: return ApplyResult.Error(proposal.id, "patchJson is not a valid Skill")
        skillsStore?.add(skill) ?: return ApplyResult.Error(proposal.id, "SkillsStore not available")
        skillRevisionStore?.snapshot(skill, proposal.id, "created by evolution")
        proposalStore.markApplied(proposal.id, "created skill ${skill.name}")
        return ApplyResult.Ok(proposal.id, "created skill ${skill.name}")
    }

    sealed interface ApplyResult {
        data class Ok(val proposalId: kotlin.String, val summary: kotlin.String) : ApplyResult
        data class Error(val proposalId: kotlin.String, val message: kotlin.String) : ApplyResult
        data class NotYetImplemented(val action: kotlin.String) : ApplyResult
    }
}
