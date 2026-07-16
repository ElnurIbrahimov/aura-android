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
    private val memoryStore: com.aura.memory.MemoryStore? = null,
    private val proactiveEventDao: com.aura.proactive.ProactiveEventDao? = null,
) {
    suspend fun apply(proposal: EvolutionProposalEntity): ApplyResult {
        val action = runCatching { EvolutionAction.valueOf(proposal.action) }.getOrNull()
            ?: return ApplyResult.Error(proposal.id, "unknown action ${proposal.action}")

        return when (action) {
            EvolutionAction.CREATE_SKILL -> applyCreateSkill(proposal)
            EvolutionAction.PATCH_SKILL -> applyPatchSkill(proposal)
            EvolutionAction.REWRITE_SKILL -> applyRewriteSkill(proposal)
            EvolutionAction.MERGE_SKILLS -> ApplyResult.Error(proposal.id, "not yet implemented: $action")
            EvolutionAction.RETIRE_SKILL -> applyRetireSkill(proposal)
            EvolutionAction.PROMOTE_TO_HAND -> ApplyResult.Error(proposal.id, "not yet implemented: $action")
            EvolutionAction.PATCH_SPECIALIST_PROMPT -> ApplyResult.Error(proposal.id, "not yet implemented: $action")
            EvolutionAction.ADD_SKILL_EXAMPLE -> ApplyResult.Error(proposal.id, "not yet implemented: $action")
            EvolutionAction.CONSOLIDATE_MEMORIES -> ApplyResult.Error(proposal.id, "not yet implemented: $action")
            EvolutionAction.FORGET_MEMORY -> applyForgetMemory(proposal)
            EvolutionAction.UPDATE_MEMORY_CATEGORY -> applyUpdateMemoryCategory(proposal)
            EvolutionAction.MERGE_MEMORIES -> ApplyResult.Error(proposal.id, "not yet implemented: $action")
            EvolutionAction.CREATE_BELIEF -> ApplyResult.Error(proposal.id, "not yet implemented: $action")
            EvolutionAction.UPDATE_BELIEF -> ApplyResult.Error(proposal.id, "not yet implemented: $action")
            EvolutionAction.RETIRE_BELIEF -> ApplyResult.Error(proposal.id, "not yet implemented: $action")
            EvolutionAction.NEW_PROACTIVE_RULE -> applyNewProactiveRule(proposal)
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


    private suspend fun applyPatchSkill(proposal: EvolutionProposalEntity): ApplyResult {
        val existing = skillsStore?.findById(proposal.targetId)
            ?: return ApplyResult.Error(proposal.id, "skill not found: ${proposal.targetId}")
        val patch = runCatching {
            Json.decodeFromString<com.aura.skills.Skill>(proposal.patchJson)
        }.getOrNull() ?: return ApplyResult.Error(proposal.id, "patchJson is not a valid Skill")
        val merged = existing.copy(
            name = patch.name.takeIf { it.isNotBlank() } ?: existing.name,
            description = patch.description.takeIf { it.isNotBlank() } ?: existing.description,
            body = patch.body.takeIf { it.isNotBlank() } ?: existing.body,
        )
        skillsStore.update(merged)
        skillRevisionStore?.snapshot(merged, proposal.id, "patched by evolution")
        proposalStore.markApplied(proposal.id, "patched skill ${merged.name}")
        return ApplyResult.Ok(proposal.id, "patched skill ${merged.name}")
    }

    private suspend fun applyRewriteSkill(proposal: EvolutionProposalEntity): ApplyResult {
        val existing = skillsStore?.findById(proposal.targetId)
            ?: return ApplyResult.Error(proposal.id, "skill not found: ${proposal.targetId}")
        val replacement = runCatching {
            Json.decodeFromString<com.aura.skills.Skill>(proposal.patchJson)
        }.getOrNull() ?: return ApplyResult.Error(proposal.id, "patchJson is not a valid Skill")
        val merged = existing.copy(
            name = replacement.name.takeIf { it.isNotBlank() } ?: existing.name,
            description = replacement.description.takeIf { it.isNotBlank() } ?: existing.description,
            body = replacement.body,
        )
        skillsStore.update(merged)
        skillRevisionStore?.snapshot(merged, proposal.id, "rewritten by evolution")
        proposalStore.markApplied(proposal.id, "rewrote skill ${merged.name}")
        return ApplyResult.Ok(proposal.id, "rewrote skill ${merged.name}")
    }

    private suspend fun applyRetireSkill(proposal: EvolutionProposalEntity): ApplyResult {
        val existing = skillsStore?.findById(proposal.targetId)
            ?: return ApplyResult.Error(proposal.id, "skill not found: ${proposal.targetId}")
        skillsStore.remove(existing.id)
        proposalStore.markApplied(proposal.id, "retired skill ${existing.name}")
        return ApplyResult.Ok(proposal.id, "retired skill ${existing.name}")
    }

    private suspend fun applyForgetMemory(proposal: EvolutionProposalEntity): ApplyResult {
        memoryStore?.forget(proposal.targetId)
            ?: return ApplyResult.Error(proposal.id, "MemoryStore not available")
        proposalStore.markApplied(proposal.id, "forgot memory ${proposal.targetId}")
        return ApplyResult.Ok(proposal.id, "forgot memory ${proposal.targetId}")
    }

    private suspend fun applyUpdateMemoryCategory(proposal: EvolutionProposalEntity): ApplyResult {
        val args = runCatching {
            Json.decodeFromString<Map<String, String>>(proposal.patchJson)
        }.getOrDefault(emptyMap())
        val newCategory = args["category"] ?: return ApplyResult.Error(proposal.id, "missing category in patch")
        val mem = memoryStore?.get(proposal.targetId)
            ?: return ApplyResult.Error(proposal.id, "memory not found: ${proposal.targetId}")
        memoryStore.update(mem.id, mem.content, newCategory, mem.importance, mem.tags)
        proposalStore.markApplied(proposal.id, "changed category to $newCategory")
        return ApplyResult.Ok(proposal.id, "changed category to $newCategory")
    }

    private suspend fun applyNewProactiveRule(proposal: EvolutionProposalEntity): ApplyResult {
        val args = runCatching {
            Json.decodeFromString<Map<String, String>>(proposal.patchJson)
        }.getOrDefault(emptyMap())
        val title = args["title"] ?: "New proactive rule"
        val body = args["body"] ?: ""
        val eventType = args["eventType"] ?: "custom"
        proactiveEventDao?.insert(
            com.aura.proactive.ProactiveEventEntity(
                eventType = eventType,
                title = title,
                body = body,
                timestamp = System.currentTimeMillis(),
                correlationTag = "evolution:${proposal.id}",
            )
        ) ?: return ApplyResult.Error(proposal.id, "ProactiveEventDao not available")
        proposalStore.markApplied(proposal.id, "created proactive rule $title")
        return ApplyResult.Ok(proposal.id, "created proactive rule $title")
    }

    sealed interface ApplyResult {
        data class Ok(val proposalId: kotlin.String, val summary: kotlin.String) : ApplyResult
        data class Error(val proposalId: kotlin.String, val message: kotlin.String) : ApplyResult
        data class NotYetImplemented(val action: kotlin.String) : ApplyResult
    }
}
