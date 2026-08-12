package com.aura.curiosity

import android.util.Log
import com.aura.dream.ContradictionDao
import com.aura.kg.KnowledgeGraphDao
import com.aura.memory.MemoryDao
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Finds the things Aura does not know, from rows rather than from a mood.
 *
 * No model call. Every subject here is a specific row that can be looked at
 * again later to ask whether the gap is still there — which is what makes a
 * question something that can be finished rather than a conversational tic.
 *
 * Three sources, in descending order of how confidently a question can be
 * written from them:
 *
 *  - **Gaps** — graph nodes with fewer than two edges. Aura has recorded that
 *    something exists and knows nothing else about it. This is the signal the
 *    CURIOSITY drive has always been computed from, used here for the first
 *    time as rows rather than as a count.
 *  - **Contradictions** — two things it believes that cannot both be true. The
 *    COHERENCE drive counts these; nothing has ever resolved one by asking.
 *  - **Stale assumptions** — important facts stored long ago and never
 *    confirmed. Decay handles these worst, because importance keeps them fresh
 *    exactly when they are least likely to still be true.
 */
@Singleton
class QuestionScanner @Inject constructor(
    private val knowledgeGraphDao: KnowledgeGraphDao,
    private val contradictionDao: ContradictionDao,
    private val memoryDao: MemoryDao,
    private val openQuestionDao: OpenQuestionDao,
) {

    /**
     * A thing worth asking about, before it has been turned into a sentence.
     *
     * [context] is what the author is given to write from. It is deliberately
     * the raw stored text: the author's job is phrasing, not inference, and
     * handing it a summary of a summary is how a question ends up being about
     * something the user never said.
     */
    data class Subject(
        val kind: String,
        val subjectKind: String,
        val subjectId: String,
        val context: String,
        val priority: Float,
    ) {
        val key: String get() = "$subjectKind/$subjectId"
    }

    /**
     * Subjects worth asking about, best first, excluding anything already
     * asked, answered or dismissed.
     *
     * Returns candidates, not questions. Nothing here decides that a question
     * will be asked — [CuriosityStore] applies the one-at-a-time rule.
     */
    suspend fun scan(limit: Int = MAX_SUBJECTS, now: Long = System.currentTimeMillis()): List<Subject> {
        // One query, not one per subject. A dismissal is permanent, so this set
        // only grows, and re-proposing something the user has already refused is
        // the fastest way to make the whole feature feel like nagging.
        val claimed = runCatching { openQuestionDao.claimedSubjects().toSet() }
            .onFailure { Log.w(TAG, "claimed-subject lookup failed; scanning anyway", it) }
            .getOrDefault(emptySet())

        val subjects = gapSubjects() + contradictionSubjects() + staleSubjects(now)
        return subjects
            .filter { it.key !in claimed }
            .distinctBy { it.key }
            .sortedByDescending { it.priority }
            .take(limit)
    }

    private suspend fun gapSubjects(): List<Subject> =
        runCatching { knowledgeGraphDao.gapNodes(POOL) }
            .onFailure { Log.w(TAG, "gap scan failed", it) }
            .getOrDefault(emptyList())
            .map { node ->
                Subject(
                    kind = OpenQuestionEntity.KIND_GAP,
                    subjectKind = OpenQuestionEntity.SUBJECT_KG_NODE,
                    subjectId = node.id,
                    context = "A ${node.type} called \"${node.label}\" came up, and nothing else is recorded about it.",
                    // A node Aura is confident about but knows nothing around is
                    // a better question than one it half-recognised.
                    priority = GAP_BASE * node.confidence,
                )
            }

    private suspend fun contradictionSubjects(): List<Subject> =
        runCatching { contradictionDao.byStatus("UNRESOLVED") }
            .onFailure { Log.w(TAG, "contradiction scan failed", it) }
            .getOrDefault(emptyList())
            .take(POOL)
            .map { row ->
                Subject(
                    kind = OpenQuestionEntity.KIND_CONTRADICTION,
                    subjectKind = OpenQuestionEntity.SUBJECT_CONTRADICTION,
                    subjectId = row.id,
                    context = "Two things recorded at different times disagree. " +
                        "Earlier: \"${row.olderText.take(CONTEXT_CHARS)}\". " +
                        "Later: \"${row.newerText.take(CONTEXT_CHARS)}\".",
                    // The highest-value question of the three: one of these is
                    // wrong and Aura is currently using both.
                    priority = CONTRADICTION_BASE * row.confidence,
                )
            }

    private suspend fun staleSubjects(now: Long): List<Subject> =
        runCatching {
            memoryDao.staleAssumptions(
                minImportance = STALE_MIN_IMPORTANCE,
                olderThan = now - STALE_AGE_MS,
                limit = POOL,
            )
        }
            .onFailure { Log.w(TAG, "stale-assumption scan failed", it) }
            .getOrDefault(emptyList())
            .map { memory ->
                val months = ((now - memory.createdAt) / MONTH_MS).coerceAtLeast(1)
                Subject(
                    kind = OpenQuestionEntity.KIND_STALE,
                    subjectKind = OpenQuestionEntity.SUBJECT_MEMORY,
                    subjectId = memory.id,
                    context = "Recorded about $months months ago and never confirmed since: " +
                        "\"${memory.content.take(CONTEXT_CHARS)}\".",
                    priority = STALE_BASE * memory.importance,
                )
            }

    private companion object {
        const val TAG = "QuestionScanner"

        /** Rows pulled per source before ranking. */
        const val POOL = 20

        /** Subjects handed to the author in one cycle. */
        const val MAX_SUBJECTS = 8

        const val CONTEXT_CHARS = 160
        const val MONTH_MS = 30L * 24 * 60 * 60 * 1000

        /**
         * Base weights, in the order a question is worth asking. A
         * contradiction means Aura is currently using something false; a gap
         * means it is missing something; a stale fact means it might be wrong.
         */
        const val CONTRADICTION_BASE = 1.0f
        const val GAP_BASE = 0.7f
        const val STALE_BASE = 0.5f

        /** Only facts that mattered are worth re-confirming. */
        const val STALE_MIN_IMPORTANCE = 0.6f

        /** Six months. Long enough that "is this still true" is a real question. */
        const val STALE_AGE_MS = 180L * 24 * 60 * 60 * 1000
    }
}
