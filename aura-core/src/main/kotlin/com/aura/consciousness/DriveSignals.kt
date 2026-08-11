package com.aura.consciousness

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Real observable inputs for [IntrinsicMotivation.assess], TTL-cached so
 * the agentic loop's hot path never pays more than 3 indexed COUNT
 * queries per [DEFAULT_TTL_MS] window.
 *
 * Signal sources (all read-only, all already Hilt-provided):
 *  - CURIOSITY / kgGapCount: [com.aura.kg.KnowledgeGraphDao.gapNodeCount]
 *    — knowledge-graph nodes with fewer than 2 incident edges.
 *  - COHERENCE / contradictionCount:
 *    [com.aura.dream.ContradictionDao.unresolvedCount] — populated by
 *    DreamConsolidator's contradiction-report phase.
 *  - COMPETENCE / lowConfidenceSkillCount:
 *    [com.aura.agent.StrategyBanditDao.lowConfidenceCount] — bandit arms
 *    with enough observations and mean success below 0.5.
 *
 * Each query is individually best-effort: a failing DAO falls back to
 * the previous snapshot's value (or 0 on first refresh) so one broken
 * table never starves the other drives.
 */
@Singleton
class DriveSignals @Inject constructor(
    private val kgDao: com.aura.kg.KnowledgeGraphDao,
    private val contradictionDao: com.aura.dream.ContradictionDao,
    private val strategyBanditDao: com.aura.agent.StrategyBanditDao,
) {
    data class Snapshot(
        val kgGapCount: Int,
        val contradictionCount: Int,
        val lowConfidenceSkillCount: Int,
        val refreshedAt: Long,
        /**
         * Total nodes in the graph, the denominator for CURIOSITY. Without it
         * `IntrinsicMotivation.assess` divides the gap count by a constant 20
         * and saturates on any real graph. Defaulted and last so existing
         * direct constructions in tests keep compiling — but see
         * `MemoryAugmentedAgenticLoopMotivationTest.cannedSignals()`, whose
         * fixture then silently supplies a zero denominator.
         */
        val kgNodeCount: Int = 0,
    )

    @Volatile
    private var cache: Snapshot? = null
    private val mutex = Mutex()

    /**
     * Return the cached snapshot if it is younger than [ttlMs], else
     * refresh it (at most one refresher at a time; concurrent callers
     * that lose the race reuse the winner's snapshot).
     */
    suspend fun get(ttlMs: Long = DEFAULT_TTL_MS): Snapshot {
        cache?.takeIf { System.currentTimeMillis() - it.refreshedAt < ttlMs }?.let { return it }
        return mutex.withLock {
            // Re-check under the lock — another caller may have refreshed
            // while this one waited.
            cache?.takeIf { System.currentTimeMillis() - it.refreshedAt < ttlMs }?.let { return it }
            val prev = cache
            val snapshot = Snapshot(
                kgGapCount = countOr(prev?.kgGapCount ?: 0) { kgDao.gapNodeCount() },
                contradictionCount = countOr(prev?.contradictionCount ?: 0) { contradictionDao.unresolvedCount() },
                lowConfidenceSkillCount = countOr(prev?.lowConfidenceSkillCount ?: 0) { strategyBanditDao.lowConfidenceCount() },
                refreshedAt = System.currentTimeMillis(),
                // A fourth indexed COUNT per TTL window, on the same table the
                // gap count already scans. The class KDoc's "3 indexed COUNT
                // queries" becomes 4; the TTL is what keeps that off the hot
                // path, not the number of queries.
                kgNodeCount = countOr(prev?.kgNodeCount ?: 0) { kgDao.nodeCount() },
            )
            cache = snapshot
            snapshot
        }
    }

    private inline fun countOr(fallback: Int, query: () -> Int): Int = try {
        query()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Throwable) {
        fallback
    }

    companion object {
        const val DEFAULT_TTL_MS = 5 * 60_000L
    }
}
