package com.aura.widget

import com.aura.health.WorkerRunEntity
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The line is the whole feature, so the line is what is tested.
 *
 * [WorkingOnWidget.describe] is pure and internal precisely so this can run
 * without a device, a launcher or a database — the risk here is all in deciding
 * what to say, and none of it needs any of those.
 */
class WorkingOnWidgetTest {

    private val now = 1_700_000_000_000L
    private val minute = 60_000L

    private fun run(
        worker: String,
        startedAt: Long,
        finishedAt: Long = 0L,
        detail: String = "",
        outcome: String = WorkerRunEntity.OUTCOME_OK,
    ) = WorkerRunEntity(
        worker = worker,
        startedAt = startedAt,
        finishedAt = finishedAt,
        outcome = outcome,
        detail = detail,
    )

    // ---- in flight --------------------------------------------------------

    @Test
    fun `an unfinished row is reported in the present tense`() {
        // record() writes the row before running the work, so finishedAt == 0
        // already meant "in flight" — no new table, just a reader that looks.
        val state = WorkingOnWidget.describe(
            listOf(
                run("DecayWorker", startedAt = now - 90 * minute, finishedAt = now - 89 * minute, detail = "12 memories faded"),
                run("DreamWorker", startedAt = now - 2 * minute),
            ),
            now,
        )

        assertEquals("Consolidating what it learned", state.line)
        assertEquals("Dreams · now", state.source)
    }

    @Test
    fun `a stale unfinished row is not claimed as still running`() {
        // An orphaned row means the process was killed mid-pass — a real
        // finding, but not a current activity. Saying "still dreaming" eleven
        // hours later would be the widget inventing a state, which is the one
        // thing it must never do.
        val state = WorkingOnWidget.describe(
            listOf(
                run("DreamWorker", startedAt = now - 11 * 60 * minute),
                run("DecayWorker", startedAt = now - 3 * minute, finishedAt = now - 2 * minute, detail = "nothing had faded enough to move"),
            ),
            now,
        )

        assertEquals("nothing had faded enough to move", state.line)
        assertTrue("Memory decay" in state.source)
    }

    @Test
    fun `the newest in-flight row wins when two are running`() {
        val state = WorkingOnWidget.describe(
            listOf(
                run("TriggerWorker", startedAt = now - 5 * minute),
                run("BackupWorker", startedAt = now - 1 * minute),
            ),
            now,
        )

        assertEquals("Backing up", state.line)
    }

    // ---- what it last did -------------------------------------------------

    @Test
    fun `the worker's own words are quoted, not re-described`() {
        // Each detail is written by the worker in the user's terms. Rewriting
        // them here would put a second author between the work and the line,
        // and the two would drift.
        val state = WorkingOnWidget.describe(
            listOf(run("DreamWorker", startedAt = now - 60 * minute, finishedAt = now - 59 * minute, detail = "3 summaries, 2 clusters, raised a question")),
            now,
        )

        assertEquals("3 summaries, 2 clusters, raised a question", state.line)
        assertEquals("Dreams · 59m ago", state.source)
    }

    @Test
    fun `the most recently finished run is the one shown`() {
        val state = WorkingOnWidget.describe(
            listOf(
                run("DreamWorker", startedAt = now - 300 * minute, finishedAt = now - 299 * minute, detail = "older"),
                run("TriggerWorker", startedAt = now - 10 * minute, finishedAt = now - 9 * minute, detail = "checked 4, none fired"),
                run("DecayWorker", startedAt = now - 200 * minute, finishedAt = now - 199 * minute, detail = "middle"),
            ),
            now,
        )

        assertEquals("checked 4, none fired", state.line)
    }

    @Test
    fun `a blank detail falls back to something rather than showing nothing`() {
        // Four workers used to record ok("") and that is now fixed, but a
        // worker added later could forget. A dull line beats an empty one.
        val state = WorkingOnWidget.describe(
            listOf(run("EvolutionWorker", startedAt = now - 5 * minute, finishedAt = now - 4 * minute, detail = "")),
            now,
        )

        assertEquals("Reviewed itself", state.line)
    }

    // ---- nothing to say ---------------------------------------------------

    @Test
    fun `an empty log says so plainly`() {
        val state = WorkingOnWidget.describe(emptyList(), now)

        assertEquals("Nothing has run yet.", state.line)
        assertEquals("waiting for the first pass", state.source)
    }

    @Test
    fun `a log holding only a stale unfinished row still says nothing has run`() {
        // Neither in flight nor finished. The honest answer is the empty one.
        val state = WorkingOnWidget.describe(
            listOf(run("DreamWorker", startedAt = now - 5 * 60 * minute)),
            now,
        )

        assertEquals("Nothing has run yet.", state.line)
    }

    // ---- provenance -------------------------------------------------------

    @Test
    fun `elapsed time is rounded to the unit a glance can use`() {
        fun sourceAfter(elapsed: Long) = WorkingOnWidget.describe(
            listOf(run("BackupWorker", startedAt = now - elapsed - minute, finishedAt = now - elapsed, detail = "wrote a file")),
            now,
        ).source

        assertEquals("Backup · just now", sourceAfter(30_000))
        assertEquals("Backup · 5m ago", sourceAfter(5 * minute))
        assertEquals("Backup · 3h ago", sourceAfter(3 * 60 * minute))
        assertEquals("Backup · 2d ago", sourceAfter(2 * 24 * 60 * minute))
    }

    @Test
    fun `an unrecognised worker degrades to its own name rather than to a blank`() {
        val state = WorkingOnWidget.describe(
            listOf(run("SomeFutureWorker", startedAt = now - 2 * minute)),
            now,
        )

        assertEquals("Working", state.line)
        assertEquals("SomeFuture · now", state.source)
    }

    @Test
    fun `a clock that jumped backwards does not read as in flight`() {
        // now - startedAt goes negative if the device clock moves back. The
        // window check is a range rather than a less-than for that reason;
        // without it a future-dated row would be reported as running forever.
        val state = WorkingOnWidget.describe(
            listOf(run("DreamWorker", startedAt = now + 60 * minute)),
            now,
        )

        assertEquals("Nothing has run yet.", state.line)
    }
}
