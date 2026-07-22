package com.aura.agent

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "conversations",
    indices = [Index(value = ["updatedAt"])],
)
data class ConversationEntity(
    @PrimaryKey val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val systemPrompt: String?,
    val model: String?,
    val metadataJson: String = "{}",
    /** The full turn list serialized as JSON via kotlinx.serialization. */
    val turnsJson: String = "[]",
    /**
     * Embedding of the conversation's latest user turn (or title when empty).
     * New saves populate it; semantic search backfills legacy null rows in
     * bounded batches.
     */
    val embedding: ByteArray? = null,
    /** Durable compression of turns before [summaryThroughTurn]. */
    val contextSummary: String = "",
    val summaryThroughTurn: Int = 0,
    /** Agent associated with this conversation. Null = General/default. */
    val agentId: String? = null,
    /**
     * Soft-delete tombstone. Null = conversation is visible. Non-null = the
     * epoch-ms when it was deleted. After [SOFT_DELETE_RETENTION_MS] a
     * background job hard-deletes the row. The History screen filters out
     * non-null rows by default; restoring just sets it back to null.
     */
    val deletedAt: Long? = null,
) {
    companion object {
        /** 7 days — long enough to recover from a fat-finger, short enough
         *  that the table doesn't grow forever. */
        const val SOFT_DELETE_RETENTION_MS: Long = 7L * 24L * 60L * 60L * 1000L
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ConversationEntity) return false
        return id == other.id
    }
    override fun hashCode(): Int = id.hashCode()
}
