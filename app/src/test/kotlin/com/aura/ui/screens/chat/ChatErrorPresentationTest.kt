package com.aura.ui.screens.chat

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChatErrorPresentationTest {

    @Test
    fun `lifts the message out of an OpenAI-compatible error envelope`() {
        val raw = """HTTP 400: {"error":{"message":"Invalid schema for function 'image_generate': """ +
            """schema must be a JSON Schema of 'type: \"object\"', got 'type: null'.",""" +
            """"type":"invalid_request_error","param":null,"code":"invalid_request_error"}}"""

        val presented = presentError(raw)

        assertEquals(
            "HTTP 400 — Invalid schema for function 'image_generate': " +
                "schema must be a JSON Schema of 'type: \"object\"', got 'type: null'.",
            presented.headline,
        )
        assertEquals(raw, presented.details, "the raw envelope stays available behind Details")
    }

    @Test
    fun `headline never contains the JSON envelope`() {
        val raw = """HTTP 429: {"error":{"message":"Rate limit reached.","type":"rate_limit"}}"""
        val presented = presentError(raw)
        assertTrue('{' !in presented.headline, "headline leaked JSON: ${presented.headline}")
        assertEquals("HTTP 429 — Rate limit reached.", presented.headline)
    }

    @Test
    fun `reads a top-level message when there is no error wrapper`() {
        val presented = presentError("""HTTP 500: {"message":"Internal error","code":500}""")
        assertEquals("HTTP 500 — Internal error", presented.headline)
    }

    @Test
    fun `a plain sentence passes through with no details disclosure`() {
        val presented = presentError("No internet connection.")
        assertEquals("No internet connection.", presented.headline)
        assertNull(presented.details, "a plain message needs no Details button")
    }

    @Test
    fun `malformed JSON falls back to the raw string`() {
        val raw = """HTTP 400: {not actually json"""
        val presented = presentError(raw)
        assertEquals(raw, presented.headline)
        assertNull(presented.details)
    }

    @Test
    fun `envelope with no message field falls back to the raw string`() {
        val raw = """HTTP 400: {"error":{"type":"invalid_request_error"}}"""
        val presented = presentError(raw)
        assertEquals(raw, presented.headline)
        assertNull(presented.details)
    }

    @Test
    fun `blank input yields a generic sentence`() {
        assertEquals("Something went wrong.", presentError("   ").headline)
    }
}
