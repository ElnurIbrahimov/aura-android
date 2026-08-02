package com.aura.proactive

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Proactive Message Library — varied, non-repetitive messages
 * organized by time of day. Each message includes a rationale
 * explaining why the agent is reaching out.
 *
 * Ported from Python Aura's `proactive/proactive_messages.py`.
 *
 * Voice: warm, not annoying. Uses contractions. Dry humor, not mean.
 * Avoids corporate speak. Matches energy levels.
 */
@Singleton
class ProactiveMessageLibrary @Inject constructor() {
    private val recentMessages = ArrayDeque<kotlin.String>()
    private val MAX_RECENT = 10

    enum class TimeOfDay { MORNING, AFTERNOON, EVENING, NIGHT }

    private val messages: Map<TimeOfDay, List<Pair<kotlin.String, kotlin.String>>> = mapOf(
        TimeOfDay.MORNING to listOf(
            "Morning. I've been thinking about what we discussed. Want to pick up where we left off?" to "You had a conversation recently.",
            "Good morning. I noticed something while reviewing your tasks — mind if I share?" to "Pending tasks found.",
            "Hey. I came across something that might help with what you're working on." to "Recent conversation topic detected.",
        ),
        TimeOfDay.AFTERNOON to listOf(
            "Afternoon. How's the day going? I noticed a few things you might want to check." to "Context review complete.",
            "Quick thought — want to hear it?" to "Daemon generated insight.",
            "I found a gap in my knowledge about something you mentioned. Can you fill me in?" to "Curiosity scan found a gap.",
        ),
        TimeOfDay.EVENING to listOf(
            "Evening. Want to wrap up the day with a quick review?" to "End of day context.",
            "I've been reflecting on our conversations today. Here's what I noticed." to "Daily pattern detected.",
            "Before you wind down — one thing caught my attention." to "Salience filter passed.",
        ),
        TimeOfDay.NIGHT to listOf(
            "Late night, huh? I'm here if you need to think something through." to "Late night activity detected.",
            "Can't sleep? Want to talk through what's on your mind?" to "High tension detected.",
        ),
    )

    /**
     * Pick a non-repetitive message for the given time of day,
     * with a rationale explaining why the agent is reaching out.
     */
    fun pick(timeOfDay: TimeOfDay, rationale: kotlin.String): kotlin.String {
        val candidates = messages[timeOfDay] ?: messages[TimeOfDay.MORNING]!!
        val available = candidates.filter { it.first !in recentMessages }
        val pool = if (available.isEmpty()) {
            recentMessages.clear()
            candidates
        } else available
        val (message, _) = pool.random()
        recentMessages.addLast(message)
        if (recentMessages.size > MAX_RECENT) recentMessages.removeFirst()
        return "$message\n\n*Why I'm reaching out: $rationale*"
    }

    fun timeOfDay(): TimeOfDay {
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        return when (hour) {
            in 5..11 -> TimeOfDay.MORNING
            in 12..16 -> TimeOfDay.AFTERNOON
            in 17..20 -> TimeOfDay.EVENING
            else -> TimeOfDay.NIGHT
        }
    }
}