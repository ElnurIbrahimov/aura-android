package com.aura.evolution

import kotlinx.serialization.json.Json
import javax.inject.Singleton
import javax.inject.Inject

/**
 * Reverts an applied evolution proposal. It restores the plaintext snapshot
 * stored in [EvolutionProposalEntity.rollbackSnapshotJson] when available,
 * and deletes artifacts that were created by the proposal.
 */
@Singleton
class EvolutionRollbackManager @Inject constructor(
    private val proposalDao: EvolutionProposalDao,
    private val revisionDao: EvolutionRevisionDao,
    private val metrics: EvolutionMetrics,
    private val skillsStore: com.aura.skills.SkillsStore? = null,
    private val memoryStore: com.aura.memory.MemoryStore? = null,
    private val proactiveEventDao: com.aura.proactive.ProactiveEventDao? = null,
    private val handRepository: com.aura.hands.HandRepository? = null,
    private val beliefDao: com.aura.world.BeliefDao? = null,
    private val userPreferences: com.aura.data.UserPreferences? = null,
) {
    private val json = Json { ignoreUnknownKeys = true }

    private companion object {
        private const val TAG = "EvolutionRollback"
    }

    suspend fun rollback(proposalId: kotlin.String): RollbackResult {
        val proposal = proposalDao.getById(proposalId)
            ?: return RollbackResult.Error("proposal not found")
        if (proposal.status != ProposalStatus.APPLIED.name) {
            return RollbackResult.Error("proposal is ${proposal.status}, not applied")
        }
        val conflict = proposalDao.open()
            .filter { it.domain == proposal.domain && it.targetId == proposal.targetId && it.id != proposalId && it.status == ProposalStatus.APPLIED.name }
            .maxByOrNull { it.updatedAt }
        if (conflict != null) {
            return RollbackResult.Conflict(
                message = "newer applied proposal ${conflict.id} conflicts with rollback",
                newerProposalId = conflict.id,
            )
        }
        val artifactResult = restoreArtifact(proposal)
        proposalDao.resolve(proposalId, ProposalStatus.ROLLED_BACK.name, "rolled back")
        metrics.record("proposal.rolled_back")
        return artifactResult
    }

    suspend fun forceRollback(proposalId: kotlin.String): RollbackResult {
        val proposal = proposalDao.getById(proposalId)
            ?: return RollbackResult.Error("proposal not found")
        if (proposal.status != ProposalStatus.APPLIED.name) {
            return RollbackResult.Error("proposal is ${proposal.status}, not applied")
        }
        val artifactResult = restoreArtifact(proposal)
        proposalDao.resolve(proposalId, ProposalStatus.ROLLED_BACK.name, "force-rolled back")
        metrics.record("proposal.force_rolled_back")
        return artifactResult
    }

    private suspend fun restoreArtifact(proposal: EvolutionProposalEntity): RollbackResult {
        val action = runCatching { EvolutionAction.valueOf(proposal.action) }.onFailure { android.util.Log.w(TAG, "rollback: parse action failed: ${it.message}") }.getOrNull()
            ?: return RollbackResult.Error("unknown action ${proposal.action}")
        return when (action) {
            EvolutionAction.CREATE_SKILL -> {
                skillsStore?.remove(proposal.targetId)
                RollbackResult.Ok("removed created skill")
            }
            EvolutionAction.PATCH_SKILL, EvolutionAction.REWRITE_SKILL -> {
                val snapshot = proposal.rollbackSnapshotJson.takeIf { it.isNotBlank() && it != "{}" }
                    ?: return RollbackResult.Error("no rollback snapshot")
                val skill = runCatching { json.decodeFromString<com.aura.skills.Skill>(snapshot) }.onFailure { android.util.Log.w(TAG, "rollback: decode Skill snapshot failed: ${it.message}") }.getOrNull()
                    ?: return RollbackResult.Error("snapshot is not a valid Skill")
                skillsStore?.update(skill) ?: return RollbackResult.Error("SkillsStore not available")
                RollbackResult.Ok("restored skill ${skill.name}")
            }
            EvolutionAction.RETIRE_SKILL -> {
                val snapshot = proposal.rollbackSnapshotJson.takeIf { it.isNotBlank() && it != "{}" }
                    ?: return RollbackResult.Error("no rollback snapshot")
                val skill = runCatching { json.decodeFromString<com.aura.skills.Skill>(snapshot) }.onFailure { android.util.Log.w(TAG, "rollback: decode retired Skill snapshot failed: ${it.message}") }.getOrNull()
                    ?: return RollbackResult.Error("snapshot is not a valid Skill")
                skillsStore?.add(skill) ?: return RollbackResult.Error("SkillsStore not available")
                RollbackResult.Ok("restored retired skill ${skill.name}")
            }
            EvolutionAction.UPDATE_MEMORY_CATEGORY -> {
                val snapshot = proposal.rollbackSnapshotJson.takeIf { it.isNotBlank() && it != "{}" }
                    ?: return RollbackResult.Error("no rollback snapshot")
                val mem = runCatching { json.decodeFromString<com.aura.memory.MemoryEntity>(snapshot) }.getOrNull()
                    ?: return RollbackResult.Error("snapshot is not a valid MemoryEntity")
                memoryStore?.update(mem.id, mem.content, mem.category, mem.importance, mem.tags)
                    ?: return RollbackResult.Error("MemoryStore not available")
                RollbackResult.Ok("restored memory category")
            }
            EvolutionAction.FORGET_MEMORY -> {
                val snapshot = proposal.rollbackSnapshotJson.takeIf { it.isNotBlank() && it != "{}" }
                if (snapshot != null) {
                    val mem = runCatching { json.decodeFromString<com.aura.memory.MemoryEntity>(snapshot) }.onFailure { android.util.Log.w(TAG, "rollback: decode forgotten MemoryEntity snapshot failed: ${it.message}") }.getOrNull()
                    if (mem != null) memoryStore?.restore(mem)
                }
                RollbackResult.Ok("restored forgotten memory")
            }
            EvolutionAction.NEW_PROACTIVE_RULE -> {
                proactiveEventDao?.deleteByCorrelationTag("evolution:${proposal.id}")
                RollbackResult.Ok("removed created proactive rule")
            }
            EvolutionAction.MERGE_SKILLS -> {
                // Restore the target skill to its pre-merge state.
                // The source skill was deleted by the apply saga and is
                // NOT in the rollback snapshot — we can only restore the
                // target, not re-create the source. This is a known
                // limitation; the user can manually re-create the source.
                val snapshot = proposal.rollbackSnapshotJson.takeIf { it.isNotBlank() && it != "{}" }
                    ?: return RollbackResult.Error("no rollback snapshot (source skill cannot be restored)")
                val skill = runCatching { json.decodeFromString<com.aura.skills.Skill>(snapshot) }.onFailure { android.util.Log.w(TAG, "rollback: decode merged Skill snapshot failed: ${it.message}") }.getOrNull()
                    ?: return RollbackResult.Error("snapshot is not a valid Skill")
                skillsStore?.update(skill) ?: return RollbackResult.Error("SkillsStore not available")
                RollbackResult.Ok("restored target skill ${skill.name} (source skill was deleted and cannot be auto-restored)")
            }
            EvolutionAction.PROMOTE_TO_HAND -> {
                // The apply saga created a Hand with name "from_skill_<name>".
                // We can't identify it by ID (the ID was random) so we
                // delete by name pattern. Best-effort — if the user
                // renamed the hand, it won't be found.
                handRepository ?: return RollbackResult.Error("HandRepository not available")
                val skillName = proposal.targetId
                val hands = handRepository.getAll()
                val created = hands.firstOrNull { it.name == "from_skill_$skillName" }
                if (created != null) {
                    handRepository.deleteById(created.id)
                    RollbackResult.Ok("removed hand '${created.name}' created from skill $skillName")
                } else {
                    RollbackResult.Error("hand created from skill $skillName not found (may have been renamed or deleted)")
                }
            }
            EvolutionAction.PATCH_SPECIALIST_PROMPT -> {
                // The apply saga changed a specialist's prompt override.
                // The rollback snapshot stores the entire specialistOverrides
                // JSON map before the change.
                userPreferences ?: return RollbackResult.Error("UserPreferences not available")
                val snapshot = proposal.rollbackSnapshotJson.takeIf { it.isNotBlank() && it != "{}" }
                if (snapshot != null) {
                    userPreferences.setSpecialistOverrides(snapshot)
                    RollbackResult.Ok("restored specialist prompt overrides")
                } else {
                    RollbackResult.Error("no rollback snapshot for specialist prompt")
                }
            }
            EvolutionAction.ADD_SKILL_EXAMPLE -> {
                // The apply saga appended an example block to the skill body.
                // Restore the skill from the pre-modification snapshot.
                val snapshot = proposal.rollbackSnapshotJson.takeIf { it.isNotBlank() && it != "{}" }
                    ?: return RollbackResult.Error("no rollback snapshot")
                val skill = runCatching { json.decodeFromString<com.aura.skills.Skill>(snapshot) }.onFailure { android.util.Log.w(TAG, "rollback: decode example-added Skill snapshot failed: ${it.message}") }.getOrNull()
                    ?: return RollbackResult.Error("snapshot is not a valid Skill")
                skillsStore?.update(skill) ?: return RollbackResult.Error("SkillsStore not available")
                RollbackResult.Ok("restored skill ${skill.name} (removed added example)")
            }
            EvolutionAction.CONSOLIDATE_MEMORIES -> {
                // The apply saga stored a consolidated memory and forgot the
                // sources. We can delete the consolidated memory, but the
                // sources were forgotten and their snapshots aren't in
                // this proposal's rollback snapshot. Best-effort: delete
                // the consolidated memory so the user can manually re-add
                // the originals if needed.
                memoryStore ?: return RollbackResult.Error("MemoryStore not available")
                val args = runCatching {
                    json.decodeFromString<Map<String, String>>(proposal.patchJson)
                }.getOrDefault(emptyMap())
                val consolidatedContent = args["consolidatedContent"]
                if (consolidatedContent != null) {
                    // Find and delete the consolidated memory by content match.
                    val recent = memoryStore.recent(100)
                    val match = recent.firstOrNull { it.content == consolidatedContent }
                    if (match != null) {
                        memoryStore.forget(match.id)
                        RollbackResult.Ok("removed consolidated memory (original sources were forgotten and cannot be auto-restored)")
                    } else {
                        RollbackResult.Error("consolidated memory not found (may have been edited or already removed)")
                    }
                } else {
                    RollbackResult.Error("cannot identify consolidated memory (missing consolidatedContent in patch)")
                }
            }
            EvolutionAction.MERGE_MEMORIES -> {
                // The apply saga merged source into target, then forgot source.
                // Restore target from snapshot. Source cannot be auto-restored.
                val snapshot = proposal.rollbackSnapshotJson.takeIf { it.isNotBlank() && it != "{}" }
                    ?: return RollbackResult.Error("no rollback snapshot (source memory cannot be restored)")
                val mem = runCatching { json.decodeFromString<com.aura.memory.MemoryEntity>(snapshot) }.onFailure { android.util.Log.w(TAG, "rollback: decode merged MemoryEntity snapshot failed: ${it.message}") }.getOrNull()
                    ?: return RollbackResult.Error("snapshot is not a valid MemoryEntity")
                memoryStore?.update(mem.id, mem.content, mem.category, mem.importance, mem.tags)
                    ?: return RollbackResult.Error("MemoryStore not available")
                RollbackResult.Ok("restored target memory ${mem.id} (source memory was forgotten and cannot be auto-restored)")
            }
            EvolutionAction.CREATE_BELIEF -> {
                // The apply saga created a new belief. Delete it.
                beliefDao ?: return RollbackResult.Error("BeliefDao not available")
                val args = runCatching {
                    json.decodeFromString<Map<String, String>>(proposal.patchJson)
                }.getOrDefault(emptyMap())
                val subject = args["subject"] ?: "user"
                val predicate = args["predicate"] ?: "property"
                // Find the belief by subject+predicate (the ID was random).
                val beliefs = beliefDao.allForBackup()
                val match = beliefs.firstOrNull { it.subject == subject && it.predicate == predicate }
                if (match != null) {
                    beliefDao.supersede(match.id, "rolled_back", "", System.currentTimeMillis())
                    RollbackResult.Ok("retired belief $subject:$predicate (created by evolution)")
                } else {
                    RollbackResult.Error("belief $subject:$predicate not found (may have been already retired)")
                }
            }
            EvolutionAction.UPDATE_BELIEF -> {
                // The apply saga updated a belief's value. Restore from snapshot.
                beliefDao ?: return RollbackResult.Error("BeliefDao not available")
                val snapshot = proposal.rollbackSnapshotJson.takeIf { it.isNotBlank() && it != "{}" }
                    ?: return RollbackResult.Error("no rollback snapshot")
                // The snapshot is a JSON object with id, subject, predicate,
                // valueJson, confidence, status.
                val snap = runCatching {
                    json.decodeFromString<Map<String, String>>(snapshot)
                }.onFailure { android.util.Log.w(TAG, "rollback: decode belief snapshot failed: ${it.message}") }.getOrNull() ?: return RollbackResult.Error("snapshot is not a valid belief JSON")
                val beliefId = snap["id"] ?: return RollbackResult.Error("snapshot missing belief id")
                val existing = beliefDao.getById(beliefId)
                if (existing != null) {
                    beliefDao.upsert(existing.copy(
                        valueJson = snap["valueJson"] ?: existing.valueJson,
                        updatedAt = System.currentTimeMillis(),
                    ))
                    RollbackResult.Ok("restored belief ${snap["subject"]}:${snap["predicate"]}")
                } else {
                    RollbackResult.Error("belief $beliefId not found")
                }
            }
            EvolutionAction.RETIRE_BELIEF -> {
                // The apply saga superseded the belief. Restore its status.
                beliefDao ?: return RollbackResult.Error("BeliefDao not available")
                val snapshot = proposal.rollbackSnapshotJson.takeIf { it.isNotBlank() && it != "{}" }
                    ?: return RollbackResult.Error("no rollback snapshot")
                val snap = runCatching {
                    json.decodeFromString<Map<String, String>>(snapshot)
                }.onFailure { android.util.Log.w(TAG, "rollback: decode retired belief snapshot failed: ${it.message}") }.getOrNull() ?: return RollbackResult.Error("snapshot is not a valid belief JSON")
                val beliefId = snap["id"] ?: return RollbackResult.Error("snapshot missing belief id")
                val existing = beliefDao.getById(beliefId)
                if (existing != null) {
                    // Restore to active status (clear supersession).
                    beliefDao.upsert(existing.copy(
                        status = snap["status"] ?: "active",
                        supersededBy = "",
                        updatedAt = System.currentTimeMillis(),
                    ))
                    RollbackResult.Ok("restored belief ${snap["subject"]}:${snap["predicate"]} from retired")
                } else {
                    RollbackResult.Error("belief $beliefId not found")
                }
            }
            EvolutionAction.ADJUST_RULE_TIMING -> {
                // The apply saga only recorded a recommendation — no
                // state was changed. Rollback is a no-op.
                RollbackResult.Ok("timing adjustment was a recommendation only (no state to revert)")
            }
            EvolutionAction.DISABLE_RULE -> {
                // The apply saga deleted a proactive event by correlationTag.
                // The original event is gone and was not snapshotted.
                // Best-effort: re-create from the proposal patch if it
                // contains the original rule data.
                val args = runCatching {
                    json.decodeFromString<Map<String, String>>(proposal.patchJson)
                }.getOrDefault(emptyMap())
                val title = args["title"] ?: args["correlationTag"] ?: "restored rule"
                val body = args["body"] ?: ""
                proactiveEventDao?.insert(
                    com.aura.proactive.ProactiveEventEntity(
                        eventType = args["eventType"] ?: "custom",
                        title = title,
                        body = body,
                        timestamp = System.currentTimeMillis(),
                        correlationTag = "evolution:rollback:${proposal.id}",
                    )
                ) ?: return RollbackResult.Error("ProactiveEventDao not available")
                RollbackResult.Ok("re-enabled rule '$title' (restored from proposal patch)")
            }
            EvolutionAction.ENABLE_RULE -> {
                // The apply saga re-inserted a proactive event. Delete it.
                proactiveEventDao?.deleteByCorrelationTag("evolution:${proposal.id}")
                RollbackResult.Ok("removed re-enabled rule (deleted proactive event from evolution:${proposal.id})")
            }
            EvolutionAction.REWRITE_RULE_MESSAGE -> {
                // The apply saga inserted a new proactive event with the
                // rewritten message. Delete it.
                proactiveEventDao?.deleteByCorrelationTag("evolution:${proposal.id}")
                RollbackResult.Ok("removed rewritten rule message (deleted proactive event from evolution:${proposal.id})")
            }
        }
    }

    sealed interface RollbackResult {
        data class Ok(val summary: kotlin.String) : RollbackResult
        data class Error(val message: kotlin.String) : RollbackResult
        data class Conflict(val message: kotlin.String, val newerProposalId: kotlin.String) : RollbackResult
    }
}
