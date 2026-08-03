package com.aura.agent.state

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Persistent emotional/behavioural state for a council agent.
 *
 * Mood (0-100): how the agent feels — decays with overuse, recovers during idle.
 * Energy (0-100): capacity to participate in debates. Low energy → abstains.
 * CurrentGoal: what the agent is currently working toward for the user.
 * StanceOnUser: -100 (critical) to +100 (supportive). Shifts based on observations.
 * ParticipationCount: how many council sessions this agent has joined.
 * LastActiveAt: timestamp of last debate/intervention. Used for mood decay.
 */
@Entity(
    tableName = "agent_state",
    foreignKeys = [
        ForeignKey(
            entity = com.aura.agent.AgentEntity::class,
            parentColumns = ["id"],
            childColumns = ["agentId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("agentId", unique = true)],
)
data class AgentStateEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "agentId") val agentId: kotlin.String,
    @ColumnInfo(name = "mood") val mood: Float = 65f,
    @ColumnInfo(name = "energy") val energy: Float = 80f,
    @ColumnInfo(name = "currentGoal") val currentGoal: kotlin.String = "",
    @ColumnInfo(name = "stanceOnUser") val stanceOnUser: Float = 0f,
    @ColumnInfo(name = "participationCount") val participationCount: Int = 0,
    @ColumnInfo(name = "lastActiveAt") val lastActiveAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "createdAt") val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "updatedAt") val updatedAt: Long = System.currentTimeMillis(),
)