package com.aura.evolution

import org.junit.Test
import kotlin.test.assertTrue

/**
 * Whether a memory-consolidation candidate can ever clear the authoring bar.
 *
 * Four constants in three files decide this and none of them mentions the others:
 *
 *  - `MemoryStore.SEMANTIC_DEDUP_THRESHOLD` (0.92) refuses to *store* a memory more
 *    similar than that, so no cluster can ever exceed it.
 *  - `MemoryStore.NEAR_DUPLICATE_THRESHOLD` (0.85) is the floor for calling two memories
 *    near-copies at all.
 *  - [EvolutionCandidateDetectors.SIMILARITY_FLOOR] / `SIMILARITY_SPAN` / `SCORE_BASE` /
 *    `SCORE_RANGE` / `SIZE_BONUS` turn a mean similarity into a score.
 *  - [EvolutionCoordinator.AUTHORING_SCORE_THRESHOLD] (0.7) is the bar an LLM call is
 *    spent above.
 *
 * The 2026-08-19 audit read these and concluded the action was arithmetically unreachable
 * — that the only auto-applying evolution action was dead code. It is not, but only just:
 * a *pair* needs a mean of exactly 0.92, which is the one value write-time dedup still
 * allows and which floating-point means it will essentially never hit. Three or more
 * memories get `SIZE_BONUS` and clear the bar from ~0.915, inside the real band.
 *
 * So the feature works for the case it is named after — several memories saying nearly the
 * same thing — and the pair case is effectively dead. That is defensible; two similar
 * memories are the weakest possible signal. What is not defensible is it being invisible,
 * which is what this test is for: move any of those constants and the arithmetic that
 * makes this reachable changes silently in a file that does not mention it.
 */
class ConsolidationReachabilityTest {

    /** [EvolutionCandidateDetectors]'s scoring, kept in one place to assert against. */
    private fun score(meanSimilarity: Float, clusterSize: Int): Float {
        val closeness = ((meanSimilarity - EvolutionCandidateDetectors.SIMILARITY_FLOOR) /
            EvolutionCandidateDetectors.SIMILARITY_SPAN).coerceIn(0f, 1f)
        return (
            EvolutionCandidateDetectors.SCORE_BASE +
                EvolutionCandidateDetectors.SCORE_RANGE * closeness +
                EvolutionCandidateDetectors.SIZE_BONUS * (clusterSize - 2)
            ).coerceIn(0.1f, 0.95f)
    }

    /** The highest mean similarity a stored cluster can have — dedup blocks anything above. */
    private val ceiling = 0.92f

    @Test
    fun `a cluster of three can clear the authoring bar`() {
        assertTrue(
            score(ceiling, clusterSize = 3) >= EvolutionCoordinator.AUTHORING_SCORE_THRESHOLD,
            "the only auto-applying evolution action would be dead code",
        )
    }

    @Test
    fun `a cluster of three clears it with room, not exactly`() {
        // 0.915 rather than the 0.92 ceiling, so the reachable band is real rather than a
        // single float value. Without this, tightening SIMILARITY_SPAN could leave the
        // test above passing on the boundary alone. (0.914 is where the arithmetic crosses
        // in double precision and lands a hair under it in Float — which is exactly the
        // kind of margin this file exists to keep visible.)
        assertTrue(
            score(0.915f, clusterSize = 3) >= EvolutionCoordinator.AUTHORING_SCORE_THRESHOLD,
            "consolidation only fires at the exact dedup ceiling, which is unreachable in practice",
        )
    }

    @Test
    fun `a pair only clears the bar at the dedup ceiling itself`() {
        // Documented, not fixed. A pair is the weakest signal consolidation acts on and
        // spending an LLM call on it is the thing the calibration is avoiding — but the
        // reason lives in this arithmetic and nowhere else.
        assertTrue(score(0.919f, clusterSize = 2) < EvolutionCoordinator.AUTHORING_SCORE_THRESHOLD)
        assertTrue(score(ceiling, clusterSize = 2) >= EvolutionCoordinator.AUTHORING_SCORE_THRESHOLD)
    }

    @Test
    fun `nothing below the near-duplicate floor scores at all`() {
        assertTrue(score(0.84f, clusterSize = 5) < EvolutionCoordinator.AUTHORING_SCORE_THRESHOLD)
    }
}
