package com.aura.ui.screens.browser

import org.junit.Assert.assertEquals
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
    fun `browser marker regex extracts URL from tool result`() {
        val result = "[BROWSER:https://example.com/page?query=1]"
        val regex = Regex("\\[BROWSER:(.+?)\\]")
        val match = regex.find(result)
        assertTrue(match != null)
        assertEquals("https://example.com/page?query=1", match!!.groupValues[1])
    }

    @Test
    fun `browser marker regex handles URL with special characters`() {
        val result = "Opened: [BROWSER:https://en.wikipedia.org/wiki/Artificial_intelligence]"
        val regex = Regex("\\[BROWSER:(.+?)\\]")
        val match = regex.find(result)
        assertTrue(match != null)
        assertEquals("https://en.wikipedia.org/wiki/Artificial_intelligence", match!!.groupValues[1])
    }

    @Test
    fun `browser marker regex returns null when no marker`() {
        val result = "Opened in browser tab: https://example.com"
        val regex = Regex("\\[BROWSER:(.+?)\\]")
        val match = regex.find(result)
        assertTrue(match == null)
    }
}