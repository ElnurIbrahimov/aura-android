package com.aura.memory

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertTrue

/**
 * End-to-end recall test: [MemoryStore.query] against a REAL in-memory
 * DAO. Regression for the `%%` pad bug — unused word slots LIKE-matched
 * every row, so recall returned "the freshest 15 in scope" for any query
 * and a relevant-but-older memory never entered the candidate pool.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class MemoryStoreQueryTest {

    private lateinit var db: MemoryDatabase
    private lateinit var store: MemoryStore

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
        store = MemoryStore(
            db.memoryDao(),
            FakeEmbedder(384),
            WriteGate(),
            db.memoryEditDao(),
            db.memoryFeedbackDao(),
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun memory(
        id: String,
        content: String,
        createdAt: Long,
        decayScore: Float,
    ) = MemoryEntity(
        id = id,
        content = content,
        source = "user",
        category = "fact",
        scope = "general",
        importance = 0.5f,
        createdAt = createdAt,
        accessedAt = createdAt,
        decayScore = decayScore,
    )

    @Test
    fun `older relevant memory beats a wall of fresh decoys`() = runBlocking {
        val now = System.currentTimeMillis()
        val dao = db.memoryDao()
        // The target: older, partially decayed, and the only row about Kotlin.
        dao.insert(memory("target", "Elnur's favorite language is Kotlin", now - 30L * 86_400_000, decayScore = 0.5f))
        // 30 fresh decoys sharing no content words with the query. Pre-fix,
        // these fill the entire candidate pool (top-15 by decayScore).
        repeat(30) { i ->
            dao.insert(memory("decoy$i", "Grocery run item number $i: bananas and milk", now - i * 60_000, decayScore = 1.0f))
        }

        val results = store.query("kotlin favorite language", MemoryStore.RecallOptions(limit = 5))

        assertTrue(
            results.any { it.id == "target" },
            "relevant memory missing from recall; got ${results.map { it.content }}",
        )
    }

    @Test
    fun `query with no lexical or vector overlap returns empty, not fresh rows`() = runBlocking {
        val now = System.currentTimeMillis()
        val dao = db.memoryDao()
        repeat(10) { i ->
            dao.insert(memory("decoy$i", "Grocery run item number $i: bananas and milk", now - i * 60_000, decayScore = 1.0f))
        }
        // No decoy contains these words, and no row has an embedding, so
        // both the lexical path and the vector fallback must come up empty.
        // Pre-fix, the %% pads returned every decoy.
        val results = store.query("zzqxv unknownword", MemoryStore.RecallOptions(limit = 5))
        assertTrue(results.isEmpty(), "expected no matches, got ${results.map { it.content }}")
    }

    @Test
    fun `stopword-only query does not flood the pool`() = runBlocking {
        val now = System.currentTimeMillis()
        val dao = db.memoryDao()
        dao.insert(memory("m1", "The user wants to make some things", now, decayScore = 1.0f))
        // Every query word is a stopword; the word list becomes empty and
        // the phrase path runs instead. It must not throw, and it must not
        // match rows that lack the full phrase.
        val results = store.query("want the this that", MemoryStore.RecallOptions(limit = 5))
        assertTrue(results.isEmpty(), "stopword query matched: ${results.map { it.content }}")
    }
}
