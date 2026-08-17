package com.aura.testing

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Every part of the project spine is reachable from something a user can do.
 *
 * This repo's most expensive recurring defect is a subsystem that is built,
 * tested, documented and connected to nothing that runs. The live-voice stack is
 * the standing example: 1,476 lines of production code and 60 tests behind
 * `RealtimeCallController.start`, which has no production caller, reached only
 * through `LiveCallSheet`, which has no caller either. Every gate in the repo
 * passed the whole time, because each one asserts a wire between two points in
 * the code and none asserts a path from a user's thumb.
 *
 * So this file checks the path, not the parts:
 *
 * - the sweep is scheduled from `ProactiveBootstrap`, or it never runs
 * - `project_state` is registered, or the model cannot answer "where is X"
 * - the Mind screen calls the section, or the ledger is invisible and therefore
 *   uncorrectable
 * - the chat header's picker is wired, or nothing can ever be attributed and
 *   every other part above is dead by starvation
 *
 * Source-scanning because the property is "this call site exists", which is
 * erased by the time anything runs. Every scan goes through [sourceDir] and
 * [requireNonEmpty], so a version of this test that resolved the wrong path
 * fails loudly rather than passing over an empty file list.
 */
class ProjectSpineIsWiredTest {

    /**
     * The `:aura-core` source root.
     *
     * Not [sourceDir]: run from the `app` module — which is how Gradle runs
     * these — its first candidate `src/main/kotlin/com/aura` resolves inside
     * `app` and never reaches `aura-core`. Walking up to the directory holding
     * `settings.gradle.kts` locates the repo root from either working directory,
     * and failing loudly beats silently reading the wrong module's sources: a
     * file that does not exist there would make every assertion below vacuous.
     */
    private fun coreDir(): File {
        var dir: File? = File(System.getProperty("user.dir"))
        while (dir != null && !File(dir, "settings.gradle.kts").isFile) dir = dir.parentFile
        val root = dir ?: error("repo root not found from ${System.getProperty("user.dir")}")
        return File(root, "aura-core/src/main/kotlin/com/aura")
            .also { check(it.isDirectory) { "aura-core sources not found at ${it.absolutePath}" } }
    }

    /**
     * Source with comments removed.
     *
     * Load-bearing, and this test shipped without it for one commit. Commenting
     * out `registry.register(projectState.tool)` left the literal string in the
     * file, `contains` still matched it, and the gate reported the tool wired
     * while the model could not see it — the failure verified by mutation before
     * this helper existed.
     *
     * ENGINEERING_HISTORY records the same defect in
     * `ForegroundAppIsNeverStoredTest`, which fired on a *KDoc* because the scan
     * matched raw file text and prose counted as consumption. A wiring gate that
     * cannot tell code from a comment about code asserts nothing.
     */
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
    fun `the ledger sweep is scheduled at startup`() {
        val bootstrap = core("proactive/ProactiveBootstrap.kt")
        assertTrue(
            bootstrap.contains("scheduleProjectLedger()"),
            "ProactiveBootstrap does not call scheduleProjectLedger(). The worker, the sweep, " +
                "the extractor and both new tables are then unreachable — nothing would ever " +
                "write a project note, and every test below this one would still pass.",
        )
        val scheduler = core("proactive/ProactiveScheduler.kt")
        assertTrue(
            scheduler.contains("ProjectLedgerWorker"),
            "ProactiveScheduler.scheduleProjectLedger does not enqueue ProjectLedgerWorker.",
        )
    }

    @Test
    fun `the worker delegates to the sweep rather than reimplementing it`() {
        val worker = core("projects/ProjectLedgerWorker.kt")
        assertTrue(
            worker.contains("sweep.sweep()"),
            "ProjectLedgerWorker does not call ProjectLedgerSweep. The watermark rules — which " +
                "are where this feature can lose data permanently — are tested on the sweep, so " +
                "a worker with its own copy of them is untested by construction.",
        )
    }

    @Test
    fun `project_state is registered as a tool`() {
        val module = core("tools/ToolsModule.kt")
        assertTrue(
            module.contains("registry.register(projectState.tool)"),
            "ProjectStateTool is not registered in ToolsModule, so the model never sees " +
                "project_state and 'where is ARC-AGI-2' falls back to a BM25 query — the exact " +
                "behaviour the ledger was built to replace.",
        )
    }

    @Test
    fun `the mind screen renders the ledger`() {
        val screen = app("ui/screens/MindScreen.kt")
        assertTrue(
            screen.contains("projectsSection(mindViewModel)"),
            "MindScreen does not call projectsSection. The ledger is written by a model on a " +
                "background sweep; if it is never displayed, a wrong row cannot be noticed and " +
                "the write path has no correction signal at all.",
        )
    }

    @Test
    fun `the chat header can attribute a conversation`() {
        val header = app("ui/screens/chat/ChatHeader.kt")
        assertTrue(
            header.contains("chat-project-pill"),
            "ChatHeader renders no project pill, so nothing can be attributed to a project.",
        )
        val route = app("ui/screens/chat/ChatRoute.kt")
        assertTrue(
            route.contains("ProjectPickerSheet("),
            "ChatRoute never shows ProjectPickerSheet, so the pill opens nothing.",
        )
        assertTrue(
            route.contains("viewModel.setActiveProject("),
            "The picker does not call setActiveProject, so picking a project changes nothing. " +
                "Attribution is the input to the whole spine: with no tagged conversation the " +
                "sweep finds nothing and every other wire above is live but starved.",
        )
    }

    @Test
    fun `attribution is written where the sweep and the history filter read it`() {
        val vm = app("ui/viewmodel/ChatViewModel.kt")
        assertTrue(
            vm.contains("conversationStore.setProject("),
            "setActiveProject does not tag the conversation. ProjectLedgerSweep reads the tag, " +
                "not the UI state, so the ledger would stay permanently empty while the header " +
                "showed a project.",
        )
        assertTrue(
            vm.contains("setStickyProjectId("),
            "setActiveProject does not persist the sticky project, so attribution would have to " +
                "be repeated for every new conversation — which, with eight live projects, means " +
                "it stops being done at all.",
        )
    }

    @Test
    fun `the ledger is backed up`() {
        val manager = core("backup/BackupManager.kt")
        for (dao in listOf("projectDao", "projectNoteDao")) {
            assertTrue(
                manager.contains("$dao?.deleteAll()"),
                "BackupManager.purgeAll does not clear $dao.",
            )
            assertTrue(
                manager.contains("$dao?.upsertAll("),
                "No restore path writes $dao back. A failed restore calls purgeAll and then " +
                    "re-writes the snapshot, so anything cleared and not written is destroyed by " +
                    "the rollback — see BackupCoverageAuditTest.",
            )
        }
    }
}
