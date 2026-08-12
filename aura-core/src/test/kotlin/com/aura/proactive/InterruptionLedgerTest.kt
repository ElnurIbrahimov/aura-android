package com.aura.proactive

import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.util.Calendar
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Interruption as a privilege that is earned and can be lost.
 *
 * Every assistant assumes the right to your attention and argues only about
 * frequency. The properties worth pinning here are the ones that make this
 * different: a category cannot interrupt until suggestions of its kind have
 * actually led somewhere, it loses the right when they stop, a category whose
 * effect cannot be observed can never earn it at all, and your explicit choice
 * outranks all of that.
 */
class InterruptionLedgerTest {

    private val outcomeDao = mockk<ProactiveOutcomeDao>(relaxed = true)
    private val interactionDao = mockk<ProactiveInteractionDao>(relaxed = true)
    private val ledger = InterruptionLedger(outcomeDao, interactionDao)

    private val now = System.currentTimeMillis()

    private fun tally(
        resolved: Int,
        ignored: Int,
        unobservable: Int = 0,
        wire: String = "stuck_tasks",
    ) = listOfNotNull(
        resolved.takeIf { it > 0 }?.let {
            OutcomeTally(wire, ProactiveOutcomeEntity.OUTCOME_RESOLVED, it)
        },
        ignored.takeIf { it > 0 }?.let {
            OutcomeTally(wire, ProactiveOutcomeEntity.OUTCOME_IGNORED, it)
        },
        unobservable.takeIf { it > 0 }?.let {
            OutcomeTally(wire, ProactiveOutcomeEntity.OUTCOME_UNOBSERVABLE, it)
        },
    )

    /** Successes recorded in the current hour, so the hour rule is satisfied. */
    private fun resolvedThisHour(count: Int) = List(count) { now - it * 60_000L }

    private fun setUp(
        resolved: Int,
        ignored: Int,
        unobservable: Int = 0,
        hourSamples: Int = 5,
        alreadyNotifying: Int = 0,
        dismissedRecently: Boolean = false,
        wire: String = "stuck_tasks",
    ) {
        coEvery { outcomeDao.tallySince(any()) } returns tally(resolved, ignored, unobservable, wire)
        coEvery { outcomeDao.resolvedTimesSince(any(), any()) } returns resolvedThisHour(hourSamples)
        coEvery { outcomeDao.notificationsSince(any(), any()) } returns alreadyNotifying
        coEvery { interactionDao.recent(any()) } returns if (dismissedRecently) {
            listOf(ProactiveInteractionEntity(eventId = 1, action = "dismissed", timestamp = now - 60_000L))
        } else {
            emptyList()
        }
    }

    @Test
    fun `a category with a good record earns the right`() = runTest {
        setUp(resolved = 7, ignored = 4)
        val verdict = ledger.verdict(ProactiveFindingType.STUCK_TASKS, now = now)
        assertTrue(verdict.mayInterrupt, verdict.reason)
        assertTrue(verdict.reason.contains("7 of 11"), "the reason must show its working: ${verdict.reason}")
    }

    @Test
    fun `too few samples is not enough, however good they look`() = runTest {
        // 3 for 3 is a perfect record and still not evidence about a person.
        setUp(resolved = 3, ignored = 0)
        val verdict = ledger.verdict(ProactiveFindingType.STUCK_TASKS, now = now)
        assertTrue(!verdict.mayInterrupt)
        assertTrue(verdict.reason.contains("not enough"), verdict.reason)
    }

    @Test
    fun `a poor record stays in the app`() = runTest {
        setUp(resolved = 2, ignored = 9)
        val verdict = ledger.verdict(ProactiveFindingType.STUCK_TASKS, now = now)
        assertTrue(!verdict.mayInterrupt)
        assertTrue(verdict.reason.contains("stays in the app"), verdict.reason)
    }

    @Test
    fun `hysteresis keeps a category that is already notifying from flapping`() = runTest {
        // 40% is under the 50% earning bar but over the 35% revocation bar.
        setUp(resolved = 4, ignored = 6, alreadyNotifying = 3)
        assertTrue(
            ledger.verdict(ProactiveFindingType.STUCK_TASKS, now = now).mayInterrupt,
            "a category on the bar was revoked on a single sample",
        )

        // The same record for a category that has never notified does not earn it.
        setUp(resolved = 4, ignored = 6, alreadyNotifying = 0)
        assertTrue(!ledger.verdict(ProactiveFindingType.STUCK_TASKS, now = now).mayInterrupt)
    }

    @Test
    fun `dismissing a notification silences that category immediately`() = runTest {
        // The strongest negative available. It should not have to wait for a
        // thirty-day average to drift downward.
        setUp(resolved = 20, ignored = 0, alreadyNotifying = 4, dismissedRecently = true)
        val verdict = ledger.verdict(ProactiveFindingType.STUCK_TASKS, now = now)
        assertTrue(!verdict.mayInterrupt, "a dismissed notification did not lock the category out")
        assertTrue(verdict.reason.contains("dismissed"), verdict.reason)
    }

    @Test
    fun `a category whose effect cannot be seen never earns the right, and says so`() = runTest {
        setUp(resolved = 0, ignored = 0, unobservable = 14, wire = "deadline_approaching")
        val verdict = ledger.verdict(ProactiveFindingType.DEADLINE_APPROACHING, now = now)
        assertTrue(!verdict.mayInterrupt)
        assertTrue(verdict.reason.contains("cannot see"), verdict.reason)
    }

    @Test
    fun `success at the wrong hours does not buy this hour`() = runTest {
        setUp(resolved = 9, ignored = 2, hourSamples = 0)
        val verdict = ledger.verdict(ProactiveFindingType.STUCK_TASKS, now = now)
        assertTrue(!verdict.mayInterrupt)
        assertTrue(verdict.reason.contains("this hour"), verdict.reason)
    }

    @Test
    fun `your explicit choice outranks the evidence in both directions`() = runTest {
        setUp(resolved = 0, ignored = 30)
        assertTrue(
            ledger.verdict(ProactiveFindingType.STUCK_TASKS, InterruptionPolicy.ALWAYS, now).mayInterrupt,
            "an explicit Always was overruled by a bad record",
        )

        setUp(resolved = 30, ignored = 0)
        assertTrue(
            !ledger.verdict(ProactiveFindingType.STUCK_TASKS, InterruptionPolicy.NEVER, now).mayInterrupt,
            "an explicit Never was overruled by a good record",
        )
    }

    @Test
    fun `global caps stop eight earned categories from each interrupting`() = runTest {
        coEvery { outcomeDao.allNotificationsSince(any()) } returns 0
        assertTrue(ledger.withinGlobalCaps(now))

        coEvery { outcomeDao.allNotificationsSince(any()) } returns InterruptionLedger.MAX_PER_HOUR
        assertTrue(!ledger.withinGlobalCaps(now), "the hourly cap did not hold")
    }

    @Test
    fun `every verdict carries a sentence, never a bare boolean`() = runTest {
        setUp(resolved = 1, ignored = 1)
        val verdicts = ledger.allVerdicts(now = now)
        assertEquals(ProactiveFindingType.entries.size, verdicts.size)
        assertTrue(
            verdicts.all { it.reason.isNotBlank() && it.reason.last() == '.' },
            "every category must be able to explain itself in a sentence",
        )
    }
}
