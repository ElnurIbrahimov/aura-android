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
            // The KG stores target NODE IDS (sha256 hashes), not the
            // human-readable text. A belief has to hold what the user can
            // read back — "Kotlin", not "9f86d081...c65" — so resolve the
            // node before it ever reaches valueJson.
            val label = resolveLabel(edge.targetId)
            val valueJson = JsonPrimitive(label).toString()
            val existing = beliefDao.active("user", predicate)
            if (existing != null) {
                // A differing value here is a genuine conflict — the same
                // subject+predicate now points somewhere else. That is the
                // conflict probe/adjudicator's job to resolve, not
                // promotion's: reinforcing it would let the promoter refresh
                // recency and grow corroboration on a belief that the user
                // has since contradicted, making it harder to overturn the
                // more it gets reinforced by a *different* fact.
                if (existing.valueJson != valueJson) continue
                beliefDao.verify(existing.id, edge.confidence, now)
                evidenceDao.upsert(edge.toEvidence(existing.id, label))
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
            evidenceDao.upsert(edge.toEvidence(beliefId, label))
            count++
        }
        return count
    }

    private fun EdgeEntity.qualifies(): Boolean =
        confidence >= MIN_EDGE_CONFIDENCE && lastReinforced > createdAt

    /**
     * Resolve a KG node id (a sha256 hash — see [KgId.node]) to its
     * human-readable label. Falls back to the id itself when the node is
     * missing, so a dangling reference degrades to the old (hash) behavior
     * instead of throwing.
     */
    private suspend fun resolveLabel(nodeId: String): String =
        kgDao.getNode(nodeId)?.label ?: nodeId

    /**
     * Snapshot the edge's provenance as evidence.
     *
     * This is load-bearing rather than decorative: the edge itself gets
     * overwritten on reinforcement, so it cannot say when support first
     * arrived. Accumulated evidence rows are the only durable record of that,
     * and they are what `BeliefArbiter.corroboration()` counts distinct
     * turns from.
     */
    private fun EdgeEntity.toEvidence(beliefId: String, label: String) = EvidenceEntity(
        id = evidenceId(beliefId, sourceTurnId),
        beliefId = beliefId,
        source = "kg_edge",
        summary = "$type → $label",
        detailJson = buildJsonObject {
            put("edgeId", id)
            // Raw node id, not the label — this is the durable link back to
            // the graph even if the node is later renamed or merged.
            put("targetNodeId", targetId)
            put("sourceTurnId", sourceTurnId)
            put("conversationId", sourceConversationId)
        }.toString(),
        // The time the supporting TURN happened, not the time promote() ran.
        // promote() runs every dream cycle and qualifies() stays true once an
        // edge has been reinforced, so using `now` here would rewrite this row
        // every cycle and pin BeliefArbiter.recency() — the heaviest-weighted
        // signal — at ~1.0 forever, making a year-old belief score as fresh as
        // this morning's.
        timestamp = lastReinforced,
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
