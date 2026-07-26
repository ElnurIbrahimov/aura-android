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

/**
 * Structural conflict detection on the write path.
 *
 * Runs after every KG save, so it must stay cheap: one indexed DAO lookup per
 * distinct predicate and no model call. Anything requiring semantic judgement
 * ("vegetarian" vs "had steak") is deliberately out of scope here and handled
 * by the dream cycle's adjudication phase.
 */
@Singleton
class BeliefConflictProbe @Inject constructor(
    private val kgDao: KnowledgeGraphDao,
    private val beliefDao: BeliefDao,
    private val evidenceDao: EvidenceDao,
    private val reviser: BeliefReviser,
) {

    /**
     * Predicates that are genuinely single-valued for a given subject, so a
     * new value for one really does contradict the old one.
     *
     * `edge.type` comes from an 18-member closed enum whose documented
     * default is `relates_to`, and most of those members (`uses`, `knows`,
     * `works_on`, ...) are legitimately many-valued — a user can use Kotlin
     * AND Rust. Treating every predicate as single-valued would have this
     * probe supersede a perfectly correct belief with a different, equally
     * correct one just because they share a predicate bucket. Kept
     * deliberately small and conservative until the semantic adjudicator (a
     * deferred later task) can judge conflicts by value class instead of by
     * predicate name alone.
     */
    private val singleValuedPredicates = setOf("located_at")

    /** @return number of revisions written. */
    suspend fun check(edges: List<EdgeEntity>, now: Long = System.currentTimeMillis()): Int {
        var revisions = 0
        for (edge in edges) {
            if (edge.sourceId != KgId.USER_NODE_ID) continue
            if (edge.type.lowercase() !in singleValuedPredicates) continue
            val existing = beliefDao.active("user", edge.type) ?: continue
            // The KG stores target NODE IDs (sha256 hashes), not the
            // human-readable text. Must match BeliefPromoter's resolution
            // exactly, or a belief written by one and compared by the other
            // never agrees even when they describe the same fact.
            val label = kgDao.getNode(edge.targetId)?.label ?: edge.targetId
            val incomingValue = JsonPrimitive(label).toString()
            if (existing.valueJson == incomingValue) continue

            val candidate = BeliefEntity(
                id = UUID.randomUUID().toString(),
                subject = "user",
                predicate = edge.type,
                valueJson = incomingValue,
                confidence = edge.confidence,
                validFrom = now,
                createdAt = now,
                updatedAt = now,
                lastVerifiedAt = now,
            )
            val incomingEvidence = listOf(
                EvidenceEntity(
                    id = UUID.randomUUID().toString(),
                    beliefId = candidate.id,
                    source = "kg_edge",
                    summary = "${edge.type} → $label",
                    detailJson = buildJsonObject {
                        put("edgeId", edge.id)
                        // Raw node id, not the label — the durable link back
                        // to the graph.
                        put("targetNodeId", edge.targetId)
                        put("sourceTurnId", edge.sourceTurnId)
                        put("conversationId", edge.sourceConversationId)
                    }.toString(),
                    timestamp = edge.lastReinforced,
                    confidence = edge.confidence,
                ),
            )
            val verdict = BeliefArbiter.arbitrate(
                BeliefSide(candidate, incomingEvidence),
                BeliefSide(existing, evidenceDao.forBelief(existing.id)),
                now,
            )
            // Only act when the INCOMING belief wins. If the existing belief
            // wins, or the sides are too close, the candidate was never
            // persisted — superseding it would mark a row that does not exist
            // and file a contradiction pointing at a phantom belief.
            if (verdict is Verdict.Winner && verdict.winning.id == candidate.id) {
                beliefDao.upsert(candidate)
                evidenceDao.upsert(incomingEvidence.first())
                if (reviser.applyVerdict(verdict, now)) revisions++
            }
        }
        return revisions
    }
}
