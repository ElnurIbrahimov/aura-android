package com.aura.creative

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.aura.memory.MemoryDatabase
import com.aura.memory.MemoryFtsSchema
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Repairing what the cascade already destroyed.
 *
 * Artifacts written while `creative_artifacts` was on `INSERT OR REPLACE` still
 * carry a `currentRevisionId` pointing at a revision the cascade deleted. The
 * DAO no longer cascades, so nothing new breaks — but nothing existing fixes
 * itself either, and a pointer into an empty table renders as an artifact whose
 * content will not open.
 *
 * Real SQLite, because the whole defect lives below the DAO seam that the other
 * creative tests mock.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class CreativeArtifactRepairTest {

    private lateinit var db: MemoryDatabase
    private lateinit var store: CreativeArtifactStore

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            MemoryDatabase::class.java,
        ).allowMainThreadQueries().addCallback(MemoryFtsSchema.triggerCallback).build()
        store = CreativeArtifactStore(db.creativeArtifactDao(), db.creativeRevisionDao(), db.creativeBranchDao())
    }

    @After
    fun tearDown() = db.close()

    private suspend fun seedProject() =
        db.creativeProjectDao().upsert(CreativeProjectEntity(id = "p1", name = "Novel"))

    private fun artifact(id: String, pointer: String?) = CreativeArtifactEntity(
        id = id,
        projectId = "p1",
        branchId = "main",
        kind = "text",
        title = "Scene",
        currentRevisionId = pointer,
        previewText = "stale preview",
    )

    @Test
    fun `an artifact with a surviving revision is re-pointed at the newest`() = runTest {
        seedProject()
        db.creativeArtifactDao().upsert(artifact("a1", pointer = "r-deleted"))
        db.creativeRevisionDao().upsert(
            CreativeRevisionEntity(id = "r-old", artifactId = "a1", branchId = "main", contentText = "older", createdAt = 1),
        )
        db.creativeRevisionDao().upsert(
            CreativeRevisionEntity(id = "r-new", artifactId = "a1", branchId = "main", contentText = "newest", createdAt = 2),
        )

        val report = store.repairDanglingRevisionPointers()

        val repaired = assertNotNull(db.creativeArtifactDao().getById("a1"))
        assertEquals("r-new", repaired.currentRevisionId)
        assertEquals("newest", repaired.previewText, "the preview must come from the revision it now points at")
        assertEquals(1, report.repointed)
        assertEquals(0, report.orphaned)
    }

    @Test
    fun `an artifact with nothing left is marked failed rather than left broken`() = runTest {
        seedProject()
        db.creativeArtifactDao().upsert(artifact("a1", pointer = "r-deleted"))

        val report = store.repairDanglingRevisionPointers()

        val repaired = assertNotNull(db.creativeArtifactDao().getById("a1"))
        assertNull(repaired.currentRevisionId, "a pointer to nothing is worse than no pointer")
        assertEquals("failed", repaired.status, "a visible orphan beats a row that silently will not open")
        assertEquals(1, report.orphaned)
    }

    @Test
    fun `a healthy artifact is left alone, and the repair is idempotent`() = runTest {
        seedProject()
        db.creativeArtifactDao().upsert(artifact("a1", pointer = "r1"))
        db.creativeRevisionDao().upsert(
            CreativeRevisionEntity(id = "r1", artifactId = "a1", branchId = "main", contentText = "content"),
        )

        assertEquals(0, store.repairDanglingRevisionPointers().touched)

        val untouched = assertNotNull(db.creativeArtifactDao().getById("a1"))
        assertEquals("stale preview", untouched.previewText, "a resolvable pointer must not be rewritten")
        assertEquals(0, store.repairDanglingRevisionPointers().touched, "running twice must change nothing")
    }
}
