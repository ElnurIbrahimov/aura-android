package com.aura.ui.components

import org.junit.Test
import kotlin.test.assertEquals

class ErrorMessageMapperTest {

    // ── 4xx HTTP codes ────────────────────────────────────────────
    @Test fun `429 is rate limited`() {
        assertEquals(
            "Rate limited. Try again in a minute.",
            friendlyErrorMessage("http_429"),
        )
    }
    @Test fun `429 in body is rate limited`() {
        assertEquals(
            "Rate limited. Try again in a minute.",
            friendlyErrorMessage("Error 429 - Too Many Requests"),
        )
    }
    @Test fun `401 is invalid API key`() {
        assertEquals(
            "Your API key is invalid. Check Settings → AI & Models.",
            friendlyErrorMessage("http_401"),
        )
    }
    @Test fun `403 is access denied`() {
        assertEquals(
            "Access denied. Your API key may be expired.",
            friendlyErrorMessage("http_403"),
        )
    }

    // ── 5xx HTTP codes ────────────────────────────────────────────
    @Test fun `500 is provider outage`() {
        assertEquals(
            "The AI provider is having issues. Try again.",
            friendlyErrorMessage("http_500"),
        )
    }
    @Test fun `502 is provider outage`() {
        assertEquals(
            "The AI provider is having issues. Try again.",
            friendlyErrorMessage("Bad Gateway (502)"),
        )
    }
    @Test fun `503 is provider outage`() {
        assertEquals(
            "The AI provider is having issues. Try again.",
            friendlyErrorMessage("Service Unavailable 503"),
        )
    }

    // ── Provider code errors ──────────────────────────────────────
    @Test fun `missing_api_key is configuration hint`() {
        assertEquals(
            "No API key configured. Go to Settings → AI & Models.",
            friendlyErrorMessage("missing_api_key"),
        )
    }
    @Test fun `not_configured is setup hint`() {
        assertEquals(
            "This provider isn't set up yet. Go to Settings.",
            friendlyErrorMessage("provider_not_configured"),
        )
    }

    // ── Tool / request errors ─────────────────────────────────────
    @Test fun `tool_timeout is tool-specific`() {
        // 'tool_timeout' should hit the tool-specific branch first,
        // NOT the generic 'timeout' branch. This locks in the order.
        assertEquals(
            "A tool took too long to respond. Try again.",
            friendlyErrorMessage("tool_timeout"),
        )
    }
    @Test fun `timeout is generic request timeout`() {
        assertEquals(
            "The request timed out. Try again.",
            friendlyErrorMessage("stream_timeout"),
        )
    }
    @Test fun `network is connectivity error`() {
        assertEquals(
            "Network error. Check your internet connection.",
            friendlyErrorMessage("network failure"),
        )
    }
    @Test fun `connection is connectivity error`() {
        assertEquals(
            "Network error. Check your internet connection.",
            friendlyErrorMessage("connection refused"),
        )
    }

    // ── Fallback ──────────────────────────────────────────────────
    @Test fun `unknown error is passed through as-is`() {
        // Raw provider messages we don't recognize should be shown
        // verbatim — better than a misleading "Network error" pill.
        val raw = "Some weird provider error 9999"
        assertEquals(raw, friendlyErrorMessage(raw))
    }
    @Test fun `empty string is passed through as-is`() {
        assertEquals("", friendlyErrorMessage(""))
    }
}
