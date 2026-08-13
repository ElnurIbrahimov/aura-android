package com.aura.place

import com.aura.agent.sourceDir
import org.junit.Test
import java.io.File
import kotlin.test.assertTrue

/**
 * A retention window with no caller is a table that grows forever.
 *
 * This is the third time in three days that shape has come up in this repo:
 * `WorkerRunRecorder.prune()` shipped with a unit test, a KDoc naming its caller
 * and no caller at all; `costCeiling` was a declared ceiling that decided
 * nothing. Both looked exactly like working. The place log is the one table
 * where an unbounded version would be a genuine privacy problem rather than a
 * disk one — a 90-day window that never runs is a permanent movement record —
 * so it gets its own gate rather than trusting that the pattern was learned.
 *
 * Source-scanning because the invariant is that a call *exists* on a scheduled
 * path, which nothing at runtime can observe the absence of.
 */
class PlaceRetentionIsWiredTest {

    private fun source(relative: String): File =
        File(sourceDir("src/main/kotlin/com/aura"), relative)
            .also { check(it.isFile) { "$relative not found at ${it.absolutePath} — this test reads the wrong tree" } }

    @Test
    fun `the place log is pruned by the periodic sweep`() {
        val decay = source("proactive/DecayWorker.kt").readText()

        assertTrue(
            "placeLog?.prune()" in decay,
            "nothing prunes place_visits. The 90-day retention in PlaceLog is then a comment rather " +
                "than a behaviour, and the table becomes a permanent record of where the user goes.",
        )
    }

    /**
     * Above the `decayEnabled` gate, beside the worker-run prune. That preference
     * means "do not let my memories fade" and must not also silently mean "keep a
     * location history forever" — the same reasoning the outcome pass is placed
     * by, now for the third caller.
     */
    @Test
    fun `retention runs even when memory decay is switched off`() {
        val decay = source("proactive/DecayWorker.kt").readText()
        val gate = decay.indexOf("decayEnabled.first()")
        val prune = decay.indexOf("placeLog?.prune()")

        assertTrue(gate > 0 && prune > 0, "this test is reading the wrong shape")
        assertTrue(
            prune < gate,
            "the place prune moved below the decayEnabled gate, so switching off memory decay now " +
                "also switches off location retention",
        )
    }

    /** Off by default is the whole reason this is acceptable to ship at all. */
    @Test
    fun `the switch defaults to off`() {
        val prefs = source("data/UserPreferences.kt").readText()

        assertTrue(
            "it[KEY_PLACE_LOG_ENABLED] ?: false" in prefs,
            "the place log no longer defaults to off. It is the only background subsystem that " +
                "collects something new rather than reading what Aura already has, and the default " +
                "for that is no.",
        )
    }
}
