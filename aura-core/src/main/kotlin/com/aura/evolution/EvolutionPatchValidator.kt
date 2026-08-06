package com.aura.evolution

import com.aura.agent.ToolRegistry
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

/**
 * Schema + safety validation for LLM-authored evolution patches. A patch that
 * fails validation means the candidate is REJECTED — an invalid patch is never
 * promoted to a proposal, so the apply saga can trust `patchJson`.
 *
 * Safety properties enforced here:
 * - `"{}"` and every missing-required-field variant is rejected per action
 *   (the regression class that made the old evolution system a no-op).
 * - [SkillPatch] body must be non-blank, different from the current body,
 *   within length caps, and free of credential leaks.
 * - [PromoteToHandPatch] steps may only reference tools that exist in the
 *   [ToolRegistry] right now.
 * - [ConsolidateMemoriesPatch] may only delete memory ids that were SHOWN to
 *   the model (blocks hallucinated deletions), needs ≥2 ids, and must include
 *   the candidate's targetId.
 */
@Singleton
class EvolutionPatchValidator @Inject constructor(
    // Provider<> breaks the Dagger cycle: ToolRegistry ← TriggerEvolutionRunTool
    // ← EvolutionCoordinator ← EvolutionPatchAuthor ← EvolutionPatchValidator.
    private val toolRegistry: Provider<ToolRegistry>,
    private val safetyGuard: EvolutionSafetyGuard,
) {
    private val json = EvolutionPatchJson.json

    data class Context(
        val targetId: String,
        /** Current skill body for PATCH_SKILL (patch must differ from it). */
        val currentSkillBody: String? = null,
        /** Memory ids shown to the model for CONSOLIDATE_MEMORIES. */
        val shownMemoryIds: Set<String> = emptySet(),
    )

    sealed interface Result {
        /** Patch is valid; [canonicalJson] is the re-encoded typed form. */
        data class Valid(val canonicalJson: String) : Result
        data class Invalid(val reason: String) : Result
    }

    fun validate(action: EvolutionAction, patchJson: String, context: Context): Result {
        val raw = patchJson.trim()
        if (raw.isBlank() || raw == "{}") return Result.Invalid("empty patch")
        return when (action) {
            EvolutionAction.PATCH_SKILL -> validatePatchSkill(raw, context)
            EvolutionAction.RETIRE_SKILL -> validateRetireSkill(raw)
            EvolutionAction.PROMOTE_TO_HAND -> validatePromoteToHand(raw)
            EvolutionAction.CONSOLIDATE_MEMORIES -> validateConsolidateMemories(raw, context)
        }
    }

    private fun validatePatchSkill(raw: String, context: Context): Result {
        val patch = runCatching { json.decodeFromString<SkillPatch>(raw) }.getOrNull()
            ?: return Result.Invalid("patch is not valid SkillPatch JSON")
        if (patch.body.isBlank()) return Result.Invalid("skill body is blank")
        if (patch.body.length > MAX_SKILL_BODY_CHARS) {
            return Result.Invalid("skill body exceeds $MAX_SKILL_BODY_CHARS chars")
        }
        val description = patch.description
        if (description != null && description.length > MAX_SKILL_DESCRIPTION_CHARS) {
            return Result.Invalid("skill description exceeds $MAX_SKILL_DESCRIPTION_CHARS chars")
        }
        val current = context.currentSkillBody
        if (current != null && patch.body.trim() == current.trim()) {
            return Result.Invalid("patched body is identical to the current body")
        }
        if (safetyGuard.containsCredentialLeak(patch.body) ||
            (description != null && safetyGuard.containsCredentialLeak(description))
        ) {
            return Result.Invalid("credential leak detected in skill patch")
        }
        return Result.Valid(json.encodeToString(SkillPatch.serializer(), patch))
    }

    private fun validateRetireSkill(raw: String): Result {
        val patch = runCatching { json.decodeFromString<RetireSkillPatch>(raw) }.getOrNull()
            ?: return Result.Invalid("patch is not valid RetireSkillPatch JSON")
        if (patch.reason.isBlank()) return Result.Invalid("retire reason is blank")
        return Result.Valid(json.encodeToString(RetireSkillPatch.serializer(), patch))
    }

    private fun validatePromoteToHand(raw: String): Result {
        val patch = runCatching { json.decodeFromString<PromoteToHandPatch>(raw) }.getOrNull()
            ?: return Result.Invalid("patch is not valid PromoteToHandPatch JSON")
        if (patch.handName.isBlank()) return Result.Invalid("handName is blank")
        if (patch.handName.length > MAX_HAND_NAME_CHARS) {
            return Result.Invalid("handName exceeds $MAX_HAND_NAME_CHARS chars")
        }
        if (patch.steps.isEmpty()) return Result.Invalid("steps is empty")
        if (patch.steps.size > MAX_HAND_STEPS) {
            return Result.Invalid("more than $MAX_HAND_STEPS steps")
        }
        val known = toolRegistry.get().names().toSet()
        for (step in patch.steps) {
            if (step.tool.isBlank()) return Result.Invalid("step has blank tool name")
            if (step.tool !in known) {
                return Result.Invalid("step tool '${step.tool}' does not exist in the ToolRegistry")
            }
        }
        return Result.Valid(json.encodeToString(PromoteToHandPatch.serializer(), patch))
    }

    private fun validateConsolidateMemories(raw: String, context: Context): Result {
        val patch = runCatching { json.decodeFromString<ConsolidateMemoriesPatch>(raw) }.getOrNull()
            ?: return Result.Invalid("patch is not valid ConsolidateMemoriesPatch JSON")
        val ids = patch.memoryIds.distinct()
        if (ids.size < 2) return Result.Invalid("memoryIds must contain at least 2 distinct ids")
        if (context.targetId.isNotBlank() && context.targetId !in ids) {
            return Result.Invalid("memoryIds must include the candidate target ${context.targetId}")
        }
        val hallucinated = ids.filterNot { it in context.shownMemoryIds }
        if (hallucinated.isNotEmpty()) {
            return Result.Invalid(
                "memoryIds contains ids never shown to the model: ${hallucinated.take(3).joinToString(", ")}"
            )
        }
        if (patch.consolidatedContent.isBlank()) return Result.Invalid("consolidatedContent is blank")
        if (patch.consolidatedContent.length > MAX_CONSOLIDATED_CHARS) {
            return Result.Invalid("consolidatedContent exceeds $MAX_CONSOLIDATED_CHARS chars")
        }
        if (safetyGuard.containsCredentialLeak(patch.consolidatedContent)) {
            return Result.Invalid("credential leak detected in consolidated content")
        }
        return Result.Valid(
            json.encodeToString(ConsolidateMemoriesPatch.serializer(), patch.copy(memoryIds = ids))
        )
    }

    private companion object {
        const val MAX_SKILL_BODY_CHARS = 24_000
        const val MAX_SKILL_DESCRIPTION_CHARS = 240
        const val MAX_HAND_NAME_CHARS = 80
        const val MAX_HAND_STEPS = 20
        const val MAX_CONSOLIDATED_CHARS = 8_000
    }
}
