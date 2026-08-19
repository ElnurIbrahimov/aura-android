package com.aura.ui.settings

import android.content.Context
import android.net.Uri
import com.aura.backup.AuraBackup
import com.aura.backup.BackupManager
import com.aura.backup.BackupService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import java.io.ByteArrayInputStream

/**
 * Tests the synchronous state-machine portion of [BackupViewModel].
 *
 * We use [UnconfinedTestDispatcher] as the main dispatcher so that
 * any viewModelScope.launch body runs inline up to the first
 * suspension point. Coroutine blocks that internally use
 * [Dispatchers.IO] (the real IO dispatcher) will still escape
 * the test's control, so we keep the tests small and synchronous.
 */
class BackupViewModelTest {

    private val context = mockk<Context>(relaxed = true)
    private val backupManager = mockk<BackupService>(relaxed = true)

    /**
     * The automatic-backup dependencies, stubbed to "nothing configured".
     *
     * These tests are about the manual export/import state machine; the
     * scheduled half has its own suites (`BackupWorkerGateTest`,
     * `BackupCryptoTest`). What matters here is that the init block's preference
     * collection cannot disturb the state these tests assert on.
     */
    private fun viewModel(): BackupViewModel {
        val prefs = mockk<com.aura.data.UserPreferences>(relaxed = true)
        every { prefs.autoBackupEnabled } returns kotlinx.coroutines.flow.flowOf(false)
        every { prefs.backupFolderUri } returns kotlinx.coroutines.flow.flowOf(null)
        every { prefs.lastBackupAt } returns kotlinx.coroutines.flow.flowOf(0L)
        every { prefs.lastBackupError } returns kotlinx.coroutines.flow.flowOf("")
        val secure = mockk<com.aura.security.SecureDataStore>(relaxed = true)
        coEvery { secure.getString(any()) } returns null
        return BackupViewModel(
            context,
            backupManager,
            prefs,
            secure,
            mockk<com.aura.proactive.ProactiveScheduler>(relaxed = true),
        )
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        // The view model now peeks for an interrupted restore in its init
        // block. A relaxed MockK of a final Kotlin class does not reliably
        // return null for a nullable object, so stub it explicitly rather
        // than let a fabricated value decide what the first state looks like.
        every { backupManager.consumeInterruptedRestore() } returns null
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `initial state is idle with no result`() = runTest {
        val vm = viewModel()
        val s = vm.state.value
        assertFalse(s.exportInFlight)
        assertFalse(s.importInFlight)
        assertNull(s.lastResult)
        assertNull(s.pendingImportBytes)
        assertFalse(s.showImportConfirm)
    }

    @Test
    fun `prepareExportFile writes JSON and surfaces result`() = runTest {
        val sampleBackup = AuraBackup(
            exportedAt = 1L, appVersionName = "0.1.0",
            memories = listOf(
                com.aura.backup.MemoryBackup("m1", "x", "user", "preference", "general", 0.5f, 1L, 1L, 0, 1f, "", "{}")
            ),
        )
        coEvery { backupManager.snapshot(any()) } returns sampleBackup
        every { backupManager.encodeToJson(sampleBackup) } returns "{\"memories\":[{}]}"
        every { backupManager.exportFile() } answers {
            java.io.File.createTempFile("aura-backup-test", ".json").apply { deleteOnExit() }
        }

        val vm = viewModel()
        val file = vm.prepareExportFile()

        val state = vm.state.value
        assertFalse(state.exportInFlight)
        assertTrue(state.lastResult!!.contains("Exported"))
        assertEquals("{\"memories\":[{}]}", file!!.readText())
    }

    @Test
    fun `cancelImport clears the confirm dialog`() = runTest {
        val vm = viewModel()
        // cancelImport on a fresh VM is a no-op; the no-op must not
        // throw and the dialog state must stay false.
        vm.cancelImport()
        assertFalse(vm.state.value.showImportConfirm)
        assertNull(vm.state.value.pendingImportBytes)
    }

    @Test
    fun `confirmImport with no staged bytes is a no-op`() = runTest {
        val vm = viewModel()
        vm.confirmImport(replace = false)
        // Restore must not be called when there's nothing staged.
        coVerify(exactly = 0) { backupManager.restore(any()) }
    }

    @Test
    fun `clearResult clears the last result`() = runTest {
        val vm = viewModel()
        vm.clearResult()
        assertNull(vm.state.value.lastResult)
    }

    // ---------------------------------------------------------------- sealed import

    /**
     * Feed [text] through the real [BackupViewModel.stageImport] by standing in for
     * the content resolver, which is the only reason no earlier test drove this path.
     */
    private fun BackupViewModel.stage(text: String) {
        val uri = mockk<Uri>(relaxed = true)
        val resolver = mockk<android.content.ContentResolver>(relaxed = true)
        every { context.contentResolver } returns resolver
        every { resolver.openInputStream(uri) } returns ByteArrayInputStream(text.toByteArray())
        stageImport(uri)
    }

    /**
     * Wait for a state the view model reaches on a real dispatcher.
     *
     * `stageImport` and `submitImportPassphrase` both hop to `Dispatchers.IO`, which
     * `UnconfinedTestDispatcher` does not control — the note at the top of this file
     * is why every earlier test stopped short of this path. Polling the StateFlow
     * with a deadline is honest about that rather than pretending it is synchronous.
     */
    private fun BackupViewModel.awaitState(what: String, predicate: (BackupUiState) -> Boolean): BackupUiState {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            val current = state.value
            if (predicate(current)) return current
            Thread.sleep(5)
        }
        throw AssertionError("never reached $what; last state was ${state.value}")
    }

    @Test
    fun `a sealed backup asks for a passphrase instead of failing to parse`() = runTest {
        // Before this existed, the sealed envelope went straight to decodeFromJson
        // and the user was told "Unexpected JSON token at offset 0".
        every { backupManager.isSealed(any()) } returns true
        val vm = viewModel()

        vm.stage("AURA-BACKUP-1.c2FsdA==.210000.cGF5bG9hZA==")

        val state = vm.awaitState("the passphrase prompt") { it.showPassphrasePrompt }
        assertFalse(state.showImportConfirm, "a sealed file must not reach the confirm dialog unopened")
        assertNull(state.passphraseError)
        assertTrue(state.pendingImportBytes!!.startsWith("AURA-BACKUP-1."), "the sealed bytes were not kept")
    }

    @Test
    fun `the right passphrase opens the backup and hands it to the confirm dialog`() = runTest {
        val decoded = AuraBackup(exportedAt = 7L, appVersionName = "sealed")
        every { backupManager.isSealed(any()) } returns true
        coEvery { backupManager.unseal(any(), "correct horse battery") } returns "{}"
        every { backupManager.decodeFromJson("{}") } returns decoded
        val vm = viewModel()
        vm.stage("AURA-BACKUP-1.c2FsdA==.210000.cGF5bG9hZA==")
        vm.awaitState("the passphrase prompt") { it.showPassphrasePrompt }

        vm.submitImportPassphrase("correct horse battery")

        val state = vm.awaitState("the confirm dialog") { it.showImportConfirm }
        assertFalse(state.showPassphrasePrompt, "the prompt should close once the file opens")
        assertNull(state.passphraseError)
    }

    @Test
    fun `a wrong passphrase reports honestly and keeps the prompt open`() = runTest {
        every { backupManager.isSealed(any()) } returns true
        coEvery { backupManager.unseal(any(), any()) } returns null
        val vm = viewModel()
        vm.stage("AURA-BACKUP-1.c2FsdA==.210000.cGF5bG9hZA==")
        vm.awaitState("the passphrase prompt") { it.showPassphrasePrompt }

        vm.submitImportPassphrase("wrong")

        val state = vm.awaitState("the error") { it.passphraseError != null }
        // BackupCrypto cannot tell a wrong passphrase from a damaged file, so the
        // message must not claim to either.
        assertTrue("wrong passphrase" in state.passphraseError!!, state.passphraseError!!)
        assertTrue("damaged" in state.passphraseError!!, state.passphraseError!!)
        assertTrue(state.showPassphrasePrompt, "closing the prompt would discard the staged bytes")
        assertFalse(state.showImportConfirm)
        assertTrue(state.pendingImportBytes != null, "the staged bytes must survive a typo")
    }

    @Test
    fun `a plaintext export still goes straight to the confirm dialog`() = runTest {
        // The manual export path is unsealed and must be untouched by any of this.
        val decoded = AuraBackup(exportedAt = 1L, appVersionName = "plain")
        every { backupManager.isSealed(any()) } returns false
        every { backupManager.decodeFromJson(any()) } returns decoded
        val vm = viewModel()

        vm.stage("{\"schemaVersion\":1}")

        val state = vm.awaitState("the confirm dialog") { it.showImportConfirm }
        assertFalse(state.showPassphrasePrompt, "a plain export must never be asked for a passphrase")
    }
}
