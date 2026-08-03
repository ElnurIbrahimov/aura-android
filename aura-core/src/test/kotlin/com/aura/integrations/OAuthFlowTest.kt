package com.aura.integrations

import android.content.Context
import android.net.Uri
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import okhttp3.Call
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * OAuth CSRF + PKCE contract tests.
 *
 * Verifies the H1 fix: every launch issues a random `state` and an
 * S256 PKCE challenge; the redirect is only exchanged when it echoes
 * the exact state; the exchange carries the code_verifier. A redirect
 * without a matching state must never reach the token endpoint.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class OAuthFlowTest {

    private lateinit var tokenStore: IntegrationTokenStore
    private lateinit var httpClient: OkHttpClient
    private lateinit var flow: OAuthFlow
    private val capturedRequests = mutableListOf<Request>()

    private fun okResponse(): Response = Response.Builder()
        .request(capturedRequests.last())
        .protocol(Protocol.HTTP_1_1)
        .code(200)
        .message("OK")
        .body("""{"access_token":"at_123","refresh_token":"rt_456","expires_in":3600}""".toResponseBody("application/json".toMediaType()))
        .build()

    @Before
    fun setUp() {
        tokenStore = mockk(relaxed = true)
        httpClient = mockk()
        val call = mockk<Call>()
        every { httpClient.newCall(any()) } answers { capturedRequests.add(firstArg()); call }
        every { call.execute() } answers { okResponse() }
        val context = mockk<Context>(relaxed = true)
        flow = OAuthFlow(context, httpClient, tokenStore)
    }

    private fun redirectUri(state: String, code: String = "auth_code_1"): Uri =
        Uri.parse("aura://oauth/google?code=$code&state=$state")

    private fun formParams(request: Request): Map<String, String> {
        val form = request.body as FormBody
        return (0 until form.size).associate { form.name(it) to form.value(it) }
    }

    // --- Authorization URL construction ---

    @Test
    fun `google auth url carries state and S256 pkce challenge`() {
        val uri = Uri.parse(flow.buildGoogleAuthUrl("client-123"))

        assertEquals("client-123", uri.getQueryParameter("client_id"))
        assertEquals("aura://oauth/google", uri.getQueryParameter("redirect_uri"))
        assertEquals("code", uri.getQueryParameter("response_type"))
        assertNotNull("state must be present", uri.getQueryParameter("state"))
        assertNotNull("code_challenge must be present", uri.getQueryParameter("code_challenge"))
        assertEquals("S256", uri.getQueryParameter("code_challenge_method"))
    }

    @Test
    fun `microsoft auth url carries state and S256 pkce challenge`() {
        val uri = Uri.parse(flow.buildMicrosoftAuthUrl("ms-client"))

        assertEquals("ms-client", uri.getQueryParameter("client_id"))
        assertEquals("aura://oauth/microsoft", uri.getQueryParameter("redirect_uri"))
        assertNotNull("state must be present", uri.getQueryParameter("state"))
        assertNotNull("code_challenge must be present", uri.getQueryParameter("code_challenge"))
        assertEquals("S256", uri.getQueryParameter("code_challenge_method"))
    }

    @Test
    fun `each launch issues a fresh state`() {
        val first = Uri.parse(flow.buildGoogleAuthUrl("c")).getQueryParameter("state")
        val second = Uri.parse(flow.buildGoogleAuthUrl("c")).getQueryParameter("state")
        assertNotEquals("state must be random per launch", first, second)
    }

    @Test
    fun `pkce challenge is a 43-char base64url sha256 digest`() {
        val challenge = Uri.parse(flow.buildGoogleAuthUrl("c")).getQueryParameter("code_challenge")
        assertNotNull(challenge)
        assertTrue("challenge must be base64url of sha256", challenge!!.matches(Regex("[A-Za-z0-9_-]{43}")))
    }

    // --- Redirect handling / CSRF ---

    @Test
    fun `redirect with matching state exchanges the code with verifier`() = runBlocking {
        val state = Uri.parse(flow.buildGoogleAuthUrl("client-123")).getQueryParameter("state")!!

        val handled = flow.handleRedirect(redirectUri(state), "client-123", null)

        assertTrue(handled)
        coVerify { tokenStore.storeGoogleTokens("at_123", "rt_456", 3600L) }
        assertEquals("one token request", 1, capturedRequests.size)
        val params = formParams(capturedRequests.single())
        assertEquals("auth_code_1", params["code"])
        assertEquals("client-123", params["client_id"])
        val verifier = params["code_verifier"]
        assertNotNull("PKCE verifier must be present", verifier)
        assertTrue("verifier must be 43-128 chars", verifier!!.length in 43..128)
    }

    @Test
    fun `redirect with mismatched state is rejected without exchange`() = runBlocking {
        flow.buildGoogleAuthUrl("client-123") // issue a real state
        val forged = redirectUri(state = "attacker-chosen-state")

        val handled = flow.handleRedirect(forged, "client-123", null)

        assertTrue("forged redirect must be swallowed as handled", handled)
        coVerify(exactly = 0) { tokenStore.storeGoogleTokens(any(), any(), any()) }
        coVerify(exactly = 0) { tokenStore.storeMicrosoftTokens(any(), any(), any()) }
        assertEquals("no token request may be sent", 0, capturedRequests.size)
    }

    @Test
    fun `redirect with no state at all is rejected`() = runBlocking {
        flow.buildGoogleAuthUrl("client-123")
        val noState = Uri.parse("aura://oauth/google?code=stolen")

        flow.handleRedirect(noState, "client-123", null)

        coVerify(exactly = 0) { tokenStore.storeGoogleTokens(any(), any(), any()) }
        assertEquals(0, capturedRequests.size)
    }

    @Test
    fun `redirect without a pending launch is rejected`() = runBlocking {
        val handled = flow.handleRedirect(redirectUri("whatever"), "client-123", null)
        assertTrue(handled)
        coVerify(exactly = 0) { tokenStore.storeGoogleTokens(any(), any(), any()) }
        assertEquals(0, capturedRequests.size)
    }

    @Test
    fun `state is single-use - replay of same state is rejected`() = runBlocking {
        val state = Uri.parse(flow.buildGoogleAuthUrl("client-123")).getQueryParameter("state")!!

        flow.handleRedirect(redirectUri(state), "client-123", null)
        coVerify { tokenStore.storeGoogleTokens("at_123", "rt_456", 3600L) }

        // Replay attack: same code+state delivered again.
        flow.handleRedirect(redirectUri(state), "client-123", null)
        coVerify(exactly = 1) { tokenStore.storeGoogleTokens(any(), any(), any()) }
    }

    @Test
    fun `error redirect is reported and never exchanged`() = runBlocking {
        val state = Uri.parse(flow.buildGoogleAuthUrl("client-123")).getQueryParameter("state")!!
        val errorUri = Uri.parse("aura://oauth/google?error=access_denied&state=$state")

        val handled = flow.handleRedirect(errorUri, "client-123", null)

        assertTrue(handled)
        coVerify(exactly = 0) { tokenStore.storeGoogleTokens(any(), any(), any()) }
        assertEquals(0, capturedRequests.size)
    }

    @Test
    fun `microsoft flow validates state and sends verifier`() = runBlocking {
        val state = Uri.parse(flow.buildMicrosoftAuthUrl("ms-client")).getQueryParameter("state")!!
        val uri = Uri.parse("aura://oauth/microsoft?code=ms_code&state=$state")

        val handled = flow.handleRedirect(uri, null, "ms-client")

        assertTrue(handled)
        coVerify { tokenStore.storeMicrosoftTokens("at_123", "rt_456", 3600L) }
        val params = formParams(capturedRequests.single())
        assertEquals("ms_code", params["code"])
        assertNotNull(params["code_verifier"])
    }
}
