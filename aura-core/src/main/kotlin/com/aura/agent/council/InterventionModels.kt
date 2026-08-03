package com.aura.agent.council

import kotlinx.serialization.Serializable

/**
 * A concrete action the agent council wants to take on behalf of the user.
 * Each intervention type maps to an existing Aura tool or subsystem.
 *
 * All interventions require explicit user approval before execution.
 */
@Serializable
sealed class Intervention {

    /** Create a task or calendar event. */
    @Serializable
    data class Schedule(
        val title: kotlin.String,
        val description: kotlin.String = "",
        val dueAt: Long? = null,
        val priority: Int = 1,
    ) : Intervention()

    /** Draft a message for the user to review and send. */
    @Serializable
    data class Message(
        val recipient: kotlin.String,
        val draftBody: kotlin.String,
        val rationale: kotlin.String,
    ) : Intervention()

    /** Set a reminder with a human rationale. */
    @Serializable
    data class Reminder(
        val message: kotlin.String,
        val triggerAt: Long,
        val rationale: kotlin.String,
    ) : Intervention()

    /** Suggest a self-care action (break, walk, sleep). */
    @Serializable
    data class SelfCare(
        val suggestion: kotlin.String,
        val rationale: kotlin.String,
    ) : Intervention()

    /** Surface a forgotten memory with a new connection. */
    @Serializable
    data class Memory(
        val memoryId: kotlin.String,
        val connection: kotlin.String,
        val rationale: kotlin.String,
    ) : Intervention()

    /** Type string for serialization/deserialization. */
    val typeName: kotlin.String
        get() = when (this) {
            is Schedule -> "schedule"
            is Message -> "message"
            is Reminder -> "reminder"
            is SelfCare -> "selfcare"
            is Memory -> "memory"
        }

    /** Human-readable summary for UI display. */
    val summary: kotlin.String
        get() = when (this) {
            is Schedule -> "Schedule: $title"
            is Message -> "Draft message to $recipient"
            is Reminder -> "Remind: $message"
            is SelfCare -> "Suggest: $suggestion"
            is Memory -> "Recall: $connection"
        }
}