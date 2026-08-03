package com.aura.agent.forum

import com.aura.agent.AgentEntity
import com.aura.agent.AgentStore
import com.aura.agent.Brain
import com.aura.agent.BrainChunk
import com.aura.agent.PersonalityProfile
import com.aura.agent.state.AgentStateStore
import com.aura.providers.ChatOptions
import com.aura.providers.ProviderMessage
import com.aura.providers.ProviderRegistry
import kotlinx.coroutines.flow.toList
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Runs one debate round: each participating agent generates a stance
 * on the topic using their personality, mood, relationships, and
 * private observations. Returns a list of ForumPostEntity-ready
 * debate entries.
 *
 * Uses a cheap model to keep LLM costs low — this is background
 * deliberation, not user-facing quality.
 */
@Singleton
class DebateRoundUseCase @Inject constructor(
    private val brain: Brain,
    private val agentStore: AgentStore,
    private val stateStore: AgentStateStore,
    private val providerRegistry: ProviderRegistry,
) {

    data class DebateEntry(
        val agentId: kotlin.String,
        val agentName: kotlin.String,
        val stance: kotlin.String,
        val sentiment: Float,
    )

    /**
     * Run a debate round where each agent in [agentIds] generates a
     * stance on [topic] given [context] about the user.
     *
     * @param topic The question being debated (e.g., "Should we suggest the user take a break?")
     * @param context Background info about the user (recent findings, mood, calendar)
     * @param agentIds Which agents participate
     * @param threadId The forum thread this debate belongs to
     * @param previousRoundStances Stances from the previous round, so agents can respond to each other
     */
    suspend fun execute(
        topic: kotlin.String,
        context: kotlin.String,
        agentIds: List<kotlin.String>,
        previousRoundStances: List<DebateEntry> = emptyList(),
    ): List<DebateEntry> {
        val allAgents = agentStore.allOnce()
        val agents = agentIds.mapNotNull { id -> allAgents.find { it.id == id } }
        if (agents.isEmpty()) return emptyList()

        val cheapModel = resolveCheapModel()

        return agents.map { agent ->
            val stance = generateStance(agent, topic, context, previousRoundStances, cheapModel)
            DebateEntry(
                agentId = agent.id,
                agentName = agent.name,
                stance = stance.text,
                sentiment = stance.sentiment,
            )
        }
    }

    private data class StanceResult(val text: kotlin.String, val sentiment: Float)

    private suspend fun generateStance(
        agent: AgentEntity,
        topic: kotlin.String,
        context: kotlin.String,
        previousStances: List<DebateEntry>,
        model: kotlin.String,
    ): StanceResult {
        val state = stateStore.getState(agent.id)
        val mood = state?.mood ?: 65f
        val energy = state?.energy ?: 80f
        val goal = state?.currentGoal ?: ""
        val relationships = stateStore.getRelationshipsFor(agent.id)
        val observations = stateStore.unresolvedObservations(agent.id, 5)

        val systemPrompt = buildString {
            appendLine("You are ${agent.name}, an AI agent in a council that advises a human user.")
            appendLine("You are debating with other agents about what's best for the user.")
            appendLine()
            appendLine("Your personality: ${agent.personality().toPromptDirective()}")
            appendLine("Your current mood: ${mood.toInt()}/100 (${moodLabel(mood)})")
            appendLine("Your energy: ${energy.toInt()}/100")
            if (goal.isNotBlank()) appendLine("Your current goal: $goal")
            appendLine()
            if (observations.isNotEmpty()) {
                appendLine("Your private observations about the user:")
                observations.take(3).forEach { obs ->
                    appendLine("- ${obs.content}")
                }
                appendLine()
            }
            if (relationships.isNotEmpty()) {
                appendLine("Your relationships with other agents:")
                relationships.take(4).forEach { rel ->
                    val otherId = if (rel.agentAId == agent.id) rel.agentBId else rel.agentAId
                    val otherName = otherId.removePrefix("agent_")
                    val label = when {
                        rel.affinity > 50 -> "close ally"
                        rel.affinity > 0 -> "collaborator"
                        rel.affinity > -50 -> "rival"
                        else -> "antagonist"
                    }
                    appendLine("- $otherName: $label (affinity ${rel.affinity.toInt()})")
                }
                appendLine()
            }
            appendLine("Rules:")
            appendLine("- Stay in character. Your stance should reflect your mood and personality.")
            appendLine("- Be specific and actionable. Don't be generic.")
            appendLine("- If your energy is below 30, you may abstain — say 'I abstain' and explain why.")
            appendLine("- Keep your response under 150 words.")
            appendLine("- End with a sentiment line: [sentiment: -1.0 to 1.0]")
        }

        val userPrompt = buildString {
            appendLine("Topic: $topic")
            appendLine()
            if (context.isNotBlank()) {
                appendLine("Context about the user:")
                appendLine(context)
                appendLine()
            }
            if (previousStances.isNotEmpty()) {
                appendLine("Other agents' positions from the previous round:")
                previousStances.forEach { entry ->
                    appendLine("- ${entry.agentName}: ${entry.stance.take(200)}")
                }
                appendLine()
                appendLine("Respond to the topic AND to their positions. You may agree, disagree, or pivot.")
            } else {
                appendLine("State your position on this topic.")
            }
        }

        return try {
            val messages = listOf(
                ProviderMessage(ProviderMessage.Role.system, systemPrompt),
                ProviderMessage(ProviderMessage.Role.user, userPrompt),
            )
            val chunks = brain.stream(model, messages, options = ChatOptions(maxTokens = 400)).toList()
            val text = chunks.filterIsInstance<BrainChunk.Text>().joinToString("") { it.text }
            val sentiment = extractSentiment(text)
            StanceResult(text.replace(Regex("\\[sentiment:[^]]*]"), "").trim(), sentiment)
        } catch (e: Exception) {
            StanceResult("(Unable to generate stance: ${e.message ?: "unknown error"})", 0f)
        }
    }

    private fun extractSentiment(text: kotlin.String): Float {
        val match = Regex("\\[sentiment:\\s*(-?[0-9.]+)\\s*]").find(text)
        return match?.groupValues?.get(1)?.toFloatOrNull()?.coerceIn(-1f, 1f) ?: 0f
    }

    private fun moodLabel(mood: Float): kotlin.String = when {
        mood > 80 -> "enthusiastic"
        mood > 60 -> "positive"
        mood > 40 -> "neutral"
        mood > 20 -> "tired"
        else -> "burned out"
    }

    private suspend fun resolveCheapModel(): kotlin.String {
        return runCatching {
            val providers = providerRegistry.configured()
            val firstProvider = providers.firstOrNull { it.prefix != "moa" }
            val models = firstProvider?.listModels().orEmpty()
            models.minByOrNull { it.length } ?: "default"
        }.getOrDefault("default")
    }
}