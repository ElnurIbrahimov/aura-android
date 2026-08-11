package com.aura.kg

import com.aura.agent.Conversation
import com.aura.agent.ConversationStore
import com.aura.provenance.ConversationProvenance
import com.aura.tools.KnowledgeGraphTool
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Recovering a graph the cascade truncated.
 *
 * The conversations that produced the lost edges are still on disk; only
 * `kg_edges` was destroyed. This walks them and re-runs extraction, which is
 * only safe to offer because both KG writes are `@Upsert` now — under the old
 * REPLACE it would have rebuilt the graph and then destroyed it again on the
 * next turn.
 */
class KnowledgeGraphRebuilderTest {

    private val store: ConversationStore = mockk()
    private val tool: KnowledgeGraphTool = mockk()
    private val repository: KnowledgeGraphRepository = mockk(relaxed = true)
    private val rebuilder = KnowledgeGraphRebuilder(store, tool, repository)

    private fun conversation(id: String, vararg turns: Pair<String, String>) =
        turns.fold(Conversation(id = id)) { acc, (u, a) -> acc.addUser(u).addAssistant(a) }

    @Test
    fun `every turn of every conversation is re-extracted`() = runTest {
        coEvery { store.recent(any()) } returns listOf(
            conversation("c1", "who am I" to "you are Elnur", "what do I use" to "Kotlin"),
            conversation("c2", "and my phone" to "Android"),
        )
        coEvery { tool.extract(any()) } returns (listOf<KgNode>() to listOf<KgEdge>())

        val progress = rebuilder.rebuild()

        // Two turns in c1, one in c2 — each turn is one extraction.
        coVerify(exactly = 3) { tool.extract(any()) }
        assertEquals(3, progress.turnsDone)
        assertEquals(2, progress.conversationsDone)
        assertEquals(0, progress.failures)
    }

    /**
     * The provenance written must be the ORIGINAL turn's, not the rebuild's.
     * A node's `sourceTurnId` names the turn that introduced it; stamping the
     * rebuild would overwrite the history this exists to recover.
     */
    @Test
    fun `the original turn's provenance is preserved, not the rebuild's`() = runTest {
        val conv = Conversation(id = "c1").addUser("hi").addAssistant("hello")
        val original = conv.turns.single().timestamp
        coEvery { store.recent(any()) } returns listOf(conv)
        coEvery { tool.extract(any()) } returns (listOf(KgNode(id = "", label = "Elnur", type = NodeType.PERSON)) to emptyList())

        val provenance = slot<ConversationProvenance>()
        coEvery { repository.saveGraph(any(), any(), capture(provenance)) } returns Unit

        rebuilder.rebuild()

        assertEquals("c1", provenance.captured.conversationId)
        assertEquals(original, provenance.captured.turnTimestamp)
    }

    /**
     * One bad conversation out of hundreds must not cost the whole rebuild —
     * it is a long, paid operation and restarting it from zero is expensive.
     */
    @Test
    fun `a failing turn is counted and stepped over`() = runTest {
        coEvery { store.recent(any()) } returns listOf(
            conversation("c1", "one" to "a", "two" to "b", "three" to "c"),
        )
        coEvery { tool.extract(any()) } throws IllegalStateException("model refused") andThen
            (emptyList<KgNode>() to emptyList()) andThen (emptyList<KgNode>() to emptyList())

        val progress = rebuilder.rebuild()

        assertEquals(3, progress.turnsDone, "the rebuild must continue past a failure")
        assertEquals(1, progress.failures)
    }

    @Test
    fun `progress is reported for every turn`() = runTest {
        coEvery { store.recent(any()) } returns listOf(conversation("c1", "a" to "b", "c" to "d"))
        coEvery { tool.extract(any()) } returns (emptyList<KgNode>() to emptyList())

        val seen = mutableListOf<Int>()
        rebuilder.rebuild { seen += it.turnsDone }

        assertEquals(listOf(1, 2), seen)
    }

    @Test
    fun `an empty history is not an error`() = runTest {
        coEvery { store.recent(any()) } returns emptyList()

        val progress = rebuilder.rebuild()

        assertEquals(0, progress.turnsDone)
        assertTrue(progress.failures == 0)
    }
}
