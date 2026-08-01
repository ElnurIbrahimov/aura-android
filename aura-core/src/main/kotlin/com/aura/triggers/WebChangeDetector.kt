package com.aura.triggers

import com.aura.core.url.SsrfGuard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.security.MessageDigest
import android.util.Log

/** Fetches a URL (with SSRF guard) and returns a content hash. */
class WebChangeDetector @javax.inject.Inject constructor(
    private val client: OkHttpClient

) {
    /** @return sha256 hex of body, or null if fetch failed / unsafe URL. */
    suspend fun hash(url: String): String? = withContext(Dispatchers.IO) {
        val validation = SsrfGuard.inspect(url)
        if (validation !is com.aura.core.url.SsrfValidation.Safe) return@withContext null
        val safeUrl = url
        runCatching {
            val request = Request.Builder()
                .url(safeUrl)
                .header("User-Agent", "Aura/1.0")
                .build()
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) return@withContext null
            val bytes = response.body?.bytes() ?: return@withContext null
            sha256(bytes)
        }.onFailure { Log.w("WebChange", "op failed: ${it.message}") }.getOrNull()
    }

    private fun sha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }
}
