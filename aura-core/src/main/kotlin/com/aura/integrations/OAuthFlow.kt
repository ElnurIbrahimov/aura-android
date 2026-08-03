package com.aura.integrations

import android.content.Context
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import com.aura.security.SecureDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import android.util.Log
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton

/**
 * OAuth 2.0 authorization code flow for Google and Microsoft.
 *
 * Launches a Custom Tabs browser tab for the user to consent,
 * then exchanges the redirect code for access + refresh tokens
 * via the token endpoint.
 *
 * Google scopes: Gmail modify, Calendar read/write, Drive read/write.
 * Microsoft scopes: Mail.ReadWrite, Mail.Send, Calendars.ReadWrite,
 * Files.ReadWrite.All.
 *
 * The redirect URI is a deep link back to the app:
 * aura://oauth/google and aura://oauth/microsoft
 */
@Singleton
class OAuthFlow @Inject constructor(
    @ApplicationContext private val context: Context,
    private val httpClient: OkHttpClient,
    private val tokenStore: IntegrationTokenStore,
) {
    companion object {
        private const val TAG = "OAuthFlow"

        // Google OAuth endpoints
        private const val GOOGLE_AUTH_URL = "https://accounts.google.com/o/oauth2/v2/auth"
        private const val GOOGLE_TOKEN_URL = "https://oauth2.googleapis.com/token"
        private const val GOOGLE_REDIRECT = "aura://oauth/google"
        private const val GOOGLE_SCOPE = "https://mail.google.com/ " +
            "https://www.googleapis.com/auth/calendar " +
            "https://www.googleapis.com/auth/drive.file"
        // Client ID is set by the user in Settings — stored in UserPreferences.
        // For a personal-use app, using a public client ID with the loopback
        // or custom scheme redirect is the standard approach.

        // Microsoft OAuth endpoints (v2.0 consumer endpoint)
        private const val MS_AUTH_URL = "https://login.microsoftonline.com/common/oauth2/v2.0/authorize"
        private const val MS_TOKEN_URL = "https://login.microsoftonline.com/common/oauth2/v2.0/token"
        private const val MS_REDIRECT = "aura://oauth/microsoft"
        private const val MS_SCOPE = "Mail.ReadWrite Mail.Send Calendars.ReadWrite Files.ReadWrite.All offline_access"
    }

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val secureRandom = SecureRandom()

    /**
     * CSRF protection: a random state token generated per launch and
     * validated when the redirect comes back. Without it, any party
     * that can deliver a crafted redirect URI (malicious app, stray
     * browser tab, link injection) can bind the user's account to an
     * attacker-chosen OAuth session. The state proves the code belongs
     * to the launch we initiated.
     */
    @Volatile
    private var pendingState: String? = null

    /**
     * PKCE S256 verifier for this launch. A custom-scheme redirect is
     * not a private channel, so the code exchange MUST be bound to the
     * original authorization request. The verifier is random per launch
     * and never leaves the device; only its SHA-256 challenge goes to
     * the authorization server.
     */
    @Volatile
    private var pendingVerifier: String? = null

    private fun newState(): String {
        val bytes = ByteArray(32)
        secureRandom.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private fun newCodeVerifier(): String {
        // 32 random bytes -> 43-char base64url string: within the
        // RFC 7636 allowed charset (A-Z a-z 0-9 - . _) and length range.
        val bytes = ByteArray(32)
        secureRandom.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private fun pkceChallenge(verifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
    }

    /**
     * Launch the Google OAuth consent flow in a browser tab.
     * After consent, the browser redirects to [GOOGLE_REDIRECT],
     * which is caught by [handleRedirect].
     */
    fun launchGoogleAuth(clientId: String) {
        launchBrowser(buildGoogleAuthUrl(clientId))
    }

    /**
     * Build the Google authorization URL with per-launch CSRF state
     * and PKCE S256 challenge. Extracted for testability.
     */
    internal fun buildGoogleAuthUrl(clientId: String): String {
        val state = newState()
        val verifier = newCodeVerifier()
        pendingState = state
        pendingVerifier = verifier
        return Uri.parse(GOOGLE_AUTH_URL).buildUpon()
            .appendQueryParameter("client_id", clientId)
            .appendQueryParameter("redirect_uri", GOOGLE_REDIRECT)
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("scope", GOOGLE_SCOPE)
            .appendQueryParameter("access_type", "offline")
            .appendQueryParameter("prompt", "consent")
            .appendQueryParameter("state", state)
            .appendQueryParameter("code_challenge", pkceChallenge(verifier))
            .appendQueryParameter("code_challenge_method", "S256")
            .build()
            .toString()
    }

    /**
     * Launch the Microsoft OAuth consent flow.
     */
    fun launchMicrosoftAuth(clientId: String) {
        launchBrowser(buildMicrosoftAuthUrl(clientId))
    }

    /**
     * Build the Microsoft authorization URL with per-launch CSRF state
     * and PKCE S256 challenge. Extracted for testability.
     */
    internal fun buildMicrosoftAuthUrl(clientId: String): String {
        val state = newState()
        val verifier = newCodeVerifier()
        pendingState = state
        pendingVerifier = verifier
        return Uri.parse(MS_AUTH_URL).buildUpon()
            .appendQueryParameter("client_id", clientId)
            .appendQueryParameter("redirect_uri", MS_REDIRECT)
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("scope", MS_SCOPE)
            .appendQueryParameter("response_mode", "query")
            .appendQueryParameter("state", state)
            .appendQueryParameter("code_challenge", pkceChallenge(verifier))
            .appendQueryParameter("code_challenge_method", "S256")
            .build()
            .toString()
    }

    private fun launchBrowser(url: String) {
        val intent = CustomTabsIntent.Builder()
            .setShowTitle(true)
            .build()
        intent.intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        intent.launchUrl(context, Uri.parse(url))
    }

    /**
     * Handle the OAuth redirect URI. Called by MainActivity when
     * it receives an intent with scheme "aura" and path "oauth/...".
     *
     * Extracts the authorization code and exchanges it for tokens.
     * Returns true if the redirect was handled (code was present),
     * false if it was for a different path or had an error.
     *
     * CSRF guard: the code is only exchanged when the redirect carries
     * the exact state token issued by [launchGoogleAuth] /
     * [launchMicrosoftAuth]. A redirect without a matching state is
     * logged and rejected — this is the login-CSRF / account-binding
     * protection.
     */
    suspend fun handleRedirect(uri: Uri, googleClientId: String?, microsoftClientId: String?): Boolean {
        val code = uri.getQueryParameter("code")
        val error = uri.getQueryParameter("error")

        if (error != null) {
            Log.w(TAG, "OAuth error: $error")
            return true
        }
        if (code.isNullOrBlank()) return false

        // Reject any redirect that does not carry the exact state we
        // issued for the pending launch. This makes the flow immune to
        // crafted redirects: an attacker cannot inject an authorization
        // code that the app will exchange.
        val expectedState = pendingState
        val redirectState = uri.getQueryParameter("state")
        if (expectedState == null || redirectState != expectedState) {
            Log.w(TAG, "OAuth state mismatch — rejecting redirect (possible login CSRF)")
            return true
        }
        val verifier = pendingVerifier
        pendingState = null
        pendingVerifier = null
        if (verifier == null) {
            Log.w(TAG, "OAuth verifier missing — rejecting redirect")
            return true
        }

        when (uri.host) {
            "oauth" -> {
                val provider = uri.lastPathSegment // "google" or "microsoft"
                when (provider) {
                    "google" -> {
                        val cid = googleClientId ?: return false
                        exchangeGoogleCode(code, verifier, cid)
                        return true
                    }
                    "microsoft" -> {
                        val cid = microsoftClientId ?: return false
                        exchangeMicrosoftCode(code, verifier, cid)
                        return true
                    }
                }
            }
        }
        return false
    }

    private suspend fun exchangeGoogleCode(code: String, verifier: String, clientId: String) = withContext(Dispatchers.IO) {
        val body = FormBody.Builder()
            .add("grant_type", "authorization_code")
            .add("code", code)
            .add("redirect_uri", GOOGLE_REDIRECT)
            .add("client_id", clientId)
            .add("code_verifier", verifier)
            .build()

        val response = runCatching {
            httpClient.newCall(Request.Builder().url(GOOGLE_TOKEN_URL).post(body).build()).execute()
        }.onFailure { Log.w(TAG, "google token exchange failed: ${it.message}", it) }
            .getOrNull() ?: return@withContext

        response.use { resp ->
            if (!resp.isSuccessful) {
                Log.w(TAG, "google token exchange HTTP ${resp.code}")
                return@withContext
            }
            val responseBody = resp.body?.string() ?: return@withContext
            val parsed = runCatching { json.parseToJsonElement(responseBody).jsonObject }.getOrNull() ?: return@withContext
            val accessToken = parsed["access_token"]?.jsonPrimitive?.content ?: return@withContext
            val refreshToken = parsed["refresh_token"]?.jsonPrimitive?.content ?: ""
            val expiresIn = parsed["expires_in"]?.jsonPrimitive?.content?.toLongOrNull() ?: 3600L
            tokenStore.storeGoogleTokens(accessToken, refreshToken, expiresIn)
            Log.i(TAG, "google OAuth tokens stored")
        }
    }

    private suspend fun exchangeMicrosoftCode(code: String, verifier: String, clientId: String) = withContext(Dispatchers.IO) {
        val body = FormBody.Builder()
            .add("grant_type", "authorization_code")
            .add("code", code)
            .add("redirect_uri", MS_REDIRECT)
            .add("client_id", clientId)
            .add("scope", MS_SCOPE)
            .add("code_verifier", verifier)
            .build()

        val response = runCatching {
            httpClient.newCall(Request.Builder().url(MS_TOKEN_URL).post(body).build()).execute()
        }.onFailure { Log.w(TAG, "microsoft token exchange failed: ${it.message}", it) }
            .getOrNull() ?: return@withContext

        response.use { resp ->
            if (!resp.isSuccessful) {
                Log.w(TAG, "microsoft token exchange HTTP ${resp.code}")
                return@withContext
            }
            val responseBody = resp.body?.string() ?: return@withContext
            val parsed = runCatching { json.parseToJsonElement(responseBody).jsonObject }.getOrNull() ?: return@withContext
            val accessToken = parsed["access_token"]?.jsonPrimitive?.content ?: return@withContext
            val refreshToken = parsed["refresh_token"]?.jsonPrimitive?.content ?: ""
            val expiresIn = parsed["expires_in"]?.jsonPrimitive?.content?.toLongOrNull() ?: 3600L
            tokenStore.storeMicrosoftTokens(accessToken, refreshToken, expiresIn)
            Log.i(TAG, "microsoft OAuth tokens stored")
        }
    }

    /**
     * Refresh a Google access token using the stored refresh token.
     * Called by [IntegrationTokenStore.getValidGoogleAccessToken].
     */
    suspend fun refreshGoogleToken(refreshToken: String, clientId: String): IntegrationTokenStore.TokenRefreshResult? =
        withContext(Dispatchers.IO) {
            val body = FormBody.Builder()
                .add("grant_type", "refresh_token")
                .add("refresh_token", refreshToken)
                .add("client_id", clientId)
                .build()

            val response = runCatching {
                httpClient.newCall(Request.Builder().url(GOOGLE_TOKEN_URL).post(body).build()).execute()
            }.getOrNull() ?: return@withContext null

            response.use { resp ->
                if (!resp.isSuccessful) return@withContext null
                val responseBody = resp.body?.string() ?: return@withContext null
                val parsed = runCatching { json.parseToJsonElement(responseBody).jsonObject }.getOrNull() ?: return@withContext null
                val accessToken = parsed["access_token"]?.jsonPrimitive?.content ?: return@withContext null
                val newRefresh = parsed["refresh_token"]?.jsonPrimitive?.content ?: refreshToken
                val expiresIn = parsed["expires_in"]?.jsonPrimitive?.content?.toLongOrNull() ?: 3600L
                IntegrationTokenStore.TokenRefreshResult(accessToken, newRefresh, expiresIn)
            }
        }

    /**
     * Refresh a Microsoft access token.
     */
    suspend fun refreshMicrosoftToken(refreshToken: String, clientId: String): IntegrationTokenStore.TokenRefreshResult? =
        withContext(Dispatchers.IO) {
            val body = FormBody.Builder()
                .add("grant_type", "refresh_token")
                .add("refresh_token", refreshToken)
                .add("client_id", clientId)
                .add("scope", MS_SCOPE)
                .build()

            val response = runCatching {
                httpClient.newCall(Request.Builder().url(MS_TOKEN_URL).post(body).build()).execute()
            }.getOrNull() ?: return@withContext null

            response.use { resp ->
                if (!resp.isSuccessful) return@withContext null
                val responseBody = resp.body?.string() ?: return@withContext null
                val parsed = runCatching { json.parseToJsonElement(responseBody).jsonObject }.getOrNull() ?: return@withContext null
                val accessToken = parsed["access_token"]?.jsonPrimitive?.content ?: return@withContext null
                val newRefresh = parsed["refresh_token"]?.jsonPrimitive?.content ?: refreshToken
                val expiresIn = parsed["expires_in"]?.jsonPrimitive?.content?.toLongOrNull() ?: 3600L
                IntegrationTokenStore.TokenRefreshResult(accessToken, newRefresh, expiresIn)
            }
        }
}