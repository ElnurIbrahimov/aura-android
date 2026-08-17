package com.aura.calibration

import android.util.Log
import com.aura.world.BeliefDao
import com.aura.world.EvidenceDao
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The only writer for `claim_resolutions`.
 *
 * Holds the three invariants the schema cannot:
 *
 * 1. **One verdict per belief.** A claim is resolved once. Allowing a second
 *    would let a single belief the user kept changing their mind about dominate
 *    the sample, and the sample is small enough that it would.
 * 2. **The asserted confidence is snapshotted, never joined.** See
 *    [ClaimResolutionEntity.assertedConfidence].
 * 3. **The belief's source is resolved at write time**, from its earliest
 *    evidence row, because that is when the evidence chain is still intact —
 *    evidence CASCADEs with the belief.
 */
@Singleton
class ClaimResolutionStore @Inject constructor(
    private val dao: ClaimResolutionDao,
    private val beliefDao: BeliefDao,
    private val evidenceDao: EvidenceDao,
) {

    /**
     * Record a verdict on a belief.
     *
     * @return the row written, or null if it was refused. Never throws: the chat
     *   classifier calls this from a background sweep where an exception would
     *   surface as an unattributable worker failure.
     */
    suspend fun record(
        beliefId: String,
        verdict: String,
        verdictSource: String,
        note: String = "",
        now: Long = System.currentTimeMillis(),
    ): ClaimResolutionEntity? {
        if (verdict !in ClaimResolutionEntity.VERDICTS) {
            Log.w(TAG, "refusing unknown verdict '$verdict'")
            return null
        }
        if (verdictSource !in ClaimResolutionEntity.VERDICT_SOURCES) {
            Log.w(TAG, "refusing unknown verdict source '$verdictSource'")
            return null
        }

        val belief = runCatching { beliefDao.getById(beliefId) }
            .onFailure { Log.w(TAG, "could not read belief $beliefId: ${it.message}", it) }
            .getOrNull()
        if (belief == null) {
            // The foreign key would reject this anyway; refusing here makes it a
            // log line rather than a constraint exception on a worker.
            Log.w(TAG, "refusing a verdict for unknown belief $beliefId")
            return null
        }

        val existing = runCatching { dao.forBelief(beliefId) }
            .onFailure { Log.w(TAG, "could not read existing verdicts: ${it.message}", it) }
            .getOrDefault(emptyList())
        if (existing.isNotEmpty()) {
            Log.w(TAG, "belief $beliefId already resolved as ${existing.first().verdict}; ignoring")
            return null
        }

        // Earliest evidence, because that is what *formed* the belief. The newest
        // row is whatever most recently agreed with it, which is the thing this
        // whole feature exists to stop treating as verification.
        val source = runCatching {
            evidenceDao.forBelief(beliefId).minByOrNull { it.timestamp }?.source
        }
            .onFailure { Log.w(TAG, "could not read evidence: ${it.message}", it) }
            .getOrNull()
            ?: UNKNOWN_SOURCE

        val row = ClaimResolutionEntity(
            id = UUID.randomUUID().toString(),
            beliefId = beliefId,
            verdict = verdict,
            verdictSource = verdictSource,
            assertedConfidence = belief.confidence,
            beliefSource = source,
            note = note.trim(),
            resolvedAt = now,
        )
        return runCatching { dao.insert(row); row }
            .onFailure { Log.w(TAG, "could not write verdict: ${it.message}", it) }
            .getOrNull()
    }

    /** Belief ids already ruled on, so the question author never re-asks one. */
    suspend fun resolvedBeliefIds(): Set<String> =
        runCatching { dao.resolvedBeliefIds().toSet() }
            .onFailure { Log.w(TAG, "could not read resolved ids: ${it.message}", it) }
            .getOrDefault(emptySet())

    suspend fun forBelief(beliefId: String): List<ClaimResolutionEntity> =
        runCatching { dao.forBelief(beliefId) }
            .onFailure { Log.w(TAG, "could not read verdicts: ${it.message}", it) }
            .getOrDefault(emptyList())

    companion object {
        /**
         * A belief whose evidence rows are gone or were never written.
         *
         * Kept as its own bucket rather than folded into a real source: a
         * reliability figure computed over beliefs of unknown provenance is not
         * a statement about any part of the system, and merging it into one
         * would corrupt a bucket that is a statement about something.
         */
        const val UNKNOWN_SOURCE = "unknown"

        private const val TAG = "ClaimResolutionStore"
    }
}
