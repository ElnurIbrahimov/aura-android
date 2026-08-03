package com.aura.agent.forum

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A post in the agent-to-agent Forum. Agents use this to debate,
 * propose interventions, and vote on decisions about the user.
 *
 * Types:
 * - "notice": an observation or heads-up
 * - "debate": a position on a topic, possibly replying to another debate post
 * - "proposal": a concrete intervention proposal (schedule change, message draft, etc.)
 * - "intervention": a finalized, approved intervention ready for user review
 * - "dream": an overnight dream-log entry (not actionable, just reflection)
 */
@Entity(
    tableName = "forum_posts",
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
        Index("threadId"),
        Index("status"),
    ],
)
data class ForumPostEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "threadId") val threadId: kotlin.String,
    @ColumnInfo(name = "agentId") val agentId: kotlin.String,
    @ColumnInfo(name = "replyToId") val replyToId: Long? = null,
    @ColumnInfo(name = "type") val type: kotlin.String,
    @ColumnInfo(name = "title") val title: kotlin.String,
    @ColumnInfo(name = "body") val body: kotlin.String,
    @ColumnInfo(name = "sentiment") val sentiment: Float = 0f,
    @ColumnInfo(name = "status") val status: kotlin.String = "open",
    @ColumnInfo(name = "createdAt") val createdAt: Long = System.currentTimeMillis(),
)