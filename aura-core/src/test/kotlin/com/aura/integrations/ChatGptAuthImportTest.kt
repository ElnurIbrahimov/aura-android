package com.aura.integrations

import org.junit.Test
import java.util.Base64
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Aura used to ask for "your ChatGPT API key" and get an access token, because
 * that is the only field the paste box had. The refresh token two lines away
 * in the same file was dropped, so every sign-in died an hour later.
 *
 * These pin the shapes a user might actually paste.
 */
class ChatGptAuthImportTest {

    private val now = 1_800_000_000L

    private fun jwt(payload: String): String {
        val enc = Base64.getUrlEncoder().withoutPadding()
        return "${enc.encodeToString("""{"alg":"RS256"}""".toByteArray())}." +
            "${enc.encodeToString(payload.toByteArray())}.signature"
    }

    private fun accessToken(exp: Long = now + 3600) = jwt("""{"exp":$exp,"sub":"user-1"}""")

    private fun idToken() = jwt(
        """{"email":"elnur@example.com",
            "https://api.openai.com/auth":{"chatgpt_plan_type":"plus","chatgpt_account_id":"acc-1"}}""",
    )

    /** What `codex login` writes to ~/.codex/auth.json. */
    private fun authJson(refresh: String = "rt-abc") = """
        {"OPENAI_API_KEY":null,
         "tokens":{"id_token":"${idToken()}","access_token":"${accessToken()}",
                   "refresh_token":"$refresh","account_id":"acc-1"},
         "last_refresh":"2026-08-07T09:12:33.123456789Z"}
    """.trimIndent()

    @Test
    fun `the whole auth file yields both tokens`() {
        val parsed = assertNotNull(ChatGptAuthImport.parse(authJson(), now))

        assertEquals(accessToken(), parsed.accessToken)
        // The field whose absence caused the bug.
        assertEquals("rt-abc", parsed.refreshToken)
    }

    @Test
    fun `expiry comes from the token itself, not a guess`() {
        val parsed = assertNotNull(ChatGptAuthImport.parse(authJson(), now))

        assertEquals(3600L, parsed.expiresInSeconds)
    }

    @Test
    fun `an already-expired token reports negative life remaining`() {
        val stale = """{"tokens":{"access_token":"${accessToken(exp = now - 500)}","refresh_token":"rt"}}"""

        val parsed = assertNotNull(ChatGptAuthImport.parse(stale, now))

        assertTrue(parsed.expiresInSeconds < 0, "got ${parsed.expiresInSeconds}")
    }

    @Test
    fun `a token with no exp claim is treated as expired`() {
        // Unknown expiry must not be optimistically assumed valid — refreshing
        // once too often is free, sending a dead token is a failed request.
        val noExp = """{"tokens":{"access_token":"${jwt("""{"sub":"u"}""")}","refresh_token":"rt"}}"""

        assertEquals(0L, ChatGptAuthImport.parse(noExp, now)!!.expiresInSeconds)
    }

    @Test
    fun `the account label is read from the id token`() {
        val parsed = assertNotNull(ChatGptAuthImport.parse(authJson(), now))

        assertEquals("elnur@example.com · Plus", parsed.accountLabel)
    }

    @Test
    fun `the account is named from the access token alone, with no id_token`() {
        // A real Codex access token has no top-level `email` claim — it lives
        // under the profile namespace. Reading only the OIDC-standard spot
        // left the card saying "Plus" with no idea whose account it was.
        val realShape = jwt(
            """{"exp":${now + 3600},
                "https://api.openai.com/profile":{"email":"elnur@example.com","name":"Elnur"},
                "https://api.openai.com/auth":{"chatgpt_plan_type":"plus"}}""",
        )

        val parsed = assertNotNull(ChatGptAuthImport.parse(realShape, now))

        assertEquals("elnur@example.com · Plus", parsed.accountLabel)
    }

    @Test
    fun `the inner tokens object pastes on its own`() {
        val inner = """{"access_token":"${accessToken()}","refresh_token":"rt-abc"}"""

        val parsed = assertNotNull(ChatGptAuthImport.parse(inner, now))

        assertEquals("rt-abc", parsed.refreshToken)
    }

    @Test
    fun `a bare access token is accepted but comes back with no refresh token`() {
        val parsed = assertNotNull(ChatGptAuthImport.parse(accessToken(), now))

        assertEquals(accessToken(), parsed.accessToken)
        // Blank is the signal that this session cannot be renewed. Callers
        // surface it rather than discovering it an hour later.
        assertEquals("", parsed.refreshToken)
    }

    @Test
    fun `surrounding whitespace from a clipboard paste is tolerated`() {
        assertNotNull(ChatGptAuthImport.parse("\n  ${authJson()}  \n", now))
    }

    @Test
    fun `an auth file whose refresh token is missing does not fabricate one`() {
        val noRefresh = """{"tokens":{"access_token":"${accessToken()}"}}"""

        assertEquals("", ChatGptAuthImport.parse(noRefresh, now)!!.refreshToken)
    }

    // ── Rejections ──

    @Test
    fun `prose is rejected rather than stored as a token`() {
        // Storing this would produce an opaque 401 much later, far from the
        // paste that caused it.
        assertNull(ChatGptAuthImport.parse("here is my key i think"))
        assertNull(ChatGptAuthImport.parse("sk-proj-abcdef123456"))
    }

    @Test
    fun `empty and malformed input is rejected`() {
        assertNull(ChatGptAuthImport.parse(""))
        assertNull(ChatGptAuthImport.parse("   "))
        assertNull(ChatGptAuthImport.parse("{not json"))
    }

    @Test
    fun `json with no access token anywhere is rejected`() {
        assertNull(ChatGptAuthImport.parse("""{"OPENAI_API_KEY":null,"tokens":{"account_id":"acc-1"}}"""))
    }

    @Test
    fun `an undecodable payload does not blow up the parse`() {
        val parsed = ChatGptAuthImport.parse("header.!!!not-base64!!!.sig", now)

        // The token shape is plausible, so it is kept; only the claims are
        // unreadable, which costs a label and an expiry, not the sign-in.
        assertNotNull(parsed)
        assertEquals(0L, parsed.expiresInSeconds)
        assertNull(parsed.accountLabel)
    }
}
