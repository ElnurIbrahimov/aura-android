package com.aura.health

import com.aura.agent.requireNonEmpty
import com.aura.agent.sourceDir
import org.junit.Test
import kotlin.test.assertTrue

/**
 * A worker that catches an exception must say so in its run record.
 *
 * `WorkerRunRecorder.record` takes the worker's own verdict — `runPass() to
 * lastOutcome` — and `lastOutcome` is initialised to `ok("")`. A catch block
 * that handles the throw itself and returns normally therefore reports **success
 * over a failed run**, because `record` never sees the exception and the worker
 * never revised its verdict.
 *
 * Three workers shipped with exactly that: `EvolutionWorker` (which also logged
 * nothing at all), `CalendarCheckWorker` and `DecayWorker`. BackgroundHealth —
 * the one screen that answers "is the background work alive" — showed all three
 * green through every failure they had. `DreamWorker` and `DaemonWorker` got it
 * right, which is what makes this a drift problem rather than a design one.
 *
 * Scanned rather than exercised because the property is "this catch block
 * assigns before it returns", which no runtime handle exposes: a correct worker
 * and a broken one produce the same `Result.retry()` and differ only in a field
 * that was already the default.
 */
class WorkerFailureIsRecordedTest {

    private fun stripComments(source: String): String = source
        .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
        .lineSequence()
        .joinToString("\n") { it.substringBefore("//") }

    @Test
    fun `every worker that reports an outcome sets one on the failure path`() {
        val sources = sourceDir("src/main/kotlin")
            .walkTopDown()
            .filter { it.extension == "kt" }
            .map { it to stripComments(it.readText()) }
            // Only files that keep a `lastOutcome` verdict are in scope; a
            // worker without one hands `record` nothing to get wrong.
            .filter { (_, text) -> text.contains("lastOutcome") && text.contains("catch (e: Exception)") }
            .toList()
            .requireNonEmpty("workers carrying a lastOutcome verdict")

        val violations = sources.mapNotNull { (file, text) ->
            // Each `catch (e: Exception)` body, up to the closing brace column.
            val bodies = Regex("""catch\s*\(\s*e:\s*Exception\s*\)\s*\{([\s\S]*?)\n\s{0,12}\}""")
                .findAll(text)
                .map { it.groupValues[1] }
                .toList()
            val silent = bodies.count { body ->
                // A body that returns a Result without revising the verdict.
                !body.contains("lastOutcome") && body.contains("Result.")
            }
            if (silent > 0) "${file.name}: $silent catch block(s) return without setting lastOutcome" else null
        }

        assertTrue(
            violations.isEmpty(),
            "Workers whose failure path reports success:\n  ${violations.joinToString("\n  ")}\n\n" +
                "`lastOutcome` starts as ok(\"\"), and `WorkerRunRecorder.record` writes whatever it " +
                "holds when the block returns. A catch that handles the throw and returns without " +
                "assigning records a healthy run over a failed one, and BackgroundHealth is the only " +
                "place that would ever have shown otherwise. Use " +
                "`lastOutcome = WorkerRunRecorder.Result.failed(e)`.",
        )
    }
}
