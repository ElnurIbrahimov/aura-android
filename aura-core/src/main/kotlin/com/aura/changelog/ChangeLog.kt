package com.aura.changelog

import android.util.Log
import com.aura.dream.ContradictionDao
import com.aura.dream.DreamConsolidationDao
import com.aura.memory.CorrectionDao
import com.aura.memory.CorrectionEntity
import com.aura.world.BeliefDao
import com.aura.world.WorldEventDao
import kotlinx.coroutines.CancellationException
import javax.inject.Inject
import javax.inject.Singleton

/** One thing that changed, from whichever store recorded it. */
data class Change(
    val at: Long,
    val kind: Kind,
    /** One line, already in the words a person would use. */
    val headline: String,
    val detail: String = "",
) {
    enum class Kind {
        /** The user said Aura was wrong. */
        CORRECTION,

        /** Aura worked something out overnight. */
        CONSOLIDATION,

        /** A belief formed or its confidence moved. */
        BELIEF,

        /** Two things Aura believes stopped agreeing. */
        CONTRADICTION,

        /** Something happened in the user's world that Aura noticed. */
        WORLD_EVENT,
    }
}

/**
 * What changed, rather than what is true.
 *
 * `MindScreen` answers "what does Aura think" well and answers it entirely in the
 * present tense. Nothing anywhere answered *what changed this week* — what it
 * learned, what it stopped believing, which correction took effect, what it got
 * wrong. For an app whose whole premise is that it accumulates, not being able to
 * show the accumulation is a strange gap, and every input needed already exists
 * and is already indexed on time.
 *
 * **A read, not a store.** No new tables, no model call, no writes. Everything
 * here is a query over state that is already being kept, which is why it can be
 * added without a migration, a backup mapper or a schema export.
 *
 * Every source is wrapped individually and returns empty on failure, following
 * `SituationReader`: one dead store must not starve the rest, and a change log
 * that fails whole is less useful than one that is missing a row.
 */
@Singleton
class ChangeLog @Inject constructor(
    // Nullable but not defaulted. Defaults on every parameter would make Kotlin
    // synthesise a no-arg overload, which Hilt reads as a second @Inject
    // constructor and refuses. Tests pass nulls explicitly, which is also more
    // honest about what they are leaving out.
    private val correctionDao: CorrectionDao?,
    private val dreamDao: DreamConsolidationDao?,
    private val beliefDao: BeliefDao?,
    private val contradictionDao: ContradictionDao?,
    private val worldEventDao: WorldEventDao?,
) {

    /**
     * Everything that changed since [since], newest first, at most [limit] rows.
     *
     * Each source is fetched at [limit] and filtered rather than queried with a
     * cutoff where a bounded ordered query already exists. Merging N-from-each
     * and taking N overall is exactly correct — the true top N cannot contain
     * more than N from any one source — and it avoids adding DAO surface that
     * would need its own migration story.
     */
    suspend fun since(since: Long, limit: Int = DEFAULT_LIMIT): List<Change> {
        val collected = buildList {
            addAll(corrections(limit))
            addAll(consolidations(limit))
            addAll(beliefs(limit))
            addAll(contradictions(since, limit))
            addAll(worldEvents(since, limit))
        }
        return collected
            .filter { it.at >= since }
            .sortedByDescending { it.at }
            .take(limit)
    }

    private suspend fun corrections(limit: Int): List<Change> = orEmpty("corrections") {
        correctionDao?.recent(limit).orEmpty().map { row ->
            Change(
                at = row.createdAt,
                kind = Change.Kind.CORRECTION,
                headline = when (row.kind) {
                    CorrectionEntity.NEVER_TRUE -> "You said something was never true"
                    CorrectionEntity.NO_LONGER_TRUE -> "You said something had changed"
                    else -> "You corrected something"
                },
                detail = row.note,
            )
        }
    }

    private suspend fun consolidations(limit: Int): List<Change> = orEmpty("consolidations") {
        dreamDao?.recent(limit).orEmpty().map { row ->
            Change(
                at = row.createdAt,
                kind = Change.Kind.CONSOLIDATION,
                headline = "Worked out overnight, from ${row.sourceCount} memories",
                detail = row.compressedText,
            )
        }
    }

    private suspend fun beliefs(limit: Int): List<Change> = orEmpty("beliefs") {
        // Active beliefs only, because that is the bounded ordered query that
        // exists. A belief that was *superseded* in the window is arguably the
        // most interesting change of all and is not visible here — recorded as a
        // known gap rather than papered over with an unbounded scan.
        beliefDao?.allActive(limit).orEmpty().map { row ->
            Change(
                at = row.updatedAt,
                kind = Change.Kind.BELIEF,
                headline = "${row.subject} ${row.predicate}",
                detail = "confidence ${(row.confidence * 100).toInt()}%",
            )
        }
    }

    private suspend fun contradictions(since: Long, limit: Int): List<Change> = orEmpty("contradictions") {
        contradictionDao?.since(since, limit).orEmpty().map { row ->
            Change(
                at = row.createdAt,
                kind = Change.Kind.CONTRADICTION,
                headline = if (row.status == "RESOLVED") "Settled a contradiction" else "Noticed a contradiction",
                detail = row.newerText,
            )
        }
    }

    private suspend fun worldEvents(since: Long, limit: Int): List<Change> = orEmpty("world events") {
        worldEventDao?.since(since, limit).orEmpty().map { row ->
            Change(
                at = row.timestamp,
                kind = Change.Kind.WORLD_EVENT,
                headline = row.summary,
                detail = row.eventType,
            )
        }
    }

    private suspend inline fun orEmpty(what: String, block: suspend () -> List<Change>): List<Change> = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (t: Throwable) {
        Log.w(TAG, "change log source failed: $what", t)
        emptyList()
    }

    companion object {
        private const val TAG = "ChangeLog"

        const val DEFAULT_LIMIT = 40

        /** The window the UI asks for. A week is short enough to still be news. */
        const val WEEK_MS = 7L * 24 * 60 * 60 * 1000
    }
}
