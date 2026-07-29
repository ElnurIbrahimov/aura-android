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
        val roots = listOf(
            System.getProperty("user.dir") + "/src/main/kotlin/com/aura/agentrun/AgentRunExecutorWorker.kt",
            System.getProperty("user.dir") + "/aura-core/src/main/kotlin/com/aura/agentrun/AgentRunExecutorWorker.kt",
        )
        for (path in roots) {
            val file = java.io.File(path)
            if (file.exists()) return file.readText()
        }
        throw AssertionError("Could not locate AgentRunExecutorWorker.kt on disk")
    }
}
