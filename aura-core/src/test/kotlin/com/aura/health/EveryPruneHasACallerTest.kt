package com.aura.health

import com.aura.agent.requireNonEmpty
import com.aura.agent.sourceDir
import org.junit.Test
import kotlin.test.assertTrue

/**
 * A retention window with no caller is a table that grows forever.
 *
 * This has now been found four separate times in this repo:
 * `WorkerRunRecorder.prune` shipped with a unit test and no production caller;
 * `PlaceLog.prune` was gated afterwards precisely because of it;
 * `RetrievalLabelStore.prune`'s KDoc names it as the defect it was written to
 * avoid; and `EvolutionEvidenceDao.deleteOlderThan` had no caller at all while
 * its table took a five-index row per stored *and per recalled* memory.
 *
 * ENGINEERING_HISTORY calls it "the third time in three days" and gave place a
 * dedicated gate rather than trust. `PlaceRetentionIsWiredTest` is that gate,
 * and it protects exactly one table. This one is derived instead: it finds every
 * `prune()` in the module and requires each to be reachable from something that
 * runs, so the fifth instance fails here rather than being discovered by a
 * sixth review.
 *
 * A caller in a test does not count — that is the precise shape of the original
 * defect, a prune that was exercised and never scheduled.
 */
class EveryPruneHasACallerTest {

    private fun stripComments(source: String): String = source
        .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
        .lineSequence()
        .joinToString("\n") { it.substringBefore("//") }

    @Test
    fun `every prune is called from production code`() {
        val mainDir = sourceDir("src/main/kotlin")
        val sources = mainDir.walkTopDown()
            .filter { it.extension == "kt" }
            .map { it to stripComments(it.readText()) }
            .toList()
            .requireNonEmpty("main sources")

        // Declarations: `fun prune(` on a type, excluding private helpers, which
        // are by definition called from within their own file.
        val declaring = sources.filter { (_, text) ->
            Regex("""(?<!private )(?:suspend )?fun prune\(""").containsMatchIn(text)
        }.requireNonEmpty("prune declarations")

        val allMainText = sources.joinToString("\n") { it.second }

        val orphans = declaring.mapNotNull { (file, ownText) ->
            // A call from anywhere in main that is not this file's own
            // declaration line. `?.prune()` is the shape every scheduled sweep
            // uses, since the collaborators are nullable-injected.
            val otherFiles = sources.filter { it.first != file }.joinToString("\n") { it.second }
            val calledElsewhere = Regex("""\.prune\(""").containsMatchIn(otherFiles)
            // Also allow a same-file call that is not the declaration itself.
            val selfCalls = Regex("""\.prune\(""").findAll(ownText).count()
            if (!calledElsewhere && selfCalls == 0) file.name else null
        }

        assertTrue(
            orphans.isEmpty(),
            "prune() declared with no production caller: ${orphans.joinToString(", ")}\n\n" +
                "A retention window nothing calls is a table that grows for the life of the " +
                "install, and it looks exactly like a retention window that works. This repo has " +
                "found that four times. Schedule it — DecayWorker holds the other four sweeps, " +
                "above its decayEnabled gate, because retention is not a feature the user opted " +
                "into.\n(main sources scanned: ${sources.size}, prune declarations: " +
                "${declaring.size}, all-main length: ${allMainText.length})",
        )
    }

    @Test
    fun `DecayWorker is where the sweeps live`() {
        // Not a style rule. Every sweep sits above the `decayEnabled` gate in
        // one worker so that "is retention running" is one question with one
        // answer, rather than five schedules that can each be switched off by
        // something unrelated to retention.
        val decay = stripComments(
            sourceDir("src/main/kotlin").resolve("com/aura/proactive/DecayWorker.kt").readText(),
        )
        val sweeps = Regex("""\?\.prune\(""").findAll(decay).count()
        assertTrue(
            sweeps >= 4,
            "DecayWorker holds $sweeps prune sweeps; expected at least the worker-run, place, " +
                "retrieval-label and evolution-evidence ones. A sweep that moved elsewhere is " +
                "how the single answer to 'is retention running' stops being single.",
        )
    }
}
