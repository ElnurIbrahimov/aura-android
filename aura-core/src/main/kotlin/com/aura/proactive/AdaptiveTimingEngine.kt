package com.aura.proactive

import javax.inject.Inject
import javax.inject.Singleton
import android.util.Log
import java.util.Calendar
import kotlin.math.abs

/**
 * Learns which hours the user is receptive to proactive messages.
 *
 * Inspired by ProActor (ACL 2026) timing-aware scheduling and
 * ProMemAssist (UIST 2025) context-aware deferral.
 *
 * Two defects made this permanently unable to say yes, and both are fixed here.
 *
 * **The scale could not represent a positive.** Scores were normalised by
 * dividing by their own maximum, coerced to at least 1, then clamped to
 * `[0, 1]`. Since `"dismissed"` was the only interaction anything ever
 * recorded, every raw score was negative, every normalised score clamped to 0,
 * and `isGoodTime()`'s `>= 0.4` was false for the life of the install — not on
 * a fresh one, but always. The replacement maps the signed score through a
 * saturating curve with a real neutral point, so **no evidence is 0.5 rather
 * than 0**, and "not actively a bad time" is the default rather than "never".
 *
 * **The buckets were UTC and the lookup was local.** Rows were bucketed by
 * `timestamp / 3_600_000 % 24` — hours since the epoch, i.e. the UTC hour —
 * while [isGoodTime] read `Calendar.HOUR_OF_DAY`, which is local. Four hours
 * out here, and silently wrong everywhere off Greenwich.
 */
@Singleton
class AdaptiveTimingEngine @Inject constructor(
    private val proactiveInteractionDao: ProactiveInteractionDao,
    private val outcomeDao: ProactiveOutcomeDao? = null,
) {
    /**
     * How receptive each local hour has been.
     *
     * **Outcomes are the primary signal and taps are secondary, at half
     * weight.** A tap says the card looked interesting; the thing it was about
     * actually resolving says the card was right, and only the second is worth
     * choosing a moment by. Interactions still contribute so that an install
     * with no closed outcomes yet is not flying blind.
     */
    suspend fun hourlyEngagement(): FloatArray {
        val raw = FloatArray(24) { 0f }

        val outcomes = runCatching { outcomeDao?.tallyForTiming(0L).orEmpty() }
            .onFailure { Log.w(TAG, "outcome read failed: ${it.message}", it) }
            .getOrDefault(emptyList())
        for (row in outcomes) {
            val hour = localHourOf(row.postedAt)
            when (row.outcome) {
                ProactiveOutcomeEntity.OUTCOME_RESOLVED -> raw[hour] += 1f
                ProactiveOutcomeEntity.OUTCOME_IGNORED -> raw[hour] -= 0.5f
                // unobservable and pending say nothing about the hour.
            }
        }

        val interactions = runCatching {
            proactiveInteractionDao.recent(200)
        }.onFailure { Log.w(TAG, "interaction read failed: ${it.message}", it) }.getOrDefault(emptyList())
        for (interaction in interactions) {
            val hour = localHourOf(interaction.timestamp)
            when (interaction.action) {
                "tapped", "acted" -> raw[hour] += 0.5f
                "dismissed", "snoozed" -> raw[hour] -= 0.25f
            }
        }

        return FloatArray(24) { normalize(raw[it]) }
    }

    /** The current hour's receptivity, as a continuous value rather than a verdict. */
    suspend fun receptivityNow(): Float = hourlyEngagement()[currentLocalHour()]

    suspend fun isGoodTime(): Boolean = receptivityNow() >= GOOD_TIME_THRESHOLD

    /**
     * Map a signed score onto `[0, 1]` with a genuine neutral.
     *
     * Saturating rather than linear so a single lucky hour cannot dominate, and
     * centred on 0.5 so the absence of evidence is neither an endorsement nor a
     * veto.
     */
    private fun normalize(raw: Float): Float =
        (NEUTRAL + NEUTRAL * (raw / (abs(raw) + SOFTNESS))).coerceIn(0f, 1f)

    companion object {
        private const val TAG = "AdaptiveTimingEngine"

        /**
         * Local hour of a timestamp.
         *
         * Uses the *current* default zone for a historical row on purpose: a
         * habit belongs to the frame the user lives in now, not the one they
         * were in during a trip. The alternative is storing an offset per row
         * for a signal that is about behaviour rather than about instants.
         */
        internal fun localHourOf(timestampMs: Long): Int =
            Calendar.getInstance().apply { timeInMillis = timestampMs }.get(Calendar.HOUR_OF_DAY)

        internal fun currentLocalHour(): Int = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)

        /** No evidence either way. */
        const val NEUTRAL = 0.5f

        /** How many net points it takes to move meaningfully off neutral. */
        private const val SOFTNESS = 2f

        /**
         * Below this an hour is actively bad. Under the neutral-centred scale
         * this reads as "not a hostile hour" rather than "a proven good one",
         * which is the right bar now that notifications are gated separately.
         */
        const val GOOD_TIME_THRESHOLD = 0.4f

        private const val DEFAULT_HOUR = 9
    }
}
