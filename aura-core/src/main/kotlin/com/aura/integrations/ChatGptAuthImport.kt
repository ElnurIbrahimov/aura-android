package com.aura.integrations

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import java.util.Base64

/**
 * Reads a ChatGPT subscription grant out of whatever the user pasted.
 *
 * `codex login` writes `~/.codex/auth.json` on the desktop:
 *
 * ```json
 * {"OPENAI_API_KEY":null,
 *  "tokens":{"id_token":"…","access_token":"…","refresh_token":"…","account_id":"…"},
 *  "last_refresh":"2026-08-07T…"}
 * ```
 *
 * Aura previously took only the `access_token` — pasted into a field labelled
 * "API key" — and dropped everything around it. An access token lives about an
 * hour, so the provider stopped answering an hour after every sign-in and there
 * was nothing left in the store to recover with. The `refresh_token` was sitting
 * two lines away in the file the whole time.
 *
 * So this accepts the **whole file**. It also accepts the inner `tokens` object
 * on its own, and a bare access token, because people paste what they have —
 * but a bare token is the degraded case and [Credentials.refreshToken] comes
 * back blank to say so.
 */
object ChatGptAuthImport {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * Claims live under an OpenAI-namespaced key rather than at the top level,
     * because OIDC reserves the flat namespace for standard claims.
     */
    private const val OPENAI_CLAIM_NAMESPACE = "https://api.openai.com/auth"

    /**
     * Where the email actually lives. Checked against a real Codex grant: the
     * access token carries `{email, email_verified, name}` here and has no
     * top-level `email` claim at all, so looking only at the standard OIDC
     * location finds nothing unless an id_token was pasted too.
     */
    private const val OPENAI_PROFILE_NAMESPACE = "https://api.openai.com/profile"

    data class Credentials(
        val accessToken: String,
        /** Blank when the paste carried no refresh token — the session will expire and stay expired. */
        val refreshToken: String,
        /** Derived from the access token's own `exp`; 0 (or negative) when it is already expired. */
        val expiresInSeconds: Long,
        /** e.g. `"elnur@example.com · Plus"`, or null if the paste had no id_token. */
        val accountLabel: String?,
    )

    /**
     * @param nowSeconds injected so expiry maths is testable without a clock.
     * @return null when no access token could be found at all.
     */
    fun parse(raw: String, nowSeconds: Long = System.currentTimeMillis() / 1000): Credentials? {
        val text = raw.trim()
        if (text.isEmpty()) return null

        val (accessToken, refreshToken, idToken) = if (text.startsWith("{")) {
            val root = runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return null
            // The full auth.json nests the grant under "tokens"; the inner
            // object pasted alone has the same fields at its root.
            val tokens = (root["tokens"] as? JsonObject) ?: root
            Triple(
                tokens.string("access_token"),
                tokens.string("refresh_token").orEmpty(),
                tokens.string("id_token"),
            )
        } else {
            // A bare token. Reject anything that isn't shaped like a JWT rather
            // than storing prose and failing later with an opaque 401.
            if (text.count { it == '.' } != 2) return null
            Triple(text, "", null)
        }

        if (accessToken.isNullOrBlank()) return null

        val expiresAt = jwtClaims(accessToken)?.get("exp")?.let { (it as? JsonPrimitive)?.longOrNull }
        return Credentials(
            accessToken = accessToken,
            refreshToken = refreshToken,
            // No `exp` means we cannot know — treat as expired so the very
            // first use refreshes rather than sending a possibly-dead token.
            expiresInSeconds = expiresAt?.let { it - nowSeconds } ?: 0L,
            accountLabel = accountLabel(idToken ?: accessToken),
        )
    }

    /** "email · Plan", dropping whichever half is missing. */
    private fun accountLabel(token: String): String? {
        val claims = jwtClaims(token) ?: return null
        val email = claims.string("email")
            ?: (claims[OPENAI_PROFILE_NAMESPACE] as? JsonObject)?.string("email")
            ?: (claims[OPENAI_CLAIM_NAMESPACE] as? JsonObject)?.string("user_email")
        val plan = (claims[OPENAI_CLAIM_NAMESPACE] as? JsonObject)
            ?.string("chatgpt_plan_type")
            ?.replaceFirstChar { it.uppercase() }
        return listOfNotNull(email, plan).joinToString(" · ").takeIf { it.isNotBlank() }
    }

    /**
     * Decodes a JWT payload without verifying the signature — deliberately.
     * We are reading our own token to label a settings row, not trusting it to
     * authorize anything; the server verifies it on every request.
     */
    private fun jwtClaims(token: String): JsonObject? {
        val payload = token.split('.').getOrNull(1) ?: return null
        val decoded = runCatching {
            String(Base64.getUrlDecoder().decode(payload.padEnd((payload.length + 3) / 4 * 4, '=')))
        }.getOrNull() ?: return null
        return runCatching { json.parseToJsonElement(decoded) as? JsonObject }.getOrNull()
    }

    private fun JsonObject.string(name: String): String? =
        (this[name] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
}
