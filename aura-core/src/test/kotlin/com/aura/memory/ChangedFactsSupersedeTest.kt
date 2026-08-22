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

    @Test
    fun `containment is checked against every neighbour, not just the newest`() = runTest {
        // The first cut asked firstOrNull, so the guard only ever saw the most
        // recently written neighbour. A richer row one position further down was
        // invisible: the terser restatement was stored anyway AND retired a
        // different memory, leaving the fuller wording live beside a redundant
        // copy of itself. Both halves of that are the opposite of the intent.
        val dao = mockk<MemoryDao>(relaxed = true)
        coEvery { dao.existsByContent(any()) } returns 0
        // recentWithEmbeddings orders newest first, so the row that contains the
        // candidate is deliberately NOT the one the old code would have looked at.
        coEvery { dao.recentWithEmbeddings(any()) } returns listOf(
            neighbour("newer", "Dark mode preferred, always"),
            neighbour("older", "I prefer dark mode because of eye strain"),
        )

        val newId = store(dao).maybeStore(
            "i prefer dark mode",
            "user",
            category = "preference",
            importance = 0.8f,
        )

        assertNull(newId, "a live row already says this and more")
        coVerify(exactly = 0) { dao.insert(any()) }
        coVerify(exactly = 0) { dao.retire(any(), any(), any(), any()) }
    }

    @Test
    fun `every contradicted neighbour is retired, not only the newest`() = runTest {
        // More than one live row saying nearly the same thing is the normal case
        // here, not the corner: the merge-by-length branch this replaces spent the
        // app's whole history producing exactly that. Retiring one of three would
        // leave recall still answering with two stale phrasings after being told
        // the preference changed — the promise, unkept, on the only installs that
        // have been running long enough to care.
        val dao = mockk<MemoryDao>(relaxed = true)
        coEvery { dao.existsByContent(any()) } returns 0
        coEvery { dao.recentWithEmbeddings(any()) } returns listOf(
            neighbour("dup1", "I prefer dark mode"),
            neighbour("dup2", "I like dark mode"),
            neighbour("dup3", "Dark mode, always"),
        )

        val newId = store(dao).maybeStore("I prefer light mode", "user")

        assertNotNull(newId)
        for (stale in listOf("dup1", "dup2", "dup3")) {
            coVerify(exactly = 1) { dao.retire(stale, newId, REASON_SUPERSEDED, any()) }
        }
    }

    @Test
    fun `one failed retirement does not strand the others`() = runTest {
        // Retirements are caught individually. Sharing one runCatching across the
        // loop would let the first failure skip every neighbour after it, which is
        // the same staleness the loop exists to clear, arrived at by accident.
        val dao = mockk<MemoryDao>(relaxed = true)
        coEvery { dao.existsByContent(any()) } returns 0
        coEvery { dao.recentWithEmbeddings(any()) } returns listOf(
            neighbour("bad", "I prefer dark mode"),
            neighbour("good", "I like dark mode"),
        )
        coEvery { dao.retire("bad", any(), any(), any()) } throws IllegalStateException("db locked")

        val newId = store(dao).maybeStore("I prefer light mode", "user")

        assertNotNull(newId)
        coVerify(exactly = 1) { dao.retire("good", newId, REASON_SUPERSEDED, any()) }
    }
}
