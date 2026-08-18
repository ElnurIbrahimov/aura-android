package com.aura.documents

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.PrimaryKey

/**
 * Full-text index over [DocumentChunkEntity.text].
 *
 * Document chunks have been searchable all along — as `memories` rows, through
 * `memories_fts`, alongside everything the user ever told Aura. That works and
 * it is what recall still reads, but it makes the two kinds of text compete on
 * one set of corpus statistics: a single imported book adds on the order of a
 * thousand rows to a store that may hold a few hundred personal memories, and
 * BM25 takes its document frequencies from that index. Ordinary words stop
 * discriminating between memories because they are common *in the book*.
 *
 * A separate index is what lets documents be searched as documents, with their
 * own `N` and their own `df`, and it is the prerequisite for eventually taking
 * them out of `memories` altogether.
 *
 * ## Standalone, not `contentEntity`
 *
 * The same reason as [com.aura.memory.MemoryFtsEntity]: Room's
 * `@Fts4(contentEntity = …)` requires the content entity to have an INTEGER
 * primary key it can map to `rowid`, and [DocumentChunkEntity.id] is a String.
 * So this is a plain FTS4 table joined back to `document_chunks` on `rowid`,
 * kept in sync by the SQL triggers in [DocumentChunkFtsSchema].
 *
 * The join is not only for the extra columns: an index row whose chunk has gone
 * matches nothing through a join, so a stale entry can never surface a passage
 * that no longer exists. Belt and braces rather than a live concern — the
 * delete trigger fires on cascaded deletes as well as direct ones, which
 * `DocumentChunkRetrievalTest` asserts rather than assumes.
 */
@Fts4
@Entity(tableName = "document_chunks_fts")
data class DocumentChunkFtsEntity(
    @PrimaryKey
    @ColumnInfo(name = "rowid")
    val rowid: Int = 0,
    /** The `document_chunks.id` this row indexes. */
    val chunkId: String = "",
    /** Mirror of [DocumentChunkEntity.text] — the only column actually searched. */
    val content: String = "",
)
