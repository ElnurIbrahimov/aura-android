package com.aura.agent.council

import com.aura.agent.AgentStore
import com.aura.agent.forum.DebateRoundUseCase
import com.aura.agent.forum.ForumEngine
import com.aura.agent.state.AgentStateStore
import com.aura.proactive.ProactiveAwarenessEngine
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Result of a council session — either a proposal that reached quorum
 * or a log of what was debated.
 */
data class CouncilResult(
    val threadId: kotlin.String,
    val topic: kotlin.String,
    val debateEntries: List<DebateRoundUseCase.DebateEntry>,
    val proposal: Intervention? = null,
    val quorumReached: Boolean = false,
    val voteTally: ForumEngine.VoteTally? = null,
    val dissent: kotlin.String? = null,
)

/**
 * Runs the overnight council: selects relevant agents, runs 2-3 debate
 * rounds, votes on proposals, and generates interventions if quorum
 * is reached.
 *
 * Designed to be called from [com.aura.proactive.DaemonWorker] during
 * idle/charging windows. Nullable injection — same pattern as other
 * proactive components.
 */
@Singleton
class CouncilOrchestrator @Inject constructor(
    private val agentStore: AgentStore,
    private val stateStore: AgentStateStore,
    private val forumEngine: ForumEngine,
    private val debateUseCase: DebateRoundUseCase,
    private val moodEngine: AgentMoodEngine? = null,
) {

    /**
     * Agents that participate in life councils. Not all specialists
     * make sense — coder and phone_native are excluded.
     */
    private val lifeCouncilAgentIds = listOf(
        "agent_general",
        "agent_researcher",
        "agent_writer",
        "agent_executive",
        "agent_creative",
    )

    /** Max agents per session to keep LLM cost bounded. */
    private val maxAgentsPerSession = 4

    /** Max debate rounds. */
    private val maxRounds = 2

    /**
     * Run a council session based on proactive findings.
     *
     * @param findings Proactive findings from the awareness engine
     * @param userContext Background info about the user (calendar, mood, etc.)
     * @return list of council results, one per finding that was debated
     */
    suspend fun runFromFindings(
        findings: List<ProactiveAwarenessEngine.ProactiveFinding>,
        userContext: kotlin.String = "",
    ): List<CouncilResult> {
        if (findings.isEmpty()) return emptyList()

        val results = mutableListOf<CouncilResult>()
        // Pick top 3 findings by urgency to keep cost bounded
        val topFindings = findings.sortedByDescending { it.urgency }.take(3)

        for (finding in topFindings) {
            val result = runSession(
                topic = finding.title,
                context = buildString {
                    append(finding.message)
                    if (userContext.isNotBlank()) {
                        append("\n\nUser context:\n$userContext")
                    }
                },
            )
            results.add(result)
        }

        return results
    }

    /**
     * Run a single council session on a topic.
     */
    suspend fun runSession(
        topic: kotlin.String,
        context: kotlin.String = "",
    ): CouncilResult {
        val threadId = "council_${System.currentTimeMillis()}_${topic.hashCode().toUInt()}"
        var availableAgents = lifeCouncilAgentIds.shuffled().take(maxAgentsPerSession)

        // Apply mood decay and filter out exhausted agents
        if (moodEngine != null) {
            val now = System.currentTimeMillis()
            moodEngine.decayAll(availableAgents, now)
            availableAgents = moodEngine.filterAvailable(availableAgents)
            if (availableAgents.isEmpty()) {
                // All agents burned out — return empty result
                return CouncilResult(
                    threadId = threadId,
                    topic = topic,
                    debateEntries = emptyList(),
                    quorumReached = false,
                )
            }
        }

        // Record participation
        availableAgents.forEach { agentId ->
            runCatching { stateStore.recordParticipation(agentId) }
                .onFailure { android.util.Log.w("CouncilOrchestrator", "recordParticipation ${agentId}: ${it.message}", it) }
        }

        // Round 1: initial stances
        val round1 = debateUseCase.execute(
            topic = topic,
            context = context,
            agentIds = availableAgents,
        )

        // Post round 1 stances to forum
        for (entry in round1) {
            forumEngine.post(
                threadId = threadId,
                agentId = entry.agentId,
                type = "debate",
                title = "${entry.agentName}'s position",
                body = entry.stance,
                sentiment = entry.sentiment,
            )
        }

        // Round 2: respond to each other
        val round2 = if (round1.isNotEmpty()) {
            debateUseCase.execute(
                topic = topic,
                context = context,
                agentIds = availableAgents,
                previousRoundStances = round1,
            )
        } else emptyList()

        // Post round 2 stances
        for (entry in round2) {
            forumEngine.post(
                threadId = threadId,
                agentId = entry.agentId,
                type = "debate",
                title = "${entry.agentName}'s response",
                body = entry.stance,
                sentiment = entry.sentiment,
            )
        }

        // Find the strongest proposal from the debate (highest sentiment alignment)
        val allEntries = round1 + round2
        val proposalEntry = allEntries.maxByOrNull { it.sentiment }

        // If someone proposed something, create a proposal post and vote
        var intervention: Intervention? = null
        var quorumReached = false
        var tally: ForumEngine.VoteTally? = null
        var dissent: kotlin.String? = null

        if (proposalEntry != null && proposalEntry.sentiment > -0.5f) {
            val proposalPostId = forumEngine.post(
                threadId = threadId,
                agentId = proposalEntry.agentId,
                type = "proposal",
                title = "Council proposal: $topic",
                body = proposalEntry.stance,
                sentiment = proposalEntry.sentiment,
            )

            // Vote: each agent votes based on whether their stance aligned
            for (entry in allEntries.distinctBy { it.agentId }) {
                val vote = when {
                    entry.agentId == proposalEntry.agentId -> "for"
                    entry.sentiment > 0.2f -> "for"
                    entry.sentiment < -0.2f -> "against"
                    else -> "abstain"
                }
                forumEngine.vote(proposalPostId, entry.agentId, vote, entry.stance.take(100))
            }

            tally = forumEngine.tally(proposalPostId)
            quorumReached = forumEngine.hasQuorum(proposalPostId)

            if (quorumReached) {
                // Generate a concrete intervention from the proposal
                intervention = extractIntervention(topic, proposalEntry.stance, context)
                forumEngine.setStatus(proposalPostId, "approved")

                // Record relationship shifts
                val forVoters = allEntries.filter { it.sentiment > 0.2f }.map { it.agentId }
                val againstVoters = allEntries.filter { it.sentiment < -0.2f }.map { it.agentId }
                recordRelationshipShifts(forVoters, againstVoters)
            } else {
                forumEngine.setStatus(proposalPostId, "rejected")
                dissent = allEntries.filter { it.sentiment < -0.2f }
                    .joinToString("; ") { "${it.agentName}: ${it.stance.take(80)}" }
            }
        }

        // Record observations for each agent
        for (entry in allEntries.distinctBy { it.agentId }) {
            stateStore.addObservation(
                agentId = entry.agentId,
                targetType = "user",
                content = "Council on '$topic': ${entry.stance.take(120)}",
                sentiment = entry.sentiment,
            )
        }

        return CouncilResult(
            threadId = threadId,
            topic = topic,
            debateEntries = allEntries,
            proposal = intervention,
            quorumReached = quorumReached,
            voteTally = tally,
            dissent = dissent,
        )
    }

    /**
     * Extract a concrete intervention from the debate text.
     * This is a heuristic extractor — a future version could use an LLM.
     */
    private fun extractIntervention(
        topic: kotlin.String,
        stance: kotlin.String,
        context: kotlin.String,
    ): Intervention {
        // Heuristic: detect keywords to pick intervention type
        val lower = stance.lowercase()
        return when {
            "break" in lower || "walk" in lower || "sleep" in lower || "rest" in lower ->
                Intervention.SelfCare(
                    suggestion = stance.take(200),
                    rationale = "Council agreed: $topic",
                )
            "remind" in lower || "remember" in lower || "don't forget" in lower ->
                Intervention.Reminder(
                    message = topic,
                    triggerAt = System.currentTimeMillis() + 3600_000L, // 1h from now
                    rationale = stance.take(150),
                )
            "message" in lower || "email" in lower || "text" in lower || "call" in lower ->
                Intervention.Message(
                    recipient = "unknown",
                    draftBody = stance.take(300),
                    rationale = "Council proposed reaching out about: $topic",
                )
            "schedule" in lower || "calendar" in lower || "task" in lower || "plan" in lower ->
                Intervention.Schedule(
                    title = topic,
                    description = stance.take(200),
                )
            "memory" in lower || "remember when" in lower || "recall" in lower ->
                Intervention.Memory(
                    memoryId = "",
                    connection = stance.take(200),
                    rationale = "Council surfaced a memory connection: $topic",
                )
            else ->
                Intervention.SelfCare(
                    suggestion = stance.take(200),
                    rationale = "Council proposal: $topic",
                )
        }
    }

    /**
     * Record relationship shifts: agents that voted together gain affinity,
     * agents that voted against each other lose affinity. Agents that
     * co-sponsor (vote together on 3+ proposals) gain an extra bond.
     */
    private suspend fun recordRelationshipShifts(
        forVoters: List<kotlin.String>,
        againstVoters: List<kotlin.String>,
    ) {
        // Co-voters gain +5 affinity, +2 extra if they're already allies (>30 affinity)
        for (i in forVoters.indices) {
            for (j in i + 1 until forVoters.size) {
                runCatching {
                    val existing = stateStore.getRelationship(forVoters[i], forVoters[j])
                    val bonus = if (existing != null && existing.affinity > 30f) 2f else 0f
                    stateStore.recordInteraction(forVoters[i], forVoters[j], 5f + bonus)
                }.onFailure { android.util.Log.w("CouncilOrchestrator", "relShift for: ${it.message}", it) }
            }
        }
        // Opposing voters lose -5 affinity
        for (a in forVoters) {
            for (b in againstVoters) {
                runCatching { stateStore.recordInteraction(a, b, -5f) }
                    .onFailure { android.util.Log.w("CouncilOrchestrator", "relShift against: ${it.message}", it) }
            }
        }
        // Among against-voters, they share a bond (mutual disagreement with proposal)
        for (i in againstVoters.indices) {
            for (j in i + 1 until againstVoters.size) {
                runCatching { stateStore.recordInteraction(againstVoters[i], againstVoters[j], 2f) }
                    .onFailure { android.util.Log.w("CouncilOrchestrator", "relShift dissent bond: ${it.message}", it) }
            }
        }
    }
}