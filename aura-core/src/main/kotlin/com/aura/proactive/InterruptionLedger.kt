package com.aura.proactive

import android.util.Log
import javax.inject.Inject
import javax.inject.Singleton

/** Whether a category may interrupt right now, and the sentence explaining it. */
data class InterruptionVerdict(
    val type: ProactiveFindingType,
    val mayInterrupt: Boolean,
    /** A complete sentence, rendered verbatim in Settings. */
    val reason: String,
    val resolved: Int = 0,
    val closed: Int = 0,
    val unobservable: Int = 0,
)

/** The user's standing instruction for a category. */
enum class InterruptionPolicy { ALWAYS, NEVER, EARNED }

/**
 * Decides which kinds of suggestion have earned the right to interrupt you.
 *
 * Every assistant assumes the right to your attention and then argues about
 * frequency. Here interruption is a privilege granted per category, earned from
 * evidence that suggestions of that kind actually led somewhere, revocable when
 * they stop, and never granted globally.
 *
 * **Derived, not stored.** The verdict is recomputed from countable rows every
 * time it is asked, so there is no cached score that can quietly disagree with
 * the evidence it claims to summarise — and "why did this notify me" is always
 * answerable by reading the same rows back.
 *
 * Nothing here learns. Bandits and weights exist elsewhere in this codebase and
 * are deliberately not used: a count can be turned into a sentence, and a
 * weight cannot.
 */
@Singleton
class InterruptionLedger @Inject constructor(
    private val outcomeDao: ProactiveOutcomeDao,
    private val interactionDao: ProactiveInteractionDao,
) {

    suspend fun verdict(
        type: ProactiveFindingType,
        policy: InterruptionPolicy = InterruptionPolicy.EARNED,
        now: Long = System.currentTimeMillis(),
    ): InterruptionVerdict {
        // The user's explicit choice outranks the evidence. The ledger measures
        // what worked; it does not know what they want.
        when (policy) {
            InterruptionPolicy.NEVER ->
                return InterruptionVerdict(type, false, "You've told Aura never to interrupt for this.")
            InterruptionPolicy.ALWAYS ->
                return InterruptionVerdict(type, true, "You've told Aura this one may always interrupt.")
            InterruptionPolicy.EARNED -> Unit
        }

        val since = now - WINDOW_MS
        val tally = runCatching { outcomeDao.tallySince(since) }
            .onFailure { Log.w(TAG, "tally failed: ${it.message}", it) }
            .getOrDefault(emptyList())
            .filter { it.findingType == type.wire }

        fun count(outcome: String) = tally.firstOrNull { it.outcome == outcome }?.count ?: 0
        val resolved = count(ProactiveOutcomeEntity.OUTCOME_RESOLVED)
        val ignored = count(ProactiveOutcomeEntity.OUTCOME_IGNORED)
        val unobservable = count(ProactiveOutcomeEntity.OUTCOME_UNOBSERVABLE)
        val closed = resolved + ignored

        fun no(reason: String) = InterruptionVerdict(type, false, reason, resolved, closed, unobservable)

        // A category whose effect cannot be observed can never earn the right,
        // and says so plainly rather than looking broken.
        if (closed == 0 && unobservable > 0) {
            return no(
                "Aura can raise this but cannot see what you did about it, " +
                    "so it stays in the app permanently.",
            )
        }

        // A dismissal of something Aura chose to interrupt with is the
        // strongest negative available and should not have to wait for a
        // thirty-day average to drift.
        val lockedOut = runCatching { dismissedNotificationRecently(type, now) }
            .onFailure { Log.w(TAG, "lockout check failed: ${it.message}", it) }
            .getOrDefault(false)
        if (lockedOut) {
            return no("You dismissed a notification from this recently, so it's silent for a few days.")
        }

        if (closed < MIN_CLOSED) {
            return no("Only $closed suggestion(s) of this kind have played out so far — not enough to judge.")
        }

        val rate = resolved.toFloat() / closed

        // Hysteresis. A category that already holds the right keeps it until it
        // falls to the lower bar, so one bad sample near the threshold does not
        // make it flap between notifying and silent. Whether it holds the right
        // is itself derived — it has been notifying — rather than stored.
        val alreadyEarned = runCatching { outcomeDao.notificationsSince(type.wire, since) > 0 }
            .onFailure { Log.w(TAG, "hysteresis check failed: ${it.message}", it) }
            .getOrDefault(false)
        val bar = if (alreadyEarned) REVOKE_RATE else EARN_RATE
        if (rate < bar) {
            return no(
                "$resolved of $closed led somewhere (${percent(rate)}). " +
                    "Below the ${percent(bar)} bar, so this stays in the app.",
            )
        }

        // Only in hours where it has actually landed. A ±1 band because 24
        // buckets over at most a few dozen samples is otherwise too sparse to
        // ever be satisfied.
        val hour = AdaptiveTimingEngine.currentLocalHour()
        val resolvedTimes = runCatching { outcomeDao.resolvedTimesSince(type.wire, since) }
            .onFailure { Log.w(TAG, "hour check failed: ${it.message}", it) }
            .getOrDefault(emptyList())
        val nearby = resolvedTimes.count { within(AdaptiveTimingEngine.localHourOf(it), hour) }
        if (nearby < MIN_HOUR_SAMPLES) {
            return no(
                "$resolved of $closed led somewhere (${percent(rate)}), but few of them around this hour.",
            )
        }

        return InterruptionVerdict(
            type = type,
            mayInterrupt = true,
            reason = "$resolved of $closed led somewhere (${percent(rate)}), $nearby of them around this hour. Notifying.",
            resolved = resolved,
            closed = closed,
            unobservable = unobservable,
        )
    }

    /**
     * Whether a notification may be sent at all right now, regardless of
     * category. Earning per category must not mean eight categories each get
     * to interrupt.
     */
    suspend fun withinGlobalCaps(now: Long = System.currentTimeMillis()): Boolean {
        val lastHour = runCatching { outcomeDao.allNotificationsSince(now - HOUR_MS) }
            .onFailure { Log.w(TAG, "hourly cap check failed: ${it.message}", it) }
            .getOrDefault(Int.MAX_VALUE)
        if (lastHour >= MAX_PER_HOUR) return false

        val today = runCatching { outcomeDao.allNotificationsSince(now - DAY_MS) }
            .onFailure { Log.w(TAG, "daily cap check failed: ${it.message}", it) }
            .getOrDefault(Int.MAX_VALUE)
        return today < MAX_PER_DAY
    }

    /** Every category's standing, for the settings screen. */
    suspend fun allVerdicts(
        policies: Map<ProactiveFindingType, InterruptionPolicy> = emptyMap(),
        now: Long = System.currentTimeMillis(),
    ): List<InterruptionVerdict> = ProactiveFindingType.entries.map { type ->
        verdict(type, policies[type] ?: InterruptionPolicy.EARNED, now)
    }

    private suspend fun dismissedNotificationRecently(type: ProactiveFindingType, now: Long): Boolean {
        val notified = outcomeDao.notificationsSince(type.wire, now - LOCKOUT_MS)
        if (notified == 0) return false
        // A dismissal inside the lockout window, on a category that has in fact
        // been notifying, is treated as being about that notification.
        return interactionDao.recent(RECENT_INTERACTIONS)
            .any { it.action == "dismissed" && it.timestamp >= now - LOCKOUT_MS }
    }

    private fun within(candidate: Int, target: Int): Boolean {
        val diff = ((candidate - target + 24) % 24)
        return diff <= 1 || diff >= 23
    }

    private fun percent(value: Float): String = "${(value * 100).toInt()}%"

    companion object {
        private const val TAG = "InterruptionLedger"

        /** Same length as the event retention sweep, deliberately. Move both or neither. */
        const val WINDOW_MS = 30L * 24 * 60 * 60 * 1000

        /**
         * Closed outcomes required before a category may earn anything.
         *
         * Eight because a run of luck over three or five would buy a permanent
         * right to interrupt, and thirty would mean a category that fires twice
         * a month never graduates at all.
         */
        const val MIN_CLOSED = 8

        /** Earn at this rate. */
        const val EARN_RATE = 0.50f

        /**
         * Revoke below this. The gap from [EARN_RATE] is hysteresis, so a
         * category sitting on the bar does not flap on a single sample.
         */
        const val REVOKE_RATE = 0.35f

        /** Successes required in this hour ±1. */
        const val MIN_HOUR_SAMPLES = 2

        const val MAX_PER_HOUR = 1
        const val MAX_PER_DAY = 3

        const val LOCKOUT_MS = 7L * 24 * 60 * 60 * 1000
        private const val HOUR_MS = 60L * 60 * 1000
        private const val DAY_MS = 24L * 60 * 60 * 1000
        private const val RECENT_INTERACTIONS = 50
    }
}
