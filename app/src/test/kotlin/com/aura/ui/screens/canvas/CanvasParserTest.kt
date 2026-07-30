package com.aura.ui.screens.canvas

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CanvasParserTest {

    @Test
    fun `extractCanvas detects markdown canvas block`() {
        val response = """
            Here's a document for you:

            ```canvas-markdown
            # Project Plan
            This is the content of the document.
            It has multiple lines.
            ```
        """.trimIndent()

        val canvas = extractCanvas(response)
        assertNotNull(canvas)
        assertEquals(CanvasType.MARKDOWN, canvas!!.type)
        assertEquals("Project Plan", canvas.title)
        assertTrue(canvas.content.contains("This is the content"))
    }

    @Test
    fun `extractCanvas detects code canvas block`() {
        val response = """
            ```canvas-code
            fun main() {
                println("Hello, World!")
            }
            ```
        """.trimIndent()

        val canvas = extractCanvas(response)
        assertNotNull(canvas)
        assertEquals(CanvasType.CODE, canvas!!.type)
        assertTrue(canvas.content.contains("fun main"))
    }

    @Test
    fun `extractCanvas detects html canvas block`() {
        val response = """
            ```canvas-html
            <!DOCTYPE html>
            <html><body><h1>Hello</h1></body></html>
            ```
        """.trimIndent()

        val canvas = extractCanvas(response)
        assertNotNull(canvas)
        assertEquals(CanvasType.HTML, canvas!!.type)
    }

    @Test
    fun `extractCanvas detects data canvas block`() {
        val response = """
            ```canvas-data
            {"labels": ["Jan", "Feb"], "values": [100, 200]}
            ```
        """.trimIndent()

        val canvas = extractCanvas(response)
        assertNotNull(canvas)
        assertEquals(CanvasType.DATA, canvas!!.type)
    }

    @Test
    fun `extractCanvas returns null when no canvas block`() {
        val response = "Just a regular response with ```python\nprint('hi')\n``` code."
        val canvas = extractCanvas(response)
        assertNull(canvas)
    }

    @Test
    fun `extractCanvas returns null for non-canvas fenced blocks`() {
        val response = "```python\nprint('hello')\n```"
        val canvas = extractCanvas(response)
        assertNull(canvas)
    }

    @Test
    fun `extractTitle extracts from markdown heading`() {
        val response = """
            ```canvas-markdown
            # My Great Document
            Content here.
            ```
        """.trimIndent()

        val canvas = extractCanvas(response)
        assertEquals("My Great Document", canvas!!.title)
    }

    @Test
    fun `extractTitle falls back to type label for long first line`() {
        val longLine = "x".repeat(100)
        val response = """
            ```canvas-data
            $longLine
            ```
        """.trimIndent()

        val canvas = extractCanvas(response)
        assertEquals("Data", canvas!!.title)
    }

    @Test
    fun `stripCanvasBlocks removes canvas blocks from text`() {
        val response = """
            Here is some text.

            ```canvas-markdown
            # Document
            Content.
            ```

            More text after.
        """.trimIndent()

        val stripped = stripCanvasBlocks(response)
        assertTrue(stripped.contains("Here is some text"))
        assertTrue(stripped.contains("More text after"))
        assertTrue(!stripped.contains("canvas-markdown"))
        assertTrue(!stripped.contains("Document"))
    }

    @Test
    fun `stripCanvasBlocks preserves non-canvas code blocks`() {
        val response = """
            ```python
            print("hello")
            ```
        """.trimIndent()

        val stripped = stripCanvasBlocks(response)
        assertTrue(stripped.contains("python"))
        assertTrue(stripped.contains("print"))
    }

    @Test
    fun `CanvasType fromLanguage matches known types`() {
        assertEquals(CanvasType.MARKDOWN, CanvasType.fromLanguage("canvas-markdown"))
        assertEquals(CanvasType.CODE, CanvasType.fromLanguage("canvas-code"))
        assertEquals(CanvasType.HTML, CanvasType.fromLanguage("canvas-html"))
        assertEquals(CanvasType.DATA, CanvasType.fromLanguage("canvas-data"))
    }

    @Test
    fun `CanvasType fromLanguage returns null for unknown`() {
        assertNull(CanvasType.fromLanguage("python"))
        assertNull(CanvasType.fromLanguage("canvas-unknown"))
        assertNull(CanvasType.fromLanguage(""))
    }
}