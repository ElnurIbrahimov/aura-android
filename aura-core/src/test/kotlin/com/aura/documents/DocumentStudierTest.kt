package com.aura.documents

import com.aura.providers.ChatOptions
import com.aura.providers.ProviderChunk
import com.aura.providers.ProviderRegistry
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DocumentStudierTest {

    private val registry = mockk<ProviderRegistry>(relaxed = true)
    private val studier = DocumentStudier(registry)

    private fun replies(vararg json: String) {
        var i = 0
        coEvery { registry.chat(any(), any(), any()) } answers {
            val body = json[minOf(i, json.lastIndex)]
            i++
            flowOf(ProviderChunk(text = body))
        }
    }

    /** Chunks sized so batching is predictable: 6 per 12,000-char batch. */
    private fun chunks(n: Int) = (1..n).map { "x".repeat(1_800) }

    private fun partsJson(range: IntRange, constraints: List<String> = emptyList()): String {
        val parts = range.joinToString(",") { """{"n":$it,"about":"subject of part $it"}""" }
        val cons = constraints.joinToString(",") { "\"$it\"" }
        return """{"parts":[$parts],"constraints":[$cons]}"""
    }

    // ---- batching ---------------------------------------------------------

    @Test
    fun `a long document is studied in batches, not one call per chunk`() = runBlocking {
        // 50 chunks is roughly a 15,000-word document. Fifty calls to describe
        // one file is not a study pass, it is a bill.
        replies(partsJson(1..6))

        studier.study("spec.md", chunks(50), "test-model")

        // 50 chunks x 1800 chars / 12000 per batch = 9 calls, not 50.
        coVerify(exactly = 9) { registry.chat(any(), any(), any()) }
    }

    @Test
    fun `part numbering continues across batch boundaries`() = runBlocking {
        replies(partsJson(1..6), partsJson(7..8))

        val outline = studier.study("spec.md", chunks(8), "test-model")!!

        assertEquals(8, outline.parts.size)
        assertEquals("subject of part 7", outline.parts[6])
        assertEquals("subject of part 8", outline.parts[7])
    }

    // ---- keeping the outline aligned with the document --------------------

    @Test
    fun `a batch that answers for only some parts does not shift the rest`() = runBlocking {
        // The outline is an index. A model that skips part 3 must leave a hole
        // at 3, not slide part 4 into its place — every line after it would
        // then point at the wrong passage.
        replies("""{"parts":[{"n":1,"about":"first"},{"n":3,"about":"third"}],"constraints":[]}""")

        val outline = studier.study("spec.md", chunks(3), "test-model")!!

        assertEquals(3, outline.parts.size)
        assertEquals("first", outline.parts[0])
        assertEquals("(part 2)", outline.parts[1])
        assertEquals("third", outline.parts[2])
    }

    @Test
    fun `a failed batch leaves placeholders rather than dropping its parts`() = runBlocking {
        replies("not json at all", partsJson(7..8))

        val outline = studier.study("spec.md", chunks(8), "test-model")!!

        assertEquals(8, outline.parts.size)
        assertTrue(outline.parts.take(6).all { it.startsWith("(part ") }, "got ${outline.parts.take(6)}")
        assertEquals("subject of part 7", outline.parts[6])
    }

    @Test
    fun `every batch failing yields no outline at all`() = runBlocking {
        // A list of apologies is not an index, and storing one would put a
        // memory in the way of the chunks that do work.
        replies("nonsense")

        assertNull(studier.study("spec.md", chunks(8), "test-model"))
    }

    // ---- constraints ------------------------------------------------------

    @Test
    fun `a rule restated in three sections is one rule`() = runBlocking {
        replies(
            partsJson(1..6, listOf("Never deploy on Friday", "Keep PRs under 400 lines")),
            partsJson(7..8, listOf("never deploy on friday", "Two approvals required")),
        )

        val outline = studier.study("spec.md", chunks(8), "test-model")!!

        assertEquals(3, outline.constraints.size, "got ${outline.constraints}")
        assertEquals(1, outline.constraints.count { it.lowercase().startsWith("never deploy") })
    }

    // ---- cost boundaries --------------------------------------------------

    @Test
    fun `the study pass is unattended and therefore budget-bounded`() = runBlocking {
        // Importing a large file must not be able to spend the day's ceiling by
        // accident, and it is not a call the user asked for directly.
        val options = slot<ChatOptions>()
        coEvery { registry.chat(any(), any(), capture(options)) } returns
            flowOf(ProviderChunk(text = partsJson(1..2)))

        studier.study("spec.md", chunks(2), "test-model")

        assertEquals(false, options.captured.attended)
    }

    @Test
    fun `a document past the ceiling is truncated rather than studied forever`() = runBlocking {
        replies(partsJson(1..6))

        val outline = studier.study("huge.md", chunks(400), "test-model")!!

        assertEquals(DocumentStudier.MAX_CHUNKS, outline.parts.size)
    }

    @Test
    fun `no chunks means no call`() = runBlocking {
        assertNull(studier.study("empty.md", emptyList(), "test-model"))
        coVerify(exactly = 0) { registry.chat(any(), any(), any()) }
    }

    // ---- rendering --------------------------------------------------------

    @Test
    fun `the rendered outline reads as a map of the document`() {
        val rendered = studier.render(
            "spec.md",
            DocumentStudier.Outline(
                parts = listOf("scope and audience", "the deployment rules"),
                constraints = listOf("Never deploy on Friday"),
            ),
        )

        assertTrue("Outline of spec.md (2 parts)" in rendered, rendered)
        assertTrue("1. scope and audience" in rendered, rendered)
        assertTrue("2. the deployment rules" in rendered, rendered)
        assertTrue("- Never deploy on Friday" in rendered, rendered)
    }

    @Test
    fun `an outline with no constraints omits the heading entirely`() {
        val rendered = studier.render(
            "notes.md",
            DocumentStudier.Outline(parts = listOf("a thought"), constraints = emptyList()),
        )

        assertTrue("Rules and constraints" !in rendered, rendered)
    }
}
