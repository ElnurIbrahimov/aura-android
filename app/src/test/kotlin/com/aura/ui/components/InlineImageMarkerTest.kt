package com.aura.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InlineImageMarkerTest {

    private val imageRegex = Regex("\\[IMAGE:(.+?)\\]")

    @Test
    fun `extracts single image URL from tool result`() {
        val result = "[IMAGE:https://example.com/image.png]"
        val urls = imageRegex.findAll(result).map { it.groupValues[1] }.toList()
        assertEquals(1, urls.size)
        assertEquals("https://example.com/image.png", urls[0])
    }

    @Test
    fun `extracts image URL from formatted tool result text`() {
        val result = """
            Image generated via OpenAI.
            URL: https://example.com/generated/123.png
            [IMAGE:https://example.com/generated/123.png]
            MIME: image/png
        """.trimIndent()
        val urls = imageRegex.findAll(result).map { it.groupValues[1] }.toList()
        assertEquals(1, urls.size)
        assertEquals("https://example.com/generated/123.png", urls[0])
    }

    @Test
    fun `extracts multiple image URLs`() {
        val result = "[IMAGE:https://a.com/1.png] and [IMAGE:https://b.com/2.jpg]"
        val urls = imageRegex.findAll(result).map { it.groupValues[1] }.toList()
        assertEquals(2, urls.size)
        assertEquals("https://a.com/1.png", urls[0])
        assertEquals("https://b.com/2.jpg", urls[1])
    }

    @Test
    fun `returns empty when no image marker`() {
        val result = "Just a text response with no images."
        val urls = imageRegex.findAll(result).map { it.groupValues[1] }.toList()
        assertTrue(urls.isEmpty())
    }

    @Test
    fun `handles URLs with query parameters`() {
        val result = "[IMAGE:https://pollinations.ai/prompt/cat?width=1024&height=1024&nologo=true]"
        val urls = imageRegex.findAll(result).map { it.groupValues[1] }.toList()
        assertEquals(1, urls.size)
        assertTrue(urls[0].contains("width=1024"))
        assertTrue(urls[0].contains("nologo=true"))
    }
}