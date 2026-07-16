package com.aura.proactive

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "proactive_events",
    indices = [Index(value = ["timestamp"])],
)
data class ProactiveEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val eventType: String,
    val title: String,
    val body: String,
    val timestamp: Long,
    val payload: String = "",
)


@Entity(
    tableName = "proactive_interactions",
    indices = [Index(value = ["eventId"]), Index(value = ["timestamp"])],
)
data class ProactiveInteractionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** The proactive event this interaction is about. */
    val eventId: Long,
    /** "dismissed", "tapped", "snoozed", "acted". */
    val action: String,
    /** Optional user text feedback. */
    val feedback: String = "",
    val timestamp: Long = System.currentTimeMillis(),
)
