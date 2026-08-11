package com.aura.agentrun

import org.junit.Test
import kotlin.test.assertTrue

/**
 * Regression: independent DAG-ready steps must execute concurrently.
 * The implementation uses coroutineScope { ready.map { async { ... } }.awaitAll() }.
 */
class AgentRunExecutorWorkerParallelContractTest {

    @Test
    fun `worker source uses coroutineScope and awaitAll for ready steps`() {
        val text = sourceFromDisk()
        assertTrue(text.contains("coroutineScope {"), "worker must launch a coroutine scope")
        assertTrue(text.contains("async {"), "worker must use async for parallel steps")
        assertTrue(text.contains(".awaitAll()"), "worker must await all parallel steps")
    }

    private fun sourceFromDisk(): String {
        // sourceDir is the repo's one way of resolving a source path from a test,
        // and it fails loudly rather than falling through. Hand-rolled user.dir
        // candidate lists are how NavigationReachabilityTest ended up with a
        // hardcoded machine path and a bare `return` — see ENGINEERING_HISTORY §2.6.
        val file = com.aura.agent.sourceDir("src/main/kotlin/com/aura/agentrun")
            .resolve("AgentRunExecutorWorker.kt")
        check(file.isFile) { "AgentRunExecutorWorker.kt not found at ${file.absolutePath}" }
        return file.readText()
    }
}
