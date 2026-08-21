package com.aura.agent

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertTrue

/**
 * A tool that calls another tool must not need a slot it cannot get.
 *
 * `ToolExecutor` caps tool bodies at eight of the shared IO pool, and the
 * dispatch parks its thread rather than suspending — `runInterruptible` wraps a
 * `runBlocking`, deliberately, so a blocking tool can be interrupted on
 * timeout. Several tools call `execute` again from inside their own body:
 * `delegate_to_agent` is the obvious one, and hands and the realtime bridge
 * reach it too.
 *
 * So eight concurrent delegations could hold all eight slots while each waited
 * for a ninth. Every one then failed its budget and returned `tool_timeout`,
 * which reads as a slow model rather than a dispatcher eating itself.
 *
 * This runs more nested calls than there are slots.
 *
 * The timeout is on the JUnit annotation, not only inside the test. Before the
 * fix this does not merely fail, it hangs: `withTimeout` cannot cancel a thread
 * parked in `runBlocking` waiting for a dispatcher slot, which is the same
 * property that makes the deadlock a deadlock. Verified by disabling the fix
 * and watching a five-minute build produce no result at all. A regression that
 * hangs CI is a regression nobody reads, so the annotation runs this on its own
 * thread and turns the hang into a failure.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class NestedToolCallDoesNotStarveTest {

    private val registry = ToolRegistry()

    private val executor = ToolExecutor(
        registry = registry,
        context = ApplicationProvider.getApplicationContext<Context>(),
    )

    private fun leafTool() = Tool(
        name = "leaf",
        description = "does nothing, quickly",
        risk = ToolRisk.READ_ONLY,
        execute = { _, _ -> ToolResult.Ok("leaf done") },
    )

    /** Calls back into the executor from inside its own body. */
    private fun nestingTool() = Tool(
        name = "nesting",
        description = "calls leaf",
        risk = ToolRisk.READ_ONLY,
        execute = { _, ctx -> executor.execute("leaf", "{}", ctx) },
    )

    @Test(timeout = 60_000)
    fun `more concurrent nested calls than there are tool slots still all complete`() = runBlocking {
        registry.register(leafTool())
        registry.register(nestingTool())
        val ctx = ToolContext(conversationId = "nested")

        // Well past the cap of eight. Every one of these holds a slot while
        // needing another.
        val results = withTimeout(60_000) {
            coroutineScope {
                (1..24).map { async { executor.execute("nesting", "{}", ctx) } }.awaitAll()
            }
        }

        val failures = results.filterIsInstance<ToolResult.Error>()
        assertTrue(
            failures.isEmpty(),
            "nested calls did not all succeed: ${failures.map { it.code + ":" + it.message }}",
        )
        assertTrue(results.all { it is ToolResult.Ok && it.output == "leaf done" })
    }
}
