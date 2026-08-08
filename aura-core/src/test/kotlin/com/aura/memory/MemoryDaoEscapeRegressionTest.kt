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
            // Room creates the FTS virtual table but not the triggers that
            // populate it. Without this the index is empty and every lexical
            // assertion below would pass or fail for the wrong reason.
            .addCallback(MemoryFtsSchema.triggerCallback)
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
    fun `searchFts executes word search against real sqlite`() = runBlocking {
        dao.insert(memory("I love kotlin programming"))
        dao.insert(memory("I prefer python"))

        // Replaced the six-LIKE searchByWordsInScopes. The reason this suite
        // exists still applies: the ESCAPE regression survived 1,669 green
        // tests because every touching test mocked the DAO, and FTS MATCH is
        // another string the DAO hands to SQLite unparsed — a malformed one is
        // a runtime SQLiteException, invisible to a mock.
        val hits = dao.searchFts(FtsQuery.build(listOf("kotlin"))!!, listOf("general"), limit = 10)

        assertTrue(hits.any { it.content == "I love kotlin programming" })
        assertTrue("unrelated rows must not match", hits.none { it.content == "I prefer python" })
    }

    @Test
    fun `searchFts survives punctuation and operator words in user text`() = runBlocking {
        dao.insert(memory("the deploy pipeline broke"))

        // NOT / OR / NEAR / - / * / ^ / " are FTS4 syntax. Passed through raw
        // they are either a syntax error (a crashed recall, not an empty one)
        // or, worse, a query that quietly means something else — a message
        // containing NOT would start excluding results. FtsQuery quotes every
        // term so they are matched literally.
        val hostile = listOf("deploy", "NOT", "OR", "NEAR", "-broke", "wild*", "^anchor", "quo\"te")
        val hits = dao.searchFts(FtsQuery.build(hostile)!!, listOf("general"), limit = 10)

        assertTrue("expected the literal term to still match", hits.any { it.content == "the deploy pipeline broke" })
    }

    @Test
    fun `searchByTextInScopes executes against real sqlite`() = runBlocking {
        dao.insert(memory("shared fact"))
        dao.insert(memory("private fact", scope = "agent:private"))

        val hits = dao.searchByTextInScopes("%fact%", listOf("agent:private"), limit = 10)

        assertEquals(listOf("private fact"), hits.map { it.content })
    }
}
