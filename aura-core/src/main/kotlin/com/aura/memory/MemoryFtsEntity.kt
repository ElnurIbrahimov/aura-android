package com.aura.memory

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.PrimaryKey

/**
 * Full-text index over [MemoryEntity.content].
 *
 * Replaces the six-`LIKE` candidate fetch that lexical recall used to run:
 *
 * ```sql
 * WHERE scope IN (:scopes) AND (content LIKE :word1 OR ... OR content LIKE :word6)
 * ```
 *
 * That query had two problems this table fixes.
 *
 * **A hard six-word ceiling, written into a DAO signature.** `MemoryStore.query`
 * fed it the user's whole message and kept only the first six non-stopwords;
 * everything after was silently dropped from lexical matching. Vector search
 * still saw the full text, so recall degraded rather than broke — which is why
 * nothing surfaced it. FTS `MATCH` takes any number of terms.
 *
 * **No corpus statistics.** `BM25`'s IDF was computed over the LIKE-matched
 * candidates, every one of which contained a query term by construction, so
 * `ln((N - df + 0.5) / (df + 0.5))` went negative for exactly the terms that
 * should discriminate and clamped to the 0.1 floor. One of RRF's six signals
 * was contributing noise. FTS makes a real per-term document frequency a single
 * indexed count, so IDF can be computed against the corpus.
 *
 * ## Why standalone rather than `contentEntity`
 *
 * Room's `@Fts4(contentEntity = MemoryEntity::class)` needs the content entity
 * to have an INTEGER primary key it can map to `rowid`. [MemoryEntity] has a
 * `String` id, and adding a synthetic rowid to a 24-entity database to satisfy
 * Room is a far larger change than the one this buys. So this is a plain FTS4
 * table joined back to `memories` on `rowid`, kept in sync by SQL triggers
 * declared in `MIGRATION_16_17`.
 *
 * Triggers rather than Kotlin-side writes, deliberately: every insert path
 * (`insert`, `insertAll`, `insertAllWithEdits`, the backup restore, the dedup
 * merge in `MemoryStore.maybeStore`) would otherwise need to remember to
 * update the index, and `MemoryDaoContractTest`'s 26 tests all write through
 * `dao.insert`. Kotlin-side sync would have left every one of them exercising
 * an empty index while still passing.
 */
@Fts4
@Entity(tableName = "memories_fts")
data class MemoryFtsEntity(
    @PrimaryKey
    @ColumnInfo(name = "rowid")
    val rowid: Int = 0,
    /** The `memories.id` this row indexes. Carried so a match can be traced back without the join. */
    val memoryId: String = "",
    /** Mirror of [MemoryEntity.content] — the only column actually searched. */
    val content: String = "",
)
