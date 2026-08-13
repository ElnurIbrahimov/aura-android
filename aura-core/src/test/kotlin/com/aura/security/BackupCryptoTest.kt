package com.aura.security

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A backup has to open on a device that is not this one.
 *
 * Which is the whole reason this class exists rather than reusing
 * [KeyManager.getOrCreateKey]: that key lives in the Android Keystore and dies
 * with the phone, so a Keystore-encrypted backup is unopenable in precisely the
 * situation it was written for. Everything below is really one property —
 * *the file and the passphrase are sufficient* — checked from several sides.
 */
class BackupCryptoTest {

    private val crypto = BackupCrypto()
    private val passphrase = "correct horse battery"

    /** A stand-in for a real export: long, structured, full of awkward characters. */
    private val payload = """
        {"schema":23,"memories":[{"id":"m1","content":"Elnur prefers Lemon Squeezy — Stripe is blocked in Azerbaijan"},
        {"id":"m2","content":"emoji 🧠, quotes \"nested\", backslash \\ and a newline follow"}],
        "beliefs":[{"claim":"the daemon is off by default","confidence":0.91}]}
    """.trimIndent()

    @Test
    fun `a sealed backup round-trips`() {
        val sealed = crypto.seal(payload, passphrase)!!
        assertEquals(payload, crypto.open(sealed, passphrase))
    }

    @Test
    fun `the wrong passphrase fails closed, not open and not loudly`() {
        val sealed = crypto.seal(payload, passphrase)!!

        // Null, not garbage and not an exception. A restore path that receives
        // corrupt-but-non-null plaintext would try to parse it.
        assertNull(crypto.open(sealed, "correct horse batteru"))
        assertNull(crypto.open(sealed, "                    "))
        assertNull(crypto.open(sealed, ""))
    }

    @Test
    fun `the plaintext is not in the file`() {
        val sealed = crypto.seal(payload, passphrase)!!

        assertTrue("Elnur" !in sealed, "the memory content survived into the envelope")
        assertTrue("Lemon Squeezy" !in sealed)
        assertTrue("daemon" !in sealed)
    }

    /**
     * Two seals of identical input must differ. A fresh salt per backup is what
     * stops a weekly series of files from revealing which weeks were identical.
     */
    @Test
    fun `the same input sealed twice produces different files, both openable`() {
        val first = crypto.seal(payload, passphrase)!!
        val second = crypto.seal(payload, passphrase)!!

        assertNotEquals(first, second)
        assertEquals(payload, crypto.open(first, passphrase))
        assertEquals(payload, crypto.open(second, passphrase))
    }

    /**
     * The iteration count is read from the file, not from the constant. Raising
     * [BackupCrypto.ITERATIONS] later must not orphan every backup written
     * before the change — which is a thing that would only be discovered by
     * someone trying to restore, on the worst day to discover it.
     */
    @Test
    fun `an envelope written at a different cost still opens`() {
        val sealed = crypto.seal(payload, passphrase)!!
        val parts = sealed.split(BackupCrypto.SEPARATOR)
        assertEquals(BackupCrypto.ITERATIONS.toString(), parts[2])

        // Re-seal by hand at a lower-but-legal cost, the way an older build would have.
        val older = crypto.javaClass.getDeclaredMethod(
            "deriveKey",
            String::class.java,
            ByteArray::class.java,
            Int::class.javaPrimitiveType,
        ).apply { isAccessible = true }
        val salt = java.util.Base64.getDecoder().decode(parts[1])
        val key = older.invoke(crypto, passphrase, salt, BackupCrypto.MIN_ITERATIONS) as javax.crypto.SecretKey
        val body = KeyManager(keyStore = null).encrypt(payload, key)
        val handmade = listOf(BackupCrypto.HEADER, parts[1], BackupCrypto.MIN_ITERATIONS.toString(), body)
            .joinToString(BackupCrypto.SEPARATOR)

        assertEquals(payload, crypto.open(handmade, passphrase))
    }

    @Test
    fun `a malformed envelope returns null rather than throwing`() {
        val sealed = crypto.seal(payload, passphrase)!!

        assertNull(crypto.open("", passphrase))
        assertNull(crypto.open("not-a-backup", passphrase))
        assertNull(crypto.open(sealed.dropLast(20), passphrase), "a truncated file was accepted")
        assertNull(crypto.open(sealed.replace(BackupCrypto.HEADER, "AURA-BACKUP-9"), passphrase))
        assertNull(crypto.open(sealed.split(BackupCrypto.SEPARATOR).take(3).joinToString("."), passphrase))
    }

    /**
     * A hostile or corrupt envelope must not be able to ask this device to spend
     * an hour deriving a key before it fails.
     */
    @Test
    fun `an absurd iteration count is refused instead of honoured`() {
        val parts = crypto.seal(payload, passphrase)!!.split(BackupCrypto.SEPARATOR)
        val absurd = listOf(parts[0], parts[1], "999999999", parts[3]).joinToString(BackupCrypto.SEPARATOR)

        assertNull(crypto.open(absurd, passphrase))
    }

    @Test
    fun `a passphrase too short to be worth anything is refused at seal time`() {
        assertNull(crypto.seal(payload, "short"), "a 5-character passphrase was accepted")
        assertNull(crypto.seal(payload, ""))
        // The boundary itself is allowed.
        assertTrue(crypto.seal(payload, "a".repeat(BackupCrypto.MIN_PASSPHRASE)) != null)
    }

    @Test
    fun `a sealed file is recognisable as one`() {
        val sealed = crypto.seal(payload, passphrase)!!

        assertTrue(crypto.isSealed(sealed))
        assertTrue(!crypto.isSealed(payload), "a plaintext JSON export was mistaken for a sealed one")
    }
}
