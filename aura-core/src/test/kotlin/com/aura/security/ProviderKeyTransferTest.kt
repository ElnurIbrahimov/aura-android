package com.aura.security

import com.aura.providers.ProviderKeys
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * API keys are the one thing a backup has never carried.
 *
 * They live in `SecureDataStore`, which is encrypted under a Keystore key bound to the
 * package. Uninstall the app and that key is destroyed — the ciphertext left in a device
 * backup is undecryptable by anything, forever. A restored Aura therefore comes back with
 * every conversation and memory intact and not one provider configured, which has already
 * cost a real key twice.
 *
 * The fix is not to put keys in the backup: the backup is written automatically, to a
 * folder the user picked once, on a schedule. Keys go in a separate file the user asks
 * for, sealed under a passphrase they type, using the same PBKDF2 envelope as the backup
 * — the point being that the passphrase is the only thing needed to open it, so nothing
 * about the envelope depends on the install that wrote it.
 */
class ProviderKeyTransferTest {

    private val providerKeys = mockk<ProviderKeys>(relaxed = true)
    private val transfer = ProviderKeyTransfer(providerKeys)

    @Test
    fun `a key exported on one install is readable on the next`() = runTest {
        // The whole point: seal on one install, open on another, with nothing shared
        // between them but the passphrase the user typed.
        coEvery { providerKeys.keyForAwaiting("anthropic") } returns "sk-ant-secret"
        coEvery { providerKeys.keyForAwaiting("deepseek") } returns "sk-deep-secret"
        every { providerKeys.embeddingModel } returns "nomic-embed-text"

        val sealed = transfer.export("a good long passphrase", now = 1L)!!

        val fresh = ProviderKeyTransfer(mockk(relaxed = true))
        val result = fresh.import(sealed, "a good long passphrase")

        assertEquals(ProviderKeyTransfer.Result.Restored(keys = 2, embeddingModel = true), result)
    }

    @Test
    fun `importing writes each key back through the same path the settings screen uses`() = runTest {
        coEvery { providerKeys.keyForAwaiting("anthropic") } returns "sk-ant-secret"
        every { providerKeys.embeddingModel } returns ""
        val sealed = ProviderKeyTransfer(providerKeys)
            .export("a good long passphrase", now = 1L)!!

        val target = mockk<ProviderKeys>(relaxed = true)
        ProviderKeyTransfer(target).import(sealed, "a good long passphrase")

        coVerify { target.set("anthropic", "sk-ant-secret") }
    }

    @Test
    fun `a wrong passphrase restores nothing rather than restoring partially`() = runTest {
        coEvery { providerKeys.keyForAwaiting("anthropic") } returns "sk-ant-secret"
        every { providerKeys.embeddingModel } returns ""
        val sealed = transfer.export("a good long passphrase", now = 1L)!!

        val target = mockk<ProviderKeys>(relaxed = true)
        val result = ProviderKeyTransfer(target).import(sealed, "the wrong passphrase")

        assertEquals(ProviderKeyTransfer.Result.Unreadable, result)
        coVerify(exactly = 0) { target.set(any(), any()) }
    }

    @Test
    fun `a passphrase too short to seal under exports nothing`() = runTest {
        // BackupCrypto refuses below MIN_PASSPHRASE by returning null. Returning a
        // plaintext file instead would be the worst possible failure for this feature.
        every { providerKeys.embeddingModel } returns ""

        assertNull(transfer.export("short", now = 1L))
    }

    @Test
    fun `a provider this build does not know is skipped, not written`() = runTest {
        // A file written by a later version naming a provider that does not exist here.
        // ProviderKeys.set does not validate the prefix, so an unfiltered import would
        // write a key nothing can ever read under a name nothing will ever look up.
        val envelope = BackupCrypto().seal(
            """{"exportedAt":1,"keys":{"anthropic":"sk-a","notaprovider":"sk-x"}}""",
            "a good long passphrase",
        )!!

        val target = mockk<ProviderKeys>(relaxed = true)
        val result = ProviderKeyTransfer(target).import(envelope, "a good long passphrase")

        assertEquals(ProviderKeyTransfer.Result.Restored(keys = 1, embeddingModel = false), result)
        coVerify(exactly = 0) { target.set("notaprovider", any()) }
    }

    @Test
    fun `a blank key is not exported at all`() = runTest {
        // Exporting empty entries would make the file look fuller than it is, and
        // importing them would clear keys the target install already had.
        coEvery { providerKeys.keyForAwaiting(any()) } returns null
        coEvery { providerKeys.keyForAwaiting("openai") } returns "   "
        every { providerKeys.embeddingModel } returns ""

        val sealed = transfer.export("a good long passphrase", now = 1L)!!
        val opened = BackupCrypto().open(sealed, "a good long passphrase")!!

        assertTrue("openai" !in opened, "a whitespace-only key must not reach the file")
    }

    @Test
    fun `the file is not a plaintext file`() = runTest {
        // The one assertion worth making twice.
        coEvery { providerKeys.keyForAwaiting("anthropic") } returns "sk-ant-secret"
        every { providerKeys.embeddingModel } returns ""

        val sealed = transfer.export("a good long passphrase", now = 1L)!!

        assertTrue("sk-ant-secret" !in sealed, "the key must not appear in the file it was sealed into")
    }
}
