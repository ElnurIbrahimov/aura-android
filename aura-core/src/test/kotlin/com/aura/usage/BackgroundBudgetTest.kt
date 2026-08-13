package com.aura.usage

import org.junit.Test
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A ceiling on what Aura spends when nobody asked it to.
 *
 * Nothing bounded unattended work: `UsageTracker` counts and never caps, and
 * `ToolPolicy.costCeiling` has been allowlisted in `DeadConfigFieldTest` as a
 * field that decides nothing since it was written. Seeding `backgroundModel` on
 * 2026-08-13 switched four timer-driven subsystems on at once, which turned this
 * from a theoretical gap into a live one.
 */
class BackgroundBudgetTest {

    private var now = at(2026, 8, 13, hour = 10)

    private fun budget() = BackgroundBudget { now }

    private fun at(year: Int, month: Int, day: Int, hour: Int): Long =
        Calendar.getInstance(TimeZone.getDefault(), Locale.US).apply {
            set(year, month - 1, day, hour, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

    @Test
    fun `headroom runs out when the day is spent`() {
        val budget = budget()
        assertTrue(budget.hasHeadroom())

        budget.record(BackgroundBudget.DEFAULT_DAILY_TOKENS - 1)
        assertTrue(budget.hasHeadroom(), "one token short of the ceiling is still headroom")

        budget.record(1)
        assertFalse(budget.hasHeadroom())
    }

    /**
     * The whole point of the cap: tomorrow is a new day and the daemon runs
     * again. A ceiling that latched would turn one expensive night into a
     * permanently dead background layer.
     */
    @Test
    fun `the ledger rolls over at local midnight`() {
        val budget = budget()
        budget.record(BackgroundBudget.DEFAULT_DAILY_TOKENS * 2)
        assertFalse(budget.hasHeadroom())

        now = at(2026, 8, 14, hour = 1)
        assertTrue(budget.hasHeadroom(), "the budget did not reset on the next local day")
        assertEquals(0, budget.snapshot().tokens)
    }

    @Test
    fun `late evening and early morning are different days, not the same one`() {
        val budget = budget()
        now = at(2026, 8, 13, hour = 23)
        budget.record(1_000)
        assertEquals(1_000, budget.snapshot().tokens)

        now = at(2026, 8, 14, hour = 0)
        assertEquals(0, budget.snapshot().tokens)
    }

    /**
     * Local, not UTC. `AdaptiveTimingEngine` bucketed in UTC while reading local
     * and scored every hour at zero for it — the same mistake is available here,
     * and this is the assertion that would catch it in a non-UTC timezone.
     */
    @Test
    fun `the day boundary follows the device timezone`() {
        val budget = budget()
        val label = budget.snapshot().day
        val expected = Calendar.getInstance(TimeZone.getDefault(), Locale.US).apply { timeInMillis = now }
        assertEquals(
            "%04d-%02d-%02d".format(
                expected.get(Calendar.YEAR),
                expected.get(Calendar.MONTH) + 1,
                expected.get(Calendar.DAY_OF_MONTH),
            ),
            label,
        )
    }

    @Test
    fun `blocked calls are counted so the Usage screen can say it happened`() {
        val budget = budget()
        budget.record(BackgroundBudget.DEFAULT_DAILY_TOKENS)

        budget.recordBlocked()
        budget.recordBlocked()

        assertEquals(2, budget.snapshot().blockedCalls)
        assertTrue(budget.snapshot().exhausted)
    }

    @Test
    fun `a zero or negative charge is ignored rather than counted`() {
        val budget = budget()
        budget.record(0)
        budget.record(-500)
        assertEquals(0, budget.snapshot().tokens)
    }

    @Test
    fun `fraction is bounded even when the day is overspent`() {
        val budget = budget()
        budget.record(BackgroundBudget.DEFAULT_DAILY_TOKENS * 10)
        assertEquals(1f, budget.snapshot().fraction)
    }
}
