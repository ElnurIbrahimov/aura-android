package com.aura.creative

import com.aura.agentrun.AgentRunContextSnapshot
import com.aura.agentrun.AgentRunEntity
import com.aura.agentrun.AgentRunStore
import com.aura.agentrun.StepSpec
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Guards two defects that made these pipelines unable to produce anything.
 *
 * Neither was visible to a unit test before, and neither surfaces as an error
 * the user can read: the first returns `tool_timeout` sixty seconds into a
 * chapter draft, the second silently discards an argument.
 */
class ProductionPipelineEngineTest {

    private val agentRunStore = mockk<AgentRunStore>(relaxed = true)

    private fun engine() = ProductionPipelineEngine(
        appContext = mockk(relaxed = true),
        agentRunStore = agentRunStore,
        capabilityRouter = mockk(relaxed = true),
    )

    private suspend fun scheduleCapturing(
        pipeline: ProductionPipelineEngine.Pipeline,
    ): Pair<String, List<StepSpec>> {
        val metadata = slot<String>()
        val steps = slot<List<StepSpec>>()
        coEvery {
            agentRunStore.createRun(any(), any(), any(), any(), capture(metadata))
        } returns AgentRunEntity(id = "run-1", goalId = "goal-1", triggerType = "USER_QUERY")
        coEvery { agentRunStore.planSteps(any(), capture(steps)) } returns Unit

        // schedule() ends by calling AgentRunExecutorService.enqueue, which
        // reaches WorkManager.getInstance and cannot initialise in a plain JVM
        // test. Both captures happen strictly before that, so the throw is
        // expected and the assertions below are what make this non-vacuous: if
        // schedule() ever fails earlier, the slots stay empty and we say so.
        runCatching { engine().schedule("project-1", pipeline, "a lighthouse keeper who cannot swim") }
        assertTrue(metadata.isCaptured, "${pipeline.name}: createRun was never reached")
        assertTrue(steps.isCaptured, "${pipeline.name}: planSteps was never reached")
        return metadata.captured to steps.captured
    }

    /**
     * `AgentRunContextSnapshot.toolTimeoutMs` defaults to 60_000, and
     * `ToolExecutor` wraps every tool in `withTimeout(ctx.timeout)`. Scheduling
     * without metadata meant a `creative_engine` draft stage — budgeted at
     * 28,672 output tokens — was killed one minute in, every time.
     */
    @Test
    fun `pipeline steps get a tool timeout long enough to draft a chapter`() = runTest {
        val (metadata, _) = scheduleCapturing(ProductionPipelineEngine.Pipeline.NOVEL)

        val snapshot = AgentRunContextSnapshot.fromJson(metadata)
        assertTrue(
            snapshot.toolTimeoutMs > 60_000L,
            "a creative stage cannot finish inside the 60s default (got ${snapshot.toolTimeoutMs}ms)",
        )
        assertEquals(300_000L, snapshot.toolTimeoutMs)
    }

    /**
     * WorkManager's execution window is roughly ten minutes. A tool allowed to
     * consume the whole window would be killed by the system before the worker
     * could record a result or re-enqueue itself.
     */
    @Test
    fun `the tool timeout leaves the worker room to finish and re-enqueue`() = runTest {
        val (metadata, _) = scheduleCapturing(ProductionPipelineEngine.Pipeline.NOVEL)
        val snapshot = AgentRunContextSnapshot.fromJson(metadata)
        assertTrue(
            snapshot.toolTimeoutMs <= 300_000L,
            "must stay well inside WorkManager's ~10 minute window (got ${snapshot.toolTimeoutMs}ms)",
        )
    }

    /**
     * `image_generate` declares prompt/size/negative_prompt and `tts_speak`
     * declares text/voice/play. Neither takes a project id, so
     * `ToolExecutor.parseArgs` logged "Dropped unknown arg" and threw it away —
     * making a discard look like wiring.
     */
    @Test
    fun `media steps send only arguments their tool actually declares`() = runTest {
        val mediaTools = setOf("image_generate", "tts_speak")
        for (pipeline in ProductionPipelineEngine.Pipeline.entries) {
            val (_, steps) = scheduleCapturing(pipeline)
            for (step in steps.filter { it.toolName in mediaTools }) {
                val args = Json.parseToJsonElement(step.toolArgs) as JsonObject
                assertTrue(
                    "projectId" !in args.keys,
                    "${pipeline.name}/${step.toolName} still sends projectId, which its schema does not declare",
                )
                assertTrue("prompt" in args.keys, "${pipeline.name}/${step.toolName} must send a prompt")
            }
        }
    }

    /** Every step's arguments must be parseable JSON — they are hand-built strings. */
    @Test
    fun `every scheduled step carries valid json arguments`() = runTest {
        for (pipeline in ProductionPipelineEngine.Pipeline.entries) {
            val (_, steps) = scheduleCapturing(pipeline)
            assertTrue(steps.isNotEmpty(), "${pipeline.name} scheduled no steps")
            for (step in steps) {
                val parsed = runCatching { Json.parseToJsonElement(step.toolArgs) }
                assertTrue(
                    parsed.isSuccess,
                    "${pipeline.name}/${step.toolName} built invalid JSON: ${step.toolArgs}",
                )
            }
        }
    }
}
