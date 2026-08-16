package com.aura.memory

import com.aura.agent.sourceDir
import org.junit.Test
import java.io.File
import kotlin.test.assertTrue

/**
 * `retrieval_labels` has no cascade and no automatic bound, so both ends of its
 * lifecycle are calls someone has to remember to make.
 *
 * The table lives in `MemoryDatabase` and `conversations` lives in
 * `ConversationDatabase`. SQLite has no cross-database foreign keys, so nothing
 * deletes a label when its conversation goes, and nothing bounds the table when
 * it grows. Both are hand-wired, and a hand-wired call is exactly what the
 * fourth instance of this defect in this repo looked like before it was found:
 * `WorkerRunRecorder.prune()` shipped with a unit test, a KDoc naming its caller,
 * and no caller.
 *
 * Modelled on [com.aura.place.PlaceRetentionIsWiredTest], and source-scanning
 * for the same reason: the invariant is that a call *exists* on a scheduled
 * path, and nothing at runtime can observe its absence.
 *
 * These rows carry the user's own questions, which makes an unbounded version a
 * privacy problem and not merely a disk one — the same argument the place log
 * gets its own gate for.
 */
class RetrievalLabelLifecycleIsWiredTest {

    private fun source(relative: String): File =
        File(sourceDir("src/main/kotlin/com/aura"), relative)
            .also { check(it.isFile) { "$relative not found at ${it.absolutePath} — this test reads the wrong tree" } }

    @Test
    fun `harvested labels are pruned by the periodic sweep`() {
        val decay = source("proactive/DecayWorker.kt").readText()

        assertTrue(
            "retrievalLabels?.prune()" in decay,
            "nothing prunes retrieval_labels. The 30-day window in RetrievalLabelStore is then a " +
                "comment rather than a behaviour, and the table becomes a permanent record of every " +
                "question the user has asked.",
        )
    }

    /**
     * Above the `decayEnabled` gate, beside the other three sweeps. That
     * preference means "do not let my memories fade"; it must not also silently
     * mean "keep my questions forever".
     */
    @Test
    fun `retention runs even when memory decay is switched off`() {
        val decay = source("proactive/DecayWorker.kt").readText()
        val gate = decay.indexOf("decayEnabled.first()")
        val prune = decay.indexOf("retrievalLabels?.prune()")

        assertTrue(gate > 0 && prune > 0, "this test is reading the wrong shape")
        assertTrue(
            prune < gate,
            "the retrieval-label prune moved below the decayEnabled gate, so switching off memory " +
                "decay now also switches off retention of the user's questions",
        )
    }

    /**
     * Deleting a conversation has to take its labels, because nothing else will.
     *
     * Hooked at the tombstone in `delete` rather than at `purgeDeletedOlderThan`,
     * which returns a count rather than ids and so cannot say what to forget.
     */
    @Test
    fun `deleting a conversation forgets its labels`() {
        val store = source("agent/ConversationStore.kt").readText()

        assertTrue(
            "retrievalLabels?.forgetConversation(" in store,
            "deleting a conversation no longer deletes the labels harvested from it, and no cascade " +
                "can do it instead — they are in different Room databases.",
        )
        assertTrue(
            "retrievalLabels?.forgetAll()" in store,
            "deleteAll no longer clears harvested labels",
        )
    }
}
