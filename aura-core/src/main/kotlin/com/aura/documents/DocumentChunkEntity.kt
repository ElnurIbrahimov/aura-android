package com.aura.documents

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * First-class document chunk with embedding metadata. Replaces the
 * current approach of storing document chunks as undifferentiated
 * memories — chunks now have their own table with source-addressable
 * citations (documentId + ordinal + character offsets).
 *
 * Embeddings are stored as a ByteArray (384-dim float × 4 bytes).
 * The [embeddingModel] and [embeddingVersion] columns track which
 * model produced the embedding so a model swap can trigger re-indexing.
 */
@Entity(
    tableName = "document_chunks",
    indices = [
        Index(value = ["documentId"]),
        Index(value = ["documentId", "ordinal"]),
        Index(value = ["contentHash"]),
    ],
    foreignKeys = [
        androidx.room.ForeignKey(
            entity = DocumentEntity::class,
            parentColumns = ["id"],
            childColumns = ["documentId"],
            onDelete = androidx.room.ForeignKey.CASCADE,
        ),
    ],
)
data class DocumentChunkEntity(
    @PrimaryKey val id: kotlin.String,
    val documentId: kotlin.String,
    /** 0-based position within the document. */
    val ordinal: Int,
    /**
     * Character range in the **normalised** text, not the original.
     *
     * `DocumentChunker` collapses CRLF, trailing whitespace and runs of blank
     * lines before it splits, and it is that string these offsets index. The
     * normalised text is not persisted anywhere, so today this range cannot be
     * resolved back to anything a user can see — which is why
     * `SearchDocumentsTool` does not print it. It is kept because it is the
     * only record of where a chunk sat, and persisting the normalised text
     * would make it resolvable.
     *
     * This KDoc said "offset in the original text", which was a direct
     * contradiction of `DocumentChunker`'s own docs one file over. Two
     * statements, both confident, one wrong.
     */
    val charStart: Int,
    val charEnd: Int,
    /** Page number if extracted from a paginated source (PDF), else 0. */
    val pageNumber: Int = 0,
    val text: kotlin.String,
    val contentHash: kotlin.String,
    /** Embedding bytes (384 × 4 = 1536 bytes). Null until embedded. */
    val embedding: ByteArray? = null,
    /** Which embedding model produced the embedding (e.g. "nomic-embed-text"). */
    val embeddingModel: kotlin.String? = null,
    /** Version of the embedding model for cache invalidation. */
    val embeddingVersion: Int = 0,
    /** When the embedding was computed. 0 = not yet embedded. */
    val embeddedAt: kotlin.Long = 0L,
) {
    override fun equals(other: Any?): kotlin.Boolean {
        if (this === other) return true
        if (other !is DocumentChunkEntity) return false
        return id == other.id
    }
    override fun hashCode(): Int = id.hashCode()
}