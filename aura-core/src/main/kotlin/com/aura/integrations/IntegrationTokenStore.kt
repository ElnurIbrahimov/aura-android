package com.aura.integrations

import com.aura.security.SecureDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import android.util.Log
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Stores OAuth tokens for external service integrations (Google, Microsoft).
 *
 * Access and refresh tokens are encrypted at rest via [SecureDataStore].
 * A [StateFlow] exposes the connection state so UI can reactively
 * show connected/disconnected status without reading SecureDataStore
 * on every recomposition.
 *
 * Token lifecycle:
 * 1. [OAuthFlow] launches a browser tab, user grants consent, redirect
 *    back to the app carries the authorization code.
 * 2. The code is exchanged for access + refresh tokens via the token
 *    endpoint. Tokens are stored here.
 * 3. API calls use [getValidAccessToken] which auto-refreshes if the
 *    access token is expired.
 */
@Singleton
class IntegrationTokenStore @Inject constructor(
    private val secureDataStore: SecureDataStore,
) {
    companion object {
        private const val TAG = "IntegrationTokens"
        private const val KEY_GOOGLE_ACCESS = "google_access_token"
        private const val KEY_GOOGLE_REFRESH = "google_refresh_token"
        private const val KEY_GOOGLE_EXPIRES = "google_expires_at"
        private const val KEY_MICROSOFT_ACCESS = "microsoft_access_token"
        private const val KEY_MICROSOFT_REFRESH = "microsoft_refresh_token"
        private const val KEY_MICROSOFT_EXPIRES = "microsoft_expires_at"

        /** Clock skew margin in seconds — refresh a bit before actual expiry. */
        private const val EXPIRY_MARGIN_SECONDS = 60L
    }

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val _googleConnected = MutableStateFlow(false)
    val googleConnected: StateFlow<Boolean> = _googleConnected.asStateFlow()

    private val _microsoftConnected = MutableStateFlow(false)
    val microsoftConnected: StateFlow<Boolean> = _microsoftConnected.asStateFlow()

    /**
     * Store tokens after a successful OAuth exchange.
     * Called by [OAuthFlow] after the token endpoint responds.
     */
    suspend fun storeGoogleTokens(accessToken: String, refreshToken: String, expiresInSeconds: Long) {
        secureDataStore.putString(KEY_GOOGLE_ACCESS, accessToken)
        secureDataStore.putString(KEY_GOOGLE_REFRESH, refreshToken)
        secureDataStore.putString(KEY_GOOGLE_EXPIRES, (System.currentTimeMillis() / 1000 + expiresInSeconds).toString())
        _googleConnected.value = true
    }

    suspend fun storeMicrosoftTokens(accessToken: String, refreshToken: String, expiresInSeconds: Long) {
        secureDataStore.putString(KEY_MICROSOFT_ACCESS, accessToken)
        secureDataStore.putString(KEY_MICROSOFT_REFRESH, refreshToken)
        secureDataStore.putString(KEY_MICROSOFT_EXPIRES, (System.currentTimeMillis() / 1000 + expiresInSeconds).toString())
        _microsoftConnected.value = true
    }

    /**
     * Get a valid Google access token, refreshing if necessary.
     * Returns null if not connected or refresh failed.
     */
    suspend fun getValidGoogleAccessToken(refreshFn: suspend (String) -> TokenRefreshResult?): String? =
        getValidToken(KEY_GOOGLE_ACCESS, KEY_GOOGLE_REFRESH, KEY_GOOGLE_EXPIRES, refreshFn)

    /**
     * Get a valid Microsoft access token, refreshing if necessary.
     */
    suspend fun getValidMicrosoftAccessToken(refreshFn: suspend (String) -> TokenRefreshResult?): String? =
        getValidToken(KEY_MICROSOFT_ACCESS, KEY_MICROSOFT_REFRESH, KEY_MICROSOFT_EXPIRES, refreshFn)

    private suspend fun getValidToken(
        accessKey: String,
        refreshKey: String,
        expiresKey: String,
        refreshFn: suspend (String) -> TokenRefreshResult?,
    ): String? = withContext(Dispatchers.IO) {
        val accessToken = secureDataStore.getString(accessKey) ?: return@withContext null
        val expiresAt = secureDataStore.getString(expiresKey)?.toLongOrNull() ?: 0L
        val now = System.currentTimeMillis() / 1000

        if (now < expiresAt - EXPIRY_MARGIN_SECONDS) {
            return@withContext accessToken
        }

        // Token expired — try to refresh
        val refreshToken = secureDataStore.getString(refreshKey) ?: return@withContext null
        val refreshResult = runCatching { refreshFn(refreshToken) }
            .onFailure { Log.w(TAG, "token refresh failed: ${it.message}") }
            .getOrNull() ?: return@withContext null

        if (refreshResult == null) return@withContext null

        secureDataStore.putString(accessKey, refreshResult.accessToken)
        secureDataStore.putString(expiresKey, (now + refreshResult.expiresInSeconds).toString())
        if (refreshResult.refreshToken.isNotBlank()) {
            secureDataStore.putString(refreshKey, refreshResult.refreshToken)
        }
        refreshResult.accessToken
    }

    suspend fun disconnectGoogle() {
        secureDataStore.removeString(KEY_GOOGLE_ACCESS)
        secureDataStore.removeString(KEY_GOOGLE_REFRESH)
        secureDataStore.removeString(KEY_GOOGLE_EXPIRES)
        _googleConnected.value = false
    }

    suspend fun disconnectMicrosoft() {
        secureDataStore.removeString(KEY_MICROSOFT_ACCESS)
        secureDataStore.removeString(KEY_MICROSOFT_REFRESH)
        secureDataStore.removeString(KEY_MICROSOFT_EXPIRES)
        _microsoftConnected.value = false
    }

    /**
     * Check if tokens exist (without validating). Called on startup
     * to populate the [StateFlow]s.
     */
    suspend fun checkConnectionState() {
        _googleConnected.value = secureDataStore.getString(KEY_GOOGLE_ACCESS) != null
        _microsoftConnected.value = secureDataStore.getString(KEY_MICROSOFT_ACCESS) != null
    }

    data class TokenRefreshResult(
        val accessToken: String,
        val refreshToken: String,
        val expiresInSeconds: Long,
    )
}