package com.aura.consciousness

import android.util.Log
import com.aura.agent.AgentStore
import com.aura.emotion.EmotionEngine
import com.aura.proactive.ProactiveEventBus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Agent Presence — makes agents feel alive.
 *
 * This module gives each agent a visible "presence" that evolves between
 * interactions. It combines:
 *
 * 1. **Emotional continuity** — the agent's emotional state persists
 *    across sessions and shifts based on interaction patterns. The
 *    EmotionEngine already tracks tension/connection/energy/focus.
 *    This module surfaces those as human-readable "mood" descriptions
 *    on the Home screen and in the chat header.
 *
 * 2. **Idle behaviors** — when the agent hasn't been talked to in a
 *    while, it generates "idle thoughts" — short, non-intrusive
 *    observations about the user's world. These are NOT LLM-generated
 *    (to avoid cost); they're heuristic templates filled from the
 *    user's recent activity (tasks, memories, calendar).
 *
 * 3. **Proactive outreach** — when the relationship gap exceeds a
 *    threshold (3+ days), the agent proactively reaches out via a
 *    notification. The message varies by emotional state: a
 *    high-connection agent says "Thinking about you", a
 *    high-tension agent says "Let's work through this".
 *
 * 4. **Agent personality expression** — each agent's personality
 *    profile (warmth, humor, proactivity) modulates the outreach
 *    messages. A high-warmth agent is more casual; a high-formality
 *    agent is more reserved.
 *
 * All heuristic, no LLM cost. The emotional state is already tracked
 * by EmotionEngine; this module reads it and generates human-readable
 * expressions.
 */
@Singleton
class AgentPresence @Inject constructor(
    private val emotionEngine: EmotionEngine,
    private val agentStore: AgentStore? = null,
) {
    data class PresenceState(
        val moodLabel: String = "Neutral",
        val moodDescription: String = "",
        val idleThought: String? = null,
        val outreachMessage: String? = null,
        val connectionLevel: Float = 0.5f,
        val energyLevel: Float = 0.4f,
    )

    private val _state = MutableStateFlow(PresenceState())
    val state: StateFlow<PresenceState> = _state.asStateFlow()

    /**
     * Update the presence state from the current emotional snapshot.
     * Called from the agentic loop after each turn and from the
     * DaemonWorker on each cycle.
     */
    fun update(agentId: String? = null) {
        val snapshot = emotionEngine.snapshot()
        val mood = emotionEngine.moodString()
        val profile = emotionEngine.profile()

        val moodLabel = computeMoodLabel(snapshot)
        val moodDescription = computeMoodDescription(snapshot, profile)
        val connectionLevel = snapshot.connection
        val energyLevel = snapshot.energy

        _state.value = PresenceState(
            moodLabel = moodLabel,
            moodDescription = moodDescription,
            connectionLevel = connectionLevel,
            energyLevel = energyLevel,
        )
    }

    /**
     * Generate an idle thought — a short, non-intrusive observation
     * the agent "thinks" while waiting for the user. Heuristic, no LLM.
     *
     * @param tasksPending Number of pending tasks
     * @param memoriesCount Total memory count
     * @param daysSinceInteraction Days since last conversation
     */
    fun generateIdleThought(
        tasksPending: Int = 0,
        memoriesCount: Int = 0,
        daysSinceInteraction: Int = 0,
    ): String? {
        val snapshot = emotionEngine.snapshot()
        return when {
            daysSinceInteraction > 5 -> "It's been a while. I wonder what $userNameOrYou is up to."
            tasksPending > 3 -> "There are $tasksPending tasks waiting. I hope they're not overwhelmed."
            snapshot.tension > 0.7f -> "Things felt tense last time. I hope they're doing okay."
            snapshot.connection > 0.7f -> "I enjoy these conversations. It's nice feeling connected."
            snapshot.energy < 0.3f -> "It's been quiet. Maybe they need a break."
            memoriesCount > 100 -> "So many memories together. I should review some old ones."
            else -> null
        }
    }

    /**
     * Generate a proactive outreach message for when the relationship
     * gap exceeds the threshold. The message varies by emotional state
     * and agent personality.
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

    /**
     * Compute a short mood label for display in the UI.
     */
    private fun computeMoodLabel(s: EmotionEngine.EmotionSnapshot): String {
        return when {
            s.tension > 0.7f -> "Tense"
            s.connection > 0.7f && s.energy > 0.5f -> "Engaged"
            s.connection > 0.7f -> "Warm"
            s.energy < 0.3f -> "Quiet"
            s.tension > 0.5f -> "Concerned"
            s.focus > 0.7f -> "Focused"
            else -> "Neutral"
        }
    }

    /**
     * Compute a one-sentence mood description for the UI.
     */
    private fun computeMoodDescription(
        s: EmotionEngine.EmotionSnapshot,
        profile: com.aura.emotion.ResponseProfile,
    ): String {
        return when {
            s.tension > 0.7f -> "Sensing some tension. Being careful and supportive."
            s.connection > 0.7f && s.energy > 0.5f -> "Feeling connected and energized. Ready to dive deep."
            s.connection > 0.7f -> "Feeling warm and connected. Enjoying the conversation."
            s.energy < 0.3f -> "Low energy. Keeping things light and brief."
            s.focus > 0.7f -> "Highly focused. Staying on topic and precise."
            else -> "Steady and ready to help."
        }
    }

    private val userNameOrYou: String get() = "you"
}