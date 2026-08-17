package com.aura.backup

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.aura.memory.MemoryDatabase
import com.aura.projects.ProjectEntity
import com.aura.projects.ProjectNoteEntity
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Schema v27 survives the trip out and back.
 *
 * The ledger is the one export whose loss would be silent: a restored device
 * would show every project reading as brand new, with all the conversations that
 * built it still present, and nothing to indicate anything was dropped.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class ProjectBackupRoundTripTest {

    private lateinit var db: MemoryDatabase
    private val json = Json { ignoreUnknownKeys = true }

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            MemoryDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() = db.close()

    private val project = ProjectEntity(
        id = "p1",
        name = "ARC-AGI-2",
        description = "7B targeting 95%",
        status = ProjectEntity.STATUS_ACTIVE,
        lastTurnAt = 500L,
        turnCount = 12,
        createdAt = 100L,
        updatedAt = 500L,
    )

    private val superseded = ProjectNoteEntity(
        id = "n1",
        projectId = "p1",
        kind = ProjectNoteEntity.KIND_DECISION,
        subject = "router",
        body = "going with the MoR router",
        sourceConversationId = "c1",
        sourceTurnAt = 200L,
        state = ProjectNoteEntity.STATE_SUPERSEDED,
        supersededBy = "n2",
        createdAt = 200L,
    )

    private val current = ProjectNoteEntity(
        id = "n2",
        projectId = "p1",
        kind = ProjectNoteEntity.KIND_DECISION,
        subject = "router",
        body = "three-wisdom architecture instead",
        sourceConversationId = "c2",
        sourceTurnAt = 400L,
        createdAt = 400L,
    )

    @Test
    fun `a project survives entity to backup to entity unchanged`() {
        assertEquals(project, project.toBackup().toEntity())
    }

    @Test
    fun `a note survives entity to backup to entity unchanged`() {
        assertEquals(superseded, superseded.toBackup().toEntity())
        assertEquals(current, current.toBackup().toEntity())
    }

    @Test
    fun `the superseded pointer survives json`() {
        val encoded = json.encodeToString(superseded.toBackup())
        val decoded = json.decodeFromString<ProjectNoteBackup>(encoded).toEntity()
        assertEquals(
            "supersededBy is what makes the ledger a history rather than a list",
            "n2",
            decoded.supersededBy,
        )
        assertEquals(ProjectNoteEntity.STATE_SUPERSEDED, decoded.state)
    }

    /**
     * The whole reason superseded rows are exported.
     *
     * Restoring only active notes would produce a project that has never changed
     * its mind — which reads as a complete history and is not one.
     */
    @Test
    fun `history is restored, not just the current answer`() {
        runBlocking {
            db.projectDao().upsertAll(listOf(project.toBackup().toEntity()))
            db.projectNoteDao().upsertAll(
                listOf(superseded, current).map { it.toBackup().toEntity() },
            )

            val active = db.projectNoteDao().activeFor("p1")
            assertEquals(1, active.size)
            assertEquals("three-wisdom architecture instead", active.single().body)

            val history = db.projectNoteDao().historyFor("p1", ProjectNoteEntity.KIND_DECISION, "router")
            assertEquals("both sides of the decision have to come back", 2, history.size)
        }
    }

    /**
     * `project_notes` has a CASCADE foreign key into `projects`, and Room
     * restores with `PRAGMA foreign_keys = ON`. Writing the notes first rejects
     * every one of them, which is why `writeEverything` writes projects first.
     */
    @Test
    fun `notes written before their project are rejected, which is why order is fixed`() {
        runBlocking {
            val threw = runCatching {
                db.projectNoteDao().upsertAll(listOf(current.toBackup().toEntity()))
            }.isFailure
            assertTrue(
                "if this ever stops throwing, the restore order in writeEverything " +
                    "is no longer load-bearing and its comment is wrong",
                threw,
            )

            db.projectDao().upsertAll(listOf(project.toBackup().toEntity()))
            db.projectNoteDao().upsertAll(listOf(current.toBackup().toEntity()))
            assertNotNull(db.projectNoteDao().byId("n2"))
        }
    }

    @Test
    fun `purging notes before projects leaves nothing behind`() {
        runBlocking {
            db.projectDao().upsertAll(listOf(project.toBackup().toEntity()))
            db.projectNoteDao().upsertAll(listOf(superseded, current).map { it.toBackup().toEntity() })

            db.projectNoteDao().deleteAll()
            db.projectDao().deleteAll()

            assertTrue(db.projectNoteDao().allForBackup().isEmpty())
            assertTrue(db.projectDao().allForBackup().isEmpty())
        }
    }
}
