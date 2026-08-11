package com.aura.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.aura.creative.CreativeArtifactEntity
import com.aura.creative.CreativeProjectEntity
import com.aura.creative.CreativeRevisionEntity
import com.aura.kg.EdgeEntity
import com.aura.kg.NodeEntity
import com.aura.memory.MemoryDatabase
import com.aura.memory.MemoryEditEntity
import com.aura.memory.MemoryEntity
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

/**
 * Re-saving a parent row must not delete its children.
 *
 * Every DAO test in this project mocks the DAO, which is precisely why
 * `INSERT OR REPLACE` on six CASCADE parents survived ten review passes: the
 * destruction happens inside SQLite, below the seam every test stubs out. These
 * cases run real SQLite and assert on the children.
 *
 * [CascadeParentReplaceAuditTest] bans the annotation across the tree; this
 * file proves the behaviour the ban exists to protect, on the parents whose
 * loss was worst — the knowledge graph, which was being truncated to a single
 * turn on every message, and creative artifacts, where writing a draft deleted
 * the draft.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class CascadeParentChildSurvivalTest {

    private lateinit var db: MemoryDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            MemoryDatabase::class.java,
        )
            .allowMainThreadQueries()
            .addCallback(MemoryFtsSchema.triggerCallback)
            .build()
    }

    @After
    fun tearDown() = db.close()

    // ---------------------------------------------------------------- kg_nodes

    @Test
    fun `re-saving a knowledge graph node keeps its edges`() = runTest {
        val kg = db.knowledgeGraphDao()
        val user = NodeEntity(id = "n-user", label = "user", type = "person")
        val topic = NodeEntity(id = "n-kotlin", label = "Kotlin", type = "topic")
        kg.insertNode(user)
        kg.insertNode(topic)
        kg.insertEdge(EdgeEntity(id = "e1", type = "knows", sourceId = user.id, targetId = topic.id))

        assertEquals(1, kg.allEdges().size, "precondition: the edge was written")

        // The extractor labels the user as `user` on essentially every turn, so
        // this is the ordinary path, not an edge case.
        kg.insertNode(user.copy(confidence = 0.95f, updatedAt = 999L))

        assertEquals(
            1,
            kg.allEdges().size,
            "re-saving a node must not cascade-delete the edges pointing at it",
        )
        assertEquals(0.95f, assertNotNull(kg.getNode(user.id)).confidence, "the update itself must still apply")
    }

    @Test
    fun `an edge survives a re-save of the node on either end`() = runTest {
        val kg = db.knowledgeGraphDao()
        val a = NodeEntity(id = "n-a", label = "A", type = "person")
        val b = NodeEntity(id = "n-b", label = "B", type = "topic")
        kg.insertNode(a)
        kg.insertNode(b)
        kg.insertEdge(EdgeEntity(id = "e1", type = "knows", sourceId = a.id, targetId = b.id))

        // kg_edges declares CASCADE on BOTH sourceId and targetId, so the
        // target end is a second, independent way to lose the same edge.
        kg.insertNode(b.copy(confidence = 0.99f))

        assertEquals(1, kg.allEdges().size, "re-saving the TARGET node must not delete the edge either")
    }

    // ------------------------------------------------------- creative artifacts

    @Test
    fun `writing a revision keeps every earlier revision of that artifact`() = runTest {
        val projects = db.creativeProjectDao()
        val artifacts = db.creativeArtifactDao()
        val revisions = db.creativeRevisionDao()

        projects.upsert(CreativeProjectEntity(id = "p1", name = "Novel"))
        val artifact = CreativeArtifactEntity(
            id = "a1",
            projectId = "p1",
            branchId = "main",
            kind = "text",
            title = "Scene 1",
        )
        artifacts.upsert(artifact)

        // Exactly what CreativeArtifactStore.addRevision does: write the
        // revision, then re-save the artifact to point at it. Under REPLACE the
        // second call deleted the revision written by the first.
        repeat(3) { i ->
            revisions.upsert(
                CreativeRevisionEntity(
                    id = "r$i",
                    artifactId = artifact.id,
                    branchId = "main",
                    contentText = "draft $i",
                    contentHash = "h$i",
                ),
            )
            artifacts.upsert(
                artifact.copy(currentRevisionId = "r$i", previewText = "draft $i", updatedAt = i.toLong()),
            )
        }

        assertEquals(
            3,
            revisions.forArtifact(artifact.id).size,
            "each re-save of the artifact must keep the revisions, including the one just written",
        )
        assertEquals("r2", assertNotNull(artifacts.getById("a1")).currentRevisionId)
    }

    @Test
    fun `re-saving a project keeps its artifacts`() = runTest {
        val projects = db.creativeProjectDao()
        val artifacts = db.creativeArtifactDao()

        val project = CreativeProjectEntity(id = "p1", name = "Novel")
        projects.upsert(project)
        artifacts.upsert(
            CreativeArtifactEntity(id = "a1", projectId = "p1", branchId = "main", kind = "text", title = "Scene 1"),
        )

        projects.upsert(project.copy(name = "Novel (working title)"))

        assertEquals(
            1,
            artifacts.allForProject("p1").size,
            "the thirteen-scenes incident recorded in CreativeProjectDao's KDoc, as a test",
        )
    }

    // ---------------------------------------------------------------- memories

    @Test
    fun `re-saving a memory keeps its edit history`() = runTest {
        val memories = db.memoryDao()
        val edits = db.memoryEditDao()
        val memory = MemoryEntity(id = "m1", content = "Elnur prefers Kotlin", source = "user", category = "preference")
        memories.insert(memory)
        edits.insert(
            MemoryEditEntity(
                memoryId = "m1",
                oldContent = "old",
                newContent = "Elnur prefers Kotlin",
                oldCategory = "fact",
                newCategory = "preference",
            ),
        )

        memories.insert(memory.copy(content = "Elnur prefers Kotlin and Compose"))

        assertEquals(
            1,
            edits.getForMemory("m1").size,
            "re-saving a memory must not cascade-delete its audit trail",
        )
    }
}
