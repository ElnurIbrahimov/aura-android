package com.aura.capabilities

import com.aura.capabilities.kling.KlingJwt
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Test
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for [KlingJwt] — the HS256 token Kling's API requires.
 * Kling rejects a raw secret sent as a Bearer credential; the JWT must
 * be header.payload.signature with base64url-without-padding segments,
 * iss=accessKey, exp=now+1800, nbf=now-5, signed HMAC-SHA256 with the
 * secret key.
 */
class KlingJwtTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun decodeSegment(segment: String): String =
        String(Base64.getUrlDecoder().decode(segment), Charsets.UTF_8)

    @Test
    fun `jwt has three base64url segments without padding`() {
        val jwt = KlingJwt.mint("ak_test", "sk_test", nowSeconds = 1_700_000_000L)
        val segments = jwt.split(".")
        assertEquals(3, segments.size)
        for (segment in segments) {
            assertFalse(segment.contains("="), "base64url must be unpadded: $segment")
            assertFalse(segment.contains("+"), "must be base64url, not base64: $segment")
            assertFalse(segment.contains("/"), "must be base64url, not base64: $segment")
        }
    }

    @Test
    fun `header declares HS256 and JWT`() {
        val jwt = KlingJwt.mint("ak_test", "sk_test", nowSeconds = 1_700_000_000L)
        val header = json.parseToJsonElement(decodeSegment(jwt.split(".")[0])).jsonObject
        assertEquals("HS256", header["alg"]!!.jsonPrimitive.content)
        assertEquals("JWT", header["typ"]!!.jsonPrimitive.content)
    }

    @Test
    fun `payload carries iss exp and nbf per Kling spec`() {
        val now = 1_700_000_000L
        val jwt = KlingJwt.mint("ak_access", "sk_secret", nowSeconds = now)
        val payload = json.parseToJsonElement(decodeSegment(jwt.split(".")[1])).jsonObject
        assertEquals("ak_access", payload["iss"]!!.jsonPrimitive.content)
        assertEquals(now + 1800L, payload["exp"]!!.jsonPrimitive.long)
        assertEquals(now - 5L, payload["nbf"]!!.jsonPrimitive.long)
    }

    @Test
    fun `signature verifies with the secret key`() {
        val jwt = KlingJwt.mint("ak_access", "sk_secret", nowSeconds = 1_700_000_000L)
        val (header, payload, signature) = jwt.split(".")
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec("sk_secret".toByteArray(Charsets.UTF_8), "HmacSHA256"))
        val expected = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(mac.doFinal("$header.$payload".toByteArray(Charsets.UTF_8)))
        assertEquals(expected, signature)
    }

    @Test
    fun `different secrets produce different signatures`() {
        val a = KlingJwt.mint("ak", "secret-a", nowSeconds = 1_700_000_000L)
        val b = KlingJwt.mint("ak", "secret-b", nowSeconds = 1_700_000_000L)
        assertEquals(a.substringBeforeLast("."), b.substringBeforeLast("."), "same input halves")
        assertTrue(a.substringAfterLast(".") != b.substringAfterLast("."), "signatures must differ")
    }

    // ── KlingVideoProvider stored-key parsing ───────────────────────────

    private fun provider() = com.aura.capabilities.kling.KlingVideoProvider(
        io.mockk.mockk(relaxed = true),
        io.mockk.mockk(relaxed = true),
    )

    @Test
    fun `provider mints a JWT for the accessKey-colon-secretKey format`() {
        val header = provider().authorizationHeader("myAccess:mySecret")
        assertTrue(header.startsWith("Bearer "))
        val jwt = header.removePrefix("Bearer ")
        val segments = jwt.split(".")
        assertEquals(3, segments.size, "expected a JWT, got: $jwt")
        val payload = json.parseToJsonElement(decodeSegment(segments[1])).jsonObject
        assertEquals("myAccess", payload["iss"]!!.jsonPrimitive.content)
    }

    @Test
    fun `provider keeps plain Bearer for a legacy single-string key`() {
        assertEquals("Bearer legacy-token", provider().authorizationHeader("legacy-token"))
    }
}
