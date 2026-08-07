package com.aura.providers

import com.aura.integrations.IntegrationTokenStore
import com.aura.integrations.OAuthFlow
import com.aura.security.SecureDataStore
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * End-to-end for the thing this work exists to fix: a ChatGPT session that
 * outlives its access token.
 *
 * Everything else is scaffolding. Before this, the token lived in the plain
 * API-key slot with no expiry and no refresh token beside it, so an hour after
 * signing in the provider started failing and the only remedy was to go back
 * to a desktop, run `codex login` again, and re-paste.
 *
 * Uses a real [IntegrationTokenStore] over an in-memory DataStore so the store
 * and the provider are tested together — a mock of the store here would assert
 * only that I called the method I wrote.
 */
class ChatGptSubscriptionRefreshTest {

    private lateinit var server: MockWebServer
    private val values = mutableMapOf<String, String>()
    private lateinit var store: IntegrationTokenStore
    private lateinit var oauth: OAuthFlow

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        values.clear()
        store = IntegrationTokenStore(
            mockk {
                coEvery { putString(any(), any()) } answers { values[firstArg()] = secondArg() }
                coEvery { getString(any()) } answers { values[firstArg<String>()] }
                coEvery { removeString(any()) } answers { values.remove(firstArg<String>()); Unit }
            },
        )
        oauth = mockk()
    }

    @After
    fun tearDown() = server.shutdown()

    private fun provider(keys: ProviderKeys = noLegacyKey()) = ChatGptSubscriptionProvider(
        providerKeys = keys,
        httpClient = OkHttpClient(),
        tokenStore = store,
        oauthFlow = oauth,
        baseUrl = server.url("/").toString().removeSuffix("/"),
    )

    private fun noLegacyKey(): ProviderKeys = mockk {
        coEvery { keyForAwaiting("chatgpt") } returns null
        every { keyFor("chatgpt") } returns null
    }

    private fun enqueueCatalog() = server.enqueue(
        MockResponse().setResponseCode(200)
            .setBody("""{"models":[{"slug":"gpt-5.6-sol","context_window":272000,"visibility":"list"}]}"""),
    )

    @Test
    fun `an expired token is renewed and the request carries the new one`() = runBlocking {
        store.storeChatGptTokens("expired-token", "refresh-1", expiresInSeconds = -60)
        coEvery { oauth.refreshChatGptToken("refresh-1") } returns
            IntegrationTokenStore.TokenRefreshResult("renewed-token", "refresh-2", 3600)
        enqueueCatalog()

        provider().listModels()

        val sent = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("Bearer renewed-token", sent.getHeader("Authorization"))
        coVerify(exactly = 1) { oauth.refreshChatGptToken("refresh-1") }
    }

    @Test
    fun `a second request within the hour reuses the token instead of refreshing again`() = runBlocking {
        store.storeChatGptTokens("expired-token", "refresh-1", expiresInSeconds = -60)
        coEvery { oauth.refreshChatGptToken(any()) } returns
            IntegrationTokenStore.TokenRefreshResult("renewed-token", "refresh-2", 3600)
        enqueueCatalog()
        enqueueCatalog()

        provider().listModels()
        provider().listModels()

        coVerify(exactly = 1) { oauth.refreshChatGptToken(any()) }
        server.takeRequest(5, TimeUnit.SECONDS)
        assertEquals("Bearer renewed-token", server.takeRequest(5, TimeUnit.SECONDS)!!.getHeader("Authorization"))
    }

    @Test
    fun `the rotated refresh token is what the next renewal presents`() = runBlocking {
        store.storeChatGptTokens("expired-token", "refresh-1", expiresInSeconds = -60)
        // First renewal rotates the refresh token and hands back an access
        // token that is itself already stale, forcing a second renewal.
        coEvery { oauth.refreshChatGptToken("refresh-1") } returns
            IntegrationTokenStore.TokenRefreshResult("t2", "refresh-2", expiresInSeconds = -60)
        coEvery { oauth.refreshChatGptToken("refresh-2") } returns
            IntegrationTokenStore.TokenRefreshResult("t3", "refresh-3", 3600)
        enqueueCatalog()
        enqueueCatalog()

        provider().listModels()
        provider().listModels()

        // Re-presenting a rotated-away refresh token is how a grant gets
        // revoked outright, so each renewal must use the newest one.
        coVerify(exactly = 1) { oauth.refreshChatGptToken("refresh-1") }
        coVerify(exactly = 1) { oauth.refreshChatGptToken("refresh-2") }
        assertEquals("refresh-3", values["chatgpt_refresh_token"])
        server.takeRequest(5, TimeUnit.SECONDS)
        assertEquals("Bearer t3", server.takeRequest(5, TimeUnit.SECONDS)!!.getHeader("Authorization"))
    }

    @Test
    fun `a rejected refresh sends no request and reports the session expired`() = runBlocking {
        store.storeChatGptTokens("expired-token", "refresh-1", expiresInSeconds = -60)
        coEvery { oauth.refreshChatGptToken(any()) } returns null

        val models = provider().listModels()

        assertTrue(models.isEmpty())
        assertEquals(0, server.requestCount, "must not call the backend with a token known to be dead")
        assertTrue(store.chatgptSessionExpired.value)
        // Still 'configured' — the credentials exist, they just need renewing.
        assertTrue(provider().isConfigured())
    }

    // ── Migration from the old API-key slot ──

    @Test
    fun `a token left in the old api-key slot is adopted and the slot cleared`() = runBlocking {
        val legacy = jwtExpiringIn(1800)
        val keys = mockk<ProviderKeys> {
            coEvery { keyForAwaiting("chatgpt") } returns legacy
            every { keyFor("chatgpt") } returns legacy
            coEvery { set("chatgpt", "") } returns Unit
        }
        enqueueCatalog()

        provider(keys).listModels()

        assertEquals(legacy, values["chatgpt_access_token"])
        coVerify(exactly = 1) { keys.set("chatgpt", "") }
        assertEquals("Bearer $legacy", server.takeRequest(5, TimeUnit.SECONDS)!!.getHeader("Authorization"))
    }

    @Test
    fun `an adopted token that is already dead is still sent, so the server can say why`() = runBlocking {
        // A bare access token has no refresh token — the user has to sign in
        // again. That is the honest outcome of the old design, not a bug in
        // the migration. Sending it earns a real 401 from OpenAI; sending an
        // empty `Bearer` would earn a malformed-request error that explains
        // nothing about what the user needs to do.
        val expired = jwtExpiringIn(-100)
        val keys = mockk<ProviderKeys> {
            coEvery { keyForAwaiting("chatgpt") } returns expired
            every { keyFor("chatgpt") } returns expired
            coEvery { set("chatgpt", "") } returns Unit
        }
        server.enqueue(MockResponse().setResponseCode(401))

        assertFailsWith<ProviderCatalogException> { provider(keys).listModels() }

        assertEquals("Bearer $expired", server.takeRequest(5, TimeUnit.SECONDS)!!.getHeader("Authorization"))
        assertTrue(store.chatgptSessionExpired.value)
        assertNull(values["chatgpt_refresh_token"])
    }

    @Test
    fun `no credentials anywhere means no request and no crash`() = runBlocking {
        assertTrue(provider().listModels().isEmpty())
        assertEquals(0, server.requestCount)
    }

    private fun jwtExpiringIn(seconds: Long): String {
        val exp = System.currentTimeMillis() / 1000 + seconds
        val enc = java.util.Base64.getUrlEncoder().withoutPadding()
        return "${enc.encodeToString("{}".toByteArray())}." +
            "${enc.encodeToString("""{"exp":$exp}""".toByteArray())}.sig"
    }
}
