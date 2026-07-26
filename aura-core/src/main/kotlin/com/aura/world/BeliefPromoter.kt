package com.aura.world

import com.aura.kg.EdgeEntity
import com.aura.kg.KgId
import com.aura.kg.KnowledgeGraphDao
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** Minimum edge confidence worth asserting as a belief. */
private const val MIN_EDGE_CONFIDENCE = 0.7f

/**
 * Promotes reinforced knowledge-graph edges about the user into world-model
 * beliefs.
 *
 * Before this existed the belief table had no producer at all — nothing in
 * the app ever created a belief, so the world model was a schema with no
 * rows.
 *
 * A belief is an edge that cleared three bars: it is about the user, the
 * extractor was confident, and it was seen again in a later turn. The last
 * one is deliberately a proxy — `EdgeEntity` has no reinforcement counter, so
 * `lastReinforced > createdAt` is the strongest "seen more than once" test
 * expressible today.
 */
@Singleton
class BeliefPromoter @Inject constructor(
    private val kgDao: KnowledgeGraphDao,
    private val beliefDao: BeliefDao,
    private val evidenceDao: EvidenceDao,
) {

    /**
     * Promote every qualifying edge. Idempotent: an edge whose belief already
     * exists bumps `lastVerifiedAt` rather than creating a duplicate.
     *
     * @return count of beliefs created or re-verified.
     */
    suspend fun promote(now: Long = System.currentTimeMillis()): Int {
        val edges = kgDao.edgesFrom(KgId.USER_NODE_ID).filter { it.qualifies() }
        var count = 0
        for (edge in edges) {
            val predicate = edge.type
            val valueJson = JsonPrimitive(edge.targetId).toString()
            val existing = beliefDao.active("user", predicate)
            if (existing != null) {
                // Same subject+predicate already believed. Conflicting values
                // are NOT resolved here — that is a later task's job.
                // Promotion only ever reinforces.
                beliefDao.verify(existing.id, edge.confidence, now)
                evidenceDao.upsert(edge.toEvidence(existing.id, now))
                count++
                continue
            }
            val beliefId = UUID.randomUUID().toString()
            beliefDao.upsert(
                BeliefEntity(
                    id = beliefId,
                    subject = "user",
                    predicate = predicate,
                    valueJson = valueJson,
                    confidence = edge.confidence,
                    validFrom = now,
                    status = "active",
                    createdAt = now,
                    updatedAt = now,
                    lastVerifiedAt = now,
                ),
            )
            evidenceDao.upsert(edge.toEvidence(beliefId, now))
            count++
        }
        return count
    }

    private fun EdgeEntity.qualifies(): Boolean =
        confidence >= MIN_EDGE_CONFIDENCE && lastReinforced > createdAt

    /**
     * Snapshot the edge's provenance as evidence.
     *
     * This is load-bearing rather than decorative: the edge itself gets
     * overwritten on reinforcement, so it cannot say when support first
     * arrived. Accumulated evidence rows are the only durable record of that,
     * and they are what `BeliefArbiter.corroboration()` counts distinct
     * turns from.
     */
    private fun EdgeEntity.toEvidence(beliefId: String, now: Long) = EvidenceEntity(
        id = evidenceId(beliefId, sourceTurnId),
        beliefId = beliefId,
        source = "kg_edge",
        summary = "$type → $targetId",
        detailJson = buildJsonObject {
            put("edgeId", id)
            put("sourceTurnId", sourceTurnId)
            put("conversationId", sourceConversationId)
        }.toString(),
        timestamp = now,
        confidence = confidence,
    )

    /**
     * Deterministic: one evidence row per (belief, supporting turn).
     * `promote()` runs every dream cycle, so a random id would add a row per
     * cycle for an edge that was never re-seen — inflating corroboration
     * with repeats of the same turn. Keying on `sourceTurnId` means
     * re-promoting an unchanged edge rewrites the same row, while a
     * genuinely new turn adds one.
     */
    private fun evidenceId(beliefId: String, sourceTurnId: String): String =
        "ev_${beliefId}_${sourceTurnId.ifBlank { "unknown" }}"
}
