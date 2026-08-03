package com.aura.agent.forum

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A vote cast by an agent on a Forum post (typically a proposal).
 *
 * Vote: "for", "against", or "abstain".
 * Reason: brief explanation the agent provides for its vote.
 */
@Entity(
    tableName = "forum_votes",
    foreignKeys = [
        ForeignKey(
            entity = com.aura.agent.AgentEntity::class,
            parentColumns = ["id"],
            childColumns = ["agentId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = ForumPostEntity::class,
            parentColumns = ["id"],
            childColumns = ["postId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["postId", "agentId"], unique = true),
    ],
)
data class ForumVoteEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "postId") val postId: Long,
    @ColumnInfo(name = "agentId") val agentId: kotlin.String,
    @ColumnInfo(name = "vote") val vote: kotlin.String,
    @ColumnInfo(name = "reason") val reason: kotlin.String = "",
    @ColumnInfo(name = "createdAt") val createdAt: Long = System.currentTimeMillis(),
)