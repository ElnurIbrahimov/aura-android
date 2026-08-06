package com.aura.memory

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Regression test for the ESCAPE clause in MemoryDao LIKE queries.
 *
 * The runtime SQL must be `ESCAPE '\'` (one backslash), which requires the
 * Kotlin source to contain `ESCAPE '\\'` (two backslash bytes). If the
 * source contains a single backslash (`ESCAPE '\'`), Kotlin unescapes `\'`
 * to a quote character, the runtime SQL becomes `ESCAPE ''`, and SQLite
 * throws "ESCAPE expression must be a single character" on EVERY call.
 *
 * This class runs against REAL in-memory SQLite (Robolectric + Room), so a
 * broken ESCAPE clause fails the test with the exact production exception.
 * Mocked-DAO tests cannot catch SQL-level errors — that is how the Aug 2026
 * regression survived a green 1,669-test suite.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class MemoryDaoEscapeRegressionTest {

    private lateinit var db: MemoryDatabase
    private lateinit var dao: MemoryDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, MemoryDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.memoryDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun memory(content: String, scope: String = "general") = MemoryEntity(
        id = "m_${content.hashCode()}",
        content = content,
        source = "user",
        category = "fact",
        scope = scope,
    )

    @Test
    fun `searchByText executes against real sqlite and matches literal percent`() = runBlocking {
        dao.insert(memory("discount 100% off"))
        dao.insert(memory("discount 100 percent"))

        // The escaped query must match ONLY the row containing the literal "%".
        val hits = dao.searchByText("%${escapeLikeWildcards("100% off")}%", limit = 10)

        assertEquals(1, hits.size)
        assertEquals("discount 100% off", hits.single().content)
    }

    @Test
    fun `searchByText treats underscore as literal when escaped`() = runBlocking {
        dao.insert(memory("a_b"))
        dao.insert(memory("axb"))

        // "a\_b" with ESCAPE '\' matches only the literal underscore row.
        val hits = dao.searchByText("%${escapeLikeWildcards("a_b")}%", limit = 10)

        assertEquals(listOf("a_b"), hits.map { it.content })
    }

    @Test
    fun `searchByWordsInScopes executes word search against real sqlite`() = runBlocking {
        dao.insert(memory("I love kotlin programming"))
        dao.insert(memory("I prefer python"))

        // Unused word slots are padded with a NUL-byte sentinel that can
        // never LIKE-match content (MemoryStore.NO_MATCH_SENTINEL). The old
        // "%%" pads matched every row — that regression is covered in
        // MemoryDaoContractTest; here we just exercise the query shape
        // against real SQLite.
        val pad = MemoryStore.NO_MATCH_SENTINEL
        val hits = dao.searchByWordsInScopes(
            word1 = "%kotlin%", word2 = pad, word3 = pad,
            word4 = pad, word5 = pad, word6 = pad,
            scopes = listOf("general"), limit = 10,
        )

        assertTrue(hits.any { it.content == "I love kotlin programming" })
        assertTrue("sentinel pads must not match unrelated rows", hits.none { it.content == "I prefer python" })
    }

    @Test
    fun `searchByTextInScopes executes against real sqlite`() = runBlocking {
        dao.insert(memory("shared fact"))
        dao.insert(memory("private fact", scope = "agent:private"))

        val hits = dao.searchByTextInScopes("%fact%", listOf("agent:private"), limit = 10)

        assertEquals(listOf("private fact"), hits.map { it.content })
    }
}
