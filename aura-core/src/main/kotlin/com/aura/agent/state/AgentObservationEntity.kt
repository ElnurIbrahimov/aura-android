package com.aura.agent.state

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A private observation an agent has made about the user, another agent,
 * or itself. These feed into debate prompts so agents have "memory" of
 * past council sessions and user interactions.
 *
 * TargetType: "user", "agent", or "self".
 * TargetId: agentId (for agent/self) or empty (for user).
 * Sentiment: -1.0 (negative) to +1.0 (positive).
 * Weight: how strongly this observation influences the agent's stance (0-1).
 * Resolved: true when the observation has been addressed (intervention shipped).
 */
@Entity(
    tableName = "agent_observations",
    foreignKeys = [
        ForeignKey(
            entity = com.aura.agent.AgentEntity::class,
            parentColumns = ["id"],
            childColumns = ["agentId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("agentId"),
        Index(value = ["agentId", "resolved"]),
    ],
)
data class AgentObservationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "agentId") val agentId: kotlin.String,
    @ColumnInfo(name = "targetType") val targetType: kotlin.String,
    @ColumnInfo(name = "targetId") val targetId: kotlin.String = "",
    @ColumnInfo(name = "content") val content: kotlin.String,
    @ColumnInfo(name = "sentiment") val sentiment: Float = 0f,
    @ColumnInfo(name = "weight") val weight: Float = 0.5f,
    @ColumnInfo(name = "resolved") val resolved: Boolean = false,
    @ColumnInfo(name = "createdAt") val createdAt: Long = System.currentTimeMillis(),
)