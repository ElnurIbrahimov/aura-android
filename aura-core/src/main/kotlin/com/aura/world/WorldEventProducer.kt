package com.aura.world

import android.util.Log
import com.aura.agent.Tool
import com.aura.agent.ToolResult
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Produces [WorldEventEntity] rows from significant app events.
 *
 * Before this existed the `world_events` table had a full Room schema, DAO,
 * backup type, and the [QueryWorldModelTool] read from it — but nothing in
 * the app ever wrote to it. The "Recent Events" section of the world model
 * screen always showed nothing.
 *
 * Events are produced from three sources:
 * 1. **Tool execution** — when the agent uses a tool that changes state
 *    (sends a message, creates a reminder, writes a calendar event), a
 *    world event is recorded. READ_ONLY tools do not produce events.
 * 2. **Dream consolidation** — each completed dream cycle produces a
 *    "memory_consolidated" event so the user can see when their memory
 *    was last compressed.
 * 3. **Conversation milestones** — first conversation, conversation forks,
 *    evolution proposals approved.
 *
 * Events are append-only. The `consumed` flag is set by the opportunity
 * engine when it has processed them. Producers never mark consumed.
 *
 * Thread safety: [WorldEventDao] uses Room's internal locking. The
 * [MutableSharedFlow] event bus is buffered (32) so a burst of tool calls
 * doesn't drop events. Subscribers (the opportunity engine) drain at their
 * own pace.
 */
@Singleton
class WorldEventProducer @Inject constructor(
    private val worldEventDao: WorldEventDao,
) {
    private val _events = MutableSharedFlow<WorldEventEntity>(
        extraBufferCapacity = 32,
    )
    val events = _events.asSharedFlow()

    /**
     * Record a world event. Idempotent per `id` — Room REPLACE strategy.
     * Returns the event id so callers can reference it.
     */
    suspend fun record(
        eventType: kotlin.String,
        source: kotlin.String,
        summary: kotlin.String,
        payloadJson: kotlin.String = "{}",
        agentScope: kotlin.String = "general",
    ): kotlin.String {
        val id = "evt_${UUID.randomUUID()}"
        val entity = WorldEventEntity(
            id = id,
            eventType = eventType,
            source = source,
            summary = summary,
            payloadJson = payloadJson,
            timestamp = System.currentTimeMillis(),
            consumed = false,
            agentScope = agentScope,
        )
        return runCatching {
            worldEventDao.insert(entity)
            _events.tryEmit(entity)
            id
        }.onFailure { Log.w("WorldEventProducer", "record failed: ${it.message}", it) }
            .onFailure { Log.w("WorldEvent", "op failed: ${it.message}", it) }.getOrDefault(id)
    }

    /**
     * Record a tool-execution event, for tools that changed something.
     *
     * READ_ONLY, REMOTE_COST and PRIVACY tools produce no event: they see rather than
     * change, and a privacy tool's output must not be persisted at all.
     */
    suspend fun recordToolExecution(
        toolName: kotlin.String,
        toolRisk: com.aura.agent.ToolRisk,
        resultSummary: kotlin.String,
        agentScope: kotlin.String = "general",
    ): kotlin.String? {
        // Only state-mutating tools produce world events. PRIVACY is deliberately not one:
        // it reads something sensitive and changes nothing, and it used to pass this gate
        // because the check compared enum ordinals and PRIVACY happens to sit above
        // WRITE_LOCAL. See ToolRisk.mutatesState.
        if (!toolRisk.mutatesState) return null
        val eventType = when (toolRisk) {
            com.aura.agent.ToolRisk.WRITE_LOCAL -> "local_action"
            com.aura.agent.ToolRisk.WRITE_REMOTE -> "remote_action"
            com.aura.agent.ToolRisk.DESTRUCTIVE -> "destructive_action"
            else -> "action"
        }
        return record(
            eventType = eventType,
            source = "tool:$toolName",
            summary = "$toolName: ${resultSummary.take(200)}",
            payloadJson = """{"tool":"$toolName","risk":"$toolRisk"}""",
            agentScope = agentScope,
        )
    }

    /**
     * Record a dream cycle completion. Called from DreamConsolidator
     * after all phases finish.
     */
    suspend fun recordDreamCycle(
        cycleId: kotlin.String,
        summariesWritten: Int,
        memoriesArchived: Int,
    ): kotlin.String {
        return record(
            eventType = "memory_consolidated",
            source = "dream",
            summary = "Dream cycle: $summariesWritten clusters summarized, $memoriesArchived memories archived",
            payloadJson = """{"cycleId":"$cycleId","summariesWritten":$summariesWritten,"memoriesArchived":$memoriesArchived}""",
        )
    }

    /**
     * Record an evolution action approval.
     */
    suspend fun recordEvolutionApproval(
        action: kotlin.String,
        proposalId: kotlin.String,
    ): kotlin.String {
        return record(
            eventType = "evolution_approved",
            source = "evolution",
            summary = "Evolution action approved: $action",
            payloadJson = """{"action":"$action","proposalId":"$proposalId"}""",
        )
    }

    /**
     * Mark all events as consumed. Called by the opportunity engine
     * after it has processed pending events into opportunities.
     */
    suspend fun markAllConsumed() {
        runCatching {
            val pending = worldEventDao.unconsumed(500)
            for (event in pending) {
                worldEventDao.markConsumed(event.id)
            }
        }.onFailure { Log.w("WorldEventProducer", "markAllConsumed failed: ${it.message}", it) }
    }
}