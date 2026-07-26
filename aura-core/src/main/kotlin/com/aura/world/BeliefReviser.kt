package com.aura.world

import com.aura.dream.ContradictionDao
import com.aura.dream.ContradictionEntity
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Applies an arbiter [Verdict] to the world model.
 *
 * The single invariant: revision never deletes. The losing belief keeps its
 * row and its evidence, and gains `status = "superseded"`, `supersededBy` and
 * `validTo`. Walking `supersededBy` backwards is what produces "I used to
 * think X" — it is the feature, not an audit side-effect.
 */
@Singleton
class BeliefReviser @Inject constructor(
    private val beliefDao: BeliefDao,
    private val contradictionDao: ContradictionDao,
) {

    /** @return true when a revision was written. */
    suspend fun applyVerdict(verdict: Verdict, now: Long = System.currentTimeMillis()): Boolean {
        if (verdict !is Verdict.Winner) return false

        beliefDao.supersede(
            verdict.losing.id,
            "superseded",
            verdict.winning.id,
            now,
        )

        // Record the resolution so the revision is auditable even after the
        // belief chain is later compacted or exported.
        contradictionDao.insert(
            ContradictionEntity(
                id = "contra_${UUID.randomUUID()}",
                olderSummaryId = "",
                newerSummaryId = "",
                olderText = verdict.losing.valueJson,
                newerText = verdict.winning.valueJson,
                triggerPhrase = "belief_conflict",
                confidence = verdict.margin.coerceIn(0f, 1f),
                status = "RESOLVED",
                createdAt = now,
                resolvedAt = now,
                olderBeliefId = verdict.losing.id,
                newerBeliefId = verdict.winning.id,
            ),
        )
        return true
    }
}
