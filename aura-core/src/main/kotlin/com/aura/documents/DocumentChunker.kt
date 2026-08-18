package com.aura.documents

/** Deterministic, paragraph-friendly text chunking for local document recall. */
object DocumentChunker {
    const val DEFAULT_MAX_CHARS = 1_800
    const val DEFAULT_OVERLAP_CHARS = 180

    /**
     * One chunk and where it came from.
     *
     * [charStart] and [charEnd] address [ChunkedText.normalized], **not** the
     * text handed to [chunkWithOffsets]. Normalisation collapses CRLF, trailing
     * whitespace and runs of blank lines, so offsets into the original would be
     * wrong by a drifting amount — and a citation that is wrong by a drifting
     * amount is worse than no citation, because it looks precise. The normalised
     * text is returned alongside so a caller that wants to resolve an offset has
     * the string the offset refers to.
     *
     * The invariant `normalized.substring(charStart, charEnd) == text` holds for
     * every chunk, and [DocumentChunkerOffsetsTest] pins it.
     */
    data class Chunk(val text: String, val charStart: Int, val charEnd: Int)

    /** The normalised source plus its chunks, which are offsets into it. */
    data class ChunkedText(val normalized: String, val chunks: List<Chunk>)

    fun chunk(
        text: String,
        maxChars: Int = DEFAULT_MAX_CHARS,
        overlapChars: Int = DEFAULT_OVERLAP_CHARS,
    ): List<String> = chunkWithOffsets(text, maxChars, overlapChars).chunks.map { it.text }

    fun chunkWithOffsets(
        text: String,
        maxChars: Int = DEFAULT_MAX_CHARS,
        overlapChars: Int = DEFAULT_OVERLAP_CHARS,
    ): ChunkedText {
        require(maxChars >= 80) { "maxChars must be at least 80" }
        require(overlapChars in 0 until maxChars) { "overlapChars must be smaller than maxChars" }

        val clean = text
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .replace(Regex("[ \\t]+\n"), "\n")
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()
        if (clean.isEmpty()) return ChunkedText(clean, emptyList())
        if (clean.length <= maxChars) {
            return ChunkedText(clean, listOf(Chunk(clean, 0, clean.length)))
        }

        val chunks = mutableListOf<Chunk>()
        var start = 0
        while (start < clean.length) {
            var end = minOf(start + maxChars, clean.length)
            if (end < clean.length) {
                val preferred = clean.lastIndexOf("\n\n", end).takeIf { it >= start + maxChars / 2 }
                    ?: clean.lastIndexOf(' ', end).takeIf { it >= start + maxChars / 2 }
                if (preferred != null) end = preferred
            }
            // The trim is the reason offsets are computed rather than assumed to
            // be (start, end): a chunk that begins after a paragraph break has
            // leading newlines the stored text does not, so `start` alone would
            // address whitespace the chunk excludes.
            val trimStart = clean.firstNonWhitespaceFrom(start, end)
            val trimEnd = clean.lastNonWhitespaceBefore(trimStart, end)
            if (trimEnd > trimStart) {
                chunks += Chunk(clean.substring(trimStart, trimEnd), trimStart, trimEnd)
            }
            if (end >= clean.length) break

            var next = (end - overlapChars).coerceAtLeast(start + 1)
            val boundary = clean.indexOf(' ', next).takeIf { it in next until end }
            if (boundary != null) next = boundary + 1
            start = next
        }
        return ChunkedText(clean, chunks)
    }

    private fun String.firstNonWhitespaceFrom(from: Int, until: Int): Int {
        var i = from
        while (i < until && this[i].isWhitespace()) i++
        return i
    }

    private fun String.lastNonWhitespaceBefore(from: Int, until: Int): Int {
        var i = until
        while (i > from && this[i - 1].isWhitespace()) i--
        return i
    }
}