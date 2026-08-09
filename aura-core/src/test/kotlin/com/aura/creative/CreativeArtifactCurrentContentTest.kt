package com.aura.creative

import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Guards the revision the model is shown.
 *
 * `CreativeEngine.buildMessages` read `revisionsForArtifact(id).lastOrNull()`,
 * and `CreativeRevisionDao.forArtifact` is `ORDER BY createdAt DESC` — so
 * "last" was the **oldest** revision. Invisible until now because nothing in
 * production ever called `revise()`: every artifact had exactly one revision, so
 * first and last were the same row. The moment a manuscript starts accumulating
 * revisions, that bug feeds the model scene one forever.
 *
 * Every case below therefore uses **two or more** revisions, which is the state
 * no existing test produced.
 */
class CreativeArtifactCurrentContentTest {

    private val artifactDao = mockk<CreativeArtifactDao>(relaxed = true)
    private val revisionDao = mockk<CreativeRevisionDao>(relaxed = true)
    private val branchDao = mockk<CreativeBranchDao>(relaxed = true)
    private val store = CreativeArtifactStore(artifactDao, revisionDao, branchDao)

    private fun artifact(currentRevisionId: String?, preview: String = "preview") =
        CreativeArtifactEntity(
            id = "a1",
            projectId = "p1",
            branchId = "b1",
            kind = "scene",
            title = "Scene 1",
            currentRevisionId = currentRevisionId,
            previewText = preview,
            createdAt = 1L,
            updatedAt = 1L,
        )

    private fun revision(id: String, text: String, createdAt: Long) =
        CreativeRevisionEntity(
            id = id,
            artifactId = "a1",
            branchId = "b1",
            contentText = text,
            createdAt = createdAt,
        )

    @Test
    fun `returns the current revision, not the oldest one`() = runTest {
        coEvery { artifactDao.getById("a1") } returns artifact(currentRevisionId = "r3")
        coEvery { revisionDao.getById("r3") } returns revision("r3", "the newest draft", 300L)

        assertEquals("the newest draft", store.currentContent("a1"))
    }

    /**
     * The specific shape of the old bug: newest-first ordering means index 0 is
     * current and `lastOrNull()` is the first draft ever written.
     */
    @Test
    fun `ordering of the revision list cannot change the answer`() = runTest {
        val newest = revision("r3", "third", 300L)
        val oldest = revision("r1", "first", 100L)
        coEvery { artifactDao.getById("a1") } returns artifact(currentRevisionId = "r3")
        coEvery { revisionDao.getById("r3") } returns newest
        // DAO order, newest first — exactly what forArtifact returns.
        coEvery { revisionDao.forArtifact("a1") } returns listOf(newest, revision("r2", "second", 200L), oldest)

        val content = assertEquals("third", store.currentContent("a1"))
        // And prove the trap is real: the old expression would have said "first".
        assertEquals("first", store.revisionsForArtifact("a1").lastOrNull()?.contentText)
        content
    }

    /**
     * A fast loop writes scenes within the same millisecond. Any answer derived
     * from `createdAt` ordering would be arbitrary here; resolving by id is not.
     */
    @Test
    fun `identical timestamps do not make the answer arbitrary`() = runTest {
        coEvery { artifactDao.getById("a1") } returns artifact(currentRevisionId = "r2")
        coEvery { revisionDao.getById("r2") } returns revision("r2", "the one pointed at", 500L)
        coEvery { revisionDao.forArtifact("a1") } returns listOf(
            revision("r1", "same instant, wrong row", 500L),
            revision("r2", "the one pointed at", 500L),
        )

        assertEquals("the one pointed at", store.currentContent("a1"))
    }

    @Test
    fun `falls back to the preview when the revision is missing`() = runTest {
        coEvery { artifactDao.getById("a1") } returns artifact(currentRevisionId = "gone", preview = "preview text")
        coEvery { revisionDao.getById("gone") } returns null

        assertEquals("preview text", store.currentContent("a1"))
    }

    @Test
    fun `an unknown artifact yields null rather than an empty string`() = runTest {
        coEvery { artifactDao.getById("nope") } returns null
        assertNull(store.currentContent("nope"))
    }
}
