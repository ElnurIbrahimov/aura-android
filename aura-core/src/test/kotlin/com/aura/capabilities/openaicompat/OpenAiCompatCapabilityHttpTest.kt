package com.aura.capabilities.openaicompat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Response parsing for the discovered OpenAI-shaped capability backends.
 *
 * This carries a regression guard that used to live in `ImageGenToolTest` and
 * moved here with the code: **a JSON null must never become a URL.**
 *
 * `JsonPrimitive.content` on a JSON null is the four-character String "null" —
 * neither null nor blank — so reading `content` rather than `contentOrNull`
 * hands "null" back as if it were an image URL. Verified against the live Agnes
 * API on 2026-08-08: it returns BOTH keys on every response with the unused one
 * set to null, so any provider populating `b64_json` instead of `url` would hit
 * this. It is the same defect `d3550610` fixed in the OpenAI SSE parser.
 */
class OpenAiCompatCapabilityHttpTest {

    private fun parse(body: String) = OpenAiCompatCapabilityHttp.firstUrlOrB64(body, "test")

    @Test
    fun `a null sibling key does not become the url`() {
        // The exact shape Agnes returns.
        val (url, b64) = parse(
            """{"data":[{"b64_json":null,"revised_prompt":null,"url":"https://example.com/i.png"}]}""",
        )

        assertEquals("https://example.com/i.png", url)
        assertNull("a JSON null must read as absent, not as the string \"null\"", b64)
    }

    @Test
    fun `a null url falls through to the base64 payload`() {
        val (url, b64) = parse("""{"data":[{"url":null,"b64_json":"aGVsbG8="}]}""")

        assertNull(url)
        assertEquals("aGVsbG8=", b64)
    }

    @Test
    fun `a hosted url is returned`() {
        val (url, b64) = parse("""{"data":[{"url":"https://example.com/i.png"}]}""")
        assertEquals("https://example.com/i.png", url)
        assertNull(b64)
    }

    @Test
    fun `inline base64 is returned when that is all there is`() {
        // gpt-image-1 returns only this.
        val (url, b64) = parse("""{"data":[{"b64_json":"aGVsbG8="}]}""")
        assertNull(url)
        assertEquals("aGVsbG8=", b64)
    }

    @Test
    fun `an empty string is treated as absent`() {
        assertThrows(RuntimeException::class.java) {
            parse("""{"data":[{"url":"","b64_json":""}]}""")
        }
    }

    @Test
    fun `a response with neither key fails loudly`() {
        val failure = assertThrows(RuntimeException::class.java) {
            parse("""{"data":[{"revised_prompt":"a cat"}]}""")
        }
        assertEquals(true, failure.message?.contains("neither url nor b64_json"))
    }

    @Test
    fun `a response with no data fails loudly`() {
        val failure = assertThrows(RuntimeException::class.java) { parse("""{"data":[]}""") }
        assertEquals(true, failure.message?.contains("no data[0]"))
    }
}
