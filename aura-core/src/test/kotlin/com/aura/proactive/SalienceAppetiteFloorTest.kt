package com.aura.proactive

import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertTrue

/**
 * The gate that actually mattered, and the one nobody would have found by
 * reading it.
 *
 * `proactiveAppetite()` asks `ProactivePolicyEngine` for a multiplier derived
 * from the ratio of negative interactions to all interactions. `"dismissed"`
 * was the only action anything in the app ever recorded — so that ratio was
 * 1.0 from the very first row, the multiplier dropped to 0.4, and the highest
 * salience any finding can reach (0.855, for an unseen route-bearing finding at
 * maximum urgency) fell to 0.342, under the 0.49 threshold.
 *
 * **One tap of the X on the Home card muted every proactive suggestion,
 * permanently, and nothing anywhere said so.** It also happens before timing is
 * ever consulted, which is why fixing the timing engine alone would have
 * changed nothing observable.
 */
class SalienceAppetiteFloorTest {

    private val eventDao = mockk<ProactiveEventDao>(relaxed = true)
    private val interactionDao = mockk<ProactiveInteractionDao>(relaxed = true)
    private val filter = SalienceFilter(eventDao, interactionDao, ProactivePolicyEngine())

    private fun finding(urgency: Float = 0.7f) = ProactiveAwarenessEngine.ProactiveFinding(
        type = ProactiveFindingType.STUCK_TASKS.wire,
        title = "2 tasks stuck",
        message = "still pending",
        urgency = urgency,
    )

    private fun dismissals(count: Int) = listOf(ActionCount(action = "dismissed", count = count))

    @Test
    fun `one dismissal does not mute everything`() = runTest {
        coEvery { eventDao.recent(any()) } returns emptyList()
        coEvery { interactionDao.summary() } returns dismissals(1)

        val result = filter.filter(listOf(finding())).single()
        assertTrue(
            result.passed,
            "a single dismissal suppressed an urgent finding (salience ${result.salience}) — " +
                "this is the bug that muted the whole system",
        )
    }

    @Test
    fun `a sustained pattern of dismissal is still allowed to quiet things down`() = runTest {
        // The floor must not become a licence to ignore the user entirely.
        coEvery { eventDao.recent(any()) } returns emptyList()
        coEvery { interactionDao.summary() } returns dismissals(20)

        val result = filter.filter(listOf(finding())).single()
        assertTrue(
            !result.passed,
            "twenty dismissals should reduce appetite; salience was ${result.salience}",
        )
    }

    @Test
    fun `positive interactions keep appetite up`() = runTest {
        coEvery { eventDao.recent(any()) } returns emptyList()
        coEvery { interactionDao.summary() } returns listOf(
            ActionCount(action = "acted", count = 12),
            ActionCount(action = "dismissed", count = 3),
        )

        val result = filter.filter(listOf(finding())).single()
        assertTrue(result.passed, "a user who acts on suggestions stopped receiving them")
    }

    @Test
    fun `an empty corpus surfaces urgent findings`() = runTest {
        coEvery { eventDao.recent(any()) } returns emptyList()
        coEvery { interactionDao.summary() } returns emptyList()
        assertTrue(filter.filter(listOf(finding())).single().passed)
    }
}
