package com.aura.testing

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Every part of the project spine is reachable from something a user can do.
 *
 * This repo's most expensive recurring defect is a subsystem that is built,
 * tested, documented and connected to nothing that runs. The live-voice stack
 * was the standing example: 1,476 lines of production code and 60 tests behind
 * `RealtimeCallController.start`, which had no production caller, reached only
 * through `LiveCallSheet`, which had no caller either. Every gate in the repo
 * passed the whole time, because each one asserts a wire between two points in
 * the code and none asserts a path from a user's thumb.
 *
 * It is wired now, and the first test below holds it that way. Worth recording
 * that naming the defect here did not fix it: this file was written *about* the
 * live-voice stack, gated four other paths, and left that one dead for another
 * two weeks. A gate only protects what it actually asserts.
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
     * out the ProjectStateTool binding left the literal string in the file,
     * `contains` still matched it, and the gate reported the tool wired while
     * the model could not see it — the failure verified by mutation before this
     * helper existed.
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
    fun `the live-call stack is reachable from the chat screen`() {
        // The standing example this file was written about, closed. The KDoc
        // above described 1,476 lines and ~60 tests behind an entry point
        // nothing reached, and it stayed that way after the gate was written
        // for four other paths — the defect being named is not the same as the
        // defect being fixed.
        //
        // The path is: mic button → onVoiceCall → LiveCallSheet → startCall →
        // RealtimeCallController. Each link is asserted, because any one of
        // them missing restores the original condition exactly.
        val route = app("ui/screens/chat/ChatRoute.kt")
        assertTrue(
            route.contains("LiveCallSheet("),
            "ChatRoute does not render LiveCallSheet, so no user action can reach a live call.",
        )
        assertTrue(
            route.contains("liveCallViewModel.startCall("),
            "ChatRoute never calls startCall, so the sheet's Call button does nothing.",
        )
        val vm = app("ui/voice/LiveCallViewModel.kt")
        assertTrue(
            vm.contains("controller.start("),
            "LiveCallViewModel does not start RealtimeCallController — the 1,476 lines behind it " +
                "are unreachable again, which is the exact state this file exists to prevent.",
        )
        assertTrue(
            vm.contains("RealtimeVoiceService.start("),
            "LiveCallViewModel does not start the foreground service. Without it the call dies " +
                "when the user leaves the screen, which is the only place a call is worth having.",
        )
    }

    @Test
    fun `a live call can be ended from the screen that started it`() {
        // The other half of the path, and it was missing for as long as the
        // first half was. `endCall` had zero callers anywhere — the test suite
        // included — so the only exits from a running call were the
        // notification's End action, the ten-minute budget, and navigating far
        // enough away to clear the ViewModel.
        //
        // Asserted separately from the start path on purpose. The test above
        // passed the whole time this was broken, which is the same lesson this
        // file's KDoc already records about itself: a gate only protects what it
        // actually asserts, and "reachable" is two claims, not one.
        val route = app("ui/screens/chat/ChatRoute.kt")
        assertTrue(
            route.contains("LiveCallStatus("),
            "ChatRoute does not render LiveCallStatus, so a running call has no UI at all.",
        )
        assertTrue(
            route.contains("liveCallViewModel.endCall("),
            "ChatRoute never calls endCall. The call can be started and not stopped, on a socket " +
                "that bills per audio-minute and a microphone that stays live.",
        )
        val sheet = app("ui/voice/LiveCallSheet.kt")
        assertTrue(
            sheet.contains("onEndCall: () -> Unit,"),
            "LiveCallStatus takes no onEndCall, or takes one with a default. The default is the " +
                "defect: it turns a forgotten argument into a dead button instead of a build error.",
        )
    }

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
        // Matches the multibinding, not a register call. ToolsModule became an
        // @IntoSet module: a tool is now one `fun provideX(t: XTool): Tool`
        // rather than a constructor parameter plus a `registry.register` line
        // eighty rows away. The invariant is unchanged — the model must be
        // able to see project_state — so only the shape being asserted moved.
        assertTrue(
            module.contains("ProjectStateTool): Tool"),
            "ProjectStateTool is not registered in ToolsModule, so the model never sees " +
                "project_state and 'where is ARC-AGI-2' falls back to a BM25 query — the exact " +
                "behaviour the ledger was built to replace.",
        )
    }

    @Test
    fun `a sealed backup can be opened by the restore path`() {
        // The purest instance of this file's thesis that the repo has produced.
        //
        // BackupWorker sealed every weekly backup with BackupCrypto.seal.
        // BackupCrypto.open was written, correct, and covered by eight tests —
        // and had no production caller, so the restore path fed the sealed
        // envelope to Json.decodeFromString and told the user "Unexpected JSON
        // token at offset 0". Every automatic backup on disk was unopenable by
        // any code in this project, on a phone whose owner had already lost
        // Keystore-encrypted keys once. The button that writes them carries the
        // comment "A backup that has never been restored is not a backup".
        val manager = core("backup/BackupManager.kt")
        assertTrue(
            manager.contains("crypto.open("),
            "BackupManager never calls BackupCrypto.open. The seal has no counterpart again, " +
                "which means every automatic backup is an envelope nothing can read.",
        )

        val viewModel = app("ui/settings/BackupViewModel.kt")
        assertTrue(
            viewModel.contains("backupManager.isSealed("),
            "stageImport does not check whether the picked file is sealed, so a sealed backup " +
                "goes straight to decodeFromJson and dies as a JSON parse error.",
        )
        assertTrue(
            viewModel.contains("backupManager.unseal("),
            "nothing calls unseal. A passphrase the user types has nowhere to go.",
        )

        val section = app("ui/settings/sections/DataAndBackupSection.kt")
        assertTrue(
            section.contains("RestorePassphraseDialog("),
            "the restore passphrase dialog is never rendered, so there is no way for the user " +
                "to supply the one thing that opens the file.",
        )

        val settings = app("ui/screens/SettingsScreen.kt")
        assertTrue(
            settings.contains("backupViewModel::submitImportPassphrase"),
            "the passphrase the dialog collects is not wired back to the view model — the last " +
                "hop, and the one that was missing for the live-call stack too.",
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

    @Test
    fun `every ViewModel action a screen was built for has a screen calling it`() {
        // Five methods, each the only implementation of a feature, each with no
        // caller anywhere: the pin, the project tag from History, the dream
        // summary delete, the agent colour and the creative thinking switch.
        // Every one compiled, every one was covered by the ViewModel's own
        // tests where it had them, and none could be reached by a thumb.
        //
        // Names rather than a pattern, deliberately. "A public ViewModel method
        // must have a UI caller" is false in general — `scripts/check-dead-code.sh`
        // counts the general case and this asserts the specific one.
        val cases = listOf(
            Triple("ui/screens/chat/ChatRoute.kt", "viewModel::togglePinTurn", "a turn cannot be pinned, and Turn.pinned goes back to being a column nothing writes"),
            Triple("ui/screens/HistoryScreen.kt", "viewModel.setConversationProject(", "a conversation can only be tagged to a project while it is open, never afterwards"),
            Triple("ui/screens/DreamsScreen.kt", "viewModel.deleteSummary(", "a dream summary the user disagrees with is permanent"),
            Triple("ui/screens/AgentEditorScreen.kt", "viewModel.updateColor(", "every created agent keeps its template's accent and the marks stop telling agents apart"),
            Triple("ui/screens/creative/CreativeProjectScreen.kt", "viewModel::toggleThinking", "thinkingEnabled is permanently true and the branch it guards has one side"),
        )
        for ((file, call, consequence) in cases) {
            assertTrue(
                app(file).contains(call),
                "$file no longer calls $call — $consequence.",
            )
        }
    }

    @Test
    fun `a run that cannot proceed says which of the three reasons it is`() {
        // `DagResolver.hasCycle` was correct, tested, and had no caller — so the
        // executor failed an unsatisfiable graph with one sentence for a failed
        // dependency, a missing one and a cycle alike. `stuckReason` replaces it
        // WITH a caller, and this is what keeps the caller.
        assertTrue(
            core("agentrun/AgentRunExecutorWorker.kt").contains("dagResolver.stuckReason("),
            "the executor no longer asks why it is stuck, so every unsatisfiable graph goes " +
                "back to reading as \"N steps pending with unmet dependencies\" — which is the " +
                "wrong sentence for the commonest of the three causes.",
        )
    }

    @Test
    fun `a run's own account of itself reaches the screen`() {
        // AgentRunsViewModel has loaded `events` into state since the screen was
        // written and no composable read it, so ten emitEvent call sites wrote
        // rows nothing could show. RUN_RESUMED would have been an eleventh.
        val screen = app("ui/screens/agentrun/AgentRunsScreen.kt")
        assertTrue(
            screen.contains("events = state.events"),
            "AgentRunDetail is not given the run's events, so the timeline is empty and every " +
                "emitEvent in AgentRunStore writes a row nothing can show.",
        )
        assertTrue(
            screen.contains("EventRow(event)"),
            "the events are passed and not rendered, which is the same defect one level down.",
        )
        assertTrue(
            app("ui/viewmodel/AgentRunsViewModel.kt").contains("agentRunStore.markResumed("),
            "resuming a run records nothing, so the timeline cannot say a person restarted it.",
        )
    }

    @Test
    fun `the user model records what the user is talking about`() {
        // `UserModel.topics` is persisted and carried through backup, and its
        // only writers — updateTopic and decayTopics — had no production caller,
        // so it stayed empty forever and both of toPrompt's topic branches were
        // unreachable. The writer is inside updateFromMessage now, which the
        // agentic loop already calls, because a second entry point is exactly
        // what went unwired the first time.
        val tom = core("consciousness/TheoryOfMind.kt")
        assertTrue(
            tom.contains("topics = updateTopics("),
            "updateFromMessage no longer writes the topic map, so UserModel.topics goes back to " +
                "being a persisted, backed-up map that nothing fills.",
        )
        assertTrue(
            tom.contains("TERM_PATTERNS"),
            "topic matching is back to bare substrings, which counts \"therapist\" as api and " +
                "tells the model the user is an expert in it.",
        )
        assertTrue(
            core("agent/MemoryAugmentedAgenticLoop.kt").contains("theoryOfMind?.updateFromMessage("),
            "nothing calls updateFromMessage, so the whole user model is unreachable again.",
        )
    }

    @Test
    fun `the living world can be switched off`() {
        // `UserPreferences.setLivingWorldEnabled` had no caller at all, so the
        // flow ProactiveBootstrap reconciles on could only ever carry its
        // default — an hourly worker plus up to twelve model calls a day per
        // world, with no way to stop it. The reconcile loop was correct the
        // whole time; there was nothing on the other end of the switch.
        assertTrue(
            app("ui/settings/SettingsViewModel.kt").contains("userPreferences.setLivingWorldEnabled("),
            "SettingsViewModel never writes the preference, so the switch has nothing behind it.",
        )
        assertTrue(
            app("ui/screens/SettingsScreen.kt").contains("viewModel::setLivingWorldEnabled"),
            "SettingsScreen does not pass the setter down, so the switch has nothing in front of it.",
        )
        assertTrue(
            app("ui/settings/sections/PrivacySection.kt").contains("onCheckedChange = onSetLivingWorldEnabled"),
            "PrivacySection renders no switch for it.",
        )
        assertTrue(
            core("proactive/ProactiveBootstrap.kt").contains("userPreferences.livingWorldEnabled"),
            "ProactiveBootstrap no longer reconciles on the preference, so flipping it changes nothing.",
        )
    }
}
