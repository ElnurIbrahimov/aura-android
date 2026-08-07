package com.aura.integrations

import com.aura.security.SecureDataStore
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The refresh path had no tests at all — which is how a store that was
 * perfectly capable of renewing a token ended up with one provider whose
 * credential expired every hour and could not be renewed, because nobody had
 * ever exercised the generic machinery against a third provider.
 *
 * These cover the parts that are easy to get subtly wrong: the expiry margin,
 * the TOCTOU re-check under the mutex, and refresh-token rotation.
 */
class IntegrationTokenStoreTest {

    private val values = mutableMapOf<String, String>()
    private lateinit var store: IntegrationTokenStore

    private fun nowSeconds() = System.currentTimeMillis() / 1000

    @Before
    fun setUp() {
        values.clear()
        val secure = mockk<SecureDataStore> {
            coEvery { putString(any(), any()) } answers { values[firstArg()] = secondArg() }
            coEvery { getString(any()) } answers { values[firstArg<String>()] }
            coEvery { removeString(any()) } answers { values.remove(firstArg<String>()); Unit }
        }
        store = IntegrationTokenStore(secure)
    }

    private fun neverCalled(): suspend (String) -> IntegrationTokenStore.TokenRefreshResult? = {
        error("refresh must not be called for a token that is still valid")
    }

    // ── Expiry ──

    @Test
    fun `an unexpired token is returned without contacting the token endpoint`() = runBlocking {
        store.storeChatGptTokens("live-token", "refresh-1", expiresInSeconds = 3600)

        assertEquals("live-token", store.getValidChatGptAccessToken(neverCalled()))
    }

    @Test
    fun `a token inside the sixty-second margin is refreshed early`() = runBlocking {
        // 30s of life left. Returning it would hand the caller a token that
        // may well die mid-request, which is the entire reason for a margin.
        store.storeChatGptTokens("about-to-die", "refresh-1", expiresInSeconds = 30)

        val token = store.getValidChatGptAccessToken {
            IntegrationTokenStore.TokenRefreshResult("fresh", "refresh-1", 3600)
        }

        assertEquals("fresh", token)
    }

    @Test
    fun `a token comfortably outside the margin is left alone`() = runBlocking {
        store.storeChatGptTokens("still-good", "refresh-1", expiresInSeconds = 120)

        assertEquals("still-good", store.getValidChatGptAccessToken(neverCalled()))
    }

    @Test
    fun `an expired token is refreshed and the new one is persisted`() = runBlocking {
        store.storeChatGptTokens("dead", "refresh-1", expiresInSeconds = -10)

        store.getValidChatGptAccessToken {
            IntegrationTokenStore.TokenRefreshResult("fresh", "refresh-2", 3600)
        }

        assertEquals("fresh", values["chatgpt_access_token"])
        assertEquals("refresh-2", values["chatgpt_refresh_token"])
        assertTrue(values["chatgpt_expires_at"]!!.toLong() > nowSeconds() + 3500)
        // Second call must now be served from the store, not another refresh.
        assertEquals("fresh", store.getValidChatGptAccessToken(neverCalled()))
    }

    @Test
    fun `a refresh that returns no new refresh token keeps the existing one`() = runBlocking {
        store.storeChatGptTokens("dead", "refresh-1", expiresInSeconds = -10)

        store.getValidChatGptAccessToken {
            IntegrationTokenStore.TokenRefreshResult("fresh", refreshToken = "", expiresInSeconds = 3600)
        }

        // Overwriting with a blank would strand the session permanently:
        // no refresh token means no way back without a fresh sign-in.
        assertEquals("refresh-1", values["chatgpt_refresh_token"])
    }

    @Test
    fun `a failed refresh yields no token and leaves the stored one untouched`() = runBlocking {
        store.storeChatGptTokens("dead", "refresh-1", expiresInSeconds = -10)

        assertNull(store.getValidChatGptAccessToken { null })

        assertEquals("dead", values["chatgpt_access_token"])
        assertEquals("refresh-1", values["chatgpt_refresh_token"])
    }

    @Test
    fun `a throwing refresh is contained rather than propagated`() = runBlocking {
        store.storeChatGptTokens("dead", "refresh-1", expiresInSeconds = -10)

        assertNull(store.getValidChatGptAccessToken { throw java.io.IOException("network down") })
    }

    @Test
    fun `no stored credentials means no token and no refresh attempt`() = runBlocking {
        assertNull(store.getValidChatGptAccessToken(neverCalled()))
    }

    // ── Concurrency ──

    @Test
    fun `concurrent callers trigger exactly one refresh`() = runBlocking {
        store.storeChatGptTokens("dead", "refresh-1", expiresInSeconds = -10)
        val refreshes = AtomicInteger(0)

        val tokens = (1..8).map {
            async {
                store.getValidChatGptAccessToken {
                    refreshes.incrementAndGet()
                    delay(20) // widen the window the re-check has to cover
                    IntegrationTokenStore.TokenRefreshResult("fresh", "refresh-2", 3600)
                }
            }
        }.awaitAll()

        // Without the re-read under the mutex, all eight would each see
        // "expired", queue on the lock, and hammer the token endpoint in turn.
        assertEquals(1, refreshes.get())
        assertTrue(tokens.all { it == "fresh" })
    }

    // ── ChatGPT session state ──

    @Test
    fun `a grant with a refresh token is connected and not expired`() = runBlocking {
        store.storeChatGptTokens("t", "refresh-1", 3600, accountLabel = "elnur@example.com · Plus")

        assertTrue(store.chatgptConnected.value)
        assertFalse(store.chatgptSessionExpired.value)
        assertEquals("elnur@example.com · Plus", store.chatgptAccount.value)
    }

    @Test
    fun `a grant with no refresh token reports the session as expired immediately`() = runBlocking {
        // This is the old behaviour preserved as a diagnosis: pasting a bare
        // access token gets you about an hour and then a dead end. Say so at
        // paste time instead of an hour later.
        store.storeChatGptTokens("t", refreshToken = "", expiresInSeconds = 3600)

        assertTrue(store.chatgptConnected.value)
        assertTrue(store.chatgptSessionExpired.value)
    }

    @Test
    fun `a refresh failure marks the session expired but keeps the provider configured`() = runBlocking {
        store.storeChatGptTokens("dead", "refresh-1", expiresInSeconds = -10)

        store.getValidChatGptAccessToken { null }

        assertTrue(store.chatgptSessionExpired.value)
        // Credentials still exist. Flipping `connected` here would make
        // isConfigured() false, and the model catalog short-circuits on that
        // before any network call — the user would be told they never
        // configured a provider they did configure.
        assertTrue(store.chatgptConnected.value)
    }

    @Test
    fun `disconnect clears every stored field`() = runBlocking {
        store.storeChatGptTokens("t", "refresh-1", 3600, accountLabel = "someone")

        store.disconnectChatGpt()

        assertFalse(store.chatgptConnected.value)
        assertNull(store.chatgptAccount.value)
        assertTrue(values.keys.none { it.startsWith("chatgpt_") }, "left behind: ${values.keys}")
    }

    @Test
    fun `connection state survives a process restart`() = runBlocking {
        store.storeChatGptTokens("t", "refresh-1", 3600, accountLabel = "elnur@example.com · Plus")

        // Same DataStore contents, fresh instance — as after process death.
        val restarted = IntegrationTokenStore(
            mockk {
                coEvery { putString(any(), any()) } answers { values[firstArg()] = secondArg() }
                coEvery { getString(any()) } answers { values[firstArg<String>()] }
                coEvery { removeString(any()) } answers { values.remove(firstArg<String>()); Unit }
            },
        )
        restarted.checkConnectionState()

        assertTrue(restarted.chatgptConnected.value)
        assertEquals("elnur@example.com · Plus", restarted.chatgptAccount.value)
    }

    // ── Migration ──

    @Test
    fun `a legacy bare access token is adopted`() = runBlocking {
        val legacy = jwt(mapOf("exp" to nowSeconds() + 1800, "email" to "elnur@example.com"))

        assertTrue(store.migrateLegacyChatGptToken(legacy))

        assertEquals(legacy, values["chatgpt_access_token"])
        assertEquals("elnur@example.com", store.chatgptAccount.value)
        // It cannot be renewed, and pretending otherwise is what made this
        // fail silently before.
        assertNull(values["chatgpt_refresh_token"])
        assertTrue(store.chatgptSessionExpired.value)
    }

    @Test
    fun `migration does not clobber a real grant`() = runBlocking {
        store.storeChatGptTokens("proper", "refresh-1", 3600)

        assertFalse(store.migrateLegacyChatGptToken(jwt(mapOf("exp" to nowSeconds() + 1800))))

        assertEquals("proper", values["chatgpt_access_token"])
    }

    @Test
    fun `migration rejects a value that is not a token`() = runBlocking {
        assertFalse(store.migrateLegacyChatGptToken("sk-not-a-jwt"))
        assertTrue(values.isEmpty())
    }

    // ── Provider isolation ──

    @Test
    fun `google and chatgpt grants do not share state`() = runBlocking {
        store.storeGoogleTokens("g-access", "g-refresh", 3600)
        store.storeChatGptTokens("c-access", "c-refresh", 3600)

        assertEquals("g-access", store.getValidGoogleAccessToken(neverCalled()))
        assertEquals("c-access", store.getValidChatGptAccessToken(neverCalled()))

        store.disconnectChatGpt()

        assertEquals("g-access", store.getValidGoogleAccessToken(neverCalled()))
        assertTrue(store.googleConnected.value)
    }

    private fun jwt(claims: Map<String, Any>): String {
        val payload = claims.entries.joinToString(",", "{", "}") { (k, v) ->
            if (v is String) "\"$k\":\"$v\"" else "\"$k\":$v"
        }
        val enc = java.util.Base64.getUrlEncoder().withoutPadding()
        return "${enc.encodeToString("{}".toByteArray())}.${enc.encodeToString(payload.toByteArray())}.sig"
    }
}
