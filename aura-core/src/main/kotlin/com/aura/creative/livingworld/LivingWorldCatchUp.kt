package com.aura.creative.livingworld

/**
 * When opening a screen should nudge the world forward.
 *
 * One tick behind is the normal steady state between hourly worker runs and
 * is not worth an enqueue; two or more means the periodic schedule slipped —
 * doze, reboot, a long absence — and the user is looking at yesterday.
 * Pure, so the policy is testable without a Context.
 */
object LivingWorldCatchUp {
    const val BEHIND_TICKS = 2L

    fun shouldEnqueue(
        currentTick: Long,
        worldEpochMs: Long,
        now: Long,
        sessionTicksBurned: Long,
    ): Boolean =
        WorldClock.behind(currentTick, worldEpochMs, now, sessionTicksBurned) >= BEHIND_TICKS
}
