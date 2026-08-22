package com.aura.creative

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.aura.memory.MemoryDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The question a writer actually has: **did the rewrite help?**
 *
 * Every writing tool generates feedback and none of them measure whether the
 * feedback worked. This app was no different — `TensionAnalyzer` streamed prose
 * into a text box, declared `TensionReport` and `SceneScore` types it never once
 * constructed, and threw the result away. Meanwhile `CreativeRevisionEntity` has
 * been append-only with a `parentRevisionId` the whole time, its own KDoc saying
 * the chain "enables diff, restore, and lineage", and nothing read it.
 *
 * So the storing is not the interesting part and is barely tested here. The diff
 * is.
 */
@RunWith(RobolectricTestRunner::class)
class CreativeAnalysisStoreTest {

    private lateinit var db: MemoryDatabase
    private lateinit var store: CreativeAnalysisStore

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            MemoryDatabase::class.java,
        ).allowMainThreadQueries().build()
        store = CreativeAnalysisStore(db.creativeAnalysisDao(), db.creativeRevisionDao())
    }

    @After
    fun tearDown() = db.close()

    /** A revision chain: `first` <- `second`. */
    private suspend fun chain(): Pair<String, String> {
        // The parents first: artifacts and branches are FK-bound to a project,
        // and Room enforces that in-memory exactly as it does on a device.
        db.creativeProjectDao().upsert(CreativeProjectEntity(id = "p1", name = "Ashfall"))
        db.creativeBranchDao().upsert(
            CreativeBranchEntity(id = "b1", projectId = "p1", name = "main"),
        )
        db.creativeArtifactDao().upsert(
            CreativeArtifactEntity(id = "a1", projectId = "p1", branchId = "b1", kind = "draft", title = "Ashfall"),
        )
        db.creativeRevisionDao().upsert(
            CreativeRevisionEntity(id = "r1", artifactId = "a1", branchId = "b1", contentText = "draft one"),
        )
        db.creativeRevisionDao().upsert(
            CreativeRevisionEntity(
                id = "r2", artifactId = "a1", branchId = "b1",
                parentRevisionId = "r1", contentText = "draft two",
            ),
        )
        return "r1" to "r2"
    }

    private fun report(vararg scores: Pair<String, Int>) = TensionReport(
        scenes = scores.map { (label, t) -> SceneScore(label = label, tension = t) },
        diagnosis = "d",
    )

    @Test
    fun `a rewrite that lifted a flat scene says so, scene by scene`() = runBlocking {
        val (first, second) = chain()
        store.saveTension(first, "a1", report("Scene 1" to 5, "Scene 2" to 7, "Scene 4" to 3))
        store.saveTension(second, "a1", report("Scene 1" to 5, "Scene 2" to 7, "Scene 4" to 6))

        val diff = store.diffAgainstParent(second)!!

        assertEquals("r1", diff.parentRevisionId)
        assertTrue(diff.improved)
        val moved = diff.moved()
        assertEquals(1, moved.size, "only one scene changed; the rest reported movement they did not have")
        assertEquals("Scene 4", moved.single().label)
        assertEquals(3, moved.single().before)
        assertEquals(6, moved.single().after)
        assertEquals(3, moved.single().change)
    }

    /**
     * The failure mode that makes a diff useless: matching scenes by position.
     * Inserting one scene early shifts every later index by one and reports the
     * whole back half of the manuscript as rewritten when none of it moved.
     */
    @Test
    fun `inserting a scene does not report every later scene as changed`() = runBlocking {
        val (first, second) = chain()
        store.saveTension(first, "a1", report("Opening" to 4, "Chase" to 8, "Aftermath" to 5))
        store.saveTension(
            second,
            "a1",
            report("Opening" to 4, "New quiet beat" to 2, "Chase" to 8, "Aftermath" to 5),
        )

        val diff = store.diffAgainstParent(second)!!

        assertEquals(
            listOf("New quiet beat"),
            diff.moved().map { it.label },
            "matching by index instead of label reported unchanged scenes as rewritten",
        )
        assertTrue(diff.scenes.first { it.label == "New quiet beat" }.isNew)
    }

    @Test
    fun `a deleted scene is reported as gone, not as a drop to zero`() = runBlocking {
        val (first, second) = chain()
        store.saveTension(first, "a1", report("Opening" to 4, "Filler" to 2))
        store.saveTension(second, "a1", report("Opening" to 4))

        val gone = store.diffAgainstParent(second)!!.scenes.single { it.label == "Filler" }

        assertTrue(gone.isGone)
        assertNull(gone.after)
    }

    /**
     * "Nothing changed" and "nothing to compare against" are different answers,
     * and a list of zeroes would be read as the first.
     */
    @Test
    fun `no parent and no prior analysis both give null rather than an empty diff`() = runBlocking {
        val (first, second) = chain()

        store.saveTension(first, "a1", report("Scene 1" to 5))
        assertNull(store.diffAgainstParent(first), "the root revision has nothing to compare to")

        store.saveTension(second, "a1", report("Scene 1" to 6))
        assertTrue(store.diffAgainstParent(second) != null)

        db.creativeAnalysisDao().deleteAll()
        store.saveTension(second, "a1", report("Scene 1" to 6))
        assertNull(store.diffAgainstParent(second), "the parent was never analysed; there is no basis")
    }

    @Test
    fun `scenes that were flat and stayed flat are the notes nobody acted on`() = runBlocking {
        val (first, second) = chain()
        store.saveTension(first, "a1", report("Scene 1" to 2, "Scene 2" to 8))
        store.saveTension(second, "a1", report("Scene 1" to 3, "Scene 2" to 9))

        assertEquals(listOf("Scene 1"), store.diffAgainstParent(second)!!.stillFlat().map { it.label })
    }

    @Test
    fun `the headline is the mean, and the trend reads newest first`() = runBlocking {
        val (first, second) = chain()
        store.saveTension(first, "a1", report("a" to 2, "b" to 4), now = 1_000)
        store.saveTension(second, "a1", report("a" to 6, "b" to 8), now = 2_000)

        assertEquals(listOf(7f, 3f), store.trend("a1"))
    }

    @Test
    fun `an empty report has a zero mean rather than dividing by nothing`() {
        assertEquals(0f, TensionReport().meanTension)
    }
}
