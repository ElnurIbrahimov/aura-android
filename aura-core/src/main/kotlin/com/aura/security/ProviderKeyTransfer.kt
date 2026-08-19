package com.aura.security

import android.util.Log
import com.aura.providers.ProviderKeys
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Move provider API keys between installs.
 *
 * Keys live in [SecureDataStore], encrypted under a Keystore key bound to the package.
 * Uninstall and that Keystore key is destroyed, taking every key with it — the ciphertext
 * surviving in a device backup is undecryptable by anything, permanently. So a restored
 * Aura comes back with every memory and conversation intact and not one provider
 * configured, and the user finds out when the next message fails.
 *
 * The answer is deliberately not "put keys in [com.aura.backup.AuraBackup]". That file is
 * written on a schedule, unattended, into a folder chosen once and often synced to a cloud
 * the user is not thinking about at the time. Keys belong in a file that only exists
 * because the user asked for it in that moment, sealed under a passphrase they typed.
 *
 * [BackupCrypto] is the right envelope precisely because it never consults the Keystore:
 * PBKDF2 over the typed passphrase and nothing else, so the passphrase is sufficient to
 * open the file on an install that shares nothing with the one that wrote it. That is the
 * whole feature.
 */
@Singleton
class ProviderKeyTransfer @Inject constructor(
    private val providerKeys: ProviderKeys,
) {

    // Constructed rather than injected, matching BackupManager and BackupWorker, which
    // both do the same. It is stateless and its only constructor parameter is a KeyManager
    // it builds with a null keyStore — there is nothing for the graph to decide.
    private val crypto = BackupCrypto()

    /** What an [import] did, for the UI to report without inspecting the file itself. */
    sealed interface Result {
        /** @param embeddingModel whether the file also carried an embedding model name. */
        data class Restored(val keys: Int, val embeddingModel: Boolean) : Result

        /**
         * The envelope did not open.
         *
         * [BackupCrypto.open] returns null for a wrong passphrase and for a file that is
         * not an Aura envelope alike, and cannot tell them apart by design — nothing
         * stores a hash of the passphrase. So this does not either, and the UI says both.
         */
        data object Unreadable : Result

        /** It opened, but held nothing this build could use. */
        data object Empty : Result
    }

    /**
     * Seal every configured key under [passphrase].
     *
     * @return the envelope, or null when [passphrase] is shorter than
     *   [BackupCrypto.MIN_PASSPHRASE] — writing a plaintext file instead would be the
     *   worst available outcome for this particular feature.
     */
    suspend fun export(passphrase: String, now: Long): String? {
        val keys = ProviderKeys.PREFIXES
            .mapNotNull { prefix ->
                providerKeys.keyForAwaiting(prefix)
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { prefix to it }
            }
            .toMap()
        val vault = Vault(
            exportedAt = now,
            keys = keys,
            embeddingModel = providerKeys.embeddingModel.takeIf { it.isNotBlank() },
        )
        return crypto.seal(JSON.encodeToString(Vault.serializer(), vault), passphrase)
    }

    /**
     * Open [envelope] and write what it holds back through [ProviderKeys.set], the same
     * path the settings screen uses — so the in-memory cache, the credential state each
     * provider row renders, and the encrypted store all move together.
     */
    suspend fun import(envelope: String, passphrase: String): Result {
        val plaintext = crypto.open(envelope, passphrase) ?: return Result.Unreadable
        val vault = runCatching { JSON.decodeFromString(Vault.serializer(), plaintext) }
            .getOrElse {
                Log.w(TAG, "key file opened but did not parse: ${it.message}", it)
                return Result.Unreadable
            }

        // A file written by a later version can name a provider that does not exist in
        // this build. ProviderKeys.set does not validate the prefix, so an unfiltered
        // import would write a key nothing can ever read, under a name nothing looks up.
        val known = vault.keys.filterKeys { it in ProviderKeys.PREFIXES }
        val skipped = vault.keys.size - known.size
        if (skipped > 0) Log.w(TAG, "$skipped key(s) named a provider this build does not have")

        for ((prefix, key) in known) providerKeys.set(prefix, key)
        vault.embeddingModel?.takeIf { it.isNotBlank() }?.let { providerKeys.setEmbeddingModel(it) }

        return if (known.isEmpty() && vault.embeddingModel == null) {
            Result.Empty
        } else {
            Result.Restored(keys = known.size, embeddingModel = vault.embeddingModel != null)
        }
    }

    /**
     * The plaintext shape inside the envelope.
     *
     * Never written to disk unsealed and never logged. [version] is present so a future
     * change has something to branch on; unknown fields are ignored rather than fatal, so
     * a file from a later version still yields the keys this build understands.
     */
    @Serializable
    private data class Vault(
        val version: Int = VERSION,
        val exportedAt: Long,
        val keys: Map<String, String> = emptyMap(),
        val embeddingModel: String? = null,
    )

    private companion object {
        const val TAG = "ProviderKeyTransfer"
        const val VERSION = 1
        val JSON = Json { ignoreUnknownKeys = true }
    }
}
