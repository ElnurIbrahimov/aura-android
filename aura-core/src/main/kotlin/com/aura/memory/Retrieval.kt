package com.aura.memory

import kotlin.math.exp
import kotlin.math.ln

/**
 * A scored memory candidate with separate text-match and vector-similarity scores.
 *
 * @property memory  the underlying memory entity
 * @property textScore  BM25-like text overlap score (0..1)
 * @property vectorScore  cosine similarity between query and memory embedding (0..1)
 */
data class ScoredMemory(
    val memory: MemoryEntity,
    val textScore: Float = 0f,
    val vectorScore: Float = 0f,
)

/**
 * RRF (Reciprocal Rank Fusion) retrieval combiner.
 *
 * Ranks memory candidates by fusing six signals:
 * - [textScore]: term-overlap score (BM25-like)
 * - [vectorScore]: cosine similarity from vector search
 * - recencyScore: exponential decay based on [MemoryEntity.createdAt]
 * - accessScore: blend of access recency and access frequency
 * - decayScore: [MemoryEntity.decayScore] (FadeMem)
 * - importance: [MemoryEntity.importance] (semantic importance)
 *
 * Each signal produces a rank ordering; the final score is the sum of
 * 1/(k + rankᵢ) across all signals (RRF). k defaults to 60, which
 * moderates the influence of any single signal's top ranks.
 *
 * The output preserves the [MemoryEntity] objects sorted by fused score
 * descending, truncated to [topK].
 */
object Retrieval {

    /**
     * Defaults, kept as the values [RetrievalConfig] starts from rather than as
     * the values this function reads. Everything is now taken from the config
     * so the eval harness can sweep it; these exist so the sweep has a
     * documented origin.
     */
    internal const val DEFAULT_RRF_K = 60f
    internal const val DEFAULT_SIGNAL_HALF_LIFE_DAYS = 7.0

    // ── Internal rankable container ──────────────────────────────────────

    private data class Rankable(
        val index: Int,
        val memory: MemoryEntity,
        val textScore: Float,
        val vectorScore: Float,
        val recencyScore: Float,
        val accessScore: Float,
        val decayScore: Float,
        val importance: Float,
    )

    // ── Public API ───────────────────────────────────────────────────────

    /**
     * Rank memory candidates using RRF fusion and return the top-[topK].
     *
     * @param query  the original query text. Accepted and unused: fusion works
     *   entirely on precomputed scores and entity metadata. Kept because every
     *   call site passes it and because a future score-based fusion would need
     *   it back; do not add logic that depends on it without saying so here.
     * @param queryEmbedding  likewise unused, for the same reason.
     * @param candidates  scored candidates (memory + textScore + vectorScore)
     * @param topK  number of results to return
     * @param now  current timestamp in millis (injectable for deterministic tests)
     * @param config  weights, RRF constant, tie handling. Defaults reproduce
     *   the shipped behaviour exactly.
     */
    fun rankCandidates(
        query: String,
        queryEmbedding: FloatArray,
        candidates: List<ScoredMemory>,
        topK: Int,
        now: Long = System.currentTimeMillis(),
        config: RetrievalConfig = RetrievalConfig.DEFAULT,
    ): List<MemoryEntity> {
        if (candidates.isEmpty() || topK <= 0) return emptyList()

        // 1) Build rankables with all computed signals ──────────────────────
        val rankables = candidates.mapIndexed { idx, sm ->
            val mem = sm.memory

            // Recency: exponential decay from createdAt
            val ageDays = (now - mem.createdAt).coerceAtLeast(0L) / 86_400_000.0
            val recencyScore = exp(-ageDays * ln(2.0) / config.signalHalfLifeDays).toFloat()

            // Access score: blend of access recency (half-life decay) and
            // access frequency (logistic-style saturation).
            val daysSinceAccess = (now - mem.accessedAt).coerceAtLeast(0L) / 86_400_000.0
            val accessRecency = exp(-daysSinceAccess * ln(2.0) / config.signalHalfLifeDays).toFloat()
            val accessFreq = mem.accessCount.toFloat() / (mem.accessCount + 5).toFloat()
            val accessScore = accessRecency * 0.5f + accessFreq * 0.5f

            Rankable(
                index = idx,
                memory = mem,
                textScore = sm.textScore,
                vectorScore = sm.vectorScore,
                recencyScore = recencyScore,
                accessScore = accessScore,
                decayScore = mem.decayScore,
                importance = mem.importance,
            )
        }

        // 2) Rank by each signal (1 = best) ─────────────────────────────────
        //
        // Under COMPETITION, equal values share a rank, so a signal that is
        // constant across the pool gives everyone rank 1 and drops out of the
        // comparison. Under DENSE — the shipped behaviour — tied values get
        // distinct consecutive ranks broken by input order, which silently
        // turns a low-cardinality signal into a second vote for whatever
        // ordered the candidate query. See TieHandling.COMPETITION.
        fun rankBy(selector: (Rankable) -> Float): Map<Int, Int> {
            val sorted = rankables.sortedByDescending { selector(it) }
            if (config.tieHandling == TieHandling.DENSE) {
                return sorted.mapIndexed { rank, r -> r.index to rank + 1 }.toMap()
            }
            val out = HashMap<Int, Int>(sorted.size)
            var currentRank = 1
            sorted.forEachIndexed { i, r ->
                if (i > 0 && selector(r) != selector(sorted[i - 1])) {
                    // Standard competition: the next distinct value takes the
                    // rank it would have had, so ranks skip over a tie group.
                    currentRank = i + 1
                }
                out[r.index] = currentRank
            }
            return out
        }

        val textRanks = rankBy { it.textScore }
        val vectorRanks = rankBy { it.vectorScore }
        val recencyRanks = rankBy { it.recencyScore }
        val accessRanks = rankBy { it.accessScore }
        val decayRanks = rankBy { it.decayScore }
        val importanceRanks = rankBy { it.importance }

        // 3) Compute RRF score for each candidate ───────────────────────────
        val k = config.rrfK
        val w = config.weights
        val rrfScores = rankables.associate { r ->
            val score = listOf(
                w.text to textRanks.getValue(r.index),
                w.vector to vectorRanks.getValue(r.index),
                w.recency to recencyRanks.getValue(r.index),
                w.usage to accessRanks.getValue(r.index),
                w.decay to decayRanks.getValue(r.index),
                w.importance to importanceRanks.getValue(r.index),
            ).sumOf { (weight, rank) -> weight * (1.0 / (k + rank)) }
            r.index to score
        }

        // 4) Sort by RRF score descending, take topK ─────────────────────────
        return rankables
            .sortedByDescending { rrfScores.getValue(it.index) }
            .take(topK)
            .map { it.memory }
    }
}
