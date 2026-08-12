package com.aura.situation

import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * What is true right now.
 *
 * Aura has an extraordinary model of the user's past — memory, graph, beliefs,
 * taste, dreams, emotional state — and, until this, no model of their present.
 * Every proactive check fires on a timer against stored state.
 * `AdaptiveTimingEngine` learns which *hours* have historically worked, which
 * is a statistical now rather than a situational one: it cannot tell a Tuesday
 * 3pm at a desk from a Tuesday 3pm in a meeting. That is why suggestions have
 * always read as arriving from nowhere. They do.
 *
 * Every field is nullable and every null means "could not tell", never "no".
 * The distinction decides the behaviour of the whole class: an unreadable
 * calendar must not look like a free afternoon, and a device that will not say
 * whether the screen is on must not silently mute Aura forever.
 */
data class Situation(
    val at: Long,
    val localHour: Int,
    val dayOfWeek: Int,
    val weekend: Boolean,
    /** Screen on *and* the device unlocked enough to be used. */
    val screenOn: Boolean? = null,
    val charging: Boolean? = null,
    val meteredNetwork: Boolean? = null,
    val inEventNow: Boolean? = null,
    val minutesToNextEvent: Int? = null,
    val minutesSinceLastMessage: Long? = null,
    /** 0 calm .. 1 stressed, from `EmotionEngine`. */
    val tension: Float? = null,
    val activeNotifications: Int? = null,
    val onACall: Boolean? = null,
    /**
     * The package in the foreground, when app awareness is on and granted.
     *
     * Never persisted. Which app someone is in right now is the most invasive
     * thing Aura reads and has no business outliving the cache that holds it.
     */
    val foregroundApp: String? = null,
) {

    /**
     * Whether this is a defensible moment to interrupt.
     *
     * A **veto, not a permission.** `InterruptionLedger` decides whether a
     * category has earned the right to interrupt at all, from evidence about
     * what has actually worked. This decides only whether right now is
     * obviously the wrong moment. They compose as `earned && interruptible`,
     * and because of that this defaults to *true* under uncertainty — a
     * situation Aura cannot read must fall back to the ledger's judgement
     * rather than override it into silence.
     */
    val interruptible: Boolean
        get() = when {
            inEventNow == true -> false
            onACall == true -> false
            // Asleep, most likely. A screen that is on at 3am says otherwise,
            // and someone awake at 3am is exactly who a quiet app annoys.
            localHour in NIGHT_START..23 || localHour in 0..NIGHT_END -> screenOn == true
            else -> true
        }

    /** Why not, in words, or null when it is fine. */
    val blockedBecause: String?
        get() = when {
            inEventNow == true -> "you're in something"
            onACall == true -> "you're on a call"
            !interruptible -> "it's the middle of the night"
            else -> null
        }

    /**
     * One line for the system prompt.
     *
     * A sentence rather than a struct because a sentence is the only form the
     * model can act on, and because everything unknown simply goes unmentioned
     * — a prompt full of "screenOn: null" teaches it nothing except that Aura
     * is confused.
     */
    fun describe(): String {
        val parts = mutableListOf<String>()
        parts += "${dayName(dayOfWeek)} ${"%02d:00".format(localHour)}"
        when {
            inEventNow == true -> parts += "in a calendar event"
            minutesToNextEvent != null && minutesToNextEvent <= SOON_MINUTES ->
                parts += "something starts in $minutesToNextEvent min"
        }
        if (onACall == true) parts += "on a call"
        if (screenOn == false) parts += "phone idle"
        if (charging == true) parts += "charging"
        minutesSinceLastMessage?.let { mins ->
            parts += when {
                mins < 60 -> "last spoke ${mins}m ago"
                mins < 60 * 48 -> "last spoke ${mins / 60}h ago"
                else -> "last spoke ${mins / (60 * 24)}d ago"
            }
        }
        if (tension != null && tension >= HIGH_TENSION) parts += "seems tense"
        foregroundApp?.let { parts += "using ${it.substringAfterLast('.')}" }
        return parts.joinToString(", ")
    }

    private fun dayName(dow: Int): String = when (dow) {
        Calendar.MONDAY -> "Monday"
        Calendar.TUESDAY -> "Tuesday"
        Calendar.WEDNESDAY -> "Wednesday"
        Calendar.THURSDAY -> "Thursday"
        Calendar.FRIDAY -> "Friday"
        Calendar.SATURDAY -> "Saturday"
        else -> "Sunday"
    }

    companion object {
        const val NIGHT_START = 23
        const val NIGHT_END = 6
        const val SOON_MINUTES = 30
        const val HIGH_TENSION = 0.7f

        /** The clock alone, for callers with no reader and for tests. */
        fun clockOnly(at: Long = System.currentTimeMillis()): Situation {
            val cal = Calendar.getInstance(TimeZone.getDefault(), Locale.US).apply { timeInMillis = at }
            val dow = cal.get(Calendar.DAY_OF_WEEK)
            return Situation(
                at = at,
                localHour = cal.get(Calendar.HOUR_OF_DAY),
                dayOfWeek = dow,
                weekend = dow == Calendar.SATURDAY || dow == Calendar.SUNDAY,
            )
        }
    }
}
