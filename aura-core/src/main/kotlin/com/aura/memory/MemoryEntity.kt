package com.aura.memory

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

@Serializable
@Entity(
    tableName = "memories",
    indices = [
        Index("createdAt"),
        Index("source"),
        Index("category"),
        Index("sourceConversationId"),
        Index("scope"),
        // Hot query keys: recall sorts by decayScore DESC (search, top),
        // and the vector fallback sorts by accessCount + decayScore.
        Index("decayScore"),
        Index("accessCount"),
    ]
)
data class MemoryEntity(
    @PrimaryKey val id: String,
    val content: String,
    val source: String,         // "user", "assistant", "tool", "system"
    val category: String,       // "fact", "preference", "episode", "person", "project", "idea", "task"
    val scope: String = "general", // "general", "project:<id>", "person:<id>", "creative:<id>"
    val importance: Float = 0.5f, // 0.0 - 1.0
    @ColumnInfo(name = "embedding") val embedding: ByteArray? = null, // 384 floats × 4 bytes
    @ColumnInfo(name = "createdAt") val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "accessedAt") val accessedAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "accessCount") val accessCount: Int = 0,
    @ColumnInfo(name = "decayScore") val decayScore: Float = 1.0f, // 0.0 = forgotten, 1.0 = fresh
    val tags: String = "",       // comma-separated
    val metadata: String = "",   // JSON
    val sourceConversationId: kotlin.String = "",
    val sourceTurnTimestamp: kotlin.Long = 0L,
    /** Which embedding model produced [embedding]. Null for legacy rows. */
    val embeddingModel: kotlin.String? = null,
    /** Version of the embedding model for cache invalidation. */
    val embeddingVersion: Int = 0,
    /**
     * When this memory stopped being retrievable, or null while it is live.
     *
     * Retirement exists because the two ways a memory can stop being true are
     * not the same thing. A mistake should vanish; a fact the world moved past
     * is history, and history that is deleted cannot be asked about later. Both
     * leave the row in place — nothing here is ever destroyed — so being wrong
     * about being wrong stays recoverable.
     *
     * Read paths filter on this. Export does not: a backup that dropped retired
     * rows would quietly make a restore destructive.
     */
    @ColumnInfo(name = "retiredAt") val retiredAt: kotlin.Long? = null,
    /** The memory that replaced this one, when one did. */
    @ColumnInfo(name = "supersededBy") val supersededBy: kotlin.String? = null,
    /** Why it was retired — "consolidated", "corrected", "superseded". */
    @ColumnInfo(name = "retiredReason") val retiredReason: kotlin.String? = null,
) {
    // Room requires equals/hashCode; ByteArray needs special handling
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MemoryEntity) return false
        return id == other.id
    }
    override fun hashCode(): Int = id.hashCode()
}


@Entity(
    tableName = "memory_feedback",
    indices = [Index("memoryId"), Index("createdAt")]
)
data class MemoryFeedbackEntity(
    @PrimaryKey val id: String,
    val memoryId: String,
    val kind: String, // "upvote", "downvote", "corrected", "forgotten"
    val note: String = "",
    val createdAt: Long = System.currentTimeMillis(),
)
