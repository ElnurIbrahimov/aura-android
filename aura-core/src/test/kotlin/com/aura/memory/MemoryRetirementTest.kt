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
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Retirement: a memory can stop being retrievable without being destroyed.
 *
 * The distinction matters because the two ways a memory can stop being true are
 * not the same. Something that was never true should vanish; something the
 * world moved past is history, and deleting history means it cannot be asked
 * about later or restored when the correction itself was the mistake. Both go
 * through the same mechanism, and neither destroys a row.
 *
 * These are the properties consolidation depends on — it is the first caller —
 * and the ones the correction spine will depend on next.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class MemoryRetirementTest {

    private lateinit var db: MemoryDatabase
    private lateinit var store: MemoryStore

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, MemoryDatabase::class.java)
            .allowMainThreadQueries()
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
    fun tearDown() = db.close()

    @Test
    fun `a retired memory stops being recalled but still exists`(): Unit = runBlocking {
        val id = store.store("Elnur lives in Baku", "user", "fact", 0.8f)
        assertTrue(store.searchByText("Baku").any { it.id == id })

        store.retire(id, reason = "corrected")

        assertTrue(store.searchByText("Baku").none { it.id == id }, "a retired memory was still recalled")
        assertTrue(store.recent(50).none { it.id == id })
        assertTrue(store.top(50).none { it.id == id })
        // Still there, and still readable by id — this is what makes undo a
        // restore rather than a reconstruction.
        val row = store.get(id)
        assertNotNull(row)
        assertEquals("Elnur lives in Baku", row.content)
        assertEquals("corrected", row.retiredReason)
        assertTrue(store.retired().any { it.id == id })
    }

    @Test
    fun `un-retiring puts it back exactly`(): Unit = runBlocking {
        val id = store.store("Elnur lives in Baku", "user", "fact", 0.8f)
        store.retire(id, reason = "corrected")
        store.unretire(id)

        assertTrue(store.searchByText("Baku").any { it.id == id })
        val row = store.get(id)!!
        assertNull(row.retiredAt)
        assertNull(row.retiredReason)
        assertNull(row.supersededBy)
    }

    @Test
    fun `retiring twice does not rewrite the first retirement`(): Unit = runBlocking {
        // Apply is re-runnable after a crash, so the second pass must not move
        // the timestamp or overwrite why it happened.
        val id = store.store("x", "user", "fact", 0.5f)
        assertTrue(store.retire(id, reason = "consolidated", now = 1_000L))
        assertTrue(!store.retire(id, reason = "corrected", now = 2_000L))

        val row = store.get(id)!!
        assertEquals(1_000L, row.retiredAt)
        assertEquals("consolidated", row.retiredReason)
    }

    @Test
    fun `retired memories do not count and do not block a new store`(): Unit = runBlocking {
        val id = store.store("Elnur drinks tea", "user", "preference", 0.5f)
        assertEquals(1, store.count())
        store.retire(id, reason = "corrected")
        assertEquals(0, store.count())

        // Exact-content dedup must not treat a retracted memory as a reason to
        // refuse the correction that replaces it.
        val replacement = store.maybeStore(
            "Elnur drinks tea",
            category = "preference",
            importance = 0.5f,
        )
        assertNotNull(replacement)
    }

    @Test
    fun `consolidation carries forward what the sources earned`(): Unit = runBlocking {
        val a = store.store("Elnur drinks tea", "user", "preference", 0.9f, tags = listOf("drink"))
        val b = store.store("Elnur prefers tea to coffee", "user", "preference", 0.4f, tags = listOf("coffee"))
        repeat(5) { store.touch(a) }
        repeat(2) { store.touch(b) }
        val sources = listOf(store.get(a)!!, store.get(b)!!)

        val merged = store.consolidate(sources, "Elnur prefers tea to coffee", "preference")

        val row = store.get(merged)!!
        // Importance used to be hardcoded to 0.7 and tags dropped entirely, so
        // merging a memory the user relied on produced a weaker, untagged one:
        // the merge itself demoted the fact.
        assertEquals(0.9f, row.importance)
        assertEquals("coffee,drink", row.tags)
        assertEquals(7, row.accessCount)
        assertEquals(sources.minOf { it.createdAt }, row.createdAt)
        assertTrue(row.metadata.contains(a) && row.metadata.contains(b), "provenance was dropped: ${row.metadata}")

        // Sources are superseded, not destroyed.
        for (source in sources) {
            val retired = store.get(source.id)!!
            assertEquals(merged, retired.supersededBy)
            assertEquals(REASON_CONSOLIDATED, retired.retiredReason)
        }
        assertTrue(store.searchByText("tea").map { it.id }.toSet() == setOf(merged))
    }

    @Test
    fun `consolidation refuses to span scopes`(): Unit = runBlocking {
        val a = store.store("a", "user", "fact", 0.5f, scope = "general")
        val b = store.store("b", "user", "fact", 0.5f, scope = "agent:researcher")
        val sources = listOf(store.get(a)!!, store.get(b)!!)

        val failed = runCatching { store.consolidate(sources, "ab", "fact") }
        assertTrue(failed.isFailure, "a cross-scope merge would widen who can recall a private fact")
        // Nothing was retired on the way to failing.
        assertNull(store.get(a)!!.retiredAt)
        assertNull(store.get(b)!!.retiredAt)
    }

    @Test
    fun `near-duplicate clusters group by content, within a scope`(): Unit = runBlocking {
        // FakeEmbedder is a hash sketch with no semantics, so identical text is
        // the only reliable way to produce a high cosine here. What this pins
        // is the grouping rule, not the similarity model.
        store.store("Elnur prefers tea", "user", "preference", 0.5f)
        store.store("Elnur prefers tea", "user", "preference", 0.5f)
        store.store("Elnur prefers tea", "user", "preference", 0.5f, scope = "agent:researcher")
        store.store("something else entirely", "user", "preference", 0.5f)

        val clusters = store.findNearDuplicateClusters()
        assertEquals(1, clusters.size, "a cluster must not reach across scopes")
        assertEquals(2, clusters.single().memories.size)
        assertTrue(clusters.single().memories.all { it.scope == "general" })
    }
}
