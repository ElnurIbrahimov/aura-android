package com.aura.testing

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The dialog's minimum and the crypto's minimum have to be the same number.
 *
 * `BackupCrypto.seal` returns null for a passphrase shorter than its floor, and
 * a null seal inside `BackupWorker` becomes a recorded failure. If the UI let a
 * shorter one through, the setup would look complete, the switch would be on,
 * and every weekly run would fail — the failure showing up only in a Settings
 * row nobody has a reason to re-read. The two constants live in different
 * modules and cannot import each other, so this is what keeps them equal.
 *
 * Source-scanning because the UI constant is `private` to a Compose file with no
 * runtime seam, and the invariant is about a literal rather than a behaviour.
 */
class BackupPassphraseUiContractTest {

    private fun constantIn(source: String, name: String): Int =
        Regex("""const val $name = (\d+)""").find(source)
            ?.groupValues?.get(1)?.toIntOrNull()
            ?: error("could not find `const val $name` — this test is reading the wrong shape")

    @Test
    fun `the dialog refuses exactly what the crypto refuses`() {
        val ui = sourceDir("src/main/kotlin/com/aura")
            .resolve("ui/settings/sections/DataAndBackupSection.kt")
            .also { check(it.isFile) { "DataAndBackupSection.kt not found at ${it.absolutePath}" } }
            .readText()

        val crypto = sourceDir("../aura-core/src/main/kotlin/com/aura")
            .resolve("security/BackupCrypto.kt")
            .also { check(it.isFile) { "BackupCrypto.kt not found at ${it.absolutePath}" } }
            .readText()

        assertEquals(
            constantIn(crypto, "MIN_PASSPHRASE"),
            constantIn(ui, "MIN_PASSPHRASE"),
            "the passphrase dialog and BackupCrypto disagree on the minimum length, so the UI can " +
                "accept a passphrase that makes every scheduled backup fail",
        )
    }

    /**
     * The warning is the only thing standing between a person and an
     * unopenable archive, and it can only be acted on at the moment of setting.
     */
    @Test
    fun `the dialog says the passphrase cannot be recovered`() {
        val ui = sourceDir("src/main/kotlin/com/aura")
            .resolve("ui/settings/sections/DataAndBackupSection.kt")
            .readText()

        assertTrue(
            "cannot be recovered" in ui,
            "the passphrase dialog no longer warns that a forgotten passphrase is unrecoverable",
        )
    }
}
