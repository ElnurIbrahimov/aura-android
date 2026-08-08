package com.aura.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FTS4 `MATCH` expressions are a query language, not a literal.
 *
 * The old lexical path escaped LIKE wildcards (`LikeEscapingTest` still covers
 * that for the paths which remain). MATCH is a different and larger surface:
 * `-` negates, `*` truncates, `^` anchors, `NEAR`/`OR`/`AND`/`NOT` are
 * operators, `"` opens a phrase. Getting it wrong fails in two ways, and the
 * quiet one is worse:
 *
 * - a syntax error raises `SQLiteException` at query time, so recall *crashes*
 *   rather than returning nothing, and
 * - a valid-but-different query silently changes what recall means — a message
 *   containing "NOT" would start excluding rows.
 *
 * Since the query is the user's whole message, both are reachable from ordinary
 * text. `MemoryDaoEscapeRegressionTest` pins the end-to-end behaviour against
 * real SQLite; this file pins the builder.
 */
class FtsQueryTest {

    @Test
    fun `terms are quoted and OR-joined`() {
        assertEquals("\"kotlin\" OR \"coroutines\"", FtsQuery.build(listOf("kotlin", "coroutines")))
    }

    @Test
    fun `a single term still builds`() {
        assertEquals("\"kotlin\"", FtsQuery.build(listOf("kotlin")))
    }

    @Test
    fun `operator words are matched literally, not as operators`() {
        // Quoted, these are phrases. Unquoted, "NOT" and "OR" would restructure
        // the query and change which rows come back.
        val built = FtsQuery.build(listOf("NOT", "OR", "NEAR"))
        assertEquals("\"NOT\" OR \"OR\" OR \"NEAR\"", built)
    }

    @Test
    fun `embedded quotes cannot break out of the phrase`() {
        // FTS4 has no escape character inside a phrase, so a stray quote would
        // close it early and turn the remainder into syntax.
        val built = FtsQuery.quote("say \"hello\" now")
        assertEquals("\"say hello now\"", built)
        assertTrue("quotes must balance", built!!.count { it == '"' } == 2)
    }

    @Test
    fun `wildcard and anchor characters are stripped`() {
        assertEquals("\"wild\"", FtsQuery.quote("wild*"))
        assertEquals("\"anchor\"", FtsQuery.quote("^anchor"))
        assertEquals("\"group\"", FtsQuery.quote("(group)"))
    }

    @Test
    fun `a leading hyphen is kept but cannot negate inside a phrase`() {
        // Inside quotes `-` is just a character, so the term is matched
        // literally rather than excluding rows that contain it.
        val built = FtsQuery.quote("-broke")
        assertEquals("\"-broke\"", built)
    }

    @Test
    fun `a term with no indexable characters is dropped`() {
        // `""` is a syntax error in FTS4, not an empty match.
        assertNull(FtsQuery.quote("***"))
        assertNull(FtsQuery.quote("---"))
        assertNull(FtsQuery.quote("   "))
        assertNull(FtsQuery.quote(""))
    }

    @Test
    fun `build returns null when nothing usable survives`() {
        // The caller MUST treat null as "do not run an FTS query". Passing an
        // empty string would make SQLite raise on `MATCH ''`.
        assertNull(FtsQuery.build(emptyList()))
        assertNull(FtsQuery.build(listOf("***", "  ", "^")))
    }

    @Test
    fun `unusable terms are dropped without discarding the usable ones`() {
        assertEquals("\"deploy\" OR \"pipeline\"", FtsQuery.build(listOf("deploy", "***", "pipeline")))
    }

    @Test
    fun `non-ascii terms survive`() {
        // The corpus is a personal memory store; it will contain names and
        // non-English text.
        assertEquals("\"Bakı\"", FtsQuery.quote("Bakı"))
        assertEquals("\"日本語\"", FtsQuery.quote("日本語"))
    }
}
