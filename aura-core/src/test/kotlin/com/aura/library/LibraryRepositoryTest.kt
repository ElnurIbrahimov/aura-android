package com.aura.library

import com.aura.creative.CreativeArtifactDao
import com.aura.creative.CreativeArtifactEntity
import com.aura.documents.DocumentDao
import com.aura.documents.DocumentEntity
import com.aura.media.GeneratedMediaDao
import com.aura.media.GeneratedMediaEntity
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * One list of everything Aura has made.
 *
 * The parts were all there and none of them met: creative artifacts in one table with
 * `kind`, `title`, `mimeType` and revisions; imported documents in another; generated
 * images in a third, added alongside this. Each had a screen of its own or no screen at
 * all, so anything Aura produced dissolved into whichever feature happened to make it.
 *
 * Deliberately a read-only union rather than a new `artifacts` table. Every producer
 * already persists correctly and carries richer per-type fields than a shared table could
 * hold; a second table would mean dual writes at three call sites and two records free to
 * disagree about the same object. The Library is a view.
 */
class LibraryRepositoryTest {

    private fun repo(
        media: List<GeneratedMediaEntity> = emptyList(),
        docs: List<DocumentEntity> = emptyList(),
        artifacts: List<CreativeArtifactEntity> = emptyList(),
    ): LibraryRepository {
        val mediaDao = mockk<GeneratedMediaDao>(relaxed = true)
        val docDao = mockk<DocumentDao>(relaxed = true)
        val artifactDao = mockk<CreativeArtifactDao>(relaxed = true)
        coEvery { mediaDao.recent(any()) } returns media
        coEvery { docDao.allForBackup() } returns docs
        coEvery { artifactDao.allForBackup() } returns artifacts
        return LibraryRepository(mediaDao, docDao, artifactDao)
    }

    private fun image(id: String, prompt: String, at: Long) = GeneratedMediaEntity(
        id = id, kind = "image", prompt = prompt, storageUri = "file:///m/$id.png", createdAt = at,
    )

    private fun doc(id: String, name: String, at: Long) = DocumentEntity(
        id = id, name = name, mimeType = "application/pdf", sourceUri = "content://$id",
        importedAt = at, characterCount = 100, chunkCount = 2,
    )

    private fun artifact(id: String, title: String, kind: String, at: Long) = CreativeArtifactEntity(
        id = id, projectId = "p1", branchId = "b1", kind = kind, title = title,
        createdAt = at, updatedAt = at,
    )

    @Test
    fun `it gathers all three sources into one list`() = runTest {
        val items = repo(
            media = listOf(image("m1", "a cat", 300)),
            docs = listOf(doc("d1", "lease.pdf", 200)),
            artifacts = listOf(artifact("a1", "Chapter One", "scene", 100)),
        ).all()

        assertEquals(3, items.size)
        assertEquals(setOf("m1", "d1", "a1"), items.map { it.id }.toSet())
    }

    @Test
    fun `newest first, across sources`() = runTest {
        // The whole value of a union is one timeline. Sorting per-source and concatenating
        // would put every image above every document regardless of when either was made.
        val items = repo(
            media = listOf(image("m1", "a cat", 200)),
            docs = listOf(doc("d1", "lease.pdf", 300)),
            artifacts = listOf(artifact("a1", "Chapter One", "scene", 100)),
        ).all()

        assertEquals(listOf("d1", "m1", "a1"), items.map { it.id })
    }

    @Test
    fun `each item carries what a list row needs to render`() = runTest {
        val items = repo(media = listOf(image("m1", "a cat wearing a hat", 300))).all()

        val item = items.single()
        assertEquals(LibraryKind.IMAGE, item.kind)
        assertEquals("a cat wearing a hat", item.title, "the prompt is the only title a user recognises")
        assertEquals("file:///m/m1.png", item.previewUri)
        assertEquals(300, item.createdAt)
    }

    @Test
    fun `an unknown artifact kind is kept rather than dropped`() = runTest {
        // `kind` on a creative artifact is a caller-supplied string, so the set is open.
        // Dropping what it does not recognise would make the Library quietly incomplete —
        // the exact failure it exists to fix.
        val items = repo(artifacts = listOf(artifact("a1", "Something New", "storyboard", 100))).all()

        assertEquals(1, items.size)
        assertEquals(LibraryKind.OTHER, items.single().kind)
        assertTrue("storyboard" in items.single().subtitle, "the real kind should survive as the subtitle")
    }

    @Test
    fun `one broken source does not empty the library`() = runTest {
        // Three reads, three chances to throw. A Library that shows nothing because the
        // document table is locked is worse than one that shows the images and says so.
        val mediaDao = mockk<GeneratedMediaDao>(relaxed = true)
        val docDao = mockk<DocumentDao>(relaxed = true)
        val artifactDao = mockk<CreativeArtifactDao>(relaxed = true)
        coEvery { mediaDao.recent(any()) } returns listOf(image("m1", "a cat", 300))
        coEvery { docDao.allForBackup() } throws IllegalStateException("database is locked")
        coEvery { artifactDao.allForBackup() } returns emptyList()

        val items = LibraryRepository(mediaDao, docDao, artifactDao).all()

        assertEquals(listOf("m1"), items.map { it.id })
    }

    @Test
    fun `filtering by kind keeps only that kind`() = runTest {
        val repo = repo(
            media = listOf(image("m1", "a cat", 300)),
            docs = listOf(doc("d1", "lease.pdf", 200)),
        )

        assertEquals(listOf("m1"), repo.all(kind = LibraryKind.IMAGE).map { it.id })
        assertEquals(listOf("d1"), repo.all(kind = LibraryKind.DOCUMENT).map { it.id })
    }
}
