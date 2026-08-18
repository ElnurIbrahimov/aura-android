package com.aura.integrations

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The OAuth redirect handler's CSRF and replay defences.
 *
 * `com.aura.integrations` is ~945 lines carrying the Google and Microsoft
 * token flows, and had no tests. `IntegrationTokenStore` was the exception —
 * its single-flight refresh is well covered — which left the part that decides
 * *whether to exchange an authorization code at all* untested.
 *
 * That is the security-critical half. `handleRedirect` is reachable from any
 * app on the device that can fire the redirect URI, so the state check is what
 * stands between a crafted redirect and Aura exchanging an attacker's code for
 * tokens it then stores as the user's own Google account.
 *
 * These assert the defence rather than the happy path: the happy path needs a
 * token endpoint, and a test that needs a server to prove a rejection is a test
 * that stops running the first time the server is inconvenient.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class OAuthFlowCsrfTest {

    private val httpClient = mockk<OkHttpClient>(relaxed = true)

    private fun flow(): OAuthFlow = OAuthFlow(
        ApplicationProvider.getApplicationContext<Context>(),
        httpClient,
        mockk<IntegrationTokenStore>(relaxed = true),
    )

    private fun stateOf(url: String): String =
        Uri.parse(url).getQueryParameter("state")
            ?: error("auth url carried no state: $url")

    private fun redirect(state: String, code: String = "auth-code-123"): Uri =
        Uri.parse("com.aura://oauth/google?code=$code&state=$state")

    @Test
    fun `the auth url carries a per-launch state and an S256 challenge`() {
        val url = flow().buildGoogleAuthUrl("client-id-1")
        val parsed = Uri.parse(url)

        assertEquals("S256", parsed.getQueryParameter("code_challenge_method"))
        assertTrue(
            "code_challenge must be present, or PKCE is decorative",
            !parsed.getQueryParameter("code_challenge").isNullOrBlank(),
        )
        assertTrue(
            "state must be present, or handleRedirect has nothing to check",
            !parsed.getQueryParameter("state").isNullOrBlank(),
        )
        assertEquals("code", parsed.getQueryParameter("response_type"))
    }

    @Test
    fun `two launches never share a state or a challenge`() {
        // A reused state is a replayable one, and a reused verifier defeats the
        // point of PKCE. Both are generated per launch from SecureRandom; this
        // pins that they are not hoisted to a field by a later refactor.
        val a = flow()
        val first = a.buildGoogleAuthUrl("client-id-1")
        val second = a.buildGoogleAuthUrl("client-id-1")

        assertNotEquals(stateOf(first), stateOf(second))
        assertNotEquals(
            Uri.parse(first).getQueryParameter("code_challenge"),
            Uri.parse(second).getQueryParameter("code_challenge"),
        )
    }

    @Test
    fun `a redirect carrying a state we never issued is refused`() = runBlocking {
        val f = flow()
        f.buildGoogleAuthUrl("client-id-1") // a real flow is pending

        val handled = f.handleRedirect(
            redirect(state = "state-the-attacker-made-up"),
            googleClientId = "client-id-1",
            microsoftClientId = null,
        )

        // Consumed — the app must not fall through to another handler — but no
        // exchange attempted. `verify` on the HTTP client is the assertion that
        // matters: "returned true" alone cannot tell a rejection from a success.
        assertTrue("the redirect must be consumed, not passed on", handled)
        verify(exactly = 0) { httpClient.newCall(any()) }
    }

    @Test
    fun `a redirect with no state at all is refused`() = runBlocking {
        val f = flow()
        f.buildGoogleAuthUrl("client-id-1")

        val handled = f.handleRedirect(
            Uri.parse("com.aura://oauth/google?code=auth-code-123"),
            googleClientId = "client-id-1",
            microsoftClientId = null,
        )

        assertTrue(handled)
        verify(exactly = 0) { httpClient.newCall(any()) }
    }

    @Test
    fun `a state cannot be used twice`() = runBlocking {
        // `pendingFlows.remove(state)` is what makes this one-shot. Replay
        // protection is the reason it is a remove rather than a get, and that
        // is a one-character difference nothing else would notice.
        val f = flow()
        val state = stateOf(f.buildGoogleAuthUrl("client-id-1"))

        // First use consumes it. It will try to exchange — the mocked client
        // makes that inert — so what this establishes is only that the state
        // is now spent.
        runCatching { f.handleRedirect(redirect(state), "client-id-1", null) }

        io.mockk.clearMocks(httpClient, answers = false)

        val replayed = f.handleRedirect(redirect(state), "client-id-1", null)

        assertTrue(replayed)
        verify(exactly = 0) { httpClient.newCall(any()) }
    }
}
