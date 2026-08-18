package com.aura.ui.screens.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InAppBrowserSheetTest {

    @Test
    fun `normalizeUrl prepends https when missing scheme`() {
        assertEquals("https://example.com", normalizeUrl("example.com"))
        assertEquals("https://example.com/path", normalizeUrl("example.com/path"))
    }

    @Test
    fun `normalizeUrl preserves http and https URLs`() {
        assertEquals("https://example.com", normalizeUrl("https://example.com"))
        assertEquals("http://example.com", normalizeUrl("http://example.com"))
    }

    @Test
    fun `normalizeUrl blocks file and content schemes`() {
        assertEquals("", normalizeUrl("file:///data/data/com.aura/files/secret.txt"))
        assertEquals("", normalizeUrl("content://com.android.providers/contacts/1"))
    }

    @Test
    fun `normalizeUrl returns empty for blank input`() {
        assertEquals("", normalizeUrl(""))
        assertEquals("", normalizeUrl("   "))
    }

    @Test
    fun `in-page navigation allows only http and https`() {
        assertTrue(allowedInPageScheme("https"))
        assertTrue(allowedInPageScheme("http"))
        assertTrue(allowedInPageScheme("HTTPS"))
    }

    @Test
    fun `in-page navigation blocks every other scheme`() {
        assertFalse(allowedInPageScheme("intent"))
        assertFalse(allowedInPageScheme("javascript"))
        assertFalse(allowedInPageScheme("file"))
        assertFalse(allowedInPageScheme("content"))
        assertFalse(allowedInPageScheme("about"))
        assertFalse(allowedInPageScheme(null))
    }

    // The three tests that were here re-declared the marker regex locally and
    // asserted it extracts a URL "from a tool result" and from arbitrary
    // surrounding text. They tested a regex, not the production path, and the
    // surrounding-text case enshrined the vulnerability as intended behaviour:
    // production parsed that marker out of EVERY tool result, including the
    // page body returned verbatim by the unattended READ_ONLY `read_url`.
    // The real contract is which tool may ask, so that is what is tested now —
    // see BrowserMarkerInjectionTest.

    @Test
    fun `only the browser tool may ask for a URL to be opened`() {
        assertEquals(
            "https://example.com/page?query=1",
            com.aura.ui.viewmodel.browserUrlFrom(
                "open_browser_tab",
                "[BROWSER:https://example.com/page?query=1]",
            ),
        )
        assertEquals(
            null,
            com.aura.ui.viewmodel.browserUrlFrom("read_url", "[BROWSER:https://example.com/page?query=1]"),
        )
    }
}