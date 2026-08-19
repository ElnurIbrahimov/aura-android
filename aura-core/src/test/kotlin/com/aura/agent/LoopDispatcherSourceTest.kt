package com.aura.agent

import org.junit.Test
import kotlin.test.assertTrue

/**
 * Both of the loop's public flows carry a `flowOn`.
 *
 * A source scan because there is no runtime handle on this: `run()` takes 38 dependencies
 * and 13 parameters, and the thing being asserted is a property of the flow's declaration
 * rather than of any value it produces. [LoopDispatcherTest] pins what `flowOn` does; this
 * pins that the loop has one.
 *
 * Uses [sourceDir] and [requireNonEmpty] for the reason their KDoc gives — a source scan
 * that finds nothing passes every emptiness assertion it makes.
 */
class LoopDispatcherSourceTest {

    @Test
    fun `run and resumeAfterGate both leave the collector's thread`() {
        val source = sourceDir("src/main/kotlin/com/aura/agent")
            .resolve("MemoryAugmentedAgenticLoop.kt")
        assertTrue(source.isFile, "loop source not found at ${source.absolutePath}")
        val text = source.readText()

        val builders = Regex("""\): Flow<AgentEvent> = flow \{""").findAll(text).toList()
            .map { it.range.first }
            .requireNonEmpty("Flow<AgentEvent> builders in the loop")

        assertTrue(
            builders.size == 2,
            "expected run() and resumeAfterGate(); found ${builders.size} flow builders — " +
                "a new one needs its own flowOn and this assertion updating",
        )
        assertTrue(
            Regex("""\.flowOn\(Dispatchers\.\w+\)""").findAll(text).count() == 2,
            "the loop is missing a flowOn: recall, context assembly and RRF would run on " +
                "whatever collects it, which is Main",
        )
    }
}
