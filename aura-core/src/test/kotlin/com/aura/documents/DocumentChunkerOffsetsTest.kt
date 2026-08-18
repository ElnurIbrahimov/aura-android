package com.aura.documents

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The offsets are the whole point of moving chunks into their own table.
 *
 * A chunk row without a usable character range is a memory row with extra
 * columns — the citation is what `document_chunks` has that `memories` does
 * not. So the invariant is pinned directly: the range addresses the chunk, in
 * the text the range is expressed in.
 *
 * That last clause is the trap. [DocumentChunker] normalises before it splits
 * (CRLF, trailing whitespace, runs of blank lines), and it trims each chunk
 * afterwards, so neither the caller's original string nor the naive `(start,
 * end)` loop bounds address the stored text.
 */
class DocumentChunkerOffsetsTest {

    private val longText = (1..600).joinToString(" ") { "passage-$it" }

    @Test
    fun `every chunk is exactly the text at its own offsets`() {
        val chunked = DocumentChunker.chunkWithOffsets(longText)

        assertTrue(chunked.chunks.size > 1, "the fixture must actually split")
        chunked.chunks.forEach { chunk ->
            assertEquals(
                chunk.text,
                chunked.normalized.substring(chunk.charStart, chunk.charEnd),
                "chunk at ${chunk.charStart}..${chunk.charEnd} does not address its own text",
            )
        }
    }

    @Test
    fun `offsets survive the normalisation that makes them necessary`() {
        // CRLF, trailing spaces before a newline, and a three-blank-line run —
        // one of each thing normalisation collapses. Offsets into the ORIGINAL
        // would drift by a growing amount here, which is why they are not.
        val messy = "First part.   \r\n\r\n\r\n\r\nSecond part.\r\n   \r\nThird part."
        val chunked = DocumentChunker.chunkWithOffsets(messy, maxChars = 80, overlapChars = 0)

        chunked.chunks.forEach { chunk ->
            assertEquals(
                chunk.text,
                chunked.normalized.substring(chunk.charStart, chunk.charEnd),
            )
        }
        assertTrue(chunked.normalized.none { it == '\r' })
    }

    @Test
    fun `a chunk beginning after a paragraph break does not start on whitespace`() {
        // The loop's `start` lands on the newlines a paragraph break leaves
        // behind, and the stored text is trimmed of them. Using `start` as the
        // offset would point the citation at the gap before the chunk.
        val paragraphs = (1..40).joinToString("\n\n") { "Paragraph $it with several words in it." }
        val chunked = DocumentChunker.chunkWithOffsets(paragraphs, maxChars = 120, overlapChars = 10)

        assertTrue(chunked.chunks.size > 1)
        chunked.chunks.forEach { chunk ->
            assertTrue(
                chunk.text.isNotEmpty() && !chunk.text.first().isWhitespace(),
                "stored chunk text should be trimmed",
            )
            assertEquals(chunk.text, chunked.normalized.substring(chunk.charStart, chunk.charEnd))
        }
    }

    @Test
    fun `the string-only API still returns what it always did`() {
        // `chunk()` is called from the import path and from
        // `DocumentChunkerTest`; it now delegates, so this pins that delegating
        // did not change its output.
        assertEquals(
            DocumentChunker.chunkWithOffsets(longText).chunks.map { it.text },
            DocumentChunker.chunk(longText),
        )
    }

    @Test
    fun `a document short enough not to split is one chunk covering all of it`() {
        val short = "A single short paragraph."
        val chunked = DocumentChunker.chunkWithOffsets(short)

        assertEquals(1, chunked.chunks.size)
        assertEquals(0, chunked.chunks.single().charStart)
        assertEquals(chunked.normalized.length, chunked.chunks.single().charEnd)
        assertEquals(short, chunked.chunks.single().text)
    }

    @Test
    fun `empty text produces no chunks and no offsets to resolve`() {
        val chunked = DocumentChunker.chunkWithOffsets("   \n\n  ")
        assertTrue(chunked.chunks.isEmpty())
    }
}
