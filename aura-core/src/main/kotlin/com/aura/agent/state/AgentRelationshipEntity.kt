package com.aura.agent.state

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Relationship between two agents in the council.
 *
 * Affinity (-100 to +100): negative = antagonistic, positive = collaborative.
 * Agents with high affinity co-sponsor proposals; low affinity refuse to
 * collaborate and may dissent more aggressively.
 *
 * ConflictCount: number of times these agents voted against each other.
 * CollaborationCount: number of times they co-sponsored or voted together.
 */
@Entity(
    tableName = "agent_relationships",
    foreignKeys = [
        ForeignKey(
            entity = com.aura.agent.AgentEntity::class,
            parentColumns = ["id"],
            childColumns = ["agentAId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = com.aura.agent.AgentEntity::class,
            parentColumns = ["id"],
            childColumns = ["agentBId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["agentAId", "agentBId"], unique = true)],
)
data class AgentRelationshipEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "agentAId") val agentAId: kotlin.String,
    @ColumnInfo(name = "agentBId") val agentBId: kotlin.String,
    @ColumnInfo(name = "affinity") val affinity: Float = 0f,
    @ColumnInfo(name = "conflictCount") val conflictCount: Int = 0,
    @ColumnInfo(name = "collaborationCount") val collaborationCount: Int = 0,
    @ColumnInfo(name = "updatedAt") val updatedAt: Long = System.currentTimeMillis(),
)