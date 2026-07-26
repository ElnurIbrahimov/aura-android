package com.aura.dream

import org.junit.Test
import kotlin.test.assertEquals

/**
 * Phase 10 reporting contract.
 *
 * `DreamCycleReport` is a value type, so what is worth pinning is that adding
 * the new counter did not disturb the counters the earlier phases write --
 * `copy(beliefsPromoted = n)` must leave every other field alone, because
 * runCycle threads a single report through nine prior phases via successive
 * `copy` calls and a clobbered field silently loses a phase's result.
 *
 * The best-effort behaviour of the phase itself (a thrown promoter must not
 * abort the cycle) is not unit-testable without constructing the whole
 * DreamConsolidator with nine collaborators; it is enforced by the
 * try/catch/log block added in Step 5 and matches every other phase in
 * runCycle. Asserting that `runCatching` catches would test the standard
 * library, not this code.
 */
class DreamPromotePhaseTest {

    @Test
    fun `report defaults the new counter to zero`() {
        assertEquals(0, DreamCycleReport().beliefsPromoted)
    }

    @Test
    fun `setting the promoted count preserves earlier phase counters`() {
        val afterEarlierPhases = DreamCycleReport(
            memoriesProcessed = 12,
            summariesWritten = 3,
            routinesExtracted = 2,
            contradictionsFound = 1,
            graphEdgesProposed = 5,
            memoriesArchived = 4,
            profileUpdated = true,
        )

        val withPromotion = afterEarlierPhases.copy(beliefsPromoted = 7)

        assertEquals(7, withPromotion.beliefsPromoted)
        assertEquals(12, withPromotion.memoriesProcessed)
        assertEquals(3, withPromotion.summariesWritten)
        assertEquals(2, withPromotion.routinesExtracted)
        assertEquals(1, withPromotion.contradictionsFound)
        assertEquals(5, withPromotion.graphEdgesProposed)
        assertEquals(4, withPromotion.memoriesArchived)
        assertEquals(true, withPromotion.profileUpdated)
    }
}
