package com.aura.evolution

import com.aura.memory.MemoryEntity
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Typed, kotlinx-serializable patch payloads for the four producible
 * [EvolutionAction]s, plus the typed rollback snapshots the apply saga
 * records (D7).
 *
 * A proposal's `patchJson` is always one of these shapes, authored by
 * [EvolutionPatchAuthor] and checked by [EvolutionPatchValidator] before the
 * candidate is ever promoted. The historic "{}" empty patches can still exist
 * on legacy rows; the saga rejects them with a typed decode error instead of
 * silently no-oping.
 */

/** PATCH_SKILL: replace the skill body (and optionally the description). */
@Serializable
data class SkillPatch(
    val description: String? = null,
    val body: String = "",
)

/** RETIRE_SKILL: delete the skill. The patch carries the model's reason. */
@Serializable
data class RetireSkillPatch(
    val reason: String = "",
)

/** One step of a promoted hand. Mirrors [com.aura.hands.HandStep]. */
@Serializable
data class HandStepPatch(
    val tool: String = "",
    val args: Map<String, String> = emptyMap(),
)

/**
 * PROMOTE_TO_HAND: create a hand from a frequently-invoked skill. The steps
 * are REAL tool invocations — every `tool` must exist in the ToolRegistry.
 */
@Serializable
data class PromoteToHandPatch(
    val handName: String = "",
    val triggerPhrase: String? = null,
    val steps: List<HandStepPatch> = emptyList(),
)

/**
 * CONSOLIDATE_MEMORIES: replace ≥2 source memories with one consolidated
 * memory. `memoryIds` must be a subset of the ids that were SHOWN to the
 * model during authoring (blocks hallucinated deletions) and must include
 * the candidate's targetId.
 */
@Serializable
data class ConsolidateMemoriesPatch(
    val memoryIds: List<String> = emptyList(),
    val consolidatedContent: String = "",
    val category: String? = null,
)

// ── Typed rollback snapshots (D7) ───────────────────────────────

/**
 * Recorded by the apply saga BEFORE inserting the hand, so rollback deletes
 * exactly the created hand by id (no name-pattern guessing).
 */
@Serializable
data class PromoteToHandSnapshot(
    val handId: String,
    val handName: String,
)

/**
 * Records the id of the stored consolidated memory plus the FULL source
 * entities deleted by the apply, so rollback is an exact inverse: forget the
 * consolidated memory, restore every source.
 */
@Serializable
data class ConsolidateMemoriesSnapshot(
    val consolidatedMemoryId: String,
    val sources: List<MemoryEntity>,
)

/** Shared lenient JSON instance for patch/snapshot (de)serialization. */
object EvolutionPatchJson {
    val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
}
