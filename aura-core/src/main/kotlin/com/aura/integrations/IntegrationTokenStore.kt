package com.aura.integrations

import com.aura.security.SecureDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import android.util.Log
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Stores OAuth tokens for external service integrations (Google, Microsoft,
 * ChatGPT).
 *
 * ChatGPT differs from the other two: Aura never runs its authorize flow, so
 * step 1 below happens on the user's desktop under `codex login` and the
 * resulting grant is pasted in. Steps 2 and 3 are identical — which is the
 * point of keeping it here rather than in the API-key store, where it had
 * nowhere to put a refresh token or an expiry.
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

        // ChatGPT subscription. Unlike Google/Microsoft this grant is not
        // minted here — the user runs `codex login` on a desktop and pastes
        // the result. Aura only keeps it alive. See OAuthFlow.refreshChatGptToken.
        private const val KEY_CHATGPT_ACCESS = "chatgpt_access_token"
        private const val KEY_CHATGPT_REFRESH = "chatgpt_refresh_token"
        private const val KEY_CHATGPT_EXPIRES = "chatgpt_expires_at"

        /**
         * Human-readable account label ("elnur@example.com · Plus") lifted
         * from the id_token at paste time. Stored so Settings can show who
         * is signed in without decoding a JWT on every recomposition, and
         * without displaying the token itself.
         */
        private const val KEY_CHATGPT_ACCOUNT = "chatgpt_account_label"

        /** Clock skew margin in seconds — refresh a bit before actual expiry. */
        private const val EXPIRY_MARGIN_SECONDS = 60L
    }

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    // Process-scoped: this @Singleton lives as long as the app does.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Per-provider mutex to prevent concurrent refresh calls (TOCTOU race).
    // Without this, N concurrent callers all see "expired" and all invoke
    // refreshFn, hammering the provider's token endpoint.
    private val googleRefreshMutex = Mutex()
    private val microsoftRefreshMutex = Mutex()
    private val chatgptRefreshMutex = Mutex()

    private val _googleConnected = MutableStateFlow(false)
    val googleConnected: StateFlow<Boolean> = _googleConnected.asStateFlow()

    private val _microsoftConnected = MutableStateFlow(false)
    val microsoftConnected: StateFlow<Boolean> = _microsoftConnected.asStateFlow()

    private val _chatgptConnected = MutableStateFlow(false)
    val chatgptConnected: StateFlow<Boolean> = _chatgptConnected.asStateFlow()

    /** Who is signed in, for Settings to show instead of a token. */
    private val _chatgptAccount = MutableStateFlow<String?>(null)
    val chatgptAccount: StateFlow<String?> = _chatgptAccount.asStateFlow()

    /**
     * Set when the stored grant can no longer be renewed — no refresh token
     * was ever captured, or the token endpoint rejected the one we have.
     *
     * Deliberately separate from [chatgptConnected]: credentials still exist,
     * they just don't work. Clearing `connected` here would make
     * `ChatGptSubscriptionProvider.isConfigured()` false, the model catalog
     * would short-circuit before any network call, and the user would be told
     * "not configured" for a provider they very much did configure.
     */
    private val _chatgptSessionExpired = MutableStateFlow(false)
    val chatgptSessionExpired: StateFlow<Boolean> = _chatgptSessionExpired.asStateFlow()

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
     * Store a ChatGPT subscription grant.
     *
     * [accountLabel] is lifted from the id_token at paste time so Settings can
     * name the account without decoding a JWT on every recomposition — and
     * without ever putting the token on screen.
     */
    suspend fun storeChatGptTokens(
        accessToken: String,
        refreshToken: String,
        expiresInSeconds: Long,
        accountLabel: String? = null,
    ) {
        secureDataStore.putString(KEY_CHATGPT_ACCESS, accessToken)
        secureDataStore.putString(KEY_CHATGPT_EXPIRES, (System.currentTimeMillis() / 1000 + expiresInSeconds).toString())
        if (refreshToken.isNotBlank()) {
            secureDataStore.putString(KEY_CHATGPT_REFRESH, refreshToken)
        }
        if (accountLabel != null) {
            secureDataStore.putString(KEY_CHATGPT_ACCOUNT, accountLabel)
        }
        _chatgptAccount.value = accountLabel ?: _chatgptAccount.value
        _chatgptConnected.value = true
        // A paste with no refresh token is expired-on-arrival as far as
        // renewal goes; say so now rather than an hour from now.
        _chatgptSessionExpired.value = refreshToken.isBlank() &&
            secureDataStore.getString(KEY_CHATGPT_REFRESH).isNullOrBlank()
    }

    /**
     * Get a valid ChatGPT access token, refreshing through [refreshFn] if the
     * stored one has expired.
     *
     * This is the whole point of the OAuth store: before it, the access token
     * was kept in the plain API-key slot with no expiry and no refresh token,
     * so it simply went stale an hour after every sign-in.
     */
    suspend fun getValidChatGptAccessToken(refreshFn: suspend (String) -> TokenRefreshResult?): String? {
        val token = getValidToken(
            KEY_CHATGPT_ACCESS, KEY_CHATGPT_REFRESH, KEY_CHATGPT_EXPIRES, refreshFn, chatgptRefreshMutex,
        )
        _chatgptSessionExpired.value = token == null && secureDataStore.getString(KEY_CHATGPT_ACCESS) != null
        return token
    }

    /**
     * Adopt a bare access token previously pasted into the `chatgpt_api_key`
     * slot, so an upgrading user is not silently signed out.
     *
     * Stored with no refresh token and an expiry taken from the token's own
     * `exp`, which for anything pasted more than an hour ago is already in the
     * past. It will fail once and report "session expired", which is the truth:
     * a bare access token cannot be renewed and never could.
     */
    suspend fun migrateLegacyChatGptToken(rawToken: String): Boolean {
        if (secureDataStore.getString(KEY_CHATGPT_ACCESS) != null) return false
        val parsed = ChatGptAuthImport.parse(rawToken) ?: return false
        storeChatGptTokens(
            accessToken = parsed.accessToken,
            refreshToken = parsed.refreshToken,
            expiresInSeconds = parsed.expiresInSeconds,
            accountLabel = parsed.accountLabel,
        )
        Log.i(TAG, "migrated legacy chatgpt key into the OAuth store")
        return true
    }

    suspend fun disconnectChatGpt() {
        secureDataStore.removeString(KEY_CHATGPT_ACCESS)
        secureDataStore.removeString(KEY_CHATGPT_REFRESH)
        secureDataStore.removeString(KEY_CHATGPT_EXPIRES)
        secureDataStore.removeString(KEY_CHATGPT_ACCOUNT)
        _chatgptConnected.value = false
        _chatgptAccount.value = null
        _chatgptSessionExpired.value = false
    }

    /**
     * Get a valid Google access token, refreshing if necessary.
     * Returns null if not connected or refresh failed.
     */
    suspend fun getValidGoogleAccessToken(refreshFn: suspend (String) -> TokenRefreshResult?): String? =
        getValidToken(KEY_GOOGLE_ACCESS, KEY_GOOGLE_REFRESH, KEY_GOOGLE_EXPIRES, refreshFn, googleRefreshMutex)

    /**
     * Get a valid Microsoft access token, refreshing if necessary.
     */
    suspend fun getValidMicrosoftAccessToken(refreshFn: suspend (String) -> TokenRefreshResult?): String? =
        getValidToken(KEY_MICROSOFT_ACCESS, KEY_MICROSOFT_REFRESH, KEY_MICROSOFT_EXPIRES, refreshFn, microsoftRefreshMutex)

    private suspend fun getValidToken(
        accessKey: String,
        refreshKey: String,
        expiresKey: String,
        refreshFn: suspend (String) -> TokenRefreshResult?,
        refreshMutex: Mutex,
    ): String? = withContext(Dispatchers.IO) {
        val accessToken = secureDataStore.getString(accessKey) ?: return@withContext null
        val expiresAt = secureDataStore.getString(expiresKey)?.toLongOrNull() ?: 0L
        val now = System.currentTimeMillis() / 1000

        if (now < expiresAt - EXPIRY_MARGIN_SECONDS) {
            return@withContext accessToken
        }

        // Token expired — try to refresh under mutex to prevent
        // concurrent refresh calls hammering the provider endpoint.
        refreshMutex.withLock {
            // Re-check after acquiring the lock — another coroutine
            // may have already refreshed the token.
            val refreshedToken = secureDataStore.getString(accessKey)
            val refreshedExpiry = secureDataStore.getString(expiresKey)?.toLongOrNull() ?: 0L
            if (refreshedToken != null && now < refreshedExpiry - EXPIRY_MARGIN_SECONDS) {
                return@withLock refreshedToken
            }

            val refreshToken = secureDataStore.getString(refreshKey) ?: return@withLock null
            val refreshResult = runCatching { refreshFn(refreshToken) }
                .onFailure { Log.w(TAG, "token refresh failed: ${it.message}", it) }
                .getOrNull() ?: return@withLock null

            if (refreshResult == null) return@withLock null

            secureDataStore.putString(accessKey, refreshResult.accessToken)
            secureDataStore.putString(expiresKey, (now + refreshResult.expiresInSeconds).toString())
            if (refreshResult.refreshToken.isNotBlank()) {
                secureDataStore.putString(refreshKey, refreshResult.refreshToken)
            }
            refreshResult.accessToken
        }
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
        _chatgptConnected.value = secureDataStore.getString(KEY_CHATGPT_ACCESS) != null
        _chatgptAccount.value = secureDataStore.getString(KEY_CHATGPT_ACCOUNT)
        _chatgptSessionExpired.value = _chatgptConnected.value &&
            secureDataStore.getString(KEY_CHATGPT_REFRESH).isNullOrBlank()
    }

    /**
     * Populate the connection flows at construction rather than waiting for
     * someone to call [checkConnectionState].
     *
     * The only existing caller is [com.aura.proactive.ProactiveBootstrap],
     * which takes this store as an *optional* dependency — so on any path
     * where it isn't wired, every flow stayed false for the whole process and
     * stored credentials looked absent. That was survivable while these flows
     * only tinted a Settings row; it is not survivable now that
     * `chatgptConnected` decides whether the provider is offered at all.
     *
     * Same shape and same accepted race as [com.aura.providers.ProviderKeys]:
     * reads report "not connected" for the few ms before DataStore answers.
     * Loading synchronously here would put a decrypt on the main thread during
     * Hilt graph construction, which is an ANR waiting to happen.
     */
    init {
        scope.launch {
            // A decryption failure here must not take down app startup, but it
            // must not vanish either: every integration would silently read as
            // disconnected and the cause would be invisible.
            runCatching { checkConnectionState() }
                .onFailure { Log.w(TAG, "could not read stored integration tokens: ${it.message}", it) }
        }
    }

    data class TokenRefreshResult(
        val accessToken: String,
        val refreshToken: String,
        val expiresInSeconds: Long,
    )
}