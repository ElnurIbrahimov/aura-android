package com.aura.evolution


import com.aura.data.UserPreferences
import com.aura.hands.Hand
import com.aura.hands.HandRepository
import com.aura.memory.MemoryEntity
import com.aura.memory.MemoryStore
import com.aura.proactive.ProactiveEventDao
import com.aura.skills.Skill
import com.aura.world.BeliefDao
import com.aura.world.BeliefEntity
import com.aura.world.EvidenceDao
import com.aura.world.EvidenceEntity
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Applies an approved evolution proposal. Each [EvolutionAction] has a
 * dedicated handler. The saga records the attempt, creates a revision,
 * and returns success/failure without silently mutating state.
 */
@Singleton
class EvolutionApplySaga @Inject constructor(
    private val proposalStore: EvolutionProposalStore,
    private val skillsStore: com.aura.skills.SkillsStore? = null,
    private val skillRevisionStore: EvolutionSkillRevisionStore? = null,
    private val memoryStore: com.aura.memory.MemoryStore? = null,
    private val proactiveEventDao: com.aura.proactive.ProactiveEventDao? = null,
    private val beliefDao: BeliefDao? = null,
    private val evidenceDao: EvidenceDao? = null,
    private val handRepository: HandRepository? = null,
    private val userPreferences: UserPreferences? = null,
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun apply(proposal: EvolutionProposalEntity): ApplyResult {
        val action = runCatching { EvolutionAction.valueOf(proposal.action) }.getOrNull()
            ?: return ApplyResult.Error(proposal.id, "unknown action ${proposal.action}")

        return when (action) {
            EvolutionAction.CREATE_SKILL -> applyCreateSkill(proposal)
            EvolutionAction.PATCH_SKILL -> applyPatchSkill(proposal)
            EvolutionAction.REWRITE_SKILL -> applyRewriteSkill(proposal)
            EvolutionAction.MERGE_SKILLS -> applyMergeSkills(proposal)
            EvolutionAction.RETIRE_SKILL -> applyRetireSkill(proposal)
            EvolutionAction.PROMOTE_TO_HAND -> applyPromoteToHand(proposal)
            EvolutionAction.PATCH_SPECIALIST_PROMPT -> applyPatchSpecialistPrompt(proposal)
            EvolutionAction.ADD_SKILL_EXAMPLE -> applyAddSkillExample(proposal)
            EvolutionAction.CONSOLIDATE_MEMORIES -> applyConsolidateMemories(proposal)
            EvolutionAction.FORGET_MEMORY -> applyForgetMemory(proposal)
            EvolutionAction.UPDATE_MEMORY_CATEGORY -> applyUpdateMemoryCategory(proposal)
            EvolutionAction.MERGE_MEMORIES -> applyMergeMemories(proposal)
            EvolutionAction.CREATE_BELIEF -> applyCreateBelief(proposal)
            EvolutionAction.UPDATE_BELIEF -> applyUpdateBelief(proposal)
            EvolutionAction.RETIRE_BELIEF -> applyRetireBelief(proposal)
            EvolutionAction.NEW_PROACTIVE_RULE -> applyNewProactiveRule(proposal)
            EvolutionAction.ADJUST_RULE_TIMING -> applyAdjustRuleTiming(proposal)
            EvolutionAction.DISABLE_RULE -> applyDisableRule(proposal)
            EvolutionAction.ENABLE_RULE -> applyEnableRule(proposal)
            EvolutionAction.REWRITE_RULE_MESSAGE -> applyRewriteRuleMessage(proposal)
        }
    }

    // ── Skill handlers ──────────────────────────────────────────

    private suspend fun applyCreateSkill(proposal: EvolutionProposalEntity): ApplyResult {
        val skill = runCatching {
            json.decodeFromString<Skill>(proposal.patchJson)
        }.getOrNull() ?: return ApplyResult.Error(proposal.id, "patchJson is not a valid Skill")
        skillsStore?.add(skill) ?: return ApplyResult.Error(proposal.id, "SkillsStore not available")
        skillRevisionStore?.snapshot(skill, proposal.id, "created by evolution")
        proposalStore.markApplied(proposal.id, "created skill ${skill.name}")
        return ApplyResult.Ok(proposal.id, "created skill ${skill.name}")
    }

    private suspend fun applyPatchSkill(proposal: EvolutionProposalEntity): ApplyResult {
        val existing = skillsStore?.findById(proposal.targetId)
            ?: return ApplyResult.Error(proposal.id, "skill not found: ${proposal.targetId}")
        proposalStore.recordRollbackSnapshot(proposal.id, json.encodeToString(Skill.serializer(), existing))
        val patch = runCatching {
            json.decodeFromString<Skill>(proposal.patchJson)
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
        proposalStore.recordRollbackSnapshot(proposal.id, json.encodeToString(Skill.serializer(), existing))
        val replacement = runCatching {
            json.decodeFromString<Skill>(proposal.patchJson)
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

    private suspend fun applyMergeSkills(proposal: EvolutionProposalEntity): ApplyResult {
        val args = runCatching {
            json.decodeFromString<Map<String, String>>(proposal.patchJson)
        }.getOrDefault(emptyMap())
        val sourceId = args["sourceId"] ?: return ApplyResult.Error(proposal.id, "missing sourceId")
        val targetId = args["targetId"] ?: proposal.targetId
        val source = skillsStore?.findById(sourceId)
            ?: return ApplyResult.Error(proposal.id, "source skill not found: $sourceId")
        val target = skillsStore?.findById(targetId)
            ?: return ApplyResult.Error(proposal.id, "target skill not found: $targetId")
        proposalStore.recordRollbackSnapshot(proposal.id, json.encodeToString(Skill.serializer(), target))
        val merged = target.copy(
            body = target.body + "\n\n--- merged from ${source.name} ---\n\n" + source.body,
            description = target.description.takeIf { it.isNotBlank() } ?: source.description,
        )
        skillsStore.update(merged)
        skillsStore.remove(source.id)
        skillRevisionStore?.snapshot(merged, proposal.id, "merged ${source.name} into ${target.name}")
        proposalStore.markApplied(proposal.id, "merged ${source.name} into ${target.name}")
        return ApplyResult.Ok(proposal.id, "merged ${source.name} into ${target.name}")
    }

    private suspend fun applyRetireSkill(proposal: EvolutionProposalEntity): ApplyResult {
        val existing = skillsStore?.findById(proposal.targetId)
            ?: return ApplyResult.Error(proposal.id, "skill not found: ${proposal.targetId}")
        proposalStore.recordRollbackSnapshot(proposal.id, json.encodeToString(Skill.serializer(), existing))
        skillsStore.remove(existing.id)
        proposalStore.markApplied(proposal.id, "retired skill ${existing.name}")
        return ApplyResult.Ok(proposal.id, "retired skill ${existing.name}")
    }

    private suspend fun applyPromoteToHand(proposal: EvolutionProposalEntity): ApplyResult {
        val skill = skillsStore?.findById(proposal.targetId)
            ?: return ApplyResult.Error(proposal.id, "skill not found: ${proposal.targetId}")
        handRepository ?: return ApplyResult.Error(proposal.id, "HandRepository not available")
        val hand = Hand(
            id = UUID.randomUUID().toString(),
            name = "from_skill_${skill.name}",
            steps = "[]",
            variables = "{}",
            conditions = "[]",
            enabled = true,
        )
        handRepository.insert(hand)
        proposalStore.markApplied(proposal.id, "promoted skill ${skill.name} to hand")
        return ApplyResult.Ok(proposal.id, "promoted skill ${skill.name} to hand")
    }

    private suspend fun applyPatchSpecialistPrompt(proposal: EvolutionProposalEntity): ApplyResult {
        userPreferences ?: return ApplyResult.Error(proposal.id, "UserPreferences not available")
        val args = runCatching {
            json.decodeFromString<Map<String, String>>(proposal.patchJson)
        }.getOrDefault(emptyMap())
        val specialistName = args["specialist"] ?: return ApplyResult.Error(proposal.id, "missing specialist")
        val newPrompt = args["prompt"] ?: return ApplyResult.Error(proposal.id, "missing prompt")
        val current = userPreferences.specialistOverrides.first()
        val map = runCatching {
            json.decodeFromString<Map<String, String>>(current.ifBlank { "{}" })
        }.getOrDefault(emptyMap())
        val updated = map + (specialistName to newPrompt)
        userPreferences.setSpecialistOverrides(json.encodeToString(MapSerializer(String.serializer(), String.serializer()), updated))
        proposalStore.markApplied(proposal.id, "patched $specialistName prompt")
        return ApplyResult.Ok(proposal.id, "patched $specialistName prompt")
    }

    private suspend fun applyAddSkillExample(proposal: EvolutionProposalEntity): ApplyResult {
        val existing = skillsStore?.findById(proposal.targetId)
            ?: return ApplyResult.Error(proposal.id, "skill not found: ${proposal.targetId}")
        proposalStore.recordRollbackSnapshot(proposal.id, json.encodeToString(Skill.serializer(), existing))
        val example = runCatching {
            json.decodeFromString<Map<String, String>>(proposal.patchJson)["example"]
        }.getOrNull() ?: return ApplyResult.Error(proposal.id, "missing example in patch")
        val exampleBlock = "\n\n## Example\n\n$example\n"
        val updated = existing.copy(body = existing.body + exampleBlock)
        skillsStore.update(updated)
        skillRevisionStore?.snapshot(updated, proposal.id, "added example by evolution")
        proposalStore.markApplied(proposal.id, "added example to ${existing.name}")
        return ApplyResult.Ok(proposal.id, "added example to ${existing.name}")
    }

    // ── Memory handlers ─────────────────────────────────────────

    private suspend fun applyConsolidateMemories(proposal: EvolutionProposalEntity): ApplyResult {
        memoryStore ?: return ApplyResult.Error(proposal.id, "MemoryStore not available")
        val args = runCatching {
            json.decodeFromString<Map<String, String>>(proposal.patchJson)
        }.getOrDefault(emptyMap())
        val memoryIds = args["memoryIds"]?.split(",")?.map { it.trim() } ?: return ApplyResult.Error(proposal.id, "missing memoryIds")
        val consolidated = args["consolidatedContent"] ?: return ApplyResult.Error(proposal.id, "missing consolidatedContent")
        val category = args["category"] ?: "consolidated"
        // Store the consolidated memory BEFORE deleting sources.
        // If store() fails (write gate rejects, dedup blocks, DB error),
        // the originals are preserved — no data loss.
        val storedId = memoryStore.store(consolidated, "evolution:consolidate", category, 0.7f)
        if (storedId == null) {
            return ApplyResult.Error(proposal.id, "consolidated content was not stored (write gate rejected or dedup)")
        }
        for (id in memoryIds) {
            memoryStore.forget(id)
        }
        proposalStore.markApplied(proposal.id, "consolidated ${memoryIds.size} memories")
        return ApplyResult.Ok(proposal.id, "consolidated ${memoryIds.size} memories")
    }

    private suspend fun applyForgetMemory(proposal: EvolutionProposalEntity): ApplyResult {
        // Capture the memory before deleting so rollback can restore it.
        val mem = memoryStore?.get(proposal.targetId)
        if (mem != null) {
            proposalStore.recordRollbackSnapshot(proposal.id, json.encodeToString(MemoryEntity.serializer(), mem))
        }
        memoryStore?.forget(proposal.targetId)
            ?: return ApplyResult.Error(proposal.id, "MemoryStore not available")
        proposalStore.markApplied(proposal.id, "forgot memory ${proposal.targetId}")
        return ApplyResult.Ok(proposal.id, "forgot memory ${proposal.targetId}")
    }

    private suspend fun applyUpdateMemoryCategory(proposal: EvolutionProposalEntity): ApplyResult {
        val args = runCatching {
            json.decodeFromString<Map<String, String>>(proposal.patchJson)
        }.getOrDefault(emptyMap())
        val newCategory = args["category"] ?: return ApplyResult.Error(proposal.id, "missing category in patch")
        val mem = memoryStore?.get(proposal.targetId)
            ?: return ApplyResult.Error(proposal.id, "memory not found: ${proposal.targetId}")
        proposalStore.recordRollbackSnapshot(proposal.id, json.encodeToString(MemoryEntity.serializer(), mem))
        memoryStore.update(mem.id, mem.content, newCategory, mem.importance, mem.tags)
        proposalStore.markApplied(proposal.id, "changed category to $newCategory")
        return ApplyResult.Ok(proposal.id, "changed category to $newCategory")
    }

    private suspend fun applyMergeMemories(proposal: EvolutionProposalEntity): ApplyResult {
        memoryStore ?: return ApplyResult.Error(proposal.id, "MemoryStore not available")
        val args = runCatching {
            json.decodeFromString<Map<String, String>>(proposal.patchJson)
        }.getOrDefault(emptyMap())
        val sourceId = args["sourceId"] ?: return ApplyResult.Error(proposal.id, "missing sourceId")
        val targetId = args["targetId"] ?: proposal.targetId
        val source = memoryStore.get(sourceId) ?: return ApplyResult.Error(proposal.id, "source memory not found")
        val target = memoryStore.get(targetId) ?: return ApplyResult.Error(proposal.id, "target memory not found")
        proposalStore.recordRollbackSnapshot(proposal.id, json.encodeToString(MemoryEntity.serializer(), target))
        val mergedContent = target.content + "\n\n--- merged ---\n\n" + source.content
        memoryStore.update(target.id, mergedContent, target.category, maxOf(target.importance, source.importance), target.tags + source.tags)
        memoryStore.forget(source.id)
        proposalStore.markApplied(proposal.id, "merged memory $sourceId into $targetId")
        return ApplyResult.Ok(proposal.id, "merged memory $sourceId into $targetId")
    }

    // ── Belief handlers ─────────────────────────────────────────

    private suspend fun applyCreateBelief(proposal: EvolutionProposalEntity): ApplyResult {
        beliefDao ?: return ApplyResult.Error(proposal.id, "BeliefDao not available")
        val args = runCatching {
            json.decodeFromString<Map<String, String>>(proposal.patchJson)
        }.getOrDefault(emptyMap())
        val subject = args["subject"] ?: "user"
        val predicate = args["predicate"] ?: "property"
        val value = args["value"] ?: ""
        val beliefId = UUID.randomUUID().toString()
        val belief = BeliefEntity(
            id = beliefId,
            subject = subject,
            predicate = predicate,
            valueJson = value,
            confidence = args["confidence"]?.toFloatOrNull() ?: 0.8f,
        )
        beliefDao.upsert(belief)
        evidenceDao?.upsert(EvidenceEntity(
            id = UUID.randomUUID().toString(),
            beliefId = beliefId,
            source = "evolution",
            summary = proposal.summary.ifBlank { "Created by evolution" },
        ))
        proposalStore.markApplied(proposal.id, "created belief $subject:$predicate")
        return ApplyResult.Ok(proposal.id, "created belief $subject:$predicate")
    }

    private suspend fun applyUpdateBelief(proposal: EvolutionProposalEntity): ApplyResult {
        beliefDao ?: return ApplyResult.Error(proposal.id, "BeliefDao not available")
        val existing = beliefDao.getById(proposal.targetId)
            ?: return ApplyResult.Error(proposal.id, "belief not found: ${proposal.targetId}")
        // Snapshot before mutation so rollback can restore.
        proposalStore.recordRollbackSnapshot(proposal.id,
            """{"id":"${existing.id}","subject":"${existing.subject}","predicate":"${existing.predicate}","valueJson":"${existing.valueJson}","confidence":${existing.confidence},"status":"${existing.status}"}""")
        val args = runCatching {
            json.decodeFromString<Map<String, String>>(proposal.patchJson)
        }.getOrDefault(emptyMap())
        val newValue = args["value"] ?: existing.valueJson
        val newBelief = existing.copy(
            valueJson = newValue,
            updatedAt = System.currentTimeMillis(),
            lastVerifiedAt = System.currentTimeMillis(),
        )
        beliefDao.upsert(newBelief)
        proposalStore.markApplied(proposal.id, "updated belief ${existing.subject}:${existing.predicate}")
        return ApplyResult.Ok(proposal.id, "updated belief ${existing.subject}:${existing.predicate}")
    }

    private suspend fun applyRetireBelief(proposal: EvolutionProposalEntity): ApplyResult {
        beliefDao ?: return ApplyResult.Error(proposal.id, "BeliefDao not available")
        // Snapshot before retiring so rollback can restore.
        val existing = beliefDao.getById(proposal.targetId)
        if (existing != null) {
            proposalStore.recordRollbackSnapshot(proposal.id,
                """{"id":"${existing.id}","subject":"${existing.subject}","predicate":"${existing.predicate}","valueJson":"${existing.valueJson}","confidence":${existing.confidence},"status":"${existing.status}"}""")
        }
        beliefDao.supersede(proposal.targetId, "retired", "", System.currentTimeMillis())
        proposalStore.markApplied(proposal.id, "retired belief ${proposal.targetId}")
        return ApplyResult.Ok(proposal.id, "retired belief ${proposal.targetId}")
    }

    // ── Proactive rule handlers ─────────────────────────────────

    private suspend fun applyNewProactiveRule(proposal: EvolutionProposalEntity): ApplyResult {
        val args = runCatching {
            json.decodeFromString<Map<String, String>>(proposal.patchJson)
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

    private suspend fun applyAdjustRuleTiming(proposal: EvolutionProposalEntity): ApplyResult {
        // Proactive timing adjustments require scheduler changes that go
        // beyond the saga's scope. Record the intent so the user can apply
        // it manually from the proactive settings.
        val args = runCatching {
            json.decodeFromString<Map<String, String>>(proposal.patchJson)
        }.getOrDefault(emptyMap())
        val newHour = args["hour"] ?: "unknown"
        proposalStore.markApplied(proposal.id, "recommended timing adjustment to hour $newHour (apply manually)")
        return ApplyResult.Ok(proposal.id, "timing adjustment recorded — apply hour $newHour in Settings")
    }

    private suspend fun applyDisableRule(proposal: EvolutionProposalEntity): ApplyResult {
        proactiveEventDao ?: return ApplyResult.Error(proposal.id, "ProactiveEventDao not available")
        val args = runCatching {
            json.decodeFromString<Map<String, String>>(proposal.patchJson)
        }.getOrDefault(emptyMap())
        val tag = args["correlationTag"] ?: proposal.targetId
        proactiveEventDao.deleteByCorrelationTag(tag)
        proposalStore.markApplied(proposal.id, "disabled rule $tag")
        return ApplyResult.Ok(proposal.id, "disabled rule $tag")
    }

    private suspend fun applyEnableRule(proposal: EvolutionProposalEntity): ApplyResult {
        // Enabling a rule means re-inserting it. Since the rule was deleted
        // by disable, we re-create it from the proposal patch.
        val args = runCatching {
            json.decodeFromString<Map<String, String>>(proposal.patchJson)
        }.getOrDefault(emptyMap())
        val title = args["title"] ?: "Re-enabled rule"
        val body = args["body"] ?: ""
        proactiveEventDao?.insert(
            com.aura.proactive.ProactiveEventEntity(
                eventType = args["eventType"] ?: "custom",
                title = title,
                body = body,
                timestamp = System.currentTimeMillis(),
                correlationTag = "evolution:${proposal.id}",
            )
        ) ?: return ApplyResult.Error(proposal.id, "ProactiveEventDao not available")
        proposalStore.markApplied(proposal.id, "enabled rule $title")
        return ApplyResult.Ok(proposal.id, "enabled rule $title")
    }

    private suspend fun applyRewriteRuleMessage(proposal: EvolutionProposalEntity): ApplyResult {
        val args = runCatching {
            json.decodeFromString<Map<String, String>>(proposal.patchJson)
        }.getOrDefault(emptyMap())
        val newTitle = args["title"] ?: return ApplyResult.Error(proposal.id, "missing title")
        val newBody = args["body"] ?: ""
        proactiveEventDao?.insert(
            com.aura.proactive.ProactiveEventEntity(
                eventType = "custom",
                title = newTitle,
                body = newBody,
                timestamp = System.currentTimeMillis(),
                correlationTag = "evolution:${proposal.id}",
            )
        ) ?: return ApplyResult.Error(proposal.id, "ProactiveEventDao not available")
        proposalStore.markApplied(proposal.id, "rewrote rule message to '$newTitle'")
        return ApplyResult.Ok(proposal.id, "rewrote rule message to '$newTitle'")
    }

    sealed interface ApplyResult {
        data class Ok(val proposalId: kotlin.String, val summary: kotlin.String) : ApplyResult
        data class Error(val proposalId: kotlin.String, val message: kotlin.String) : ApplyResult
    }
}