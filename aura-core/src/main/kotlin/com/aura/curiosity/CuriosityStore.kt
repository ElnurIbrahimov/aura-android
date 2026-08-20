package com.aura.curiosity

import android.util.Log
import com.aura.consciousness.IntrinsicMotivation
import com.aura.consciousness.NarrativeSelf
import com.aura.memory.MemoryStore
import com.aura.provenance.ConversationProvenance
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The rules about asking.
 *
 * Two of them do all the work, and both are enforced here rather than left to
 * whoever calls next:
 *
 *  - **One open question at a time.** Aura does not think of a second question
 *    until the first is answered or refused. An assistant that can queue
 *    questions will eventually ask all of them, and twenty questions is the
 *    failure mode that would make this feature worth deleting.
 *  - **A refusal is permanent, per subject.** "Never ask about this" means the
 *    subject is spoken for forever, which is why the dismissal row is kept
 *    rather than deleted and why the scanner reads `claimedSubjects()`.
 *
 * Everything else — what gets asked, when, through which surface — is decided
 * elsewhere. This is the part that has to be boring.
 */
@Singleton
class CuriosityStore @Inject constructor(
    private val dao: OpenQuestionDao,
    private val scanner: QuestionScanner,
    private val author: QuestionAuthor,
    private val memoryStore: MemoryStore,
    private val narrativeSelf: NarrativeSelf? = null,
    private val intrinsicMotivation: IntrinsicMotivation? = null,
    /** Writes belief-verification questions into the same single slot. */
    private val verificationAuthor: com.aura.calibration.BeliefVerificationAuthor? = null,
) {

    /**
     * Look for something worth asking, and write it down.
     *
     * @return how many questions were recorded — at most one, and zero
     *   whenever one is already open.
     */
    suspend fun scanAndAuthor(now: Long = System.currentTimeMillis()): Int {
        if (dao.openCount() > 0) return 0

        // Verification first, and bounded by its own cooldown so it cannot
        // monopolise the slot.
        //
        // Ordered ahead of curiosity because a belief that is wrong actively
        // corrupts every future turn that recalls it, while a gap only limits
        // one. The cooldown is what stops that priority becoming starvation:
        // with beliefs always available to check, an unbounded verification pass
        // would take the single slot every time and curiosity would never ask
        // anything again.
        val verification = verificationAuthor?.let { author ->
            runCatching {
                author.nextQuestion(
                    claimedSubjects = dao.claimedSubjects().toSet(),
                    lastAskedAt = dao.lastCreatedAtForKind(OpenQuestionEntity.KIND_VERIFICATION) ?: 0L,
                    now = now,
                )
            }.onFailure { android.util.Log.w("CuriosityStore", "verification author failed: ${it.message}", it) }
                .getOrNull()
        }
        if (verification != null) {
            dao.insert(verification)
            refreshNarrative()
            return 1
        }

        val subjects = scanner.scan(now = now)
        if (subjects.isEmpty()) return 0

        // The author is given several subjects because one model call for eight
        // costs the same as one for one, and the top-ranked subject is
        // frequently the one it cannot phrase. Only the best result is kept.
        val authored = author.author(subjects).firstOrNull() ?: return 0
        dao.insert(
            OpenQuestionEntity(
                id = UUID.randomUUID().toString(),
                kind = authored.subject.kind,
                subjectKind = authored.subject.subjectKind,
                subjectId = authored.subject.subjectId,
                question = authored.question,
                answerable = authored.answerable,
                // What the scan-time decision was, kept so it can be argued with. The
                // arithmetic is recomputable; the model's sentence is not.
                voiScore = ValueOfInformation.percent(
                    ValueOfInformation.score(authored.subject.priority, authored.subject.signals, now),
                ),
                voiReason = authored.reason,
                createdAt = now,
            ),
        )
        refreshNarrative()
        return 1
    }

    /** The question waiting to be asked, if any. */
    suspend fun current(): OpenQuestionEntity? = dao.current()

    /** Record that the question was actually put in front of the user. */
    suspend fun markAsked(id: String, now: Long = System.currentTimeMillis()) = dao.markAsked(id, now)

    /**
     * The user answered. The answer becomes a memory, and the question closes.
     *
     * Stored as an ordinary memory rather than in some answers table: a fact is
     * a fact however it was elicited, and the point of asking was to know it,
     * not to have a record of having asked. The link back to the question is
     * what lets the outcome be scored later — did anything ever recall this.
     */
    suspend fun answer(
        id: String,
        answerText: String,
        provenance: ConversationProvenance = ConversationProvenance(),
        now: Long = System.currentTimeMillis(),
    ): String? {
        val question = dao.byId(id)?.takeIf { it.status == OpenQuestionEntity.STATUS_OPEN } ?: return null
        if (answerText.isBlank()) return null
        val memoryId = runCatching {
            memoryStore.store(
                // The question travels with the answer. "Istanbul" is not a
                // memory; "Where do you live now? Istanbul" is.
                content = "${question.question} ${answerText.trim()}",
                source = SOURCE_ANSWERED,
                category = categoryFor(question),
                importance = ANSWER_IMPORTANCE,
                provenance = provenance,
            )
        }.onFailure { Log.w(TAG, "storing the answer failed", it) }
            .getOrNull()
            ?: return null

        dao.close(id, OpenQuestionEntity.STATUS_ANSWERED, memoryId, now)
        satisfyCuriosity()
        refreshNarrative()
        return memoryId
    }

    /** The user does not want to be asked about this subject again. */
    suspend fun dismiss(id: String, now: Long = System.currentTimeMillis()) {
        dao.close(id, OpenQuestionEntity.STATUS_DISMISSED, null, now)
        // Deliberately not satisfying the drive. Being told to stop asking is
        // not the same as having learned something, and recording it as such
        // would make refusals look like successes in the outcome ledger.
        refreshNarrative()
    }

    /** Aura found the answer itself; [memoryId] is what it wrote down. */
    suspend fun markResearched(id: String, memoryId: String, now: Long = System.currentTimeMillis()) {
        dao.close(id, OpenQuestionEntity.STATUS_RESEARCHED, memoryId, now)
        satisfyCuriosity()
        refreshNarrative()
    }

    /**
     * Push the live questions into the self-model.
     *
     * `NarrativeSelf.unresolvedQuestions` was built for exactly this and has
     * never held anything: its only writer,
     * `DreamConsolidator.updateNarrativeSelf`, seeds it from its own previous
     * value, so an empty list stayed empty forever. This is the writer that was
     * missing.
     */
    private suspend fun refreshNarrative() {
        val self = narrativeSelf ?: return
        runCatching {
            self.updateOpenQuestions(
                dao.byStatus(OpenQuestionEntity.STATUS_OPEN, NARRATIVE_QUESTIONS).map { it.question },
            )
            self.save()
        }.onFailure { Log.w(TAG, "narrative refresh failed (non-fatal)", it) }
    }

    private suspend fun satisfyCuriosity() {
        val drive = intrinsicMotivation ?: return
        runCatching {
            drive.satisfy(IntrinsicMotivation.DriveType.CURIOSITY)
            drive.save()
        }.onFailure { Log.w(TAG, "satisfying curiosity failed (non-fatal)", it) }
    }

    private fun categoryFor(question: OpenQuestionEntity): String = when (question.kind) {
        // A resolved contradiction and a re-confirmed assumption are both
        // statements about what is currently true.
        OpenQuestionEntity.KIND_CONTRADICTION, OpenQuestionEntity.KIND_STALE -> "fact"
        else -> "fact"
    }

    private companion object {
        const val TAG = "CuriosityStore"
        const val SOURCE_ANSWERED = "user:answered"

        /**
         * Answers are worth more than an overheard remark and less than
         * something volunteered: the user was prompted, so the fact is true but
         * the emphasis is Aura's, not theirs.
         */
        const val ANSWER_IMPORTANCE = 0.7f

        const val NARRATIVE_QUESTIONS = 5
    }
}
