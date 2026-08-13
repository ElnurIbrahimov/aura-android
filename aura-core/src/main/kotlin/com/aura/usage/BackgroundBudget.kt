package com.aura.usage

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import javax.inject.Inject
import javax.inject.Singleton

/** Thrown by `ProviderRegistry.chat` when unattended work has spent the day's tokens. */
class BackgroundBudgetExhausted(spent: Long, limit: Long) :
    IllegalStateException("daily background budget spent ($spent/$limit tokens)")

/** What the budget knows right now, for the Usage screen. */
data class BackgroundSpend(
    val day: String = "",
    val tokens: Long = 0,
    val limit: Long = BackgroundBudget.DEFAULT_DAILY_TOKENS,
    val blockedCalls: Long = 0,
) {
    val exhausted: Boolean get() = tokens >= limit
    val fraction: Float get() = if (limit <= 0) 0f else (tokens.toFloat() / limit).coerceIn(0f, 1f)
}

/**
 * A ceiling on what Aura spends when nobody asked it to.
 *
 * [UsageTracker] counts tokens and has never capped them, and `ToolPolicy`'s
 * `costCeiling` has been allowlisted in `DeadConfigFieldTest` as a field that
 * decides nothing since it was written. So there was no bound at all on the
 * daemon, dream consolidation, the morning brief, curiosity authoring and daily
 * research — all of which run on a timer, on whatever the chat model is. Seeding
 * `backgroundModel` on 2026-08-13 switched four of those on at once, which is
 * what made this urgent rather than theoretical.
 *
 * **Tokens, not currency.** `ModelCatalog` carries no pricing, and a price table
 * maintained by hand against seventeen providers would drift into being confidently
 * wrong — which is worse than an honest token number the user can reason about.
 *
 * **Attended calls are never checked.** A chat turn refused because a dream cycle
 * spent the budget at 4am would be a far worse failure than the one this prevents.
 * The check reads [ChatOptions.attended], which defaults to true.
 *
 * Stored in SharedPreferences rather than a Room table, matching [UsageTracker]
 * next door. A counter that resets at midnight and means nothing tomorrow does not
 * need a schema version, a migration, a backup mapper and three doc counts — and
 * the guarantee here is "spend is bounded", not "spend is queryable".
 */
@Singleton
class BackgroundBudget private constructor(
    private val preferences: SharedPreferences?,
    private val clock: () -> Long,
) {
    @Inject
    constructor(@ApplicationContext context: Context) : this(
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE),
        { System.currentTimeMillis() },
    )

    /** In-memory, fixed-clock constructor for tests. */
    constructor(now: () -> Long) : this(null, now)

    private var memoryDay: String = ""
    private var memoryTokens: Long = 0
    private var memoryBlocked: Long = 0

    private val _spend = MutableStateFlow(read())
    val spend: StateFlow<BackgroundSpend> = _spend.asStateFlow()

    /**
     * The configured ceiling.
     *
     * Deliberately generous: this is a backstop against a runaway loop or a
     * misconfigured expensive model, not a fine-grained cost control. A normal
     * day of background work is a handful of calls; a day that reaches this has
     * something wrong with it, and that is the thing worth catching.
     */
    @Volatile
    var dailyLimit: Long = DEFAULT_DAILY_TOKENS

    /** False when unattended work has spent the day. Attended callers never ask. */
    @Synchronized
    fun hasHeadroom(now: Long = clock()): Boolean = current(dayOf(now)).tokens < dailyLimit

    /** Count tokens an unattended call actually used. */
    @Synchronized
    fun record(tokens: Long, now: Long = clock()) {
        if (tokens <= 0) return
        val day = dayOf(now)
        val state = current(day)
        write(state.copy(tokens = state.tokens + tokens))
    }

    /** Count a call that was refused, so the Usage screen can say it happened. */
    @Synchronized
    fun recordBlocked(now: Long = clock()) {
        val day = dayOf(now)
        val state = current(day)
        write(state.copy(blockedCalls = state.blockedCalls + 1))
    }

    fun snapshot(now: Long = clock()): BackgroundSpend = current(dayOf(now))

    /**
     * Today's row, rolling over at local midnight.
     *
     * Local, not UTC: "today" is the user's day. `AdaptiveTimingEngine` bucketed
     * in UTC while reading local and scored every hour at zero for it — the same
     * mistake is available here.
     */
    private fun current(day: String): BackgroundSpend {
        val state = read()
        return if (state.day == day) state else BackgroundSpend(day = day, limit = dailyLimit)
    }

    private fun read(): BackgroundSpend {
        if (preferences == null) {
            return BackgroundSpend(memoryDay, memoryTokens, dailyLimit, memoryBlocked)
        }
        return BackgroundSpend(
            day = preferences.getString(KEY_DAY, "").orEmpty(),
            tokens = preferences.getLong(KEY_TOKENS, 0L),
            limit = dailyLimit,
            blockedCalls = preferences.getLong(KEY_BLOCKED, 0L),
        )
    }

    private fun write(state: BackgroundSpend) {
        if (preferences == null) {
            memoryDay = state.day
            memoryTokens = state.tokens
            memoryBlocked = state.blockedCalls
        } else {
            preferences.edit()
                .putString(KEY_DAY, state.day)
                .putLong(KEY_TOKENS, state.tokens)
                .putLong(KEY_BLOCKED, state.blockedCalls)
                .apply()
        }
        _spend.value = state.copy(limit = dailyLimit)
    }

    private fun dayOf(now: Long): String {
        val cal = Calendar.getInstance(TimeZone.getDefault(), Locale.US).apply { timeInMillis = now }
        return "%04d-%02d-%02d".format(
            cal.get(Calendar.YEAR),
            cal.get(Calendar.MONTH) + 1,
            cal.get(Calendar.DAY_OF_MONTH),
        )
    }

    companion object {
        private const val PREFERENCES_NAME = "aura_background_budget"
        private const val KEY_DAY = "day"
        private const val KEY_TOKENS = "tokens"
        private const val KEY_BLOCKED = "blocked"

        /**
         * 400k tokens a day.
         *
         * A dream cycle, a morning brief, a research call and a day of daemon
         * passes is comfortably inside this. A runaway loop is not.
         */
        const val DEFAULT_DAILY_TOKENS = 400_000L
    }
}
