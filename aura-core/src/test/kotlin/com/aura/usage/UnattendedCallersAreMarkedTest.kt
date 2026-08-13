package com.aura.usage

import com.aura.agent.requireNonEmpty
import com.aura.agent.sourceDir
import org.junit.Test
import java.io.File
import kotlin.test.assertTrue

/**
 * A background caller that forgets `attended = false` is not capped.
 *
 * `ChatOptions.attended` defaults to true deliberately — refusing a chat turn
 * because a dream cycle spent the budget overnight would be a far worse failure
 * than an unbounded background job. The cost of that choice is that forgetting
 * the flag fails *open*, silently, and looks exactly like working.
 *
 * So the flag cannot be the only thing holding the guarantee up. This is the
 * list of subsystems that spend money on a timer with nobody waiting, and every
 * one of them has to say so. A seventh subsystem added later will not be here
 * and will not be capped — which is why the failure message names the file to
 * add rather than just reporting a mismatch.
 *
 * Source-scanning because the property is "this call site passes this argument",
 * which is erased by the time anything runs.
 */
class UnattendedCallersAreMarkedTest {

    /**
     * Every subsystem that reaches a model without a person waiting.
     *
     * `EvolutionWorker` is absent on purpose: it schedules and delegates rather
     * than calling a model itself. `MemoryReranker` is absent because it runs
     * inside an attended recall — the user is waiting for that answer.
     */
    private val unattended = listOf(
        "dream/DreamConsolidator.kt",
        "proactive/DaemonWorker.kt",
        "proactive/MorningBriefBuilder.kt",
        "proactive/IdleTimePreparationEngine.kt",
        "curiosity/QuestionAuthor.kt",
        "curiosity/SelfServeResearcher.kt",
        "creative/livingworld/WorldNarrator.kt",
    )

    private fun source(relative: String): File =
        File(sourceDir("src/main/kotlin/com/aura"), relative)
            .also { check(it.isFile) { "$relative not found at ${it.absolutePath} — this test is reading the wrong tree" } }

    @Test
    fun `every timer-driven model caller marks itself unattended`() {
        val unmarked = unattended
            .requireNonEmpty("unattended subsystems")
            .filterNot { "attended = false" in source(it).readText() }

        assertTrue(
            unmarked.isEmpty(),
            "these spend money on a timer with nobody waiting, and are not counted against the daily " +
                "background budget:\n" + unmarked.joinToString("\n") { "  - $it" } +
                "\nPass `attended = false` in their ChatOptions. Without it the call is treated as the " +
                "user's own turn and is never capped — which fails open and looks exactly like working.",
        )
    }

    /**
     * The check itself has to be at the choke point. Moving it into the callers
     * would mean a new caller can skip it, which is the defect this repo keeps
     * finding under other names.
     */
    @Test
    fun `the cap is enforced where every call passes`() {
        val registry = source("providers/ProviderRegistry.kt").readText()

        assertTrue(
            "options.attended" in registry && "hasHeadroom" in registry,
            "ProviderRegistry no longer checks the background budget. It is the only place every LLM " +
                "call in the app passes through, and therefore the only place the check cannot be forgotten.",
        )
    }

    /**
     * Hitting the ceiling is not a fault. Recorded once, centrally, so the six
     * workers that can hit it do not each need a catch that a seventh forgets.
     */
    @Test
    fun `an exhausted budget is recorded as a skip rather than a failure`() {
        val recorder = source("health/WorkerRunRecorder.kt").readText()

        assertTrue(
            "BackgroundBudgetExhausted" in recorder && "OUTCOME_SKIPPED" in recorder,
            "WorkerRunRecorder no longer maps an exhausted budget to a skip, so a normal end-of-budget " +
                "day will show up in BackgroundHealth as six failed workers",
        )
    }
}
