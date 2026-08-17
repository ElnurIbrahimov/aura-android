package com.aura.projects

import com.aura.agent.ConversationStore
import com.aura.data.UserPreferences
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One pass of the project ledger over recently-used conversations.
 *
 * Separate from [ProjectLedgerWorker] for the reason `PlaceLog` is separate from
 * `PlaceLogWorker`: the worker is an adapter onto WorkManager and the decisions
 * are here, where they can be tested without a `WorkerParameters` or a running
 * scheduler. The decision that most needs testing is the watermark, which has a
 * silent, permanent failure mode — see [sweep].
 */
@Singleton
class ProjectLedgerSweep @Inject constructor(
    private val conversationStore: ConversationStore,
    private val projectStore: ProjectStore,
    private val extractor: ProjectLedgerExtractor,
    private val userPreferences: UserPreferences,
) {
    data class Outcome(
        val conversationsRead: Int = 0,
        val notesWritten: Int = 0,
        val reason: String = "",
    )

    /**
     * Read every project-tagged conversation with turns newer than its watermark.
     *
     * **The watermark advances only after a pass that actually ran**, and only to
     * the newest turn that pass read. Two failures follow from getting either
     * half wrong, and both are silent:
     *
     * - Advancing after a skip — no model, budget spent, project deleted — means
     *   those turns are never read again. Whatever was decided in them is gone
     *   from the ledger permanently, and nothing anywhere reports it.
     * - Advancing to `now` rather than to the newest turn read means a turn that
     *   arrives while the sweep is in flight lands behind the watermark and is
     *   skipped forever.
     *
     * Re-reading is the safe direction and costs nothing: an identical
     * restatement is folded onto the existing row by
     * [ProjectStore.recordNote].
     */
    suspend fun sweep(): Outcome {
        val model = userPreferences.backgroundModel.first()?.takeIf { it.isNotBlank() }
            ?: return Outcome(reason = "no background model configured")

        val tagged = runCatching { conversationStore.recent(CONVERSATION_SCAN) }
            .getOrDefault(emptyList())
            .filter { conversationStore.projectOf(it) != null }
        if (tagged.isEmpty()) return Outcome(reason = "no project-tagged conversations")

        var read = 0
        var notes = 0
        for (conv in tagged.take(MAX_PER_SWEEP)) {
            val name = conversationStore.projectOf(conv) ?: continue
            // The tag holds a name, not an id — see ConversationStore.projectOf.
            val project = projectStore.byName(name) ?: continue
            val watermark = conversationStore.ledgerWatermarkOf(conv)
            val fresh = conv.turns.filter { it.timestamp > watermark }
            if (fresh.isEmpty()) continue

            val outcome = extractor.extract(
                projectId = project.id,
                conversationId = conv.id,
                turns = fresh,
                baseModel = model,
            )
            if (!outcome.ran) continue

            read++
            notes += outcome.notesWritten
            val newest = fresh.maxOf { it.timestamp }
            conversationStore.setLedgerWatermark(conv.id, newest)
            projectStore.touch(project.id, newest)
        }

        return Outcome(
            conversationsRead = read,
            notesWritten = notes,
            reason = if (read == 0) "nothing new said" else "read $read conversation(s), wrote $notes note(s)",
        )
    }

    companion object {
        /** How far back a sweep looks for tagged conversations. */
        const val CONVERSATION_SCAN = 60

        /**
         * Conversations extracted per sweep — one model call each.
         *
         * `BackgroundBudget` is the real spend bound; this stops a single sweep
         * consuming the whole day's allowance in one burst and starving the
         * dream cycle and morning brief queued behind it.
         */
        const val MAX_PER_SWEEP = 5
    }
}
