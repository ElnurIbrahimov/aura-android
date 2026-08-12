package com.aura.memory

import android.util.Log
import com.aura.kg.EdgeEntity
import com.aura.kg.KnowledgeGraphDao
import com.aura.provenance.ConversationProvenance
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Records a correction and makes it take effect.
 *
 * Recording alone is what the app already had three times over — a feedback
 * table nothing read, a skill-failure kind that named no skill, a decay score
 * that treated a lie and a stale fact identically. So every path here ends in a
 * state change the next recall can see, and returns a [Report] saying what
 * changed, because a correction whose effect the user cannot observe is
 * indistinguishable from one that was dropped.
 */
@Singleton
class CorrectionStore @Inject constructor(
    private val dao: CorrectionDao,
    private val memoryStore: MemoryStore,
    private val embedder: Embedder,
    private val knowledgeGraphDao: KnowledgeGraphDao? = null,
    private val evolutionHooks: com.aura.evolution.EvolutionHooks? = null,
) {

    /**
     * What a correction did, in terms the user can check.
     *
     * [propagated] is the count of derived claims the correction reached — the
     * one-hop report. It is deliberately a number and a sentence rather than a
     * silent side effect: propagation that happens invisibly is how one
     * correction quietly rewrites half a history.
     */
    data class Report(
        val correctionId: String,
        val summary: String,
        val propagated: Int = 0,
    )

    /**
     * "That was never true." The memory stops being retrievable.
     *
     * Retired rather than deleted, so this is undoable and so the row survives
     * to explain why an answer changed.
     */
    suspend fun neverTrue(
        memoryId: String,
        note: String = "",
        provenance: ConversationProvenance = ConversationProvenance(),
        now: Long = System.currentTimeMillis(),
    ): Report {
        val memory = memoryStore.get(memoryId)
            ?: return Report("", "That memory is already gone.")
        memoryStore.retire(memoryId, reason = REASON_RETRACTED, now = now)
        val propagated = propagateFrom(memory)
        val id = record(
            targetId = memoryId,
            kind = CorrectionEntity.NEVER_TRUE,
            note = note,
            provenance = provenance,
            propagated = propagated,
            now = now,
        )
        return Report(id, "Retracted. ${propagationSentence(propagated)}", propagated.size)
    }

    /**
     * "That was true, but it changed." The replacement wins; the original
     * becomes history with an end date rather than fading.
     */
    suspend fun noLongerTrue(
        memoryId: String,
        replacementContent: String,
        note: String = "",
        provenance: ConversationProvenance = ConversationProvenance(),
        now: Long = System.currentTimeMillis(),
    ): Report {
        val memory = memoryStore.get(memoryId)
            ?: return Report("", "That memory is already gone.")
        require(replacementContent.isNotBlank()) { "no_longer_true needs a replacement" }
        // The successor inherits scope, category and standing: what changed is
        // the fact, not how much it matters or who may see it.
        val replacementId = memoryStore.store(
            content = replacementContent,
            source = "user:correction",
            category = memory.category,
            importance = memory.importance,
            tags = memory.tags.split(",").map { it.trim() }.filter { it.isNotEmpty() },
            scope = memory.scope,
            provenance = provenance,
        )
        memoryStore.retire(memoryId, supersededBy = replacementId, reason = REASON_SUPERSEDED, now = now)
        // No propagation. What was derived from the original turn was true when
        // it was derived; superseding a fact does not make its history wrong.
        val id = record(
            targetId = memoryId,
            kind = CorrectionEntity.NO_LONGER_TRUE,
            replacementId = replacementId,
            note = note,
            provenance = provenance,
            now = now,
        )
        return Report(id, "Updated. The old version is kept as history.")
    }

    /**
     * "True, but not for this question." A demotion scoped to questions like
     * the one that surfaced it, not a global penalty.
     */
    suspend fun irrelevantHere(
        memoryId: String,
        queryText: String,
        note: String = "",
        provenance: ConversationProvenance = ConversationProvenance(),
        now: Long = System.currentTimeMillis(),
    ): Report {
        if (memoryStore.get(memoryId) == null) return Report("", "That memory is already gone.")
        // Without an embedding the correction can only match the identical
        // string, which is a scope of one question and not worth recording.
        val vector = runCatching { embedder.embed(queryText) }
            .onFailure { Log.w(TAG, "could not embed the query for a scoped correction", it) }
            .getOrNull()
            ?: return Report("", "Couldn't record that one — try again in a moment.")
        val id = record(
            targetId = memoryId,
            kind = CorrectionEntity.IRRELEVANT_HERE,
            note = note,
            queryText = queryText,
            queryEmbedding = Embedder.toBytes(vector),
            provenance = provenance,
            now = now,
        )
        return Report(id, "Noted. It stays, but it won't lead on questions like that.")
    }

    /**
     * "That answer was bad." Aimed at the skill that produced it.
     *
     * This is the first real `skill_failed` evidence in the app: the only other
     * writer recorded a name-lookup miss under the id `"_unknown_"`, so the
     * PATCH_SKILL detector had never seen a resolvable skill id.
     */
    suspend fun badAnswer(
        skillId: String,
        note: String = "",
        provenance: ConversationProvenance = ConversationProvenance(),
        now: Long = System.currentTimeMillis(),
    ): Report {
        runCatching {
            evolutionHooks?.onSkillFailed(
                skillId,
                errorCode = "user_marked_bad",
                conversationId = provenance.conversationId.takeIf { it.isNotBlank() },
                turnTimestamp = provenance.turnTimestamp.takeIf { it > 0L },
            )
        }.onFailure { Log.w(TAG, "recording skill failure failed", it) }
        val id = record(
            targetKind = CorrectionEntity.TARGET_SKILL,
            targetId = skillId,
            kind = CorrectionEntity.BAD_ANSWER,
            note = note,
            provenance = provenance,
            now = now,
        )
        return Report(id, "Noted. Aura will look at that skill.")
    }

    /** Undo a correction, including whatever it propagated to. */
    suspend fun undo(correctionId: String, now: Long = System.currentTimeMillis()): Report {
        val correction = dao.byId(correctionId)
            ?: return Report(correctionId, "That correction is no longer on record.")
        if (correction.undoneAt != null) return Report(correctionId, "Already undone.")

        when (correction.kind) {
            CorrectionEntity.NEVER_TRUE -> memoryStore.unretire(correction.targetId)
            CorrectionEntity.NO_LONGER_TRUE -> {
                memoryStore.unretire(correction.targetId)
                correction.replacementId?.let { memoryStore.forget(it) }
            }
            // Scoped demotions and skill reports have no state to unwind; the
            // row itself is the effect, and marking it undone removes it.
            else -> Unit
        }
        val restored = restorePropagated(correction)
        dao.markUndone(correctionId, now)
        return Report(correctionId, "Put back. ${propagationSentence(restored, restored = true)}", restored.size)
    }

    /** Corrections against [memoryId] that are still standing. */
    suspend fun forMemory(memoryId: String): List<CorrectionEntity> = dao.forTarget(memoryId)

    suspend fun recent(limit: Int = 50): List<CorrectionEntity> = dao.recent(limit)

    // ── one hop ─────────────────────────────────────────────────

    /**
     * Delete the graph claims extracted from the same turn as [memory].
     *
     * One hop, deliberately. A knowledge graph is a connected thing and
     * unbounded propagation through it is how a single correction silently
     * rewrites a history the user never asked about — so this reaches exactly
     * the claims made from the sentence that was wrong, and stops.
     *
     * Edges rather than nodes: an edge is a claim ("Elnur lives in Baku"), and
     * a node is an entity ("Baku") that other true claims also point at.
     * Deleting the node would cascade them.
     */
    private suspend fun propagateFrom(memory: MemoryEntity): List<PropagatedItem> {
        val dao = knowledgeGraphDao ?: return emptyList()
        if (memory.sourceConversationId.isBlank() || memory.sourceTurnTimestamp <= 0L) return emptyList()
        val edges = runCatching {
            dao.edgesFromTurn(memory.sourceConversationId, memory.sourceTurnTimestamp)
        }.onFailure { Log.w(TAG, "one-hop propagation lookup failed", it) }
            .getOrDefault(emptyList())
        val touched = mutableListOf<PropagatedItem>()
        for (edge in edges) {
            runCatching {
                dao.deleteEdge(edge.id)
                touched += PropagatedItem(
                    kind = PropagatedItem.KG_EDGE,
                    id = edge.id,
                    snapshotJson = json.encodeToString(EdgeEntity.serializer(), edge),
                )
            }.onFailure { Log.w(TAG, "could not propagate to edge ${edge.id}", it) }
        }
        return touched
    }

    private suspend fun restorePropagated(correction: CorrectionEntity): List<PropagatedItem> {
        val graph = knowledgeGraphDao ?: return emptyList()
        val items = runCatching {
            json.decodeFromString(ListSerializer(PropagatedItem.serializer()), correction.propagatedJson)
        }.getOrDefault(emptyList())
        val restored = mutableListOf<PropagatedItem>()
        for (item in items) {
            if (item.kind != PropagatedItem.KG_EDGE) continue
            runCatching {
                graph.insertEdge(json.decodeFromString(EdgeEntity.serializer(), item.snapshotJson))
                restored += item
            }.onFailure { Log.w(TAG, "could not restore ${item.id}", it) }
        }
        return restored
    }

    private fun propagationSentence(items: List<PropagatedItem>, restored: Boolean = false): String = when {
        items.isEmpty() -> "Nothing else referred to it."
        restored -> "Also restored ${items.size} connected fact${plural(items.size)}."
        else -> "Also removed ${items.size} connected fact${plural(items.size)} from the same conversation."
    }

    private fun plural(n: Int) = if (n == 1) "" else "s"

    private suspend fun record(
        targetKind: String = CorrectionEntity.TARGET_MEMORY,
        targetId: String,
        kind: String,
        replacementId: String? = null,
        note: String = "",
        queryText: String = "",
        queryEmbedding: ByteArray? = null,
        provenance: ConversationProvenance,
        propagated: List<PropagatedItem> = emptyList(),
        now: Long,
    ): String {
        val id = UUID.randomUUID().toString()
        dao.insert(
            CorrectionEntity(
                id = id,
                targetKind = targetKind,
                targetId = targetId,
                kind = kind,
                replacementId = replacementId,
                note = note,
                queryText = queryText,
                queryEmbedding = queryEmbedding,
                sourceConversationId = provenance.conversationId,
                sourceTurnTimestamp = provenance.turnTimestamp,
                propagatedJson = json.encodeToString(ListSerializer(PropagatedItem.serializer()), propagated),
                createdAt = now,
            ),
        )
        return id
    }

    private companion object {
        const val TAG = "CorrectionStore"
        val json = Json { ignoreUnknownKeys = true }
    }
}

/** [MemoryEntity.retiredReason] for a memory the user says was never true. */
const val REASON_RETRACTED = "retracted"

/** [MemoryEntity.retiredReason] for a memory a later one replaced. */
const val REASON_SUPERSEDED = "superseded"
