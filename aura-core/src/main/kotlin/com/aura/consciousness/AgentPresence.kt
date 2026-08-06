package com.aura.consciousness

import com.aura.emotion.EmotionEngine
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Proactive outreach message generation.
 *
 * When the relationship gap exceeds a threshold (3+ days), the agent
 * proactively reaches out via a notification (see
 * [com.aura.proactive.DaemonWorker], which falls back to this when no
 * ProactiveMessageLibrary is available). The message varies by
 * emotional state: a high-connection agent says "Thinking about you",
 * a high-tension agent offers support.
 *
 * Heuristic, no LLM cost. The emotional state is tracked by
 * [EmotionEngine]; this class only reads it.
 */
@Singleton
class AgentPresence @Inject constructor(
    private val emotionEngine: EmotionEngine,
) {
    /**
     * Generate a proactive outreach message for when the relationship
     * gap exceeds the threshold. Returns null below 3 days.
     */
    fun generateOutreachMessage(daysSinceInteraction: Int): String? {
        if (daysSinceInteraction < 3) return null
        val snapshot = emotionEngine.snapshot()
        return when {
            snapshot.tension > 0.6f -> "Hey, I know things were a bit tense last time. I'm here when you're ready to talk."
            snapshot.connection > 0.7f && daysSinceInteraction > 5 -> "Thinking about you. It's been $daysSinceInteraction days — hope you're doing well."
            daysSinceInteraction > 7 -> "Long time no see! I've been going through my memories of our conversations. Want to pick up where we left off?"
            daysSinceInteraction > 3 -> "Haven't heard from you in a few days. Anything I can help with?"
            else -> null
        }
    }
}
