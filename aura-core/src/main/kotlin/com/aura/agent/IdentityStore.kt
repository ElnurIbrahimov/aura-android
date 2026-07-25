package com.aura.agent

import android.content.Context
import android.util.Log
import com.aura.data.UserPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

private const val TAG = "IdentityStore"

/**
 * Canonical identity repository.
 *
 * User customization lives in [UserPreferences.customIdentity], so the live
 * brain, Settings editor, backup export, and backup restore all read the same
 * value. A legacy `filesDir/identity.md` override is migrated once on read.
 */
@Singleton
class IdentityStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val userPreferences: UserPreferences,
) {
    /** Legacy path retained only for one-time migration from older builds. */
    val overrideFile: File
        get() = File(context.filesDir, Brain.IDENTITY_OVERRIDE_FILENAME)

    suspend fun readCurrent(): String {
        val stored = userPreferences.customIdentity.first().trim()
        if (stored.isNotEmpty()) return stored

        val legacy = withContext(Dispatchers.IO) {
            overrideFile.takeIf { it.exists() && it.length() > 0L }
                ?.readText()
                ?.trim()
                .orEmpty()
        }
        if (legacy.isNotEmpty()) {
            userPreferences.setCustomIdentity(legacy)
            withContext(Dispatchers.IO) {
                runCatching { overrideFile.delete() }.onFailure {
                    Log.w(TAG, "Failed to delete legacy identity override file", it)
                }
            }
            return legacy
        }

        return withContext(Dispatchers.IO) { readAsset() }
    }

    /** Bundled persona, with a hardcoded safety fallback. */
    fun readAsset(): String = runCatching {
        context.assets
            .open(Brain.IDENTITY_ASSET_FILENAME)
            .bufferedReader()
            .use { it.readText() }
            .trim()
    }.onFailure {
        Log.w(TAG, "Failed to read bundled identity asset; using fallback", it)
    }.getOrElse { Brain.IDENTITY_FALLBACK.trim() }

    suspend fun save(text: String): Boolean {
        val normalized = text.trim()
        if (normalized.isEmpty()) return resetToDefault()
        return runCatching {
            userPreferences.setCustomIdentity(normalized)
            withContext(Dispatchers.IO) {
                runCatching { overrideFile.delete() }.onFailure {
                    Log.w(TAG, "Failed to delete legacy identity override file after save", it)
                }
            }
        }.isSuccess
    }

    suspend fun resetToDefault(): Boolean = runCatching {
        userPreferences.setCustomIdentity("")
        withContext(Dispatchers.IO) {
            runCatching { overrideFile.delete() }.onFailure {
                Log.w(TAG, "Failed to delete legacy identity override file on reset", it)
            }
        }
    }.isSuccess

    suspend fun hasOverride(): Boolean =
        userPreferences.customIdentity.first().isNotBlank() || withContext(Dispatchers.IO) {
            overrideFile.exists() && overrideFile.length() > 0L
        }
}
