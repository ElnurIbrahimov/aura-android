package com.aura.memory

/**
 * Builds SQLite FTS4 `MATCH` expressions from untrusted text.
 *
 * FTS4 match strings are a small query language, not a literal: bare `-`
 * negates, `*` truncates, `^` anchors, `NEAR`/`OR`/`AND`/`NOT` are operators,
 * and `"` opens a phrase. Passing a user's message straight through is at best
 * a syntax error (which SQLite raises at query time, so it surfaces as a
 * crashed recall rather than an empty one) and at worst a query that means
 * something other than what was asked — a message containing `NOT` would
 * silently exclude results.
 *
 * The rule here: every term becomes a quoted phrase, which makes FTS treat it
 * as a literal, and everything is joined with an explicit `OR`. That matches
 * the semantics the old six-`LIKE` fetch had — "rows sharing at least one word
 * with the query" — so this is a like-for-like replacement of the candidate
 * step, not a change in what recall considers relevant.
 *
 * `escapeLikeWildcards` in MemoryStore stays for the LIKE paths that remain
 * (the stopword-only fallback and the Memory-screen search bar); this is its
 * counterpart for the FTS path.
 */
internal object FtsQuery {

    /**
     * Characters FTS4 treats as syntax inside an unquoted term. Quoting handles
     * the rest, but a stray `"` would close the phrase early, so it is stripped
     * outright rather than escaped — FTS4 has no escape character inside a
     * phrase.
     */
    private val STRIPPED = charArrayOf('"', '*', '^', '(', ')')

    /**
     * Turn [terms] into `"a" OR "b" OR "c"`.
     *
     * Returns null when nothing usable survives — the caller must treat that as
     * "do not run an FTS query" rather than passing an empty string, because
     * `MATCH ''` is a syntax error in SQLite, not an empty result.
     */
    fun build(terms: List<String>): String? {
        val quoted = terms.mapNotNull { quote(it) }
        return if (quoted.isEmpty()) null else quoted.joinToString(" OR ")
    }

    /**
     * Quote one term as an FTS phrase. Returns null for a term with no
     * indexable characters left after stripping — quoting an empty string
     * produces `""`, which FTS rejects.
     */
    fun quote(term: String): String? {
        val cleaned = buildString {
            for (ch in term) if (ch !in STRIPPED) append(ch)
        }.trim()
        // A term of only punctuation tokenizes to nothing; FTS treats `"-"` as
        // an empty phrase and errors rather than matching nothing.
        if (cleaned.isEmpty() || cleaned.none { it.isLetterOrDigit() }) return null
        return "\"$cleaned\""
    }
}
