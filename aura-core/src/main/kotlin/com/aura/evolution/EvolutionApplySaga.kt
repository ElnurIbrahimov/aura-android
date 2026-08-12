package com.aura.evolution

import com.aura.hands.Hand
import com.aura.hands.HandRepository
import com.aura.hands.HandStep
import com.aura.skills.Skill
import kotlinx.serialization.json.JsonArray
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Applies an approved evolution proposal. Each of the four producible
 * [EvolutionAction]s has a dedicated handler that decodes the TYPED patch
 * ([EvolutionPatches]) and records a complete rollback snapshot (D7) before
 * mutating anything, so [EvolutionRollbackManager] can perform an exact
 * inverse.
 */
@Singleton
class EvolutionApplySaga @Inject constructor(
    private val proposalStore: EvolutionProposalStore,
    private val skillsStore: com.aura.skills.SkillsStore? = null,
    private val skillRevisionStore: EvolutionSkillRevisionStore? = null,
    private val memoryStore: com.aura.memory.MemoryStore? = null,
    private val handRepository: HandRepository? = null,
) {
    private val json = EvolutionPatchJson.json

    private companion object {
        private const val TAG = "EvolutionApplySaga"
    }

    suspend fun apply(proposal: EvolutionProposalEntity): ApplyResult {
        val action = runCatching { EvolutionAction.valueOf(proposal.action) }
            .onFailure { android.util.Log.w(TAG, "apply: parse action failed: ${it.message}", it) }
            .getOrNull()
            ?: return ApplyResult.Error(proposal.id, "unknown action ${proposal.action}")

        return when (action) {
            EvolutionAction.PATCH_SKILL -> applyPatchSkill(proposal)
            EvolutionAction.RETIRE_SKILL -> applyRetireSkill(proposal)
            EvolutionAction.PROMOTE_TO_HAND -> applyPromoteToHand(proposal)
            EvolutionAction.CONSOLIDATE_MEMORIES -> applyConsolidateMemories(proposal)
        }
    }

    // ── Skill handlers ──────────────────────────────────────────

    private suspend fun applyPatchSkill(proposal: EvolutionProposalEntity): ApplyResult {
        val store = skillsStore ?: return ApplyResult.Error(proposal.id, "SkillsStore not available")
        store.awaitLoaded()
        val existing = store.findById(proposal.targetId)
            ?: return ApplyResult.Error(proposal.id, "skill not found: ${proposal.targetId}")
        val patch = runCatching { json.decodeFromString<SkillPatch>(proposal.patchJson) }
            .onFailure { android.util.Log.w(TAG, "apply: decode SkillPatch failed: ${it.message}", it) }
            .getOrNull()
            ?: return ApplyResult.Error(proposal.id, "patchJson is not a valid SkillPatch")
        if (patch.body.isBlank()) return ApplyResult.Error(proposal.id, "SkillPatch body is blank")
        // Snapshot the full pre-patch skill so rollback restores it exactly.
        proposalStore.recordRollbackSnapshot(proposal.id, json.encodeToString(Skill.serializer(), existing))
        val merged = existing.copy(
            description = patch.description?.takeIf { it.isNotBlank() } ?: existing.description,
            body = patch.body,
            updatedAt = System.currentTimeMillis(),
        )
        store.update(merged)
        skillRevisionStore?.snapshot(merged, proposal.id, "patched by evolution")
        proposalStore.markApplied(proposal.id, "patched skill ${merged.name}")
        return ApplyResult.Ok(proposal.id, "patched skill ${merged.name}")
    }

    private suspend fun applyRetireSkill(proposal: EvolutionProposalEntity): ApplyResult {
        val store = skillsStore ?: return ApplyResult.Error(proposal.id, "SkillsStore not available")
        store.awaitLoaded()
        val existing = store.findById(proposal.targetId)
            ?: return ApplyResult.Error(proposal.id, "skill not found: ${proposal.targetId}")
        // Snapshot the full skill so rollback re-adds it exactly.
        proposalStore.recordRollbackSnapshot(proposal.id, json.encodeToString(Skill.serializer(), existing))
        store.remove(existing.id)
        proposalStore.markApplied(proposal.id, "retired skill ${existing.name}")
        return ApplyResult.Ok(proposal.id, "retired skill ${existing.name}")
    }

    private suspend fun applyPromoteToHand(proposal: EvolutionProposalEntity): ApplyResult {
        val store = skillsStore ?: return ApplyResult.Error(proposal.id, "SkillsStore not available")
        store.awaitLoaded()
        val skill = store.findById(proposal.targetId)
            ?: return ApplyResult.Error(proposal.id, "skill not found: ${proposal.targetId}")
        val repo = handRepository ?: return ApplyResult.Error(proposal.id, "HandRepository not available")
        val patch = runCatching { json.decodeFromString<PromoteToHandPatch>(proposal.patchJson) }
            .onFailure { android.util.Log.w(TAG, "apply: decode PromoteToHandPatch failed: ${it.message}", it) }
            .getOrNull()
            ?: return ApplyResult.Error(proposal.id, "patchJson is not a valid PromoteToHandPatch")
        if (patch.handName.isBlank() || patch.steps.isEmpty()) {
            return ApplyResult.Error(proposal.id, "PromoteToHandPatch missing handName or steps")
        }
        val handId = UUID.randomUUID().toString()
        // REAL steps from the authored patch — not "[]".
        val stepsJson = JsonArray(patch.steps.map { HandStep(it.tool, it.args).toJsonObject() }).toString()
        val hand = Hand(
            id = handId,
            name = patch.handName,
            triggerPhrase = patch.triggerPhrase.orEmpty(),
            steps = stepsJson,
            enabled = true,
        )
        // D7: record the created hand id BEFORE inserting so rollback deletes
        // exactly this hand even if the user later renames it.
        proposalStore.recordRollbackSnapshot(
            proposal.id,
            json.encodeToString(PromoteToHandSnapshot.serializer(), PromoteToHandSnapshot(handId, patch.handName)),
        )
        repo.insert(hand)
        proposalStore.markApplied(proposal.id, "promoted skill ${skill.name} to hand '${patch.handName}'")
        return ApplyResult.Ok(proposal.id, "promoted skill ${skill.name} to hand '${patch.handName}'")
    }

    // ── Memory handler ──────────────────────────────────────────

    private suspend fun applyConsolidateMemories(proposal: EvolutionProposalEntity): ApplyResult {
        val store = memoryStore ?: return ApplyResult.Error(proposal.id, "MemoryStore not available")
        val patch = runCatching { json.decodeFromString<ConsolidateMemoriesPatch>(proposal.patchJson) }
            .onFailure { android.util.Log.w(TAG, "apply: decode ConsolidateMemoriesPatch failed: ${it.message}", it) }
            .getOrNull()
            ?: return ApplyResult.Error(proposal.id, "patchJson is not a valid ConsolidateMemoriesPatch")
        if (patch.memoryIds.size < 2) return ApplyResult.Error(proposal.id, "fewer than 2 memoryIds in patch")
        if (patch.consolidatedContent.isBlank()) return ApplyResult.Error(proposal.id, "consolidatedContent is blank")
        // D7: fetch the FULL source entities up front. Only the ones that
        // still exist are retired, and exactly those are snapshotted.
        val sources = patch.memoryIds.distinct().mapNotNull { id -> store.get(id) }
        if (sources.size < 2) {
            return ApplyResult.Error(proposal.id, "fewer than 2 source memories still exist")
        }
        // A consolidation that spans scopes is refused rather than widened.
        // The old behaviour stored the merged memory in "general", which is
        // visible to every agent — so merging a private project memory with a
        // general one silently broadened who could recall it. There is no
        // correct scope for that merge, and inventing one is a disclosure, not
        // a default.
        val sourceScopes = sources.map { it.scope }.toSet()
        if (sourceScopes.size > 1) {
            return ApplyResult.Error(
                proposal.id,
                "sources span ${sourceScopes.size} scopes ($sourceScopes); refusing to widen to 'general'",
            )
        }
        val targetScope = sourceScopes.first()
        // Snapshot BEFORE the state change, so a crash between the two leaves a
        // rollback that describes work which never happened rather than work
        // that happened and cannot be undone.
        proposalStore.recordRollbackSnapshot(
            proposal.id,
            json.encodeToString(
                ConsolidateMemoriesSnapshot.serializer(),
                ConsolidateMemoriesSnapshot(consolidatedMemoryId = "", sources = sources),
            ),
        )
        val storedId = runCatching {
            store.consolidate(
                sources = sources,
                content = patch.consolidatedContent,
                category = patch.category ?: "consolidated",
            )
        }.onFailure { android.util.Log.w(TAG, "apply: consolidate failed: ${it.message}", it) }
            .getOrNull()
            ?: return ApplyResult.Error(proposal.id, "consolidated content was not stored")
        // Re-record now that the consolidated id exists. Rollback needs it to
        // remove the replacement, and it is unknowable before the write.
        proposalStore.recordRollbackSnapshot(
            proposal.id,
            json.encodeToString(
                ConsolidateMemoriesSnapshot.serializer(),
                ConsolidateMemoriesSnapshot(consolidatedMemoryId = storedId, sources = sources),
            ),
        )
        proposalStore.markApplied(proposal.id, "consolidated ${sources.size} memories into scope '$targetScope'")
        return ApplyResult.Ok(proposal.id, "consolidated ${sources.size} memories into scope '$targetScope'")
    }

    sealed interface ApplyResult {
        data class Ok(val proposalId: kotlin.String, val summary: kotlin.String) : ApplyResult
        data class Error(val proposalId: kotlin.String, val message: kotlin.String) : ApplyResult
    }
}
