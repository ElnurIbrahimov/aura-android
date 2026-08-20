package com.aura.agent

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertTrue

/**
 * The loop must not do its thinking on the thread that collects it.
 *
 * `MemoryAugmentedAgenticLoop.run()` was a bare `flow { }` with no `flowOn`, collected by
 * `ChatSendController` in `viewModelScope` — which is Main. Everything before the first
 * emission runs on the collector's thread, and that is BM25 scoring, cosine similarity
 * against every candidate embedding, and reciprocal-rank fusion. `MemoryStore` has zero
 * `withContext` of its own, so none of it moved off.
 *
 * Asserting the dispatcher of a 1,300-line function with 38 dependencies is not something
 * a unit test can do directly. What it can do is pin the property that makes the fix work
 * and the mechanism that shows it: a `flowOn` moves upstream work to a real dispatcher, so
 * a blocking sleep upstream costs wall-clock on another thread and leaves the collector
 * free — while without one it blocks the caller outright.
 *
 * `LoopDispatcherSourceTest` guards that the loop actually carries the `flowOn`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LoopDispatcherTest {

    @Test
    fun `flowOn moves blocking upstream work off the collecting thread`() = runTest {
        val collectingThread = Thread.currentThread().name
        var producedOn = ""

        val values = flow {
            producedOn = Thread.currentThread().name
            Thread.sleep(50)  // stands in for cosine over a few hundred embeddings
            emit(1)
        }.flowOn(Dispatchers.Default).toList()

        assertTrue(values == listOf(1))
        assertTrue(
            producedOn != collectingThread,
            "upstream ran on the collector's thread ($collectingThread) — flowOn did nothing",
        )
    }
}
