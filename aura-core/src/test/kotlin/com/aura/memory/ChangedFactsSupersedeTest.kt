package com.aura.memory

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * A fact that changed must leave an end date behind, not be overwritten.
 *
 * `maybeStore` resolved a near neighbour by **length**: it kept whichever text
 * had more characters and wrote it over the other in place. "I prefer dark
 * mode" and "I prefer light mode" are near-identical to an embedder — same
 * subject, opposite value — so a changed preference was decided by character
 * count and the change left no trace at all. Nothing tested that branch, which
 * is a fair part of why it lasted.
 *
 * The machinery to do it properly was already present and had no automatic
 * caller: `retire(supersededBy, reason)` is what `CorrectionStore` runs when the
 * user says a fact has changed. Now the store runs it too.
 *
 * Every test here fixes the embedding rather than the text, because the
 * embedder's job — deciding these two are about the same subject — is not what
 * is under test. What is under test is the decision taken once it has.
 */
class ChangedFactsSupersedeTest {

    private val memoryEditDao = mockk<MemoryEditDao>(relaxed = true)
    private val memoryFeedbackDao = mockk<MemoryFeedbackDao>(relaxed = true)

    /**
     * Scores every pair of texts as identical.
     *
     * `FakeEmbedder` hashes the text, so two different sentences land far apart
     * and the near-neighbour branch is unreachable from a unit test. Pinning the
     * vector puts every pair above the threshold and leaves the content as the
     * only variable, which is exactly the axis these tests vary.
     */
    private class ConstantEmbedder : Embedder {
        override suspend fun embed(text: String) = FloatArray(384) { 0.05f }
        override fun modelId() = "constant-test-embedder"
        override fun dimension() = 384
    }

    private fun neighbour(id: String, content: String) = MemoryEntity(
        id = id,
        content = content,
        source = "user",
        category = "preference",
        embedding = Embedder.toBytes(FloatArray(384) { 0.05f }),
        embeddingModel = "constant-test-embedder",
    )

    private fun store(dao: MemoryDao) = MemoryStore(
        dao,
        ConstantEmbedder(),
        WriteGate(),
        memoryEditDao,
        memoryFeedbackDao,
    )

    @Test
    fun `a contradicting statement supersedes the one it replaces`() = runTest {
        val dao = mockk<MemoryDao>(relaxed = true)
        coEvery { dao.existsByContent(any()) } returns 0
        coEvery { dao.recentWithEmbeddings(any()) } returns
            listOf(neighbour("old", "I prefer dark mode"))
        val inserted = slot<MemoryEntity>()
        coEvery { dao.insert(capture(inserted)) } returns Unit

        val newId = store(dao).maybeStore("I prefer light mode", "user")

        assertNotNull(newId, "the new statement must be stored, not dropped")
        assertEquals("I prefer light mode", inserted.captured.content)
        coVerify(exactly = 1) {
            dao.retire("old", newId, REASON_SUPERSEDED, any())
        }
    }

    @Test
    fun `the superseded row is retired, never overwritten`() = runTest {
        // The old behaviour wrote the new text over the old row. That is what
        // made the change untraceable: one row, one wording, no history and
        // nothing to undo.
        val dao = mockk<MemoryDao>(relaxed = true)
        coEvery { dao.existsByContent(any()) } returns 0
        coEvery { dao.recentWithEmbeddings(any()) } returns
            listOf(neighbour("old", "I prefer dark mode"))

        store(dao).maybeStore("I prefer light mode", "user")

        coVerify(exactly = 0) { dao.update(any()) }
    }

    @Test
    fun `a terser restatement is skipped rather than allowed to displace the fuller one`() = runTest {
        // The containment guard. Without it, newer-wins would let "working on
        // ARC-AGI-2" retire the row that also says what the target is.
        val dao = mockk<MemoryDao>(relaxed = true)
        coEvery { dao.existsByContent(any()) } returns 0
        coEvery { dao.recentWithEmbeddings(any()) } returns listOf(
            neighbour("old", "Working on ARC-AGI-2, targeting 95% with a 7B model"),
        )

        // Category and importance supplied, as the agentic loop's LLM write
        // gate supplies them. Without that the heuristic gate can refuse the
        // content outright and return null before the dedup scan is reached —
        // which is a null for the wrong reason, and a test that passes with the
        // guard deleted. The scan assertion below pins that it was reached.
        val newId = store(dao).maybeStore(
            "working on ARC-AGI-2",
            "user",
            category = "project",
            importance = 0.6f,
        )

        coVerify(exactly = 1) { dao.recentWithEmbeddings(any()) }
        assertNull(newId, "a restatement that adds nothing should not be stored")
        coVerify(exactly = 0) { dao.insert(any()) }
        coVerify(exactly = 0) { dao.retire(any(), any(), any(), any()) }
    }

    @Test
    fun `containment ignores case and spacing`() = runTest {
        // A restatement rarely arrives byte-identical, and a guard that only
        // caught exact substrings would supersede on a capital letter.
        val dao = mockk<MemoryDao>(relaxed = true)
        coEvery { dao.existsByContent(any()) } returns 0
        coEvery { dao.recentWithEmbeddings(any()) } returns listOf(
            neighbour("old", "I  prefer   Dark Mode because of eye strain"),
        )

        val newId = store(dao).maybeStore(
            "i prefer dark mode",
            "user",
            category = "preference",
            importance = 0.8f,
        )

        coVerify(exactly = 1) { dao.recentWithEmbeddings(any()) }
        assertNull(newId)
        coVerify(exactly = 0) { dao.retire(any(), any(), any(), any()) }
    }

    @Test
    fun `an unrelated memory retires nothing`() = runTest {
        // Nothing in the scan means nothing to supersede — the ordinary path,
        // which must not have acquired a retirement.
        val dao = mockk<MemoryDao>(relaxed = true)
        coEvery { dao.existsByContent(any()) } returns 0
        coEvery { dao.recentWithEmbeddings(any()) } returns emptyList()

        val newId = store(dao).maybeStore("I prefer light mode", "user")

        assertNotNull(newId)
        coVerify(exactly = 0) { dao.retire(any(), any(), any(), any()) }
    }

    @Test
    fun `a failed retirement still keeps the memory that was just stored`() = runTest {
        // Ordering matters: the new row is written first, so a failure here
        // costs the supersession and not the fact. Two live rows saying similar
        // things is what the dedup existed to tidy, not what it existed to
        // prevent.
        val dao = mockk<MemoryDao>(relaxed = true)
        coEvery { dao.existsByContent(any()) } returns 0
        coEvery { dao.recentWithEmbeddings(any()) } returns
            listOf(neighbour("old", "I prefer dark mode"))
        coEvery { dao.retire(any(), any(), any(), any()) } throws IllegalStateException("db locked")

        val newId = store(dao).maybeStore("I prefer light mode", "user")

        assertNotNull(newId, "the store must survive a failed retirement")
        coVerify(exactly = 1) { dao.insert(any()) }
    }
}
