package com.aura.security

import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Encryption for a backup that has to outlive the device it came from.
 *
 * [KeyManager.getOrCreateKey] returns a key held in the Android Keystore, which
 * is the right answer for everything else in this app and the wrong answer here:
 * that key is destroyed with the phone, and a phone that no longer exists is the
 * exact situation a backup is for. A Keystore-encrypted backup is a file nobody
 * can ever open.
 *
 * So the key is derived from a passphrase instead. The salt travels in the file,
 * so any device that knows the passphrase can open it, and the iteration count
 * travels with it too — raising [ITERATIONS] later must not orphan the backups
 * written before the change.
 *
 * The AES-GCM half is [KeyManager]'s, unchanged. Only the key source is new.
 * That is deliberate: this file has no cryptography of its own to get wrong.
 *
 * **A forgotten passphrase is an unrecoverable backup.** There is no recovery
 * path and there is not supposed to be one — a backup that can be opened without
 * the passphrase is a plaintext backup with extra steps. The setup UI states
 * this once, plainly, at the point of setting it.
 */
class BackupCrypto(
    // Bring-your-own-key: only encrypt/decrypt are used, and both take the key as
    // a parameter. The null keyStore here is not "test mode" — it means this class
    // never asks KeyManager for a key, because the Keystore is the one place this
    // key must not come from.
    private val keyManager: KeyManager = KeyManager(keyStore = null),
) {

    /**
     * Encrypt [plaintext] under [passphrase], with a fresh random salt.
     *
     * @return a self-describing envelope, or null if the passphrase is too short.
     */
    fun seal(plaintext: String, passphrase: String): String? {
        if (passphrase.length < MIN_PASSPHRASE) return null
        val salt = ByteArray(SALT_BYTES).also { SecureRandom().nextBytes(it) }
        val key = deriveKey(passphrase, salt, ITERATIONS)
        val payload = keyManager.encrypt(plaintext, key)
        return listOf(
            HEADER,
            Base64.getEncoder().encodeToString(salt),
            ITERATIONS.toString(),
            payload,
        ).joinToString(SEPARATOR)
    }

    /**
     * Decrypt an envelope produced by [seal].
     *
     * Returns null for a wrong passphrase, a truncated file, or an envelope this
     * build does not understand — all of which are the same thing to a caller,
     * and none of which should throw. A wrong passphrase derives a different key,
     * so GCM's tag check fails and [KeyManager.decrypt] already returns null:
     * this fails closed without needing to check the passphrase against anything,
     * which is why nothing here stores a hash of it.
     */
    fun open(envelope: String, passphrase: String): String? {
        val parts = envelope.split(SEPARATOR)
        if (parts.size != 4 || parts[0] != HEADER) return null
        val salt = runCatching { Base64.getDecoder().decode(parts[1]) }.getOrNull() ?: return null
        // Read the iteration count from the file rather than using ITERATIONS.
        // A backup written before a cost bump still has to open afterwards.
        val iterations = parts[2].toIntOrNull()?.takeIf { it in MIN_ITERATIONS..MAX_ITERATIONS } ?: return null
        val key = deriveKey(passphrase, salt, iterations)
        return runCatching { keyManager.decrypt(parts[3], key) }.getOrNull()
    }

    /** True when [envelope] looks like something [open] could accept. */
    fun isSealed(envelope: String): Boolean = envelope.startsWith("$HEADER$SEPARATOR")

    private fun deriveKey(passphrase: String, salt: ByteArray, iterations: Int): SecretKey {
        val spec = PBEKeySpec(passphrase.toCharArray(), salt, iterations, KEY_BITS)
        val bytes = SecretKeyFactory.getInstance(KDF).generateSecret(spec).encoded
        return SecretKeySpec(bytes, "AES")
    }

    companion object {
        /** Version marker. A future format change gets a new one, not a silent reinterpretation. */
        const val HEADER = "AURA-BACKUP-1"

        const val SEPARATOR = "."

        /**
         * Short passphrases are the weakness PBKDF2 cannot fix — the whole
         * security of the file reduces to guessing this string.
         */
        const val MIN_PASSPHRASE = 8

        const val SALT_BYTES = 16
        const val KEY_BITS = 256

        /** OWASP's 2023 floor for PBKDF2-HMAC-SHA256. A few hundred ms once a week. */
        const val ITERATIONS = 210_000

        // Bounds on what a file may claim, so a malformed or hostile envelope
        // cannot ask this device to spend an hour deriving a key.
        const val MIN_ITERATIONS = 100_000
        const val MAX_ITERATIONS = 2_000_000

        /** API 26+; minSdk here is 26. */
        const val KDF = "PBKDF2WithHmacSHA256"
    }
}
