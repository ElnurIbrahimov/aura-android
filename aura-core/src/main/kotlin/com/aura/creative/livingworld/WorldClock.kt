package com.aura.creative.livingworld

/**
 * Maps wall-clock time onto world ticks.
 *
 * **Tick identity is a function of the clock, not of when a worker ran.** This
 * is what makes WorkManager's 15-minute periodic floor irrelevant: tick 412 is
 * tick 412 whether it was computed at 09:00 or at 11:30, so throttling, doze
 * and a missed window change latency and never outcome. It also means a screen
 * can say "next tick in 23 minutes" correctly in a process where no worker has
 * ever run, because the answer comes from arithmetic on a stored epoch rather
 * than from any job's state.
 *
 * Pure, stateless, and takes `now` as a parameter — nothing here reads the
 * clock itself, so the whole thing is testable without waiting for time to pass.
 */
object WorldClock {
    /**
     * One tick per real hour, and one tick is one world day.
     *
     * Chosen so a day away yields about a month of world history — enough that
     * there is real news waiting, not so much that the thread is lost — and a
     * year of ordinary use builds roughly twenty-five years of past to tour.
     * Deliberately a constant rather than a setting: the pace should be lived
     * with before it is made adjustable.
     */
    const val TICK_REAL_MS: Long = 3_600_000L

    /** The highest tick that has become due by [now]. Never negative. */
    fun dueTick(worldEpochMs: Long, now: Long): Long {
        if (now <= worldEpochMs) return 0L
        return (now - worldEpochMs) / TICK_REAL_MS
    }

    /** How many ticks the stored state still owes the clock. Never negative. */
    fun behind(currentTick: Long, worldEpochMs: Long, now: Long): Long {
        val due = dueTick(worldEpochMs, now)
        return if (due > currentTick) due - currentTick else 0L
    }

    /** Milliseconds until the next tick becomes due. Zero when one already is. */
    fun msUntilNextTick(currentTick: Long, worldEpochMs: Long, now: Long): Long {
        if (behind(currentTick, worldEpochMs, now) > 0L) return 0L
        val nextDueAt = worldEpochMs + (currentTick + 1L) * TICK_REAL_MS
        return if (nextDueAt > now) nextDueAt - now else 0L
    }

    /** World days elapsed at [tick]. One tick is one day, so this is the identity. */
    fun worldDay(tick: Long): Long = tick

    /** A plain "Year 3, day 42" label. No calendar system is assumed. */
    fun label(tick: Long): String {
        val year = tick / DAYS_PER_WORLD_YEAR
        val day = tick % DAYS_PER_WORLD_YEAR
        return "Year ${year + 1}, day ${day + 1}"
    }

    const val DAYS_PER_WORLD_YEAR: Long = 360L
}
