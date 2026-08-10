package com.aura.voice

/**
 * Emits complete sentences from a growing stream of text, so speech can start
 * before generation finishes.
 *
 * Voice mode waited for `!streaming` before speaking a word. On a reply of any
 * length that is one to three seconds of silence after the user stops talking —
 * the single largest contributor to voice mode feeling slow, and it has nothing
 * to do with model latency. Speaking the first sentence while the rest is still
 * arriving removes most of it.
 *
 * Stateful and single-threaded by design: [accept] is called with each
 * cumulative snapshot of the response and returns only what is newly complete.
 *
 * The hard part is not finding full stops. It is not stopping at the wrong one
 * — "Dr. Smith", "3.5", "e.g." and a trailing "..." are all mid-sentence, and a
 * naive split turns fluent speech into stutter.
 */
class SentenceChunker {

    private var consumed = 0

    /**
     * Feed the latest cumulative text; get back whatever sentences completed
     * since the last call.
     *
     * Cumulative rather than incremental because that is what the streaming UI
     * already holds. Passing deltas would mean every caller reassembling them,
     * and a dropped delta would silently truncate speech.
     */
    fun accept(fullText: String): List<String> {
        if (fullText.length < consumed) {
            // The text shrank — a retry, an edit, or a reset. Start over rather
            // than emitting a fragment of the old response against the new one.
            consumed = 0
        }
        val pending = fullText.substring(consumed)
        if (pending.isEmpty()) return emptyList()

        val out = mutableListOf<String>()
        var searchFrom = 0
        while (true) {
            val end = findSentenceEnd(pending, searchFrom) ?: break
            val sentence = pending.substring(searchFrom, end).trim()
            if (sentence.isNotEmpty()) out += sentence
            searchFrom = end
        }
        consumed += searchFrom
        return out
    }

    /**
     * Whatever is left, complete or not.
     *
     * Called when the stream ends: the last sentence usually has no terminator
     * yet, and dropping it would silently swallow the end of every reply.
     */
    fun flush(fullText: String): String {
        if (fullText.length < consumed) consumed = 0
        val rest = fullText.substring(consumed).trim()
        consumed = fullText.length
        return rest
    }

    fun reset() {
        consumed = 0
    }

    /**
     * Index just past the end of the first sentence at or after [from], or null.
     *
     * Requires a terminator followed by whitespace, so a number like `3.5` and
     * a domain like `example.com` do not split — and requires enough text after
     * the terminator to be sure the whitespace is real rather than the stream
     * simply having stopped mid-token.
     */
    private fun findSentenceEnd(text: String, from: Int): Int? {
        var i = from
        while (i < text.length) {
            val c = text[i]
            if (c in TERMINATORS) {
                // Run past "...", "?!" and similar so they emit once, not three
                // times with two empty fragments between.
                var end = i
                while (end < text.length && text[end] in TERMINATORS) end++

                // A terminator at the very end of what has arrived is
                // ambiguous: it may end a sentence, or be the first dot of an
                // ellipsis still being written. Wait for more — except for CJK,
                // where the mark is unambiguous and there is no following space
                // to wait for.
                if (end >= text.length) {
                    return if (text[i] in CJK_TERMINATORS) end else null
                }

                // CJK terminators need no following whitespace — 。 and ？ end a
                // sentence on their own, and requiring a space after them means
                // Japanese and Chinese never chunk at all. Requiring it for
                // ASCII is what keeps "3.5" and "example.com" intact, so the
                // rule differs by terminator rather than being relaxed for both.
                val cjk = text[i] in CJK_TERMINATORS
                if (!cjk && !text[end].isWhitespace()) {
                    i = end
                    continue
                }
                if (!cjk && isAbbreviation(text, i)) {
                    i = end
                    continue
                }
                if (cjk) return end

                // Include the trailing whitespace so the next sentence does not
                // start with it.
                var afterSpace = end
                while (afterSpace < text.length && text[afterSpace].isWhitespace()) afterSpace++
                return afterSpace
            }
            i++
        }
        return null
    }

    /**
     * Whether the terminator at [dot] is part of an abbreviation rather than
     * the end of a sentence.
     *
     * Covers the two shapes that actually matter in assistant replies: a known
     * title or Latin abbreviation, and a single capital letter used as an
     * initial. Not exhaustive, and cannot be — the failure mode is a slightly
     * early pause, which is far cheaper than the alternative of never pausing.
     */
    private fun isAbbreviation(text: String, dot: Int): Boolean {
        if (text[dot] != '.') return false
        var start = dot
        while (start > 0 && (text[start - 1].isLetter() || text[start - 1] == '.')) start--
        val word = text.substring(start, dot)
        if (word.lowercase() in ABBREVIATIONS) return true
        // A single capital letter — an initial, as in "J. R. R. Tolkien".
        return word.length == 1 && word[0].isUpperCase()
    }

    private companion object {
        val TERMINATORS = charArrayOf('.', '!', '?', '。', '！', '？')

        /**
         * Terminators that end a sentence without a following space. Kept
         * separate because the whitespace requirement is what protects "3.5"
         * and "example.com" — relaxing it for ASCII too would split both.
         */
        val CJK_TERMINATORS = charArrayOf('。', '！', '？')

        /**
         * Abbreviations that end in a full stop mid-sentence. Lowercased at the
         * comparison, so each appears once.
         */
        val ABBREVIATIONS = setOf(
            "mr", "mrs", "ms", "dr", "prof", "sr", "jr", "st", "mt",
            "e.g", "i.e", "etc", "vs", "approx", "no", "vol", "fig",
            "jan", "feb", "mar", "apr", "jun", "jul", "aug", "sep", "sept", "oct", "nov", "dec",
        )
    }
}
