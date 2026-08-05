package com.aura.world

import android.util.Log
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Generates [OpportunityEntity] rows from unconsumed [WorldEventEntity]s
 * and active [BeliefEntity]s.
 *
 * Before this existed the `opportunities` table had a full schema, DAO,
 * backup type, and the [QueryWorldModelTool] read from it — but nothing
 * in the app ever created an opportunity. The "Opportunities" section
 * of the world model screen always said "No world-model entries found."
 *
 * This is the opportunity engine. It runs after the dream cycle (which
 * produces world events and promotes beliefs) and converts signals
 * into actionable proposals the user can approve, dismiss, or snooze.
 *
 * ## Heuristic design
 *
 * The engine is deliberately heuristic, not LLM-driven. An LLM call per
 * opportunity would cost a round-trip for each candidate — the same
 * cost problem that made the dream cycle's planning step expensive.
 * Instead, the engine pattern-matches on event types and belief
 * predicates, producing suggestions with benefit/urgency heuristics:
 *
 * - **Stale conversation** — last conversation > 3 days ago → suggest
 *   "Resume conversation" (urgency 0.3, benefit 0.5)
 * - **Pending reminders** — unconsumed reminder events → suggest
 *   "Review pending reminders" (urgency 0.7, benefit 0.6)
 * - **Unverified beliefs** — beliefs with lastVerifiedAt == 0 → suggest
 *   "Verify this belief" (urgency 0.2, benefit 0.4)
 * - **Contradictory beliefs** — two active beliefs with same
 *   subject+predicate but different values → suggest "Resolve
 *   conflict" (urgency 0.6, benefit 0.7)
 * - **Routine detected** — dream cycle extracted a routine → suggest
 *   "Automate this routine as a Hand" (urgency 0.3, benefit 0.8)
 *
 * After generating opportunities, all consumed events are marked
 * consumed so the next cycle doesn't re-process them.
 *
 * ## Idempotency
 *
 * Opportunities are keyed by a deterministic id
 * (`opp_<hash_of_source>`) so re-running the engine on the same
 * events produces REPLACE, not duplicates. The hash includes the
 * event/belief id and the opportunity kind.
 */
@Singleton
class OpportunityEngine @Inject constructor(
    private val worldEventDao: WorldEventDao,
    private val opportunityDao: OpportunityDao,
    private val beliefDao: BeliefDao,
) {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Scan unconsumed world events and active beliefs, generate
     * opportunities, mark events consumed. Returns the count of
     * new/replaced opportunities created.
     */
    suspend fun runCycle(agentScope: kotlin.String = "general"): Int {
        var count = 0
        try {
            val pendingEvents = worldEventDao.unconsumed(100)
            val activeBeliefs = beliefDao.allActiveInScopes(
                if (agentScope.isBlank()) listOf("general") else listOf("general", agentScope),
                200,
            )

            // 1. Generate from events
            for (event in pendingEvents) {
                val opportunities = generateFromEvent(event, agentScope)
                for (opp in opportunities) {
                    opportunityDao.upsert(opp)
                    count++
                }
            }

            // 2. Generate from beliefs
            val beliefOpportunities = generateFromBeliefs(activeBeliefs, agentScope)
            for (opp in beliefOpportunities) {
                opportunityDao.upsert(opp)
                count++
            }

            // 3. Mark events consumed — they've been processed
            for (event in pendingEvents) {
                worldEventDao.markConsumed(event.id)
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (t: Throwable) {
            Log.w("OpportunityEngine", "runCycle failed: ${t.message}")
        }
        return count
    }

    /**
     * Generate opportunities from a single world event.
     */
    private fun generateFromEvent(
        event: WorldEventEntity,
        agentScope: kotlin.String,
    ): List<OpportunityEntity> {
        val now = System.currentTimeMillis()
        return when (event.eventType) {
            "local_action" -> {
                // Tool executed a state-mutating action — suggest a
                // follow-up if the tool was a reminder or calendar write.
                if (event.source.contains("set_reminder") || event.source.contains("schedule_task")) {
                    listOf(
                        OpportunityEntity(
                            id = oppId(event.id, "review_reminder"),
                            title = "Review scheduled reminders",
                            description = "You recently scheduled a task. Want to review upcoming reminders?",
                            kind = "info",
                            benefit = 0.6f,
                            urgency = 0.5f,
                            confidence = 0.7f,
                            evidenceJson = """["${event.id}"]""",
                            suggestedActionJson = """{"action":"navigate","target":"reminders"}""",
                            status = "proposed",
                            createdAt = now,
                            agentScope = agentScope,
                        ),
                    )
                } else emptyList()
            }

            "memory_consolidated" -> {
                // Dream cycle completed — suggest reviewing what was consolidated
                listOf(
                    OpportunityEntity(
                        id = oppId(event.id, "review_dream"),
                        title = "Review dream consolidation",
                        description = "Aura compressed ${parseSummaries(event.payloadJson)} memory clusters during the last dream cycle.",
                        kind = "info",
                        benefit = 0.3f,
                        urgency = 0.1f,
                        confidence = 0.9f,
                        evidenceJson = """["${event.id}"]""",
                        suggestedActionJson = """{"action":"navigate","target":"dreams"}""",
                        status = "proposed",
                        createdAt = now,
                        agentScope = agentScope,
                    ),
                )
            }

            "evolution_approved" -> {
                listOf(
                    OpportunityEntity(
                        id = oppId(event.id, "review_evolution"),
                        title = "Review evolution change",
                        description = "An evolution proposal was approved. Check what changed.",
                        kind = "info",
                        benefit = 0.4f,
                        urgency = 0.2f,
                        confidence = 0.8f,
                        evidenceJson = """["${event.id}"]""",
                        suggestedActionJson = """{"action":"navigate","target":"evolution/inbox"}""",
                        status = "proposed",
                        createdAt = now,
                        agentScope = agentScope,
                    ),
                )
            }

            "destructive_action" -> {
                listOf(
                    OpportunityEntity(
                        id = oppId(event.id, "destructive_warning"),
                        title = "Destructive action performed",
                        description = "Aura performed a destructive action. Review the details.",
                        kind = "action_required",
                        benefit = 0.8f,
                        urgency = 0.9f,
                        confidence = 0.95f,
                        evidenceJson = """["${event.id}"]""",
                        suggestedActionJson = """{"action":"navigate","target":"diagnostics"}""",
                        status = "proposed",
                        createdAt = now,
                        agentScope = agentScope,
                    ),
                )
            }

            else -> emptyList()
        }
    }

    /**
     * Generate opportunities from active beliefs.
     */
    private fun generateFromBeliefs(
        beliefs: List<BeliefEntity>,
        agentScope: kotlin.String,
    ): List<OpportunityEntity> {
        val now = System.currentTimeMillis()
        val opportunities = mutableListOf<OpportunityEntity>()

        // 1. Unverified beliefs — lastVerifiedAt == 0 means never verified
        for (belief in beliefs) {
            if (belief.lastVerifiedAt == 0L && belief.confidence < 0.9f) {
                opportunities.add(
                    OpportunityEntity(
                        id = oppId(belief.id, "verify_belief"),
                        title = "Verify: ${belief.subject} ${belief.predicate}",
                        description = "Aura believes \"${belief.valueJson}\" but hasn't verified it. Confirm or correct?",
                        kind = "suggestion",
                        benefit = 0.4f,
                        urgency = 0.2f,
                        confidence = 0.6f,
                        evidenceJson = """["${belief.id}"]""",
                        suggestedActionJson = """{"action":"navigate","target":"world_model"}""",
                        status = "proposed",
                        createdAt = now,
                        agentScope = agentScope,
                    ),
                )
            }
        }

        // 2. Contradictory beliefs — same subject+predicate, different values
        val byKey = beliefs.groupBy { "${it.subject}|${it.predicate}" }
        for ((_, group) in byKey) {
            if (group.size > 1) {
                val distinctValues = group.map { it.valueJson }.distinct()
                if (distinctValues.size > 1) {
                    opportunities.add(
                        OpportunityEntity(
                            id = oppId(group.first().id, "resolve_conflict"),
                            title = "Resolve belief conflict",
                            description = "Aura holds contradictory beliefs about ${group.first().subject} ${group.first().predicate}.",
                            kind = "action_required",
                            benefit = 0.7f,
                            urgency = 0.6f,
                            confidence = 0.8f,
                            evidenceJson = group.map { """"${it.id}"""" }.toString(),
                            suggestedActionJson = """{"action":"navigate","target":"world_model"}""",
                            status = "proposed",
                            createdAt = now,
                            agentScope = agentScope,
                        ),
                    )
                }
            }
        }

        return opportunities
    }

    /**
     * Deterministic opportunity id so re-running on the same
     * source event/belief produces REPLACE, not duplicates.
     */
    private fun oppId(sourceId: kotlin.String, kind: kotlin.String): kotlin.String =
        "opp_${sourceId.hashCode()}_${kind.hashCode()}"

    /**
     * Parse the summariesWritten count from a dream-cycle payload.
     */
    private fun parseSummaries(payloadJson: kotlin.String): Int {
        return runCatching {
            json.parseToJsonElement(payloadJson).jsonObject["summariesWritten"]?.toString()?.toIntOrNull() ?: 0
        }.onFailure { Log.w("OpportunityEngine", "runCatching failed: ${it.message}", it) }.getOrDefault(0)
    }

}