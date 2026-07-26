package com.aura.world

import com.aura.kg.EdgeEntity
import com.aura.kg.KgId
import kotlinx.serialization.json.JsonPrimitive
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
    private val beliefDao: BeliefDao,
    private val evidenceDao: EvidenceDao,
    private val reviser: BeliefReviser,
) {

    /** @return number of revisions written. */
    suspend fun check(edges: List<EdgeEntity>, now: Long = System.currentTimeMillis()): Int {
        var revisions = 0
        for (edge in edges) {
            if (edge.sourceId != KgId.USER_NODE_ID) continue
            val existing = beliefDao.active("user", edge.type) ?: continue
            val incomingValue = JsonPrimitive(edge.targetId).toString()
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
                    summary = "${edge.type} → ${edge.targetId}",
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
