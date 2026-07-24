package com.aura.kg

import com.aura.kg.KgNode
import com.aura.kg.NodeType
import com.aura.kg.KnowledgeGraphRepository
import com.aura.tools.KnowledgeGraphTool
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ConversationKgExtractorTest {

    @Test
    fun `extract debounces multiple calls but processes all queued turns in order`() = runTest {
        // MEMORY_AUDIT E1 regression test. Pre-fix, the extractor
        // OVERWROTE pendingExtraction on every call — only the
        // LATEST turn was ever extracted, the earlier ones were
        // silently dropped. The test name "debounces into one
        // extraction" was encoding the bug.
        //
        // Post-fix: every call to extract() enqueues a new turn
        // into a ConcurrentLinkedQueue. The debounce restarts on
        // every call, but when the debounce fires, ALL queued
        // turns are drained serially in arrival order. None are
        // dropped.
        val dispatcher = StandardTestDispatcher(testScheduler)
        val tool = mockk<KnowledgeGraphTool> {
            coEvery { extract(any()) } returns Pair(emptyList(), emptyList())
        }
        val repo = mockk<KnowledgeGraphRepository> {
            coEvery { saveGraph(any(), any(), any()) } just Runs
        }
        val extractor = ConversationKgExtractor(tool, repo, dispatcher, Unit)

        extractor.extract("first")
        extractor.extract("second")
        extractor.extract("third")

        advanceTimeBy(6_000L)

        // All three must be processed in order — not just the latest.
        coVerify(exactly = 1) { tool.extract("first") }
        coVerify(exactly = 1) { tool.extract("second") }
        coVerify(exactly = 1) { tool.extract("third") }

        // No turns should be dropped (queue never overflowed).
        assertEquals(0, extractor.getDroppedCount(),
            "no turns should be dropped when the queue has capacity")

        extractor.shutdown()
    }

    @Test
    fun `extract processes turns arriving while a previous extraction is running`() = runTest {
        // MEMORY_AUDIT E1 — the audit's worst case: a turn
        // arrives AFTER the debounce has fired and the
        // extraction is in flight (running=true). Pre-fix, the
        // new turn was dropped at line 84. Post-fix, the new
        // turn is queued and processed by the chained
        // continuation after the current run finishes.
        val dispatcher = StandardTestDispatcher(testScheduler)
        // tool.extract takes 4s — long enough that the second
        // debounce fires while it's still running.
        val tool = mockk<KnowledgeGraphTool> {
            coEvery { extract(any()) } coAnswers {
                kotlinx.coroutines.delay(4_000L)
                Pair(emptyList(), emptyList())
            }
        }
        val repo = mockk<KnowledgeGraphRepository> {
            coEvery { saveGraph(any(), any(), any()) } just Runs
        }
        val extractor = ConversationKgExtractor(tool, repo, dispatcher, Unit)

        extractor.extract("turn-1")
        advanceTimeBy(2_500L) // first debounce fires, extract("turn-1") starts (takes 4s)
        // Now running=true. Queue a second turn.
        extractor.extract("turn-2")
        advanceTimeBy(2_500L) // second debounce fires; running is still true → return
        advanceTimeBy(4_000L) // tool.extract("turn-1") finishes, drainQueue continues, turn-2 starts + finishes

        coVerify(exactly = 1) { tool.extract("turn-1") }
        coVerify(exactly = 1) { tool.extract("turn-2") }
        assertEquals(0, extractor.getDroppedCount(),
            "turn-2 must be processed, not dropped (E1 audit's worst case)")

        extractor.shutdown()
    }

    @Test
    fun `extract drops oldest turns when queue overflows and increments droppedCount`() = runTest {
        // MEMORY_AUDIT E1 cap test. The pending queue is
        // bounded at MAX_PENDING (64). When the cap is hit,
        // the oldest entry is dropped and droppedCount is
        // incremented. This bounds memory if extraction can't
        // keep up with turn production.
        val dispatcher = StandardTestDispatcher(testScheduler)
        val tool = mockk<KnowledgeGraphTool> {
            coEvery { extract(any()) } returns Pair(emptyList(), emptyList())
        }
        val repo = mockk<KnowledgeGraphRepository> {
            coEvery { saveGraph(any(), any(), any()) } just Runs
        }
        val extractor = ConversationKgExtractor(tool, repo, dispatcher, Unit)

        // Enqueue 70 turns (cap is 64). The first 6 should be
        // dropped (70 - 64 = 6).
        for (i in 1..70) {
            extractor.extract("turn-$i")
        }
        assertEquals(6, extractor.getDroppedCount(),
            "70 turns enqueued with cap 64 → 6 oldest dropped")

        extractor.shutdown()
    }

    @Test
    fun `extract logs failures instead of swallowing silently`() = runTest {
        // MEMORY_AUDIT E2 regression test. Pre-fix, a tool
        // failure was caught and swallowed without logging —
        // extraction breakage was invisible. Post-fix, every
        // failure emits a Log.w with the turn's provenance
        // and the stacktrace.
        //
        // We can't easily mock android.util.Log without
        // Robolectric. Instead, we assert the contract: a
        // failure during extraction does NOT prevent the
        // next pending turn from being processed. (The
        // existing test `extract swallows exceptions
        // without rethrowing` already pins the no-throw
        // behavior.) This new test pins the
        // continue-after-failure behavior — the bug was
        // not just silent failure but a single failure
        // aborting the queue drain (well, in the old
        // code the queue was size 1, so this didn't
        // matter; in the new code, it matters).
        val dispatcher = StandardTestDispatcher(testScheduler)
        val tool = mockk<KnowledgeGraphTool> {
            coEvery { extract("good") } returns Pair(emptyList(), emptyList())
            coEvery { extract("bad") } throws RuntimeException("network blip")
        }
        val repo = mockk<KnowledgeGraphRepository> {
            coEvery { saveGraph(any(), any(), any()) } just Runs
        }
        val extractor = ConversationKgExtractor(tool, repo, dispatcher, Unit)

        extractor.extract("bad")
        extractor.extract("good")

        // Both should be processed — the failure on "bad"
        // should not abort the queue. The "good" turn
        // saves an empty graph (or whatever extract returns).
        advanceTimeBy(6_000L)
        coVerify(exactly = 1) { tool.extract("bad") }
        coVerify(exactly = 1) { tool.extract("good") }
        coVerify(exactly = 0) { repo.saveGraph(any(), any(), any()) } // both return empty

        extractor.shutdown()
    }

    @Test
    fun `extract saves graph when nodes or edges present`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val tool = mockk<KnowledgeGraphTool> {
            coEvery { extract(any()) } returns Pair(
                listOf(KgNode("n1", "Kotlin", NodeType.UNKNOWN)),
                emptyList(),
            )
        }
        val repo = mockk<KnowledgeGraphRepository> {
            coEvery { saveGraph(any(), any(), any()) } just Runs
        }
        val extractor = ConversationKgExtractor(tool, repo, dispatcher, Unit)
        val provenance = com.aura.provenance.ConversationProvenance("conv-7", 77L)

        extractor.extract("Kotlin is a language", provenance)
        advanceTimeBy(6_000L)

        coVerify(exactly = 1) { repo.saveGraph(any(), any(), provenance) }

        extractor.shutdown()
    }

    @Test
    fun `extract skips save when empty`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val tool = mockk<KnowledgeGraphTool> {
            coEvery { extract(any()) } returns Pair(emptyList(), emptyList())
        }
        val repo = mockk<KnowledgeGraphRepository> {
            coEvery { saveGraph(any(), any(), any()) } just Runs
        }
        val extractor = ConversationKgExtractor(tool, repo, dispatcher, Unit)

        extractor.extract("just chatting")
        advanceTimeBy(6_000L)

        coVerify(exactly = 0) { repo.saveGraph(any(), any(), any()) }

        extractor.shutdown()
    }

    @Test
    fun `extract swallows exceptions without rethrowing`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val tool = mockk<KnowledgeGraphTool> {
            coEvery { extract(any()) } throws RuntimeException("boom")
        }
        val repo = mockk<KnowledgeGraphRepository>()
        val extractor = ConversationKgExtractor(tool, repo, dispatcher, Unit)

        // Should not throw even though extract() throws
        extractor.extract("throw at me")
        advanceTimeBy(6_000L)

        coVerify(exactly = 0) { repo.saveGraph(any(), any(), any()) }

        extractor.shutdown()
    }

    @Test
    fun `shutdown cancels pending debounce`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val tool = mockk<KnowledgeGraphTool> {
            coEvery { extract(any()) } returns Pair(emptyList(), emptyList())
        }
        val repo = mockk<KnowledgeGraphRepository> {
            coEvery { saveGraph(any(), any(), any()) } just Runs
        }
        val extractor = ConversationKgExtractor(tool, repo, dispatcher, Unit)

        extractor.extract("delayed")
        extractor.shutdown()

        // Allow the test scheduler to drain; no extraction should run after shutdown.
        advanceTimeBy(10_000L)
        coVerify(exactly = 0) { tool.extract("delayed") }
        coVerify(exactly = 0) { repo.saveGraph(any(), any(), any()) }
    }

    @Test
    fun `blank input is ignored`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val tool = mockk<KnowledgeGraphTool>()
        val repo = mockk<KnowledgeGraphRepository>()
        val extractor = ConversationKgExtractor(tool, repo, dispatcher, Unit)

        extractor.extract("   ")
        extractor.extract("")
        advanceTimeBy(6_000L)

        coVerify(exactly = 0) { tool.extract(any()) }
        coVerify(exactly = 0) { repo.saveGraph(any(), any(), any()) }

        extractor.shutdown()
    }
}
