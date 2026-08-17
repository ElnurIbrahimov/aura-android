package com.aura.projects

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A sustained concern, and the thing that can answer "where is this?".
 *
 * Everything Aura holds is either a **turn** — a conversation, gone when it
 * scrolls away — or a **fact**, atomised into `memories` with no memory of how
 * it was arrived at. There has never been a unit in between: something that
 * accumulates across months, carries a state, and can be finished.
 *
 * `project` already existed in this codebase three times, and was a tag every
 * time: a free-text string in `ConversationEntity.metadataJson`, a `category`
 * value on `MemoryEntity`, and `NodeType.PROJECT` in the knowledge graph, which
 * nothing wrote. Three names for a thing nothing owned. Asking "where is
 * ARC-AGI-2" therefore ran a BM25 query over whatever the user had happened to
 * say, and the answer was as good as the phrasing.
 *
 * Deliberately **not** a generalisation of [com.aura.creative.CreativeProjectEntity].
 * Five tables foreign-key into that one (artifacts, revisions, branches, canon
 * facts, generation jobs) and it carries a `WorldBible` that means nothing
 * outside fiction. Same separation reasoning as the `living_*` tables, and for
 * the same reason: two things that share a word and not a shape.
 */
@Entity(
    tableName = "projects",
    indices = [
        Index("status"),
        Index("lastTurnAt"),
        // Unique because `project_state` and the sticky picker both resolve by
        // name. Two projects called "RentEase" makes that resolution a coin
        // flip, and a coin flip that writes into the wrong ledger.
        Index(value = ["name"], unique = true),
    ],
)
data class ProjectEntity(
    @PrimaryKey val id: String,
    val name: String,
    val description: String = "",
    /** [STATUS_ACTIVE], [STATUS_PAUSED] or [STATUS_DONE]. */
    val status: String = STATUS_ACTIVE,
    /**
     * When a turn was last attributed here.
     *
     * The liveness signal, and the ordering key for the picker: with eight live
     * projects, the one used an hour ago belongs at the top and the one last
     * touched in June does not. Distinct from [updatedAt], which any edit moves.
     */
    val lastTurnAt: Long = 0L,
    val turnCount: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
) {
    companion object {
        /** In play. The only status the sticky picker offers by default. */
        const val STATUS_ACTIVE = "active"

        /** Set down, not abandoned. Excluded from recall scope, kept in the list. */
        const val STATUS_PAUSED = "paused"

        /** Finished. The state nothing in Aura has ever been able to reach. */
        const val STATUS_DONE = "done"

        val STATUSES = setOf(STATUS_ACTIVE, STATUS_PAUSED, STATUS_DONE)
    }
}

/**
 * One recorded thing about a project: a decision, a blocker, or a status.
 *
 * The whole point of writing these down rather than deriving them on demand is
 * that neither "decided" nor "blocked" is recorded anywhere else in Aura.
 * `memories` hold facts; a decision is not a fact, it is a fact plus the moment
 * it displaced another one. Deriving that at read time means asking a model to
 * infer it from a pile, on every ask, with nothing to point at when it is wrong.
 *
 * One table with a [kind] column rather than three tables, following
 * `CorrectionEntity.kind`, `OpenQuestionEntity.kind` and `Change.Kind`. Three
 * tables of identically-shaped rows would be three migrations and three backup
 * mappers to express a difference that is one string wide.
 *
 * **Questions are not a kind here.** They go to
 * [com.aura.curiosity.OpenQuestionEntity] with `subjectKind = "project"`, which
 * costs no schema change and inherits that table's dismissal path, its
 * `(subjectKind, subjectId)` index, curiosity's scanner and the Mind screen's
 * open-questions section. A fourth kind here would have reimplemented all four.
 */
@Entity(
    tableName = "project_notes",
    foreignKeys = [
        ForeignKey(
            entity = ProjectEntity::class,
            parentColumns = ["id"],
            childColumns = ["projectId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("projectId", "state"),
        // The supersession lookup. Not unique: superseded rows keep their
        // (projectId, kind, subject) forever, which is the audit trail.
        Index("projectId", "kind", "subject"),
        Index("createdAt"),
    ],
)
data class ProjectNoteEntity(
    @PrimaryKey val id: String,
    val projectId: String,
    /** [KIND_DECISION], [KIND_BLOCKER] or [KIND_STATUS]. */
    val kind: String,
    /**
     * The normalised key this row is *about* — "payments", "training run",
     * "eval harness".
     *
     * The field the whole design rests on. Without it the table is append-only:
     * eleven decisions about payments and no way to know which one holds. A new
     * row whose `(projectId, kind, subject)` already has an [STATE_ACTIVE] row
     * supersedes it, so the ledger converges instead of accumulating.
     *
     * This is `SceneLedger`'s single-valued-predicate mechanism — `location`,
     * `alive`, `allegiance` — applied to project state, and it is also where
     * `SceneLedger` was bitten: a blank key made every row collapse onto one
     * and silently disabled contradiction detection for every pre-existing
     * project. So a blank subject is **refused at the store**, never defaulted.
     */
    val subject: String,
    /** The statement itself, written to be read by a person. */
    val body: String,
    /**
     * The conversation this was extracted from.
     *
     * Provenance, and the reason extraction is safe to run unsupervised: a row
     * the model invented can be traced to the exchange that produced it and
     * deleted. A ledger that could not answer "why do you think that" would be
     * a worse version of the inference it replaces.
     */
    val sourceConversationId: String,
    val sourceTurnAt: Long,
    /** [STATE_ACTIVE], [STATE_SUPERSEDED] or [STATE_RESOLVED]. */
    val state: String = STATE_ACTIVE,
    /** The row that displaced this one. Set together with [STATE_SUPERSEDED]. */
    val supersededBy: String? = null,
    val resolvedAt: Long? = null,
    val createdAt: Long = System.currentTimeMillis(),
) {
    companion object {
        /** Something the user settled. Supersedes the prior decision on the same subject. */
        const val KIND_DECISION = "decision"

        /** Something stopping progress. Resolvable, unlike a decision. */
        const val KIND_BLOCKER = "blocker"

        /** Where the work stands. The answer to "where is this" when nothing else applies. */
        const val KIND_STATUS = "status"

        val KINDS = setOf(KIND_DECISION, KIND_BLOCKER, KIND_STATUS)

        /** Current. The only state `project_state` reports as fact. */
        const val STATE_ACTIVE = "active"

        /** Displaced by a later row on the same subject. Kept — it is the history. */
        const val STATE_SUPERSEDED = "superseded"

        /** A blocker that cleared. Distinct from superseded: nothing replaced it. */
        const val STATE_RESOLVED = "resolved"
    }
}
