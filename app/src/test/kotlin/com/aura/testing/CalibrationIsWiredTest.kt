package com.aura.testing

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The calibration loop is closed end to end.
 *
 * Calibration is unusually easy to build as a dead subsystem, because every part
 * of it degrades *quietly* into looking correct. An author nobody calls writes
 * no questions; a prompt block nobody renders asks nothing; a sweep nobody runs
 * grades nothing — and the outcome of all three is the same screen saying "0 of
 * 20 claims scored", which is exactly what a working system says on day one.
 * There is no failure state that looks like a failure.
 *
 * So the wires get a test rather than trust. Each assertion below names what
 * breaks silently if it goes, since a gate that only says "false was not true"
 * teaches the next person nothing.
 *
 * Comments are stripped before matching. `ProjectSpineIsWiredTest` shipped
 * without that and reported a commented-out tool registration as wired, which is
 * the defect `ForegroundAppIsNeverStoredTest` records hitting a KDoc: a scan over
 * raw file text counts prose as code.
 */
class CalibrationIsWiredTest {

    /** See `ProjectSpineIsWiredTest.coreDir` — `sourceDir` resolves inside `app`. */
    private fun coreDir(): File {
        var dir: File? = File(System.getProperty("user.dir"))
        while (dir != null && !File(dir, "settings.gradle.kts").isFile) dir = dir.parentFile
        val root = dir ?: error("repo root not found from ${System.getProperty("user.dir")}")
        return File(root, "aura-core/src/main/kotlin/com/aura")
            .also { check(it.isDirectory) { "aura-core sources not found at ${it.absolutePath}" } }
    }

    private fun stripComments(source: String): String = source
        .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
        .lineSequence()
        .joinToString("\n") { it.substringBefore("//") }

    private fun core(relative: String): String =
        stripComments(
            File(coreDir(), relative)
                .also { check(it.isFile) { "expected ${it.absolutePath} to exist" } }
                .readText(),
        )

    private fun app(relative: String): String =
        stripComments(
            File(sourceDir("src/main/kotlin/com/aura"), relative)
                .also { check(it.isFile) { "expected ${it.absolutePath} to exist" } }
                .readText(),
        )

    @Test
    fun `something asks the question`() {
        val curiosity = core("curiosity/CuriosityStore.kt")
        assertTrue(
            curiosity.contains("verificationAuthor") && curiosity.contains("nextQuestion("),
            "CuriosityStore never calls BeliefVerificationAuthor, so no verification question is " +
                "ever written. Every other part of calibration would still compile, still be " +
                "tested, and produce an empty report indistinguishable from a new install.",
        )
    }

    @Test
    fun `the question actually reaches the model`() {
        val loop = core("agent/MemoryAugmentedAgenticLoop.kt")
        assertTrue(
            loop.contains("KIND_VERIFICATION"),
            "The agentic loop does not look for a verification question, so one can be written " +
                "and never asked. Routing it through IntrinsicMotivation instead is not " +
                "sufficient: toPrompt() renders only mostUrgent(), one drive of four.",
        )
        assertTrue(
            loop.contains("verificationBlock,"),
            "verificationBlock is computed but never added to the step-1 prompt list — the " +
                "defaulted-parameter shape that left SceneContextBuilder's storySoFar unsupplied " +
                "for months.",
        )
    }

    @Test
    fun `something grades the answer`() {
        val decay = core("proactive/DecayWorker.kt")
        // The CALL, not the name. `verdictSweep` alone also matches the
        // constructor parameter, so the first version of this assertion passed
        // with the call commented out — a gate that proves the dependency was
        // injected and never that anything invokes it.
        assertTrue(
            decay.contains("verdictSweep?.sweep()"),
            "DecayWorker declares BeliefVerdictSweep but never calls it, so answered verification " +
                "questions are never turned into verdicts and claim_resolutions stays permanently " +
                "empty — which on screen is indistinguishable from a new install.",
        )
        val sweep = core("calibration/BeliefVerdictSweep.kt")
        assertTrue(
            sweep.contains("attended = false"),
            "The grading call is not marked unattended, so BackgroundBudget never caps it. " +
                "ChatOptions.attended defaults to true and fails open silently.",
        )
        assertTrue(
            sweep.contains("SOURCE_CHAT_ANSWER"),
            "The sweep does not attribute its verdicts, so per-source reliability cannot separate " +
                "chat answers from inherited corrections.",
        )
    }

    @Test
    fun `the number is shown`() {
        val screen = app("ui/screens/MindScreen.kt")
        assertTrue(
            screen.contains("calibrationSection(mindViewModel)"),
            "MindScreen does not render the calibration section. The whole feature exists to put " +
                "a number in front of a person; unrendered, it is a table nothing reads.",
        )
        val vm = app("ui/viewmodel/MindViewModel.kt")
        assertTrue(
            vm.contains("calibrationReader?.report()"),
            "MindViewModel never asks for the report, so the section renders a permanent null.",
        )
    }

    @Test
    fun `the floor cannot be bypassed by the screen`() {
        val screen = app("ui/screens/MindScreen.kt")
        assertTrue(
            screen.contains("current.reportable"),
            "MindScreen does not consult Report.reportable, so it would render a percentage over " +
                "a handful of samples — a guess laundered into a statistic, which is worse than " +
                "showing nothing.",
        )
    }

    @Test
    fun `verdicts are backed up`() {
        val manager = core("backup/BackupManager.kt")
        assertTrue(
            manager.contains("claimResolutionDao?.deleteAll()"),
            "purgeAll does not clear claim_resolutions.",
        )
        assertTrue(
            manager.contains("claimResolutionDao?.upsertAll("),
            "No restore path writes claim_resolutions back, so a failed restore destroys it. " +
                "These are the only rows in the export a person produced by hand and nothing " +
                "could regenerate them.",
        )
    }
}
