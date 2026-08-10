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
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Creative persistence against **real SQLite**, with foreign keys enforced.
 *
 * Every other test of this area mocks its DAOs, and mocked DAOs enforce no
 * constraints — which is precisely why two bugs lived here undetected until the
 * feature ran on a phone:
 *
 * 1. [CreativeArtifactStore.create] inserted the revision before the artifact it
 *    foreign-keys to, so every call died with `SQLITE_CONSTRAINT_FOREIGNKEY`. No
 *    artifact had ever been written to a real database.
 * 2. [CreativeProjectStore.updateWorld] used `@Insert(REPLACE)`, which SQLite
 *    implements as DELETE-then-INSERT. Three tables CASCADE from
 *    `creative_projects`, so saving the world silently deleted every artifact,
 *    revision, branch and job the project owned. A thirteen-scene run finished
 *    with thirteen beats marked "drafted" and zero rows behind them.
 *
 * Both are invisible to a mock and unmissable here. That is the whole point of
 * this file.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class CreativePersistenceContractTest {

    private lateinit var db: MemoryDatabase
    private lateinit var projectStore: CreativeProjectStore
    private lateinit var artifactStore: CreativeArtifactStore
    private lateinit var branchStore: CreativeBranchStore

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, MemoryDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        projectStore = CreativeProjectStore(db.creativeProjectDao())
        artifactStore = CreativeArtifactStore(
            db.creativeArtifactDao(),
            db.creativeRevisionDao(),
            db.creativeBranchDao(),
        )
        branchStore = CreativeBranchStore(db.creativeBranchDao())
    }

    @After
    fun tearDown() = db.close()

    private suspend fun seedProject(): CreativeProject =
        projectStore.create("Lighthouse", "A keeper who cannot swim", "literary", "spare", "novel")

    private suspend fun seedScene(project: CreativeProject, title: String = "Scene 1"): String {
        val branchId = branchStore.createMainBranch(project.id).id
        return artifactStore.create(
            projectId = project.id,
            branchId = branchId,
            kind = "scene",
            title = title,
            initialContent = "The beam died at midnight.",
            authorKind = "generation",
        ).id
    }

    /** Bug 1. Fails with SQLITE_CONSTRAINT_FOREIGNKEY if the revision is written first. */
    @Test
    fun `an artifact and its first revision both persist`() = runBlocking {
        val project = seedProject()
        val artifactId = seedScene(project)

        assertNotNull(artifactStore.get(artifactId), "the artifact row should exist")
        assertEquals(1, artifactStore.revisionsForArtifact(artifactId).size)
        assertEquals("The beam died at midnight.", artifactStore.currentContent(artifactId))
    }

    /**
     * Bug 2, and the exact shape the device produced: draft a scene, mark the
     * beat, and find the scene gone.
     */
    @Test
    fun `saving the world does not delete the project's artifacts`() = runBlocking {
        val project = seedProject()
        val artifactId = seedScene(project)

        projectStore.updateWorld(
            project.id,
            project.world.copy(
                outline = listOf(StoryBeat(title = "The Keeper's Vigil", status = "drafted", artifactId = artifactId)),
            ),
        )

        assertNotNull(artifactStore.get(artifactId), "the artifact must survive a world save")
        assertEquals(1, artifactStore.revisionsForArtifact(artifactId).size, "its revision must survive too")
        assertTrue(branchStore.forProject(project.id).isNotEmpty(), "the branch must survive")
    }

    /** The whole loop: thirteen scenes, each followed by a world save, as the run does it. */
    @Test
    fun `a multi-scene run keeps every scene it wrote`() = runBlocking {
        val project = seedProject()
        val branchId = branchStore.createMainBranch(project.id).id
        var world = project.world

        repeat(13) { i ->
            val artifact = artifactStore.create(
                projectId = project.id,
                branchId = branchId,
                kind = "scene",
                title = "${i + 1}. Beat ${i + 1}",
                initialContent = "Scene ${i + 1} text.",
                authorKind = "generation",
            )
            world = world.copy(
                outline = world.outline + StoryBeat(
                    title = "Beat ${i + 1}",
                    status = "drafted",
                    artifactId = artifact.id,
                ),
            )
            projectStore.updateWorld(project.id, world)
        }

        val scenes = artifactStore.forProjectByKind(project.id, "scene")
        assertEquals(13, scenes.size, "every drafted scene must still exist")
        // And every beat's artifactId must resolve — a beat naming a row that
        // is gone is what the device showed.
        val reloaded = assertNotNull(projectStore.get(project.id))
        for (beat in reloaded.world.outline) {
            assertNotNull(
                artifactStore.get(beat.artifactId),
                "beat '${beat.title}' names artifact ${beat.artifactId}, which no longer exists",
            )
        }
    }

    /** Metadata edits are the other update path through the same DAO. */
    @Test
    fun `editing project details does not delete its artifacts`() = runBlocking {
        val project = seedProject()
        val artifactId = seedScene(project)

        projectStore.updateProject(project.id, "Renamed", "New premise", "horror", "bleak", "novel")

        assertNotNull(artifactStore.get(artifactId), "the artifact must survive a metadata edit")
        assertEquals("Renamed", assertNotNull(projectStore.get(project.id)).name)
    }

    @Test
    fun `incrementing the turn counter does not delete artifacts`() = runBlocking {
        val project = seedProject()
        val artifactId = seedScene(project)

        projectStore.incrementTurn(project.id)

        assertNotNull(artifactStore.get(artifactId))
        assertEquals(1, assertNotNull(projectStore.get(project.id)).turnCount)
    }

    /** Deleting a project *should* still cascade — the constraint is right, its use was not. */
    @Test
    fun `deleting a project still removes its artifacts`() = runBlocking {
        val project = seedProject()
        val artifactId = seedScene(project)

        projectStore.delete(project.id)

        assertEquals(null, artifactStore.get(artifactId), "a real delete should still cascade")
    }
}
